package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.DraftHydrationHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerDraftRecoveryPresentationPolicyTest {
    @Test
    fun `corrupt and oversized health expose an explicit recovery action`() {
        listOf(
            DraftHydrationHealth.UNREADABLE_CORRUPT,
            DraftHydrationHealth.UNREADABLE_OVERSIZED,
        ).forEach { health ->
            val presentation = ComposerDraftRecoveryPresentationPolicy.resolve(health)
            assertEquals(ComposerDraftRecoveryPresentationPolicy.UnreadableMessage, presentation.message)
            assertTrue(presentation.recoveryAvailable)
        }
    }

    @Test
    fun `io failure is informative but never offers destructive recovery`() {
        val presentation = ComposerDraftRecoveryPresentationPolicy.resolve(DraftHydrationHealth.UNREADABLE_IO)
        assertEquals(ComposerDraftRecoveryPresentationPolicy.IoUnavailableMessage, presentation.message)
        assertFalse(presentation.recoveryAvailable)
    }

    @Test
    fun `valid and missing health hide recovery surface`() {
        listOf(DraftHydrationHealth.VALID, DraftHydrationHealth.MISSING).forEach { health ->
            val presentation = ComposerDraftRecoveryPresentationPolicy.resolve(health)
            assertNull(presentation.message)
            assertFalse(presentation.recoveryAvailable)
        }
    }

    @Test
    fun `cancelled confirmation never starts recovery`() {
        assertFalse(
            ComposerDraftRecoveryPresentationPolicy.shouldStartRecovery(
                confirmed = false,
                health = DraftHydrationHealth.UNREADABLE_CORRUPT,
            ),
        )
    }
}
