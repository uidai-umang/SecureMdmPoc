package gov.uidai.securemdmpoc

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import gov.uidai.securemdmpoc.data.prefs.SharedPreferences
import gov.uidai.securemdmpoc.data.repository.DeviceRepository
import gov.uidai.securemdmpoc.manager.BluetoothBlockManager
import gov.uidai.securemdmpoc.manager.DeviceOwnerContext
import gov.uidai.securemdmpoc.manager.DynamicAppManager
import gov.uidai.securemdmpoc.manager.LockdownManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MyFirebaseMessagingService : FirebaseMessagingService() {
    private val sharedPref: SharedPreferences by inject()
    private val lockdown: LockdownManager by inject()
    private val dynamicAppManager: DynamicAppManager by inject()
    private val deviceRepository: DeviceRepository by inject()
    private val bluetoothBlockManager: BluetoothBlockManager by inject()
    private val deviceOwnerContext: DeviceOwnerContext by inject()

    private val updateChecker: UpdateChecker by inject()

    // Add at top of class
    private val appManagementScope = CoroutineScope(
        Dispatchers.IO.limitedParallelism(1) +
                kotlinx.coroutines.SupervisorJob()
    )

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val action = data["action"] ?: run {
            Log.w(TAG, "FCM message with no action — ignoring")
            return
        }

        // Check if message targets a specific device
        val targetDevice = data["targetDevice"]
        if (targetDevice != null) {
            val myModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            if (targetDevice != myModel) {
                Log.d(TAG, "FCM for $targetDevice — ignoring on $myModel")
                return
            }
        }

        confirmFcmReceived(action)

        Log.d(TAG, "FCM received — action: $action data: $data")

        when (action) {
            "SET_POLICY" -> handleSetPolicy(data)
            "RESTORE_DEVICE" -> handleRestore()
            "CHECK_UPDATE" -> handleCheckUpdate()
            "HIDE_APPS" -> handleHideApps()
            "UNHIDE_APPS" -> handleUnhideApps()
            "HIDE_APP" -> handleHideApp(data)
            "UNHIDE_APP" -> handleUnhideApp(data)
            "BLOCK_BLUETOOTH" -> applyBluetoothLock()
            "UNBLOCK_BLUETOOTH" -> restoreBluetooth()
            "DEBUG_CHECK_PERMISSION" -> handleDebugCheckPermission(data)
            else -> Log.w(TAG, "Unknown action: $action")
        }
    }

    // Called when FCM token changes — send to backend
    // onNewToken
    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM token refreshed")
        sharedPref.fcmToken = token
        FirebaseMessaging.getInstance().subscribeToTopic("all-devices")

        sendTokenToBackend(token)
    }

    // ── Policy handler ────────────────────────────────────
    private fun handleSetPolicy(data: Map<String, String>) {
        data["screenshotEnabled"]?.let {
            val enabled = it.toBoolean()
            lockdown.setScreenCapture(enabled)
        }

        data["kioskEnabled"]?.let {
            val enabled = it.toBoolean()
            sharedPref.kioskEnabled = enabled // persist state
            lockdown.setKioskMode(enabled) // notify lockdown manager
            sendLocalBroadcast(enabled) // Notify MainActivity to start/stop lock task
        }

        data["cameraEnabled"]?.let {
            val enabled = it.toBoolean()
            if (!enabled) lockdown.applyCameraPolicy()

            Log.d(TAG, "Camera policy applied: $enabled")
        }
    }

    // ── Restore handler ───────────────────────────────────
    private fun handleRestore() {
        Log.d(TAG, "Remote restore triggered")
        lockdown.restoreDeviceToNormal()
    }

    // ── OTA update handler ────────────────────────────────

    private fun handleCheckUpdate() {
        Log.d(TAG, "OTA update check triggered")
        appManagementScope.launch {

            val updateInfo = updateChecker.checkForUpdate() ?: run {
                Log.d(TAG, "No update available")
                return@launch
            }
            Log.d(TAG, "Update found: v${updateInfo}")
            val apkFile = updateChecker.downloadApk(
                updateInfo.apkUrl!!
            ) { progress ->
                Log.d(TAG, "Download: $progress%")
            } ?: return@launch

            updateChecker.installApkSilently(apkFile)
        }
    }

    private fun handleHideApps() {
        Log.d(TAG, "HIDE_APPS triggered via FCM")
        appManagementScope.launch {
            try {
                val report = dynamicAppManager.applyDynamicRestrictions()
                Log.d(TAG, "HIDE_APPS complete — hidden:${report.hiddenCount}")
            } catch (e: Exception) {
                Log.e(TAG, "HIDE_APPS failed: ${e.message}")
                DeviceErrorReporter.report(
                    applicationContext,
                    errorType = "HIDE_APPS_FAILED",
                    errorMessage = e.message ?: "Unknown error",
                    step = "handleHideApps",
                    stackTrace = e.stackTraceToString()
                )
            }
        }
    }

    private fun handleUnhideApps() {
        Log.d(TAG, "UNHIDE_APPS triggered via FCM")
        appManagementScope.launch {
            try {
                dynamicAppManager.restoreAll()
                Log.d(TAG, "UNHIDE_APPS complete")
            } catch (e: Exception) {
                Log.e(TAG, "UNHIDE_APPS failed: ${e.message}")
                DeviceErrorReporter.report(
                    applicationContext,
                    errorType = "UNHIDE_APPS_FAILED",
                    errorMessage = e.message ?: "Unknown error",
                    step = "handleUnhideApps",
                    stackTrace = e.stackTraceToString()
                )
            }
        }
    }


    private fun handleHideApp(data: Map<String, String>) {
        val packageName = data["packageName"] ?: run {
            Log.w(TAG, "HIDE_APP — no packageName in payload")
            return
        }
        appManagementScope.launch {
            dynamicAppManager.hideSingleApp(packageName)
        }
    }

    private fun handleUnhideApp(data: Map<String, String>) {
        val packageName = data["packageName"] ?: run {
            Log.w(TAG, "UNHIDE_APP — no packageName in payload")
            return
        }
        appManagementScope.launch {
            dynamicAppManager.unhideSingleApp(packageName)
        }
    }

    private fun handleDebugCheckPermission(data: Map<String, String>) {
        val pkg = data["packageName"] ?: return
        val permission = data["permission"] ?: android.Manifest.permission.ACCESS_FINE_LOCATION

        val dpm = deviceOwnerContext.dpm
        val admin = deviceOwnerContext.admin

        val isSystem = try {
            val appInfo = applicationContext.packageManager.getApplicationInfo(pkg, 0)
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            DeviceErrorReporter.report(
                applicationContext,
                errorType = "DEBUG_CHECK_PERMISSION_RESULT",
                errorMessage = "Package not found: $pkg",
                step = "handleDebugCheckPermission"
            )
            return
        }

        val state = try {
            dpm.getPermissionGrantState(admin, pkg, permission)
        } catch (e: Exception) {
            DeviceErrorReporter.report(
                applicationContext,
                errorType = "DEBUG_CHECK_PERMISSION_RESULT",
                errorMessage = "getPermissionGrantState failed: ${e.message}",
                step = "handleDebugCheckPermission"
            )
            return
        }

        val label = when (state) {
            android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED -> "DENIED"
            android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED -> "GRANTED"
            android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT -> "DEFAULT"
            else -> "UNKNOWN($state)"
        }

        DeviceErrorReporter.report(
            applicationContext,
            errorType = "DEBUG_CHECK_PERMISSION_RESULT",
            errorMessage = "isSystem=$isSystem | $pkg / $permission -> $label",
            step = "handleDebugCheckPermission"
        )
    }


    // ── Kiosk broadcast to MainActivity ──────────────────

    private fun sendLocalBroadcast(kioskEnabled: Boolean) {
        val intent = Intent(KioskModeReceiver.ACTION).apply {
            setPackage(packageName)
            putExtra("enabled", kioskEnabled)
        }
        applicationContext.sendBroadcast(intent)
    }

    // ── Send FCM token to backend ─────────────────────────
    private fun sendTokenToBackend(token: String) {
        appManagementScope.launch {
            try {
                // Token sent to backend
                deviceRepository.updateToken(token)
                Log.d(TAG, "Token to send: ${token.take(20)}...")
            } catch (e: Exception) {
                Log.e(TAG, "Token send failed: ${e.message}")
            }
        }
    }

    // confirmFcmReceived
    private fun confirmFcmReceived(action: String) {
        appManagementScope.launch {
            deviceRepository.confirmFcm(action)
        }
    }

    private fun applyBluetoothLock() {
        appManagementScope.launch { bluetoothBlockManager.applyBluetoothBlock() }
    }

    private fun restoreBluetooth() {
        appManagementScope.launch { bluetoothBlockManager.restoreBluetooth() }
    }

    companion object {
        private const val TAG = "FCMService"
    }
}