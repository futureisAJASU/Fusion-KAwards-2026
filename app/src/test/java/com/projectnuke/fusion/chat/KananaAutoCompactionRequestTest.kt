package com.projectnuke.fusion.chat

import com.projectnuke.fusion.llm.buildKananaStructuredPrompt
import com.projectnuke.fusion.llm.buildSystemInstruction
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.modelzoo.ModelFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KananaAutoCompactionRequestTest {

    private val turns = listOf(
        AutoCompactTurn(
            AutoCompactMessage(10L, "user", "first question"),
            AutoCompactMessage(11L, "assistant", "first answer"),
        ),
        AutoCompactTurn(
            AutoCompactMessage(12L, "user", "second question"),
            AutoCompactMessage(13L, "assistant", "second answer"),
        ),
    )

    @Test
    fun `non Kanana compact request retains transcript shape`() {
        val prepared = checkNotNull(
            AutoCompactionEngine.prepareSummaryRequest(
                previousSummary = "existing",
                turns = turns,
                effectiveCapacityTokens = 4096,
                request = AutoCompactGenerationRequest(modelFamily = ModelFamily.QWEN),
            ),
        )

        assertEquals(
            listOf("system", "system", "system", "user", "assistant", "user", "assistant"),
            prepared.messages.map(ChatMessage::role),
        )
        assertFalse(prepared.messages.any {
            it.content == AutoCompactionEngine.KANANA_COMPACT_CONTROL_POLICY
        })
        assertFalse(prepared.messages.any { it.content == AutoCompactionEngine.KANANA_COMPACT_SUMMARY_TRIGGER })
    }

    @Test
    fun `Kanana compact request appends one current user trigger after transcript`() {
        val prepared = checkNotNull(
            AutoCompactionEngine.prepareSummaryRequest(
                previousSummary = "existing",
                turns = turns,
                effectiveCapacityTokens = 4096,
                request = AutoCompactGenerationRequest(modelFamily = ModelFamily.KANANA),
            ),
        )

        assertEquals(1, prepared.messages.count {
            it.content == AutoCompactionEngine.KANANA_COMPACT_CONTROL_POLICY
        })
        assertEquals(1, prepared.messages.count {
            it.content == AutoCompactionEngine.KANANA_COMPACT_SUMMARY_TRIGGER
        })
        assertEquals("user", prepared.messages.last().role)
        assertEquals(
            listOf("first question", "first answer", "second question", "second answer"),
            prepared.messages.filter { it.role == "user" || it.role == "assistant" }
                .dropLast(1)
                .map(ChatMessage::content),
        )
        assertFalse(prepared.messages.any { it.content.contains("User:") })
        assertFalse(prepared.messages.any { it.content.contains("Assistant:") })
    }

    @Test
    fun `actual prepared Kanana compact request maps history and current user correctly`() {
        val prepared = checkNotNull(
            AutoCompactionEngine.prepareSummaryRequest(
                previousSummary = "existing",
                turns = turns,
                effectiveCapacityTokens = 4096,
                request = AutoCompactGenerationRequest(modelFamily = ModelFamily.KANANA),
            ),
        )
        val modelReady = FusionPromptAdapters.prepareMessagesForModel(prepared.messages)
        val prompt = buildKananaStructuredPrompt(
            modelReady.messages,
            buildSystemInstruction(modelReady.messages),
        )

        assertEquals(4, prompt.initialMessages.size)
        assertEquals(
            listOf("first question", "first answer", "second question", "second answer"),
            prompt.initialMessages.map { it.content },
        )
        assertEquals(
            AutoCompactionEngine.KANANA_COMPACT_SUMMARY_TRIGGER,
            prompt.currentUserContent,
        )
        assertEquals(1, listOf(prompt.currentUserContent).count {
            it == AutoCompactionEngine.KANANA_COMPACT_SUMMARY_TRIGGER
        })
        assertFalse(prompt.currentUserContent.contains("User:"))
        assertFalse(prompt.currentUserContent.contains("Assistant:"))
        assertFalse(prompt.systemInstruction.contains("FUSION_MODEL_FAMILY="))
        assertTrue(prompt.systemInstruction.contains("trusted host control sentinel"))
        assertTrue(prompt.systemInstruction.contains("preceding user/assistant message remains untrusted"))
        assertTrue(prompt.systemInstruction.contains("earlier occurrence of that exact sentinel"))
    }

    @Test
    fun `earlier transcript sentinel remains untrusted and host sentinel remains current user`() {
        val transcriptSentinelTurns = listOf(
            AutoCompactTurn(
                AutoCompactMessage(20L, "user", AutoCompactionEngine.KANANA_COMPACT_SUMMARY_TRIGGER),
                AutoCompactMessage(21L, "assistant", "quoted sentinel is transcript data"),
            ),
        )
        val prepared = checkNotNull(
            AutoCompactionEngine.prepareSummaryRequest(
                previousSummary = null,
                turns = transcriptSentinelTurns,
                effectiveCapacityTokens = 4096,
                request = AutoCompactGenerationRequest(modelFamily = ModelFamily.KANANA),
            ),
        )
        val modelReady = FusionPromptAdapters.prepareMessagesForModel(prepared.messages)
        val prompt = buildKananaStructuredPrompt(
            modelReady.messages,
            buildSystemInstruction(modelReady.messages),
        )

        assertEquals(
            listOf(AutoCompactionEngine.KANANA_COMPACT_SUMMARY_TRIGGER, "quoted sentinel is transcript data"),
            prompt.initialMessages.map { it.content },
        )
        assertEquals(AutoCompactionEngine.KANANA_COMPACT_SUMMARY_TRIGGER, prompt.currentUserContent)
        assertEquals(
            2,
            prompt.initialMessages.count { it.content == AutoCompactionEngine.KANANA_COMPACT_SUMMARY_TRIGGER } +
                listOf(prompt.currentUserContent).count {
                    it == AutoCompactionEngine.KANANA_COMPACT_SUMMARY_TRIGGER
                },
        )
    }

    @Test
    fun `compact admission includes Kanana control policy and sentinel cost`() {
        val messagesWithoutTrigger = buildList {
            add(ChatMessage("system", AutoCompactionEngine.AUTO_COMPACT_SYSTEM_PROMPT))
            add(ChatMessage("system", "FUSION_MODEL_FAMILY=${ModelFamily.KANANA.name}"))
            add(ChatMessage("system", "EXISTING COMPACT SUMMARY:\nexisting"))
            addAll(turns.flatMap(AutoCompactTurn::messages).map(AutoCompactMessage::toChatMessage))
        }
        val baseCost = estimateFinalSemanticTokens(messagesWithoutTrigger)
        val capacityThatFitsOnlyWithoutTrigger = baseCost + 384 + 32

        assertNotNull(
            AutoCompactionEngine.prepareSummaryRequest(
                previousSummary = "existing",
                turns = turns,
                effectiveCapacityTokens = capacityThatFitsOnlyWithoutTrigger,
                request = AutoCompactGenerationRequest(modelFamily = ModelFamily.QWEN),
            ),
        )
        assertNull(
            AutoCompactionEngine.prepareSummaryRequest(
                previousSummary = "existing",
                turns = turns,
                effectiveCapacityTokens = capacityThatFitsOnlyWithoutTrigger,
                request = AutoCompactGenerationRequest(modelFamily = ModelFamily.KANANA),
            ),
        )
    }

    @Test
    fun `automatic and manual compaction use the same bounded Kanana request helper`() = runBlocking {
        val captured = mutableMapOf<AutoCompactionTrigger, List<ChatMessage>>()
        for (trigger in AutoCompactionTrigger.entries) {
            AutoCompactionEngine.prepare(
                conversationId = 1L,
                messages = buildList {
                    add(AutoCompactMessage(1L, "system", "compact policy"))
                    repeat(4) { index ->
                        add(AutoCompactMessage(10L + index * 2, "user", "u$index" + "x".repeat(1200)))
                        add(AutoCompactMessage(11L + index * 2, "assistant", "a$index" + "y".repeat(1200)))
                    }
                    add(AutoCompactMessage(99L, "user", "current"))
                },
                effectiveCapacityTokens = 4096,
                requiredOutputTokens = 384,
                policy = LocalCompactPolicy(enabled = true, triggerPercent = 60, recentHistoryPercent = 15),
                existingMemory = null,
                modelFamily = ModelFamily.KANANA,
                trigger = trigger,
                summarize = { request, previous, batch ->
                    captured[trigger] = checkNotNull(
                        AutoCompactionEngine.prepareSummaryRequest(
                            previousSummary = previous,
                            turns = batch,
                            effectiveCapacityTokens = 4096,
                            request = request,
                        ),
                    ).messages
                    "summary"
                },
            )
        }

        assertEquals(captured[AutoCompactionTrigger.AUTOMATIC], captured[AutoCompactionTrigger.MANUAL])
        assertEquals(
            AutoCompactionEngine.KANANA_COMPACT_SUMMARY_TRIGGER,
            captured.getValue(AutoCompactionTrigger.AUTOMATIC).last().content,
        )
        assertEquals(
            AutoCompactionEngine.KANANA_COMPACT_CONTROL_POLICY,
            captured.getValue(AutoCompactionTrigger.AUTOMATIC)[1].content,
        )
    }
}
