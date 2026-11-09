package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.ConversationStructuralMutationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseVersionNavigationTest {
    @Test
    fun `directional moves derive each target from durable active state`() {
        val initial = ResponseVersionState(
            groupByMessageId = mapOf(1L to 7L, 2L to 7L, 3L to 7L),
            activeMessageIdByGroup = mapOf(7L to 3L),
        )
        val afterFirst = requireNotNull(
            moveResponseVersionFromDurableState(
                initial, 7L, listOf(1L, 2L, 3L), ResponseVersionDirection.PREVIOUS,
            ),
        )
        assertEquals(2L, afterFirst.activeMessageIdByGroup[7L])
        val afterSecond = requireNotNull(
            moveResponseVersionFromDurableState(
                afterFirst, 7L, listOf(1L, 2L, 3L), ResponseVersionDirection.PREVIOUS,
            ),
        )
        assertEquals(1L, afterSecond.activeMessageIdByGroup[7L])
    }

    @Test
    fun `stale UI membership cannot overwrite a newly persisted version`() {
        val current = ResponseVersionState(
            groupByMessageId = mapOf(1L to 8L, 2L to 8L, 3L to 8L),
            activeMessageIdByGroup = mapOf(8L to 3L),
        )
        assertNull(
            moveResponseVersionFromDurableState(
                current, 8L, listOf(1L, 2L), ResponseVersionDirection.PREVIOUS,
            ),
        )
    }

    @Test
    fun `legacy implicit original navigates backward`() {
        val state = ResponseVersionState(
            groupByMessageId = mapOf(13L to -12L),
            activeMessageIdByGroup = mapOf(-12L to 13L),
        )
        val moved = requireNotNull(
            moveResponseVersionFromDurableState(
                state, -12L, listOf(12L, 13L), ResponseVersionDirection.PREVIOUS,
            ),
        )
        assertEquals(12L, moved.activeMessageIdByGroup[-12L])
    }

    @Test
    fun `legacy implicit original navigates forward`() {
        val state = ResponseVersionState(
            groupByMessageId = mapOf(13L to -12L),
            activeMessageIdByGroup = mapOf(-12L to 12L),
        )
        val moved = requireNotNull(
            moveResponseVersionFromDurableState(
                state, -12L, listOf(12L, 13L), ResponseVersionDirection.NEXT,
            ),
        )
        assertEquals(13L, moved.activeMessageIdByGroup[-12L])
    }

    @Test
    fun `legacy valid membership without active map uses newest member`() {
        val state = ResponseVersionState(
            groupByMessageId = mapOf(13L to -12L),
            activeMessageIdByGroup = emptyMap(),
        )
        val moved = requireNotNull(
            moveResponseVersionFromDurableState(
                state, -12L, listOf(12L, 13L), ResponseVersionDirection.PREVIOUS,
            ),
        )
        assertEquals(12L, moved.activeMessageIdByGroup[-12L])
    }

    @Test
    fun `wrong implicit fallback group is rejected`() {
        val state = ResponseVersionState(
            groupByMessageId = mapOf(13L to -12L),
            activeMessageIdByGroup = mapOf(-12L to 13L),
        )
        assertNull(
            moveResponseVersionFromDurableState(
                state, -13L, listOf(12L, 13L), ResponseVersionDirection.PREVIOUS,
            ),
        )
    }

    @Test
    fun `version lock can be joined under a pre-existing structural lease`() = runTest {
        val lease = requireNotNull(ConversationStructuralMutationRegistry.tryAcquire(1001L))
        val deletion = ConversationStructuralMutationRegistry.beginDeletion(1001L)
        try {
            assertTrue(
                ResponseVersionMutationCoordinator.withVersionLockUnderLease(lease) { true },
            )
        } finally {
            lease.release()
            deletion.awaitQuiescent()
            deletion.finish()
        }
    }

    @Test
    fun `navigation flight accepts one operation per group and releases it`() {
        val flight = ResponseVersionNavigationFlight()
        assertTrue(flight.tryAcquire(9L))
        assertFalse(flight.tryAcquire(9L))
        assertTrue(flight.tryAcquire(10L))
        flight.release(9L)
        assertTrue(flight.tryAcquire(9L))
        flight.release(9L)
        flight.release(10L)
    }
}
