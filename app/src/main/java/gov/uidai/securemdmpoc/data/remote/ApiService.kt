package gov.uidai.securemdmpoc.data.remote

import gov.uidai.securemdmpoc.ErrorReport
import gov.uidai.securemdmpoc.UpdateInfo
import gov.uidai.securemdmpoc.UpdateSuccessReport
import gov.uidai.securemdmpoc.data.model.CheckInRequest
import gov.uidai.securemdmpoc.data.model.CheckInResponse
import gov.uidai.securemdmpoc.data.model.HealthResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

interface ApiService {

    @POST("/device/checkin")
    suspend fun checkIn(@Body request: CheckInRequest): CheckInResponse

    @GET("/health")
    suspend fun health(): HealthResponse

    // Add these to existing ApiService interface
    @GET("/update/check")
    suspend fun checkUpdate(
        @Query("versionCode") versionCode: Int
    ): UpdateInfo

    @GET
    @Streaming
    suspend fun downloadApk(@Url url: String): okhttp3.ResponseBody

    @POST("/device/update/success")
    suspend fun reportSuccess(@Body report: UpdateSuccessReport): okhttp3.ResponseBody

    @POST("/device/error")
    suspend fun reportError(@Body report: ErrorReport): okhttp3.ResponseBody
}