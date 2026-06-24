package gov.uidai.securemdmpoc.manager

import android.app.admin.DevicePolicyManager
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
import gov.uidai.securemdmpoc.util.Utils
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Permission state — type-safe wrapper around DevicePolicyManager's
 * raw Int grant-state constants, used everywhere we set a permission
 * via setPermissionState() instead of passing the raw Int directly.
 */
enum class PermissionState(val dpmValue: Int) {
    GRANTED(DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED),
    DENIED(DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED),
    DEFAULT(DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)
}

class DynamicAppManager(
    private val context: Context,
    private val deviceOwner: DeviceOwnerContext,
    private val repository: AppManagementRepository
) {
    private val TAG = "DynamicAppManager"

    private val ourPackage = context.packageName
    private val dpm get() = deviceOwner.dpm
    private val admin get() = deviceOwner.admin
    val isDeviceOwner get() = deviceOwner.isDeviceOwner

    private val pm = context.packageManager

    /** Storage permissions — single source of truth, used by deny/restore both. */
    private val storagePermissions: List<String> = buildList {
        add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(android.Manifest.permission.READ_MEDIA_IMAGES)
            add(android.Manifest.permission.READ_MEDIA_VIDEO)
        }
    }

    // ── Master method ─────────────────────────────────────────

    fun applyDynamicRestrictions(): RestrictionReport {
        var hiddenCount = 0
        var skippedCount = 0
        var cameraDeniedCount = 0
        var storageDeniedCount = 0
        var failedCount = 0
        val newlyHidden = mutableListOf<String>()

        classifyAllApps().forEach { classification ->
            val pkg = classification.packageName

            if (isSuspendTarget(pkg)) {
                suspendPackage()
                skippedCount++
                return@forEach
            }

            try {
                if (classification.shouldHide) {
                    if (hideApp(pkg)) {
                        hiddenCount++
                        newlyHidden.add(pkg)
                    } else {
                        skippedCount++
                    }
                } else {
                    skippedCount++
                }

                if (classification.shouldDenyCamera) {
                    setCameraState(pkg, PermissionState.DENIED)
                    cameraDeniedCount++
                }
                if (classification.shouldDenyStorage) {
                    setStorageState(pkg, PermissionState.DENIED)
                    storageDeniedCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed processing $pkg: ${e.message}")
                failedCount++
            }
        }

        if (newlyHidden.isNotEmpty()) {
            HiddenAppsStore.addAll(context, newlyHidden)
        }
        reportToBackend("HIDE_APPS", newlyHidden)

        Log.d(
            TAG, """
            Dynamic restrictions applied:
            Hidden        : $hiddenCount
            Skipped       : $skippedCount
            Camera denied : $cameraDeniedCount
            Storage denied: $storageDeniedCount
            Failed        : $failedCount
        """.trimIndent()
        )

        return RestrictionReport(hiddenCount, skippedCount, cameraDeniedCount, failedCount)
    }

    fun restoreAll() {
        unsuspendAllSuspendTargets()

        val hidden = HiddenAppsStore.load(context).toList()
        if (hidden.isEmpty()) {
            Log.d(TAG, "restoreAll — store is empty, no-op")
            return
        }

        val restored = mutableListOf<String>()

        hidden.forEach { pkg ->
            try {
                dpm.setApplicationHidden(admin, pkg, false)
                setCameraState(pkg, PermissionState.DEFAULT)
                setStorageState(pkg, PermissionState.DEFAULT)
                restored.add(pkg)
                Log.d(TAG, "Restored: $pkg")
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore $pkg: ${e.message}")
            }
        }

        HiddenAppsStore.clear(context)
        reportToBackend("UNHIDE_APPS", restored)
        Log.d(TAG, "restoreAll complete — ${restored.size} apps restored")
    }

    // ── Single-app hide/unhide ─────────────────────────────────

    fun hideSingleApp(packageName: String): Boolean {
        if (isSuspendTarget(packageName)) {
            suspendPackage()
            return true
        }

        val result = hideApp(packageName)
        if (result) reportToBackend("HIDE_APP", listOf(packageName))
        return result
    }

    fun unhideSingleApp(packageName: String) {
        if (isSuspendTarget(packageName)) {
            unsuspendAllSuspendTargets()
            reportToBackend("UNHIDE_APP", listOf(packageName))
            return
        }

        try {
            dpm.setApplicationHidden(admin, packageName, false)
            Log.d(TAG, "Unhidden: $packageName")
            reportToBackend("UNHIDE_APP", listOf(packageName))
        } catch (e: Exception) {
            Log.e(TAG, "unhideSingleApp failed for $packageName: ${e.message}")
        }
    }

    // ── Per-package policy (called on fresh install/update) ────

    fun denyCameraForPackage(packageName: String) {
        if (!isDeviceOwner || packageName == ourPackage) return
        setCameraState(packageName, PermissionState.DENIED)
    }

    fun denyStoragePermissions(pkg: String) = setStorageState(pkg, PermissionState.DENIED)
    fun restoreStoragePermissions(pkg: String) = setStorageState(pkg, PermissionState.DEFAULT)

    // ── Classification (unchanged logic, only return type changes) ──

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

    fun classifyApp(appInfo: ApplicationInfo): AppClassification {
        val pkg = appInfo.packageName

        if (isOurAppOrExempt(pkg)) {
            setCameraState(pkg, PermissionState.GRANTED)
            return AppClassification(
                pkg,
                AppCategory.OUR_APP,
                shouldHide = false,
                shouldDenyCamera = false,
                shouldDenyStorage = false
            )
        }

        if (isAbsolutelyEssential(appInfo)) {
            return classification(
                pkg,
                AppCategory.ESSENTIAL,
                hide = false,
                camera = false,
                storage = false
            )
        }

        if (isLauncherApp(pkg)) {
            // Never hide launchers — causes boot loop
            return classification(
                pkg,
                AppCategory.SYSTEM_UI,
                hide = false,
                camera = false,
                storage = false
            )
        }

        val permissions = getDeclaredPermissions(pkg)
        val hasCamera = permissions.contains(android.Manifest.permission.CAMERA)
        val hasInternet = permissions.contains(android.Manifest.permission.INTERNET)
        val hasCall = permissions.contains(android.Manifest.permission.CALL_PHONE)
        val hasSms = permissions.contains(android.Manifest.permission.SEND_SMS) ||
                permissions.contains(android.Manifest.permission.READ_SMS)
        val requestsStorage = permissions.any { it in storagePermissions }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (appInfo.category == ApplicationInfo.CATEGORY_SOCIAL || appInfo.category == ApplicationInfo.CATEGORY_NEWS)
        ) {
            return classification(
                pkg,
                AppCategory.COMMUNICATION,
                hide = true,
                camera = hasCamera,
                storage = requestsStorage
            )
        }
        if (hasCall || hasSms) {
            return classification(
                pkg,
                AppCategory.COMMUNICATION,
                hide = true,
                camera = hasCamera,
                storage = requestsStorage
            )
        }

        if (isMediaApp(appInfo, permissions)) {
            return classification(
                pkg,
                AppCategory.MEDIA,
                hide = true,
                camera = hasCamera,
                storage = requestsStorage
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (appInfo.category == ApplicationInfo.CATEGORY_PRODUCTIVITY ||
                    appInfo.category == ApplicationInfo.CATEGORY_MAPS ||
                    appInfo.category == ApplicationInfo.CATEGORY_GAME)
        ) {
            return classification(
                pkg,
                AppCategory.PRODUCTIVITY,
                hide = true,
                camera = hasCamera,
                storage = requestsStorage
            )
        }

        if (hasCamera && !hasInternet && !hasCall) {
            return classification(
                pkg,
                AppCategory.CAMERA_DEDICATED,
                hide = true,
                camera = true,
                storage = requestsStorage
            )
        }
        if (hasCamera && hasInternet) {
            return classification(
                pkg,
                AppCategory.CAMERA_CAPABLE,
                hide = true,
                camera = true,
                storage = requestsStorage
            )
        }

        if (isBrowserApp(pkg)) {
            return classification(
                pkg,
                AppCategory.BROWSER,
                hide = true,
                camera = hasCamera,
                storage = requestsStorage
            )
        }
        if (isAppStore(pkg)) {
            return classification(
                pkg,
                AppCategory.STORE,
                hide = true,
                camera = hasCamera,
                storage = requestsStorage
            )
        }
        if (isVoiceAssistant(pkg)) {
            return classification(
                pkg,
                AppCategory.ASSISTANT,
                hide = true,
                camera = hasCamera,
                storage = requestsStorage
            )
        }
        if (isLauncherApp(pkg)) {
            return classification(
                pkg,
                AppCategory.SYSTEM_UI,
                hide = true,
                camera = hasCamera,
                storage = requestsStorage
            )
        }

        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (!hasCamera && !hasCall && !hasSms && !hasInternet && !requestsStorage && isSystem) {
            return classification(
                pkg,
                AppCategory.SAFE_UTILITY,
                hide = false,
                camera = false,
                storage = false
            )
        }

        return classification(
            pkg, AppCategory.UNKNOWN,
            hide = isSystem, camera = hasCamera, storage = requestsStorage
        )
    }

    private fun classification(
        pkg: String, category: AppCategory, hide: Boolean, camera: Boolean, storage: Boolean
    ) = AppClassification(
        pkg,
        category,
        shouldHide = hide,
        shouldDenyCamera = camera,
        shouldDenyStorage = storage
    )

    // ── Predicates — single source of truth for "what kind of package is this" ──

    private fun isOurAppOrExempt(pkg: String) =
        pkg == ourPackage || Utils.excemptionPackages.contains(pkg)

    private fun isSuspendTarget(pkg: String) = Utils.packagesToSuspend.contains(pkg)

    private fun isAbsolutelyEssential(appInfo: ApplicationInfo): Boolean {
        val pkg = appInfo.packageName
        if (pkg == ourPackage) return true
        if (pkg == "com.google.android.apps.work.clouddpc") return true
        if (appInfo.uid == Process.SYSTEM_UID) return true
        if (appInfo.uid == 2000) return true

        val isPersistent = (appInfo.flags and ApplicationInfo.FLAG_PERSISTENT) != 0
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (isPersistent && isSystem) return true

        try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            @Suppress("DEPRECATION")
            if (pkgInfo.sharedUserId == "com.google.uid.shared") return true
        } catch (e: Exception) {
        }

        if (isSystem && !hasLauncherIcon(pkg)) return true
        return false
    }

    private fun hasLauncherIcon(pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(pkg)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L)).isNotEmpty()
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0).isNotEmpty()
        }
    }

    private fun isBrowserApp(pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse("http://www.example.com")
        }
        return resolveActivities(intent).any { it.activityInfo.packageName == pkg }
    }

    private fun isLauncherApp(pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        return resolveActivities(intent).any {
            it.activityInfo.packageName == pkg && it.activityInfo.packageName != ourPackage
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (appInfo.category == ApplicationInfo.CATEGORY_VIDEO ||
                    appInfo.category == ApplicationInfo.CATEGORY_IMAGE ||
                    appInfo.category == ApplicationInfo.CATEGORY_AUDIO)
        ) return true

        val intent = Intent(Intent.ACTION_VIEW).apply { type = "image/*" }
        val handlesImage =
            resolveActivities(intent).any { it.activityInfo.packageName == appInfo.packageName }
        val hasMediaPerm = permissions.contains(android.Manifest.permission.READ_MEDIA_IMAGES)
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

    private fun getDeclaredPermissions(pkg: String): List<String> {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    pkg,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
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

    // ── Single generic permission setter — replaces 4 duplicated methods ──

    private fun setPermissionState(
        pkg: String,
        permission: String,
        state: PermissionState
    ): Boolean {
        return try {
            dpm.setPermissionGrantState(admin, pkg, permission, state.dpmValue)
            true
        } catch (e: Exception) {
            Log.w(TAG, "setPermissionState($permission, $state) failed for $pkg: ${e.message}")
            false
        }
    }

    private fun setCameraState(pkg: String, state: PermissionState) =
        setPermissionState(pkg, android.Manifest.permission.CAMERA, state)

    private fun setStorageState(pkg: String, state: PermissionState) {
        storagePermissions.forEach { setPermissionState(pkg, it, state) }
    }

    // ── Hide/unhide low-level ──────────────────────────────────

    private fun hideApp(pkg: String): Boolean {
        return try {
            val result = dpm.setApplicationHidden(admin, pkg, true)
            if (result) {
                HiddenAppsStore.add(context, pkg)
                Log.d(TAG, "Hidden: $pkg")
            } else {
                Log.w(TAG, "setApplicationHidden returned false for $pkg")
            }
            result
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException hiding $pkg: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Exception hiding $pkg: ${e.message}")
            false
        }
    }

    // ── Play Store suspend/unsuspend ───────────────────────────

    private fun suspendPackage() {
        try {
            val result =
                dpm.setPackagesSuspended(admin, Utils.packagesToSuspend.toTypedArray(), true)
            Log.d(TAG, "Suspend result (failed packages): ${result.joinToString()}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not suspend: ${e.message}")
        }
    }

    private fun unsuspendAllSuspendTargets() {
        try {
            dpm.setPackagesSuspended(admin, Utils.packagesToSuspend.toTypedArray(), false)
            Log.d(TAG, "Unsuspended: ${Utils.packagesToSuspend}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not unsuspend: ${e.message}")
        }
    }

    // ── Backend reporting ──────────────────────────────────────

    private fun reportToBackend(action: String, packages: List<String>) {
        CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                repository.reportApps(action, packages)
                Log.d(TAG, "Reported $action — ${packages.size} packages")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report to backend: ${e.message}")
            }
        }
    }
}