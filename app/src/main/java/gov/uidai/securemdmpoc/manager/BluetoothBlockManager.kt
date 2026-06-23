package gov.uidai.securemdmpoc.manager

import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import gov.uidai.securemdmpoc.data.repository.AppManagementRepository
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

    // ── Master method ─────────────────────────────────────────

    fun applyBluetoothBlock() {
        if (!isDeviceOwner) return

        Log.i(TAG, "Applying Bluetooth block...")

        blockViaUserRestrictions()
        forceDisableAdapter()
        denyBluetoothPermissionsForAllApps()
        blockNfcOutgoingBeam()
        denyNearbyDiscoveryPermissions()

        Log.i(TAG, "Bluetooth block applied")
        report("BLUETOOTH_BLOCKED")
    }

    // ── Layer 1 — User restrictions ───────────────────────────
    // DISALLOW_BLUETOOTH: blocks toggle in Settings AND disables
    // the radio device-wide when set by Device Owner.
    // DISALLOW_BLUETOOTH_SHARING: blocks outgoing BT/Quick-Share sends.
    // Receiving is NOT covered by either — documented platform limitation.

    private fun blockViaUserRestrictions() {
        try {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_BLUETOOTH)
            Log.d(TAG, "DISALLOW_BLUETOOTH applied")
        } catch (e: Exception) {
            Log.w(TAG, "DISALLOW_BLUETOOTH failed: ${e.message}")
        }

        try {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_BLUETOOTH_SHARING)
            Log.d(TAG, "DISALLOW_BLUETOOTH_SHARING applied")
        } catch (e: Exception) {
            Log.w(TAG, "DISALLOW_BLUETOOTH_SHARING failed: ${e.message}")
        }
    }

    // ── Layer 2 — Force disable adapter ───────────────────────
    // BluetoothAdapter.disable() is deprecated and a no-op for
    // apps targeting API 33+, EXCEPT Device Owner/Profile Owner/
    // system apps, which remain exempt. We are Device Owner.

    private fun forceDisableAdapter() {
        try {
            val bluetoothManager = context.getSystemService(
                Context.BLUETOOTH_SERVICE
            ) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: run {
                Log.d(TAG, "No Bluetooth adapter on this device")
                return
            }

            if (adapter.isEnabled) {
                @Suppress("DEPRECATION")
                val disabled = adapter.disable()
                Log.d(TAG, if (disabled) "Bluetooth adapter disabled"
                else "adapter.disable() returned false")
            } else {
                Log.d(TAG, "Bluetooth already OFF")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception disabling BT: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "BT disable failed: ${e.message}")
        }
    }

    // ── Layer 3 — Deny Bluetooth runtime permissions per-app ──
    // Same proven pattern already used for camera/storage in
    // DynamicAppManager. Even if BT is somehow on, apps cannot
    // use it to initiate transfers.

    private fun denyBluetoothPermissionsForAllApps() {
        val pm = context.packageManager
        val allApps = pm.getInstalledApplications(0)

        val btPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

        var deniedCount = 0

        allApps.forEach { app ->
            val pkg = app.packageName
            if (pkg == context.packageName) return@forEach
            if (pkg == "android") return@forEach
            if (pkg == "com.google.android.gms") return@forEach

            btPermissions.forEach { permission ->
                try {
                    dpm.setPermissionGrantState(
                        admin, pkg, permission,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                    )
                    deniedCount++
                } catch (e: Exception) {
                    // Permission may not be declared by this app — expected, not an error
                }
            }
        }

        Log.d(TAG, "Bluetooth permissions denied for $deniedCount app-permission pairs")
    }

    // ── Layer 4 — NFC outgoing beam ───────────────────────────
    // DISALLOW_OUTGOING_BEAM blocks NFC-based "beam out" sharing.
    // This is narrower than full NFC radio disable, but is the
    // broadly-available, stable restriction across our actual
    // Android 9/12/14 fleet.

    private fun blockNfcOutgoingBeam() {
        try {
            dpm.addUserRestriction(admin, "no_outgoing_beam")
            Log.d(TAG, "NFC outgoing beam disabled")
        } catch (e: Exception) {
            Log.w(TAG, "NFC beam restriction failed: ${e.message}")
        }
    }

    // ── Layer 5 — Deny location & nearby-device permissions per-app ──
    // Quick Share / Nearby Share request these to discover nearby devices.
    // Denying them doesn't touch system location services generally —
    // it specifically blocks apps that would otherwise use location/
    // nearby-device discovery as part of a sharing flow.

    private fun denyNearbyDiscoveryPermissions() {
        val pm = context.packageManager
        val allApps = pm.getInstalledApplications(0)

        val nearbyPermissions = buildList {
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        var deniedCount = 0

        allApps.forEach { app ->
            val pkg = app.packageName
            if (pkg == context.packageName) return@forEach

            // Never touch ANY system app — not just a hardcoded shortlist.
            // This is the same essential-detection discipline DynamicAppManager
            // already uses, and which this method was missing.
            val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem) return@forEach

            nearbyPermissions.forEach { permission ->
                try {
                    dpm.setPermissionGrantState(
                        admin, pkg, permission,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                    )
                    deniedCount++
                } catch (e: Exception) { }
            }
        }

        Log.d(TAG, "Nearby discovery permissions denied for $deniedCount app-permission pairs")
    }

    // ── Restore ────────────────────────────────────────────────

    fun restoreBluetooth() {
        if (!isDeviceOwner) return

        Log.i(TAG, "Restoring Bluetooth to normal state...")

        listOf(
            UserManager.DISALLOW_BLUETOOTH,
            UserManager.DISALLOW_BLUETOOTH_SHARING,
            "no_outgoing_beam"
        ).forEach { restriction ->
            try {
                dpm.clearUserRestriction(admin, restriction)
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear $restriction: ${e.message}")
            }
        }

        val allApps = context.packageManager.getInstalledApplications(0)
        val btPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

        allApps.forEach { app ->
            btPermissions.forEach { permission ->
                try {
                    dpm.setPermissionGrantState(
                        admin, app.packageName, permission,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                    )
                } catch (e: Exception) { }
            }
        }

        val nearbyPermissions = buildList {
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        allApps.forEach { app ->
            nearbyPermissions.forEach { permission ->
                try {
                    dpm.setPermissionGrantState(
                        admin, app.packageName, permission,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                    )
                } catch (e: Exception) { }
            }
        }

        Log.i(TAG, "Bluetooth restored to normal")
        report("BLUETOOTH_RESTORED")
    }

    // In BluetoothBlockManager.kt — add:
    fun denyBluetoothAndNearbyForPackage(packageName: String) {
        if (packageName == context.packageName) return

        val isSystem = try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            return
        }
        if (isSystem) return

        val btPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

        val nearbyPermissions = buildList {
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        (btPermissions + nearbyPermissions).forEach { permission ->
            try {
                dpm.setPermissionGrantState(
                    admin, packageName, permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                )
            } catch (e: Exception) { }
        }

        Log.d(TAG, "Bluetooth + nearby permissions denied for newly-installed $packageName")
    }

    // ── Verify ─────────────────────────────────────────────────

    fun verifyBluetoothBlocked(): Boolean {
        val btManager = context.getSystemService(
            Context.BLUETOOTH_SERVICE
        ) as? BluetoothManager
        val adapter = btManager?.adapter
        val isAdapterOff = adapter?.isEnabled == false
        val isRestricted = dpm.getUserRestrictions(admin)
            .containsKey(UserManager.DISALLOW_BLUETOOTH)

        Log.d(TAG, "adapterOff=$isAdapterOff restricted=$isRestricted")
        return isAdapterOff && isRestricted
    }

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