package com.projectnuke.fusion.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeHygienePolicyTest {
    private fun candidate(id: String, title: String, text: String, rank: Int = 0) =
        KnowledgeHygienePolicy.CandidateInput(id, title, text, null, null, rank)

    @Test fun `source is excluded and output capped at three`() {
        val duplicate = "같은 저장 정보 연구 결과 날짜 수치 자료 내용"
        val input = (0..8).map { candidate("$it", "연구 결과", duplicate) }
        val result = KnowledgeHygienePolicy.admit("0", "연구 결과", duplicate, null, null, input)
        assertEquals(3, result.size)
        assertFalse(result.any { it.id == "0" })
    }

    @Test fun `candidate pool is bounded before admission`() {
        val source = "같은 단어 자료 중복 저장"
        val pool = (0 until 110).map { candidate("$it", "자료", source) }
        val result = KnowledgeHygienePolicy.admit("source", "자료", source, null, null, pool)
        assertEquals(KnowledgeHygienePolicy.MAX_HYGIENE_RESULTS, result.size)
        assertTrue(result.all { it.id.toInt() < KnowledgeHygienePolicy.MAX_HYGIENE_POOL })
    }

    @Test fun `semantic ordering by itself cannot admit a candidate`() {
        val result = KnowledgeHygienePolicy.admit(
            sourceId = "source", sourceTitle = "지역 도서관 운영 시간", sourceText = "월요일 휴관",
            sourceUrl = null, sourceIdentity = null,
            candidates = listOf(candidate("unrelated", "새 휴대폰 구매", "배터리와 카메라 성능", 0)),
        )
        assertTrue(result.isEmpty())
    }

    @Test fun `strong lexical duplicate and canonical title are admitted`() {
        val result = KnowledgeHygienePolicy.admit(
            sourceId = "source", sourceTitle = "Fusion 연구 일정", sourceText = "지역 모델 성능 결과 자료 비교",
            sourceUrl = null, sourceIdentity = null,
            candidates = listOf(candidate("copy", "Ｆｕｓｉｏｎ 연구 일정", "지역 모델 성능 결과 자료 비교")),
        )
        assertEquals("copy", result.single().id)
        assertEquals("매우 비슷한 내용", result.single().classification)
    }

    @Test fun `related but distinct material remains excluded`() {
        val result = KnowledgeHygienePolicy.admit(
            sourceId = "source", sourceTitle = "프로젝트 일정 관리", sourceText = "마감과 담당자 기록",
            sourceUrl = null, sourceIdentity = null,
            candidates = listOf(candidate("related", "프로젝트 회고", "팀 협업과 진행 상황 평가")),
        )
        assertTrue(result.isEmpty())
    }

    @Test fun `presentation candidate has no similarity or confidence field`() {
        val fields = com.projectnuke.fusion.knowledge.KnowledgeHygieneCandidate::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .map { it.name }.toSet()
        assertEquals(setOf("id", "title", "label"), fields)
        assertFalse(fields.any { it.contains("score", ignoreCase = true) || it.contains("confidence", ignoreCase = true) })
    }
}
