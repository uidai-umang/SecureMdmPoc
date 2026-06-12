package gov.uidai.securemdmpoc.data.model

data class CheckInRequest(
    val packageName: String,
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val kioskActive: Boolean,
    val isDeviceOwner: Boolean,
    val timestamp: Long,
    val fcmToken: String?
)
