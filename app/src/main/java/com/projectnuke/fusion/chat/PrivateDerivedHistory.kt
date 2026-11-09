package com.projectnuke.fusion.chat

import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadata

/**
 * Conservative containment for private-derived assistant content (Phase 7).
 *
 * An assistant message carrying local-RAG provenance
 * ([LocalRagSourceMetadata]) embeds private attachment-derived facts in its
 * prose. The hidden metadata block is stripped for prompts, but the derived
 * prose remains — so such turns must never be selected for external
 * transmission until a dedicated private-history consent UX exists.
 *
 * Policy (conservative):
 * - external AI history builders remove private-derived turns;
 * - external-target saved memory / conversation summary is suppressed for a
 *   private-tainted branch;
 * - cloud escalation is blocked when its frozen history would contain
 *   private-derived context.
 */
internal fun isPrivateDerivedMessage(content: String): Boolean =
    LocalRagSourceMetadata.hasProvenance(content)

internal fun isPrivateTaintedBranch(messages: List<MessageEntity>): Boolean =
    messages.any { it.role == "assistant" && isPrivateDerivedMessage(it.content) }

internal fun excludePrivateDerivedTurns(messages: List<MessageEntity>): List<MessageEntity> =
    messages.filterNot { it.role == "assistant" && isPrivateDerivedMessage(it.content) }
