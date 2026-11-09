package com.projectnuke.fusion.retrieval

internal data class DocumentSectionPageInput(
    val pageNumber: Int?,
    val sourceChunkId: String?,
    val sourceContentHashHex: String?,
    val text: String,
)

internal enum class DocumentSectionKind(val label: String) {
    TEXT("본문"),
    TABLE("표"),
    KEY_VALUE("항목 값"),
    LIST("목록"),
}

internal data class StructuredDocumentSection(
    val title: String,
    val kind: DocumentSectionKind,
    val text: String,
    val pageNumber: Int?,
    val sourceChunkId: String?,
    val sourceContentHashHex: String?,
    val truncated: Boolean,
)

internal data class DocumentSectionOutline(
    val sections: List<StructuredDocumentSection>,
    val partial: Boolean,
)

/** Conservative text-only structure recognition; never claims page layout fidelity. */
internal object DocumentSectionPolicy {
    const val MAX_SOURCE_PAGES = 32
    const val MAX_TOTAL_TEXT_CHARS = 48_000
    const val MAX_SECTIONS = 32
    const val MAX_LINES_PER_PAGE = 2_000
    const val MAX_SECTION_TEXT_CHARS = 8_000

    private val numberedHeading = Regex("^\\s*(?:제\\s*)?\\d+(?:\\.\\d+)*(?:장|절|부|항)?[.)]?\\s+.{1,100}$")
    private val listPrefix = Regex("^\\s*(?:[-*•▪‣]|\\d+[.)])\\s+\\S.*$")
    private val keyValue = Regex("^\\s*[^:：=＝]{1,80}\\s*[:：=＝]\\s*\\S.{0,300}$")
    private val tableSeparator = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(?:\\|\\s*:?-{2,}:?\\s*)+\\|?\\s*$")

    fun detect(inputs: List<DocumentSectionPageInput>): DocumentSectionOutline {
        val acceptedPages = inputs.take(MAX_SOURCE_PAGES)
        var remainingChars = MAX_TOTAL_TEXT_CHARS
        val sections = mutableListOf<StructuredDocumentSection>()
        var partial = inputs.size > acceptedPages.size
        for (page in acceptedPages) {
            if (remainingChars <= 0) {
                partial = true
                break
            }
            val text = page.text.take(remainingChars)
            if (text.length < page.text.length) partial = true
            if (text.count { it == '\n' } + 1 > MAX_LINES_PER_PAGE) partial = true
            remainingChars -= text.length
            scanPage(page.copy(text = text), sections)
            if (sections.size >= MAX_SECTIONS) {
                partial = true
                break
            }
        }
        return DocumentSectionOutline(
            sections.take(MAX_SECTIONS),
            partial || sections.any { it.truncated },
        )
    }

    private fun scanPage(page: DocumentSectionPageInput, out: MutableList<StructuredDocumentSection>) {
        val lines = page.text.lineSequence().take(MAX_LINES_PER_PAGE).map(String::trim).toList()
        var currentHeading = ""
        var prose = mutableListOf<String>()
        fun emit(kind: DocumentSectionKind, title: String, rows: List<String>) {
            val body = rows.joinToString("\n").trim()
            if (body.isBlank() || out.size >= MAX_SECTIONS) return
            out += StructuredDocumentSection(
                title = title.ifBlank { kind.label }.take(180),
                kind = kind,
                text = body.take(MAX_SECTION_TEXT_CHARS),
                pageNumber = page.pageNumber,
                sourceChunkId = page.sourceChunkId,
                sourceContentHashHex = page.sourceContentHashHex,
                truncated = body.length > MAX_SECTION_TEXT_CHARS,
            )
        }
        fun flushProse() {
            if (prose.isNotEmpty()) emit(DocumentSectionKind.TEXT, currentHeading.ifBlank { "본문" }, prose)
            prose = mutableListOf()
        }

        var index = 0
        val scanLines = lines.take(MAX_LINES_PER_PAGE)
        while (index < scanLines.size && out.size < MAX_SECTIONS) {
            val line = scanLines[index]
            val heading = headingLabel(line)
            if (heading != null) {
                flushProse()
                currentHeading = heading
                index++
                continue
            }
            val tableEnd = tableEnd(scanLines, index)
            if (tableEnd > index) {
                flushProse()
                emit(DocumentSectionKind.TABLE, contextualTitle(currentHeading, "표"), scanLines.subList(index, tableEnd))
                index = tableEnd
                continue
            }
            val listEnd = runEnd(scanLines, index, listPrefix::containsMatchIn)
            if (listEnd - index >= 2) {
                flushProse()
                emit(DocumentSectionKind.LIST, contextualTitle(currentHeading, "목록"), scanLines.subList(index, listEnd))
                index = listEnd
                continue
            }
            val keyValueEnd = runEnd(scanLines, index, keyValue::matches)
            if (keyValueEnd - index >= 2) {
                flushProse()
                emit(DocumentSectionKind.KEY_VALUE, contextualTitle(currentHeading, "항목 값"), scanLines.subList(index, keyValueEnd))
                index = keyValueEnd
                continue
            }
            if (line.isNotBlank()) prose += line
            index++
        }
        flushProse()
    }

    private fun headingLabel(raw: String): String? {
        val line = raw.trim()
        if (line.isBlank() || line.length > 100 || line.startsWith("|") || listPrefix.containsMatchIn(line)) return null
        val markdown = line.removePrefix("###").removePrefix("##").removePrefix("#").trim()
        if (markdown != line && markdown.isNotBlank()) return markdown
        if (numberedHeading.matches(line)) return line
        if (line.length !in 3..60 || line.count(Char::isWhitespace) > 7) return null
        if (line.last() in ".,;:!?。？！；：") return null
        if (line.any { it in ":：=＝" }) return null
        return line
    }

    private fun tableEnd(lines: List<String>, start: Int): Int {
        if (start + 1 >= lines.size || '|' !in lines[start] || !tableSeparator.matches(lines[start + 1])) return start
        var end = start + 2
        while (end < lines.size && '|' in lines[end] && lines[end].isNotBlank()) end++
        return if (end > start + 2) end else start
    }

    private fun runEnd(lines: List<String>, start: Int, matches: (String) -> Boolean): Int {
        if (!matches(lines[start])) return start
        var end = start + 1
        while (end < lines.size && matches(lines[end])) end++
        return end
    }

    private fun contextualTitle(heading: String, suffix: String): String =
        if (heading.isBlank()) suffix else "$heading · $suffix"
}
