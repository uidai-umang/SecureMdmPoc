package gov.uidai.securemdmpoc.receivers

import android.Manifest
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import gov.uidai.securemdmpoc.PolicyEnforcementService
import gov.uidai.securemdmpoc.manager.DeviceOwnerContext
import gov.uidai.securemdmpoc.manager.LockdownManager
import gov.uidai.securemdmpoc.manager.PolicyController
import org.koin.java.KoinJavaComponent
import org.koin.java.KoinJavaComponent.inject

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    private val policyController: PolicyController by inject(PolicyController::class.java)

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device admin enabled")
        requestBatteryOptimizationExemption(context)
        policyController.applyAllPolicies()

        // Subscribe to FCM topic for remote commands
        FirebaseMessaging
            .getInstance()
            .subscribeToTopic("all-devices")
            .addOnCompleteListener { task ->
                Log.d(
                    TAG,
                    "FCM topic subscription: ${
                        if (task.isSuccessful) "✅ success" else "❌ failed"
                    }"
                )
            }

        // Start foreground service
        PolicyEnforcementService.safeStartPolicyService(context)
    }

    override fun onProfileProvisioningComplete(
        context: Context, intent: Intent
    ) {
        super.onProfileProvisioningComplete(context, intent)
        Log.d(TAG, "Provisioning complete — applying policies")

        requestBatteryOptimizationExemption(context)

        policyController.applyAllPolicies()

        // Subscribe to FCM — critical for remote management
        FirebaseMessaging
            .getInstance()
            .subscribeToTopic("all-devices")

        // Start foreground service
        PolicyEnforcementService.safeStartPolicyService(context)

        grantNotificationPermission(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "Device admin disabled")
    }

    override fun onLockTaskModeEntering(
        context: Context, intent: Intent, pkg: String
    ) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Log.d(TAG, "Lock task entering: $pkg")
    }

    override fun onLockTaskModeExiting(
        context: Context, intent: Intent
    ) {
        super.onLockTaskModeExiting(context, intent)
        Log.d(TAG, "Lock task exiting")
    }


    private fun grantNotificationPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        policyController.grantNotificationPermission(context.packageName)
    }

    private fun requestBatteryOptimizationExemption(context: Context) {
        // NOTE: this method's actual battery-optimization mechanism
        // (Runtime.exec + "power_whitelist") does not work without root —
        // confirmed dead code, kept disabled rather than removed pending
        // a real DPM-based replacement, if one exists.

        try {
            // Device Owner can whitelist itself silently
            // without showing any dialog to the user
//            val dpm = deviceOwnerContext.dpm
//
//            val admin = deviceOwnerContext.admin
//
//            if (dpm.isDeviceOwnerApp(context.packageName)) {
//                dpm.addUserRestriction(
//                    admin,
//                    "allow_any_codec_for_playback"
//                )
//
//                // Whitelist our package from battery optimization
//                val powerWhitelist = context.getSystemService(
//                    "power_whitelist"
//                )
//
//                // Use shell command via Device Owner privilege
//                Runtime.getRuntime().exec(
//                    "dumpsys deviceidle whitelist +${context.packageName}"
//                )
//                Log.d(TAG, "Battery optimization exemption requested")
//            }
        } catch (e: Exception) {
            Log.e(TAG, "Battery opt failed: ${e.message}")
        }
    }

    companion object {
        const val TAG = "DeviceAdminReceiver"

        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(
                Context.DEVICE_POLICY_SERVICE
            ) as DevicePolicyManager
            return dpm.isDeviceOwnerApp(context.packageName)
        }
    }
}