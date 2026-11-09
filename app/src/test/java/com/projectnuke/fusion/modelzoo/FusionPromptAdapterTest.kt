package com.projectnuke.fusion.modelzoo

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionPromptAdapterTest {
    private val supportedTags = mapOf(
        ModelFamily.GEMMA to setOf("fusion_thinking"),
        ModelFamily.CUSTOM to setOf("fusion_thinking"),
        ModelFamily.QWEN to setOf("think"),
        ModelFamily.DEEPSEEK to setOf("think", "thinking"),
    )

    @Test
    fun `explicit channel support is separate from Fusion prompt instructions`() {
        ModelFamily.entries.forEach { family ->
            val adapter = FusionPromptAdapters.forFamily(family)
            assertEquals(family.name, supportedTags[family].orEmpty(), adapter.explicitReasoningTags)
            assertEquals(
                family.name,
                family == ModelFamily.GEMMA || family == ModelFamily.CUSTOM,
                adapter.supportsFusionReasoningTags(),
            )
        }
        assertFalse(QwenPromptAdapter.explicitReasoningTags.contains("thinking"))
    }

    @Test
    fun `default adapter has no explicit channel and uses canonical sanitation`() {
        val adapter = object : FusionPromptAdapter {
            override val family = ModelFamily.CUSTOM
            override fun buildMessages(messages: List<ChatMessage>): List<ChatMessage> = messages
            override fun supportsFusionReasoningTags(): Boolean = true
        }
        assertTrue(adapter.explicitReasoningTags.isEmpty())
        assertEquals("<think>hidden</think>answer", adapter.sanitizeOutput("<think>hidden</think>answer"))
    }

    @Test
    fun `recognized channels normalize wrappers while preserving emitted reasoning bytes`() {
        supportedTags.forEach { (family, tags) ->
            val adapter = FusionPromptAdapters.forFamily(family)
            tags.forEach { tag ->
                listOf(tag, tag.uppercase(), " $tag ").forEach { spelling ->
                    val thinking = "  emitted\r\n  reasoning\t "
                    val raw = "<$spelling>$thinking</$spelling>\r\n" +
                        "<fusion_answer>한국 답변\n  formatted</fusion_answer>  "
                    assertEquals(
                        "$family/$spelling",
                        "<fusion_thinking>$thinking</fusion_thinking>\r\n한국 답변\n  formatted",
                        adapter.sanitizeOutput(raw),
                    )
                }
            }
        }
    }

    @Test
    fun `recognized reasoning only and unfinished output normalize only emitted text`() {
        supportedTags.forEach { (family, tags) ->
            val adapter = FusionPromptAdapters.forFamily(family)
            tags.forEach { tag ->
                listOf("<$tag>emitted</$tag>", "<$tag>emitted", "<$tag>emitted</${tag.take(2)}").forEach { raw ->
                    assertEquals("$family/$tag", "<fusion_thinking>emitted</fusion_thinking>", adapter.sanitizeOutput(raw))
                }
                assertEquals("", adapter.sanitizeOutput("<$tag> \r\n\t </$tag>"))
                assertEquals("answer", adapter.sanitizeOutput("<$tag> \r\n\t </$tag>answer"))
            }
            assertEquals("plain answer", adapter.sanitizeOutput("  plain answer  "))
            assertEquals("", adapter.sanitizeOutput("  \n\t"))
        }
    }

    @Test
    fun `unsupported families hide only Fusion-owned wrappers and preserve native literals`() {
        ModelFamily.entries.filterNot { it in supportedTags }.forEach { family ->
            val adapter = FusionPromptAdapters.forFamily(family)
            val raw = "  <fusion_thinking>hidden</fusion_thinking><think>literal</think>tail" +
                "<fusion_answer>한국 답변\r\n\r\n  indented  text</fusion_answer>  "
            assertEquals(family.name, "<think>literal</think>tail한국 답변\n\n  indented  text", adapter.sanitizeOutput(raw))
            assertEquals(visibleMessageContentPreservingWhitespace(raw), adapter.sanitizeOutput(raw))
            assertEquals("<think>only literal</think>", adapter.sanitizeOutput("<think>only literal</think>"))
            assertEquals("intro<thinking>unfinished", adapter.sanitizeOutput("intro<thinking>unfinished"))
            assertEquals("answer<thi", adapter.sanitizeOutput("answer<thi"))
            assertEquals("", adapter.sanitizeOutput(" \n\t "))
        }
    }
}
