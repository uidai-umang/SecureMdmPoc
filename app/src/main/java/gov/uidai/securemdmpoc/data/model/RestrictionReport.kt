package gov.uidai.securemdmpoc.data.model

data class RestrictionReport(
    val hiddenCount: Int,
    val skippedCount: Int,
    val cameraDeniedCount: Int,
    val failedCount: Int
)
