package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE A corrective — exact answer-surface identity and project fence.
 *
 * Presentation state (source reveal, secondary sheet) is keyed by this exact
 * identity — conversationId + assistantMessageId + canonical content hash —
 * NOT by visible text. These tests use the SAME factory the production
 * `remember()` key uses, so they pin the actual key semantics.
 */
class AnswerSurfaceIdentityTest {

    private fun assistant(conversationId: Long, id: Long, content: String) = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = "assistant",
        content = content,
        createdAt = 1L,
    )

    @Test
    fun `same content with a different message id is a different identity`() {
        val a = AnswerSurfaceIdentity.of(1L, assistant(1L, 10L, "동일한 답변"))
        val b = AnswerSurfaceIdentity.of(1L, assistant(1L, 11L, "동일한 답변"))
        assertNotEquals(a, b)
        assertNotEquals(a.assistantMessageId, b.assistantMessageId)
    }

    @Test
    fun `same content and message id in a different conversation is a different identity`() {
        val a = AnswerSurfaceIdentity.of(7L, assistant(7L, 20L, "동일한 답변"))
        val b = AnswerSurfaceIdentity.of(8L, assistant(8L, 20L, "동일한 답변"))
        assertNotEquals(a, b)
        assertNotEquals(a.conversationId, b.conversationId)
    }

    @Test
    fun `the same exact active answer keeps a stable identity`() {
        val first = AnswerSurfaceIdentity.of(1L, assistant(1L, 30L, "안정 답변"))
        val second = AnswerSurfaceIdentity.of(1L, assistant(1L, 30L, "안정 답변"))
        assertEquals(first, second)
        assertEquals(first.contentHash, second.contentHash)
    }

    @Test
    fun `fact verification action requires a persisted stable assistant answer`() {
        val answer = assistant(1L, 31L, "Acme released ProductX2 in 2026.")
        fun eligible(
            message: MessageEntity? = answer,
            visible: Boolean = true,
            streaming: Boolean = false,
            regenerating: Boolean = false,
        ) = canOfferPostAnswerFactVerification(
            message = message,
            visibleAnswerAvailable = visible,
            evidenceStoreAvailable = true,
            verifierAvailable = true,
            translationSurfaceAvailable = true,
            isStreaming = streaming,
            isRegenerating = regenerating,
        )

        assertTrue(eligible())
        assertFalse(eligible(streaming = true))
        assertFalse(eligible(regenerating = true))
        assertFalse(eligible(visible = false))
        assertFalse(eligible(message = answer.copy(role = "user")))
        assertFalse(eligible(message = answer.copy(id = 0L)))
    }

    @Test
    fun `regeneration to a new assistant message id resets even with identical content`() {
        // Regeneration creates a new persisted assistant message id -> identity
        // must differ so presentation state (source reveal, sheet) resets.
        val original = AnswerSurfaceIdentity.of(1L, assistant(1L, 40L, "문자열 그대로"))
        val regenerated = AnswerSurfaceIdentity.of(1L, assistant(1L, 41L, "문자열 그대로"))
        assertNotEquals(original, regenerated)
    }

    @Test
    fun `new chat resets to a non-shared identity`() {
        val newChat = AnswerSurfaceIdentity.of(0L, null)
        assertEquals(0L, newChat.conversationId)
        assertEquals(-1L, newChat.assistantMessageId)
        assertEquals("", newChat.contentHash)
        assertNotEquals(newChat, AnswerSurfaceIdentity.of(0L, assistant(0L, 50L, "x")))
    }

    @Test
    fun `stale identity cannot execute the project action`() {
        val current = AnswerSurfaceIdentity.of(1L, assistant(1L, 60L, "답변"))
        val stale = AnswerSurfaceIdentity.of(1L, assistant(1L, 61L, "답변"))
        assertFalse(
            AnswerSurfaceIdentityFence.canOpenProjectPicker(
                liveIdentity = current,
                expectedIdentity = stale,
                conversationPersisted = true,
            ),
        )
        assertFalse(
            AnswerSurfaceIdentityFence.canOpenProjectPicker(
                liveIdentity = current,
                expectedIdentity = current,
                conversationPersisted = false,
            ),
        )
    }

    @Test
    fun `current identity routes through the existing project picker seam`() {
        val id = AnswerSurfaceIdentity.of(1L, assistant(1L, 70L, "답변"))
        assertTrue(
            AnswerSurfaceIdentityFence.canOpenProjectPicker(
                liveIdentity = id,
                expectedIdentity = id,
                conversationPersisted = true,
            ),
        )
        // The fence never mutates directly; the picker seam is the sole mutation
        // owner (project actions are routed via the existing picker row).
    }

    @Test
    fun `secondary sheet project action rejects stale A to B and accepts current A`() {
        val identityA = AnswerSurfaceIdentity.of(1L, assistant(1L, 80L, "answer A"))
        val identityB = AnswerSurfaceIdentity.of(1L, assistant(1L, 81L, "answer B"))
        var pickerCallbackCount = 0

        // Production freezes this value when the secondary sheet opens.
        val expectedIdentity = identityA
        var currentIdentity = identityB
        if (canExecuteProjectPickerAction(currentIdentity, expectedIdentity, conversationPersisted = true)) {
            pickerCallbackCount++
        }
        assertEquals(0, pickerCallbackCount)

        currentIdentity = identityA
        if (canExecuteProjectPickerAction(currentIdentity, expectedIdentity, conversationPersisted = true)) {
            pickerCallbackCount++
        }
        assertEquals(1, pickerCallbackCount)
    }

    @Test
    fun `secondary sheet compare rejects stale answer or response group`() {
        val answerA = AnswerSurfaceIdentity.of(1L, assistant(1L, 90L, "answer A"))
        val answerB = AnswerSurfaceIdentity.of(1L, assistant(1L, 91L, "answer B"))
        assertFalse(
            AnswerSurfaceIdentityFence.canOpenCompareSurface(
                liveIdentity = answerB,
                expectedIdentity = answerA,
                liveResponseVersionGroupId = 100L,
                expectedResponseVersionGroupId = 100L,
                responseVersionCount = 2,
                liveSnapshotAvailable = true,
            ),
        )
        assertFalse(
            AnswerSurfaceIdentityFence.canOpenCompareSurface(
                liveIdentity = answerA,
                expectedIdentity = answerA,
                liveResponseVersionGroupId = 101L,
                expectedResponseVersionGroupId = 100L,
                responseVersionCount = 2,
                liveSnapshotAvailable = true,
            ),
        )
        assertTrue(
            AnswerSurfaceIdentityFence.canOpenCompareSurface(
                liveIdentity = answerA,
                expectedIdentity = answerA,
                liveResponseVersionGroupId = 100L,
                expectedResponseVersionGroupId = 100L,
                responseVersionCount = 2,
                liveSnapshotAvailable = true,
            ),
        )
    }
}
