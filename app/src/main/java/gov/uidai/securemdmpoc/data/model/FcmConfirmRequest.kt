package gov.uidai.securemdmpoc.data.model

data class FcmConfirmRequest(
    val model: String,
    val manufacturer: String,
    val action: String,
    val status: String,
    val receivedAt: Long
)