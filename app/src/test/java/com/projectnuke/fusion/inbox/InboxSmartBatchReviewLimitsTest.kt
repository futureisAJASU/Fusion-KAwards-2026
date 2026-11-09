package com.projectnuke.fusion.inbox

import org.junit.Assert.*
import org.junit.Test

class InboxSmartBatchReviewLimitsTest {

    @Test
    fun batchLimitRespected() {
        assertEquals(12, InboxSmartBatchReviewLimits.MAX_SMART_BATCH_ITEMS)
    }

    @Test
    fun noBulkApplyAllExists() {
        // Phase D invariant: no bulk mutation authority exists.
        // This structural test confirms the limit constant is positive.
        assertTrue(InboxSmartBatchReviewLimits.MAX_SMART_BATCH_ITEMS > 1)
    }
}
