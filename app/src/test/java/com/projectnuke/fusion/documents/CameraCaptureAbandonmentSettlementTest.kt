package com.projectnuke.fusion.documents

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import com.projectnuke.fusion.util.AttachmentStorageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P13-02: abandonment settlement must not disappear with a cancelled scope.
 *
 * `ChatScreen.abandonCameraCapture` used to hand its DURABLE settlement
 * (picker import owner + physical managed copy) to the composition scope:
 *
 *     scope.launch { withContext(NonCancellable) { settle(); discard() } }
 *
 * `withContext(NonCancellable)` only shields work that has already started.
 * `CoroutineScope.launch` on a scope whose Job is ALREADY cancelled creates a
 * coroutine that is cancelled before its first dispatch, so the body never
 * runs at all: the UI state was cleared synchronously while the durable
 * `PICKER_OPEN` import ownership and the pending-copy registration were never
 * settled.
 *
 * The first test establishes that root cause deterministically (no sleeps);
 * the rest verify [CameraCaptureAbandonmentOwner], the repair.
 */
class CameraCaptureAbandonmentSettlementTest {

    @After
    fun tearDown() {
        CameraCaptureAbandonmentOwner.resetForTest()
    }

    @Test
    fun `a launch on an already cancelled scope never runs its body`() = runBlocking {
        // Root cause, proved: the OLD shape silently dropped the durable
        // settlement. NonCancellable cannot help work that never started.
        val disposedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        disposedScope.cancel()
        var settlementRan = false

        try {
            disposedScope.launch {
                withContext(NonCancellable) { settlementRan = true }
            }
            // Deterministic flush of the surrounding event loop: no wall clock.
            repeat(8) { withContext(Dispatchers.Unconfined) { yield() } }
        } finally {
            disposedScope.cancel()
        }

        assertFalse(
            "a NonCancellable block cannot run when the enclosing launch never started",
            settlementRan,
        )
    }

    @Test
    fun `abandonment settlement still runs when the requesting scope is already cancelled`() = runBlocking {
        // The requesting composition scope is disposed; the durable
        // settlement must still be delivered.
        val disposedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        disposedScope.cancel()
        val settled = CompletableDeferred<Unit>()

        try {
            // Proves the caller itself is dead: this body never executes.
            disposedScope.launch {
                settled.complete(Unit)
            }
            repeat(4) { withContext(Dispatchers.Unconfined) { yield() } }
            assertFalse("the requesting scope is dead", settled.isCompleted)

            // What abandonCameraCapture now does: hand the durable settlement
            // to the process-lifetime owner instead of the dead scope.
            CameraCaptureAbandonmentOwner.settleOnce("1|token-a|/tmp/a.jpg") {
                settled.complete(Unit)
            }
            CameraCaptureAbandonmentOwner.awaitOutstanding()
        } finally {
            disposedScope.cancel()
        }

        assertTrue(
            "durable abandonment settlement must not disappear with the requesting scope",
            settled.isCompleted,
        )
    }

    @Test
    fun `overlapping abandons coalesce while a completed settlement can be retried`() = runBlocking {
        var settleCount = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val key = "7|token-repeat|/tmp/repeat.jpg"

        CameraCaptureAbandonmentOwner.settleOnce(key) {
            settleCount += 1
            entered.complete(Unit)
            release.await()
        }
        entered.await()
        repeat(5) {
            CameraCaptureAbandonmentOwner.settleOnce(key) { settleCount += 1 }
        }
        assertEquals("concurrent duplicate cleanup jobs must be coalesced", 1, settleCount)

        release.complete(Unit)
        CameraCaptureAbandonmentOwner.awaitOutstanding()
        CameraCaptureAbandonmentOwner.settleOnce(key) { settleCount += 1 }
        CameraCaptureAbandonmentOwner.awaitOutstanding()

        assertEquals("a completed settlement can be scheduled again for idempotent recovery", 2, settleCount)
    }

    @Test
    fun `an interrupted settle leaves a recoverable durable state`() = runBlocking {
        val root = Files.createTempDirectory("camera-abandon-recovery").toFile()
        val captured = root.resolve("camera_ocr.jpg").apply { writeBytes(ByteArray(16)) }
        val canonical = captured.canonicalPath
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getExternalFilesDir(type: String?): File = root
            override fun getFilesDir(): File = root
        }
        val controller = CameraKnowledgeCaptureController(
            context = context,
            recognizeImage = { _, _ -> throw UnsupportedOperationException("unused") },
            isDurablyReferenced = { false },
        )
        val key = "0|token-settle|$canonical"
        try {
            AttachmentStorageManager.registerPendingAttachment(captured)

            CameraCaptureAbandonmentOwner.settleOnce(key) {
                controller.discard(canonical)
            }
            CameraCaptureAbandonmentOwner.awaitOutstanding()

            assertFalse("the abandoned physical copy must be released", captured.exists())
            assertFalse(
                "pending-copy ownership must be settled, not leaked",
                AttachmentStorageManager.isPendingAttachmentRegistered(canonical),
            )
            // A retry of the very same settlement is idempotent: it neither
            // fails nor resurrects state, so an interrupted job recovers.
            assertFalse("a repeated discard must be a no-op", controller.discard(canonical))
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(canonical)
            root.deleteRecursively()
            CameraCaptureAbandonmentOwner.resetForTest()
        }
    }

    @Test
    fun `an in-flight settlement is observable`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val key = "3|token-inflight|/tmp/inflight.jpg"

        try {
            CameraCaptureAbandonmentOwner.settleOnce(key) {
                entered.complete(Unit)
                release.await()
            }
            entered.await()
            assertTrue(
                "an in-flight settlement must be observable",
                CameraCaptureAbandonmentOwner.isSettling(key),
            )
            release.complete(Unit)
            CameraCaptureAbandonmentOwner.awaitOutstanding()
            assertFalse(
                "settlement bookkeeping must be released",
                CameraCaptureAbandonmentOwner.isSettling(key),
            )
        } finally {
            release.complete(Unit)
        }
    }
}
