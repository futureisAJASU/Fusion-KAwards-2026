package com.projectnuke.fusion.retrieval

import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import java.io.File

/**
 * Bounded conversation-attachment continuity for LOCAL text retrieval.
 *
 * Today Local RAG prepares from the attachments attached to the current
 * request only; after that turn commits, attachment metadata is stripped from
 * normal history for the model, so a document attached to turn 1 stops being
 * directly retrievable on turn 2. This helper restores a safe form of that
 * continuity: trusted supported text attachments from the active branch join
 * the current request attachments, exactly bounded and deduplicated, WITHOUT
 * changing what is visibly attached to the composer (retrieval continuity,
 * not fake UI state).
 *
 * Rules:
 *  - only trusted managed attachments (existing AttachmentMessageCodec /
 *    AttachmentStorageManager trust classification, no new parsers)
 *  - only existing files (unavailable records never become readable)
 *  - suspicious envelopes contribute NO trust and their message is skipped
 *  - only LocalRagInputPolicy-supported text/document types
 *  - deduplicated by canonical managed local path
 *  - current-request attachments keep priority and order
 *  - historical text attachments follow, newest message first, deterministic
 *  - bounded by [maxDocs] (defaults to LocalRagBounds.MAX_DOCS_PER_REQUEST)
 *  - no arbitrary external paths, no historical images
 *
 * The caller passes history messages in chronological (oldest -> newest)
 * order, which is the order the DAO returns; newest-first ordering is derived
 * deterministically inside.
 */
object ConversationAttachmentContinuity {

    fun collectEffectiveRagAttachments(
        currentAttachments: List<AttachmentRecord>,
        historicalMessages: List<String>,
        attachmentRoot: File,
        maxDocs: Int = LocalRagBounds.MAX_DOCS_PER_REQUEST,
    ): List<AttachmentRecord> {
        if (maxDocs <= 0) return emptyList()
        val effective = ArrayList<AttachmentRecord>(minOf(currentAttachments.size, maxDocs))
        val seen = HashSet<String>()

        for (record in currentAttachments) {
            if (effective.size >= maxDocs) break
            if (record.localPath.isBlank()) continue
            if (!LocalRagInputPolicy.isSupported(record.mimeType, record.name)) continue
            if (!seen.add(record.localPath)) continue
            effective += record
        }

        for (message in historicalMessages.asReversed()) {
            if (effective.size >= maxDocs) break
            val parsed = AttachmentMessageCodec.parseTrustedAttachmentMessage(message, attachmentRoot)
            if (parsed.suspiciousEnvelope) continue
            for (record in parsed.records) {
                if (effective.size >= maxDocs) break
                if (!LocalRagInputPolicy.isSupported(record.mimeType, record.name)) continue
                if (!seen.add(record.localPath)) continue
                effective += record
            }
        }
        return effective
    }
}