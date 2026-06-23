package gov.uidai.securemdmpoc.di

import gov.uidai.securemdmpoc.UpdateChecker
import gov.uidai.securemdmpoc.data.prefs.SharedPreferences
import gov.uidai.securemdmpoc.data.remote.RetrofitClient
import gov.uidai.securemdmpoc.data.repository.AppManagementRepository
import gov.uidai.securemdmpoc.data.repository.DeviceRepository
import gov.uidai.securemdmpoc.data.repository.UpdateRepository
import gov.uidai.securemdmpoc.manager.BluetoothBlockManager
import gov.uidai.securemdmpoc.manager.DeviceOwnerContext
import gov.uidai.securemdmpoc.manager.DynamicAppManager
import gov.uidai.securemdmpoc.manager.LockdownManager
import gov.uidai.securemdmpoc.manager.StorageDefenceManager
import gov.uidai.securemdmpoc.ui.admin.AdminViewModel
import gov.uidai.securemdmpoc.ui.kiosk.KioskViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import kotlin.math.sin

val appModule = module {

    // Retrofit API service singleton
    single { RetrofitClient.apiService }
    single { RetrofitClient.updateApiService }

    // Repository singleton
    single { DeviceRepository(androidContext(), get()) }

    // SharedPrefs
    single { SharedPreferences(androidContext()) }

    // App management repository
    single { DeviceRepository(androidContext(), get()) }
    single { AppManagementRepository(androidContext(), get()) }
    single { UpdateRepository(androidContext(), get(), get()) }

    single { UpdateChecker(get(), get()) }

    single { DeviceOwnerContext(androidContext()) }

    factory { LockdownManager(androidContext(), get(), get(), get(), get()) }
    factory { DynamicAppManager(androidContext(),  get(), get()) }
    factory { BluetoothBlockManager(androidContext(),  get(), get()) }
    factory { StorageDefenceManager(androidContext(), get()) }

    // ViewModels
    viewModel { KioskViewModel(get()) }
    viewModel { AdminViewModel(get()) }
}