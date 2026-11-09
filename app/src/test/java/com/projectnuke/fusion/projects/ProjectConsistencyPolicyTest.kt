package com.projectnuke.fusion.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectConsistencyPolicyTest {
    @Test fun explicitOpposingWordingIsOnlyLabeledAsPotentialConflict() {
        val issues = ProjectConsistencyPolicy.review(listOf(
            source("saved:a", "HBM 대역폭", "대역폭 확장은 지원됩니다."),
            source("research:b", "HBM 대역폭", "대역폭 확장은 지원하지 않습니다."),
        ))

        assertEquals(1, issues.size)
        assertEquals(ProjectConsistencyIssueKind.POTENTIAL_CONFLICT, issues.single().kind)
        assertTrue(issues.single().statement.contains("오류나 거짓으로 판정하지 않습니다"))
    }

    @Test fun differingTitlesOrUnclearWordingDoNotBecomeContradictions() {
        val issues = ProjectConsistencyPolicy.review(listOf(
            source("saved:a", "HBM 대역폭", "대역폭은 1.2 TB/s입니다."),
            source("saved:b", "HBM 처리량", "대역폭은 2.4 TB/s입니다."),
            source("saved:c", "인터페이스", "추가 자료가 필요합니다."),
            source("saved:d", "인터페이스", "문서가 아직 검토 중입니다."),
        ))

        assertFalse(issues.any { it.kind == ProjectConsistencyIssueKind.POTENTIAL_CONFLICT })
        assertFalse(issues.any { it.kind == ProjectConsistencyIssueKind.DUPLICATE_CONCLUSION })
        assertTrue(issues.isEmpty())
    }

    @Test fun onlyAnExplicitSupersedesReferenceShowsPriorDecision() {
        val old = ProjectConsistencySource(
            id = "decision:old", kind = ProjectConsistencySourceKind.DECISION,
            title = "Cache policy", text = "Cache is enabled", decisionStatus = "SUPERSEDED",
        )
        val current = ProjectConsistencySource(
            id = "decision:current", kind = ProjectConsistencySourceKind.DECISION,
            title = "Cache policy", text = "Cache is disabled", decisionStatus = "ACTIVE",
            supersedesDecisionId = "old",
        )

        val issues = ProjectConsistencyPolicy.review(listOf(old, current))

        assertEquals(listOf(ProjectConsistencyIssueKind.SUPERSEDED_REFERENCE), issues.map { it.kind })
        assertEquals("decision:current", issues.single().sourceA.id)
        assertEquals("decision:old", issues.single().sourceB.id)
    }

    @Test fun duplicateAndResultCountsAreBoundedAndStable() {
        val duplicate = ProjectConsistencyPolicy.review(listOf(
            source("saved:a", "Decision summary", "Current policy is retained for another release."),
            source("saved:b", "Decision summary", "Current policy is retained for another release."),
        ))
        val many = (0 until 80).map { source("s:$it", "Topic $it", "A bounded note that differs for item $it.") }

        assertEquals(ProjectConsistencyIssueKind.DUPLICATE_CONCLUSION, duplicate.single().kind)
        assertTrue(ProjectConsistencyPolicy.review(many).size <= MAX_PROJECT_CONSISTENCY_ISSUES)
        assertTrue(MAX_PROJECT_CONSISTENCY_SOURCES < many.size)
    }

    @Test fun oppositeNumericPolarityNeverReadsAsDuplicate() {
        val issues = ProjectConsistencyPolicy.review(listOf(
            source("saved:a", "설계 허용 온도", "설계 허용 온도는 -50 ℃이며 충분히 긴 본문 내용을 포함합니다. 추가 설명 문장입니다."),
            source("saved:b", "설계 허용 온도", "설계 허용 온도는 50 ℃이며 충분히 긴 본문 내용을 포함합니다. 추가 설명 문장입니다."),
        ))

        assertFalse(issues.any { it.kind == ProjectConsistencyIssueKind.DUPLICATE_CONCLUSION })
    }

    @Test fun explicitPositiveVsNegativeSignNeverReadsAsDuplicate() {
        val issues = ProjectConsistencyPolicy.review(listOf(
            source("saved:a", "전압 오프셋", "오프셋은 +5 V로 설정하며 충분히 긴 본문 내용을 포함합니다. 추가 설명 문장입니다."),
            source("saved:b", "전압 오프셋", "오프셋은 -5 V로 설정하며 충분히 긴 본문 내용을 포함합니다. 추가 설명 문장입니다."),
        ))

        assertFalse(issues.any { it.kind == ProjectConsistencyIssueKind.DUPLICATE_CONCLUSION })
    }

    @Test fun decimalAndComparisonOperatorsStayMeaningful() {
        val decimals = ProjectConsistencyPolicy.review(listOf(
            source("saved:a", "대역폭", "대역폭은 1.2 TB/s이며 충분히 긴 본문 내용을 포함합니다. 추가 설명 문장입니다."),
            source("saved:b", "대역폭", "대역폭은 12 TB/s이며 충분히 긴 본문 내용을 포함합니다. 추가 설명 문장입니다."),
        ))
        assertFalse(decimals.any { it.kind == ProjectConsistencyIssueKind.DUPLICATE_CONCLUSION })

        val comparisons = ProjectConsistencyPolicy.review(listOf(
            source("saved:a", "온도 조건", "동작 조건은 50도 초과이며 충분히 긴 본문 내용을 포함합니다. 추가 설명 문장입니다."),
            source("saved:b", "온도 조건", "동작 조건은 50도 미만이며 충분히 긴 본문 내용을 포함합니다. 추가 설명 문장입니다."),
        ))
        assertFalse(comparisons.any { it.kind == ProjectConsistencyIssueKind.DUPLICATE_CONCLUSION })
    }

    @Test fun excerptEqualButTailDivergentSourcesNeverReadAsDuplicate() {
        // Shared head exceeds the 1,800-char display excerpt, so the excerpt
        // comparison alone would read these as identical.
        val sharedHead = "동일한 서론 문장입니다. ".repeat(150)
        val issues = ProjectConsistencyPolicy.review(listOf(
            source("saved:a", "분기 결론", sharedHead + "최종 결론은 채택입니다."),
            source("saved:b", "분기 결론", sharedHead + "최종 결론은 기각입니다."),
        ))

        assertFalse(issues.any { it.kind == ProjectConsistencyIssueKind.DUPLICATE_CONCLUSION })
    }

    private fun source(id: String, title: String, text: String) = ProjectConsistencySource(
        id = id, kind = if (id.startsWith("research:")) ProjectConsistencySourceKind.RESEARCH
        else ProjectConsistencySourceKind.SAVED_KNOWLEDGE,
        title = title, text = text,
    )
}
