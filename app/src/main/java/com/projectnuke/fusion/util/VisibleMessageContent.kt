package com.projectnuke.fusion.util

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadata
import com.projectnuke.fusion.search.stripSearchSourcesMetadata
import com.projectnuke.fusion.trust.TrustRoutingMetadataCodec

/**
 * Canonical visible-message-content contract.
 *
 * Visible (searchable, titlable, exportable as body):
 * - actual user/assistant visible body, including `<fusion_answer>` inner text.
 *
 * Hidden (never searchable, never titled, never exported raw):
 * - V1/V2/V3 attachment machine envelopes (names, MIME types, local paths,
 *   V3 Base64 payloads);
 * - `[FUSION_SEARCH_SOURCES_JSON]` hidden source metadata;
 * - `[FUSION_TRUST_ROUTE_JSON]` hidden routing/provenance metadata;
 * - `[FUSION_ANSWER_CONTEXT_JSON]` immutable scalar context receipt;
 * - `[FUSION_LOCAL_RAG_SOURCES_JSON]` local-RAG provenance (absolute
 *   attachment paths, content hashes, document excerpts);
 * - `<fusion_metrics>` / `<fusion_thinking>` blocks and stray Fusion-owned
 *   `fusion_answer`/`fusion_thinking`/`fusion_metrics` tags, per current
 *   product semantics. Model-native `<think>` / `<thinking>` markers are
 *   intentionally not generic syntax and remain literal here.
 *
 * Both the conversation-list search post-filter and the in-chat search index
 * use [visibleMessageContent], so the two surfaces agree by construction.
 * Envelope removal reuses [AttachmentMessageCodec] parsers instead of
 * duplicating version-specific regexes. Malformed envelopes that no parser
 * accepts are left in place so potential user text is never silently hidden.
 */

private val MetricsBlockRegex = Regex("""(?is)<fusion_metrics>.*?</fusion_metrics>""")
private val ExplicitReasoningTagRegex =
    Regex("""<\s*(/?)\s*(fusion_thinking|think|thinking|fusion_answer)\s*>""", RegexOption.IGNORE_CASE)
private val PartialExplicitTagRegex = Regex("""<\s*(?:/\s*)?([a-z_]*)\s*$""", RegexOption.IGNORE_CASE)
private val FusionOwnedTagNames = setOf("fusion_thinking", "fusion_answer", "fusion_metrics")
private val StrayMetricsTagRegex = Regex("""</?fusion_metrics>""", RegexOption.IGNORE_CASE)
private val WhitespaceRunRegex = Regex("""\s+""")

/** Display name for an attachment placeholder: declared name, else path basename, else fallback. */
fun displayAttachmentName(record: AttachmentRecord): String {
    if (record.name.isNotBlank()) return record.name
    val base = record.localPath.substringAfterLast('/').substringAfterLast('\\')
    if (base.isNotBlank()) return base
    return "파일"
}

/** Removes hidden metadata blocks but keeps attachment envelopes for the caller to handle. */
fun stripHiddenMetadataBlocks(content: String): String = splitExplicitReasoning(content).answer

/**
 * Shared first stage for parsers that must preserve reasoning/metrics long
 * enough to inspect them. It is deliberately kept beside the canonical
 * visible-content authority so callers cannot drift on receipt ordering.
 */
internal fun stripHiddenMetadataEnvelopes(content: String): String =
    TrustRoutingMetadataCodec.strip(
        stripSearchSourcesMetadata(
            LocalRagSourceMetadata.strip(
                com.projectnuke.fusion.chat.AnswerContextReceipt.strip(content),
            ),
        ),
    )

internal data class ExplicitReasoningContent(val thinking: String, val answer: String)

internal val FusionOwnedReasoningTags: Set<String> = setOf("fusion_thinking")

internal fun splitExplicitReasoning(
    content: String,
    reasoningTags: Set<String> = FusionOwnedReasoningTags,
): ExplicitReasoningContent {
    if (content.isEmpty()) return ExplicitReasoningContent("", "")
    val allowed = reasoningTags.map { it.lowercase() }.toSet()
    val structuralTags = allowed + FusionOwnedTagNames
    // Receipt first (its JSON is machine text), then the other provenance
    // envelopes. Every hidden marker is removed before a visible surface sees
    // the answer.
    val clean = stripHiddenMetadataEnvelopes(content)
        .replace(MetricsBlockRegex, "")
        .replace(StrayMetricsTagRegex, "")
    val partialStart = clean.lastIndexOf('<')
    val partial = if (partialStart >= 0) PartialExplicitTagRegex.matchEntire(clean.substring(partialStart)) else null
    val hasPartialTag = partial != null && structuralTags.any {
        it.startsWith(partial.groupValues[1], ignoreCase = true)
    }
    val text = if (hasPartialTag) clean.substring(0, partialStart) else clean
    val thinking = StringBuilder()
    val answer = StringBuilder()
    val openTags = mutableListOf<String>()
    var offset = 0
    ExplicitReasoningTagRegex.findAll(text).forEach { match ->
        val nameLower = match.groupValues[2].lowercase()
        // Native model tags are structural only when this caller supplies
        // the model-family authority that owns them. Generic message
        // utilities therefore preserve <think>/<thinking> byte-for-byte.
        if (nameLower !in structuralTags) {
            val target = when {
                openTags.isEmpty() -> answer
                openTags.all { it in allowed } -> thinking
                else -> null
            }
            target?.append(text, offset, match.range.last + 1)
            offset = match.range.last + 1
            return@forEach
        }
        if (nameLower == "fusion_answer") {
            val target = if (openTags.isEmpty()) answer else if (openTags.all { it in allowed }) thinking else null
            target?.append(text, offset, match.range.first)
            offset = match.range.last + 1
            return@forEach
        }
        val isOpen = match.groupValues[1].isEmpty()
        if (openTags.isEmpty()) {
            answer.append(text, offset, match.range.first)
        } else if (openTags.all { it in allowed }) {
            thinking.append(text, offset, match.range.first)
        }
        if (isOpen) {
            openTags.add(nameLower)
        } else if (openTags.lastOrNull() == nameLower) {
            openTags.removeAt(openTags.lastIndex)
        }
        offset = match.range.last + 1
    }
    if (openTags.isEmpty()) {
        answer.append(text, offset, text.length)
    } else if (openTags.all { it in allowed }) {
        thinking.append(text, offset, text.length)
    }
    return ExplicitReasoningContent(thinking.toString(), answer.toString())
}

/**
 * Canonical visible text of a stored message: attachment envelopes removed,
 * hidden metadata removed, whitespace collapsed.
 */
fun visibleMessageContent(raw: String): String {
    if (raw.isEmpty()) return raw
    return stripHiddenMetadataBlocks(
        AttachmentMessageCodec.replaceAttachmentEnvelopes(raw) { "" },
    ).replace(WhitespaceRunRegex, " ").trim()
}

/**
 * Evidence-staleness visible text: removes the same hidden machine metadata
 * as [visibleMessageContent] (attachment envelopes, search-sources metadata,
 * metrics/thinking blocks, answer unwrapping) but PRESERVES meaningful
 * user-visible formatting (newlines, indentation, intra-line spacing).
 *
 * Only normalizes platform line endings (\r\n / \r -> \n) and trims outer
 * whitespace so that the mere presence/absence of a trailing hidden block
 * does not change the fingerprint.
 */
fun visibleMessageContentPreservingWhitespace(raw: String): String {
    if (raw.isEmpty()) return raw
    val stripped = stripHiddenMetadataBlocks(
        AttachmentMessageCodec.replaceAttachmentEnvelopes(raw) { "" },
    )
    return stripped
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .trim()
}

/**
 * Provider reasoning enters durable content only through Fusion-owned tags.
 * The answer remains the canonical visible body; a blank answer is never
 * manufactured from the reasoning artifact.
 */
internal fun composeExplicitReasoningContent(
    reasoningContent: String?,
    answer: String,
): String {
    val thinking = reasoningContent?.takeIf { it.isNotBlank() } ?: return answer
    return "<fusion_thinking>$thinking</fusion_thinking><fusion_answer>$answer</fusion_answer>"
}

/**
 * Canonical assistant-history text. Unlike generic visible-message helpers,
 * this boundary may consume legacy native reasoning markers only when the
 * caller supplies the assistant model-family authority that owns them.
 *
 * With no such authority, a native marker makes the old row ambiguous. The
 * row is therefore withheld from history rather than risking private legacy
 * reasoning at a downstream provider. User/system content never calls this
 * helper.
 */
internal sealed interface AssistantHistoryResult {
    data class Visible(val text: String) : AssistantHistoryResult
    data object Withhold : AssistantHistoryResult
}

/** A history value whose durable identity travels with the semantic message. */
data class HistoryMessageWithId(
    val durableMessageId: Long,
    val message: ChatMessage,
)

internal fun visibleAssistantHistoryText(
    raw: String,
    reasoningTags: Set<String> = emptySet(),
): AssistantHistoryResult {
    if (raw.isEmpty()) return AssistantHistoryResult.Visible("")
    val normalizedTags = reasoningTags.map { it.lowercase() }.toSet()
    val attachmentFree = AttachmentMessageCodec.replaceAttachmentEnvelopes(raw) { "" }
    val fusionVisible = splitExplicitReasoning(attachmentFree).answer
    val nativeMarkerNames = setOf("think", "thinking")
    val hasUnownedNativeMarker = ExplicitReasoningTagRegex.findAll(fusionVisible).any { match ->
        val name = match.groupValues[2].lowercase()
        name in nativeMarkerNames && name !in normalizedTags
    }
    val partialStart = fusionVisible.lastIndexOf('<')
    val partial = if (partialStart >= 0) {
        PartialExplicitTagRegex.matchEntire(fusionVisible.substring(partialStart))
    } else {
        null
    }
    val hasUnownedNativePartial = partial != null && nativeMarkerNames.any { name ->
        name.startsWith(partial.groupValues[1], ignoreCase = true) &&
            name !in normalizedTags
    }
    if (hasUnownedNativeMarker || hasUnownedNativePartial) return AssistantHistoryResult.Withhold

    val answer = if (normalizedTags.isEmpty()) {
        visibleMessageContentPreservingWhitespace(raw)
    } else {
        splitExplicitReasoning(attachmentFree, normalizedTags).answer
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()
    }
    return AssistantHistoryResult.Visible(answer)
}

/**
 * Applies [visibleAssistantHistoryText] while preserving whole historical
 * user/assistant turns. A privacy-withheld assistant removes its immediately
 * preceding historical user from the same turn; visible empty assistant text
 * remains distinguishable from suppression.
 */
internal fun sanitizeAssistantHistoryTurns(
    messages: List<ChatMessage>,
    reasoningTags: Set<String> = emptySet(),
): List<ChatMessage> = sanitizeAssistantHistoryTurns(
    messages = messages.mapIndexed { index, message ->
        HistoryMessageWithId(Long.MIN_VALUE + index, message)
    },
    reasoningTags = reasoningTags,
).map(HistoryMessageWithId::message)

/**
 * Identity-preserving variant used by persisted local request snapshots.
 * Withholding a legacy reasoning turn removes the user/assistant pair as a
 * single item operation, so later IDs cannot shift onto earlier messages.
 */
@JvmName("sanitizeAssistantHistoryTurnsWithIds")
internal fun sanitizeAssistantHistoryTurns(
    messages: List<HistoryMessageWithId>,
    reasoningTags: Set<String> = emptySet(),
): List<HistoryMessageWithId> {
    val sanitized = mutableListOf<HistoryMessageWithId>()
    var pendingUserIndex: Int? = null
    messages.forEach { item ->
        val message = item.message
        when (message.role.lowercase()) {
            "user" -> {
                sanitized += item
                pendingUserIndex = sanitized.lastIndex
            }
            "assistant" -> {
                when (val result = visibleAssistantHistoryText(message.content, reasoningTags)) {
                    is AssistantHistoryResult.Visible -> {
                        sanitized += item.copy(message = message.copy(content = result.text))
                        pendingUserIndex = null
                    }
                    AssistantHistoryResult.Withhold -> {
                        pendingUserIndex?.let(sanitized::removeAt)
                        pendingUserIndex = null
                    }
                }
            }
            else -> sanitized += item
        }
    }
    return sanitized
}

/**
 * Canonical per-message export body: attachment envelopes become bounded
 * human-readable placeholders (matching the existing export behavior);
 * hidden metadata is removed; raw Base64/internal paths never appear.
 */
fun exportMessageBody(
    raw: String,
    placeholder: (AttachmentRecord) -> String = {
        "\n[첨부파일: ${displayAttachmentName(it)}]\n"
    },
): String {
    if (raw.isEmpty()) return raw
    return stripHiddenMetadataBlocks(
        AttachmentMessageCodec.replaceAttachmentEnvelopes(raw, placeholder),
    ).replace(Regex("""\n{3,}"""), "\n\n").trim()
}

/**
 * Canonical per-message title-candidate text: envelopes and hidden metadata
 * removed, whitespace collapsed. Callers apply their own length policy.
 */
fun titleMessageBody(raw: String): String {
    if (raw.isEmpty()) return raw
    return stripHiddenMetadataBlocks(
        AttachmentMessageCodec.replaceAttachmentEnvelopes(raw) { " " },
    ).replace(WhitespaceRunRegex, " ").trim()
}
