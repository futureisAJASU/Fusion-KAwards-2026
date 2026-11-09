package com.projectnuke.fusion.ui

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One-shot external-event consumption policy for failed handoff/shortcut
 * processing: bounded recoverable failures consume the event after the
 * user-visible outcome; structural cancellation and fatal runtime Errors
 * never consume — the event stays retryable and the broken runtime
 * propagates instead of being discarded as normal completion.
 */
class ExternalEventConsumptionPolicyTest {

    @Test
    fun `bounded recoverable failures consume the one-shot event`() {
        assertTrue(consumeExternalEventAfterFailure(IllegalStateException("bounded")))
        assertTrue(consumeExternalEventAfterFailure(RuntimeException("bounded")))
    }

    @Test
    fun `fatal runtime failures never consume the one-shot event`() {
        assertFalse(consumeExternalEventAfterFailure(LinkageError("broken backend")))
        assertFalse(consumeExternalEventAfterFailure(OutOfMemoryError()))
        assertFalse(consumeExternalEventAfterFailure(InternalError()))
        assertFalse(consumeExternalEventAfterFailure(AssertionError("invariant")))
    }

    @Test
    fun `structural cancellation never consumes the event`() {
        assertFalse(consumeExternalEventAfterFailure(CancellationException("scope disposed")))
    }
}
