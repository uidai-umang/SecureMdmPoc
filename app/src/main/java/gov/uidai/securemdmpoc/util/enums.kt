package gov.uidai.securemdmpoc.util

import android.app.admin.DevicePolicyManager

enum class AppCategory {
    OUR_APP,
    ESSENTIAL,
    COMMUNICATION,
    MEDIA,
    PRODUCTIVITY,
    CAMERA_DEDICATED,
    CAMERA_CAPABLE,
    BROWSER,
    STORE,
    ASSISTANT,
    SYSTEM_UI,
    SAFE_UTILITY,
    UNKNOWN
}

enum class PermissionState(val dpmValue: Int) {
    GRANTED(DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED),
    DENIED(DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED),
    DEFAULT(DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)
}