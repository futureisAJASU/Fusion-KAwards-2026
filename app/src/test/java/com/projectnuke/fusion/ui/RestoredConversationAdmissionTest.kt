package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ConversationEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RestoredConversationAdmissionTest {
    private fun conversation(isArchived: Boolean) = ConversationEntity(
        id = 42L,
        title = "conversation",
        createdAt = 1L,
        updatedAt = 1L,
        isArchived = isArchived,
    )

    @Test
    fun `active conversation is allowed even when archive lock is enabled`() {
        assertEquals(
            RestoredConversationAdmission.ALLOW,
            evaluateRestoredConversationAdmission(conversation(false), archiveLockEnabled = true),
        )
    }

    @Test
    fun `archived conversation is allowed when archive lock is disabled`() {
        assertEquals(
            RestoredConversationAdmission.ALLOW,
            evaluateRestoredConversationAdmission(conversation(true), archiveLockEnabled = false),
        )
    }

    @Test
    fun `locked archived conversation is rejected`() {
        assertEquals(
            RestoredConversationAdmission.REJECT_LOCKED_ARCHIVED,
            evaluateRestoredConversationAdmission(conversation(true), archiveLockEnabled = true),
        )
    }

    @Test
    fun `missing and unreadable lookups fail closed`() {
        assertEquals(
            RestoredConversationAdmission.REJECT_MISSING,
            evaluateRestoredConversationAdmission(null, archiveLockEnabled = false),
        )
        assertEquals(
            RestoredConversationAdmission.REJECT_UNREADABLE,
            evaluateRestoredConversationAdmission(
                conversation = null,
                archiveLockEnabled = false,
                lookupSucceeded = false,
            ),
        )
    }

    @Test
    fun `restored owner stays hidden until admission settles`() {
        assertFalse(isRestoredConversationSurfaceReady(42L, pendingRestoredConversationId = 42L))
        assertTrue(isRestoredConversationSurfaceReady(0L, pendingRestoredConversationId = 42L))
        assertTrue(isRestoredConversationSurfaceReady(7L, pendingRestoredConversationId = 42L))
        assertTrue(isRestoredConversationSurfaceReady(42L, pendingRestoredConversationId = null))
    }

    // P5.1: an ordinary lookup failure is durable-unreadable evidence, so it
    // maps to REJECT_UNREADABLE.
    @Test
    fun `restored lookup ordinary exception maps to unreadable`() = runTest {
        val admission = restoredConversationAdmissionOrUnreadable(
            conversationLookup = { throw IllegalStateException("room unavailable") },
            archiveLockEnabled = false,
        )
        assertEquals(RestoredConversationAdmission.REJECT_UNREADABLE, admission)
    }

    // P5.2: cancellation must propagate, not become REJECT_UNREADABLE.
    @Test
    fun `restored lookup cancellation propagates`() = runTest {
        try {
            restoredConversationAdmissionOrUnreadable(
                conversationLookup = { throw CancellationException("cancelled") },
                archiveLockEnabled = false,
            )
            fail("expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // expected: cancellation propagates
        }
    }

    // P5.3: a fatal lookup failure must propagate; it must not authorize the
    // New Chat selection fallback via REJECT_UNREADABLE.
    @Test
    fun `restored lookup linkage error propagates`() = runTest {
        try {
            restoredConversationAdmissionOrUnreadable(
                conversationLookup = { throw LinkageError("lookup fatal") },
                archiveLockEnabled = false,
            )
            fail("expected LinkageError to propagate")
        } catch (_: LinkageError) {
            // expected: fatal propagates
        }
    }
}
