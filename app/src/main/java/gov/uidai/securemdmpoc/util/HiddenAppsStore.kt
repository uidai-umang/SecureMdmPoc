package gov.uidai.securemdmpoc.util

import android.content.Context
import gov.uidai.securemdmpoc.data.prefs.SharedPreferences
import android.util.Log
import org.koin.java.KoinJavaComponent.inject

object HiddenAppsStore {

    private const val PREFS_NAME = "hidden_apps_store"
    private const val KEY_PACKAGES = "hidden_packages"
    private const val DELIMITER = "|"
    private const val TAG = "HiddenAppsStore"

    fun add(context: Context, packageName: String) {
        val current = load(context).toMutableSet()
        current.add(packageName)
        save(context, current)
        Log.d(TAG, "Added $packageName — total hidden: ${current.size}")
    }

    fun load(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PACKAGES, "") ?: ""
        if (raw.isEmpty()) return emptySet()
        return raw.split(DELIMITER).filter { it.isNotBlank() }.toSet()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_PACKAGES).apply()
        Log.d(TAG, "Store cleared")
    }

    fun count(context: Context): Int = load(context).size

    private fun save(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PACKAGES, packages.joinToString(DELIMITER))
            .apply()
    }

    fun addAll(context: Context, packageNames: Collection<String>) {
        if (packageNames.isEmpty()) return
        val current = load(context).toMutableSet()
        current.addAll(packageNames)
        save(context, current)
        Log.d(TAG, "Added ${packageNames.size} packages — total hidden: ${current.size}")
    }
}