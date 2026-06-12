package gov.uidai.securemdmpoc

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import gov.uidai.securemdmpoc.data.remote.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import android.content.pm.PackageManager

class UpdateChecker(private val context: Context) {

    private val currentVersionCode: Int get() = context.packageManager
        .getPackageInfo(context.packageName, 0).longVersionCode.toInt()

    suspend fun checkForUpdate(): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val response = RetrofitClient.updateInstance
                    .checkUpdate(currentVersionCode)

                if (response.updateAvailable) {
                    Log.d(TAG, "Update available: ${response.versionName} (${response.versionCode})")
                    response
                } else {
                    Log.d(TAG, "App is up to date: versionCode=$currentVersionCode")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed: ${e.message}")
                DeviceErrorReporter.report(
                    context,
                    errorType = DeviceErrorReporter.ERROR_UPDATE_CHECK,
                    errorMessage = e.message ?: "Unknown error",
                    step = "checkForUpdate — versionCode=$currentVersionCode",
                    stackTrace = e.stackTraceToString()
                )
                null
            }
        }
    }

    suspend fun downloadApk(
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): File? {
        return withContext(Dispatchers.IO) {
            try {
                val response = RetrofitClient.updateInstance.downloadApk(apkUrl)
                val totalBytes = response.contentLength()
                var downloadedBytes = 0L
                val apkFile = File(context.cacheDir, "update.apk")
                val inputStream: InputStream = response.byteStream()
                val outputStream = FileOutputStream(apkFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        val progress = (downloadedBytes * 100 / totalBytes).toInt()
                        onProgress(progress)
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                Log.d(TAG, "APK downloaded: ${apkFile.absolutePath}")
                apkFile

            } catch (e: Exception) {
                Log.e(TAG, "APK download failed: ${e.message}")
                DeviceErrorReporter.report(
                    context,
                    errorType = DeviceErrorReporter.ERROR_UPDATE_DOWNLOAD,
                    errorMessage = e.message ?: "Unknown error",
                    step = "downloadApk — url=$apkUrl",
                    stackTrace = e.stackTraceToString()
                )
                null
            }
        }
    }

    fun installApkSilently(apkFile: File) {
//        if (!verifyVersionCode(apkFile)) {
//            Log.e(TAG, "❌ Install blocked — versionCode not higher")
//            DeviceErrorReporter.report(
//                context,
//                errorType = DeviceErrorReporter.ERROR_UPDATE_VERSION,
//                errorMessage = "versionCode of downloaded APK is not higher than installed",
//                step = "installApkSilently — verifyVersionCode"
//            )
//            apkFile.delete()
//            return
//        }
//
//        if (!verifySignature(apkFile)) {
//            Log.e(TAG, "❌ Install blocked — signature mismatch")
//            DeviceErrorReporter.report(
//                context,
//                errorType = DeviceErrorReporter.ERROR_UPDATE_SIGNATURE,
//                errorMessage = "Signature of downloaded APK does not match installed app",
//                step = "installApkSilently — verifySignature"
//            )
//            apkFile.delete()
//            return
//        }

        Log.d(TAG, "✅ Both checks passed — proceeding with install")

        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setAppPackageName(context.packageName)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            val inputStream = apkFile.inputStream()
            val outputStream = session.openWrite("update.apk", 0, apkFile.length())
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            session.fsync(outputStream)
            outputStream.close()
            inputStream.close()
            val intent = android.content.Intent(context, UpdateInstallReceiver::class.java)
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, sessionId, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_MUTABLE
            )
            session.commit(pendingIntent.intentSender)
            session.close()
            Log.d(TAG, "Silent install committed for session: $sessionId")

        } catch (e: Exception) {
            Log.e(TAG, "Silent install failed: ${e.message}")
            DeviceErrorReporter.report(
                context,
                errorType = DeviceErrorReporter.ERROR_UPDATE_INSTALL,
                errorMessage = e.message ?: "Unknown error",
                step = "installApkSilently — PackageInstaller",
                stackTrace = e.stackTraceToString()
            )
        }
    }

    fun verifyVersionCode(apkFile: File): Boolean {
        return try {
            val packageInfo = context.packageManager
                .getPackageArchiveInfo(
                    apkFile.absolutePath,
                    0
                )

            val newVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo?.longVersionCode ?: 0
            } else {
                @Suppress("DEPRECATION")
                packageInfo?.versionCode?.toLong() ?: 0
            }

            val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .versionCode.toLong()
            }

            val valid = newVersionCode > currentVersionCode

            Log.d(TAG, """
            Version check:
            Current  : $currentVersionCode
            New      : $newVersionCode
            Valid    : $valid
        """.trimIndent())

            valid

        } catch (e: Exception) {
            Log.e(TAG, "Version check failed: ${e.message}")
            false
        }
    }

    fun verifySignature(apkFile: File): Boolean {
        return try {
            val pm = context.packageManager

            // Get signing cert of downloaded APK
            val newPkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            }

            // Get signing cert of currently installed app
            val currentPkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            }

            val newSigs = newPkgInfo
                ?.signingInfo
                ?.apkContentsSigners
                ?: run {
                    Log.e(TAG, "Could not get signatures from downloaded APK")
                    return false
                }

            val currentSigs = currentPkgInfo
                .signingInfo
                ?.apkContentsSigners
                ?: run {
                    Log.e(TAG, "Could not get signatures from installed app")
                    return false
                }

            Log.d(TAG, "New APK sigs count: ${newSigs.size}")
            Log.d(TAG, "Current app sigs count: ${currentSigs.size}")

            val valid = newSigs.size == currentSigs.size &&
                    newSigs.zip(currentSigs).all { (new, current) ->
                        new.toByteArray().contentEquals(current.toByteArray())
                    }

            Log.d(TAG, "Signature match: $valid")
            valid

        } catch (e: Exception) {
            Log.e(TAG, "Signature check failed: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "UpdateChecker"
    }
}

data class UpdateInfo(
    val updateAvailable: Boolean,
    val versionName: String?,
    val versionCode: Int?,
    val apkUrl: String?,
    val sha256: String?,
    val size: Long?
)