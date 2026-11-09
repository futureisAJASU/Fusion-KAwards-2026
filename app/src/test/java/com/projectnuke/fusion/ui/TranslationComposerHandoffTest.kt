package com.projectnuke.fusion.ui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationComposerHandoffTest {
    @Test
    fun `null result does not call durable writer`() = runTest {
        var calls = 0

        val result = applyTranslationToComposer(null, 41L) { _, _ ->
            calls++
            true
        }

        assertEquals(TranslationComposerHandoffResult.NoUsableResult, result)
        assertEquals(0, calls)
    }

    @Test
    fun `valid translated result writes exact text to exact conversation`() = runTest {
        var received: Pair<Long, String>? = null

        val result = applyTranslationToComposer("translated result", 42L) { id, text ->
            received = id to text
            true
        }

        assertEquals(TranslationComposerHandoffResult.Success, result)
        assertEquals(42L to "translated result", received)
    }

    @Test
    fun `durable rejection does not report success`() = runTest {
        var called = false

        val result = applyTranslationToComposer("translated", 43L) { _, _ ->
            called = true
            false
        }

        assertTrue(called)
        assertEquals(TranslationComposerHandoffResult.DraftRejected, result)
        assertFalse(result == TranslationComposerHandoffResult.Success)
    }

    @Test
    fun `original source is never substituted for translated result`() = runTest {
        var written = ""

        applyTranslationToComposer("translated only", 44L) { _, text ->
            written = text
            true
        }

        assertEquals("translated only", written)
    }

    @Test
    fun `selection change before durable write rejects without calling writer`() = runTest {
        var calls = 0

        val result = applyTranslationToComposer(
            replacementText = "translated",
            conversationId = 45L,
            replaceDurably = { _, _ ->
                calls++
                true
            },
            canWrite = { false },
        )

        assertEquals(TranslationComposerHandoffResult.DraftRejected, result)
        assertEquals(0, calls)
    }

    @Test
    fun `durable success with unchanged selection navigates to chat`() {
        assertEquals(
            TranslationComposerHandoffPresentation.SuccessTargetStillVisible,
            resolveTranslationComposerHandoffPresentation(
                TranslationComposerHandoffResult.Success,
                targetConversationId = 46L,
                currentConversationId = 46L,
            ),
        )
    }

    @Test
    fun `durable success with changed selection stays truthful`() {
        assertEquals(
            TranslationComposerHandoffPresentation.SuccessTargetChanged,
            resolveTranslationComposerHandoffPresentation(
                TranslationComposerHandoffResult.Success,
                targetConversationId = 47L,
                currentConversationId = 48L,
            ),
        )
        assertFalse(
            resolveTranslationComposerHandoffPresentation(
                TranslationComposerHandoffResult.Success,
                targetConversationId = 47L,
                currentConversationId = 48L,
            ) is TranslationComposerHandoffPresentation.DraftRejected,
        )
    }
}
