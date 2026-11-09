package com.projectnuke.fusion.handoff

import com.projectnuke.fusion.retrieval.LocalRagBounds

/**
 * How an external Android -> Fusion text handoff is presented, decided purely
 * by payload size. Blank input never reaches this policy (the parser rejects
 * it first). Classification is side-effect free and never alters the payload.
 *
 *  - [INLINE_COMPOSER]: the exact payload is durably written into the fresh
 *    NEW_CONVERSATION_ID composer as ordinary untrusted user text. No file is
 *    created, no RAG copy is made, and the text only enters ordinary
 *    conversation history once the user sends it.
 *  - [MANAGED_TEXT_ATTACHMENT]: the payload goes through the managed
 *    "공유된 텍스트.txt" local-document flow (managed attachment directory,
 *    attachment import ownership, Local RAG continuity across later turns).
 *  - [REJECT_TOO_LARGE]: beyond the hard [LocalRagBounds.MAX_DOC_CHARS]
 *    ceiling; rejected clearly, never silently truncated.
 */
enum class ExternalTextHandoffPresentation {
    INLINE_COMPOSER,
    MANAGED_TEXT_ATTACHMENT,
    REJECT_TOO_LARGE,
}

/**
 * Hybrid presentation policy for text handoffs.
 *
 * INLINE_COMPOSER requires BOTH of:
 *
 *  - UTF-8 encoded size <= [MAX_INLINE_UTF8_BYTES]
 *  - logical line count <= [MAX_INLINE_LINES]
 *
 * Anything else within the hard [LocalRagBounds.MAX_DOC_CHARS] ceiling uses
 * the managed attachment flow; anything beyond it is rejected.
 *
 * The payload itself is never trimmed or normalized merely to classify it:
 * the accepted text is preserved exactly byte-for-byte by the handoff flow.
 */
object ExternalTextHandoffPresentationPolicy {

    const val MAX_INLINE_UTF8_BYTES = 2 * 1024

    const val MAX_INLINE_LINES = 24

    fun classify(text: String): ExternalTextHandoffPresentation {
        if (text.length > LocalRagBounds.MAX_DOC_CHARS) {
            return ExternalTextHandoffPresentation.REJECT_TOO_LARGE
        }
        if (utf8ByteSize(text) > MAX_INLINE_UTF8_BYTES) {
            return ExternalTextHandoffPresentation.MANAGED_TEXT_ATTACHMENT
        }
        if (logicalLineCount(text) > MAX_INLINE_LINES) {
            return ExternalTextHandoffPresentation.MANAGED_TEXT_ATTACHMENT
        }
        return ExternalTextHandoffPresentation.INLINE_COMPOSER
    }

    /** Exact UTF-8 encoded size in bytes (surrogate pairs are 4 bytes). */
    internal fun utf8ByteSize(text: String): Int =
        text.toByteArray(Charsets.UTF_8).size

    /**
     * Deterministic '\n'-separated logical line count. A trailing newline
     * therefore counts the final empty line exactly as written.
     */
    internal fun logicalLineCount(text: String): Int = text.split('\n').size
}