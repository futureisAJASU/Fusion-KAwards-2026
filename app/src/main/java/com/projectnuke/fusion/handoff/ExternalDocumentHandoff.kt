package com.projectnuke.fusion.handoff

/**
 * Source of an Android -> Fusion external document handoff (v2).
 *
 * v2 supports local document streams truthfully readable by
 * [com.projectnuke.fusion.retrieval.LocalRagInputPolicy]: the text family,
 * application/json, .txt/.md/.json/.csv, and PDF text-layer documents whose
 * extraction verifies as READY through Document Intelligence at import
 * time. Office/image/OCR extraction is explicitly out of scope and must
 * never be advertised.
 */
enum class ExternalDocumentHandoffSource {
    SHARE_STREAM,
    SHARE_MULTIPLE,
}

/**
 * One externally delivered document-stream handoff, owned by MainActivity and
 * consumed exactly once by FusionApp after the managed import settles.
 *
 * Only safe URI string identity plus event metadata is persisted across
 * Activity recreation; open streams and ContentResolver objects are never
 * stored. After a successful managed copy, persistent references point only
 * to app-managed canonical files.
 *
 * @param uriStrings ContentResolver URI strings in deterministic sender
 * order (distinct, bounded by [MAX_HANDOFF_DOCS] at import time).
 * @param declaredMimeType The Intent-declared MIME type, if any. Per-URI
 * truth comes from ContentResolver metadata at import time; this is only a
 * hint and is never trusted alone.
 */
data class ExternalDocumentHandoff(
    override val id: String,
    val uriStrings: List<String>,
    val declaredMimeType: String?,
    val source: ExternalDocumentHandoffSource,
    /**
     * How many distinct admitted URIs the original event carried, which may
     * exceed [uriStrings] once the list has been bounded for persistence.
     * Reporting stays truthful about documents that were never persisted.
     */
    val totalDistinctUriCount: Int = uriStrings.size,
) : ExternalHandoff {
    companion object {
        /** Product attachment-capacity parity: normal picker maximum is 5. */
        const val MAX_HANDOFF_DOCS = 5

        /**
         * Hard ceiling on the saved-state envelope for document URIs.
         *
         * The incoming Intent is parsed before this limit can be applied, so it
         * bounds what is PERSISTED rather than what may arrive. Every persisted
         * URI is a `content://` string of practical length, so this also bounds
         * individual string size and therefore the total Bundle payload.
         */
        const val MAX_PERSISTED_URIS = MAX_HANDOFF_DOCS

        /** Individual persisted URI ceiling (Android content URIs are short). */
        const val MAX_PERSISTED_URI_CHARS = 2048

        /** Returns a bounded copy suitable for Android saved state. */
        fun bounded(event: ExternalDocumentHandoff): ExternalDocumentHandoff {
            val admitted = event.uriStrings
                .filter { it.isNotBlank() && it.length <= MAX_PERSISTED_URI_CHARS }
                .distinct()
            // Report the number of documents the event really carried, so a
            // restored event can still tell the user how many were never
            // persisted. Blank entries are not documents and are not counted.
            val total = event.totalDistinctUriCount
                .coerceAtLeast(admitted.size)
                .coerceAtMost(MAX_PERSISTED_URIS * MAX_URI_COUNT_HEADROOM)
            return event.copy(
                uriStrings = admitted.take(MAX_PERSISTED_URIS),
                totalDistinctUriCount = total,
            )
        }

        /** Restores a persisted event, tolerating malformed or oversized state. */
        fun restore(
            id: String,
            uriStrings: List<String>?,
            declaredMimeType: String?,
            source: ExternalDocumentHandoffSource?,
            totalDistinctUriCount: Int?,
        ): ExternalDocumentHandoff {
            val kept = uriStrings.orEmpty()
                .filter { it.isNotBlank() && it.length <= MAX_PERSISTED_URI_CHARS }
                .distinct()
                .take(MAX_PERSISTED_URIS)
            // A missing or corrupt count falls back to the retained subset, and
            // is never allowed to understate what is actually present.
            val total = (totalDistinctUriCount ?: kept.size).coerceAtLeast(kept.size)
            return ExternalDocumentHandoff(
                id = id,
                uriStrings = kept,
                declaredMimeType = declaredMimeType,
                // A caller that cannot yet resolve the enum passes null; it must
                // supply the real source before the event is consumed.
                source = source ?: ExternalDocumentHandoffSource.SHARE_STREAM,
                totalDistinctUriCount = total,
            )
        }

        /** Guards the persisted count against absurd sender-supplied values. */
        private const val MAX_URI_COUNT_HEADROOM = 64
    }
}
