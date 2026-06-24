package gov.uidai.securemdmpoc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.google.firebase.messaging.FirebaseMessaging
import gov.uidai.securemdmpoc.data.prefs.SharedPreferences
import gov.uidai.securemdmpoc.manager.LockdownManager
import gov.uidai.securemdmpoc.receivers.KioskModeReceiver
import gov.uidai.securemdmpoc.ui.admin.AdminExitFragment
import org.koin.android.ext.android.inject

class MainActivity : AppCompatActivity(), AdminExitFragment.AdminExitListener {

    private lateinit var navController: NavController
    private var kioskActive = false
    private val sharedPref: SharedPreferences by inject()
    private val lockdownManager: LockdownManager by inject()

    private val kioskModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val enabled = intent?.getBooleanExtra("enabled", true) ?: return
            setKioskMode(enabled)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setNavHost()

        // Subscribe to FCM here — app process is fully running
        subscribeToFcm()

        registerKioskReceiver()

        // Handle kiosk intent from KioskModeReceiver
        handleKioskIntent(intent)

        // Restore kiosk state on first launch
        if (MyDeviceAdminReceiver.isDeviceOwner(this)) {
            setKioskMode(sharedPref.kioskEnabled)
        }
    }

    private fun registerKioskReceiver() {
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            kioskModeReceiver,
            IntentFilter(KioskModeReceiver.ACTION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun setNavHost() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHostFragment.navController
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle kiosk intent when activity is already running
        handleKioskIntent(intent)
    }

    private fun handleKioskIntent(intent: Intent?) {
        intent ?: return
        if (intent.hasExtra(KioskModeReceiver.EXTRA_KIOSK_ENABLED)) {
            val enabled = intent.getBooleanExtra(
                KioskModeReceiver.EXTRA_KIOSK_ENABLED, true
            )
            Log.d(TAG, "handleKioskIntent: enabled=$enabled")
            setKioskMode(enabled)
            // Clear the extra so it doesn't re-trigger on rotation
            intent.removeExtra(KioskModeReceiver.EXTRA_KIOSK_ENABLED)
        }
    }

    override fun onResume() {
        super.onResume()

        subscribeToFcm()

        // Sync kiosk state — handles relaunch after OTA install
        if (MyDeviceAdminReceiver.isDeviceOwner(this)) {
            val shouldBeKiosk = sharedPref.kioskEnabled
            if (shouldBeKiosk && !kioskActive) {
                setKioskMode(true)
            } else if (!shouldBeKiosk && kioskActive) {
                setKioskMode(false)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(kioskModeReceiver)
    }

    fun setKioskMode(enabled: Boolean) {
        kioskActive = enabled
        sharedPref.kioskEnabled = enabled
        if (enabled) {
            try {
                startLockTask()
                Log.d(TAG, "Lock task started")
            } catch (e: Exception) {
                Log.d(TAG, "startLockTask failed: ${e.message}")
            }
        } else {
            try {
                stopLockTask()
                Log.d(TAG, "Lock task stopped")
            } catch (e: Exception) {
                Log.d(TAG, "stopLockTask failed: ${e.message}")
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!kioskActive) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_MENU -> true

            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDeviceRestored() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finishAffinity()
    }

    private fun subscribeToFcm() {
        FirebaseMessaging
            .getInstance()
            .subscribeToTopic("all-devices")
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}