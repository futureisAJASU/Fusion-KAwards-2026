package com.projectnuke.fusion.llm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCompactionRuntimeOwnershipTest {
    @Test
    fun `manual runtime reservation is visible before branch capture can suspend`() = runBlocking {
        val branchCaptureStarted = CompletableDeferred<Unit>()
        val releaseManual = CompletableDeferred<Unit>()
        val manual = launch(start = CoroutineStart.UNDISPATCHED) {
            FusionRuntimeLock.withChatGeneration {
                assertTrue(FusionRuntimeLock.isChatGenerationRunning)
                branchCaptureStarted.complete(Unit)
                releaseManual.await()
            }
        }

        branchCaptureStarted.await()
        var conflictingOperationEntered = false
        val conflicting = launch {
            FusionRuntimeLock.withChatGeneration {
                conflictingOperationEntered = true
            }
        }
        yield()
        assertFalse(conflictingOperationEntered)

        releaseManual.complete(Unit)
        manual.join()
        conflicting.join()
        assertTrue(conflictingOperationEntered)
    }
}
