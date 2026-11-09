package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.SelectionSourceIdentity
import com.projectnuke.fusion.knowledge.MAX_SAVED_NOTE_BODY_CHARS
import com.projectnuke.fusion.knowledge.MAX_SAVED_NOTE_TITLE_CHARS
import com.projectnuke.fusion.knowledge.SavedKnowledgeNoteEditorDraft
import com.projectnuke.fusion.knowledge.SavedKnowledgeNoteReferenceDraft

internal const val STRUCTURED_EXTRACTION_MAX_SOURCE_CHARS = 40_000
internal const val STRUCTURED_EXTRACTION_MAX_ROWS = 100
internal const val STRUCTURED_EXTRACTION_MAX_COLUMNS = 8
internal const val STRUCTURED_EXTRACTION_MAX_CELL_CHARS = 240

internal enum class StructuredExtractionType(val label: String) {
    KEY_NUMBERS("핵심 수치"),
    SPECIFICATIONS("스펙"),
    SCHEDULE("일정"),
    COMPARISON("비교 항목"),
    TABLE("표 형태"),
}

internal data class StructuredExtractionSource(
    val title: String,
    val kind: String,
    /** Durable identity label for the result; never an internal chunk ID. */
    val reference: String,
    val text: String,
    val durableIdentity: SelectionSourceIdentity? = null,
)

internal data class StructuredExtractionRow(
    val cells: List<String>,
    /** Exact bounded source line when available; no page or section is inferred. */
    val sourceText: String,
    val contentTruncated: Boolean = false,
    val columnsOmitted: Boolean = false,
)

internal data class StructuredExtractionResult(
    val type: StructuredExtractionType,
    val title: String,
    val columns: List<String>,
    val rows: List<StructuredExtractionRow>,
    val sourceTitle: String,
    val sourceKind: String,
    val sourceReference: String,
    val durableIdentity: SelectionSourceIdentity?,
    val warnings: List<String>,
    val cellContentTruncated: Boolean = false,
    val columnsOmitted: Boolean = false,
    val rowsOmitted: Boolean = false,
    val inputTruncated: Boolean = false,
)

internal object StructuredExtractionPolicy {
    private data class ParsedTable(
        val columns: List<String>,
        val rows: List<StructuredExtractionRow>,
        val columnsOmitted: Boolean = false,
        val partialRows: Boolean = false,
        val contentTruncated: Boolean = false,
    )

    private val number = Regex("(?<![\\p{L}\\p{N}])[-+]?\\d+(?:[.,]\\d+)?(?:\\s*(?:%|％|원|달러|원화|mm|cm|m|km|mg|g|kg|ml|L|Wh|kWh|W|kW|Hz|GHz|GB|TB|MB|일|주|개월|년|명|개|회|℃|°C))?", RegexOption.IGNORE_CASE)
    private val keyValue = Regex("^\\s*(.{1,80}?)\\s*[:=：＝]\\s*(.{1,400}?)\\s*$")
    private val dateOrTime = Regex("(?:\\b\\d{4}[./-]\\d{1,2}[./-]\\d{1,2}\\b|\\d{1,2}월(?:\\s*\\d{1,2}일)?|\\b\\d{1,2}:\\d{2}\\b|오늘|내일|모레|이번\\s*주|다음\\s*주|마감|회의|일정|예정|deadline|meeting|schedule)", RegexOption.IGNORE_CASE)
    private val comparisonCue = Regex("(?:비교|대비|차이|공통점|장점|단점|반면|그러나|vs\\.?|versus)", RegexOption.IGNORE_CASE)
    private val tableSeparator = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(?:\\|\\s*:?-{2,}:?\\s*)+\\|?\\s*$")

    fun extract(source: StructuredExtractionSource, type: StructuredExtractionType): StructuredExtractionResult {
        val boundedText = source.text.take(STRUCTURED_EXTRACTION_MAX_SOURCE_CHARS)
        // Raw lines keep blank-line boundaries so independent Markdown tables
        // never merge (C10); block segments below are split on blank lines.
        val rawLines = boundedText.lineSequence().take(2_001).toList()
        val truncatedByLineCap = rawLines.size > 2_000
        val lines = rawLines.take(2_000).map(String::trim).filter(String::isNotBlank).toList()
        val warnings = mutableListOf<String>()
        if (source.text.length > STRUCTURED_EXTRACTION_MAX_SOURCE_CHARS) warnings += "입력 길이 제한으로 뒤쪽 내용을 제외했습니다."
        // Bounded extraction is allowed; silent truncation is not (C11).
        // The user must distinguish a full result from a bounded one.
        val totalSourceLines = source.text.lineSequence().count()
        if (truncatedByLineCap || totalSourceLines > boundedText.lineSequence().count()) {
            warnings += "입력 줄 수 제한으로 뒤쪽 내용을 제외했습니다."
        }
        val columns: List<String>
        val rows: List<StructuredExtractionRow>
        var tableColumnsOmitted = false
        var tableHasPartialRows = false
        var tableContentTruncated = false
        when (type) {
            StructuredExtractionType.KEY_NUMBERS -> {
                columns = listOf("수치가 포함된 내용")
                rows = lines.filter { number.containsMatchIn(it) }
                    .map { row(listOf(it), it) }
            }
            StructuredExtractionType.SPECIFICATIONS -> {
                columns = listOf("항목", "값")
                rows = lines.mapNotNull { line ->
                    val match = keyValue.matchEntire(line) ?: return@mapNotNull null
                    val key = cleanCell(match.groupValues[1])
                    val value = cleanCell(match.groupValues[2])
                    if (key.isBlank() || value.isBlank()) null else row(listOf(key, value), line)
                }
            }
            StructuredExtractionType.SCHEDULE -> {
                columns = listOf("일정 내용")
                rows = lines.filter { dateOrTime.containsMatchIn(it) }.map { row(listOf(it), it) }
            }
            StructuredExtractionType.COMPARISON -> {
                columns = listOf("비교 내용")
                rows = lines.filter { comparisonCue.containsMatchIn(it) }.map { row(listOf(it), it) }
            }
            StructuredExtractionType.TABLE -> {
                val table = parseFirstTableBlock(rawLines.take(2_000))
                columns = table.columns
                rows = table.rows
                tableColumnsOmitted = table.columnsOmitted
                tableHasPartialRows = table.partialRows
                tableContentTruncated = table.contentTruncated
                if (rows.isNotEmpty()) warnings += "텍스트의 구분 기호를 기준으로 배열한 단순 표입니다. 원본 레이아웃과 병합 셀은 복원하지 않습니다."
                else warnings += "명확한 표 구분을 찾지 못했습니다. 표 형태로 재구성하지 않았습니다."
            }
        }
        if (rows.any { it.contentTruncated } || tableContentTruncated) {
            warnings.add(0, "Some cell or source text exceeded the 240-character limit and was truncated.")
        }
        if (tableColumnsOmitted) warnings.add(0, "Table columns beyond the 8-column limit were omitted.")
        if (tableHasPartialRows) warnings.add(0, "Some table rows have a different number of cells than the header.")
        val boundedRows = rows.take(STRUCTURED_EXTRACTION_MAX_ROWS)
        if (rows.size > boundedRows.size) warnings += "결과 행 수 제한으로 일부 항목을 제외했습니다."
        if (boundedRows.isEmpty() && type != StructuredExtractionType.TABLE) warnings += "선택한 형식에 해당하는 내용을 찾지 못했습니다."
        return StructuredExtractionResult(
            type = type,
            title = "${source.title.take(120)} · ${type.label}",
            columns = columns,
            rows = boundedRows,
            sourceTitle = source.title.take(180),
            sourceKind = source.kind.take(48),
            sourceReference = source.reference.take(180),
            durableIdentity = source.durableIdentity,
            warnings = warnings.distinct().take(4),
            inputTruncated = source.text.length > STRUCTURED_EXTRACTION_MAX_SOURCE_CHARS || truncatedByLineCap || totalSourceLines > boundedText.lineSequence().count(),
            rowsOmitted = rows.size > boundedRows.size,
            columnsOmitted = tableColumnsOmitted || rows.any { it.columnsOmitted },
            cellContentTruncated = rows.any { it.contentTruncated } || tableContentTruncated,
        )
    }

    fun asPlainText(result: StructuredExtractionResult): String = buildString {
        appendLine(result.title)
        appendLine("출처: ${result.sourceKind} · ${result.sourceTitle}")
        if (result.rows.isEmpty()) appendLine("추출된 항목 없음")
        else {
            if (result.columns.size > 1) appendLine(result.columns.joinToString("\t"))
            result.rows.forEach { appendLine(it.cells.joinToString("\t")) }
        }
        result.warnings.forEach { appendLine("주의: $it") }
    }.trimEnd()

    fun askPrompt(result: StructuredExtractionResult): String =
        "다음에 추출한 내용을 바탕으로 답해 주세요. 추출 범위를 넘는 내용은 구분해 주세요.\n\n" +
            asPlainText(result).take(2_400)

    /**
     * Independent Markdown tables separated by blank lines must not merge
     * (C10). A Markdown table cannot span a blank line, so each blank-line
     * delimited block is parsed independently and the first block holding a
     * valid table wins. Within a block the previous contiguous-row behavior
     * is preserved, including stopping at a non-pipe paragraph line.
     */
    private fun parseFirstTableBlock(rawLines: List<String>): ParsedTable {
        val blocks = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        for (line in rawLines) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) {
                if (current.isNotEmpty()) {
                    blocks += current
                    current = mutableListOf()
                }
            } else {
                current += trimmed
            }
        }
        if (current.isNotEmpty()) blocks += current
        for (block in blocks) {
            val table = parseSimpleTable(block)
            if (table.rows.isNotEmpty()) return table
        }
        return ParsedTable(emptyList(), emptyList())
    }

    private fun parseSimpleTable(lines: List<String>): ParsedTable {
        for (index in 0 until (lines.size - 1).coerceAtLeast(0)) {
            val headerLine = lines[index]
            val separatorLine = lines[index + 1]
            if ('|' !in headerLine || !tableSeparator.matches(separatorLine)) continue
            val rawHeaders = splitCells(headerLine)
            val headers = rawHeaders.take(STRUCTURED_EXTRACTION_MAX_COLUMNS)
            if (headers.size < 2) continue
            val rows = lines.drop(index + 2).takeWhile { '|' in it }
                .mapNotNull { line ->
                    val cells = splitCells(line)
                    if (cells.size < 2) null else row(cells, line)
                }
            if (rows.isNotEmpty()) return ParsedTable(
                columns = headers.map(::cleanCell),
                rows = rows,
                columnsOmitted = rawHeaders.size > STRUCTURED_EXTRACTION_MAX_COLUMNS || rows.any { it.columnsOmitted },
                partialRows = rows.any { it.cells.size != headers.size },
                contentTruncated = rawHeaders.any { it.length > STRUCTURED_EXTRACTION_MAX_CELL_CHARS },
            )
        }
        return ParsedTable(emptyList(), emptyList())
    }

    private fun splitCells(line: String): List<String> = line.trim().trim('|').split('|').map(::normalizeCell)

    private fun row(cells: List<String>, sourceText: String) = StructuredExtractionRow(
        cells = cells.take(STRUCTURED_EXTRACTION_MAX_COLUMNS).map(::cleanCell),
        sourceText = sourceText.take(STRUCTURED_EXTRACTION_MAX_CELL_CHARS),
        contentTruncated = cells.any { normalizeCell(it).length > STRUCTURED_EXTRACTION_MAX_CELL_CHARS } || sourceText.length > STRUCTURED_EXTRACTION_MAX_CELL_CHARS,
        columnsOmitted = cells.size > STRUCTURED_EXTRACTION_MAX_COLUMNS,
    )

    private fun cleanCell(value: String): String = normalizeCell(value).take(STRUCTURED_EXTRACTION_MAX_CELL_CHARS)

    private fun normalizeCell(value: String): String = value.trim().replace(Regex("\\s+"), " ")
}

internal fun StructuredExtractionResult.toNoteDraft(
    projectId: Long? = null,
    projectName: String? = null,
): SavedKnowledgeNoteEditorDraft? {
    val identity = durableIdentity ?: return null
    val plainText = StructuredExtractionPolicy.asPlainText(this)
    if (plainText.length > MAX_SAVED_NOTE_BODY_CHARS) return null
    return SavedKnowledgeNoteEditorDraft(
        id = "",
        title = title.take(MAX_SAVED_NOTE_TITLE_CHARS),
        body = plainText,
        references = listOf(SavedKnowledgeNoteReferenceDraft(
            identity,
            sourceTitle.take(com.projectnuke.fusion.chat.SelectionContextSnapshot.MAX_TITLE_CHARS),
        )),
        projectId = projectId,
        projectName = projectName,
    )
}
