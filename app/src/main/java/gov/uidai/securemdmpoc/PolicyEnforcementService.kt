package gov.uidai.securemdmpoc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import gov.uidai.securemdmpoc.manager.StorageDefenceManager
import gov.uidai.securemdmpoc.receivers.PackageChangeReceiver
import org.koin.android.ext.android.inject

class PolicyEnforcementService : Service() {

    private val packageReceiver = PackageChangeReceiver()
    private val storageDefence: StorageDefenceManager by inject()

    override fun onCreate() {
        super.onCreate()
        startForeground()
        registerPackageReceiver()
        storageDefence.startDetection()
        Log.d(TAG, "PolicyEnforcementService started")
    }

    private fun startForeground() {
        val channelId = "mdm_policy_channel"

        val channel = NotificationChannel(
            channelId,
            "MDM Policy",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Enforcing device policy"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Device policy active")
            .setContentText("Camera access is managed")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .build()

        try {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    1001,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}")
            DeviceErrorReporter.report(
                applicationContext,
                errorType = "FOREGROUND_SERVICE_START_FAILED",
                errorMessage = e.javaClass.simpleName + ": " + (e.message ?: "no message"),
                step = "PolicyEnforcementService.startForeground"
            )
        }
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        // Restart if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        storageDefence.stopDetection()

        try {
            unregisterReceiver(packageReceiver)
        } catch (e: Exception) {
        }

        Log.d(TAG, "PolicyEnforcementService stopped — restarting")

        // Restart service if destroyed
        safeStartPolicyService(this)
    }

    companion object {
        private const val TAG = "PolicyService"

        fun safeStartPolicyService(context: Context) {
            try {
                context.startForegroundService(
                    Intent(context, PolicyEnforcementService::class.java)
                )
            } catch (e: Exception) {
                Log.e("PolicyServiceStart", "Failed: ${e.javaClass.simpleName} — ${e.message}")
                DeviceErrorReporter.report(
                    context,
                    errorType = "POLICY_SERVICE_START_REJECTED",
                    errorMessage = e.javaClass.simpleName + ": " + (e.message ?: "no message"),
                    step = "safeStartPolicyService"
                )
            }
        }
    }

}