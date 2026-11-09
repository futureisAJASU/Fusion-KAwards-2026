package com.projectnuke.fusion.ai

import com.projectnuke.fusion.ai.model.AiRole
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAiChatMapperTest {
    @Test
    fun `both mapper overloads exclude previous local reasoning and hidden metadata`() {
        val history = listOf(
            ChatMessage("user", "Earlier request"),
            ChatMessage(
                "assistant",
                "<fusion_metrics>42 tokens</fusion_metrics>" +
                    "<fusion_thinking>local secret<think>nested secret</think>tail secret</fusion_thinking>" +
                    "<fusion_answer>Visible answer\n  preserved indentation</fusion_answer>" +
                    "[FUSION_ANSWER_CONTEXT_JSON]{\"note\":\"<think>receipt secret\"}[/FUSION_ANSWER_CONTEXT_JSON]",
            ),
            ChatMessage("assistant", "<thinking>unfinished local secret"),
            ChatMessage("assistant", "<fusion_think"),
            ChatMessage("ASSISTANT", "safe prefix<think>unfinished secret"),
        )
        val mapped = buildExternalAiMessages(history) { it }
        val withInput = buildExternalAiMessages(history, "Current request") { it }

        assertEquals(
            listOf("Earlier request", "Visible answer\n  preserved indentation"),
            mapped.map { it.content },
        )
        assertEquals(listOf(AiRole.USER, AiRole.ASSISTANT), mapped.map { it.role })
        assertEquals(mapped, withInput.dropLast(1))
        assertEquals("Current request", withInput.last().content)
        assertTrue(mapped.none { it.content.contains("tail secret") })
        assertTrue(mapped.none { it.content.contains("unfinished secret") })
    }

    @Test
    fun `user attachment callback and current input semantics remain unchanged`() {
        val attachment = AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(AttachmentRecord("notes.txt", "text/plain", "/private/notes.txt")),
            "<think>literal user text</think>",
        )
        val system = "  <thinking>system instruction</thinking>  "
        val assistant = "<fusion_thinking>local secret</fusion_thinking><fusion_answer>answer</fusion_answer>"
        val history = listOf(ChatMessage("system", system), ChatMessage("user", attachment), ChatMessage("assistant", assistant))
        val calls = mutableListOf<String>()
        val stripAttachments: (String) -> String = { raw ->
            calls.add(raw)
            if (raw == attachment) "  attachment placeholder\n<think>literal user text</think>  " else raw
        }
        val mapped = buildExternalAiMessages(history, attachment, stripAttachments)

        assertEquals(listOf(system, attachment, assistant), calls)
        assertEquals("<thinking>system instruction</thinking>", mapped[0].content)
        assertEquals("attachment placeholder\n<think>literal user text</think>", mapped[1].content)
        assertEquals("answer", mapped[2].content)
        assertEquals(attachment, mapped[3].content)
        calls.clear()
        assertEquals(mapped.dropLast(1), buildExternalAiMessages(history, stripAttachments))
        assertEquals(listOf(system, attachment, assistant), calls)
    }

    @Test
    fun `assistant attachments are excluded even when callback is identity`() {
        val raw = AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(AttachmentRecord("private.txt", "text/plain", "/private/file.txt")),
            "<fusion_thinking>secret</fusion_thinking><fusion_answer>answer</fusion_answer>",
        )
        assertEquals("answer", buildExternalAiMessages(listOf(ChatMessage("assistant", raw))) { it }.single().content)
    }

    @Test
    fun `proven model family authority consumes legacy native assistant reasoning`() {
        val qwen = buildExternalAiMessages(
            messages = listOf(ChatMessage("assistant", "<think>secret</think>answer")),
            stripAttachments = { it },
            assistantReasoningTags = FusionPromptAdapters.forFamily(ModelFamily.QWEN).explicitReasoningTags,
        )
        val deepSeek = buildExternalAiMessages(
            messages = listOf(ChatMessage("assistant", "<thinking>secret</thinking>answer")),
            stripAttachments = { it },
            assistantReasoningTags = FusionPromptAdapters.forFamily(ModelFamily.DEEPSEEK).explicitReasoningTags,
        )
        assertEquals("answer", qwen.single().content)
        assertEquals("answer", deepSeek.single().content)
    }

    @Test
    fun `generic user native markup remains external request text`() {
        val literal = "literal <think>example</think>"
        val mapped = buildExternalAiMessages(
            messages = listOf(ChatMessage("user", literal)),
            stripAttachments = { it },
        )
        assertEquals(literal, mapped.single().content)
    }

    @Test
    fun `legacy privacy suppression removes the historical turn before current input`() {
        val unsafe = buildExternalAiMessages(
            history = listOf(
                ChatMessage("user", "old user A"),
                ChatMessage("assistant", "<think>private legacy reasoning</think>old answer"),
            ),
            userInput = "current user B",
            stripAttachments = { it },
        )
        val valid = buildExternalAiMessages(
            history = listOf(
                ChatMessage("user", "old user A"),
                ChatMessage("assistant", "<fusion_thinking>private</fusion_thinking><fusion_answer>old answer</fusion_answer>"),
            ),
            userInput = "current user B",
            stripAttachments = { it },
        )
        assertEquals(listOf("current user B"), unsafe.map { it.content })
        assertEquals(
            listOf("old user A", "old answer", "current user B"),
            valid.map { it.content },
        )
    }
}
