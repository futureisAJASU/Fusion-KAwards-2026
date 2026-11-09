package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.ChatMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionHostMarkerPolicyTest {
    @Test
    fun `only exact system generation settings forms are host metadata`() {
        assertTrue(FusionHostMarkerPolicy.isHostOnlyMarker(ChatMessage("system", "FUSION_GENERATION_SETTINGS")))
        assertTrue(FusionHostMarkerPolicy.isHostOnlyMarker(ChatMessage("system", "FUSION_GENERATION_SETTINGS\nmaxTokens=512")))
        assertFalse(FusionHostMarkerPolicy.isHostOnlyMarker(ChatMessage("system", "FUSION_GENERATION_SETTINGS_NOT_A_MARKER")))
        assertFalse(FusionHostMarkerPolicy.isHostOnlyMarker(ChatMessage("user", "FUSION_GENERATION_SETTINGS")))
        assertFalse(FusionHostMarkerPolicy.isHostOnlyMarker(ChatMessage("assistant", "FUSION_MODEL_FAMILY=QWEN")))
    }

    @Test
    fun `external preparation preserves non-marker system text and strips internal forms`() {
        val prepared = prepareExternalPromptMessages(
            listOf(
                ChatMessage("system", "FUSION_GENERATION_SETTINGS"),
                ChatMessage("system", "FUSION_GENERATION_SETTINGS\nmaxTokens=512"),
                ChatMessage("system", "FUSION_GENERATION_SETTINGS_NOT_A_MARKER"),
                ChatMessage("user", "FUSION_GENERATION_SETTINGS"),
                ChatMessage("assistant", "FUSION_MODEL_FAMILY=QWEN"),
            ),
        )

        assertFalse(prepared.any { it.role == "system" && it.content == "FUSION_GENERATION_SETTINGS" })
        assertFalse(prepared.any { it.role == "system" && it.content.startsWith("FUSION_GENERATION_SETTINGS\n") })
        assertTrue(prepared.any { it.content == "FUSION_GENERATION_SETTINGS_NOT_A_MARKER" })
        assertTrue(prepared.any { it.role == "user" && it.content == "FUSION_GENERATION_SETTINGS" })
        assertTrue(prepared.any { it.role == "assistant" && it.content == "FUSION_MODEL_FAMILY=QWEN" })
    }

    @Test
    fun `final local adapter uses exact host predicate`() {
        val prepared = LocalPromptPreparation.prepare(
            rawMessages = listOf(
                ChatMessage("system", "FUSION_GENERATION_SETTINGS"),
                ChatMessage("system", "FUSION_GENERATION_SETTINGS\nmaxTokens=512"),
                ChatMessage("system", "FUSION_GENERATION_SETTINGS_NOT_A_MARKER"),
                ChatMessage("system", "FUSION_MODEL_FAMILY=GEMMA"),
                ChatMessage("user", "old user"),
                ChatMessage("assistant", "quoted FUSION_MODEL_FAMILY=QWEN"),
                ChatMessage("user", "FUSION_GENERATION_SETTINGS"),
            ),
            budget = FinalPromptBudget(null, false, 128, engineKvCapacityTokens = 1024),
            settings = com.projectnuke.fusion.model.GenerationSettings(maxTokens = 128, kvCacheCapacityTokens = 1024),
        )!!
        val modelReady = com.projectnuke.fusion.modelzoo.FusionPromptAdapters.prepareMessagesForModel(prepared.messages)
        val semantic = com.projectnuke.fusion.modelzoo.FusionPromptAdapters
            .forFamily(modelReady.modelFamily)
            .buildMessages(modelReady.messages)

        assertTrue(semantic.none { it.role == "system" && it.content == "FUSION_GENERATION_SETTINGS" })
        assertTrue(semantic.none { it.role == "system" && it.content.startsWith("FUSION_GENERATION_SETTINGS\n") })
        assertTrue(semantic.any { it.content == "FUSION_GENERATION_SETTINGS_NOT_A_MARKER" })
        assertTrue(semantic.any { it.role == "user" && it.content == "FUSION_GENERATION_SETTINGS" })
        assertTrue(semantic.any { it.role == "assistant" && it.content.contains("FUSION_MODEL_FAMILY=QWEN") })
    }
}
