package com.projectnuke.fusion.llm

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StableRuntimeOwnerTest {
    @Test
    fun `background unload and return to chat retain one wrapper identity`() {
        val owner = StableRuntimeOwner<FakeRuntimeWrapper>()
        val chat = owner.acquire(::FakeRuntimeWrapper)
        chat.load("model-a")

        owner.unload { it.unload() }
        val returnedChat = owner.acquire(::FakeRuntimeWrapper)

        assertSame(chat, returnedChat)
        assertNull(returnedChat.loadedModel)
        returnedChat.load("model-a")
        assertEquals("model-a", chat.loadedModel)
    }

    @Test
    fun `chat benchmark and A B testing share one wrapper identity`() {
        val owner = StableRuntimeOwner<FakeRuntimeWrapper>()
        val chat = owner.acquire(::FakeRuntimeWrapper)
        owner.unload { it.unload() }
        val benchmark = owner.acquire(::FakeRuntimeWrapper)
        owner.unload { it.unload() }
        val abTesting = owner.acquire(::FakeRuntimeWrapper)

        assertSame(chat, benchmark)
        assertSame(chat, abTesting)
        assertSame(chat, owner.currentIdentity())
    }

    @Test
    fun `model switch unloads native state without replacing wrapper`() {
        val owner = StableRuntimeOwner<FakeRuntimeWrapper>()
        val runtime = owner.acquire(::FakeRuntimeWrapper)
        runtime.load("model-a")
        owner.unload { it.unload() }
        owner.acquire(::FakeRuntimeWrapper).load("model-b")

        assertSame(runtime, owner.currentIdentity())
        assertEquals("model-b", runtime.loadedModel)
        assertEquals(1, runtime.unloadCount)
    }

    @Test
    fun `concurrent acquire and unload cannot publish a second wrapper`() {
        val owner = StableRuntimeOwner<FakeRuntimeWrapper>()
        val creations = AtomicInteger()
        val identities = Collections.synchronizedList(mutableListOf<FakeRuntimeWrapper>())
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)

        try {
            repeat(64) { index ->
                executor.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    if (index % 3 == 0) {
                        owner.unload { it.unload() }
                    }
                    identities += owner.acquire {
                        creations.incrementAndGet()
                        FakeRuntimeWrapper()
                    }
                }
            }
            start.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, creations.get())
        val identity = owner.currentIdentity()
        assertTrue(identities.isNotEmpty())
        assertTrue(identities.all { it === identity })
    }

    @Test
    fun `unload reports whether a wrapper was actually released`() {
        val owner = StableRuntimeOwner<FakeRuntimeWrapper>()
        assertEquals(false, owner.unload { it.unload() })

        val runtime = owner.acquire(::FakeRuntimeWrapper)
        runtime.load("model-a")
        assertEquals(true, owner.unload { it.unload() })
        assertNull(runtime.loadedModel)
    }

    private class FakeRuntimeWrapper {
        var loadedModel: String? = null
            private set
        var unloadCount: Int = 0
            private set

        fun load(model: String) {
            loadedModel = model
        }

        fun unload() {
            loadedModel = null
            unloadCount++
        }
    }
}
