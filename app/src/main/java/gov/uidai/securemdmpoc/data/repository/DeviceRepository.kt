package gov.uidai.securemdmpoc.data.repository

import android.content.Context
import android.os.Build
import gov.uidai.securemdmpoc.ErrorReport
import gov.uidai.securemdmpoc.MyDeviceAdminReceiver
import gov.uidai.securemdmpoc.data.model.CheckInRequest
import gov.uidai.securemdmpoc.data.model.CheckInResponse
import gov.uidai.securemdmpoc.data.remote.ApiService

class DeviceRepository(
    private val context: Context,
    private val apiService: ApiService
) {

    suspend fun checkIn(kioskActive: Boolean, fcmToken: String?): Result<CheckInResponse> {
        return try {
            val response = apiService.checkIn(
                CheckInRequest(
                    packageName = context.packageName,
                    model = Build.MODEL,
                    manufacturer = Build.MANUFACTURER,
                    androidVersion = Build.VERSION.RELEASE,
                    kioskActive = kioskActive,
                    isDeviceOwner = MyDeviceAdminReceiver.isDeviceOwner(context),
                    timestamp = System.currentTimeMillis(),
                    fcmToken = fcmToken
                )
            )
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reportError(errorReport: ErrorReport) = apiService.reportError(errorReport)
}