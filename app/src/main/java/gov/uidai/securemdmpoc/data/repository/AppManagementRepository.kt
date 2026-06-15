package gov.uidai.securemdmpoc.data.repository

import android.content.Context
import android.os.Build
import android.util.Log
import gov.uidai.securemdmpoc.data.model.AppsReport
import gov.uidai.securemdmpoc.data.remote.RetrofitClient
import gov.uidai.securemdmpoc.DeviceErrorReporter
import gov.uidai.securemdmpoc.data.remote.ApiService
import gov.uidai.securemdmpoc.data.remote.UpdateApiService

class AppManagementRepository(private val context: Context, private val apiService: ApiService) {

    private val TAG = "AppManagementRepository"

    suspend fun reportApps(action: String, packages: List<String>) {
        try {
            val model = "${Build.MANUFACTURER} ${Build.MODEL}"
            apiService.reportApps(
                AppsReport(
                    packageName = context.packageName,
                    model = model,
                    action = action,
                    packages = packages,
                    timestamp = System.currentTimeMillis()
                )
            )
            Log.d(TAG, "Reported $action — ${packages.size} packages")
        } catch (e: Exception) {
            Log.e(TAG, "reportApps failed: ${e.message}")
        }
    }

    suspend fun reportError(errorType: String, errorMessage: String, step: String, stackTrace: String? = null) {
        try {
            DeviceErrorReporter.report(
                context = context,
                errorType = errorType,
                errorMessage = errorMessage,
                step = step,
                stackTrace = stackTrace
            )
        } catch (e: Exception) {
            Log.e(TAG, "reportError failed: ${e.message}")
        }
    }
}