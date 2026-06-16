package gov.uidai.securemdmpoc.data.remote

import gov.uidai.securemdmpoc.UpdateInfo
import gov.uidai.securemdmpoc.UpdateSuccessReport
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

interface UpdateApiService {

    @GET("/update/check")
    suspend fun checkUpdate(
        @Query("versionCode") versionCode: Int
    ): UpdateInfo

    @GET
    @Streaming
    suspend fun downloadApk(
        @Url url: String
    ): ResponseBody

    @POST("/device/update/success")
    suspend fun reportSuccess(@Body report: UpdateSuccessReport): okhttp3.ResponseBody
}