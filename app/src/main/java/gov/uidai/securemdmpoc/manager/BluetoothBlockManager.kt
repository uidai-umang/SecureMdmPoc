package gov.uidai.securemdmpoc.manager

import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.UserManager
import android.util.Log
import gov.uidai.securemdmpoc.data.repository.AppManagementRepository
import gov.uidai.securemdmpoc.util.PermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BluetoothBlockManager(
    private val context: Context,
    private val deviceOwner: DeviceOwnerContext,
    private val repository: AppManagementRepository
) {
    private val TAG = "BluetoothBlockManager"

    private val dpm get() = deviceOwner.dpm
    private val admin get() = deviceOwner.admin
    val isDeviceOwner get() = deviceOwner.isDeviceOwner

    /** Single source of truth — built once, reused by all 4 call sites. */
    private val bluetoothPermissions: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            listOf(
                android.Manifest.permission.BLUETOOTH,
                android.Manifest.permission.BLUETOOTH_ADMIN
            )
        }

    private val nearbyPermissions: List<String> = buildList {
        add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    private val allManagedPermissions: List<String> get() = bluetoothPermissions + nearbyPermissions

    private val userRestrictions = listOf(
        UserManager.DISALLOW_BLUETOOTH,
        UserManager.DISALLOW_BLUETOOTH_SHARING,
        "no_outgoing_beam"
    )

    // ── Master methods ─────────────────────────────────────────

    fun applyBluetoothBlock() {
        if (!isDeviceOwner) return
        Log.i(TAG, "Applying Bluetooth block...")

        applyUserRestrictions()
        setAdapterEnabled(false)
        setPermissionsForAllApps(PermissionState.DENIED)

        Log.i(TAG, "Bluetooth block applied")
        report("BLUETOOTH_BLOCKED")
    }

    fun restoreBluetooth() {
        if (!isDeviceOwner) return
        Log.i(TAG, "Restoring Bluetooth to normal state...")

        clearUserRestrictions()
        setAdapterEnabled(true)
        setPermissionsForAllApps(PermissionState.DEFAULT)

        Log.i(TAG, "Bluetooth restored to normal")
        report("BLUETOOTH_RESTORED")
    }

    // ── Per-package (fresh install) ───────────────────────────

    fun denyBluetoothAndNearbyForPackage(packageName: String) {
        if (packageName == context.packageName) return
        if (isSystemApp(packageName)) return

        allManagedPermissions.forEach { permission ->
            setPermission(packageName, permission, PermissionState.DENIED)
        }
        Log.d(TAG, "Bluetooth + nearby permissions denied for $packageName")
    }

    // ── User restrictions ──────────────────────────────────────
    // DISALLOW_BLUETOOTH: blocks toggle in Settings AND disables the
    // radio device-wide when set by Device Owner.
    // DISALLOW_BLUETOOTH_SHARING: blocks outgoing BT/Quick-Share sends.
    // no_outgoing_beam: blocks NFC-based beam-out sharing.
    // None of these cover RECEIVING — documented platform limitation.

    private fun applyUserRestrictions() {
        userRestrictions.forEach { restriction ->
            try {
                dpm.addUserRestriction(admin, restriction)
            } catch (e: Exception) {
                Log.w(TAG, "Could not apply $restriction: ${e.message}")
            }
        }
    }

    private fun clearUserRestrictions() {
        userRestrictions.forEach { restriction ->
            try {
                dpm.clearUserRestriction(admin, restriction)
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear $restriction: ${e.message}")
            }
        }
    }

    // ── Adapter on/off ─────────────────────────────────────────
    // BluetoothAdapter.disable()/enable() are deprecated and a no-op
    // for apps targeting API 33+, EXCEPT Device Owner/Profile Owner/
    // system apps, which remain exempt. We are Device Owner.

    @Suppress("DEPRECATION")
    private fun setAdapterEnabled(enabled: Boolean) {
        try {
            val adapter =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                    ?: return

            if (enabled && !adapter.isEnabled) {
                Log.d(
                    TAG,
                    if (adapter.enable()) "Bluetooth adapter enabled" else "adapter.enable() returned false"
                )
            } else if (!enabled && adapter.isEnabled) {
                Log.d(
                    TAG,
                    if (adapter.disable()) "Bluetooth adapter disabled" else "adapter.disable() returned false"
                )
            } else {
                Log.d(TAG, "Bluetooth adapter already in desired state (enabled=$enabled)")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception toggling BT adapter: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "BT adapter toggle failed: ${e.message}")
        }
    }

    // ── Per-app permission sweep ───────────────────────────────
    // Same pattern as DynamicAppManager's classification sweep, but
    // simpler: Bluetooth/nearby denial doesn't need full classification,
    // only the FLAG_SYSTEM safety guard (the lesson from the RescueParty
    // incident — never deny permissions to system apps by a narrow
    // hardcoded name list, always use the flag check).

    private fun setPermissionsForAllApps(state: PermissionState) {
        val allApps = context.packageManager.getInstalledApplications(0)
        var changed = 0

        allApps.forEach { app ->
            val pkg = app.packageName
            if (pkg == context.packageName) return@forEach
            if (isSystemApp(app)) return@forEach

            allManagedPermissions.forEach { permission ->
                if (setPermission(pkg, permission, state)) changed++
            }
        }

        Log.d(TAG, "setPermissionsForAllApps($state) — $changed permission grants changed")
    }

    private fun isSystemApp(packageName: String): Boolean {
        return try {
            isSystemApp(context.packageManager.getApplicationInfo(packageName, 0))
        } catch (e: Exception) {
            false
        }
    }

    private fun isSystemApp(appInfo: ApplicationInfo): Boolean =
        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

    // ── Generic permission setter ──────────────────────────────

    private fun setPermission(pkg: String, permission: String, state: PermissionState): Boolean {
        return try {
            dpm.setPermissionGrantState(admin, pkg, permission, state.dpmValue)
            true
        } catch (e: Exception) {
            false // expected when an app doesn't declare this permission
        }
    }

    // ── Verify ─────────────────────────────────────────────────

    fun verifyBluetoothBlocked(): Boolean {
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val isAdapterOff = adapter?.isEnabled == false
        val isRestricted =
            dpm.getUserRestrictions(admin).containsKey(UserManager.DISALLOW_BLUETOOTH)
        Log.d(TAG, "adapterOff=$isAdapterOff restricted=$isRestricted")
        return isAdapterOff && isRestricted
    }

    // ── Backend reporting ──────────────────────────────────────

    private fun report(action: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repository.reportApps(action, emptyList())
            } catch (e: Exception) {
                Log.e(TAG, "Report failed: ${e.message}")
            }
        }
    }
}