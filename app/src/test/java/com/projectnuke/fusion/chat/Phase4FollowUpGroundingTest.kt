package com.projectnuke.fusion.chat

import com.projectnuke.fusion.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 cross-system adversarial tests: dynamic follow-up semantic
 * ownership and grounding, plus next-action (Action Center) grounding.
 */
class Phase4FollowUpGroundingTest {

    // ── Follow-up premise grounding (§19) ───────────────────────────────────

    @Test
    fun `comparative follow-up naming an entity absent from the answer is rejected`() {
        val question = "삼성전자와 삼성디스플레이 중 어디가 더 큰가요?"
        // The answer only ever mentions Samsung Display. Samsung Electronics is
        // an adjacent entity the evidence never names, so the loaded
        // comparative premise is materially wrong and must not be published.
        val answer = "삼성디스플레이는 패널을 생산한다."
        val kept = DynamicFollowUpPolicy.filterSupported(listOf(question), "", answer)
        assertTrue(
            "materially wrong adjacent entity must fail closed, kept=$kept",
            kept.isEmpty(),
        )
    }

    @Test
    fun `comparative follow-up grounded inside one answer clause is accepted`() {
        val question = "삼성전자와 삼성디스플레이 중 어디가 더 큰가요?"
        val answer = "삼성디스플레이가 삼성전자보다 매출이 크다."
        val kept = DynamicFollowUpPolicy.filterSupported(listOf(question), "", answer)
        assertEquals(listOf(question), kept)
    }

    @Test
    fun `neutral exploratory follow-up stays eligible without answer grounding`() {
        val question = "삼성디스플레이는 무엇인가요?"
        val kept = DynamicFollowUpPolicy.filterSupported(listOf(question), "", "삼성디스플레이는 패널을 생산한다.")
        assertEquals(listOf(question), kept)
    }

    @Test
    fun `causal follow-up scattered across unrelated clauses is rejected`() {
        val question = "eDRAM이 SRAM보다 빠른 이유는?"
        val answer = "eDRAM과 SRAM은 메모리 종류입니다. 캐시는 빠른 메모리를 사용합니다."
        val kept = DynamicFollowUpPolicy.filterSupported(listOf(question), "", answer)
        assertTrue("scattered vocabulary must not establish a premise, kept=$kept", kept.isEmpty())
    }

    @Test
    fun `machine residue blocks the dynamic follow-up request`() {
        val messages = DynamicFollowUpPolicy.buildMessages(
            rawUserRequest = "요약해 줘",
            rawAnswer = "답변\n[FUSION_LOCAL_RAG_SOURCES_JSON]\n[{\"fileName\":\"a.txt\"}]\n[/FUSION_LOCAL_RAG_SOURCES_JSON]",
        )
        assertTrue(messages == null || messages.all { !it.content.contains("FUSION_") })
    }

    @Test
    fun `parseQuestions keeps only bounded question-style lines`() {
        val raw = """
            물론입니다. 아래는 추천 질문입니다.
            1. 이 정책의 적용 범위는?
            2. 이 정책의 적용 범위는?   3. 비용은 얼마인가요?
            - 대답이 아니라 질문만 씁니다
            4. ${"가".repeat(80)}?
        """.trimIndent()
        val parsed = DynamicFollowUpPolicy.parseQuestions(raw)
        assertEquals(2, parsed.size)
        assertTrue(parsed.all { it.length <= DynamicFollowUpPolicy.MAX_QUESTION_CHARS })
    }

    // ── Follow-up ownership (§18) ───────────────────────────────────────────

    private fun ownership(
        conversationId: Long,
        assistantMessageId: Long,
        activeResponseMessageId: Long = assistantMessageId,
        userTurnId: Long = 10L,
    ) = DynamicFollowUpOwnership(
        conversationId = conversationId,
        newChatEpoch = 1L,
        userTurnId = userTurnId,
        assistantMessageId = assistantMessageId,
        versionGroupId = assistantMessageId,
        activeResponseMessageId = activeResponseMessageId,
    )

    @Test
    fun `answer A follow-ups cannot publish under answer B`() {
        val frozenA = ownership(conversationId = 1L, assistantMessageId = 100L)
        val currentB = ownership(conversationId = 1L, assistantMessageId = 200L)
        assertFalse(DynamicFollowUpPolicy.isOwnershipCurrent(frozenA, currentB))
        assertTrue(DynamicFollowUpPolicy.isOwnershipCurrent(frozenA, frozenA))
    }

    @Test
    fun `follow-ups cannot publish after a response-version switch`() {
        val frozen = ownership(conversationId = 1L, assistantMessageId = 100L)
        val switched = frozen.copy(activeResponseMessageId = 101L)
        assertFalse(DynamicFollowUpPolicy.isOwnershipCurrent(frozen, switched))
    }

    @Test
    fun `follow-ups cannot publish after a conversation switch`() {
        val frozen = ownership(conversationId = 1L, assistantMessageId = 100L)
        val switched = frozen.copy(conversationId = 2L)
        assertFalse(DynamicFollowUpPolicy.isOwnershipCurrent(frozen, switched))
    }

    @Test
    fun `unresolved originating user turn blocks follow-up generation`() {
        val active = listOf(
            MessageEntity(id = 100L, conversationId = 1L, role = "assistant", content = "answer", createdAt = 1L),
        )
        assertFalse(
            DynamicFollowUpPolicy.isOriginUserTurnResolved(activeMessages = active, userTurnId = 10L),
        )
        val withUser = active + MessageEntity(
            id = 10L,
            conversationId = 1L,
            role = "user",
            content = "question",
            createdAt = 0L,
        )
        assertTrue(
            DynamicFollowUpPolicy.isOriginUserTurnResolved(activeMessages = withUser, userTurnId = 10L),
        )
    }

    @Test
    fun `composeRow caps the row and removes label and prompt duplicates`() {
        val dynamic = listOf("이 답변의 핵심은?", "출처를 비교해 줘")
        val deterministic = listOf(
            NextActionSuggestion(
                id = "short_summary",
                kind = NextActionKind.SHORT_SUMMARY,
                label = "이 답변의 핵심은?",
                target = NextActionTarget.ComposerPrompt("핵심만 짧게 정리해 줘"),
            ),
            NextActionSuggestion(
                id = "compare_sources",
                kind = NextActionKind.COMPARE_SOURCES,
                label = "출처 비교",
                target = NextActionTarget.ComposerPrompt("출처를 비교해 줘"),
            ),
            NextActionSuggestion(
                id = "counter",
                kind = NextActionKind.COUNTERPOINT,
                label = "반론 검토",
                target = NextActionTarget.ComposerPrompt("반론을 검토해 줘"),
            ),
        )
        val row = DynamicFollowUpPolicy.composeRow(dynamic, deterministic)
        assertTrue(row.size <= NextActionSuggestionPolicy.MAX_SUGGESTIONS)
        assertEquals(NextActionSuggestionPolicy.MAX_SUGGESTIONS, row.size)
        val labels = row.map { it.label }
        assertEquals(labels.size, labels.distinct().size)
        val prompts = row.mapNotNull { it.promptText }
        assertEquals(prompts.size, prompts.distinct().size)
        assertTrue(row.take(2).all { it.kind == NextActionKind.DYNAMIC_FOLLOW_UP })
        assertTrue(row.drop(2).none { it.kind == NextActionKind.DYNAMIC_FOLLOW_UP })
    }

    // ── Action Center grounding (§17) ───────────────────────────────────────

    @Test
    fun `next actions never name an attachment path or attachment identity`() {
        val context = NextActionContext(
            visibleAnswer = "답변 본문",
            webSourceCount = 2,
            localSourceCount = 3,
            localDocumentCount = 2,
            isProjectConversation = true,
            projectFileCount = 4,
        )
        val suggestions = NextActionSuggestionPolicy.suggestions(context)
        assertTrue(suggestions.isNotEmpty())
        suggestions.forEach { suggestion ->
            assertTrue(NextActionSuggestionPolicy.isValidSuggestion(suggestion))
            val texts = listOfNotNull(suggestion.label, suggestion.promptText)
            texts.forEach { text ->
                assertFalse("path-like target leaked: $text", text.contains("/"))
                assertFalse("attachment identity leaked: $text", text.contains("att-"))
            }
        }
    }

    @Test
    fun `research refresh action is only promoted while it is actually available`() {
        val base = ContextualAnswerActionContext(
            availableActions = setOf(AnswerSecondaryAction.COMPARE_RESPONSES),
            isResearchResult = true,
            researchRefreshAvailable = false,
            answerLooksTabular = false,
            hasAnswerReceipt = true,
        )
        assertEquals(
            ContextualAnswerActionSuggestion.COMPARE_RESPONSES,
            ContextualAnswerActionSuggestionPolicy.suggest(base),
        )
        val withRefresh = base.copy(researchRefreshAvailable = true)
        assertEquals(
            ContextualAnswerActionSuggestion.RESEARCH_REFRESH,
            ContextualAnswerActionSuggestionPolicy.suggest(withRefresh),
        )
        // Single ownership: the promoted action is removed from the overflow.
        val remaining = ContextualAnswerActionSuggestionPolicy.remainingSecondaryActions(
            actions = listOf(
                AnswerSecondaryAction.EXTRACT_STRUCTURED,
                AnswerSecondaryAction.COMPARE_RESPONSES,
            ),
            suggestion = ContextualAnswerActionSuggestion.COMPARE_RESPONSES,
        )
        assertEquals(listOf(AnswerSecondaryAction.EXTRACT_STRUCTURED), remaining)
    }
}
