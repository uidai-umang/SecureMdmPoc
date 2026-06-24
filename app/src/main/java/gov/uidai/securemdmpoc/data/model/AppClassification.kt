package gov.uidai.securemdmpoc.data.model

import gov.uidai.securemdmpoc.util.AppCategory


data class AppClassification(
    val packageName: String,
    val category: AppCategory,
    val shouldHide: Boolean,
    val shouldDenyCamera: Boolean,
    val shouldDenyStorage: Boolean   // ← new field, must be added
)