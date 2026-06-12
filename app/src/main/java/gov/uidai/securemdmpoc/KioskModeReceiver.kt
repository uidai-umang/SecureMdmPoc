package gov.uidai.securemdmpoc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

class KioskModeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        val enabled = intent?.getBooleanExtra("enabled", true) ?: return
        Log.d(TAG, "KioskModeReceiver: enabled=$enabled")

        if (!enabled) {
            // Launch MainActivity to call stopLockTask from Activity context
            val activityIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra(EXTRA_KIOSK_ENABLED, false)
            }
            context.startActivity(activityIntent)
            return
        }

        showKioskNotification(context)
    }

    private fun showKioskNotification(context: Context) {
        val channelId = "kiosk_channel"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Kiosk Mode",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val activityIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(EXTRA_KIOSK_ENABLED, true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Kiosk mode activating")
            .setContentText("Tap to activate")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val TAG = "KioskModeReceiver"
        const val ACTION = "gov.uidai.securemdmpoc.KIOSK_MODE"
        const val EXTRA_KIOSK_ENABLED = "kiosk_enabled"
        const val NOTIFICATION_ID = 2001
    }
}