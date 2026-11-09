package com.projectnuke.fusion.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustRoutingPresentationTest {
    private fun decision(route: TrustRoute, reason: TrustRoutingReason) = TrustRoutingDecision(
        route = route,
        reason = reason,
        signals = emptySet(),
    )

    @Test
    fun `local route is quiet in both generation modes`() {
        val local = TrustRoutingPresentationMapper.map(
            decision(TrustRoute.LOCAL, TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE),
            generationModeKey = "LOCAL_MODEL",
        )
        val external = TrustRoutingPresentationMapper.map(
            decision(TrustRoute.LOCAL, TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE),
            generationModeKey = "EXTERNAL_AI_API",
        )

        assertEquals(TrustRoutingPresentationKind.NONE, local.kind)
        assertFalse(local.isVisible)
        assertEquals(TrustRoutingPresentationKind.NONE, external.kind)
        assertFalse(external.isVisible)
    }

    @Test
    fun `search reasons become bounded explanations without verification claims`() {
        val explicit = TrustRoutingPresentationMapper.map(
            decision(TrustRoute.SEARCH, TrustRoutingReason.EXPLICIT_WEB_REQUEST),
        )
        val manual = TrustRoutingPresentationMapper.map(
            decision(TrustRoute.SEARCH, TrustRoutingReason.MANUAL_SEARCH_ENABLED),
        )
        val current = TrustRoutingPresentationMapper.map(
            decision(TrustRoute.SEARCH, TrustRoutingReason.CURRENT_OR_RECENT),
        )
        val noUsableResults = TrustRoutingPresentationMapper.map(
            decision(TrustRoute.SEARCH, TrustRoutingReason.SPECIFIC_MODERN_FACT),
        )

        assertEquals(TrustRoutingPresentationKind.WEB_CHECK, explicit.kind)
        assertEquals("🌐 웹 확인", explicit.label)
        assertEquals("요청에 따라 웹 검색 경로를 사용했어요.", explicit.explanation)
        assertEquals("검색 옵션이 켜져 있어 웹 검색 경로를 사용했어요.", manual.explanation)
        assertEquals("최신 정보가 필요할 수 있어 웹 확인 경로를 선택했어요.", current.explanation)
        assertTrue(noUsableResults.explanation.orEmpty().contains("웹 검색 경로를 선택했어요"))

        listOf(explicit, manual, current, noUsableResults).forEach { presentation ->
            val copy = "${presentation.label} ${presentation.explanation}"
            assertFalse(copy.contains("검증 완료"))
            assertFalse(copy.contains("확인됨"))
            assertFalse(copy.contains("confidence", ignoreCase = true))
        }
    }

    @Test
    fun `future unknowable route has deterministic assistant response`() {
        val presentation = TrustRoutingPresentationMapper.map(
            decision(TrustRoute.ABSTAIN, TrustRoutingReason.FUTURE_UNKNOWABLE),
        )

        assertEquals(TrustRoutingPresentationKind.ABSTAIN, presentation.kind)
        assertTrue(presentation.isVisible)
        assertNotNull(presentation.assistantResponse)
        assertEquals(
            "정확하게 알 수 없는 미래 정보라 단정해서 답하기 어려워요.\n\n" +
                "예보, 공개 일정, 확률처럼 확인 가능한 정보라면 검색해서 도와드릴 수 있어요.",
            presentation.assistantResponse,
        )
    }

    @Test
    fun `cloud suggestion is visible only for local generation`() {
        val local = TrustRoutingPresentationMapper.map(
            decision(TrustRoute.CLOUD_SUGGEST, TrustRoutingReason.HIGH_COMPLEXITY_OPTIONAL_CLOUD),
            generationModeKey = "LOCAL_MODEL",
        )
        val external = TrustRoutingPresentationMapper.map(
            decision(TrustRoute.CLOUD_SUGGEST, TrustRoutingReason.HIGH_COMPLEXITY_OPTIONAL_CLOUD),
            generationModeKey = "EXTERNAL_AI_API",
        )

        assertTrue(local.isVisible)
        assertEquals(TrustRoutingPresentationKind.CLOUD_SUGGESTION, local.kind)
        assertTrue(local.explanation.orEmpty().contains("현재 설정으로도 계속 처리"))
        assertFalse(external.isVisible)
        assertFalse(external.label.orEmpty().contains("클라우드"))
    }
}
