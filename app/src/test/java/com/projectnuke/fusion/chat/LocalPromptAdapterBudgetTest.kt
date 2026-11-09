package com.projectnuke.fusion.chat

import com.projectnuke.fusion.llm.buildPrompt
import com.projectnuke.fusion.llm.buildSystemInstruction
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.modelzoo.DEEPSEEK_REASONING_INSTRUCTION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPromptAdapterBudgetTest {
    @Test
    fun `production host-marker ordering budgets DeepSeek policy before adapter serialization`() {
        val capacity = 2048
        val settings = GenerationSettings(maxTokens = 512, kvCacheCapacityTokens = capacity)
        val prepared = checkNotNull(
            LocalPromptPreparation.prepare(
                rawMessages = listOf(
                    ChatMessage("system", "FUSION_GENERATION_SETTINGS\nmaxTokens=512"),
                    ChatMessage("system", "Fusion system rules"),
                    ChatMessage("system", "FUSION_SELECTED_MODEL_PATH=/models/deepseek"),
                    ChatMessage("system", "FUSION_MODEL_FAMILY=DEEPSEEK"),
                    ChatMessage("system", "[저장된 메모리]\nold context"),
                    ChatMessage("user", "current request"),
                ),
                budget = FinalPromptBudget(null, false, 512, engineKvCapacityTokens = capacity),
                settings = settings,
            ),
        )

        assertTrue(prepared.messages.any {
            it.role == "system" && it.content == DEEPSEEK_REASONING_INSTRUCTION
        })
        val modelReady = FusionPromptAdapters.prepareMessagesForModel(prepared.messages)
        assertFalse(modelReady.messages.any { it.content.startsWith("FUSION_GENERATION_SETTINGS") })
        val adapted = FusionPromptAdapters.forFamily(modelReady.modelFamily).buildMessages(modelReady.messages)
        assertTrue(adapted.any { it.content == DEEPSEEK_REASONING_INSTRUCTION })
        assertTrue(
            PromptTokenEstimator.estimateTokens(adapted) +
                prepared.appliedOutputTokens + FinalPromptBudgeter.DEFAULT_SAFETY_MARGIN_TOKENS <= capacity,
        )
        assertEquals(prepared.appliedOutputTokens, prepared.conversationOptions.maxOutputToken)
    }

    @Test
    fun `historical source matrix uses one guarded representation across capacities and families`() {
        val sourceSets = listOf(
            emptyList(),
            listOf("[저장된 메모리]\nsaved context"),
            listOf("[대화 요약]\nmanual context"),
            listOf("[FUSION_AUTO_COMPACT]\nauto context"),
            listOf(
                "[저장된 메모리]\nsaved context",
                "[대화 요약]\nmanual context",
                "[FUSION_AUTO_COMPACT]\nauto context",
            ),
        )

        listOf(2048, 4096, 8192).forEach { capacity ->
            ModelFamily.entries.forEach { family ->
                sourceSets.forEach { sources ->
                    val prepared = checkNotNull(
                        LocalPromptPreparation.prepare(
                            rawMessages = buildList {
                                add(ChatMessage("system", "Fusion system rules"))
                                add(ChatMessage("system", "FUSION_MODEL_FAMILY=${family.name}"))
                                sources.forEach { add(ChatMessage("system", it)) }
                                add(ChatMessage("user", "new current request"))
                            },
                            budget = FinalPromptBudget(
                                modelId = null,
                                external = false,
                                maxOutputTokens = 256,
                                engineKvCapacityTokens = capacity,
                            ),
                            settings = GenerationSettings(
                                maxTokens = 256,
                                kvCacheCapacityTokens = capacity,
                            ),
                        ),
                    )
                    val modelReady = FusionPromptAdapters.prepareMessagesForModel(prepared.messages)
                    val adapted = FusionPromptAdapters.forFamily(modelReady.modelFamily).buildMessages(modelReady.messages)
                    val semanticPrompt = buildSystemInstruction(adapted) + "\n" + buildPrompt(adapted)

                    assertTrue(semanticPrompt.contains("User:\nnew current request"))
                    assertTrue(
                        semanticPrompt.windowedCount(HISTORICAL_MEMORY_POLICY_MARKER) ==
                            if (sources.isEmpty()) 0 else 1,
                    )
                    assertTrue(!semanticPrompt.contains("FUSION_MODEL_FAMILY="))
                    assertTrue(
                        PromptTokenEstimator.estimateTokens(adapted) +
                            prepared.appliedOutputTokens + 32 <= capacity,
                    )
                    assertTrue(prepared.appliedOutputTokens == prepared.conversationOptions.maxOutputToken)
                }
            }
        }
    }

    @Test
    fun `every family stays within the authoritative fit after adapter serialization`() {
        val capacity = 2048
        val requestedOutput = 512

        ModelFamily.entries.forEach { family ->
            val prepared = (0..12_000 step 4).asSequence()
                .mapNotNull { fillerLength ->
                    LocalPromptPreparation.prepare(
                        rawMessages = listOf(
                            ChatMessage("system", "Fusion system rules"),
                            ChatMessage("system", "FUSION_MODEL_FAMILY=${family.name}"),
                            ChatMessage("system", "[저장된 메모리]\nhistorical context"),
                            ChatMessage("user", "x".repeat(fillerLength)),
                        ),
                        budget = FinalPromptBudget(
                            modelId = null,
                            external = false,
                            maxOutputTokens = requestedOutput,
                            engineKvCapacityTokens = capacity,
                        ),
                        settings = GenerationSettings(
                            maxTokens = requestedOutput,
                            kvCacheCapacityTokens = capacity,
                        ),
                    )?.takeIf { it.appliedOutputTokens < requestedOutput }
                }
                .firstOrNull()

            assertTrue("family $family must have a clamped near-capacity fixture", prepared != null)
            val modelReady = FusionPromptAdapters.prepareMessagesForModel(prepared!!.messages)
            val adapted = FusionPromptAdapters.forFamily(modelReady.modelFamily).buildMessages(modelReady.messages)
            val budgetedCost = estimateFinalSemanticTokens(prepared.messages)
            val finalCost = PromptTokenEstimator.estimateTokens(adapted)

            assertTrue("family $family expanded after fitting", finalCost <= budgetedCost)
            assertTrue(
                "family $family exceeded KV after adapter serialization",
                finalCost + prepared.appliedOutputTokens + FinalPromptBudgeter.DEFAULT_SAFETY_MARGIN_TOKENS <= capacity,
            )
        }
    }

    private fun String.windowedCount(value: String): Int = windowed(value.length).count { it == value }
}
