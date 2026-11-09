package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.FinalPromptBudget
import com.projectnuke.fusion.chat.LocalPromptPreparation
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.modelzoo.ModelFamily
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbSavedMemoryAuthorityTest {
    @Test
    fun `model-only memory uses stable target id and remains system data`() {
        val candidate = ConversationMemoryCandidate(
            id = "memory",
            text = "Use C++ by default",
            conversationId = 0L,
            createdAt = 1L,
            scope = MemoryScope.MODEL_ONLY,
            modelId = "stable-qwen-id",
        )
        assertTrue(isSavedMemoryCandidateApplicable(candidate, null, "stable-qwen-id", false))
        assertFalse(isSavedMemoryCandidateApplicable(candidate, null, "Qwen display name", false))

        val prepared = checkNotNull(
            LocalPromptPreparation.prepare(
                rawMessages = buildAbTestPromptMessages(
                    modelFamily = ModelFamily.QWEN,
                    savedMemoryText = "[저장된 메모리]\n- Use C++ by default",
                    prompt = "Use Rust for this test",
                ),
                budget = FinalPromptBudget("stable-qwen-id", false, 128, engineKvCapacityTokens = 2048),
                settings = GenerationSettings(maxTokens = 128, kvCacheCapacityTokens = 2048),
            ),
        )
        val modelReady = FusionPromptAdapters.prepareMessagesForModel(prepared.messages)
        assertTrue(modelReady.messages.any { it.role == "system" && it.content.contains("Use C++") })
        assertTrue(modelReady.messages.any { it.role == "user" && it.content == "Use Rust for this test" })
        assertTrue(modelReady.messages.none { it.role == "user" && it.content.contains("[저장된 메모리]") })
        assertTrue(modelReady.messages.any { it.content.contains("historical", ignoreCase = true) })
    }
}
