package gov.uidai.securemdmpoc.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import gov.uidai.securemdmpoc.MainActivity
import gov.uidai.securemdmpoc.PolicyEnforcementService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )

            // Start policy enforcement service
            PolicyEnforcementService.safeStartPolicyService(context)
        }
    }
}