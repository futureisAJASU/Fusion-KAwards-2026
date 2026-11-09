package com.projectnuke.fusion.chat

import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.ui.ResponseVersionState
import com.projectnuke.fusion.ui.activeTimelineMessages
import com.projectnuke.fusion.util.sanitizeAssistantHistoryTurns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptHistoryBudgeterTest {
    @Test
    fun `privacy-withheld assistant never reaches budgeter as a blank message`() {
        val sanitized = sanitizeAssistantHistoryTurns(
            listOf(
                message("user", "old user A"),
                message("assistant", "<think>private legacy reasoning</think>old answer"),
                message("user", "current user B"),
            ),
        )
        val selected = select(sanitized)

        assertEquals(listOf("user"), selected.messages.map { it.role })
        assertEquals(listOf("current user B"), selected.messages.map { it.content })
        assertTrue(selected.messages.none { it.role == "assistant" && it.content.isBlank() })
        assertTrue(selected.messages.none { it.content.contains("old user A") })
    }

    @Test
    fun `short coherent history is preserved`() {
        val history = listOf(
            message("user", "u1"),
            message("assistant", "a1"),
            message("user", "u2"),
            message("assistant", "a2"),
        )

        val selected = select(history)

        assertEquals(history, selected.messages)
        assertEquals(0, selected.omittedTurnCount)
    }

    @Test
    fun `external budgeter sees more than two hundred fifty six short messages`() {
        val history = (1..300).flatMap { index ->
            listOf(message("user", "u$index"), message("assistant", "a$index"))
        }
        val selected = selectExternal(history, maxOutputTokens = 512)
        assertEquals(history, selected.messages)
    }

    @Test
    fun `long history keeps the most recent whole turns`() {
        val history = (1..20).flatMap { index ->
            listOf(
                message("user", "u$index-" + "x".repeat(600)),
                message("assistant", "a$index-" + "y".repeat(600)),
            )
        }

        val selected = select(history, maxOutputTokens = 2_000)

        assertTrue(selected.omittedTurnCount > 0)
        assertTrue(selected.messages.last().content.startsWith("a20-"))
        assertWholeTurns(selected.messages)
    }

    @Test
    fun `long individual messages are preserved or omitted as whole turns`() {
        val history = listOf(
            message("user", "U".repeat(20_000)),
            message("assistant", "A".repeat(20_000)),
        )

        val selected = select(history, modelId = "Gemma E4B", maxOutputTokens = 128)

        assertTrue(selected.messages.isEmpty() || selected.messages.size == 2)
        assertTrue(selected.messages.none { "oversized message truncated" in it.content })
        if (selected.messages.size == 2) {
            assertEquals(20_000, selected.messages[0].content.length)
            assertEquals(20_000, selected.messages[1].content.length)
        }
    }

    @Test
    fun `attachment and web reservations reduce retained history without splitting pairs`() {
        val history = (1..8).flatMap { index ->
            listOf(
                message("user", "user-$index-" + "x".repeat(400)),
                message("assistant", "assistant-$index-" + "y".repeat(400)),
            )
        }

        val selected = select(
            history = history,
            attachmentCount = 5,
            webSearchPlanned = true,
            maxOutputTokens = 1_000,
        )

        assertTrue(selected.messages.size < history.size)
        assertWholeTurns(selected.messages)
    }

    @Test
    fun `response version branch is selected before budgeting`() {
        val entities = listOf(
            entity(1, "user", "question"),
            entity(11, "assistant", "old branch"),
            entity(12, "assistant", "new branch"),
        )
        val versionState = ResponseVersionState(
            groupByMessageId = mapOf(11L to 1L, 12L to 1L),
            activeMessageIdByGroup = mapOf(1L to 11L),
        )
        val activeBranch = activeTimelineMessages(entities, versionState)
            .map { message(it.role, it.content) }

        val selected = select(activeBranch)

        assertTrue(selected.messages.any { it.content == "old branch" })
        assertFalse(selected.messages.any { it.content == "new branch" })
    }

    @Test
    fun `summary is included only when older turns are omitted`() {
        val history = (1..20).flatMap { index ->
            listOf(
                message("user", "u$index-" + "x".repeat(600)),
                message("assistant", "a$index-" + "y".repeat(600)),
            )
        }

        val selected = select(
            history = history,
            maxOutputTokens = 2_000,
            summary = "deterministic older context",
        )

        assertTrue(selected.usedSummary)
        assertTrue(selected.messages.first().content.contains("deterministic older context"))
        assertWholeTurns(selected.messages.filter { it.role != "system" })
    }

    @Test
    fun `local E2B with explicit KV capacity retains whole turns instead of zeroing history`() {
        // Previous semantics treated "Gemma E2B + maxOutput=4000 => zero retained
        // history" as correct. With an explicit Engine KV capacity the
        // authoritative hard capacity is the prompt+output KV budget (8000
        // tokens) — NOT the 16000-char model-name heuristic minus output*4.
        val selected = select(
            history = listOf(
                message("system", "policy"),
                message("user", "old"),
                message("assistant", "reply"),
            ),
            modelId = "Gemma E2B",
            maxOutputTokens = 4_000,
            engineKvCapacityTokens = 8_000,
        )

        assertEquals(listOf("system", "user", "assistant"), selected.messages.map { it.role })
        assertEquals(0, selected.omittedTurnCount)
    }

    @Test
    fun `local E2B attachments and web reserve history against the KV budget in whole turns`() {
        val history = (1..8).flatMap { index ->
            listOf(
                message("user", "user-$index-" + "x".repeat(400)),
                message("assistant", "assistant-$index-" + "y".repeat(400)),
            )
        }

        val selected = select(
            history = history,
            modelId = "Gemma E2B",
            maxOutputTokens = 4_000,
            attachmentCount = 5,
            webSearchPlanned = true,
            engineKvCapacityTokens = 16_000,
        )

        // The attachments/web/output reserve is charged against the KV token
        // budget, never against the 16000-char heuristic; whole turns survive.
        assertTrue(selected.messages.isNotEmpty())
        assertWholeTurns(selected.messages)
    }

    @Test
    fun `local Korean history selection uses final fitter token costs and safety margin`() {
        val history = listOf(message("system", "핵심 시스템 정책")) + (1..8).flatMap { index ->
            listOf(
                message("user", "이전 한국어 요청 $index " + "삼성전자 관련 역사 문맥입니다. ".repeat(12)),
                message("assistant", "이전 답변 $index " + "검색과 대화에 관한 요약 정보입니다. ".repeat(12)),
            )
        }
        val selected = PromptHistoryBudgeter.select(
            PromptHistoryBudgetRequest(
                history = history,
                modelId = "Gemma 4 E2B",
                generationModeKey = "LOCAL_MODEL",
                maxOutputTokens = 512,
                currentRequestChars = 64,
                attachmentCount = 0,
                webSearchPlanned = false,
                engineKvCapacityTokens = 4_096,
                currentRequestText = "current request " + "x".repeat(48),
            ),
        )
        val finalMessages = FinalPromptBudgeter.fit(
            selected.messages + message("user", "current request " + "x".repeat(48)),
            FinalPromptBudget(
                modelId = "Gemma 4 E2B",
                external = false,
                maxOutputTokens = 512,
                engineKvCapacityTokens = 4_096,
            ),
        )

        assertFalse(finalMessages.isTooLarge)
        assertTrue("Final fit discarded history already selected", selected.messages.all { it in finalMessages.messages })
        assertEquals(512, finalMessages.appliedOutputTokens)
        assertTrue(
            PromptTokenEstimator.estimateTokens(finalMessages.messages) +
                finalMessages.appliedOutputTokens + FinalPromptBudgeter.DEFAULT_SAFETY_MARGIN_TOKENS <= 4_096,
        )
    }

    private fun select(
        history: List<ChatMessage>,
        modelId: String = "Gemma E2B",
        maxOutputTokens: Int = 512,
        attachmentCount: Int = 0,
        webSearchPlanned: Boolean = false,
        summary: String? = null,
        engineKvCapacityTokens: Int? = null,
    ) = PromptHistoryBudgeter.select(
        PromptHistoryBudgetRequest(
            history = history,
            modelId = modelId,
            generationModeKey = "LOCAL_MODEL",
            maxOutputTokens = maxOutputTokens,
            currentRequestChars = 128,
            attachmentCount = attachmentCount,
            webSearchPlanned = webSearchPlanned,
            summaryText = summary,
            engineKvCapacityTokens = engineKvCapacityTokens,
        )
    )

    private fun selectExternal(history: List<ChatMessage>, maxOutputTokens: Int) =
        PromptHistoryBudgeter.select(
            PromptHistoryBudgetRequest(
                history = history,
                modelId = null,
                generationModeKey = "EXTERNAL_AI_API",
                maxOutputTokens = maxOutputTokens,
                currentRequestChars = 128,
                attachmentCount = 0,
                webSearchPlanned = false,
            )
        )

    private fun assertWholeTurns(messages: List<ChatMessage>) {
        assertEquals(0, messages.size % 2)
        messages.chunked(2).forEach { turn ->
            assertEquals("user", turn[0].role)
            assertEquals("assistant", turn[1].role)
        }
    }

    private fun message(role: String, content: String) = ChatMessage(role = role, content = content)

    private fun entity(id: Long, role: String, content: String) = MessageEntity(
        id = id,
        conversationId = 1L,
        role = role,
        content = content,
        createdAt = id,
    )
}
