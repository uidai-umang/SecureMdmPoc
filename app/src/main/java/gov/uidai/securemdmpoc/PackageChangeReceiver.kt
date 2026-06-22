package gov.uidai.securemdmpoc

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import gov.uidai.securemdmpoc.manager.BluetoothBlockManager
import gov.uidai.securemdmpoc.manager.DynamicAppManager
import gov.uidai.securemdmpoc.manager.LockdownManager
import gov.uidai.securemdmpoc.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject

class PackageChangeReceiver : BroadcastReceiver() {
    private val lockdownManager: LockdownManager by inject(LockdownManager::class.java)
    private val dynamicAppManager: DynamicAppManager by inject(DynamicAppManager::class.java)

    private val bluetoothBlockManager: BluetoothBlockManager by inject(BluetoothBlockManager::class.java)


    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        val packageName = intent?.data?.schemeSpecificPart ?: return
        val action = intent.action ?: return

        Utils.showToast(msg = "PackageChangeReceiver: $action -> $packageName")

        // Only handle installs and updates
        if (action != Intent.ACTION_PACKAGE_ADDED &&
            action != Intent.ACTION_PACKAGE_REPLACED
        ) return

        // Skip our own package
        if (packageName == context.packageName) return

        Log.d(TAG, "📦 Package event: $action → $packageName")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Small delay to let package manager settle
                delay(500)

                // Apply camera deny policy
                lockdownManager.applyCameraPolicyForPackage(packageName)
                dynamicAppManager.denyStoragePermissions(packageName)
                bluetoothBlockManager.denyBluetoothAndNearbyForPackage(packageName)


                // Verify the state was actually set
                val dpm = context.getSystemService(
                    Context.DEVICE_POLICY_SERVICE
                ) as DevicePolicyManager

                val admin = ComponentName(
                    context,
                    MyDeviceAdminReceiver::class.java
                )

                val state = dpm.getPermissionGrantState(
                    admin,
                    packageName,
                    android.Manifest.permission.CAMERA
                )

                when (state) {
                    DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED ->
                        Log.d(TAG, "✅ Camera DENIED for $packageName")
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED ->
                        Log.d(TAG, "⚠️ Camera GRANTED for $packageName")
                    DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT ->
                        Log.d(TAG, "⚪ Camera DEFAULT for $packageName — app may not request camera")
                    else ->
                        Log.d(TAG, "❓ Camera state $state for $packageName")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed for $packageName: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PackageChangeReceiver"
    }
}