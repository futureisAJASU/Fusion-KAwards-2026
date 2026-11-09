package com.projectnuke.fusion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * C1.5-A host regression matrix. These tests exercise only the deterministic
 * assessor; semantic retrieval is deliberately not part of the verdict path.
 */
class GeneralEvidenceSemanticsTest {

    private val assessor = ConservativeRuleEvidenceAssessor()

    private fun source(text: String) = EvidenceSource(
        id = "E1",
        title = text,
        source = "test",
        url = "https://example.com/e1",
        snippet = "",
        publishedAt = null,
        providerType = "TEST",
        providerDisplayName = "Test",
        queryUsed = "q",
    )

    private fun verdict(claim: String, evidence: String): EvidenceVerdict {
        val result = assessor.assess(
            EvidenceClaimCandidate(
                id = "C1",
                text = claim,
                ordinal = 1,
                classification = ClaimClassification.CHECKABLE_FACT,
                priority = ClaimPriority.HIGH,
                reason = ClaimClassificationReason.FACTUAL_DECLARATIVE,
            ),
            listOf(source(evidence)),
        )
        return result.verdict
    }

    @Test
    fun `history creation binds person object and year`() {
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("세종은 1443년에 한글을 창제했다.", "1443년 세종에 의해 한글이 만들어졌다."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("세종은 1443년에 한글을 창제했다.", "세종은 1443년에 측우기를 만들었다."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("세종은 1443년에 한글을 창제했다.", "태종은 1443년에 한글을 창제했다."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("세종은 1443년에 한글을 창제했다.", "한글은 1446년에 반포되었다."),
        )
    }

    @Test
    fun `copula preserves specific predicate complement`() {
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("김치는 발효 음식이다.", "김치는 발효 음식이다."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("김치는 발효 음식이다.", "김치는 튀긴 음식이다."),
        )
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("The capital of South Korea is Seoul.", "The capital of South Korea is Seoul."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("The capital of South Korea is Seoul.", "The capital of North Korea is Seoul."),
        )
    }

    @Test
    fun `specific complements do not align on shared head`() {
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("The box contains a red ball.", "The box contains a blue ball."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("The bottle contains water.", "The bottle contains oil."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("The bottle contains 2 liters of water.", "The bottle contains 2 liters of oil."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("Alice created a red car.", "Alice created a blue car."),
        )
    }

    @Test
    fun `physics preserves property and rejects opposite force claim`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("외력이 없으면 물체는 운동 상태를 유지한다.", "물체는 관성에 의해 현재의 운동 상태를 계속 유지한다."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("외력이 없으면 물체는 운동 상태를 유지한다.", "외력이 있으면 물체는 운동 상태를 유지한다."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict(
                "If there is no external force, the object maintains its state of motion.",
                "If there is an external force, the object maintains its state of motion.",
            ),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("물체는 관성에 의해 운동 상태를 유지한다.", "관성은 물체를 멈추게 하는 힘이다."),
        )
    }

    @Test
    fun `chemistry formula anchors remain value safe`() {
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("물의 화학식은 H2O이다.", "물의 화학식은 H2O이다."),
        )
        assertEquals(
            EvidenceVerdict.CONFLICTING_EVIDENCE,
            verdict("물의 화학식은 H2O이다.", "물의 화학식은 H2O2이다."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("물의 화학식은 H2O이다.", "과산화수소의 화학식은 H2O2이다."),
        )
    }

    @Test
    fun `biology keeps multi relation paraphrase insufficient`() {
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("적혈구는 산소를 운반한다.", "적혈구는 산소를 운반한다."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("적혈구는 산소를 운반한다.", "적혈구의 헤모글로빈은 산소와 결합해 온몸으로 운반한다."),
        )
    }

    @Test
    fun `earth science does not invent percentage equivalence`() {
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("지구 대기의 대부분은 질소이다.", "지구 대기의 대부분은 질소이다."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("지구 대기의 대부분은 질소이다.", "공기 중 약 78%는 질소이다."),
        )
    }

    @Test
    fun `mathematics equality and comparator direction are bounded`() {
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("삼각형의 내각의 합은 180도이다.", "삼각형의 세 각을 모두 더하면 180도가 된다."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("삼각형의 내각의 합은 180도이다.", "삼각형의 내각의 합은 360도이다."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("KTX는 버스보다 빠르다.", "버스는 KTX보다 빠르다."),
        )
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("KTX는 버스보다 빠르다.", "버스는 KTX보다 느리다."),
        )
    }

    @Test
    fun `language and social studies remain conservative across paraphrase gaps`() {
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("김치는 발효 음식이다.", "김치는 발효 음식이다."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("김치는 발효 음식이다.", "Kimchi is a fermented food."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("국회는 법률을 제정하는 기관이다.", "법원은 법률을 제정하는 기관이다."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("국회는 법률을 제정하는 기관이다.", "국회는 법률을 집행하는 기관이다."),
        )
    }

    @Test
    fun `geography supports location variants but not entity substitution`() {
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("사하라 사막은 아프리카에 있다.", "사하라는 아프리카 대륙에 위치한다."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("사하라 사막은 아프리카에 있다.", "고비 사막은 아프리카에 있다."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("South Korea is in East Asia.", "North Korea is in East Asia."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("ProductX2 supports 12GB.", "ProductX3 supports 12GB."),
        )
    }

    @Test
    fun `everyday causality keeps direction`() {
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("비 때문에 운동회가 연기되었다.", "운동회는 비가 와서 미뤄졌다."),
        )
        assertNotEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("비 때문에 운동회가 연기되었다.", "운동회가 열려서 비가 내렸다."),
        )
    }

    @Test
    fun `bounded morphology normalizes Korean and English variants`() {
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("기관은 법률을 제정한다.", "기관은 법률을 제정하며."),
        )
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("The museum was established in 1900.", "The museum was founded in 1900."),
        )
    }
}
