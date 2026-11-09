package com.projectnuke.fusion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rules-v7 quantity-attribute compatibility (memory vs storage).
 *
 * A bare quantity anchor (12GB) does not say WHAT it measures. Support
 * requires compatible attribute families whenever both sides resolve one;
 * a resolved mismatch must be INSUFFICIENT (never SUPPORT, and never a
 * conflict — a different attribute is not the same proposition with a
 * different value).
 *
 * rules-v7: when claim has specific attribute (MEMORY/STORAGE) but source
 * has only generic/unresolved (capacity/용량/bare), result is INSUFFICIENT.
 * Spaced quantity forms (12 GB, 12  GB, 12 gb, 12 Gb) are supported.
 */
class EvidenceAttributeCompatibilityTest {

    private val assessor = ConservativeRuleEvidenceAssessor()

    private fun source(title: String, snippet: String = "") = EvidenceSource(
        id = "E1",
        title = title,
        source = "test",
        url = "https://example.com/e1",
        snippet = snippet,
        publishedAt = null,
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

    private fun verdict(claim: String, sourceText: String): EvidenceVerdict =
        assessor.assess(checkable(claim), listOf(source(sourceText))).verdict

    @Test
    fun `same attribute may support`() {
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("ProductX2 has 12GB RAM.", "ProductX2 has 12GB RAM."),
        )
    }

    @Test
    fun `ram vs memory may support`() {
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("ProductX2 has 12GB RAM.", "ProductX2 has 12GB memory."),
        )
    }

    @Test
    fun `ram vs storage must not support and must not conflict`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 has 12GB RAM.", "ProductX2 has 12GB storage."),
        )
    }

    @Test
    fun `storage vs ram must not support in either direction`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 has 12GB storage.", "ProductX2 has 12GB RAM."),
        )
    }

    @Test
    fun `same attribute different value still conflicts`() {
        assertEquals(
            EvidenceVerdict.CONFLICTING_EVIDENCE,
            verdict("ProductX2 has 12GB RAM.", "ProductX2 has 8GB RAM."),
        )
    }

    @Test
    fun `different attribute different value is insufficient not conflict`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 has 12GB RAM.", "ProductX2 has 8GB storage."),
        )
    }

    @Test
    fun `korean ram vs storage must not support`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2는 12GB RAM을 지원한다.", "ProductX2는 12GB 저장공간을 지원한다."),
        )
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2는 12GB 저장공간을 제공한다.", "ProductX2는 12GB 램을 제공한다."),
        )
    }

    @Test
    fun `korean same attribute may support`() {
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("ProductX2는 12GB 램을 지원한다.", "ProductX2는 12GB 메모리를 지원한다."),
        )
    }

    @Test
    fun `rulesV7 korean ram vs capacity is insufficient`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2는 12GB 램을 지원한다.", "ProductX2는 12GB 용량을 지원한다."),
        )
    }

    @Test
    fun `weights never masquerade as memory`() {
        // "그램" contains the substring "램" but must not resolve MEMORY.
        val verdict = verdict("ProductX2 has 12GB storage.", "ProductX2 weighs 500 grams and has 12GB RAM.")
        assertTrue(verdict != EvidenceVerdict.SUPPORTED_BY_EVIDENCE)
    }

    // ---- rules-v7: ambiguous capacity/generic terms are INSUFFICIENT when claim is specific ----

    @Test
    fun `rulesV7 ram vs capacity is insufficient`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 has 12GB RAM.", "ProductX2 has 12GB capacity."),
        )
    }

    @Test
    fun `rulesV7 ram vs bare quantity is insufficient`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 has 12GB RAM.", "ProductX2 has 12GB."),
        )
    }

    @Test
    fun `rulesV7 storage vs capacity is insufficient`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 has 12GB storage.", "ProductX2 has 12GB capacity."),
        )
    }

    @Test
    fun `rulesV7 storage vs bare quantity is insufficient`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 has 12GB storage.", "ProductX2 has 12GB."),
        )
    }

    // ---- rules-v7: spaced quantity/unit forms ----

    @Test
    fun `spaced 12 GB ram vs 12 GB storage is insufficient`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 has 12 GB RAM.", "ProductX2 has 12 GB storage."),
        )
    }

    @Test
    fun `spaced 12 GB ram vs 12 GB memory may support`() {
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("ProductX2 has 12 GB RAM.", "ProductX2 has 12 GB memory."),
        )
    }

    @Test
    fun `spaced double space 12  GB ram vs 12  GB storage is insufficient`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 has 12  GB RAM.", "ProductX2 has 12  GB storage."),
        )
    }

    @Test
    fun `lowercase gb ram vs gb storage is insufficient`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 has 12 gb RAM.", "ProductX2 has 12 gb storage."),
        )
    }

    @Test
    fun `mixed case gb ram vs Gb storage is insufficient`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2 has 12 gb RAM.", "ProductX2 has 12 Gb storage."),
        )
    }

    // ---- Korean spaced forms ----

    @Test
    fun `korean spaced 12 GB 램 vs 12 GB 저장공간 is insufficient`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2는 12 GB 램을 지원한다.", "ProductX2는 12 GB 저장공간을 지원한다."),
        )
    }

    @Test
    fun `korean spaced 12 GB 램 vs 12 GB 메모리 may support`() {
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            verdict("ProductX2는 12 GB 램을 지원한다.", "ProductX2는 12 GB 메모리를 지원한다."),
        )
    }

    @Test
    fun `korean spaced 12 GB 저장공간 vs 12 GB 램 is insufficient`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2는 12 GB 저장공간을 제공한다.", "ProductX2는 12 GB 램을 제공한다."),
        )
    }

    @Test
    fun `korean spaced 12 GB 램 vs 12 GB 용량 is insufficient`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2는 12 GB 램을 지원한다.", "ProductX2는 12 GB 용량을 지원한다."),
        )
    }

    @Test
    fun `korean spaced 12 GB 저장공간 vs 12 GB 용량 is insufficient`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2는 12 GB 저장공간을 제공한다.", "ProductX2는 12 GB 용량을 제공한다."),
        )
    }

    // ---- Korean bare quantity vs specific attribute ----

    @Test
    fun `korean ram vs bare 12GB is insufficient`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2는 12GB 램을 지원한다.", "ProductX2는 12GB를 지원한다."),
        )
    }

    @Test
    fun `korean storage vs bare 12GB is insufficient`() {
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            verdict("ProductX2는 12GB 저장공간을 제공한다.", "ProductX2는 12GB를 제공한다."),
        )
    }
}
