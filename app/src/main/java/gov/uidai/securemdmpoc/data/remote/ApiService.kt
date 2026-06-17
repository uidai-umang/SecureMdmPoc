package gov.uidai.securemdmpoc.data.remote

import gov.uidai.securemdmpoc.ErrorReport
import gov.uidai.securemdmpoc.data.model.AppsReport
import gov.uidai.securemdmpoc.data.model.CheckInRequest
import gov.uidai.securemdmpoc.data.model.CheckInResponse
import gov.uidai.securemdmpoc.data.model.FcmConfirmRequest
import gov.uidai.securemdmpoc.data.model.HealthResponse
import gov.uidai.securemdmpoc.data.model.TokenUpdateRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {

    @POST("/device/checkin")
    suspend fun checkIn(@Body request: CheckInRequest): CheckInResponse

    @GET("/health")
    suspend fun health(): HealthResponse

    @POST("/device/error")
    suspend fun reportError(@Body report: ErrorReport): okhttp3.ResponseBody

    @POST("/device/apps/report")
    suspend fun reportApps(@Body report: AppsReport): okhttp3.ResponseBody

    @POST("/device/token")
    suspend fun updateToken(@Body request: TokenUpdateRequest): okhttp3.ResponseBody

    @POST("/device/fcm/confirm")
    suspend fun confirmFcm(@Body request: FcmConfirmRequest): okhttp3.ResponseBody
}