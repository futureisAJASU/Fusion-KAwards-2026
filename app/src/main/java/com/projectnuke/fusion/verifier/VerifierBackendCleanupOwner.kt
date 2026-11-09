package com.projectnuke.fusion.verifier

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class VerifierBackendCleanupOwner(
    private val scope: CoroutineScope,
    private val recordFailure: (Throwable) -> Unit,
) {
    fun dispose(close: () -> Unit) {
        scope.launch { closeAndRecord(close) }
    }

    internal fun closeAndRecord(close: () -> Unit) {
        try {
            close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            recordFailure(failure)
            if (failure is Error) throw failure
        }
    }
}
