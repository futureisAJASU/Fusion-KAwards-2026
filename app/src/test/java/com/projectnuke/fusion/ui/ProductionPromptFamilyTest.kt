package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.FinalPromptBudget
import com.projectnuke.fusion.chat.LocalPromptPreparation
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.modelzoo.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionPromptFamilyTest {
    @Test
    fun `AB production request preserves every target family`() {
        ModelFamily.entries.forEach { family ->
            val raw = buildAbTestPromptMessages(
                modelFamily = family,
                savedMemoryText = "[저장된 메모리]\nold preference",
                prompt = "current test prompt",
            )
            val prepared = checkNotNull(
                LocalPromptPreparation.prepare(
                    rawMessages = raw,
                    budget = FinalPromptBudget(
                        modelId = "ab-$family",
                        external = false,
                        maxOutputTokens = 128,
                        engineKvCapacityTokens = 2048,
                    ),
                    settings = GenerationSettings(maxTokens = 128, kvCacheCapacityTokens = 2048),
                ),
            )
            val modelReady = FusionPromptAdapters.prepareMessagesForModel(prepared.messages)
            assertEquals(family, modelReady.modelFamily)
            assertTrue(modelReady.messages.any { it.role == "user" && it.content == "current test prompt" })
            assertTrue(modelReady.messages.any { it.content.contains("old preference") })
            assertFalse(modelReady.messages.any { it.content.startsWith("FUSION_MODEL_FAMILY=") })
        }
    }

    @Test
    fun `benchmark snapshot request preserves frozen family`() {
        ModelFamily.entries.forEach { family ->
            val snapshot = BenchmarkSnapshot(
                modelName = "benchmark-$family",
                modelPath = null,
                settings = GenerationSettings(maxTokens = 128, kvCacheCapacityTokens = 2048),
                requestedMaxTokens = 128,
                requestedKvCapacity = 2048,
                safeMaxTokensCap = null,
                reasoningEnabled = false,
                webSearchEnabled = false,
                modelFamily = family,
            )
            val prepared = checkNotNull(
                LocalPromptPreparation.prepare(
                    rawMessages = buildBenchmarkPromptMessages(snapshot.modelFamily, "benchmark prompt"),
                    budget = FinalPromptBudget(snapshot.modelName, false, 128, engineKvCapacityTokens = 2048),
                    settings = snapshot.settings,
                ),
            )
            val modelReady = FusionPromptAdapters.prepareMessagesForModel(prepared.messages)
            assertEquals(family, modelReady.modelFamily)
            assertTrue(modelReady.messages.any { it.role == "user" && it.content == "benchmark prompt" })
        }
    }
}
