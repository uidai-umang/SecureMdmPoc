package gov.uidai.securemdmpoc.manager

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import gov.uidai.securemdmpoc.data.model.AppClassification
import gov.uidai.securemdmpoc.data.model.RestrictionReport
import gov.uidai.securemdmpoc.data.repository.AppManagementRepository
import gov.uidai.securemdmpoc.util.AppCategory
import gov.uidai.securemdmpoc.util.HiddenAppsStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

class DynamicAppManager(private val context: Context, private val repository: AppManagementRepository) {

    private val TAG = "DynamicAppManager"

    private val dpm = context.getSystemService(
        Context.DEVICE_POLICY_SERVICE
    ) as DevicePolicyManager

    private val admin = ComponentName(
        context,
        gov.uidai.securemdmpoc.MyDeviceAdminReceiver::class.java
    )

    private val pm = context.packageManager

    // ── Master method ─────────────────────────────────────────

    fun applyDynamicRestrictions(): RestrictionReport {
        var hiddenCount = 0
        var skippedCount = 0
        var cameraDeniedCount = 0
        var failedCount = 0
        val hiddenPackages = mutableListOf<String>()

        val apps = classifyAllApps()

        apps.forEach { classification ->
            try {
                when {
                    classification.shouldHide -> {
                        val hidden = hideApp(classification.packageName)
                        if (hidden) {
                            hiddenCount++
                            hiddenPackages.add(classification.packageName)
                            HiddenAppsStore.add(context, classification.packageName)
                        } else {
                            skippedCount++
                        }
                        if (classification.shouldDenyCamera) {
                            denyCameraPermission(classification.packageName)
                            cameraDeniedCount++
                        }
                    }
                    classification.shouldDenyCamera -> {
                        denyCameraPermission(classification.packageName)
                        cameraDeniedCount++
                        skippedCount++
                    }
                    else -> skippedCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed processing ${classification.packageName}: ${e.message}")
                failedCount++
            }
        }

        // Report to backend
        reportToBackend("HIDE_APPS", hiddenPackages)

        Log.d(TAG, """
        Dynamic restrictions applied:
        Hidden       : $hiddenCount
        Skipped      : $skippedCount
        Camera denied: $cameraDeniedCount
        Failed       : $failedCount
    """.trimIndent())

        return RestrictionReport(hiddenCount, skippedCount, cameraDeniedCount, failedCount)
    }

    fun restoreAll() {
        val hidden = HiddenAppsStore.load(context).toList()

        if (hidden.isEmpty()) {
            Log.d(TAG, "restoreAll — store is empty, no-op")
            return
        }

        val restored = mutableListOf<String>()

        hidden.forEach { packageName ->
            try {
                dpm.setApplicationHidden(admin, packageName, false)
                dpm.setPermissionGrantState(
                    admin, packageName,
                    android.Manifest.permission.CAMERA,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                )
                restored.add(packageName)
                Log.d(TAG, "Restored: $packageName")
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore $packageName: ${e.message}")
            }
        }

        HiddenAppsStore.clear(context)

        // Report to backend
        reportToBackend("UNHIDE_APPS", restored)

        Log.d(TAG, "restoreAll complete — ${restored.size} apps restored")
    }

    private fun reportToBackend(action: String, packages: List<String>) {
        CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                repository.reportApps(
                    action,
                    packages
                )
                Log.d(TAG, "Reported $action — ${packages.size} packages to backend")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report to backend: ${e.message}")
            }
        }
    }
    // ── Classify all apps ─────────────────────────────────────

    fun classifyAllApps(): List<AppClassification> {
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }

        return apps.map { classifyApp(it) }
    }

    // ── Classify single app ───────────────────────────────────

    fun classifyApp(appInfo: ApplicationInfo): AppClassification {
        val pkg = appInfo.packageName

        // Priority 1 — our app
        if (pkg == context.packageName) {
            grantCameraPermission(pkg)
            return AppClassification(pkg, AppCategory.OUR_APP, false, false)
        }

        // Priority 2 — essential
        if (isAbsolutelyEssential(appInfo)) {
            return AppClassification(pkg, AppCategory.ESSENTIAL, false, false)
        }

        if (isLauncherApp(pkg)) {
            // Never hide launchers — causes boot loop
            return AppClassification(pkg, AppCategory.SYSTEM_UI, false, false)
        }

        val permissions = getDeclaredPermissions(pkg)
        val hasCamera = permissions.contains(android.Manifest.permission.CAMERA)
        val hasInternet = permissions.contains(android.Manifest.permission.INTERNET)
        val hasCall = permissions.contains(android.Manifest.permission.CALL_PHONE)
        val hasSms = permissions.contains(android.Manifest.permission.SEND_SMS) ||
                permissions.contains(android.Manifest.permission.READ_SMS)

        // Priority 3 — communication
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (appInfo.category == ApplicationInfo.CATEGORY_SOCIAL ||
                appInfo.category == ApplicationInfo.CATEGORY_NEWS) {
                return AppClassification(pkg, AppCategory.COMMUNICATION, true, hasCamera)
            }
        }
        if (hasCall || hasSms) {
            return AppClassification(pkg, AppCategory.COMMUNICATION, true, hasCamera)
        }

        // Priority 4 — media
        if (isMediaApp(appInfo, permissions)) {
            return AppClassification(pkg, AppCategory.MEDIA, true, hasCamera)
        }

        // Priority 5 — productivity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (appInfo.category == ApplicationInfo.CATEGORY_PRODUCTIVITY ||
                appInfo.category == ApplicationInfo.CATEGORY_MAPS ||
                appInfo.category == ApplicationInfo.CATEGORY_GAME) {
                return AppClassification(pkg, AppCategory.PRODUCTIVITY, true, hasCamera)
            }
        }

        // Priority 6 — camera dedicated
        if (hasCamera && !hasInternet && !hasCall) {
            return AppClassification(pkg, AppCategory.CAMERA_DEDICATED, true, true)
        }

        // Priority 7 — camera capable
        if (hasCamera && hasInternet) {
            return AppClassification(pkg, AppCategory.CAMERA_CAPABLE, true, true)
        }

        // Priority 8 — browser
        if (isBrowserApp(pkg)) {
            return AppClassification(pkg, AppCategory.BROWSER, true, hasCamera)
        }

        // Priority 9 — store
        if (isAppStore(pkg)) {
            return AppClassification(pkg, AppCategory.STORE, true, hasCamera)
        }

        // Priority 10 — assistant
        if (isVoiceAssistant(pkg)) {
            return AppClassification(pkg, AppCategory.ASSISTANT, true, hasCamera)
        }

        // Priority 11 — system UI / launcher
        if (isLauncherApp(pkg)) {
            return AppClassification(pkg, AppCategory.SYSTEM_UI, true, hasCamera)
        }

        // Priority 12 — safe utility
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (!hasCamera && !hasCall && !hasSms && !hasInternet && isSystem) {
            return AppClassification(pkg, AppCategory.SAFE_UTILITY, false, false)
        }

        // Priority 13 — unknown
        return if (isSystem) {
            AppClassification(pkg, AppCategory.UNKNOWN, true, hasCamera)
        } else {
            AppClassification(pkg, AppCategory.UNKNOWN, false, hasCamera)
        }
    }

    private fun isAbsolutelyEssential(appInfo: ApplicationInfo): Boolean {
        val pkg = appInfo.packageName

        // Our app
        if (pkg == context.packageName) return true

        // AMAPI agent
        if (pkg == "com.google.android.apps.work.clouddpc") return true

        // System UID (uid=1000)
        if (appInfo.uid == Process.SYSTEM_UID) return true

        // Shell UID (uid=2000)
        if (appInfo.uid == 2000) return true

        // Persistent system app — core services
        val isPersistent = (appInfo.flags and ApplicationInfo.FLAG_PERSISTENT) != 0
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (isPersistent && isSystem) return true

        // GMS sharedUserId
        try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            @Suppress("DEPRECATION")
            if (pkgInfo.sharedUserId == "com.google.uid.shared") return true
        } catch (e: Exception) { }

        // System app with no launcher icon — background infrastructure
        // These are never meant to be user-visible
        if (isSystem && !hasLauncherIcon(pkg)) return true

        return false
    }

    private fun hasLauncherIcon(pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(pkg)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0L)
            ).isNotEmpty()
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0).isNotEmpty()
        }
    }

    // ── Intent resolution helpers ─────────────────────────────
    private fun isBrowserApp(pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse("http://www.example.com")
        }
        return resolveActivities(intent).any { it.activityInfo.packageName == pkg }
    }

    private fun isLauncherApp(pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        return resolveActivities(intent).any {
            it.activityInfo.packageName == pkg &&
                    it.activityInfo.packageName != context.packageName
        }
    }

    private fun isAppStore(pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
        return resolveActivities(intent).any { it.activityInfo.packageName == pkg }
    }

    private fun isVoiceAssistant(pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_VOICE_COMMAND)
        return resolveActivities(intent).any { it.activityInfo.packageName == pkg }
    }

    private fun isMediaApp(appInfo: ApplicationInfo, permissions: List<String>): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (appInfo.category == ApplicationInfo.CATEGORY_VIDEO ||
                appInfo.category == ApplicationInfo.CATEGORY_IMAGE ||
                appInfo.category == ApplicationInfo.CATEGORY_AUDIO) return true
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "image/*"
        }
        val handlesImage = resolveActivities(intent)
            .any { it.activityInfo.packageName == appInfo.packageName }
        val hasMediaPerm = permissions.contains(
            android.Manifest.permission.READ_MEDIA_IMAGES
        )
        return handlesImage && hasMediaPerm
    }

    @Suppress("DEPRECATION")
    private fun resolveActivities(intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }

    // ── Permission helpers ────────────────────────────────────

    private fun getDeclaredPermissions(pkg: String): List<String> {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    pkg,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_PERMISSIONS.toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            }
            info.requestedPermissions?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── DPM helpers ───────────────────────────────────────────

    private fun hideApp(pkg: String): Boolean {
        return try {
            val result = dpm.setApplicationHidden(admin, pkg, true)
            if (!result) Log.w(TAG, "setApplicationHidden returned false for $pkg")
            result
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException hiding $pkg: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Exception hiding $pkg: ${e.message}")
            false
        }
    }

    fun hideSingleApp(packageName: String): Boolean {
        return try {
            val result = dpm.setApplicationHidden(admin, packageName, true)
            if (result) {
                HiddenAppsStore.add(context, packageName)
                Log.d(TAG, "Hidden: $packageName")
                reportToBackend("HIDE_APP", listOf(packageName))
            } else {
                Log.w(TAG, "setApplicationHidden returned false for $packageName")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "hideSingleApp failed for $packageName: ${e.message}")
            false
        }
    }

    fun unhideSingleApp(packageName: String) {
        try {
            dpm.setApplicationHidden(admin, packageName, false)
            Log.d(TAG, "Unhidden: $packageName")
            reportToBackend("UNHIDE_APP", listOf(packageName))
        } catch (e: Exception) {
            Log.e(TAG, "unhideSingleApp failed for $packageName: ${e.message}")
        }
    }

    private fun denyCameraPermission(pkg: String) {
        try {
            dpm.setPermissionGrantState(
                admin, pkg,
                android.Manifest.permission.CAMERA,
                DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not deny camera for $pkg: ${e.message}")
        }
    }

    private fun grantCameraPermission(pkg: String) {
        try {
            dpm.setPermissionGrantState(
                admin, pkg,
                android.Manifest.permission.CAMERA,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not grant camera for $pkg: ${e.message}")
        }
    }
}