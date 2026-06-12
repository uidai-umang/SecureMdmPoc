package gov.uidai.securemdmpoc.di

import gov.uidai.securemdmpoc.data.prefs.SharedPreferences
import gov.uidai.securemdmpoc.data.remote.RetrofitClient
import gov.uidai.securemdmpoc.data.repository.DeviceRepository
import gov.uidai.securemdmpoc.ui.admin.AdminViewModel
import gov.uidai.securemdmpoc.ui.kiosk.KioskViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {

    // Retrofit API service singleton
    single { RetrofitClient.instance }

    // Repository singleton
    single { DeviceRepository(androidContext(), get()) }

    // ViewModel
    viewModel { KioskViewModel(get()) }
    viewModel { AdminViewModel() }

    // SharedPrefs
    single { SharedPreferences(androidContext()) }
}