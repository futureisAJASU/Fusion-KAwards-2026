package com.projectnuke.fusion.ui

import android.content.Intent
import android.speech.RecognitionListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transactional start ownership for the platform recognizer: a failed start
 * (creation, listener installation, startListening after publication) must
 * roll back ALL partially established ownership — no live session token, no
 * published recognizer, candidate destroyed exactly once — while success,
 * cancel and destroy semantics stay unchanged.
 */
class PlatformRecognizerLifecycleTest {

    private class FakeHandle(
        private val failSetListener: Boolean = false,
        private val failStartListening: Boolean = false,
        private val failCancel: Boolean = false,
    ) : SpeechRecognizerHandle {
        var installedListener: RecognitionListener? = null
            private set
        var startCount = 0
        var stopListeningCount = 0
        var cancelCount = 0
        var destroyCount = 0

        override fun setRecognitionListener(listener: RecognitionListener) {
            installedListener = listener
            if (failSetListener) throw IllegalStateException("listener install failed")
        }

        override fun startListening(intent: Intent) {
            startCount++
            if (failStartListening) throw IllegalStateException("startListening failed")
        }

        override fun stopListening() {
            stopListeningCount++
        }

        override fun cancel() {
            cancelCount++
            if (failCancel) throw IllegalStateException("cancel failed")
        }

        override fun destroy() {
            destroyCount++
        }
    }

    private class RecordingDictationListener : DictationEngineListener {
        val events = mutableListOf<String>()

        override fun onPartial(sessionToken: Long, transcript: String) {
            events += "partial:$sessionToken:$transcript"
        }

        override fun onFinal(sessionToken: Long, transcript: String) {
            events += "final:$sessionToken"
        }

        override fun onEngineError(sessionToken: Long, message: String) {
            events += "error:$sessionToken"
        }
    }

    private fun lifecycle(
        gate: RecognitionSessionGate,
        dictationListener: DictationEngineListener,
        factory: () -> FakeHandle,
    ): PlatformRecognizerLifecycle = PlatformRecognizerLifecycle(
        sessionGate = gate,
        recognizerFactory = factory,
        intentFactory = { languageTag -> recognitionIntent(languageTag) },
        listenerFor = { token -> recognitionListener(token, gate) { dictationListener } },
    )

    // ---- P3.1 recognizer creation failure -----------------------------------

    @Test
    fun `recognizer creation failure returns false and leaves no live session`() {
        val gate = RecognitionSessionGate()
        val dictationListener = RecordingDictationListener()
        var created = 0
        var survivingHandle: FakeHandle? = null
        val lifecycle = lifecycle(
            gate = gate,
            dictationListener = dictationListener,
            factory = {
                created++
                if (created == 1) throw IllegalStateException("service unavailable")
                FakeHandle().also { survivingHandle = it }
            },
        )

        assertFalse(lifecycle.start(1L, "ko-KR"))
        assertEquals(1, created)
        // No candidate resource exists; the failed token never went live:
        // the very next start is a normal session and its callbacks flow.
        assertTrue(lifecycle.start(2L, "ko-KR"))
        val good = survivingHandle!!
        assertEquals(1, good.startCount)
        good.installedListener!!.onResults(null)
        assertEquals(listOf("final:2"), dictationListener.events)
        assertNull(dictationListener.events.firstOrNull { it.endsWith(":1") })
    }

    // ---- P3.2 listener installation failure ---------------------------------

    @Test
    fun `listener installation failure destroys the candidate and invalidates the gate`() {
        val gate = RecognitionSessionGate()
        val dictationListener = RecordingDictationListener()
        val handle = FakeHandle(failSetListener = true)
        val lifecycle = lifecycle(gate, dictationListener) { handle }

        assertFalse(lifecycle.start(1L, "ko-KR"))
        // The candidate resource is destroyed best-effort.
        assertEquals(1, handle.cancelCount)
        assertEquals(1, handle.destroyCount)
        assertEquals(0, handle.startCount)
        // The session token never went live: late callbacks from the
        // installed listener are fenced by the gate.
        handle.installedListener!!.onResults(null)
        handle.installedListener!!.onError(5)
        assertTrue(dictationListener.events.isEmpty())
    }

    // ---- P3.3 startListening throws after ownership publication -------------

    @Test
    fun `startListening failure after publication rolls back all ownership`() {
        val gate = RecognitionSessionGate()
        val dictationListener = RecordingDictationListener()
        val handle = FakeHandle(failStartListening = true)
        val lifecycle = lifecycle(gate, dictationListener) { handle }

        assertFalse(lifecycle.start(1L, "ko-KR"))
        // The candidate was destroyed exactly once (rollback only).
        assertEquals(1, handle.destroyCount)
        assertEquals(1, handle.startCount)
        // The published recognizer was cleared: stop() cannot reach it.
        lifecycle.stop()
        assertEquals(0, handle.stopListeningCount)
        // The session token is invalid: late callbacks are fenced.
        handle.installedListener!!.onResults(null)
        handle.installedListener!!.onError(5)
        handle.installedListener!!.onPartialResults(null)
        assertTrue(dictationListener.events.isEmpty())
    }

    // ---- P3.4 next start succeeds -------------------------------------------

    @Test
    fun `next start after a failed start succeeds and fences the failed token`() {
        val gate = RecognitionSessionGate()
        val dictationListener = RecordingDictationListener()
        val failing = FakeHandle(failStartListening = true)
        val good = FakeHandle()
        var next = 0
        val lifecycle = lifecycle(
            gate = gate,
            dictationListener = dictationListener,
            factory = {
                next++
                if (next == 1) failing else good
            },
        )

        assertFalse(lifecycle.start(1L, "ko-KR"))
        assertTrue(lifecycle.start(2L, "ko-KR"))

        // T1's late callbacks (from the failed candidate) are rejected.
        failing.installedListener!!.onResults(null)
        failing.installedListener!!.onError(5)
        // T2's callbacks are accepted verbatim with the controller's token.
        good.installedListener!!.onResults(null)
        good.installedListener!!.onError(5)

        assertEquals(listOf("final:2", "error:2"), dictationListener.events)
        assertTrue(dictationListener.events.none { it.endsWith(":1") })
    }

    // ---- P3.5 destroy/cancel idempotence after a failed start ----------------

    @Test
    fun `cancel and destroy remain idempotent after a failed start`() {
        val gate = RecognitionSessionGate()
        val dictationListener = RecordingDictationListener()
        val handle = FakeHandle(failStartListening = true)
        val lifecycle = lifecycle(gate, dictationListener) { handle }

        assertFalse(lifecycle.start(1L, "ko-KR"))

        lifecycle.cancel()
        lifecycle.cancel()
        lifecycle.destroy()
        lifecycle.destroy()

        // No double destroy of the already-rolled-back candidate.
        assertEquals(1, handle.destroyCount)
        // No stale token resurrection: the failed token stays dead.
        handle.installedListener!!.onResults(null)
        assertTrue(dictationListener.events.isEmpty())
        // A destroyed lifecycle never starts again.
        assertFalse(lifecycle.start(2L, "ko-KR"))
        assertEquals(1, handle.startCount)
    }

    // ---- P2.1 factory failure after replacing a previous recognizer -----------

    @Test
    fun `factory failure after replacing a previous recognizer leaves no gate token`() {
        val gate = RecognitionSessionGate()
        val dictationListener = RecordingDictationListener()
        val first = FakeHandle()
        val third = FakeHandle()
        var next = 0
        val lifecycle = lifecycle(
            gate = gate,
            dictationListener = dictationListener,
            factory = {
                next++
                when (next) {
                    1 -> first
                    2 -> throw IllegalStateException("service unavailable")
                    else -> third
                }
            },
        )

        assertTrue(lifecycle.start(1L, "ko-KR"))
        assertEquals(1L, gate.current())

        assertFalse(lifecycle.start(2L, "ko-KR"))
        // The superseded handle was released best-effort.
        assertEquals(1, first.cancelCount)
        assertEquals(1, first.destroyCount)
        // No stale token outlives the replaced session.
        assertEquals(0L, gate.current())
        // No recognizer published: stop() reaches nothing.
        lifecycle.stop()
        assertEquals(0, first.stopListeningCount)
        // Late T1 callbacks remain fenced.
        first.installedListener!!.onResults(null)
        assertTrue(dictationListener.events.isEmpty())

        // The next healthy start is a clean session; no stale resurrection.
        assertTrue(lifecycle.start(3L, "ko-KR"))
        assertEquals(3L, gate.current())
        assertEquals(1, third.startCount)
        third.installedListener!!.onResults(null)
        first.installedListener!!.onResults(null)
        assertEquals(listOf("final:3"), dictationListener.events)
    }

    // ---- P2.2 failed-start rollback: cancel throws, destroy still executes ----

    @Test
    fun `failed start rollback destroys candidate even when cancel throws`() {
        val gate = RecognitionSessionGate()
        val dictationListener = RecordingDictationListener()
        val handle = FakeHandle(failStartListening = true, failCancel = true)
        val lifecycle = lifecycle(gate, dictationListener) { handle }

        assertFalse(lifecycle.start(1L, "ko-KR"))
        assertEquals(1, handle.cancelCount)
        // A failing cancel must not suppress the destroy attempt.
        assertEquals(1, handle.destroyCount)
        assertEquals(0L, gate.current())

        // No stale token/callback resurrection on the next healthy start.
        val good = FakeHandle()
        val lifecycle2 = lifecycle(gate, dictationListener) { good }
        assertTrue(lifecycle2.start(2L, "ko-KR"))
        assertEquals(2L, gate.current())
        good.installedListener!!.onResults(null)
        assertEquals(listOf("final:2"), dictationListener.events)
    }

    // ---- P2.3 replacement invalidation: cancel throws, destroy still executes -

    @Test
    fun `replacement invalidation destroys old handle even when its cancel throws`() {
        val gate = RecognitionSessionGate()
        val dictationListener = RecordingDictationListener()
        val first = FakeHandle(failCancel = true)
        val second = FakeHandle()
        var next = 0
        val lifecycle = lifecycle(
            gate = gate,
            dictationListener = dictationListener,
            factory = {
                next++
                if (next == 1) first else second
            },
        )

        assertTrue(lifecycle.start(1L, "ko-KR"))
        // Replacement proceeds even though the old handle's cancel throws;
        // the old handle still receives destroy.
        assertTrue(lifecycle.start(2L, "ko-KR"))
        assertEquals(1, first.cancelCount)
        assertEquals(1, first.destroyCount)
        assertEquals(1, second.startCount)
        assertEquals(2L, gate.current())
        // The old session's callbacks stay fenced.
        first.installedListener!!.onResults(null)
        second.installedListener!!.onResults(null)
        assertEquals(listOf("final:2"), dictationListener.events)
    }

    // ---- P2.4 public destroy: cancel throws, destroy still executes -----------

    @Test
    fun `public destroy destroys handle even when cancel throws`() {
        val gate = RecognitionSessionGate()
        val dictationListener = RecordingDictationListener()
        val handle = FakeHandle(failCancel = true)
        val lifecycle = lifecycle(gate, dictationListener) { handle }

        assertTrue(lifecycle.start(1L, "ko-KR"))
        lifecycle.stop()
        assertEquals(1, handle.stopListeningCount)

        lifecycle.destroy()
        assertEquals(1, handle.cancelCount)
        // A failing cancel must not suppress the destroy attempt.
        assertEquals(1, handle.destroyCount)
        assertEquals(0L, gate.current())
        // Published ownership cleared: stop() can reach no handle.
        lifecycle.stop()
        assertEquals(1, handle.stopListeningCount)
        assertEquals(1, handle.startCount)
    }

    // ---- unchanged success / supersedion semantics ----------------------------

    @Test
    fun `successful start publishes the handle and a later start supersedes it`() {
        val gate = RecognitionSessionGate()
        val dictationListener = RecordingDictationListener()
        val first = FakeHandle()
        val second = FakeHandle()
        var next = 0
        val lifecycle = lifecycle(
            gate = gate,
            dictationListener = dictationListener,
            factory = {
                next++
                if (next == 1) first else second
            },
        )

        assertTrue(lifecycle.start(1L, "ko-KR"))
        assertEquals(1, first.startCount)
        first.installedListener!!.onResults(null)
        assertEquals(listOf("final:1"), dictationListener.events)

        // A new start invalidates the previous recognizer deterministically.
        assertTrue(lifecycle.start(2L, "ko-KR"))
        assertEquals(1, first.cancelCount)
        assertEquals(1, first.destroyCount)
        // The superseded session's callbacks are fenced.
        first.installedListener!!.onResults(null)
        second.installedListener!!.onResults(null)
        assertEquals(listOf("final:1", "final:2"), dictationListener.events)

        // stop() reaches only the live recognizer.
        lifecycle.stop()
        assertEquals(0, first.stopListeningCount)
        assertEquals(1, second.stopListeningCount)

        // cancel() fences the live session; destroy() releases the resource
        // exactly once.
        lifecycle.cancel()
        second.installedListener!!.onResults(null)
        assertEquals(1, second.cancelCount)
        assertEquals(listOf("final:1", "final:2"), dictationListener.events)
        lifecycle.destroy()
        lifecycle.destroy()
        assertEquals(1, second.destroyCount)
        assertFalse(lifecycle.start(3L, "ko-KR"))
    }

    @Test
    fun `zero session token is rejected without touching any resource`() {
        val gate = RecognitionSessionGate()
        val dictationListener = RecordingDictationListener()
        var created = 0
        val lifecycle = lifecycle(
            gate = gate,
            dictationListener = dictationListener,
            factory = { created++; FakeHandle() },
        )

        assertFalse(lifecycle.start(0L, "ko-KR"))
        assertEquals(0, created)
    }
}
