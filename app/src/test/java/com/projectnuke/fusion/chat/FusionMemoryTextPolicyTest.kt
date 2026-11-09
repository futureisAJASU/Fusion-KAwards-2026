package com.projectnuke.fusion.chat

import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class FusionMemoryTextPolicyTest {
    @Test
    fun `memory excludes Fusion-owned complete unclosed and nested reasoning`() {
        listOf("fusion_thinking").forEach { outer ->
            listOf("fusion_thinking").forEach { inner ->
                val prefix = "visible<$outer>secret<$inner>nested secret</$inner>hidden tail"
                assertEquals("visible", sanitizeFusionMemoryText(prefix))
                assertEquals("visible", sanitizeFusionMemoryText("$prefix<fusion_answer>still secret</fusion_answer>"))
                assertEquals("visible answer", sanitizeFusionMemoryText("$prefix</$outer><fusion_answer> answer</fusion_answer>"))
            }
        }
    }

    @Test
    fun `memory withholds every trailing partial reasoning tag`() {
        listOf("fusion_thinking").forEach { tag ->
            val open = "<$tag>"
            val close = "</$tag>"
            for (length in 1 until open.length) {
                assertEquals("visible", sanitizeFusionMemoryText("visible" + open.take(length)))
            }
            for (length in 1 until close.length) {
                assertEquals("visible", sanitizeFusionMemoryText("visible<$tag>secret" + close.take(length)))
            }
        }
    }

    @Test
    fun `memory reuses canonical metadata and attachment removal with existing whitespace policy`() {
        val raw = AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(AttachmentRecord("private.txt", "text/plain", "/private/file.txt")),
            "<fusion_metrics>private metrics</fusion_metrics>" +
                "[FUSION_ANSWER_CONTEXT_JSON]{\"note\":\"<think>private receipt\"}[/FUSION_ANSWER_CONTEXT_JSON]" +
                "<fusion_thinking>secret</fusion_thinking><fusion_answer>  first\t line\n\n\nsecond  line  </fusion_answer>",
        )
        assertEquals("first line\n\nsecond line", sanitizeFusionMemoryText(raw))
        assertEquals("<thinking>only secret", sanitizeFusionMemoryText("<thinking>only secret"))
        assertEquals("", sanitizeFusionMemoryText(""))
        assertEquals("plain text", sanitizeFusionMemoryText("plain text"))
    }
}
