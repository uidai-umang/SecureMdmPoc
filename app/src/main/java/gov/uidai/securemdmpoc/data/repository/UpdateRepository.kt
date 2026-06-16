package gov.uidai.securemdmpoc.data.repository

import android.content.Context
import gov.uidai.securemdmpoc.UpdateSuccessReport
import gov.uidai.securemdmpoc.data.remote.ApiService
import gov.uidai.securemdmpoc.data.remote.UpdateApiService

class UpdateRepository(
    private val context: Context,
    private val api: ApiService,
    private val updateApi: UpdateApiService
) {

    suspend fun checkUpdate(versionCode: Int) = updateApi.checkUpdate(versionCode)

    suspend fun downloadApk(url: String) = updateApi.downloadApk(url)

    suspend fun reportSuccess(report: UpdateSuccessReport) = updateApi.reportSuccess(report)

}