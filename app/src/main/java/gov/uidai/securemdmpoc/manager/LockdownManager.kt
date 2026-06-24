package gov.uidai.securemdmpoc.manager

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log

class LockdownManager(
    private val context: Context,
    private val deviceOwner: DeviceOwnerContext,
    private val dynamicAppManager: DynamicAppManager,
    private val bluetoothBlockManager: BluetoothBlockManager
) {
    private val dpm get() = deviceOwner.dpm
    private val admin get() = deviceOwner.admin
    val isDeviceOwner get() = deviceOwner.isDeviceOwner

    /**
     * User restrictions this manager owns and toggles together as
     * a unit in applyAllPolicies()/restoreDeviceToNormal().
     * Bluetooth related restrictions live in BluetoothBlockManager instead —
     * this list is intentionally everything else.
     */
    private val managedRestrictions: List<String> = buildList {
        add(UserManager.DISALLOW_DEBUGGING_FEATURES)
        add(UserManager.DISALLOW_USB_FILE_TRANSFER)
        add(UserManager.DISALLOW_FACTORY_RESET)
        add(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
        add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
        }
    }

    // ── Apply all policies ───────────────────────────────────

    fun applyAllPolicies() {
        if (!isDeviceOwner) {
            Log.w(TAG, "Not device owner — skipping policies")
            return
        }

        setupLockTask()
        dynamicAppManager.applyDynamicRestrictions()
        applyManagedRestrictions()
        bluetoothBlockManager.applyBluetoothBlock()
        disableScreenCapture()
        Log.d(TAG, "All policies applied")
    }

    fun restoreDeviceToNormal() {
        if (!isDeviceOwner) return
        Log.d(TAG, "Restoring device to normal state")

        dynamicAppManager.restoreAll()
        bluetoothBlockManager.restoreBluetooth()

        dpm.setScreenCaptureDisabled(admin, false)
        clearManagedRestrictions()
        dpm.setLockTaskPackages(admin, emptyArray())

        // POINT OF NO RETURN
        dpm.clearDeviceOwnerApp(context.packageName)

        Log.d(TAG, "Device restored to normal")
    }

    // ── Restriction batch helpers ─────────────────────────────

    private fun applyManagedRestrictions() {
        if (!isDeviceOwner) return
        managedRestrictions.forEach { restriction ->
            try {
                dpm.addUserRestriction(admin, restriction)
            } catch (e: Exception) {
                Log.w(TAG, "Could not apply restriction $restriction: ${e.message}")
            }
        }
        Log.d(TAG, "Managed restrictions applied: $managedRestrictions")
    }

    private fun clearManagedRestrictions() {
        managedRestrictions.forEach { restriction ->
            try {
                dpm.clearUserRestriction(admin, restriction)
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear restriction $restriction: ${e.message}")
            }
        }
        Log.d(TAG, "Managed restrictions cleared")
    }

    // ── Lock task ────────────────────────────────────────────

    fun setupLockTask() {
        if (!isDeviceOwner) return
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        }
        Log.d(TAG, "Lock task configured")
    }

    // ── Screen capture ───────────────────────────────────────

    fun disableScreenCapture() {
        if (!isDeviceOwner) return
        dpm.setScreenCaptureDisabled(admin, true)
        Log.d(TAG, "Screen capture disabled")
    }

    fun setScreenCapture(enabled: Boolean) {
        if (!isDeviceOwner) return
        dpm.setScreenCaptureDisabled(admin, !enabled)
        Log.d(TAG, "Screen capture disabled: ${!enabled}")
    }

    // ── Kiosk mode toggle ────────────────────────────────────

    private fun enableKioskMode() {
        if (!isDeviceOwner) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        }
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        Log.d(TAG, "Kiosk mode enabled")
    }

    private fun disableKioskMode() {
        if (!isDeviceOwner) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(
                admin,
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                        DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                        DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                        DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
            )
        }
        Log.d(TAG, "Kiosk mode disabled")
    }

    fun setKioskMode(enabled: Boolean) {
        if (enabled) enableKioskMode() else disableKioskMode()
        Log.d(TAG, "Kiosk mode set to: $enabled")
    }

    companion object {
        private const val TAG = "LockdownManager"
    }
}