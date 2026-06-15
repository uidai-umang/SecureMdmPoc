package gov.uidai.securemdmpoc.manager

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log

class LockdownManager(private val context: Context) {
    private val OUR_PACKAGE = context.packageName

    private val dpm = context.getSystemService(
        Context.DEVICE_POLICY_SERVICE
    ) as DevicePolicyManager

    private val admin = ComponentName(
        context,
        gov.uidai.securemdmpoc.MyDeviceAdminReceiver::class.java
    )

    val isDeviceOwner: Boolean
        get() = dpm.isDeviceOwnerApp(context.packageName)

    // ── Apply all policies ───────────────────────────────────

    fun applyAllPolicies() {
        if (!isDeviceOwner) {
            Log.w(TAG, "Not device owner — skipping policies")
            return
        }

        setupLockTask()
//        applyCameraPolicy()
        DynamicAppManager(context).applyDynamicRestrictions()
        disableDeveloperOptions()
        disableUsbDataTransfer()
        disableExternalStorage()
        disableUnknownSources()
        disableFactoryReset()
        disableScreenCapture()
        Log.d(TAG, "All policies applied")
    }

    // ── Lock task ────────────────────────────────────────────
    fun setupLockTask() {
        if (!isDeviceOwner) return

        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(
                admin,
                DevicePolicyManager.LOCK_TASK_FEATURE_NONE
            )
        }
        Log.d(TAG, "Lock task configured")
    }

    // ── Developer options ────────────────────────────────────
    fun disableDeveloperOptions() {
        if (!isDeviceOwner) return
        dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
        dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
        Log.d(TAG, "Developer options disabled")
    }

    // ── USB data transfer ────────────────────────────────────

    fun disableUsbDataTransfer() {
        if (!isDeviceOwner) return
        dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
        Log.d(TAG, "USB data transfer disabled")
    }

    // ── Factory reset protection ─────────────────────────────

    fun disableFactoryReset() {
        if (!isDeviceOwner) return
        dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
        Log.d(TAG, "Factory reset disabled")
    }

    // ── Screen capture ───────────────────────────────────────

    fun disableScreenCapture() {
        if (!isDeviceOwner) return
        dpm.setScreenCaptureDisabled(admin, true)
        Log.d(TAG, "Screen capture disabled")
    }

    fun disableUnknownSources() {
        if (!isDeviceOwner) return
        dpm.addUserRestriction(
            admin,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dpm.addUserRestriction(
                admin,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY
            )
        }

        Log.d(TAG, "Unknown sources disabled — only Play Store installs allowed")
    }

    fun disableExternalStorage() {
        if (!isDeviceOwner) return
        dpm.addUserRestriction(
            admin,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA
        )
        Log.d(TAG, "External storage / SD card disabled")
    }

    // ── Restore device to normal ─────────────────────────────
    fun restoreDeviceToNormal() {
        if (!isDeviceOwner) return

        Log.d(TAG, "Restoring device to normal state")

        // Restore all hidden apps and camera permissions
        DynamicAppManager(context).restoreAll()

        dpm.apply {
            setScreenCaptureDisabled(admin, false)
            clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
            clearUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
            clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            clearUserRestriction(admin, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
            clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                clearUserRestriction(
                    admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY
                )
            }
            setLockTaskPackages(admin, emptyArray())

            // POINT OF NO RETURN
            clearDeviceOwnerApp(context.packageName)
        }

        Log.d(TAG, "Device restored to normal")
    }

    fun applyCameraPolicy() {
        if (!isDeviceOwner) return

        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)

        var granted = 0
        var denied = 0
        var skipped = 0
        var failed = 0

        apps.forEach { app ->

            try {

                val packageInfo = pm.getPackageInfo(
                    app.packageName,
                    android.content.pm.PackageManager.GET_PERMISSIONS
                )

                val requestsCamera =
                    packageInfo.requestedPermissions?.contains(
                        android.Manifest.permission.CAMERA
                    ) == true

                if (!requestsCamera &&
                    app.packageName != OUR_PACKAGE
                ) {
                    skipped++
                    return@forEach
                }

                val state =
                    if (app.packageName == OUR_PACKAGE) {
                        granted++
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    } else {
                        denied++
                        DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                    }

                dpm.setPermissionGrantState(
                    admin,
                    app.packageName,
                    android.Manifest.permission.CAMERA,
                    state
                )

                Log.d(
                    TAG,
                    "${app.packageName} -> $state"
                )

            } catch (e: Exception) {

                failed++

                Log.w(
                    TAG,
                    "Failed ${app.packageName}: ${e.message}"
                )
            }
        }

        Log.d(
            TAG,
            """
        Camera policy applied
        Granted : $granted
        Denied  : $denied
        Skipped : $skipped
        Failed  : $failed
        """.trimIndent()
        )
    }

    fun restoreCameraPermissions() {
        if (!isDeviceOwner) return

        var restored = 0

        context.packageManager
            .getInstalledApplications(0)
            .forEach { app ->

                try {
                    dpm.setPermissionGrantState(
                        admin,
                        app.packageName,
                        android.Manifest.permission.CAMERA,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                    )

                    restored++

                } catch (_: Exception) {
                }
            }

        Log.d(TAG, "Restored camera permission for $restored apps")
    }

    fun applyCameraPolicyForPackage(packageName: String) {

        if (!isDeviceOwner) return

        if (packageName == OUR_PACKAGE) return

        try {

            val before = dpm.getPermissionGrantState(
                admin,
                packageName,
                android.Manifest.permission.CAMERA
            )

            Log.d(
                TAG,
                "$packageName CAMERA before = $before"
            )

            dpm.setPermissionGrantState(
                admin,
                packageName,
                android.Manifest.permission.CAMERA,
                DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
            )

            val after = dpm.getPermissionGrantState(
                admin,
                packageName,
                android.Manifest.permission.CAMERA
            )

            Log.d(
                TAG,
                "$packageName CAMERA after = $after"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed applying camera policy to $packageName",
                e
            )
        }
    }

    // ── Kiosk mode toggle ────────────────────────────────────

    fun enableKioskMode() {
        if (!isDeviceOwner) return

        // Lock task features — remove all chrome
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(
                admin,
                DevicePolicyManager.LOCK_TASK_FEATURE_NONE
            )
        }

        // Set our app as whitelisted for lock task
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))

        Log.d(TAG, "Kiosk mode enabled")
    }

    fun disableKioskMode() {
        if (!isDeviceOwner) return

        // Restore all lock task features
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(
                admin,
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                        DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                        DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                        DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
            )
        }

        // Clear lock task packages
//        dpm.setLockTaskPackages(admin, emptyArray())

        Log.d(TAG, "Kiosk mode disabled")
    }

    fun setKioskMode(enabled: Boolean) {
        if (enabled) enableKioskMode() else disableKioskMode()
        Log.d(TAG, "Kiosk mode set to: $enabled")
    }

    fun setScreenCapture(enabled: Boolean) {
        if (!isDeviceOwner) return
        dpm.setScreenCaptureDisabled(admin, !enabled)
        Log.d(TAG, "Screen capture disabled: ${!enabled}")
    }

    companion object {
        private const val TAG = "LockdownManager"
    }
}