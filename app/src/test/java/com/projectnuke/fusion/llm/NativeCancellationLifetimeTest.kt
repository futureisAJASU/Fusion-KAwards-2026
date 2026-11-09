package com.projectnuke.fusion.llm

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCancellationLifetimeTest {
    @Test
    fun `unregister permits close only after admitted native cancellation finishes`() {
        val controller = NativeConversationCancellationController()
        val key = GenerationRequestKey(3, "request")
        val owner = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val unregisterStarted = CountDownLatch(1)
        val nativeFinished = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(2)
        controller.registerCancellationHook(key, owner) {
            entered.countDown()
            release.await()
            assertFalse(closed.get())
            nativeFinished.set(true)
        }
        try {
            val stop = executor.submit<Boolean> { controller.cancelProcess(key) }
            entered.await()
            val unregister = executor.submit {
                unregisterStarted.countDown()
                controller.unregisterCancellationHook(key, owner)
                assertTrue("native call must settle before Conversation.close", nativeFinished.get())
                closed.set(true)
            }
            unregisterStarted.await()
            release.countDown()
            assertTrue(stop.get())
            unregister.get()
            assertTrue(closed.get())
            assertFalse(controller.cancelProcess(key))
            assertEquals(0, controller.registeredRequestCount())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `stop that observed an unregistered conversation cannot cancel it after close`() {
        val controller = NativeConversationCancellationController()
        val key = GenerationRequestKey(1, "request")
        val owner = Any()
        var closed = false
        var calls = 0
        var usedAfterClose = false
        controller.registerCancellationHook(key, owner) {
            calls++
            usedAfterClose = closed
        }
        controller.onBeforeCancellationLock = {
            controller.unregisterCancellationHook(key, owner)
            closed = true // Models the enclosing Conversation.use finally.
        }

        assertFalse(controller.cancelProcess(key))
        assertFalse(usedAfterClose)
        assertEquals(0, calls)
        assertEquals(0, controller.registeredRequestCount())
    }

    @Test
    fun `replacement entry does not make an observed retired owner cancellable`() {
        val controller = NativeConversationCancellationController()
        val key = GenerationRequestKey(2, "request")
        val retired = Any()
        val replacement = Any()
        var retiredCalls = 0
        var replacementCalls = 0
        controller.registerCancellationHook(key, retired) { retiredCalls++ }
        controller.onBeforeCancellationLock = {
            controller.unregisterCancellationHook(key, retired)
            controller.registerCancellationHook(key, replacement) { replacementCalls++ }
        }

        assertFalse(controller.cancelProcess(key))
        assertEquals(0, retiredCalls)
        assertEquals(0, replacementCalls)
        controller.onBeforeCancellationLock = {}
        controller.unregisterCancellationHook(key, retired)
        assertTrue(controller.cancelProcess(key))
        assertEquals(1, replacementCalls)
        controller.unregisterCancellationHook(key, replacement)
        assertEquals(0, controller.registeredRequestCount())
    }
}
