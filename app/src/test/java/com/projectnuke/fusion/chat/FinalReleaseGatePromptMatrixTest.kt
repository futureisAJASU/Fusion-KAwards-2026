package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.modelzoo.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalReleaseGatePromptMatrixTest {
    @Test
    fun `all local families preserve fitting long current request without post-fit expansion`() {
        val current = "FIRST_SENTINEL\n" + "x".repeat(19_950) + "\nMIDDLE_SENTINEL\nLAST_SENTINEL"
        ModelFamily.entries.forEach { family ->
            val raw = listOf(
                ChatMessage("system", "FUSION_GENERATION_SETTINGS\nmaxTokens=512"),
                ChatMessage("system", "Fusion system rules"),
                ChatMessage("system", "FUSION_SELECTED_MODEL_PATH=/models/$family"),
                ChatMessage("system", "FUSION_MODEL_FAMILY=${family.name}"),
                ChatMessage("system", "[저장된 메모리]\nold context"),
                ChatMessage("user", current),
            )
            val prepared = checkNotNull(
                LocalPromptPreparation.prepare(
                    rawMessages = raw,
                    budget = FinalPromptBudget("release-$family", false, 512, engineKvCapacityTokens = 32_768),
                    settings = GenerationSettings(maxTokens = 512, kvCacheCapacityTokens = 32_768),
                ),
            )
            val modelReady = FusionPromptAdapters.prepareMessagesForModel(prepared.messages)
            val finalMessages = FusionPromptAdapters.forFamily(modelReady.modelFamily).buildMessages(modelReady.messages)
            assertEquals(family, modelReady.modelFamily)
            assertEquals(current, finalMessages.last().content)
            assertFalse(finalMessages.any { it.content.contains("truncated", ignoreCase = true) })
            assertFalse(finalMessages.any { it.content.startsWith("FUSION_") })
            assertTrue(PromptTokenEstimator.estimateTokens(finalMessages) + prepared.appliedOutputTokens + 32 <= 32_768)
            assertEquals(prepared.appliedOutputTokens, prepared.conversationOptions.maxOutputToken)
        }
    }
}
