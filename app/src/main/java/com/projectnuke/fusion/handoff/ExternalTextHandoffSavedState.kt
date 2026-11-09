package com.projectnuke.fusion.handoff

/** Typed, bounded Activity saved-state policy for a pending text share. */
internal object ExternalTextHandoffSavedState {
    const val MAX_TEXT_CHARS = 50_000

    /** Read only for compatibility with saved Bundles written by the old version. */
    const val LEGACY_OVERFLOW_SENTINEL = "__TOO_LARGE_FOR_SAVED_STATE__"

    data class Encoded(val text: String?, val overflow: Boolean)

    fun encode(text: String): Encoded =
        if (text.length <= MAX_TEXT_CHARS) Encoded(text = text, overflow = false)
        else Encoded(text = null, overflow = true)

    fun restore(
        text: String?,
        overflowMetadataPresent: Boolean,
        overflow: Boolean,
    ): String? = when {
        overflowMetadataPresent && overflow -> null
        overflowMetadataPresent -> text
        // Older Bundles used this content-equal marker. New Bundles carry a
        // separate boolean, so user text equal to this value is preserved.
        text == LEGACY_OVERFLOW_SENTINEL -> null
        else -> text
    }
}
