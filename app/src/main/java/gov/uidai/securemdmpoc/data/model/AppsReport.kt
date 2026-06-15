package gov.uidai.securemdmpoc.data.model

data class AppsReport(
    val packageName: String,
    val model: String,
    val action: String,        // "HIDE_APPS" or "UNHIDE_APPS"
    val packages: List<String>,
    val timestamp: Long
)