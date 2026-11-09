package com.projectnuke.fusion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerActionTest {

    @Test
    fun `primary action priority is import then submit then stop`() {
        assertEquals(
            ComposerPrimaryAction.ImportProgress,
            resolveComposerPrimaryAction(true, true, true, "hello", true),
        )
        assertEquals(
            ComposerPrimaryAction.SubmitProgress,
            resolveComposerPrimaryAction(true, true, false, "hello", true),
        )
        assertEquals(
            ComposerPrimaryAction.Stop,
            resolveComposerPrimaryAction(true, false, false, "", false),
        )
    }

    @Test
    fun `idle composer resolves voice or send`() {
        assertEquals(
            ComposerPrimaryAction.VoiceActions,
            resolveComposerPrimaryAction(false, false, false, "", false),
        )
        assertEquals(
            ComposerPrimaryAction.Send,
            resolveComposerPrimaryAction(false, false, false, "hello", false),
        )
        assertEquals(
            ComposerPrimaryAction.Send,
            resolveComposerPrimaryAction(false, false, false, "", true),
        )
    }

    @Test
    fun `clearIfMatches clears only the exact owner`() {
        val old = MessageSubmissionOwner(token = "old", sourceConversationId = 1L)
        val current = MessageSubmissionOwner(token = "current", sourceConversationId = 2L)

        assertNull(old.clearIfMatches(old))
        assertEquals(current, current.clearIfMatches(old))
        assertNull((null as MessageSubmissionOwner?).clearIfMatches(old))
    }

    @Test
    fun `reconciliation clears only unchanged raw input`() {
        val unchanged = reconcileCommittedDraft(
            currentInput = "hello  ",
            capturedRawInput = "hello  ",
            pendingAttachments = emptyList(),
            capturedDraftPaths = emptyList(),
        )
        val changed = reconcileCommittedDraft(
            currentInput = "new draft",
            capturedRawInput = "hello",
            pendingAttachments = emptyList(),
            capturedDraftPaths = emptyList(),
        )

        assertTrue(unchanged.shouldClearInput)
        assertFalse(changed.shouldClearInput)
    }

    @Test
    fun `reconciliation removes captured attachment identities only`() {
        val result = reconcileCommittedDraft(
            currentInput = "",
            capturedRawInput = "",
            pendingAttachments = listOf(
                LocalAttachment("a", "text/plain", "/a"),
                LocalAttachment("b", "text/plain", "/b"),
                LocalAttachment("later", "text/plain", "/later"),
            ),
            capturedDraftPaths = listOf("/a", "/b", "/a"),
        )

        assertEquals(listOf("/later"), result.remainingAttachments.map { it.localPath })
        assertEquals(listOf("/a", "/b"), result.committedPaths)
    }

    @Test
    fun `picker capacity enforces five total attachments`() {
        assertEquals(PickerCapacity(5, 5, 3), computePickerCapacity(0, 8))
        assertEquals(PickerCapacity(1, 1, 2), computePickerCapacity(4, 3))
        assertEquals(PickerCapacity(0, 0, 3), computePickerCapacity(5, 3))
    }

    @Test
    fun `external attachment preflight blocks only external attachments`() {
        assertTrue(shouldBlockExternalAttachments(ChatGenerationMode.EXTERNAL_AI_API, true))
        assertFalse(shouldBlockExternalAttachments(ChatGenerationMode.LOCAL_MODEL, true))
        assertFalse(shouldBlockExternalAttachments(ChatGenerationMode.EXTERNAL_AI_API, false))
    }

    @Test
    fun `retry unavailable preflight excludes style regeneration`() {
        assertTrue(shouldBlockRetryForUnavailableAttachments(1, false))
        assertFalse(shouldBlockRetryForUnavailableAttachments(0, false))
        assertFalse(shouldBlockRetryForUnavailableAttachments(1, true))
    }
}
