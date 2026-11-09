package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.modelzoo.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionPromptOrderingMatrixTest {
    @Test
    fun `main retry style and compact production ordering fits every family`() {
        ModelFamily.entries.forEach { family ->
            listOf("main", "retry", "style").forEach { generationKind ->
                val prepared = checkNotNull(
                    LocalPromptPreparation.prepare(
                        rawMessages = productionMessages(family, generationKind),
                        budget = FinalPromptBudget(
                            modelId = "production-$family",
                            external = false,
                            maxOutputTokens = 256,
                            engineKvCapacityTokens = 8192,
                        ),
                        settings = GenerationSettings(maxTokens = 256, kvCacheCapacityTokens = 8192),
                    ),
                )
                val modelReady = FusionPromptAdapters.prepareMessagesForModel(prepared.messages)
                val adapted = FusionPromptAdapters.forFamily(modelReady.modelFamily).buildMessages(modelReady.messages)

                assertEquals(family, modelReady.modelFamily)
                assertFalse(adapted.any { it.content == "FUSION_GENERATION_SETTINGS\nmaxTokens=256" })
                assertFalse(adapted.any { it.content.startsWith("FUSION_SELECTED_MODEL_PATH=") })
                assertFalse(adapted.any { it.content.startsWith("FUSION_MODEL_FAMILY=") })
                assertTrue(adapted.any { it.role == "user" && it.content == "current user request" })
                assertEquals(1, adapted.count { it.content.contains("[FUSION_HISTORICAL_MEMORY_POLICY]") })
                assertTrue(
                    PromptTokenEstimator.estimateTokens(adapted) + prepared.appliedOutputTokens + 32 <= 8192,
                )
            }
        }
    }

    @Test
    fun `internal compact ordering preserves source turns and family adapter`() {
        ModelFamily.entries.forEach { family ->
            val raw = listOf(
                ChatMessage("system", "internal compact system prompt"),
                ChatMessage("system", "FUSION_MODEL_FAMILY=${family.name}"),
                ChatMessage("system", "[FUSION_AUTO_COMPACT]\nexisting summary"),
                ChatMessage("user", "completed source turn"),
                ChatMessage("assistant", "completed answer"),
            )
            val prepared = checkNotNull(
                LocalPromptPreparation.prepare(
                    rawMessages = raw,
                    budget = FinalPromptBudget("compact-$family", false, 128, engineKvCapacityTokens = 2048),
                    settings = GenerationSettings(maxTokens = 128, kvCacheCapacityTokens = 2048),
                ),
            )
            val modelReady = FusionPromptAdapters.prepareMessagesForModel(prepared.messages)
            val adapted = FusionPromptAdapters.forFamily(modelReady.modelFamily).buildMessages(modelReady.messages)
            assertEquals(family, modelReady.modelFamily)
            assertTrue(adapted.any { it.content.contains("completed source turn") })
            assertTrue(adapted.none { it.content.startsWith("FUSION_MODEL_FAMILY=") })
            assertTrue(PromptTokenEstimator.estimateTokens(adapted) + prepared.appliedOutputTokens + 32 <= 2048)
        }
    }

    private fun productionMessages(family: ModelFamily, generationKind: String): List<ChatMessage> = listOf(
        ChatMessage("system", "FUSION_GENERATION_SETTINGS\nmaxTokens=256"),
        ChatMessage("system", "Fusion system prompt for $generationKind"),
        ChatMessage("system", "FUSION_SELECTED_MODEL_PATH=/models/$family"),
        ChatMessage("system", "FUSION_MODEL_FAMILY=${family.name}"),
        ChatMessage("system", "FUSION_GENERATION_SETTINGS_NOT_A_MARKER"),
        ChatMessage("system", "[저장된 메모리]\nold preference"),
        ChatMessage("system", "[대화 요약]\nold decision"),
        ChatMessage("system", "[FUSION_AUTO_COMPACT]\nold summary"),
        ChatMessage("assistant", "history"),
        ChatMessage("user", "current user request"),
    )
}
