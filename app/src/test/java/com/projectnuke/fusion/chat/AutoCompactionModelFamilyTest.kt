package com.projectnuke.fusion.chat

import com.projectnuke.fusion.llm.buildPrompt
import com.projectnuke.fusion.llm.buildSystemInstruction
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.modelzoo.DEEPSEEK_REASONING_INSTRUCTION
import com.projectnuke.fusion.modelzoo.ModelFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCompactionModelFamilyTest {

    private val turns = listOf(
        AutoCompactTurn(
            AutoCompactMessage(10L, "user", "new user turn"),
            AutoCompactMessage(11L, "assistant", "new assistant turn"),
        ),
    )

    @Test
    fun `internal compact request selects and strips every supported model family`() {
        ModelFamily.entries.forEach { family ->
            val prepared = checkNotNull(
                AutoCompactionEngine.prepareSummaryRequest(
                    previousSummary = "existing summary",
                    turns = turns,
                    effectiveCapacityTokens = 4096,
                    request = AutoCompactGenerationRequest(modelFamily = family),
                ),
            )
            val modelReady = FusionPromptAdapters.prepareMessagesForModel(prepared.messages)
            assertEquals(family, modelReady.modelFamily)
            assertFalse(
                modelReady.messages.any { it.content.startsWith("FUSION_MODEL_FAMILY=") },
            )
            val semanticPrompt = buildSystemInstruction(modelReady.messages) + "\n" +
                buildPrompt(modelReady.messages)
            assertFalse(semanticPrompt.contains("FUSION_MODEL_FAMILY="))
            assertTrue(semanticPrompt.contains("new user turn"))
            val adapted = FusionPromptAdapters.forFamily(modelReady.modelFamily).buildMessages(modelReady.messages)
            assertEquals(
                if (family == ModelFamily.DEEPSEEK) 1 else 0,
                adapted.sumOf { it.content.windowed(DEEPSEEK_REASONING_INSTRUCTION.length).count { window -> window == DEEPSEEK_REASONING_INSTRUCTION } },
            )
            assertTrue(PromptTokenEstimator.estimateTokens(adapted) + 384 + 32 <= 4096)
        }
    }

    @Test
    fun `local compact preparation propagates the resolved host family to every batch`() = runBlocking {
        val policy = LocalCompactPolicy(enabled = true, triggerPercent = 80, recentHistoryPercent = 25)
        ModelFamily.entries.forEach { family ->
            val capturedFamilies = mutableListOf<ModelFamily>()
            val rawMessages = buildList {
                add(ChatMessage("system", "FUSION_MODEL_FAMILY=${family.name}"))
                repeat(8) { index ->
                    add(ChatMessage("user", "u$index" + "x".repeat(500)))
                    add(ChatMessage("assistant", "a$index" + "y".repeat(500)))
                }
                add(ChatMessage("user", "current"))
            }
            prepareLocalAutoCompaction(
                conversationId = 1L,
                rawMessages = rawMessages,
                historyMessageIds = (10L..25L).toList(),
                settings = GenerationSettings(maxTokens = 384, kvCacheCapacityTokens = 2048),
                compactPolicy = policy,
                existingMemory = null,
                summarize = { request, _, _ ->
                    capturedFamilies += request.modelFamily
                    "summary"
                },
            )
            assertTrue(capturedFamilies.isNotEmpty())
            assertTrue(capturedFamilies.all { it == family })
        }
    }

    @Test
    fun `deepseek adds its reasoning instruction once without mutating memory blocks`() {
        val memory = "[FUSION_AUTO_COMPACT]\nhistorical summary"
        val adapted = FusionPromptAdapters.forFamily(ModelFamily.DEEPSEEK).buildMessages(
            FusionPromptAdapters.prepareMessagesForLocalBudget(
                listOf(
                ChatMessage("system", "Fusion system rules"),
                ChatMessage("system", "FUSION_MODEL_FAMILY=DEEPSEEK"),
                ChatMessage("system", memory),
                ChatMessage("system", "[대화 요약]\nmanual summary"),
                ChatMessage("user", "current request"),
                ),
            ),
        )
        val instruction = "추론 출력이 길어질 수 있으므로 최종 답변은 명확하게 구분해 주세요."

        assertEquals(1, adapted.sumOf { it.content.windowed(instruction.length).count { window -> window == instruction } })
        assertTrue(adapted.any { it.content == memory })
        assertTrue(adapted.any { it.content == "[대화 요약]\nmanual summary" })
    }

    @Test
    fun `DeepSeek compact batch admission includes final semantic policy cost`() {
        val capacity = 1024
        val acceptedBeforeFinalPolicy = (0..2400 step 8).firstOrNull { filler ->
            val prepared = AutoCompactionEngine.prepareSummaryRequest(
                previousSummary = "s".repeat(filler),
                turns = listOf(
                    AutoCompactTurn(
                        AutoCompactMessage(10L, "user", "u"),
                        AutoCompactMessage(11L, "assistant", "a"),
                    ),
                ),
                effectiveCapacityTokens = capacity,
                request = AutoCompactGenerationRequest(modelFamily = ModelFamily.DEEPSEEK),
            ) ?: return@firstOrNull false
            val modelReady = FusionPromptAdapters.prepareMessagesForModel(prepared.messages)
            val adapted = FusionPromptAdapters.forFamily(modelReady.modelFamily).buildMessages(modelReady.messages)
            PromptTokenEstimator.estimateTokens(adapted) + 384 + 32 > capacity
        }

        assertEquals(
            "summary admission must reject requests that exceed capacity after DeepSeek policy serialization",
            null,
            acceptedBeforeFinalPolicy,
        )
    }
}
