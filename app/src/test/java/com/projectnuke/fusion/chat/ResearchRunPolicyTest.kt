package com.projectnuke.fusion.chat

import com.projectnuke.fusion.search.*
import com.projectnuke.fusion.ui.ResearchEvidenceOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ResearchRunPolicyTest {
    @Test fun researchHasFiniteDeadlineAndRunsCleanup() = runTest {
        var cleaned = false
        assertFalse(ResearchRunPolicy.execute(ChatInteractionMode.RESEARCH,100) { try { awaitCancellation() } finally { cleaned = true } })
        assertTrue(cleaned)
    }
    @Test fun ordinaryModeRemainsAuthoritative() = runTest { var completed=false
        assertTrue(ResearchRunPolicy.execute(ChatInteractionMode.NORMAL,1) { delay(10); completed=true }); assertTrue(completed) }
    @Test fun explicitCancellationIsNotTimeoutSuccess() = runBlocking {
        val cancellation=CancellationException("user stop")
        try { ResearchRunPolicy.execute(ChatInteractionMode.RESEARCH) { throw cancellation }; fail() } catch (seen: CancellationException) { assertSame(cancellation,seen) }
    }
    @Test fun fatalIdentityIsPreserved() = runBlocking { val fatal=AssertionError("fatal")
        try { ResearchRunPolicy.execute(ChatInteractionMode.RESEARCH) { throw fatal }; fail() } catch (seen: AssertionError) { assertSame(fatal,seen) }
    }
    @Test fun instructionsAreBoundedAndDoNotChangeOrdinaryOrStudy() {
        assertEquals("",ResearchRunPolicy.modelInstruction(ChatInteractionMode.NORMAL))
        assertEquals("",ResearchRunPolicy.modelInstruction(ChatInteractionMode.STUDY))
        assertTrue(ResearchRunPolicy.modelInstruction(ChatInteractionMode.RESEARCH).length in 1..2000)
    }
    @Test fun noVerifiedPairAlwaysFailsClosedAndCoverageIsConservative() {
        assertEquals("의미 검증 미실행",ResearchRunPolicy.auditLabel(10,0))
        assertEquals("일부 주장 검증",ResearchRunPolicy.auditLabel(10,2))
        assertEquals("일부 주장 검증",ResearchRunPolicy.auditLabel(1,1))
    }
    @Test fun operationalStagesExposeNoReasoning() { assertEquals("검색 중",ResearchRunPolicy.stage("인터넷 검색 중..."))
        assertEquals("근거 확인 중",ResearchRunPolicy.stage("근거 확인 중")); assertEquals("자료 정리 중",ResearchRunPolicy.stage("검색 결과 정리 중...")) }
    @Test fun sourcesAreBoundedStableAndManualSearchIsUnchanged() {
        val results=(0 until 20).map { FusionSearchResult("source$it","test","https://example.com/$it","text",null) }
        val response=FusionSearchResponse("query","query",SearchIntent.GENERAL,results,null)
        assertSame(response,ResearchSearchOwner.bound(response,ChatInteractionMode.NORMAL))
        val bounded=ResearchSearchOwner.bound(response,ChatInteractionMode.RESEARCH)
        assertEquals(results.take(6),bounded.results); assertEquals(bounded,ResearchSearchOwner.bound(response,ChatInteractionMode.RESEARCH))
    }
    @Test fun modelClaimsNeverBecomeNewAuditWebRequests() {
        val plan=ResearchEvidenceOwner.boundedPlan("서울은 대한민국의 수도입니다. 물은 100도에서 끓습니다. 지구는 태양을 공전합니다.")
        assertTrue(plan.targetedSearchRequests.isEmpty()); assertTrue(plan.claims.size<=4)
    }
    @Test fun researchScopeResetsAndStaleModeOwnerCannotSwitch() {
        val scope=ChatInteractionModeScope(1,0)
        val state=ChatInteractionModePolicy.switch(ChatInteractionModeState(),scope,ChatInteractionMode.RESEARCH,true)
        assertEquals(ChatInteractionMode.RESEARCH,ChatInteractionModePolicy.currentMode(state,scope))
        assertEquals(ChatInteractionMode.NORMAL,ChatInteractionModePolicy.currentMode(state,ChatInteractionModeScope(2,0)))
        assertSame(state,ChatInteractionModePolicy.switch(state,ChatInteractionModeScope(2,0),ChatInteractionMode.STUDY,true))
    }
}
