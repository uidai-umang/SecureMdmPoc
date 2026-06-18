package gov.uidai.securemdmpoc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class PolicyEnforcementService : Service() {

    private val packageReceiver = PackageChangeReceiver()

    override fun onCreate() {
        super.onCreate()
        startForeground()
        registerPackageReceiver()
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

        startForeground(1001, notification)
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
        try {
            unregisterReceiver(packageReceiver)
        } catch (e: Exception) { }
        Log.d(TAG, "PolicyEnforcementService stopped — restarting")

        // Restart service if destroyed
        val restart = Intent(this, PolicyEnforcementService::class.java)
        startService(restart)
    }

    companion object {
        private const val TAG = "PolicyService"
    }
}