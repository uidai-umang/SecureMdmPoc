package gov.uidai.securemdmpoc.data.repository

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresPermission
import gov.uidai.securemdmpoc.ErrorReport
import gov.uidai.securemdmpoc.MyDeviceAdminReceiver
import gov.uidai.securemdmpoc.data.model.CheckInRequest
import gov.uidai.securemdmpoc.data.model.CheckInResponse
import gov.uidai.securemdmpoc.data.model.FcmConfirmRequest
import gov.uidai.securemdmpoc.data.model.TokenUpdateRequest
import gov.uidai.securemdmpoc.data.remote.ApiService

class DeviceRepository(
    private val context: Context,
    private val apiService: ApiService
) {

    @RequiresPermission("android.permission.READ_PRIVILEGED_PHONE_STATE")
    suspend fun checkIn(kioskActive: Boolean, fcmToken: String?): Result<CheckInResponse> {
        val serial = try {
            Build.getSerial()
        } catch (e: Exception) {
            null
        }

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
                    fcmToken = fcmToken,
                    serialNumber = serial
                )
            )
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reportError(errorReport: ErrorReport) = apiService.reportError(errorReport)

    suspend fun updateToken(fcmToken: String): Result<Unit> {
        return try {
            apiService.updateToken(
                TokenUpdateRequest(
                    model = Build.MODEL,
                    manufacturer = Build.MANUFACTURER,
                    fcmToken = fcmToken
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun confirmFcm(action: String): Result<Unit> {
        return try {
            apiService.confirmFcm(
                FcmConfirmRequest(
                    model = Build.MODEL,
                    manufacturer = Build.MANUFACTURER,
                    action = action,
                    status = "received",
                    receivedAt = System.currentTimeMillis()
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}