package com.projectnuke.fusion.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.util.SafeWebUrlPolicy

/** Authoritative finalized assistant Markdown presentation surface.
 *
 * Receives ONLY sanitized user-visible assistant text (after stripping
 * attachments, internal metadata, trust/evidence envelopes, and
 * search-source metadata). Never passes raw persisted internal content
 * directly to the Markdown parser.
 */
@Composable
fun AssistantMarkdownContent(
    visibleAnswer: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle(fontSize = 18.sp, lineHeight = 29.sp),
    color: Color? = null,
    onOpenLink: (String) -> Unit = {},
) {
    AssistantMarkdownAnswerContent(
        visibleAnswer = visibleAnswer,
        modifier = modifier,
        style = style,
        color = color ?: MaterialTheme.colorScheme.onSurface,
        onOpenLink = onOpenLink,
    )
}

@Composable
internal fun AssistantMarkdownAnswerContent(
    visibleAnswer: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle(fontSize = 18.sp, lineHeight = 29.sp),
    color: Color = MaterialTheme.colorScheme.onSurface,
    onOpenLink: (String) -> Unit = {},
    selectionState: SelectionState? = null,
    onSelectionAction: ((List<String>, MarkdownDocument, String) -> Unit)? = null,
    onRenderedAnswerIdentity: ((String) -> Unit)? = null,
) {
    val document = androidx.compose.runtime.remember(visibleAnswer) { parseMarkdownDocument(visibleAnswer) }
    MarkdownDocumentContent(
        document = document,
        modifier = modifier,
        style = style,
        color = color,
        onOpenLink = onOpenLink,
        selectionState = selectionState,
        onSelectionAction = onSelectionAction,
        onRenderedAnswerIdentity = onRenderedAnswerIdentity,
    )
}

/** Explicit block-level document model. Inline syntax is never represented as
 * separate vertical blocks.
 */
internal data class MarkdownDocument(val blocks: List<MarkdownBlock>)

internal enum class MarkdownBlockKind {
    PARAGRAPH,
    HEADING,
    ORDERED_LIST_ITEM,
    UNORDERED_LIST_ITEM,
    TABLE,
    CODE,
}

internal data class MarkdownInlineSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val linkUrl: String? = null,
    val citationSource: EvidenceSource? = null,
)

internal data class MarkdownBlock(
    val kind: MarkdownBlockKind,
    val inlineSpans: List<MarkdownInlineSpan> = emptyList(),
    val codeText: String = "",
    val headingLevel: Int = 0,
    val listNumber: Int = 0,
    val listDepth: Int = 0,
    val tableHeader: List<List<MarkdownInlineSpan>> = emptyList(),
    val tableRows: List<List<List<MarkdownInlineSpan>>> = emptyList(),
) {
    /** Compatibility/readability projection used by focused tests and callers. */
    val text: String get() = when (kind) {
        MarkdownBlockKind.CODE -> codeText
        MarkdownBlockKind.TABLE -> (listOf(tableHeader) + tableRows).joinToString("\n") { row ->
            row.joinToString(" | ") { cell -> cell.joinToString("") { it.text } }
        }
        else -> inlineSpans.joinToString("") { it.text }
    }
    val isBold: Boolean
        get() = kind == MarkdownBlockKind.HEADING || inlineSpans.any { it.bold }
    val isItalic: Boolean
        get() = inlineSpans.any { it.italic }
    val isCodeInline: Boolean
        get() = inlineSpans.any { it.code }
    val isLink: Boolean
        get() = inlineSpans.any { it.linkUrl != null }
    val linkUrl: String?
        get() = inlineSpans.firstOrNull { it.linkUrl != null }?.linkUrl
    val isHeading: Int
        get() = if (kind == MarkdownBlockKind.HEADING) headingLevel else 0
    val isListItem: Boolean
        get() = kind == MarkdownBlockKind.ORDERED_LIST_ITEM || kind == MarkdownBlockKind.UNORDERED_LIST_ITEM
    val isOrderedList: Boolean
        get() = kind == MarkdownBlockKind.ORDERED_LIST_ITEM
    val isCodeBlock: Boolean
        get() = kind == MarkdownBlockKind.CODE
    val isTable: Boolean
        get() = kind == MarkdownBlockKind.TABLE
}

/** Retained as the small test seam for callers that only need block nodes. */
internal fun parseMarkdownBlocks(raw: String): List<MarkdownBlock> = parseMarkdownDocument(raw).blocks

/** Parse block syntax first, then parse each block's inline content into spans. */
internal fun parseMarkdownDocument(raw: String): MarkdownDocument =
    parseMarkdownDocumentInternal(raw.replace("\r\n", "\n").replace('\r', '\n'), emptyMap())

/** Internal presentation-only citation insertion. The inserted token never
 * enters the persisted answer or the Markdown source shown to the user.
 */
internal data class MarkdownCitationInsertion(
    val offset: Int,
    val sources: List<EvidenceSource>,
)

internal fun parseMarkdownDocumentWithCitations(
    raw: String,
    insertions: List<MarkdownCitationInsertion>,
): MarkdownDocument {
    if (insertions.isEmpty()) return parseMarkdownDocument(raw)
    val markers = linkedMapOf<String, List<EvidenceSource>>()
    val augmented = buildString(raw.length + insertions.size * 24) {
        var cursor = 0
        insertions.sortedBy { it.offset }.forEachIndexed { index, insertion ->
            val offset = insertion.offset.coerceIn(cursor, raw.length)
            append(raw.substring(cursor, offset))
            val token = "\uE000FUSION_CITATION_$index\uE001"
            markers[token] = insertion.sources
            append(token)
            cursor = offset
        }
        append(raw.substring(cursor))
    }
    return parseMarkdownDocumentInternal(augmented.replace("\r\n", "\n").replace('\r', '\n'), markers)
}

private fun parseMarkdownDocumentInternal(
    raw: String,
    citationMarkers: Map<String, List<EvidenceSource>>,
): MarkdownDocument {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraphLines = mutableListOf<String>()
    var inCodeBlock = false
    var codeFence: String? = null
    var codeStarted = false
    val codeLines = mutableListOf<String>()
    var inIndentedCodeBlock = false
    var pendingIndentedBlankLines = 0

    fun flushParagraph() {
        if (paragraphLines.isEmpty()) return
        blocks += MarkdownBlock(
            kind = MarkdownBlockKind.PARAGRAPH,
            inlineSpans = parseInlineSpans(paragraphLines.joinToString("\n"), citationMarkers),
        )
        paragraphLines.clear()
    }

    fun flushCode() {
        if (!codeStarted) return
        blocks += MarkdownBlock(
            kind = MarkdownBlockKind.CODE,
            codeText = codeLines.joinToString("\n"),
        )
        codeLines.clear()
        codeStarted = false
    }

    val lines = raw.split('\n')
    var consumedTableEnd = -1
    lines.forEachIndexed { lineIndex, line ->
        if (lineIndex <= consumedTableEnd) return@forEachIndexed
        val trimmed = line.trim()
        if (inCodeBlock) {
            val fence = codeFence!!
            val closingLength = trimmed.takeWhile { it == fence[0] }.length
            if (closingLength >= fence.length && trimmed.substring(closingLength).isBlank()) {
                inCodeBlock = false
                codeFence = null
                flushCode()
            } else {
                codeLines += line
            }
            return@forEachIndexed
        }

        if (inIndentedCodeBlock) {
            val indent = leadingIndentColumns(line)
            if (line.isBlank()) {
                pendingIndentedBlankLines++
                return@forEachIndexed
            }
            if (indent >= 4) {
                repeat(pendingIndentedBlankLines) { codeLines += "" }
                pendingIndentedBlankLines = 0
                codeLines += dropIndentColumns(line, 4)
                return@forEachIndexed
            }
            // Trailing blank lines are not part of an indented code block.
            pendingIndentedBlankLines = 0
            inIndentedCodeBlock = false
            flushCode()
        }

        val openingFence = when {
            trimmed.startsWith("```") -> trimmed.takeWhile { it == '`' }
            trimmed.startsWith("~~~") -> trimmed.takeWhile { it == '~' }
            else -> null
        }
        if (openingFence != null) {
            flushParagraph()
            val remainder = trimmed.substring(openingFence.length)
            val inlineClose = remainder.indexOf(openingFence)
            if (inlineClose >= 0) {
                blocks += MarkdownBlock(
                    kind = MarkdownBlockKind.CODE,
                    codeText = remainder.substring(0, inlineClose).trimEnd(),
                )
                val trailingText = remainder.substring(inlineClose + openingFence.length).trim()
                if (trailingText.isNotEmpty()) {
                    blocks += MarkdownBlock(
                        kind = MarkdownBlockKind.PARAGRAPH,
                        inlineSpans = parseInlineSpans(trailingText, citationMarkers),
                    )
                }
            } else {
                // The optional language identifier is deliberately ignored.
                inCodeBlock = true
                codeFence = openingFence
                codeStarted = true
            }
            return@forEachIndexed
        }

        if (lineIndex + 1 < lines.size && isMarkdownTableHeader(line, lines[lineIndex + 1])) {
            flushParagraph()
            val header = parseMarkdownTableRow(line).orEmpty()
            val rows = mutableListOf<List<String>>()
            var next = lineIndex + 2
            while (next < lines.size) {
                val row = parseMarkdownTableRow(lines[next]) ?: break
                if (row.size != header.size || row.all(::isMarkdownTableSeparatorCell)) break
                rows += row
                next++
            }
            blocks += MarkdownBlock(
                kind = MarkdownBlockKind.TABLE,
                tableHeader = header.map { parseInlineSpans(it, citationMarkers) },
                tableRows = rows.map { row -> row.map { parseInlineSpans(it, citationMarkers) } },
            )
            consumedTableEnd = next - 1
            // The separator is consumed together with the header and rows.
            return@forEachIndexed
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            return@forEachIndexed
        }

        val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(trimmed)
        if (heading != null) {
            flushParagraph()
            blocks += MarkdownBlock(
                kind = MarkdownBlockKind.HEADING,
                inlineSpans = parseInlineSpans(heading.groupValues[2], citationMarkers),
                headingLevel = heading.groupValues[1].length,
            )
            return@forEachIndexed
        }

        val ordered = Regex("^(\\d+)\\.\\s+(.+)$").matchEntire(trimmed)
        if (ordered != null) {
            flushParagraph()
            blocks += MarkdownBlock(
                kind = MarkdownBlockKind.ORDERED_LIST_ITEM,
                inlineSpans = parseInlineSpans(ordered.groupValues[2], citationMarkers),
                listNumber = ordered.groupValues[1].toIntOrNull() ?: 1,
                listDepth = leadingIndentColumns(line) / 2,
            )
            return@forEachIndexed
        }

        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            flushParagraph()
            blocks += MarkdownBlock(
                kind = MarkdownBlockKind.UNORDERED_LIST_ITEM,
                inlineSpans = parseInlineSpans(trimmed.substring(2).trim(), citationMarkers),
                listDepth = leadingIndentColumns(line) / 2,
            )
            return@forEachIndexed
        }

        if (leadingIndentColumns(line) >= 4) {
            flushParagraph()
            inIndentedCodeBlock = true
            codeStarted = true
            codeLines += dropIndentColumns(line, 4)
            return@forEachIndexed
        }

        paragraphLines += trimmed
    }

    flushParagraph()
    if (inCodeBlock || inIndentedCodeBlock) flushCode()
    return MarkdownDocument(blocks)
}

private fun isMarkdownTableHeader(headerLine: String, separatorLine: String): Boolean {
    val header = parseMarkdownTableRow(headerLine) ?: return false
    val separator = parseMarkdownTableRow(separatorLine) ?: return false
    return header.isNotEmpty() && separator.size == header.size && separator.all(::isMarkdownTableSeparatorCell)
}

private fun parseMarkdownTableRow(line: String): List<String>? {
    var source = line.trim()
    if ('|' !in source) return null
    if (source.startsWith('|')) source = source.substring(1)
    if (source.endsWith('|') && !source.endsWith("\\|")) source = source.dropLast(1)
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var index = 0
    while (index < source.length) {
        when {
            source[index] == '\\' && index + 1 < source.length && source[index + 1] == '|' -> {
                cell.append('|')
                index += 2
            }
            source[index] == '|' -> {
                cells += cell.toString().trim()
                cell.clear()
                index++
            }
            else -> cell.append(source[index++])
        }
    }
    cells += cell.toString().trim()
    return cells.takeIf { it.isNotEmpty() }
}

private fun isMarkdownTableSeparatorCell(cell: String): Boolean =
    cell.matches(Regex(":?-{3,}:?"))

private fun leadingIndentColumns(line: String): Int {
    var columns = 0
    for (character in line) {
        when (character) {
            ' ' -> columns++
            '\t' -> columns += 4 - columns % 4
            else -> return columns
        }
    }
    return columns
}

private fun dropIndentColumns(line: String, target: Int): String {
    var index = 0
    var columns = 0
    while (index < line.length && columns < target) {
        when (line[index]) {
            ' ' -> columns++
            '\t' -> columns += 4 - columns % 4
            else -> break
        }
        index++
    }
    return line.substring(index)
}

private fun isMarkdownEscapable(character: Char): Boolean =
    character in '!'..'/' || character in ':'..'@' || character in '['..'`' || character in '{'..'~'

/** Inline parser for a bounded CommonMark subset. It is a tokenizing parser,
 * not regex-only styling: block nodes own inline span nodes, and nested
 * emphasis is parsed recursively into those spans.
 */
private fun parseInlineSpans(
    source: String,
    citationMarkers: Map<String, List<EvidenceSource>> = emptyMap(),
): List<MarkdownInlineSpan> {
    val spans = mutableListOf<MarkdownInlineSpan>()
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            appendMerged(spans, MarkdownInlineSpan(plain.toString()))
            plain.clear()
        }
    }

    fun appendStyled(inner: List<MarkdownInlineSpan>, bold: Boolean = false, italic: Boolean = false, linkUrl: String? = null) {
        inner.forEach { span ->
            appendMerged(
                spans,
                span.copy(
                    bold = span.bold || bold,
                    italic = span.italic || italic,
                    linkUrl = linkUrl ?: span.linkUrl,
                ),
            )
        }
    }

    var position = 0
    while (position < source.length) {
        val citationToken = citationMarkers.keys.firstOrNull { source.startsWith(it, position) }
        if (citationToken != null) {
            flushPlain()
            citationMarkers[citationToken].orEmpty().forEach { sourceRef ->
                appendMerged(spans, MarkdownInlineSpan("[${sourceRef.id}]", citationSource = sourceRef))
            }
            position += citationToken.length
            continue
        }

        if (source.startsWith("**", position)) {
            val end = source.indexOf("**", position + 2)
            if (end > position + 2) {
                flushPlain()
                appendStyled(parseInlineSpans(source.substring(position + 2, end), citationMarkers), bold = true)
                position = end + 2
                continue
            }
        }

        if (source[position] == '\\' && position + 1 < source.length && isMarkdownEscapable(source[position + 1])) {
            plain.append(source[position + 1])
            position += 2
            continue
        }

        if (source[position] == '*' && !source.startsWith("**", position)) {
            val end = source.indexOf('*', position + 1)
            if (end > position + 1) {
                flushPlain()
                appendStyled(parseInlineSpans(source.substring(position + 1, end), citationMarkers), italic = true)
                position = end + 1
                continue
            }
        }

        if (source[position] == '`') {
            var tickEnd = position
            while (tickEnd < source.length && source[tickEnd] == '`') tickEnd++
            val ticks = tickEnd - position
            val delimiter = "`".repeat(ticks)
            val end = source.indexOf(delimiter, position + ticks)
            if (end > position + ticks) {
                flushPlain()
                appendMerged(spans, MarkdownInlineSpan(source.substring(position + ticks, end), code = true))
                position = end + ticks
                continue
            }
        }

        if (source[position] == '[') {
            val closeBracket = source.indexOf(']', position + 1)
            if (closeBracket > position + 1 && closeBracket + 1 < source.length && source[closeBracket + 1] == '(') {
                val closeParen = findLinkClose(source, closeBracket + 2)
                if (closeParen > closeBracket + 2) {
                    flushPlain()
                    val label = source.substring(position + 1, closeBracket)
                    val url = source.substring(closeBracket + 2, closeParen)
                    appendStyled(parseInlineSpans(label, citationMarkers), linkUrl = url)
                    position = closeParen + 1
                    continue
                }
            }
        }

        plain.append(source[position])
        position++
    }
    flushPlain()
    return spans
}

private fun findLinkClose(source: String, openParen: Int): Int {
    var depth = 1
    var position = openParen
    while (position < source.length) {
        when (source[position]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return position
            }
        }
        position++
    }
    return -1
}

private fun appendMerged(target: MutableList<MarkdownInlineSpan>, span: MarkdownInlineSpan) {
    if (span.text.isEmpty()) return
    val previous = target.lastOrNull()
    if (previous != null &&
        previous.bold == span.bold &&
        previous.italic == span.italic &&
        previous.code == span.code &&
        previous.linkUrl == span.linkUrl &&
        previous.citationSource == span.citationSource
    ) {
        target[target.lastIndex] = previous.copy(text = previous.text + span.text)
    } else {
        target += span
    }
}

/** Legacy-shaped projection retained for focused parser tests. */
internal data class InlineSegment(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: Boolean = false,
    val linkUrl: String? = null,
)

internal fun splitInlineSegments(line: String): List<InlineSegment> =
    parseInlineSpans(line).map { span ->
        InlineSegment(span.text, span.bold, span.italic, span.code, span.linkUrl != null, span.linkUrl)
    }

@Composable
internal fun MarkdownDocumentContent(
    document: MarkdownDocument,
    modifier: Modifier = Modifier,
    style: TextStyle,
    color: Color,
    onOpenLink: (String) -> Unit = {},
    onOpenSource: (EvidenceSource) -> Unit = {},
    selectionState: SelectionState? = null,
    onSelectionAction: ((List<String>, MarkdownDocument, String) -> Unit)? = null,
    onRenderedAnswerIdentity: ((String) -> Unit)? = null,
) {
    val renderedBlocks = androidx.compose.runtime.remember(document) {
        AnswerTextSelectionPolicy.renderedBlocks(document)
    }
    val renderedIdentity = androidx.compose.runtime.remember(renderedBlocks) {
        AnswerTextSelectionPolicy.renderedIdentity(renderedBlocks)
    }
    SideEffect { onRenderedAnswerIdentity?.invoke(renderedIdentity) }
    val selectedParts = selectionState?.selectedTexts?.map { it.text }.orEmpty()
    val selectionModifier = if (selectionState != null && onSelectionAction != null) {
        Modifier.appendTextContextMenuComponents {
            if (selectedParts.any { it.isNotEmpty() }) {
                item(
                    key = "fusion_verify_selected_sentence",
                    label = "이 문장 확인",
                ) {
                    // The context menu can outlive the selection that created its action.
                    val currentSelection = selectionState?.selectedTexts?.map { it.text }.orEmpty()
                    if (currentSelection.any { it.isNotEmpty() }) {
                        onSelectionAction(currentSelection, document, renderedIdentity)
                    }
                    close()
                }
            }
        }
    } else {
        Modifier
    }
    val content: @Composable () -> Unit = {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            document.blocks.forEach { block ->
                when (block.kind) {
                    MarkdownBlockKind.CODE -> CodeBlockContent(block.codeText)
                    MarkdownBlockKind.HEADING -> MarkdownInlineText(
                        spans = block.inlineSpans,
                        style = style.copy(fontSize = headingFontSize(block.headingLevel)),
                        color = color,
                        onOpenLink = onOpenLink,
                        onOpenSource = onOpenSource,
                        heading = true,
                    )
                    MarkdownBlockKind.ORDERED_LIST_ITEM,
                    MarkdownBlockKind.UNORDERED_LIST_ITEM -> ListItemContent(
                        spans = block.inlineSpans,
                        ordered = block.isOrderedList,
                        number = block.listNumber,
                        depth = block.listDepth,
                        style = style,
                        color = color,
                        onOpenLink = onOpenLink,
                        onOpenSource = onOpenSource,
                    )
                    MarkdownBlockKind.TABLE -> MarkdownTableContent(
                        block = block,
                        style = style,
                        color = color,
                        onOpenLink = onOpenLink,
                        onOpenSource = onOpenSource,
                    )
                    MarkdownBlockKind.PARAGRAPH -> MarkdownInlineText(
                        spans = block.inlineSpans,
                        style = style,
                        color = color,
                        onOpenLink = onOpenLink,
                        onOpenSource = onOpenSource,
                    )
                }
            }
        }
    }
    if (selectionState != null) {
        SelectionContainer(state = selectionState, modifier = selectionModifier, content = content)
    } else {
        SelectionContainer(content = content)
    }
}

@Composable
private fun MarkdownInlineText(
    spans: List<MarkdownInlineSpan>,
    style: TextStyle,
    color: Color,
    onOpenLink: (String) -> Unit,
    onOpenSource: (EvidenceSource) -> Unit,
    heading: Boolean = false,
) {
    Text(
        text = buildMarkdownInlineAnnotatedString(
            spans, color, onOpenLink, onOpenSource, heading,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
        style = style,
        color = color,
    )
}

internal fun buildMarkdownAnnotatedString(
    document: MarkdownDocument,
    color: Color = Color.Unspecified,
    onOpenLink: (String) -> Unit = {},
    onOpenSource: (EvidenceSource) -> Unit = {},
): AnnotatedString = buildAnnotatedString {
    document.blocks.forEachIndexed { index, block ->
        if (index > 0) append("\n")
        when (block.kind) {
            MarkdownBlockKind.CODE -> append(block.codeText)
            MarkdownBlockKind.HEADING,
            MarkdownBlockKind.PARAGRAPH -> appendInlineSpans(block.inlineSpans, color, onOpenLink, onOpenSource, block.kind == MarkdownBlockKind.HEADING)
            MarkdownBlockKind.ORDERED_LIST_ITEM,
            MarkdownBlockKind.UNORDERED_LIST_ITEM -> {
                append("  ".repeat(block.listDepth.coerceAtMost(12)))
                append(if (block.isOrderedList) "${block.listNumber}. " else "• ")
                appendInlineSpans(block.inlineSpans, color, onOpenLink, onOpenSource)
            }
            MarkdownBlockKind.TABLE -> {
                (listOf(block.tableHeader) + block.tableRows).forEachIndexed { rowIndex, row ->
                    if (rowIndex > 0) append("\n")
                    row.forEachIndexed { cellIndex, cell ->
                        if (cellIndex > 0) append(" | ")
                        appendInlineSpans(cell, color, onOpenLink, onOpenSource, heading = rowIndex == 0)
                    }
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.appendInlineSpans(
    spans: List<MarkdownInlineSpan>,
    color: Color,
    onOpenLink: (String) -> Unit,
    onOpenSource: (EvidenceSource) -> Unit,
    heading: Boolean = false,
    codeColor: Color = Color.Unspecified,
    codeBackground: Color = Color.Unspecified,
) {
    spans.forEach { span ->
        val spanStyle = SpanStyle(
            color = when {
                span.code -> codeColor
                span.citationSource != null -> Color(0xFF69B7FF)
                else -> color
            },
            fontWeight = when {
                heading -> FontWeight.SemiBold
                span.bold -> FontWeight.Bold
                else -> FontWeight.Normal
            },
            fontStyle = if (span.italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = when {
                span.citationSource != null || (span.linkUrl != null && isSafeLinkUrl(span.linkUrl)) -> TextDecoration.Underline
                else -> TextDecoration.None
            },
            background = if (span.code) codeBackground else Color.Transparent,
        )
        when {
            span.citationSource != null -> {
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "fusion-evidence:${span.citationSource.id}",
                        styles = TextLinkStyles(style = spanStyle),
                        linkInteractionListener = { onOpenSource(span.citationSource) },
                    )
                ) { append(span.text) }
            }
            span.linkUrl != null && isSafeLinkUrl(span.linkUrl) -> {
                withLink(
                    LinkAnnotation.Clickable(
                        tag = span.linkUrl,
                        styles = TextLinkStyles(style = spanStyle),
                        linkInteractionListener = { onOpenLink(span.linkUrl) },
                    )
                ) { append(span.text) }
            }
            else -> withStyle(spanStyle) { append(span.text) }
        }
    }
}

private fun buildMarkdownInlineAnnotatedString(
    spans: List<MarkdownInlineSpan>,
    color: Color,
    onOpenLink: (String) -> Unit,
    onOpenSource: (EvidenceSource) -> Unit,
    heading: Boolean = false,
    codeColor: Color = Color.Unspecified,
    codeBackground: Color = Color.Unspecified,
): AnnotatedString = buildAnnotatedString {
    appendInlineSpans(spans, color, onOpenLink, onOpenSource, heading, codeColor, codeBackground)
}

@Composable
private fun ListItemContent(
    spans: List<MarkdownInlineSpan>,
    ordered: Boolean,
    number: Int,
    depth: Int,
    style: TextStyle,
    color: Color,
    onOpenLink: (String) -> Unit,
    onOpenSource: (EvidenceSource) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(12) * 16).dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = if (ordered) "$number. " else "• ", style = style, color = color)
        MarkdownInlineText(spans, style, color, onOpenLink, onOpenSource)
    }
}

@Composable
private fun MarkdownTableContent(
    block: MarkdownBlock,
    style: TextStyle,
    color: Color,
    onOpenLink: (String) -> Unit,
    onOpenSource: (EvidenceSource) -> Unit,
) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        (listOf(block.tableHeader) + block.tableRows).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                row.forEach { cell ->
                    val cellColor = if (rowIndex == 0) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface
                    androidx.compose.material3.Surface(color = cellColor) {
                        Box(Modifier.width(160.dp).padding(horizontal = 8.dp, vertical = 6.dp)) {
                            MarkdownInlineText(
                                spans = cell,
                                style = if (rowIndex == 0) style.copy(fontWeight = FontWeight.SemiBold) else style,
                                color = color,
                                onOpenLink = onOpenLink,
                                onOpenSource = onOpenSource,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockContent(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp, horizontal = 8.dp),
    ) {
        Text(text = text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
    }
}

private fun headingFontSize(level: Int) = when (level) {
    1 -> 28.sp
    2 -> 24.sp
    3 -> 20.sp
    else -> 18.sp
}

/** Safe link launcher: only normal browser URI launching. */
internal fun isSafeLinkUrl(url: String): Boolean {
    return SafeWebUrlPolicy.normalize(url) != null
}
