package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.modelzoo.KananaPromptAdapter
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.modelzoo.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KananaStructuredPromptTest {

    @Test
    fun `first turn sends one current user without transcript labels`() {
        val prepared = FusionPromptAdapters.prepareMessagesForModel(
            listOf(
                ChatMessage("system", "FUSION_MODEL_FAMILY=${ModelFamily.KANANA.name}"),
                ChatMessage("system", "Fusion semantic system content"),
                ChatMessage("user", "안녕! 한 문장으로 자기소개해줘."),
            )
        )

        val prompt = buildKananaStructuredPrompt(prepared.messages, buildSystemInstruction(prepared.messages))

        assertTrue(prompt.initialMessages.isEmpty())
        assertEquals("안녕! 한 문장으로 자기소개해줘.", prompt.currentUserContent)
        assertFalse(prompt.currentUserContent.contains("User:"))
        assertFalse(prompt.currentUserContent.contains("Assistant:"))
        assertEquals(1, prompt.systemInstruction.split("Fusion semantic system content").size - 1)
    }

    @Test
    fun `second turn maps prior roles and sends current user exactly once`() {
        val prepared = FusionPromptAdapters.prepareMessagesForModel(
            listOf(
                ChatMessage("system", "FUSION_MODEL_FAMILY=${ModelFamily.KANANA.name}"),
                ChatMessage("system", "Fusion semantic system content"),
                ChatMessage("user", "안녕! 한 문장으로 자기소개해줘."),
                ChatMessage("assistant", "저는 Fusion의 로컬 AI 비서입니다."),
                ChatMessage("user", "아까 답변을 더 짧게 말해줘."),
            )
        )

        val prompt = buildKananaStructuredPrompt(prepared.messages, buildSystemInstruction(prepared.messages))

        assertEquals(
            listOf(
                StructuredLiteRtMessage(StructuredLiteRtRole.USER, "안녕! 한 문장으로 자기소개해줘."),
                StructuredLiteRtMessage(StructuredLiteRtRole.MODEL, "저는 Fusion의 로컬 AI 비서입니다."),
            ),
            prompt.initialMessages,
        )
        assertEquals("아까 답변을 더 짧게 말해줘.", prompt.currentUserContent)
        assertEquals(1, listOf(prompt.currentUserContent).count { it == "아까 답변을 더 짧게 말해줘." })
        assertFalse(prompt.initialMessages.any { it.content == prompt.currentUserContent })
    }

    @Test
    fun `host markers are removed before Kanana role mapping`() {
        val prepared = FusionPromptAdapters.prepareMessagesForModel(
            listOf(
                ChatMessage("system", "FUSION_MODEL_FAMILY=${ModelFamily.KANANA.name}"),
                ChatMessage("system", "FUSION_GENERATION_SETTINGS\nmaxTokens=256"),
                ChatMessage("system", "FUSION_SELECTED_MODEL_PATH=/managed/kanana.litertlm"),
                ChatMessage("system", "Fusion semantic system content"),
                ChatMessage("user", "현재 질문"),
            )
        )
        val prompt = buildKananaStructuredPrompt(prepared.messages, buildSystemInstruction(prepared.messages))
        val visibleBytes = buildString {
            append(prompt.systemInstruction)
            prompt.initialMessages.forEach { append(it.content) }
            append(prompt.currentUserContent)
        }

        assertFalse(visibleBytes.contains("FUSION_MODEL_FAMILY"))
        assertFalse(visibleBytes.contains("FUSION_GENERATION_SETTINGS"))
        assertFalse(visibleBytes.contains("FUSION_SELECTED_MODEL_PATH"))
    }

    @Test
    fun `non Kanana families retain flattened prompt construction`() {
        val messages = listOf(
            ChatMessage("system", "FUSION_MODEL_FAMILY=${ModelFamily.QWEN.name}"),
            ChatMessage("system", "semantic"),
            ChatMessage("user", "previous"),
            ChatMessage("assistant", "answer"),
            ChatMessage("user", "current"),
        )
        val prepared = FusionPromptAdapters.prepareMessagesForModel(messages)
        assertEquals(ModelFamily.QWEN, prepared.modelFamily)
        assertTrue(buildPrompt(FusionPromptAdapters.forFamily(prepared.modelFamily).buildMessages(prepared.messages)).contains("User:"))
    }

    @Test
    fun `user stop always returns Cancelled with USER_STOP intent even for blank partial`() {
        val partial = userStopOutcome(KananaPromptAdapter, "  안녕하세요  ")
        assertEquals(
            GenerationOutcome.Cancelled(
                partialText = "안녕하세요",
                terminationIntent = GenerationTerminationIntent.USER_STOP,
            ),
            partial,
        )
        val blank = userStopOutcome(KananaPromptAdapter, "   ")
        assertEquals(
            GenerationOutcome.Cancelled(
                partialText = "",
                terminationIntent = GenerationTerminationIntent.USER_STOP,
            ),
            blank,
        )
    }
}
