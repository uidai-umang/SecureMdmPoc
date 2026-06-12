package gov.uidai.securemdmpoc.data.model

data class CheckInResponse(
    val status: String,
    val message: String,
    val enterpriseId: String,
    val policyName: String
)
