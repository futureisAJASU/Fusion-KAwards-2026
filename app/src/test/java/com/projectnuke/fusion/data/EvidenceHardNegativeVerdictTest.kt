package com.projectnuke.fusion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end hard-negative verdict proof through the REAL deterministic
 * assessor (rules-v7): semantically retrieved near-duplicates must never
 * become SUPPORT, no matter how high their embedding similarity scores.
 */
class EvidenceHardNegativeVerdictTest {

    private val assessor = ConservativeRuleEvidenceAssessor()

    private fun source(text: String, publishedAt: String? = null) = EvidenceSource(
        id = "E1",
        title = text,
        source = "test",
        url = "https://example.com/e1",
        snippet = "",
        publishedAt = publishedAt,
        providerType = "TEST",
        providerDisplayName = "Test",
        queryUsed = "q",
    )

    private fun checkable(text: String) = EvidenceClaimCandidate(
        id = "C1",
        text = text,
        ordinal = 1,
        classification = ClaimClassification.CHECKABLE_FACT,
        priority = ClaimPriority.HIGH,
        reason = ClaimClassificationReason.FACTUAL_DECLARATIVE,
    )

    private fun verdict(claim: String, sourceText: String, publishedAt: String? = null): EvidenceVerdict =
        assessor.assess(checkable(claim), listOf(source(sourceText, publishedAt))).verdict

    // ---- English (§6 required list) ----

    @Test
    fun `12GB RAM vs 12GB storage through the real assessor`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 has 12GB RAM.", "ProductX2 has 12GB storage."),
        )
    }

    @Test
    fun `12 GB RAM vs 12 GB storage spaced`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 has 12 GB RAM.", "ProductX2 has 12 GB storage."),
        )
    }

    @Test
    fun `12GB RAM vs 12GB capacity`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 has 12GB RAM.", "ProductX2 has 12GB capacity."),
        )
    }

    @Test
    fun `12 GB RAM vs bare 12 GB`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 has 12 GB RAM.", "ProductX2 has 12 GB."),
        )
    }

    @Test
    fun `12GB RAM vs 8GB RAM`() {
        assertEquals(
            EvidenceVerdict.CONFLICTING_EVIDENCE,
            verdict("ProductX2 has 12GB RAM.", "ProductX2 has 8GB RAM."),
        )
    }

    @Test
    fun `RAM vs storage different value`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 has 12GB RAM.", "ProductX2 has 8GB storage."),
        )
    }

    @Test
    fun `ProductX2 vs ProductX3`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 supports 12GB.", "ProductX3 supports 12GB."),
        )
    }

    @Test
    fun `supports vs does not support`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 supports 12GB.", "ProductX2 does not support 12GB."),
        )
    }

    @Test
    fun `released vs will release`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("Acme released ProductX2.", "Acme will release ProductX2."),
        )
    }

    @Test
    fun `today vs yesterday`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("Acme released ProductX2 today.", "Acme released ProductX2 yesterday."),
        )
    }

    @Test
    fun `current vs former never supports`() {
        assertTrue(
            verdict("Kim is currently the manager.", "Kim was formerly the manager.") !=
                EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
        )
    }

    // ---- Korean equivalents ----

    @Test
    fun `korean ram vs storage through the real assessor`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2는 12GB 램을 지원한다.", "ProductX2는 12GB 저장공간을 지원한다."),
        )
    }

    @Test
    fun `korean spaced 12 GB 램 vs 12 GB 저장공간`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2는 12 GB 램을 지원한다.", "ProductX2는 12 GB 저장공간을 지원한다."),
        )
    }

    @Test
    fun `korean spaced 12 GB 램 vs 12 GB 용량`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2는 12 GB 램을 지원한다.", "ProductX2는 12 GB 용량을 지원한다."),
        )
    }

    @Test
    fun `korean 12GB vs 8GB`() {
        assertEquals(
            EvidenceVerdict.CONFLICTING_EVIDENCE,
            verdict("ProductX2는 12GB 램을 지원한다.", "ProductX2는 8GB 램을 지원한다."),
        )
    }

    @Test
    fun `korean entity substitution`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2는 12GB를 지원한다.", "ProductX3는 12GB를 지원한다."),
        )
    }

    @Test
    fun `korean negation`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2는 12GB를 지원한다.", "ProductX2는 12GB를 지원하지 않는다."),
        )
    }

    @Test
    fun `korean completed vs planned`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("새 도서관이 개관했다.", "새 도서관이 다음 달에 개관할 예정이다."),
        )
    }

    @Test
    fun `korean today vs yesterday`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("오늘 서울에 비가 내렸다.", "어제 서울에 비가 내렸다."),
        )
    }

    @Test
    fun `korean current vs former never supports`() {
        assertTrue(
            verdict("현행 요금제는 월 5만 원이다.", "구 요금제는 월 5만 원이다.") !=
                EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
        )
    }

    // ---- History / science / math hard negatives stay unsafe ----

    @Test
    fun `same topic different year stays unsafe`() {
        assertTrue(
            verdict("임진왜란은 1592년에 발발했다.", "임진왜란은 1597년에 발발했다.") !=
                EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
        )
    }

    @Test
    fun `same science topic opposite proposition stays unsafe`() {
        assertTrue(
            verdict("물체는 관성에 의해 운동 상태를 유지하려 한다.", "관성은 물체를 멈추게 하는 힘이다.") !=
                EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
        )
    }

    @Test
    fun `triangle vs quadrilateral numeric facts stay unsafe`() {
        assertTrue(
            verdict("삼각형의 내각의 합은 180도이다.", "사각형의 내각의 합은 180도이다.") !=
                EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
        )
    }
}
