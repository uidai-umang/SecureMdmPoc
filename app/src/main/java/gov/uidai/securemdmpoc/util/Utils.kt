package gov.uidai.securemdmpoc.util

import android.content.Context
import android.widget.Toast
import android.os.Handler
import android.os.Looper

object Utils {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun showToast(
        tag: String? = null,
        msg: String,
        length: Int = Toast.LENGTH_SHORT
    ) {
        appContext?.let { context ->
            val message = if (tag.isNullOrBlank()) msg else "$tag: $msg"

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, message, length).show()
            }
        }
    }

    val excemptionPackages = listOf<String>("in.gov.uidai.pehchaan")
    val packagesToSuspend = listOf<String>("com.android.vending")
}