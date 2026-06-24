package gov.uidai.securemdmpoc.receivers

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import gov.uidai.securemdmpoc.DeviceErrorReporter
import gov.uidai.securemdmpoc.PolicyEnforcementService
import gov.uidai.securemdmpoc.data.repository.UpdateRepository
import gov.uidai.securemdmpoc.manager.LockdownManager
import gov.uidai.securemdmpoc.manager.PolicyController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject
import kotlin.getValue

class UpdateInstallReceiver : BroadcastReceiver() {
    private val policyController: PolicyController by inject(PolicyController::class.java)
    private val updateRepository: UpdateRepository by inject(UpdateRepository::class.java)

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.d(TAG, "✅ APK installed successfully")

                val isOwner = policyController.isDeviceOwner

                if (isOwner) {
                    Log.d(TAG, "✅ Device Owner confirmed after upgrade")

                    val pendingResult = goAsync()

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            policyController.applyAllPolicies()
                            Log.d(TAG, "✅ Policies re-applied after upgrade")

                            PolicyEnforcementService.safeStartPolicyService(context)

                            val newVersion = context.packageManager
                                .getPackageInfo(context.packageName, 0)
                                .versionName ?: "unknown"

                            reportSuccess(
                                context = context,
                                message = "APK installed successfully — v$newVersion",
                                step = "UpdateInstallReceiver — STATUS_SUCCESS"
                            )
                        } finally {
                            pendingResult.finish()
                        }
                    }
                } else {
                    Log.e(TAG, "❌ CRITICAL — Device Owner lost after upgrade")
                    DeviceErrorReporter.report(
                        context,
                        errorType = DeviceErrorReporter.ERROR_DEVICE_OWNER_LOST,
                        errorMessage = "Device Owner lost after APK upgrade",
                        step = "UpdateInstallReceiver — STATUS_SUCCESS"
                    )
                }
                context.cacheDir
                    .listFiles { f -> f.name == "update.apk" }
                    ?.forEach { it.delete() }
            }

            else -> {
                Log.e(TAG, "❌ Install failed: $status — $message")
                DeviceErrorReporter.report(
                    context,
                    errorType = DeviceErrorReporter.ERROR_UPDATE_INSTALL,
                    errorMessage = "PackageInstaller failed: status=$status message=$message",
                    step = "UpdateInstallReceiver — status=$status"
                )
            }
        }
    }

    private fun reportSuccess(
        context: Context,
        message: String,
        step: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val model = "${Build.MANUFACTURER} ${Build.MODEL}"
                val versionName = context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .versionName ?: "unknown"

                val versionCode = context.packageManager.getPackageInfo(
                    context.packageName,
                    0
                ).longVersionCode.toInt()

                updateRepository.reportSuccess(
                    UpdateSuccessReport(
                        packageName = context.packageName,
                        model = model,
                        message = message,
                        versionName = versionName,
                        versionCode = versionCode,
                        step = step,
                        timestamp = System.currentTimeMillis()
                    )
                )

                Log.d(TAG, "✅ Success reported to backend")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to report success: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "UpdateInstallReceiver"
    }
}

data class UpdateSuccessReport(
    val packageName: String,
    val model: String,
    val message: String,
    val versionName: String,
    val versionCode: Int,
    val step: String,
    val timestamp: Long
)