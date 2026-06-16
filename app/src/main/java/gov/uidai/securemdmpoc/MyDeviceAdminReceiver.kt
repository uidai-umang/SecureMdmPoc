package gov.uidai.securemdmpoc

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.content.ContextCompat.startActivity
import gov.uidai.securemdmpoc.manager.LockdownManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject

class MyDeviceAdminReceiver : DeviceAdminReceiver() {
    private val lockdownManager: LockdownManager by inject(LockdownManager::class.java)

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device admin enabled")
        requestBatteryOptimizationExemption(context)
        lockdownManager.applyAllPolicies()

        // Subscribe to FCM topic for remote commands
        com.google.firebase.messaging.FirebaseMessaging
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
        val serviceIntent = Intent(context, PolicyEnforcementService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    override fun onProfileProvisioningComplete(
        context: Context, intent: Intent
    ) {
        super.onProfileProvisioningComplete(context, intent)
        Log.d(TAG, "Provisioning complete — applying policies")

        requestBatteryOptimizationExemption(context)

        lockdownManager.applyAllPolicies()

        // Subscribe to FCM — critical for remote management
        com.google.firebase.messaging.FirebaseMessaging
            .getInstance()
            .subscribeToTopic("all-devices")

        // Start foreground service
        val serviceIntent = Intent(context, PolicyEnforcementService::class.java)
        context.startForegroundService(serviceIntent)
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


    private fun requestBatteryOptimizationExemption(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // Device Owner can whitelist itself silently
                // without showing any dialog to the user
                val dpm = context.getSystemService(
                    Context.DEVICE_POLICY_SERVICE
                ) as android.app.admin.DevicePolicyManager

                val admin = android.content.ComponentName(
                    context,
                    MyDeviceAdminReceiver::class.java
                )

                if (dpm.isDeviceOwnerApp(context.packageName)) {
                    dpm.addUserRestriction(
                        admin,
                        "allow_any_codec_for_playback"
                    )

                    // Whitelist our package from battery optimization
                    val powerWhitelist = context.getSystemService(
                        "power_whitelist"
                    )

                    // Use shell command via Device Owner privilege
                    Runtime.getRuntime().exec(
                        "dumpsys deviceidle whitelist +${context.packageName}"
                    )
                    Log.d(TAG, "Battery optimization exemption requested")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Battery opt failed: ${e.message}")
            }
        }
    }

    companion object {
        const val TAG = "DeviceAdminReceiver"

        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(
                Context.DEVICE_POLICY_SERVICE
            ) as android.app.admin.DevicePolicyManager
            return dpm.isDeviceOwnerApp(context.packageName)
        }
    }
}