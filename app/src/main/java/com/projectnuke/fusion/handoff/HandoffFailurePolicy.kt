package com.projectnuke.fusion.handoff

import kotlinx.coroutines.CancellationException

/**
 * Fatal-error boundary for the external handoff stack.
 *
 * Classification (matches the repo-wide fatal-error policy):
 *  - structural cancellation ([CancellationException]) rethrows so the
 *    one-shot event stays retryable;
 *  - ordinary recoverable operational failures ([RuntimeException]) settle
 *    soft (null / false) into the existing user-visible FAILED_IMPORT /
 *    toast semantics;
 *  - fatal runtime failures (LinkageError, VirtualMachineError, and every
 *    other [Error]) PROPAGATE — they are never converted into an ordinary
 *    FAILED_IMPORT, null metadata, false cleanup, or a consumed event.
 */
internal suspend fun <T> boundedHandoffOutcome(block: suspend () -> T): T? = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: RuntimeException) {
    null
}
