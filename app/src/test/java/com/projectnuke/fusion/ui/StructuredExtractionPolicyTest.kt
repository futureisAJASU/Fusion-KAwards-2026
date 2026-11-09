package com.projectnuke.fusion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredExtractionPolicyTest {
    @Test
    fun `extracts exact numeric lines and retains result provenance`() {
        val source = source("HBM notes", "원문", "project-file:7", "대역폭은 1.2 TB/s입니다.\n다음 검토에서 확인합니다.")

        val result = StructuredExtractionPolicy.extract(source, StructuredExtractionType.KEY_NUMBERS)

        assertEquals(listOf("대역폭은 1.2 TB/s입니다."), result.rows.map { it.sourceText })
        assertEquals("project-file:7", result.sourceReference)
        assertEquals(listOf("대역폭은 1.2 TB/s입니다."), result.rows.single().cells)
    }

    @Test
    fun `extracts bounded key value schedule and comparison rows`() {
        val source = source("Project", "Project", "project:4", "속도: 3.2 GHz\n회의 일정: 2026-10-05 14:00\nA 대비 B는 전력 사용량이 낮습니다.")

        assertEquals(
            listOf(listOf("속도", "3.2 GHz"), listOf("회의 일정", "2026-10-05 14:00")),
            StructuredExtractionPolicy.extract(source, StructuredExtractionType.SPECIFICATIONS).rows.map { it.cells },
        )
        assertEquals(listOf("회의 일정: 2026-10-05 14:00"), StructuredExtractionPolicy.extract(source, StructuredExtractionType.SCHEDULE).rows.map { it.sourceText })
        assertEquals(listOf("A 대비 B는 전력 사용량이 낮습니다."), StructuredExtractionPolicy.extract(source, StructuredExtractionType.COMPARISON).rows.map { it.sourceText })
    }

    @Test
    fun `table extraction requires explicit separators and states its limitation`() {
        val result = StructuredExtractionPolicy.extract(
            source("Comparison", "Research", "conversation:19", "| 항목 | A | B |\n| --- | --- | --- |\n| 속도 | 3 | 4 |"),
            StructuredExtractionType.TABLE,
        )

        assertEquals(listOf("항목", "A", "B"), result.columns)
        assertEquals(listOf("속도", "3", "4"), result.rows.single().cells)
        assertTrue(result.warnings.single().contains("복원하지 않습니다"))
    }

    @Test
    fun `caps source rows and cells and empty table result does not invent data`() {
        val longCell = "x".repeat(STRUCTURED_EXTRACTION_MAX_CELL_CHARS + 80)
        val source = source("Long", "Document", "project-file:3", "spec: $longCell\n".repeat(STRUCTURED_EXTRACTION_MAX_ROWS + 5))

        val specs = StructuredExtractionPolicy.extract(source, StructuredExtractionType.SPECIFICATIONS)
        val table = StructuredExtractionPolicy.extract(source("Plain", "Document", "project-file:4", "항목 설명만 있습니다."), StructuredExtractionType.TABLE)

        assertEquals(STRUCTURED_EXTRACTION_MAX_ROWS, specs.rows.size)
        assertTrue(specs.rows.all { row -> row.cells.all { it.length <= STRUCTURED_EXTRACTION_MAX_CELL_CHARS } })
        assertTrue(specs.warnings.any { it.contains("제한") })
        assertTrue(table.rows.isEmpty())
        assertFalse(table.warnings.isEmpty())
    }

    @Test
    fun `blank line separated tables do not merge`() {
        val text = "| 제품 | 가격 |\n| --- | --- |\n| 사과 | 1000 |\n\n| 일정 | 담당자 |\n| --- | --- |\n| 월요일 | 김 |\n"
        val result = StructuredExtractionPolicy.extract(
            source("Two tables", "Document", "doc:1", text),
            StructuredExtractionType.TABLE,
        )

        assertEquals(listOf("제품", "가격"), result.columns)
        assertEquals(1, result.rows.size)
        assertEquals(listOf("사과", "1000"), result.rows.single().cells)
    }

    @Test
    fun `table between prose blocks keeps first table schema`() {
        val text = "서론 문장입니다.\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n본문 문장입니다.\n\n| C | D |\n| --- | --- |\n| 3 | 4 |\n"
        val result = StructuredExtractionPolicy.extract(
            source("Prose tables", "Document", "doc:2", text),
            StructuredExtractionType.TABLE,
        )

        assertEquals(listOf("A", "B"), result.columns)
        assertEquals(listOf(listOf("1", "2")), result.rows.map { it.cells })
    }

    @Test
    fun `line cap truncation surfaces a bounded warning`() {
        val filler = (1..2_000).joinToString("\n") { "줄 $it" }
        val text = "$filler\n마감: 2026-11-01"
        val result = StructuredExtractionPolicy.extract(
            source("Long", "Document", "doc:3", text),
            StructuredExtractionType.SCHEDULE,
        )

        assertTrue(result.warnings.any { it.contains("줄 수 제한") })
    }

    @Test
    fun `line cap boundary keeps full result warning free`() {
        val text = (1..1_999).joinToString("\n") { "줄 $it" }
        val result = StructuredExtractionPolicy.extract(
            source("Boundary", "Document", "doc:4", text),
            StructuredExtractionType.SCHEDULE,
        )

        assertFalse(result.warnings.any { it.contains("줄 수 제한") })
    }

    @Test
    fun `cell and source truncation are measured before bounded output is stored`() {
        val exact = "x".repeat(STRUCTURED_EXTRACTION_MAX_CELL_CHARS)
        val over = "y".repeat(STRUCTURED_EXTRACTION_MAX_CELL_CHARS + 1)
        val specs = StructuredExtractionPolicy.extract(
            source("Specs", "Document", "doc:5", "a: $exact\nb: $over"),
            StructuredExtractionType.SPECIFICATIONS,
        )
        assertEquals(STRUCTURED_EXTRACTION_MAX_CELL_CHARS, specs.rows[0].cells[1].length)
        assertEquals(STRUCTURED_EXTRACTION_MAX_CELL_CHARS, specs.rows[1].cells[1].length)
        assertTrue(specs.cellContentTruncated)

        val exactLine = "1" + "z".repeat(STRUCTURED_EXTRACTION_MAX_CELL_CHARS - 1)
        val overLine = "1" + "z".repeat(STRUCTURED_EXTRACTION_MAX_CELL_CHARS)
        val boundary = StructuredExtractionPolicy.extract(
            source("Boundary", "Document", "doc:9", "$exactLine\n$overLine"),
            StructuredExtractionType.KEY_NUMBERS,
        )
        assertEquals(STRUCTURED_EXTRACTION_MAX_CELL_CHARS, boundary.rows[0].cells.single().length)
        assertFalse(boundary.rows[0].contentTruncated)
        assertTrue(boundary.rows[1].contentTruncated)
        assertTrue(boundary.warnings.any { it.contains("240-character") })

        val table = StructuredExtractionPolicy.extract(
            source("Table", "Document", "doc:6", "| a | b |\n| --- | --- |\n| $exact | $over |"),
            StructuredExtractionType.TABLE,
        )
        assertEquals(STRUCTURED_EXTRACTION_MAX_CELL_CHARS, table.rows.single().cells[0].length)
        assertEquals(STRUCTURED_EXTRACTION_MAX_CELL_CHARS, table.rows.single().cells[1].length)
        assertTrue(table.cellContentTruncated)
        assertTrue(table.rows.single().contentTruncated)
    }

    @Test
    fun `table omission and partial rows are visible in warnings and copied output`() {
        val eight = (1..8).joinToString(" | ") { "h$it" }
        val exactlyEight = StructuredExtractionPolicy.extract(
            source("Eight", "Document", "doc:7", "| $eight |\n| --- | --- | --- | --- | --- | --- | --- | --- |\n| $eight |"),
            StructuredExtractionType.TABLE,
        )
        assertFalse(exactlyEight.columnsOmitted)

        val oversizedHeader = "long".repeat(STRUCTURED_EXTRACTION_MAX_CELL_CHARS)
        val nine = (1..9).joinToString(" | ") { "h$it" }
        val partial = StructuredExtractionPolicy.extract(
            source("Nine", "Document", "doc:8", "| $nine |\n| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n| $oversizedHeader | only |"),
            StructuredExtractionType.TABLE,
        )
        assertTrue(partial.columnsOmitted)
        assertTrue(partial.cellContentTruncated)
        assertTrue(partial.warnings.any { it.contains("8-column") })
        assertTrue(partial.warnings.any { it.contains("different number of cells") })
        assertTrue(StructuredExtractionPolicy.asPlainText(partial).contains("8-column"))
    }

    private fun source(title: String, kind: String, reference: String, text: String) =
        StructuredExtractionSource(title, kind, reference, text)
}
