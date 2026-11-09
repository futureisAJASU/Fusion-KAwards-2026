package com.projectnuke.fusion.inbox

import com.projectnuke.fusion.data.InboxItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxTriagePolicyTest {
    @Test
    fun `selection is deduplicated and capped without partial truncation`() {
        val duplicate = item("same")
        assertEquals(listOf(duplicate), InboxTriagePolicy.select(listOf(duplicate, duplicate)))
        assertNull(InboxTriagePolicy.select((0..InboxTriagePolicy.MAX_SELECTION).map { item("$it") }))
        assertNull(InboxTriagePolicy.select(listOf(item(""))))
    }

    @Test
    fun `partial result counts only durable item successes`() {
        val results = listOf(
            settlement("1", InboxTriageOutcome.COMPLETED),
            settlement("2", InboxTriageOutcome.COMPLETED),
            settlement("3", InboxTriageOutcome.COMPLETED),
            settlement("4", InboxTriageOutcome.FAILED),
            settlement("5", InboxTriageOutcome.FAILED),
        )

        val summary = InboxTriagePolicy.summarize(results)

        assertEquals(3, summary.completed)
        assertEquals(2, summary.failed)
        assertEquals(0, summary.missing)
        assertEquals(5, summary.total)
        assertFalse(summary.completed == summary.total)
    }

    @Test
    fun `missing items are accounted separately from failed actions`() {
        val summary = InboxTriagePolicy.summarize(
            listOf(
                settlement("1", InboxTriageOutcome.COMPLETED),
                settlement("2", InboxTriageOutcome.MISSING),
            ),
        )

        assertEquals(1, summary.completed)
        assertEquals(1, summary.missing)
        assertNotNull(summary)
        assertTrue(summary.total == summary.completed + summary.failed + summary.missing)
    }

    private fun settlement(id: String, outcome: InboxTriageOutcome) =
        InboxTriageSettlement(id, id, outcome)

    private fun item(id: String) = InboxItemEntity(
        id = id,
        captureType = "TEXT",
        title = "title",
        content = "content",
        originalUri = null,
        managedFileId = null,
        managedMimeType = null,
        sourceHandoffEventId = null,
        createdAt = 1,
        updatedAt = 1,
        processingState = "READY",
    )
}
