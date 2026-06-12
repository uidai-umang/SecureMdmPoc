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
import gov.uidai.securemdmpoc.data.prefs.SharedPreferences
import gov.uidai.securemdmpoc.manager.LockdownManager
import org.koin.android.ext.android.inject

class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private var kioskActive = false
    private val sharedPref: SharedPreferences by inject()

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

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHostFragment.navController

//        // Subscribe to FCM here — app process is fully running
//        com.google.firebase.messaging.FirebaseMessaging
//            .getInstance()
//            .subscribeToTopic("all-devices")
//            .addOnCompleteListener { task ->
//                Log.d(TAG, "FCM subscription: ${
//                    if (task.isSuccessful) "✅ success" else "❌ failed"
//                }")
//            }
//
//
//        // Handle Android 14+ predictive back gesture
//        onBackPressedDispatcher.addCallback(
//            this,
//            object : androidx.activity.OnBackPressedCallback(true) {
//                override fun handleOnBackPressed() {
//                    if (kioskActive) return // block in kiosk
//                    isEnabled = false
//                    onBackPressedDispatcher.onBackPressed()
//                    isEnabled = true
//                }
//            }
//        )

        androidx.core.content.ContextCompat.registerReceiver(
            this,
            kioskModeReceiver,
            IntentFilter(KioskModeReceiver.ACTION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Handle kiosk intent from KioskModeReceiver
        handleKioskIntent(intent)

        // Restore kiosk state on first launch
        if (MyDeviceAdminReceiver.isDeviceOwner(this)) {
            setKioskMode(sharedPref.kioskEnabled)
        }
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
            setKioskMode(enabled)
            // Clear the extra so it doesn't re-trigger on rotation
            intent.removeExtra(KioskModeReceiver.EXTRA_KIOSK_ENABLED)
        }
    }

    override fun onResume() {
        super.onResume()
//        LockdownManager(this).applyAllPolicies()

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
                Log.e(TAG, "startLockTask failed: ${e.message}")
            }
        } else {
            try {
                stopLockTask()
                Log.d(TAG, "Lock task stopped")
            } catch (e: Exception) {
                Log.e(TAG, "stopLockTask failed: ${e.message}")
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

    companion object {
        private const val TAG = "MainActivity"
    }
}