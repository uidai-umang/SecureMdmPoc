package gov.uidai.securemdmpoc

import android.content.Context
import android.os.Build
import android.util.Log
import gov.uidai.securemdmpoc.data.remote.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object DeviceErrorReporter {

    private const val TAG = "ErrorReporter"

    // Error types
    const val ERROR_UPDATE_CHECK   = "UPDATE_CHECK_FAILED"
    const val ERROR_UPDATE_DOWNLOAD = "UPDATE_DOWNLOAD_FAILED"
    const val ERROR_UPDATE_INSTALL  = "UPDATE_INSTALL_FAILED"
    const val ERROR_UPDATE_VERSION  = "UPDATE_VERSION_CHECK_FAILED"
    const val ERROR_UPDATE_SIGNATURE = "UPDATE_SIGNATURE_CHECK_FAILED"
    const val ERROR_DEVICE_OWNER_LOST = "DEVICE_OWNER_LOST"
    const val ERROR_POLICY_APPLY   = "POLICY_APPLY_FAILED"
    const val ERROR_FCM_HANDLER    = "FCM_HANDLER_FAILED"

    fun report(
        context: Context,
        errorType: String,
        errorMessage: String,
        step: String,
        stackTrace: String? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val model = "${Build.MANUFACTURER} ${Build.MODEL}"

                RetrofitClient.instance.reportError(
                    ErrorReport(
                        packageName = context.packageName,
                        model = model,
                        errorType = errorType,
                        errorMessage = errorMessage,
                        stackTrace = stackTrace,
                        step = step,
                        timestamp = System.currentTimeMillis()
                    )
                )

                Log.d(TAG, "Error reported to backend: $errorType")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to report error: ${e.message}")
            }
        }
    }
}

data class ErrorReport(
    val packageName: String,
    val model: String,
    val errorType: String,
    val errorMessage: String,
    val stackTrace: String?,
    val step: String,
    val timestamp: Long
)