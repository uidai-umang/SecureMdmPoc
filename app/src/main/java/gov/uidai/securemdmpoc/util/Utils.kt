package gov.uidai.securemdmpoc.util

import android.content.Context
import android.widget.Toast

object Utils {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun showToast(
        tag: String?,
        msg: String,
        length: Int = Toast.LENGTH_SHORT
    ) {
        appContext?.let { context ->
            val message = if (tag.isNullOrEmpty()) {
                msg
            } else {
                "$tag: $msg"
            }
            Toast.makeText(context, message, length).show()
        }
    }
}