package gov.uidai.securemdmpoc.manager

import android.content.Context
import android.util.Log
import gov.uidai.securemdmpoc.DeviceErrorReporter
import gov.uidai.securemdmpoc.data.model.RestrictionReport

/**
 * Single entry point for ALL policy/manager operations.
 *
 * No other class — Service, Receiver, ViewModel — should inject
 * LockdownManager, DynamicAppManager, BluetoothBlockManager, or
 * StorageDefenceManager directly. They inject ONLY PolicyController,
 * which owns and delegates to all of them internally.
 *
 * This keeps the manager graph private to this one class — adding
 * a new manager later never requires touching every call site again.
 */
class PolicyController(
    private val context: Context,
    private val lockdown: LockdownManager,
    private val dynamicAppManager: DynamicAppManager,
    private val bluetoothBlockManager: BluetoothBlockManager,
    private val storageDefenceManager: StorageDefenceManager,
    private val deviceOwnerContext: DeviceOwnerContext
) {
    private val TAG = "PolicyController"

    val isDeviceOwner: Boolean
        get() = deviceOwnerContext.isDeviceOwner

    // ── Lifecycle / bulk policy ────────────────────────────────

    fun applyAllPolicies() = safe("applyAllPolicies") {
        lockdown.applyAllPolicies()
    }

    fun restoreDeviceToNormal() = safe("restoreDeviceToNormal") {
        lockdown.restoreDeviceToNormal()
    }

    // ── Kiosk ───────────────────────────────────────────────────

    fun setKioskMode(enabled: Boolean) = safe("setKioskMode") {
        lockdown.setKioskMode(enabled)
    }

    fun setScreenCapture(enabled: Boolean) = safe("setScreenCapture") {
        lockdown.setScreenCapture(enabled)
    }

    // ── App hide/unhide (bulk) ───────────────────────────────────

    fun applyDynamicRestrictions(): RestrictionReport? = safeReturn("applyDynamicRestrictions") {
        dynamicAppManager.applyDynamicRestrictions()
    }

    fun restoreAllApps() = safe("restoreAllApps") {
        dynamicAppManager.restoreAll()
    }

    // ── App hide/unhide (single) ─────────────────────────────────

    fun hideSingleApp(packageName: String) = safe("hideSingleApp") {
        dynamicAppManager.hideSingleApp(packageName)
    }

    fun unhideSingleApp(packageName: String) = safe("unhideSingleApp") {
        dynamicAppManager.unhideSingleApp(packageName)
    }

    // ── Per-package policy (called on fresh install) ─────────────

    fun applyPolicyForNewPackage(packageName: String) = safe("applyPolicyForNewPackage") {
        dynamicAppManager.denyCameraForPackage(packageName)
        dynamicAppManager.denyStoragePermissions(packageName)
        bluetoothBlockManager.denyBluetoothAndNearbyForPackage(packageName)
    }

    // ── Bluetooth ──────────────────────────────────────────────

    fun blockBluetooth() = safe("blockBluetooth") {
        bluetoothBlockManager.applyBluetoothBlock()
    }

    fun unblockBluetooth() = safe("unblockBluetooth") {
        bluetoothBlockManager.restoreBluetooth()
    }

    // ── Storage defence ────────────────────────────────────────

    fun startStorageDetection() = safe("startStorageDetection") {
        storageDefenceManager.startDetection()
    }

    fun stopStorageDetection() = safe("stopStorageDetection") {
        storageDefenceManager.stopDetection()
    }

    fun grantNotificationPermission(packageName: String) = safe("grantNotificationPermission") {
        deviceOwnerContext.dpm.setPermissionGrantState(
            deviceOwnerContext.admin,
            packageName,
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
        )
        Log.d(TAG, "POST_NOTIFICATIONS granted for $packageName")
    }

    // ── Debug helpers ──────────────────────────────────────────

    fun debugCheckPermission(packageName: String, permission: String): String {
        val dpm = deviceOwnerContext.dpm
        val admin = deviceOwnerContext.admin

        val isSystem = try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            return "Package not found: $packageName"
        }

        val state = try {
            dpm.getPermissionGrantState(admin, packageName, permission)
        } catch (e: Exception) {
            return "getPermissionGrantState failed: ${e.message}"
        }

        val label = when (state) {
            android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED -> "DENIED"
            android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED -> "GRANTED"
            android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT -> "DEFAULT"
            else -> "UNKNOWN($state)"
        }

        return "isSystem=$isSystem | $packageName / $permission -> $label"
    }

    fun debugCheckRestrictions(): String {
        val dpm = deviceOwnerContext.dpm
        val admin = deviceOwnerContext.admin

        val restrictions = try {
            dpm.getUserRestrictions(admin)
        } catch (e: Exception) {
            return "getUserRestrictions failed: ${e.message}"
        }

        val hasBluetooth = restrictions.containsKey(android.os.UserManager.DISALLOW_BLUETOOTH)
        val hasSharing = restrictions.containsKey(android.os.UserManager.DISALLOW_BLUETOOTH_SHARING)
        val hasBeam = restrictions.containsKey("no_outgoing_beam")

        return "DISALLOW_BLUETOOTH=$hasBluetooth | DISALLOW_BLUETOOTH_SHARING=$hasSharing | no_outgoing_beam=$hasBeam | all=${restrictions.keySet()}"
    }

    // ── Internal error-handling wrappers ──────────────────────────
    // Every public method funnels failures through ONE reporting path,
    // instead of every caller (FCM service, receivers) needing its
    // own try/catch + DeviceErrorReporter boilerplate.

    private inline fun safe(step: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "$step failed: ${e.message}")
            DeviceErrorReporter.report(
                context,
                errorType = "${step.uppercase()}_FAILED",
                errorMessage = e.message ?: "Unknown error",
                step = "PolicyController.$step",
                stackTrace = e.stackTraceToString()
            )
        }
    }

    private inline fun <T> safeReturn(step: String, block: () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "$step failed: ${e.message}")
            DeviceErrorReporter.report(
                context,
                errorType = "${step.uppercase()}_FAILED",
                errorMessage = e.message ?: "Unknown error",
                step = "PolicyController.$step",
                stackTrace = e.stackTraceToString()
            )
            null
        }
    }

    companion object {
        private const val TAG = "PolicyController"
    }
}