package gov.uidai.securemdmpoc.app

import android.app.Application
import com.google.firebase.FirebaseApp
import gov.uidai.securemdmpoc.di.appModule
import gov.uidai.securemdmpoc.util.Utils
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize Firebase
        FirebaseApp.initializeApp(this)
        Utils.init(this)

        startKoin {
            androidContext(this@MyApplication)
            modules(appModule)
        }
    }
}