package com.projectnuke.fusion.ai.model

/**
 * Keeps the editor's capability-list identifier separate from the durable
 * provider representation. The default request patch has no durable ID.
 */
internal object ExternalVariantSelectionPolicy {
    const val DEFAULT_UI_VARIANT_ID = "default"

    fun editorVariantId(durableVariantId: String?): String =
        durableVariantId ?: DEFAULT_UI_VARIANT_ID

    fun durableVariantId(editorVariantId: String?): String? =
        editorVariantId?.takeUnless { it == DEFAULT_UI_VARIANT_ID }

    fun normalizeDurableVariantId(durableVariantId: String?): String? =
        durableVariantId(durableVariantId)

    fun effectiveEditorVariantId(
        requestedEditorVariantId: String?,
        availableVariantIds: Set<String>,
    ): String {
        val requested = editorVariantId(requestedEditorVariantId)
        return if (requested in availableVariantIds) requested else DEFAULT_UI_VARIANT_ID
    }

    fun durableVariantIdForSave(
        requestedEditorVariantId: String?,
        availableVariantIds: Set<String>,
    ): String? = durableVariantId(
        effectiveEditorVariantId(requestedEditorVariantId, availableVariantIds)
    )

    fun rebaseEditorVariantAfterDelete(
        currentEditorVariantId: String?,
        deletedVariantId: String,
    ): String? = if (currentEditorVariantId == deletedVariantId) {
        DEFAULT_UI_VARIANT_ID
    } else {
        currentEditorVariantId
    }
}
