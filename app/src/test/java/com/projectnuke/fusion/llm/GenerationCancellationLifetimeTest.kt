package com.projectnuke.fusion.llm

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationCancellationLifetimeTest {
    @Test
    fun `plain job cancellation cannot close before native callback terminal`() = runBlocking {
        val events = mutableListOf<String>()
        val callback = CompletableDeferred<NativeTerminalCallback<String>>()
        val job = launch {
            try {
                collectNativeCallbackUntilTerminal(
                    startNative = { callback.complete(it) },
                    onStarted = { events += "NATIVE_GENERATION_BEGIN" },
                    onMessage = {},
                    onTerminal = { events += "FLOW_TERMINAL" },
                    onConsumerFailure = { events += "NATIVE_CANCEL_REQUESTED" },
                )
            } finally {
                events += "CLOSE_BEGIN"
                events += "CLOSE_END"
            }
        }
        val nativeCallback = withTimeout(1_000) { callback.await() }

        job.cancel(CancellationException("owner disposed"))
        yield()
        assertFalse("native collector must keep the owning Job unsettled", job.isCompleted)
        assertEquals(listOf("NATIVE_GENERATION_BEGIN"), events)

        nativeCallback.onDone()
        job.cancelAndJoin()
        assertEquals(
            listOf("NATIVE_GENERATION_BEGIN", "FLOW_TERMINAL", "CLOSE_BEGIN", "CLOSE_END"),
            events,
        )
    }

    @Test
    fun `consumer failure requests cancellation and still waits for native terminal`() = runBlocking {
        val events = mutableListOf<String>()
        val callback = CompletableDeferred<NativeTerminalCallback<String>>()
        val job = launch {
            try {
                collectNativeCallbackUntilTerminal(
                    startNative = { callback.complete(it) },
                    onStarted = { events += "NATIVE_GENERATION_BEGIN" },
                    onMessage = { throw IllegalStateException("consumer failed") },
                    onTerminal = { events += "FLOW_TERMINAL" },
                    onConsumerFailure = { events += "NATIVE_CANCEL_REQUESTED" },
                )
            } catch (_: IllegalStateException) {
                events += "COLLECTOR_FAILURE_AFTER_TERMINAL"
            } finally {
                events += "CLOSE_BEGIN"
            }
        }
        val nativeCallback = withTimeout(1_000) { callback.await() }
        nativeCallback.onMessage("chunk")
        yield()
        assertFalse(job.isCompleted)
        assertEquals(
            listOf("NATIVE_GENERATION_BEGIN", "NATIVE_CANCEL_REQUESTED"),
            events,
        )

        nativeCallback.onDone()
        job.join()
        assertEquals(
            listOf(
                "NATIVE_GENERATION_BEGIN",
                "NATIVE_CANCEL_REQUESTED",
                "FLOW_TERMINAL",
                "COLLECTOR_FAILURE_AFTER_TERMINAL",
                "CLOSE_BEGIN",
            ),
            events,
        )
    }

    @Test
    fun `retirement before native start prevents send without calling native cancel`() {
        val controller = NativeConversationCancellationController()
        val key = GenerationRequestKey(77L, "pre-start-cancel")
        val owner = Any()
        var nativeStarts = 0
        var nativeCancels = 0
        controller.registerCancellationHook(
            key = key,
            owner = owner,
            requireNativeStartAdmission = true,
        ) {
            nativeCancels++
        }

        assertTrue(controller.reserveIntent(key, GenerationTerminationIntent.SUPERSEDED))
        assertTrue(controller.cancelProcess(key))
        assertFalse(controller.startNativeIfCurrent(key, owner) { nativeStarts++ })
        assertEquals(0, nativeStarts)
        assertEquals("pre-start retirement must not call cancelProcess on an idle Conversation", 0, nativeCancels)
    }

    @Test
    fun `active retirement calls native cancel before coroutine settlement`() {
        val controller = NativeConversationCancellationController()
        val key = GenerationRequestKey(78L, "active-cancel")
        val owner = Any()
        val events = mutableListOf<String>()
        controller.registerCancellationHook(
            key = key,
            owner = owner,
            requireNativeStartAdmission = true,
        ) {
            events += "NATIVE_CANCEL_REQUESTED"
        }
        assertTrue(controller.startNativeIfCurrent(key, owner) { events += "NATIVE_GENERATION_BEGIN" })

        assertTrue(controller.reserveIntent(key, GenerationTerminationIntent.USER_STOP))
        events += "RETIRE_REQUESTED"
        assertTrue(controller.cancelProcess(key))
        events += "JOB_CANCELLED"

        assertEquals(
            listOf("NATIVE_GENERATION_BEGIN", "RETIRE_REQUESTED", "NATIVE_CANCEL_REQUESTED", "JOB_CANCELLED"),
            events,
        )
    }
}
