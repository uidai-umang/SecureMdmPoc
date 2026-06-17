package gov.uidai.securemdmpoc.data.model

data class TokenUpdateRequest(
    val model: String,
    val manufacturer: String,
    val fcmToken: String
)