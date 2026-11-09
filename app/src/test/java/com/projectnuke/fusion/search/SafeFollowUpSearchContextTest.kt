package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Privacy-safe multi-turn search follow-up context (v2): up to 3 SAFE
// prior user turns with the attachment gate BEFORE sanitization, frozen
// per request, exact query authorization preserved.
class SafeFollowUpSearchContextTest {

    private fun attachedRaw(token: String): String =
        AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(AttachmentRecord("secret-plan.pdf", "application/pdf", "/tmp/secret-plan.pdf")),
            "요약해줘 $token",
        )

    // ---- snapshot builder ---------------------------------------------------

    @Test
    fun `max three prior turns enforced newest first`() {
        val raws = listOf("t5 다섯", "t4 넷", "t3 셋", "t2 둘", "t1 하나")
        val safe = buildSafeWebSearchPriorTurns(raws)
        assertEquals(3, safe.size)
        assertEquals(listOf("t5 다섯", "t4 넷", "t3 셋"), safe)
        assertEquals(MAX_SAFE_PRIOR_USER_TURNS, 3)
    }

    @Test
    fun `per-turn char bound enforced`() {
        val long = "가".repeat(300)
        val safe = buildSafeWebSearchPriorTurns(listOf(long))
        assertEquals(1, safe.size)
        assertEquals(MAX_SAFE_PRIOR_TURN_CHARS, 256)
        assertEquals(256, safe.single().length)
    }

    @Test
    fun `attachment-bearing prior turn excluded before sanitization`() {
        val secret = "PRIVATE-EXCERPT-TOKEN-abc123"
        val raws = listOf(
            "최근 소식 알려줘",
            attachedRaw(secret),
            "삼성 HBM4E 수율 알려줘",
        )
        val safe = buildSafeWebSearchPriorTurns(raws)
        assertEquals(2, safe.size)
        assertTrue(safe.none { it.contains(secret) })
        assertTrue(safe.none { it.contains("secret-plan.pdf") })
    }

    @Test
    fun `blank turns skipped`() {
        assertEquals(
            listOf("실제 질문"),
            buildSafeWebSearchPriorTurns(listOf("   ", "", "실제 질문")),
        )
    }

    // ---- planner semantics ---------------------------------------------------

    @Test
    fun `ordinary three-turn follow-up retains topic`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(
                currentUserInput = "그럼 최근 자료 더 찾아줘",
                safePriorUserTurns = listOf(
                    "SK하이닉스 HBM4E 수율은 어떻게 돼?",
                    "삼성 HBM4E 수율 최신 정보 알려줘",
                ),
            ),
        )
        assertTrue(plan.primaryQuery.contains("HBM4E"))
        assertTrue(plan.primaryQuery.contains("수율"))
    }

    @Test
    fun `nearest meaningful prior turn chosen`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(
                currentUserInput = "최근 자료 더 찾아줘",
                safePriorUserTurns = listOf(
                    "SK하이닉스 HBM4E 수율은 어떻게 돼?",
                    "삼성전자 주가 알려줘",
                ),
            ),
        )
        // Nearest first: 하이닉스 topic wins, no concatenation with the
        // older unrelated 삼성전자 topic. (The tokenizer splits
        // "SK하이닉스" into "SK 하이닉스", so assert on 하이닉스.)
        assertTrue(plan.primaryQuery.contains("하이닉스"))
        assertFalse(plan.primaryQuery.contains("삼성전자"))
    }

    @Test
    fun `generic immediate prior walks back to earlier safe topic`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(
                currentUserInput = "그럼 최근 자료 더 찾아줘",
                safePriorUserTurns = listOf(
                    "더 찾아줘",
                    "삼성 HBM4E 수율 최신 정보 알려줘",
                ),
            ),
        )
        assertTrue(plan.primaryQuery.contains("HBM4E"))
    }

    @Test
    fun `unrelated old topics are never concatenated`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(
                currentUserInput = "더 찾아줘",
                safePriorUserTurns = listOf(
                    "오늘 저녁 메뉴 추천해줘",
                    "삼성 HBM4E 수율 알려줘",
                ),
            ),
        )
        val hasOld = plan.primaryQuery.contains("HBM4E")
        val hasNear = plan.primaryQuery.contains("메뉴")
        assertFalse("must not merge unrelated old topics: ${plan.primaryQuery}", hasOld && hasNear)
    }

    @Test
    fun `current explicit input still appears in the query`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(
                currentUserInput = "HBM4E 경쟁사도 찾아줘",
                safePriorUserTurns = listOf("삼성 HBM4E 수율 최신 정보 알려줘"),
            ),
        )
        assertTrue(plan.primaryQuery.contains("경쟁사"))
        assertTrue(plan.primaryQuery.contains("HBM4E"))
    }

    @Test
    fun `assistant prose cannot influence the query`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(
                currentUserInput = "삼성 HBM4E 수율 알려줘",
                previousAssistantMessage = "SECRET-ASSISTANT-TOKEN assistant prose here",
                safePriorUserTurns = emptyList(),
            ),
        )
        assertFalse(plan.primaryQuery.contains("SECRET-ASSISTANT-TOKEN"))
        assertFalse(plan.alternateQueries.any { it.contains("SECRET-ASSISTANT-TOKEN") })
    }

    @Test
    fun `private excerpt and file name never reach authorization`() {
        val secret = "PRIVATE-EXCERPT-TOKEN-xyz789"
        val safe = buildSafeWebSearchPriorTurns(
            listOf("최근 소식 알려줘", attachedRaw(secret)),
        )
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(
                currentUserInput = "최근 소식 알려줘",
                safePriorUserTurns = safe,
            ),
        )
        val approved = SearchQueryAuthorization(
            requestToken = "test",
            approvedQueries = (setOf(plan.primaryQuery) + plan.alternateQueries).filter { it.isNotBlank() }.toSet(),
        )
        assertFalse(plan.primaryQuery.contains(secret))
        assertTrue(plan.alternateQueries.none { it.contains(secret) || it.contains("secret-plan.pdf") })
        try {
            approved.requireApproved("요약해줘 $secret")
            fail("foreign private query must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: exact authorization rejects non-planner material.
        }
    }

    // ---- request-scoped authorization ----------------------------------------

    @Test
    fun `request authorization rejects foreign queries and isolates overlaps`() {
        val authA = SearchQueryAuthorization("token-a", setOf("query A topic"))
        val authB = SearchQueryAuthorization("token-b", setOf("query B topic"))
        assertTrue(authA.allows("query A topic"))
        assertFalse(authA.allows("query B topic"))
        assertFalse(authB.allows("query A topic"))
        try {
            authA.requireApproved("query B topic")
            fail("overlapping search must stay isolated")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    // ---- snapshot freezing ----------------------------------------------------

    @Test
    fun `snapshot freezes safe context with a safe default`() {
        val frozen = listOf("SK하이닉스 HBM4E 수율은 어떻게 돼?", "삼성 HBM4E 수율 최신 정보 알려줘")
        val snapshot = GenerationRequestSnapshot(
            requestId = "r1",
            conversationId = 7L,
            generationModeKey = "LOCAL_MODEL",
            selectedModelId = null,
            selectedModelPath = null,
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "p",
            rawUserText = "그럼 최근 자료 더 찾아줘",
            createdAt = 1L,
            safeWebSearchPriorUserTurns = frozen,
        )
        assertEquals(frozen, snapshot.safeWebSearchPriorUserTurns)
        // Default is empty: legacy call sites stay safe without migration.
        val legacy = GenerationRequestSnapshot(
            requestId = "r0",
            conversationId = 7L,
            generationModeKey = "LOCAL_MODEL",
            selectedModelId = null,
            selectedModelPath = null,
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "p",
            rawUserText = "u",
            createdAt = 1L,
        )
        assertTrue(legacy.safeWebSearchPriorUserTurns.isEmpty())
        // Data-class copy preserves the frozen list for retry branches.
        assertEquals(frozen, snapshot.copy(requestId = "r2").safeWebSearchPriorUserTurns)
    }

    @Test
    fun `frozen safe context is independent per build`() {
        val first = buildSafeWebSearchPriorTurns(listOf("삼성 HBM4E 수율 알려줘"))
        val second = buildSafeWebSearchPriorTurns(listOf("다른 질문 내용입니다"))
        assertEquals(listOf("삼성 HBM4E 수율 알려줘"), first)
        assertEquals(listOf("다른 질문 내용입니다"), second)
    }

    // ---- failure behavior ------------------------------------------------------

    @Test
    fun `planner exception falls back to explicit query safely`() = runTest {
        FusionWebSearch.plannerFailureForTests = IllegalStateException("test planner failure")
        try {
            val response = FusionWebSearch.search(
                userInput = "명시적 질문 내용",
                previousUserMessage = "이전 맥락",
                safePriorUserTurns = listOf("삼성 HBM4E 수율 최신 정보 알려줘"),
            )
            assertTrue(response.results.isEmpty())
            assertEquals("명시적 질문 내용", response.query)
        } finally {
            FusionWebSearch.plannerFailureForTests = null
        }
    }

    @Test
    fun `facade helpers accept frozen context without changing fallbacks`() {
        FusionWebSearch.plannerFailureForTests = IllegalStateException("test planner failure")
        try {
            assertEquals(
                "ordinary prompt",
                FusionWebSearch.rewriteQuery("ordinary prompt", "prev", listOf("older")),
            )
            val plan = FusionWebSearch.buildSearchPlan("ordinary prompt", "prev", listOf("older"))
            assertEquals("ordinary prompt", plan.primaryQuery)
        } finally {
            FusionWebSearch.plannerFailureForTests = null
        }
    }
}
