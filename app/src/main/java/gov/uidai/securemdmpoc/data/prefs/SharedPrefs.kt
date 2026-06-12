package gov.uidai.securemdmpoc.data.prefs

import android.content.Context
import android.content.SharedPreferences

class SharedPreferences(context: Context) {

    private val prefs: SharedPreferences = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Kiosk ─────────────────────────────────────────────

    var kioskEnabled: Boolean
        get() = prefs.getBoolean(KEY_KIOSK, true)
        set(value) = prefs.edit().putBoolean(KEY_KIOSK, value).apply()

    // ── FCM Token ─────────────────────────────────────────

    var fcmToken: String?
        get() = prefs.getString(KEY_FCM_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_FCM_TOKEN, value).apply()

    // ── Last known version ────────────────────────────────

    var lastInstalledVersion: String?
        get() = prefs.getString(KEY_LAST_VERSION, null)
        set(value) = prefs.edit().putString(KEY_LAST_VERSION, value).apply()

    // ── Device provisioned ────────────────────────────────

    var isProvisioned: Boolean
        get() = prefs.getBoolean(KEY_PROVISIONED, false)
        set(value) = prefs.edit().putBoolean(KEY_PROVISIONED, value).apply()

    // ── Clear all ─────────────────────────────────────────

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val PREFS_NAME = "mdm_prefs"
        private const val KEY_KIOSK = "kiosk_enabled"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_LAST_VERSION = "last_installed_version"
        private const val KEY_PROVISIONED = "is_provisioned"
    }
}