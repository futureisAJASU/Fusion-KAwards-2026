package com.projectnuke.fusion.ui

import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.llm.FailureKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateWithLiteRtRecoveryTest {

    @Test
    fun `Cancelled is terminal - no retry, no unload`() {
        val outcome = GenerationOutcome.Cancelled(
            partialText = "partial",
            terminationIntent = com.projectnuke.fusion.llm.GenerationTerminationIntent.USER_STOP
        )
        assertFalse(shouldRetryWithUnload(outcome))
    }

    @Test
    fun `Cancelled with empty partial is terminal`() {
        val outcome = GenerationOutcome.Cancelled(
            partialText = "",
            terminationIntent = com.projectnuke.fusion.llm.GenerationTerminationIntent.USER_STOP
        )
        assertFalse(shouldRetryWithUnload(outcome))
    }

    @Test
    fun `Empty is terminal - no retry`() {
        assertFalse(shouldRetryWithUnload(GenerationOutcome.Empty))
    }

    @Test
    fun `Success is terminal - no retry`() {
        assertFalse(shouldRetryWithUnload(GenerationOutcome.Success(text = "ok", snapshot = null)))
    }

    @Test
    fun `non-load Failure is terminal - no retry`() {
        assertFalse(shouldRetryWithUnload(GenerationOutcome.Failure(
            kind = FailureKind.GENERATION_INTERRUPTED,
            message = "error"
        )))
    }

    @Test
    fun `MODEL_LOAD_FAILED Failure triggers retry`() {
        assertTrue(shouldRetryWithUnload(GenerationOutcome.Failure(
            kind = FailureKind.MODEL_LOAD_FAILED,
            message = "model load failed"
        )))
    }

    @Test
    fun `retry result Cancelled does not trigger retry failed unload`() {
        val retryResult = GenerationOutcome.Cancelled(
            partialText = "partial",
            terminationIntent = com.projectnuke.fusion.llm.GenerationTerminationIntent.USER_STOP
        )
        assertFalse(shouldRetryWithUnload(retryResult))
    }

    @Test
    fun `retry result Empty does not trigger retry failed unload`() {
        assertFalse(shouldRetryWithUnload(GenerationOutcome.Empty))
    }

    @Test
    fun `retry result Success does not trigger retry failed unload`() {
        assertFalse(shouldRetryWithUnload(GenerationOutcome.Success(text = "ok", snapshot = null)))
    }

    @Test
    fun `retry result non-load Failure does not trigger retry failed unload`() {
        assertFalse(shouldRetryWithUnload(GenerationOutcome.Failure(
            kind = FailureKind.GENERATION_INTERRUPTED,
            message = "error"
        )))
    }
}