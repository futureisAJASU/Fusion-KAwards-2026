package com.projectnuke.fusion.documents

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lifecycle-independent settlement owner for camera-capture abandonment.
 *
 * `ChatScreen.abandonCameraCapture` has two halves:
 *
 *  1. the transient Compose UI state (`cameraOutputPath`, `cameraImportToken`,
 *     `cameraAdoptionInFlight`, ...), owned by the composition
 *     `rememberCoroutineScope()`;
 *  2. the DURABLE settlement: the picker import owner
 *     (`ChatViewModel.settleAttachmentImportAfterCallback`) and the physical
 *     managed copy (`CameraKnowledgeCaptureController.discard`).
 *
 * Part 2 used to be launched on the composition scope:
 *
 *     scope.launch { withContext(NonCancellable) { /* settle + discard */ } }
 *
 * `NonCancellable` only shields work that has already started. A `launch` on
 * a CoroutineScope whose Job is ALREADY cancelled starts a coroutine that is
 * cancelled before its first dispatch, so the body never runs at all: the UI
 * state was cleared synchronously while the durable picker owner and the
 * pending-copy registration never settled. The in-memory
 * `ComposerImportOwnership` then stayed `PICKER_OPEN` for the rest of the
 * process lifetime, permanently rejecting every later attachment import for
 * that conversation with `EXISTING_IMPORT`, and the physical capture stayed
 * on disk.
 *
 * Ownership lifetime contract (P14-02 correction — the earlier revision
 * called this state "persisted", which is inaccurate):
 *
 *  - `ComposerImportOwnership` is PROCESS-LOCAL. It lives in the
 *    `ChatDraftStateMachine`'s in-memory map; `PersistentComposerDraftStore`
 *    neither serializes nor restores it (verified: `encode`/`parseStrict`
 *    carry text, attachments, submission token and handoff fields only), so
 *    a process restart clears it and manufactures no false ownership.
 *  - The pending-copy registration (`AttachmentStorageManager`) is likewise
 *    process-local. A restart therefore drops both the logical owner and the
 *    pending claim; the abandoned camera file becomes a plain orphan and is
 *    reclaimed by the existing foreground-resume unreferenced-attachment GC.
 *  - Composition disposal is NOT a restart: it keeps the process (and both
 *    in-memory facts) alive while destroying the scope that used to settle
 *    them — exactly the gap this owner closes.
 *
 * This owner removes that failure mode without introducing an unbounded
 * unstructured scope and without duplicating any existing recovery:
 *
 *  - EXPLICIT OWNERSHIP. One process-wide owner, and the only production seam
 *    that starts abandonment settlement outside a composition scope. It is
 *    not a general "uncancellable" scope: only finite settlement is accepted.
 *  - BOUNDED WORK. Each job is a single bounded settlement (durable owner read
 *    and release inside the reference-mutation gate plus one physical
 *    discard). `CameraKnowledgeCaptureController.discard` is itself bounded:
 *    no LLM generation, no index rebuild, no OCR.
 *  - NO DUPLICATE JOBS. [settleOnce] is keyed by the abandoning session
 *    (conversation + import token + physical path), so a repeated or racing
 *    abandon for the same capture schedules at most one settlement.
 *  - RECOVERY / RETRY. `settleAttachmentImportAfterCallback` returns a typed
 *    retry-required outcome when persistence rejects a matching-owner clear.
 *    The body then stops before physical discard, and this owner retains the
 *    exact key-scoped body as cleanup debt. Foreground resume retries it; each
 *    attempt revalidates the token and reference authority. Failures are
 *    logged, the in-flight key is released, and no retry loop runs in the
 *    background.
 *  - PROCESS DEATH. Both the import owner and the pending registration are
 *    process-local, so a restart clears them without manufacturing ownership.
 *    The only durable residue is the orphan camera file, which the
 *    foreground-resume GC reclaims. This owner deliberately keeps no retry
 *    queue across restarts: there is nothing logical left to retry.
 *  - BOUNDS. One job per capture key, each a single bounded settle plus one
 *    physical discard; cardinality is user-driven (one abandoned capture at
 *    a time), failed jobs release their key, and no job outlives its own
 *    bounded body.
 *  - DETERMINISTIC TESTS. [awaitOutstanding] joins every in-flight job so the
 *    canceled-scope guarantee is verified without a wall-clock sleep.
 */
internal object CameraCaptureAbandonmentOwner {

    private val lock = Any()
    private val inFlight = ConcurrentHashMap<String, Job>()
    private val failed = ConcurrentHashMap<String, suspend () -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Schedules the durable settlement of one abandoned capture exactly once.
     *
     * Safe to call from a composition scope that is already cancelled: the
     * work is handed to [scope] (lifetime = process), so it can never be
     * started-already-cancelled the way `cancelledScope.launch { ... }` is.
     */
    fun settleOnce(key: String, body: suspend () -> Unit) {
        val job = synchronized(lock) {
            inFlight[key]?.let { return }
            val started = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    // P14-02: a throwing settlement must neither kill the
                    // process nor wedge the owner. `scope` has no
                    // CoroutineExceptionHandler, so an uncaught exception here
                    // would reach the thread's uncaught handler (an app crash
                    // on Android) while the file/owner it was settling stays
                    // dirty. Settlement bodies are idempotent and the
                    // authority-complete GC already observes the same durable
                    // facts, so the failure is logged and the job completes:
                    // the key is released and the same capture may be
                    // abandoned again for a later, authority-complete pass.
                    // Cancellation still propagates (the finally below runs).
                    try {
                        body()
                        failed.remove(key)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        // Retain token/path-scoped cleanup debt for an
                        // explicit foreground-resume retry. The body must
                        // revalidate logical and durable ownership on each run.
                        failed[key] = body
                        Log.w(
                            "CameraAbandon",
                            "abandonment settlement failed for $key; " +
                                "retained for the next foreground cleanup pass",
                            failure,
                        )
                    }
                } finally {
                    synchronized(lock) { inFlight.remove(key) }
                }
            }
            inFlight[key] = started
            // Publish the job before allowing its finally block to remove it.
            // With a regular eager launch, a fast IO dispatcher can complete
            // the body before the map assignment and leave a completed job
            // permanently registered under this key.
            started.start()
            started
        }
        job
    }

    /** Retries failed abandonment work on an explicit foreground-resume trigger. */
    fun retryFailedSettlements() {
        failed.toMap().forEach { (key, body) -> settleOnce(key, body) }
    }

    /** True while a settlement for [key] is still in flight. */
    fun isSettling(key: String): Boolean = inFlight.containsKey(key)

    /**
     * Joins every outstanding settlement. Test-only seam: production must
     * never block on cleanup, but abandoned-capture settlement has to be
     * observable from a deterministic test.
     */
    suspend fun awaitOutstanding() {
        repeat(MAX_AWAIT_ROUNDS) {
            val snapshot = synchronized(lock) { inFlight.values.toList() }
            if (snapshot.isEmpty()) return
            snapshot.forEach { it.join() }
        }
    }

    /** Test-only: drop bookkeeping so suites do not observe each other's jobs. */
    fun resetForTest() = synchronized(lock) {
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        failed.clear()
    }

    private const val MAX_AWAIT_ROUNDS = 64
}
