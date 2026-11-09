package com.projectnuke.fusion.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffDocumentBatchCountsTest {
    @Test
    fun `receipt values preserve legacy failed outcome and distinguish partial success`() {
        assertEquals("FAILED", HandoffImportOutcome.FAILED_IMPORT.receiptValue())
        assertEquals("IMPORTED", HandoffImportOutcome.IMPORTED.receiptValue())
        assertEquals("PARTIALLY_IMPORTED", HandoffImportOutcome.PARTIALLY_IMPORTED.receiptValue())
        assertEquals("REJECTED_TOO_LARGE", HandoffImportOutcome.REJECTED_TOO_LARGE.receiptValue())
        assertEquals("FAILED_FRESH_CHAT", HandoffImportOutcome.FAILED_FRESH_CHAT.receiptValue())
    }

    @Test
    fun `full batch is not partial and counts attempted files exactly`() {
        val counts = HandoffDocumentBatchCounts(
            total = 3,
            attempted = 3,
            imported = 3,
            unsupported = 0,
            failed = 0,
            excess = 0,
        )
        assertFalse(counts.isPartial)
    }

    @Test
    fun `restored twenty URI event with five attempts records fifteen as excess`() {
        val counts = HandoffDocumentBatchCounts(
            total = 20,
            attempted = 5,
            imported = 5,
            unsupported = 0,
            failed = 0,
            excess = 15,
        )

        assertTrue(counts.isPartial)
        assertEquals(20, counts.attempted + counts.excess)
        assertEquals(5, counts.imported + counts.unsupported + counts.failed)
    }

    @Test
    fun `partial summary distinguishes unsupported failed and excess items without content`() {
        val counts = HandoffDocumentBatchCounts(
            total = 8,
            attempted = 5,
            imported = 2,
            unsupported = 1,
            failed = 2,
            excess = 3,
        )
        assertTrue(counts.isPartial)
        assertEquals(
            "문서 8개 중 2개를 Inbox에 저장했어요. 지원하지 않는 문서 1개. 가져오지 못한 문서 2개. 한 번에 최대 5개라 3개는 처리하지 않았어요.",
            counts.userMessage("Inbox"),
        )
        assertFalse(counts.userMessage("Inbox").contains("secret document"))
    }

    @Test
    fun `zero stored documents reports a bounded failure and each skipped reason`() {
        val counts = HandoffDocumentBatchCounts(
            total = 3,
            attempted = 3,
            imported = 0,
            unsupported = 2,
            failed = 1,
            excess = 0,
        )
        assertFalse(counts.isPartial)
        assertEquals(
            "문서 3개를 Project에 저장하지 못했어요. 지원하지 않는 문서 2개. 가져오지 못한 문서 1개.",
            counts.userMessage("Project"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `impossible count combinations are rejected`() {
        HandoffDocumentBatchCounts(
            total = 2,
            attempted = 2,
            imported = 1,
            unsupported = 1,
            failed = 1,
            excess = 0,
        )
    }
}
