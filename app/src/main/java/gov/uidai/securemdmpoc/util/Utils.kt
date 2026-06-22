package gov.uidai.securemdmpoc.util

import android.content.Context
import android.widget.Toast
import android.os.Handler
import android.os.Looper

object Utils {

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun showToast(
        tag: String? = null,
        msg: String,
        length: Int = Toast.LENGTH_SHORT
    ) {
        val context = appContext ?: run {
            return
        }

        val message = if (tag.isNullOrBlank()) msg else "$tag: $msg"

        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(context, message, length).show()
                } catch (e: Exception) {

                }
            }
        } catch (e: Exception) {

        }
    }

    val excemptionPackages = listOf<String>("in.gov.uidai.pehchaan")
    val packagesToSuspend = listOf<String>("com.android.vending")
}