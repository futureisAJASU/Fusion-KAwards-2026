package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ClaimClassification
import com.projectnuke.fusion.data.EvidenceClaimClassifier
import com.projectnuke.fusion.data.EvidenceClaimSplitter
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.data.MessageEntity
import java.security.MessageDigest
import java.util.Locale

/**
 * A selectable text block as it is actually emitted by MarkdownDocumentContent.
 * Ranges are UTF-16 offsets in this rendered block text, never offsets in raw
 * Markdown. Link destinations are intentionally absent from [text].
 */
internal data class AnswerTextRenderedBlock(
    val id: String,
    val text: String,
    val structural: Boolean,
    val codeRanges: List<AnswerTextRenderedRange> = emptyList(),
    val citationRanges: List<AnswerTextRenderedRange> = emptyList(),
)

internal data class AnswerTextRenderedRange(val start: Int, val endExclusive: Int) {
    fun overlaps(start: Int, endExclusive: Int): Boolean =
        this.start < endExclusive && start < this.endExclusive
}

internal enum class AnswerTextSelectionRefusal {
    EMPTY,
    TOO_LONG,
    MULTIPLE_TEXT_NODES,
    UNMAPPED,
    AMBIGUOUS,
    STRUCTURAL_CONTENT,
    CODE_CONTENT,
    CITATION_CONTENT,
    URL_OR_INTERNAL_MARKER,
    INCOMPLETE_STATEMENT,
    MULTIPLE_CLAIMS,
    NOT_CHECKABLE,
    STALE_ANSWER,
}

internal data class AnswerTextSelectionSnapshot(
    val selectionId: String,
    val conversationId: Long,
    val assistantMessageId: Long,
    val canonicalAnswerHash: String,
    val answerSurfaceIdentity: AnswerSurfaceIdentity,
    val renderedAnswerIdentity: String,
    val renderedBlockId: String,
    val renderedBlockTextHash: String,
    val exactSelectedText: String,
) {
    val claimId: String
        get() = "S" + sha256(selectionId + "|" + renderedBlockId + "|" + exactSelectedText).take(32)

    companion object {
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

internal sealed interface AnswerTextSelectionDecision {
    data class Eligible(val snapshot: AnswerTextSelectionSnapshot) : AnswerTextSelectionDecision

    data class Unsupported(
        val selectedParts: List<String>,
        val reason: AnswerTextSelectionRefusal,
        val explanation: String,
    ) : AnswerTextSelectionDecision {
        val exactSelectedText: String?
            get() = selectedParts.singleOrNull()
    }
}

/**
 * Maps the exact Compose selection back to one unique visible Markdown text
 * block. It never searches persisted Markdown or uses copied clipboard text.
 * Heading blocks are structural and are refused; list item body text maps
 * separately from the list marker and remains eligible when it is one claim.
 */
internal object AnswerTextSelectionPolicy {
    const val MAX_SELECTION_LENGTH = 1_000
    private const val MAX_RENDERED_BLOCKS = 512
    private const val MAX_RENDERED_TEXT_LENGTH = 100_000

    fun renderedBlocks(document: MarkdownDocument): List<AnswerTextRenderedBlock> {
        val blocks = ArrayList<AnswerTextRenderedBlock>()
        document.blocks.forEachIndexed { index, block ->
            when (block.kind) {
                MarkdownBlockKind.CODE -> blocks += AnswerTextRenderedBlock(
                    id = "block-" + index + "-code",
                    text = block.codeText,
                    structural = true,
                    codeRanges = if (block.codeText.isEmpty()) emptyList()
                    else listOf(AnswerTextRenderedRange(0, block.codeText.length)),
                )
                MarkdownBlockKind.TABLE -> blocks += AnswerTextRenderedBlock(
                    id = "block-" + index + "-table",
                    text = block.text,
                    structural = true,
                )
                MarkdownBlockKind.ORDERED_LIST_ITEM,
                MarkdownBlockKind.UNORDERED_LIST_ITEM -> {
                    val marker = "  ".repeat(block.listDepth.coerceAtMost(12)) +
                        (if (block.isOrderedList) block.listNumber.toString() + ". " else "• ")
                    blocks += AnswerTextRenderedBlock(
                        id = "block-" + index + "-marker",
                        text = marker,
                        structural = true,
                    )
                    blocks += inlineRenderedBlock(
                        id = "block-" + index + "-body",
                        spans = block.inlineSpans,
                        structural = false,
                    )
                }
                MarkdownBlockKind.HEADING -> blocks += inlineRenderedBlock(
                    id = "block-" + index + "-heading",
                    spans = block.inlineSpans,
                    structural = true,
                )
                MarkdownBlockKind.PARAGRAPH -> blocks += inlineRenderedBlock(
                    id = "block-" + index + "-paragraph",
                    spans = block.inlineSpans,
                    structural = false,
                )
            }
        }
        return blocks
    }

    fun renderedIdentity(blocks: List<AnswerTextRenderedBlock>): String {
        val canonical = buildString {
            blocks.forEach { block ->
                append(block.id)
                append('\u0000')
                append(if (block.structural) '1' else '0')
                append('\u0000')
                append(block.text)
                append('\u0000')
                block.codeRanges.forEach { range ->
                    append("c:")
                    append(range.start)
                    append(':')
                    append(range.endExclusive)
                    append(';')
                }
                block.citationRanges.forEach { range ->
                    append("e:")
                    append(range.start)
                    append(':')
                    append(range.endExclusive)
                    append(';')
                }
                append('\u0001')
            }
        }
        return sha256(canonical)
    }

    fun capture(
        conversationId: Long,
        message: MessageEntity,
        answerSurfaceIdentity: AnswerSurfaceIdentity,
        selectedParts: List<String>,
        document: MarkdownDocument,
        selectionId: String,
    ): AnswerTextSelectionDecision {
        val stableParts = selectedParts.toList()
        val selectedText = stableParts.singleOrNull()
        if (stableParts.isEmpty() || stableParts.all { it.isBlank() }) {
            return unsupported(stableParts, AnswerTextSelectionRefusal.EMPTY)
        }
        if (stableParts.size != 1) {
            return unsupported(stableParts, AnswerTextSelectionRefusal.MULTIPLE_TEXT_NODES)
        }
        if (selectedText == null || selectedText.isBlank()) {
            return unsupported(stableParts, AnswerTextSelectionRefusal.EMPTY)
        }
        if (selectedText.length > MAX_SELECTION_LENGTH) {
            return unsupported(stableParts, AnswerTextSelectionRefusal.TOO_LONG)
        }
        val canonicalHash = EvidenceFingerprint.canonicalAnswerHash(message.content)
        if (message.role != "assistant" || message.id <= 0L ||
            message.conversationId != conversationId || conversationId <= 0L ||
            answerSurfaceIdentity != AnswerSurfaceIdentity.of(conversationId, message) ||
            answerSurfaceIdentity.contentHash != canonicalHash
        ) {
            return unsupported(stableParts, AnswerTextSelectionRefusal.STALE_ANSWER)
        }
        if (selectionId.isBlank()) {
            return unsupported(stableParts, AnswerTextSelectionRefusal.STALE_ANSWER)
        }

        val blocks = renderedBlocks(document)
        val visibleLength = blocks.sumOf { it.text.length }
        if (blocks.size > MAX_RENDERED_BLOCKS || visibleLength > MAX_RENDERED_TEXT_LENGTH) {
            return unsupported(stableParts, AnswerTextSelectionRefusal.UNMAPPED)
        }
        val occurrences = blocks.flatMap { block ->
            occurrences(block.text, selectedText).map { range -> block to range }
        }
        if (occurrences.isEmpty()) {
            return unsupported(stableParts, AnswerTextSelectionRefusal.UNMAPPED)
        }
        if (occurrences.size != 1) {
            return unsupported(stableParts, AnswerTextSelectionRefusal.AMBIGUOUS)
        }
        val (block, range) = occurrences.single()
        if (block.codeRanges.any { it.overlaps(range.start, range.endExclusive) }) {
            return unsupported(stableParts, AnswerTextSelectionRefusal.CODE_CONTENT)
        }
        if (block.structural) {
            return unsupported(stableParts, AnswerTextSelectionRefusal.STRUCTURAL_CONTENT)
        }
        if (block.citationRanges.any { it.overlaps(range.start, range.endExclusive) }) {
            return unsupported(stableParts, AnswerTextSelectionRefusal.CITATION_CONTENT)
        }
        if (containsUrlOrInternalMarker(selectedText)) {
            return unsupported(stableParts, AnswerTextSelectionRefusal.URL_OR_INTERNAL_MARKER)
        }
        if (!isOneCompleteStatement(block.text, range, selectedText)) {
            return unsupported(stableParts, AnswerTextSelectionRefusal.INCOMPLETE_STATEMENT)
        }
        val classification = EvidenceClaimClassifier.classifyWithReason(selectedText)
        if (classification.classification != ClaimClassification.CHECKABLE_FACT) {
            return unsupported(stableParts, AnswerTextSelectionRefusal.NOT_CHECKABLE)
        }

        return AnswerTextSelectionDecision.Eligible(
            AnswerTextSelectionSnapshot(
                selectionId = selectionId,
                conversationId = conversationId,
                assistantMessageId = message.id,
                canonicalAnswerHash = canonicalHash,
                answerSurfaceIdentity = answerSurfaceIdentity,
                renderedAnswerIdentity = renderedIdentity(blocks),
                renderedBlockId = block.id,
                renderedBlockTextHash = sha256(block.text),
                exactSelectedText = selectedText,
            ),
        )
    }

    fun isCurrent(
        snapshot: AnswerTextSelectionSnapshot,
        currentIdentity: AnswerSurfaceIdentity,
        currentRenderedAnswerIdentity: String,
        message: MessageEntity,
    ): Boolean =
        snapshot.answerSurfaceIdentity == currentIdentity &&
            snapshot.conversationId == currentIdentity.conversationId &&
            snapshot.assistantMessageId == currentIdentity.assistantMessageId &&
            snapshot.canonicalAnswerHash == currentIdentity.contentHash &&
            snapshot.renderedAnswerIdentity == currentRenderedAnswerIdentity &&
            snapshot.canonicalAnswerHash == EvidenceFingerprint.canonicalAnswerHash(message.content) &&
            message.role == "assistant" &&
            message.id == snapshot.assistantMessageId &&
            message.conversationId == snapshot.conversationId

    private fun inlineRenderedBlock(
        id: String,
        spans: List<MarkdownInlineSpan>,
        structural: Boolean,
    ): AnswerTextRenderedBlock {
        var offset = 0
        val text = buildString { spans.forEach { append(it.text) } }
        val code = mutableListOf<AnswerTextRenderedRange>()
        val citations = mutableListOf<AnswerTextRenderedRange>()
        spans.forEach { span ->
            val end = offset + span.text.length
            if (span.code && end > offset) code += AnswerTextRenderedRange(offset, end)
            if (span.citationSource != null && end > offset) citations += AnswerTextRenderedRange(offset, end)
            offset = end
        }
        return AnswerTextRenderedBlock(id, text, structural, code, citations)
    }

    private fun occurrences(haystack: String, needle: String): List<AnswerTextRenderedRange> {
        if (needle.isEmpty() || haystack.isEmpty()) return emptyList()
        val result = mutableListOf<AnswerTextRenderedRange>()
        var from = 0
        while (from <= haystack.length - needle.length) {
            val found = haystack.indexOf(needle, from)
            if (found < 0) break
            result += AnswerTextRenderedRange(found, found + needle.length)
            from = found + 1
        }
        return result
    }

    private fun isOneCompleteStatement(
        blockText: String,
        range: AnswerTextRenderedRange,
        selectedText: String,
    ): Boolean {
        if (selectedText != selectedText.trim()) return false
        val split = EvidenceClaimSplitter.splitWindow(selectedText)
        if (split.truncated || split.claims.size != 1 || split.claims.singleOrNull() != selectedText) return false
        val last = selectedText.lastOrNull { !it.isWhitespace() } ?: return false
        if (last !in ".!?。！？") return false
        val prefix = blockText.substring(0, range.start).trimEnd()
        if (prefix.isNotEmpty() && !prefix.lastOrNull { !it.isWhitespace() }.isSentenceBoundary()) return false
        return true
    }

    private fun Char?.isSentenceBoundary(): Boolean =
        this == null || this in ".!?。！？"

    private fun containsUrlOrInternalMarker(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        return normalized.contains("http://") ||
            normalized.contains("https://") ||
            normalized.contains("www.") ||
            text.contains("\uE000FUSION_CITATION_") ||
            Regex("""\[(?:E|S)\d{1,8}]""").containsMatchIn(text) ||
            text.contains("<|") ||
            text.contains("|>")
    }

    private fun unsupported(
        parts: List<String>,
        reason: AnswerTextSelectionRefusal,
    ) = AnswerTextSelectionDecision.Unsupported(parts.toList(), reason, explanation(reason))

    private fun explanation(reason: AnswerTextSelectionRefusal): String = when (reason) {
        AnswerTextSelectionRefusal.EMPTY -> "선택한 텍스트를 읽지 못했습니다. 확인할 문장을 다시 선택해 주세요."
        AnswerTextSelectionRefusal.TOO_LONG -> "선택한 범위가 너무 깁니다. 한 문장만 선택해 주세요."
        AnswerTextSelectionRefusal.MULTIPLE_TEXT_NODES -> "여러 텍스트 블록에 걸친 선택은 확인할 수 없습니다. 한 문장 전체를 한 번에 선택해 주세요."
        AnswerTextSelectionRefusal.UNMAPPED -> "선택한 텍스트를 현재 표시된 답변 블록에 연결할 수 없습니다. 문장을 다시 선택해 주세요."
        AnswerTextSelectionRefusal.AMBIGUOUS -> "같은 문장이 여러 위치에 있어 선택 위치를 특정할 수 없습니다. 문장을 다시 선택해 주세요."
        AnswerTextSelectionRefusal.STRUCTURAL_CONTENT -> "제목이나 목록 기호 같은 구조 텍스트는 확인할 수 없습니다. 본문 문장을 선택해 주세요."
        AnswerTextSelectionRefusal.CODE_CONTENT -> "코드 안의 텍스트는 문장 확인 대상이 아닙니다."
        AnswerTextSelectionRefusal.CITATION_CONTENT -> "근거 인용 표시는 확인 문장에 포함할 수 없습니다. 인용 표시를 제외하고 선택해 주세요."
        AnswerTextSelectionRefusal.URL_OR_INTERNAL_MARKER -> "링크 주소나 내부 표시는 확인 문장으로 사용할 수 없습니다. 화면에 보이는 문장만 선택해 주세요."
        AnswerTextSelectionRefusal.INCOMPLETE_STATEMENT -> "한 문장 전체가 선택되지 않았거나 여러 문장이 포함되어 있습니다. 완전한 사실 문장 하나를 선택해 주세요."
        AnswerTextSelectionRefusal.MULTIPLE_CLAIMS -> "서로 다른 주장이 함께 선택되었습니다. 확인할 사실 문장 하나만 선택해 주세요."
        AnswerTextSelectionRefusal.NOT_CHECKABLE -> "선택한 내용은 확인 가능한 사실 문장으로 분류되지 않았습니다."
        AnswerTextSelectionRefusal.STALE_ANSWER -> "답변이 변경되어 이 선택을 사용할 수 없습니다. 현재 답변에서 다시 선택해 주세요."
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
