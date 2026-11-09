package com.projectnuke.fusion.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class VisibleAnswerCorrectionTest {
    @Test fun `long fences close and preserve subsequent paragraphs`() {
        for (char in listOf('`', '~')) for (length in 4..6) {
            val fence = char.toString().repeat(length)
            val blocks = parseMarkdownBlocks("${fence}kotlin\ncode\n$fence\nafter")
            assertEquals(listOf("code", "after"), blocks.map { it.text })
            assertEquals(MarkdownBlockKind.PARAGRAPH, blocks.last().kind)
        }
    }
    @Test fun `shorter embedded fences remain code and longer fences close`() {
        assertEquals(listOf("```\ncode", "after"), parseMarkdownBlocks("````\n```\ncode\n`````\nafter").map { it.text })
    }
    @Test fun `code uses semantic theme pairs`() {
        val source = File("src/main/java/com/projectnuke/fusion/ui/AssistantMarkdownContent.kt").readText()
        assertFalse(source.contains("Color(0xFF222222)"))
        assertFalse(source.contains("Color(0xFFE1F6FF)"))
        assertTrue(source.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
        assertTrue(source.contains("MaterialTheme.colorScheme.surfaceVariant"))
    }
    @Test fun `history has no current selection or fabricated today date`() {
        val source = File("src/main/java/com/projectnuke/fusion/ui/AssistantMessageSurface.kt").readText()
        assertFalse(source.contains("selectedModel: String"))
        assertFalse(source.contains("SimpleDateFormat(\"오늘"))
        assertTrue(source.contains("historicalAnswerModel(content)"))
    }
}
