// New file: manager/DeviceOwnerContext.kt
package gov.uidai.securemdmpoc.manager

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import gov.uidai.securemdmpoc.MyDeviceAdminReceiver

class DeviceOwnerContext(context: Context) {
    val dpm: DevicePolicyManager = context.getSystemService(
        Context.DEVICE_POLICY_SERVICE
    ) as DevicePolicyManager

    val admin: ComponentName = ComponentName(
        context, MyDeviceAdminReceiver::class.java
    )

    val isDeviceOwner: Boolean
        get() = dpm.isDeviceOwnerApp(adminPackageName)

    private val adminPackageName = context.packageName
}