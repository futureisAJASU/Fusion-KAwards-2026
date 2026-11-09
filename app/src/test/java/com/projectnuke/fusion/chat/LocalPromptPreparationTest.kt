package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1C/1D regression: the shared local request preparation contract.
 *
 *  - Host-only FUSION markers never participate in prompt/history budgeting;
 *    runtime marker length differences cannot change which semantic history
 *    turns survive (1C).
 *  - The `appliedOutputTokens` produced by prompt preparation is the EXACT
 *    value sent to the real LiteRT Conversation through
 *    [PreparedLocalGeneration.conversationOptions] — no second independent
 *    clamp may run downstream (1D).
 */
class LocalPromptPreparationTest {

    private val semanticHistory = buildList {
        add(ChatMessage("system", "정책"))
        repeat(4) { index ->
            add(ChatMessage("user", "u$index-" + "x".repeat(600)))
            add(ChatMessage("assistant", "a$index-" + "y".repeat(600)))
        }
        add(ChatMessage("user", "동일한 사용자 요청"))
    }

    private fun markersSet(gpu: Boolean, output: Int, temperature: Float): List<ChatMessage> = buildList {
        val accelerator = if (gpu) "GPU" else "CPU"
        val mtp = if (gpu) "true" else "false"
        add(
            ChatMessage(
                role = "system",
                content = """
                    FUSION_GENERATION_SETTINGS
                    maxTokens=$output
                    topK=40
                    topP=0.9
                    temperature=$temperature
                    accelerator=$accelerator
                    reasoningBudgetTokens=2048
                    speculativeDecoding=$mtp
                """.trimIndent()
            )
        )
        if (gpu) {
            add(ChatMessage(role = "system", content = "FUSION_SELECTED_MODEL_PATH=/very/long/runtime/path/to/the/gpu-mtp-enabled/selected/model.bin.litertlm"))
        } else {
            add(ChatMessage(role = "system", content = "FUSION_SELECTED_MODEL_PATH=model.bin"))
        }
        add(ChatMessage(role = "system", content = "FUSION_MODEL_FAMILY=GEMMA"))
    }

    @Test
    fun `long accumulated history and eight bounded results fit without truncating current request`() {
        val evidence = buildString {
            appendLine("[FUSION_WEB_SEARCH_EVIDENCE]")
            repeat(8) { index ->
                appendLine("UNTRUSTED_RESULT_${index + 1}_BEGIN")
                appendLine("RESULT_SENTINEL_${index + 1} " + "삼성 반도체 최신 공개 뉴스 근거 ".repeat(8))
                appendLine("UNTRUSTED_RESULT_${index + 1}_END")
            }
            appendLine("CURRENT_REQUEST_SENTINEL")
        }
        val history = buildList {
            add(ChatMessage("system", "Fusion policy " + "정책 ".repeat(80)))
            repeat(16) { index ->
                add(ChatMessage("user", "old-user-$index " + "이전 대화 내용 ".repeat(100)))
                add(ChatMessage("assistant", "old-answer-$index " + "이전 답변 내용 ".repeat(100)))
            }
            add(ChatMessage("user", evidence))
        }
        val settings = GenerationSettings(maxTokens = 1024, kvCacheCapacityTokens = 4096)
        val rawMessages = markersSet(gpu = true, output = 1024, temperature = 0.7f) + history
        val prepared = LocalPromptPreparation.prepare(
            rawMessages = rawMessages,
            budget = FinalPromptBudget("Gemma 4 E2B-it", false, 1024, engineKvCapacityTokens = 4096),
            settings = settings,
        )

        assertNotNull("search evidence plus current request must fit after whole-turn history trimming", prepared)
        val ready = requireNotNull(prepared)
        assertEquals(3, ready.messages.count(FusionHostMarkerPolicy::isHostOnlyMarker))
        assertTrue(ready.messages.last().content.contains("CURRENT_REQUEST_SENTINEL"))
        assertEquals(8, Regex("RESULT_SENTINEL_\\d+").findAll(ready.messages.last().content).count())
        assertTrue(ready.messages.size < rawMessages.size)
        val modelFacing = FusionPromptAdapters.prepareMessagesForModel(ready.messages).messages
        val estimated = PromptTokenEstimator.estimateTokens(modelFacing)
        assertTrue(estimated + ready.appliedOutputTokens + FinalPromptBudgeter.DEFAULT_SAFETY_MARGIN_TOKENS <= 4096)
    }

    @Test
    fun `runtime marker length differences never change which semantic history turns survive`() {
        val markersA = markersSet(gpu = true, output = 4000, temperature = 0.7f)
        val markersB = markersSet(gpu = false, output = 1024, temperature = 1.4f)
        assertNotEquals(
            "marker lengths must actually differ between runs",
            markersA.map { it.content.length }.sum(),
            markersB.map { it.content.length }.sum()
        )

        val budget = FinalPromptBudget(
            modelId = "Gemma 4 E2B-it",
            external = false,
            maxOutputTokens = 4000,
            engineKvCapacityTokens = 8000,
        )
        val settingsA = GenerationSettings(
            maxTokens = 4000,
            kvCacheCapacityTokens = 8000,
            temperature = 0.7f,
            accelerator = AcceleratorMode.GPU,
            speculativeDecodingEnabled = true,
        )
        val settingsB = GenerationSettings(
            maxTokens = 4000,
            kvCacheCapacityTokens = 8000,
            temperature = 1.4f,
            accelerator = AcceleratorMode.CPU,
            speculativeDecodingEnabled = false,
        )

        val preparedA = LocalPromptPreparation.prepare(markersA + semanticHistory, budget, settingsA)!!
        val preparedB = LocalPromptPreparation.prepare(markersB + semanticHistory, budget, settingsB)!!
        assertEquals(
            "applied output must be identical—markers cannot change the capacity result",
            preparedA.appliedOutputTokens,
            preparedB.appliedOutputTokens
        )

        val semanticA = preparedA.messages.filterNot { FusionHostMarkerPolicy.isHostOnlyMarker(it) }
        val semanticB = preparedB.messages.filterNot { FusionHostMarkerPolicy.isHostOnlyMarker(it) }
        assertEquals("marker differences must not change retained semantic turns", semanticA, semanticB)
        assertEquals("동일한 사용자 요청", semanticA.last().content)
    }

    @Test
    fun `prepared applied output is the exact ConversationOptions maxOutputToken`() {
        val settings = GenerationSettings(maxTokens = 1024, kvCacheCapacityTokens = 4096)
        val messages = buildList {
            add(ChatMessage("system", "s".repeat(2_000)))
            add(ChatMessage("user", "u".repeat(4_000)))
        }
        val prepared = LocalPromptPreparation.prepare(
            rawMessages = messages,
            budget = FinalPromptBudget(
                modelId = "Gemma 4 E2B-it",
                external = false,
                maxOutputTokens = 1024,
                engineKvCapacityTokens = 4096,
                safetyMarginTokens = 0,
            ),
            settings = settings,
        )!!
        assertEquals(1024, prepared.requestedOutputTokens)
        assertTrue("applied must be reduced below requested: ${prepared.appliedOutputTokens}", prepared.appliedOutputTokens in 1..1023)
        assertEquals(
            "the value sent to the real Conversation must be the exact applied value",
            prepared.appliedOutputTokens,
            prepared.conversationOptions.maxOutputToken
        )
        assertEquals(OutputAdjustmentReason.ReducedForKvCapacity, prepared.adjustmentReason)
        // Sampling fields flow through unchanged — only the output cap is authoritative.
        assertEquals(settings.temperature, prepared.conversationOptions.temperature)
        assertEquals(settings.topK, prepared.conversationOptions.topK)
        assertEquals(settings.topP, prepared.conversationOptions.topP)
    }

    @Test
    fun `host markers are re-attached to the model-facing message list`() {
        val prepared = LocalPromptPreparation.prepare(
            rawMessages = markersSet(gpu = true, output = 4000, temperature = 0.7f) + semanticHistory,
            budget = FinalPromptBudget(
                modelId = "Gemma 4 E2B-it",
                external = false,
                maxOutputTokens = 4000,
                engineKvCapacityTokens = 8000,
            ),
            settings = GenerationSettings(maxTokens = 4000, kvCacheCapacityTokens = 8000),
        )!!
        val markersInPrepared = prepared.messages.filter { FusionHostMarkerPolicy.isHostOnlyMarker(it) }
        assertEquals(3, markersInPrepared.size)
        assertTrue(markersInPrepared[0].content.startsWith("FUSION_GENERATION_SETTINGS"))
        assertTrue(markersInPrepared[1].content.startsWith("FUSION_SELECTED_MODEL_PATH="))
        assertTrue(markersInPrepared[2].content.startsWith("FUSION_MODEL_FAMILY="))
    }

    @Test
    fun `arbitrary user text containing FUSION substring is never stripped`() {
        val userAskingAboutMarkers = ChatMessage("user", "FUSION_GENERATION_SETTINGS는 무엇인가요?")
        assertTrue(!FusionHostMarkerPolicy.isHostOnlyMarker(userAskingAboutMarkers))
        val assistantQuotingMarker = ChatMessage("assistant", "FUSION_SELECTED_MODEL_PATH=... 라고 적혀 있었어요")
        assertTrue(!FusionHostMarkerPolicy.isHostOnlyMarker(assistantQuotingMarker))
    }

    @Test
    fun `historical authority representation is budgeted before local fitting`() {
        val rawMessages = listOf(
            ChatMessage("system", "Fusion system rules"),
            ChatMessage("system", "[저장된 메모리]\nRemember the project goal."),
            ChatMessage("system", "[대화 요약]\nThe older plan used C++."),
            ChatMessage("system", "[FUSION_AUTO_COMPACT]\nOpen item: choose the implementation language."),
            ChatMessage("system", "FUSION_MODEL_FAMILY=GEMMA"),
            ChatMessage("user", "Use Rust for this request."),
        )
        val settings = GenerationSettings(maxTokens = 384, kvCacheCapacityTokens = 8192)

        listOf(1024, 2048, 8192).forEach { capacity ->
            val prepared = LocalPromptPreparation.prepare(
                rawMessages = rawMessages,
                budget = FinalPromptBudget(
                    modelId = "Gemma 4 E2B-it",
                    external = false,
                    maxOutputTokens = 384,
                    engineKvCapacityTokens = capacity,
                ),
                settings = settings.copy(kvCacheCapacityTokens = capacity),
            )
            if (prepared == null) {
                assertEquals("only the smallest tested KV capacity may reject mandatory policy", 1024, capacity)
                return@forEach
            }
            val semanticBudgeted = prepared.messages.filterNot(FusionHostMarkerPolicy::isHostOnlyMarker)
            val modelReady = FusionPromptAdapters.prepareMessagesForModel(prepared.messages)

            assertEquals(
                "final model representation must be the representation local fitting counted at KV=$capacity",
                PromptTokenEstimator.estimateTokens(semanticBudgeted),
                PromptTokenEstimator.estimateTokens(modelReady.messages),
            )
            assertTrue(
                "final guarded prompt must respect the explicit KV budget at KV=$capacity",
                PromptTokenEstimator.estimateTokens(modelReady.messages) +
                    prepared.appliedOutputTokens +
                    FinalPromptBudgeter.DEFAULT_SAFETY_MARGIN_TOKENS <= capacity,
            )
        }
    }

    @Test
    fun `identical explicit KV capacity is independent of local model identity`() {
        val modelIds = listOf(
            "Gemma 4 E2B-it",
            "Gemma 4 E4B-it",
            "Qwen 1.7B",
            "Qwen 4B",
            "Llama 1B",
            "DeepSeek 1.5B",
            "custom-model-arbitrary-name",
        )
        val messages = listOf(
            ChatMessage("system", "system"),
            ChatMessage("user", "old user"),
            ChatMessage("assistant", "old assistant"),
            ChatMessage("user", "current user"),
        )
        val results = modelIds.map { modelId ->
            LocalPromptPreparation.prepare(
                rawMessages = messages,
                budget = FinalPromptBudget(modelId, false, 4_000, engineKvCapacityTokens = 8_000),
                settings = GenerationSettings(maxTokens = 4_000, kvCacheCapacityTokens = 8_000),
            )!!
        }
        assertTrue(results.drop(1).all { it.messages == results.first().messages })
        assertTrue(results.drop(1).all { it.appliedOutputTokens == results.first().appliedOutputTokens })
    }
}
