package com.projectnuke.fusion.study

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickReviewCardCaptureControllerTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `reviewed card is written only after explicit acceptance`() = runTest {
        var writes = 0
        var accepted: QuickReviewCardProposal? = null
        val writer = QuickReviewCardWriter { proposal, _ ->
            writes++
            accepted = proposal
            QuickReviewCardWriteResult.Stored
        }
        val controller = QuickReviewCardCaptureController(writer, backgroundScope)
        controller.begin(QuickReviewCardDraft("", "Visible answer"), archiveAccessIncluded = false)
        assertEquals(0, writes)
        controller.updateFront("Reviewed question")
        controller.save(now = 20)
        runCurrent()
        controller.save(now = 20)
        runCurrent()
        assertEquals(1, writes)
        assertTrue(controller.state.value.saved)
        assertEquals("Reviewed question", accepted?.front)
        assertEquals("Visible answer", accepted?.back)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `late result cannot overwrite a newer capture`() = runTest {
        val release = CompletableDeferred<Unit>()
        val writer = QuickReviewCardWriter { _, _ ->
            release.await()
            QuickReviewCardWriteResult.Stored
        }
        val controller = QuickReviewCardCaptureController(writer, backgroundScope)
        controller.begin(QuickReviewCardDraft("Old", "Answer"), false)
        controller.save(now = 10)
        runCurrent()
        controller.begin(QuickReviewCardDraft("New", "Answer"), false)
        release.complete(Unit)
        runCurrent()
        assertEquals("New", controller.state.value.draft?.front)
        assertFalse(controller.state.value.saved)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `write failure remains visible without claiming the card was saved`() = runTest {
        val controller = QuickReviewCardCaptureController(
            QuickReviewCardWriter { _, _ -> QuickReviewCardWriteResult.Failed }, backgroundScope,
        )
        controller.begin(QuickReviewCardDraft("Question", "Answer"), false)
        controller.save(now = 10)
        runCurrent()
        assertFalse(controller.state.value.saved)
        assertEquals("복습 카드를 저장하지 못했습니다.", controller.state.value.error)
    }
}
