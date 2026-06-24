package gov.uidai.securemdmpoc.receivers

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import gov.uidai.securemdmpoc.manager.BluetoothBlockManager
import gov.uidai.securemdmpoc.manager.DeviceOwnerContext
import gov.uidai.securemdmpoc.manager.DynamicAppManager
import gov.uidai.securemdmpoc.manager.LockdownManager
import gov.uidai.securemdmpoc.manager.PolicyController
import gov.uidai.securemdmpoc.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent
import org.koin.java.KoinJavaComponent.inject
import kotlin.time.Duration.Companion.milliseconds

class PackageChangeReceiver : BroadcastReceiver() {
    private val policyController: PolicyController by inject(PolicyController::class.java)

    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        val packageName = intent?.data?.schemeSpecificPart ?: return
        val action = intent.action ?: return

        Utils.showToast(msg = "PackageChangeReceiver: $action -> $packageName")

        // Only handle installs and updates
        if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) return

        // Skip our own package
        if (packageName == context.packageName) return

        Log.d(TAG, "📦 Package event: $action → $packageName")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(500.milliseconds)
                policyController.applyPolicyForNewPackage(packageName)
                Log.d(TAG, "Policy applied for new/updated package: $packageName")
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