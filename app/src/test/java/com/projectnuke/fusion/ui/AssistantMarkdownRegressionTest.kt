package com.projectnuke.fusion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import com.projectnuke.fusion.data.EvidenceClaim
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.data.EvidenceVerdict
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.MessageEvidenceReport
import com.projectnuke.fusion.data.EVIDENCE_VERIFIER_VERSION
import com.projectnuke.fusion.search.appendSearchSourcesMetadata
import com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantMarkdownRegressionTest {

    @Test
    fun `empty and blank leading or trailing lines never crash or create empty blocks`() {
        listOf(
            "",
            "\n",
            "\n안녕하세요",
            "안녕하세요\n",
            "\n\n첫 문단\n\n\n둘째 문단\n\n",
        ).forEach { input ->
            val document = parseMarkdownDocument(input)
            assertTrue(document.blocks.none { it.text.isEmpty() && !it.isCodeBlock })
        }
        assertEquals(listOf("안녕하세요"), parseMarkdownDocument("\n안녕하세요").blocks.map { it.text })
        assertEquals(2, parseMarkdownDocument("첫 문단\n\n둘째 문단").blocks.size)
    }

    @Test
    fun `mixed inline formatting remains one paragraph with styled spans`() {
        val block = parseMarkdownDocument("일반 **굵게** 일반").blocks.single()
        assertEquals(MarkdownBlockKind.PARAGRAPH, block.kind)
        assertEquals(3, block.inlineSpans.size)
        assertEquals("일반 굵게 일반", block.text)
        assertTrue(block.inlineSpans[1].bold)
        assertFalse(block.text.contains("**"))
    }

    @Test
    fun `ordered and unordered list retain inline bold and visible text`() {
        val document = parseMarkdownDocument("1. **주요 이슈:** 설명\n- 일반 **항목**")
        assertEquals(2, document.blocks.size)
        assertEquals(MarkdownBlockKind.ORDERED_LIST_ITEM, document.blocks[0].kind)
        assertEquals(MarkdownBlockKind.UNORDERED_LIST_ITEM, document.blocks[1].kind)
        assertTrue(document.blocks[0].inlineSpans.any { it.bold && it.text == "주요 이슈:" })
        assertTrue(document.blocks[1].inlineSpans.any { it.bold && it.text == "항목" })
        assertFalse(buildMarkdownAnnotatedString(document, Color.Black).text.contains("**"))
    }

    @Test
    fun `inline code italic heading and fenced language blocks parse structurally`() {
        val document = parseMarkdownDocument(
            "# Heading\n\n*기울임*과 `코드`\n\n```kotlin\nval x = 1\n```\n\n```python\nprint(\"x\")\n```",
        )
        assertEquals(MarkdownBlockKind.HEADING, document.blocks[0].kind)
        val paragraph = document.blocks[1]
        assertTrue(paragraph.inlineSpans.any { it.italic && it.text == "기울임" })
        assertTrue(paragraph.inlineSpans.any { it.code && it.text == "코드" })
        assertEquals(listOf("val x = 1", "print(\"x\")"), document.blocks.filter { it.isCodeBlock }.map { it.codeText })
        assertFalse(document.blocks.any { it.text == "kotlin" || it.text == "python" })
    }

    @Test
    fun `inline fenced code keeps visible text after the closing fence`() {
        val document = parseMarkdownDocument("```x```TAIL")

        assertEquals(listOf(MarkdownBlockKind.CODE, MarkdownBlockKind.PARAGRAPH), document.blocks.map { it.kind })
        assertEquals("x", document.blocks[0].codeText)
        assertEquals("TAIL", document.blocks[1].text)
    }

    @Test
    fun `four space indented code is rendered as code and retains consecutive lines`() {
        val document = parseMarkdownDocument("    x = 1\n    y = 2\n\nnext")

        assertEquals(listOf(MarkdownBlockKind.CODE, MarkdownBlockKind.PARAGRAPH), document.blocks.map { it.kind })
        assertEquals("x = 1\ny = 2", document.blocks[0].codeText)
    }

    @Test
    fun `escaped emphasis markers stay literal and do not open italic spans`() {
        val block = parseMarkdownDocument("a\\*b\\*c").blocks.single()

        assertEquals("a*b*c", block.text)
        assertFalse(block.isItalic)
    }

    @Test
    fun `nested lists retain depth and markdown tables become structured rows`() {
        val document = parseMarkdownDocument(
            "1. parent\n    - child\n\n| Name | Value |\n| --- | :---: |\n| A\\|B | **1** |",
        )

        assertEquals(0, document.blocks[0].listDepth)
        assertEquals(2, document.blocks[1].listDepth)
        val table = document.blocks.single { it.kind == MarkdownBlockKind.TABLE }
        assertEquals("Name | Value\nA|B | 1", table.text)
        assertTrue(table.tableRows.last().last().any { it.bold })
        assertTrue(buildMarkdownAnnotatedString(document).text.contains("    • child"))
    }

    @Test
    fun `normal and list links retain URL identity and unsafe scheme is inert`() {
        val document = parseMarkdownDocument("[normal](https://example.com/a)\n\n- [list](https://example.com/b)")
        assertEquals("https://example.com/a", document.blocks[0].inlineSpans.single().linkUrl)
        assertEquals("https://example.com/b", document.blocks[1].inlineSpans.single().linkUrl)
        val unsafe = buildMarkdownAnnotatedString(parseMarkdownDocument("[unsafe](javascript:alert(1))"), Color.Black)
        assertEquals("unsafe", unsafe.text)
        assertTrue(unsafe.getLinkAnnotations(0, unsafe.length).isEmpty())
        assertFalse(isSafeLinkUrl("javascript:alert(1)"))
    }

    @Test
    fun `evidence answer uses same markdown spans while only mapped source is clickable`() {
        val answer = "일반 **굵게** 일반 [1]"
        val message = MessageEntity(1, 10, "assistant", answer, 1)
        val source = EvidenceSource("E1", "source", "Example", "https://example.com", "굵게", "2026", "FREE_DEFAULT", "Search", "query")
        val report = MessageEvidenceReport(
            message.id,
            EvidenceReport(
                1,
                EvidenceFingerprint.canonicalAnswerHash(answer),
                1,
                EVIDENCE_VERIFIER_VERSION,
                listOf(EvidenceClaim("C1", "굵게", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, listOf("E1"), null)),
                listOf(source),
            ),
        )
        val document = buildEvidenceMarkdownDocument(message, answer, report)
        val bold = document.blocks.single().inlineSpans.first { it.text == "굵게" }
        assertTrue(bold.bold)
        assertFalse(document.blocks.single().text.contains("**"))
        val rendered = buildEvidenceCitationText(message, answer, report) {}
        assertEquals("일반 굵게[E1] 일반 [1]", rendered.text)
        val citationLinks = rendered.getLinkAnnotations(0, rendered.length)
        assertEquals(1, citationLinks.size)
        val citation = citationLinks.single().item
        assertTrue(citation is LinkAnnotation.Clickable)
        assertEquals("fusion-evidence:E1", (citation as LinkAnnotation.Clickable).tag)
    }

    @Test
    fun `internal Fusion envelope is stripped before markdown presentation`() {
        val raw = appendSearchSourcesMetadata(
            "<fusion_answer>일반 **굵게**</fusion_answer>\n<fusion_metrics>42 tokens</fusion_metrics>",
            emptyList(),
        )
        val visible = visibleMessageContentPreservingWhitespace(raw)
        val document = parseMarkdownDocument(visible)
        assertEquals("일반 굵게", document.blocks.single().text)
        assertTrue(document.blocks.single().inlineSpans.any { it.bold && it.text == "굵게" })
        assertFalse(document.blocks.any { it.text.contains("FUSION_") || it.text.contains("fusion_", ignoreCase = true) })
    }
}
