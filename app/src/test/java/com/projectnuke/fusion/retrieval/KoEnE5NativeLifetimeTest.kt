package com.projectnuke.fusion.retrieval

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure host coverage for the native session/options lifetime contract. */
@OptIn(ExperimentalCoroutinesApi::class)
class KoEnE5NativeLifetimeTest {

    private data class Events(
        val values: MutableList<String> = mutableListOf(),
    ) {
        val options = Any()
        val sessionClosed: Boolean get() = "session" in values
        val optionsClosed: Boolean get() = "options" in values

        fun bundle() = KoEnE5NativeResourceBundle<Unit, Any>(
            session = Unit,
            sessionOptions = options,
            closeSession = { values += "session" },
            closeSessionOptions = { values += "options" },
        )
    }

    private fun failingBundle(
        events: MutableList<String>,
        closeFailure: Throwable,
    ) = KoEnE5NativeResourceBundle<Unit, Any>(
        session = Unit,
        sessionOptions = Any(),
        closeSession = {
            events += "session"
            throw closeFailure
        },
        closeSessionOptions = { events += "options" },
    )

    @Test
    fun `published pair retains options while session lease is live and closes in order`() = runTest {
        val events = Events()
        val lifetime = KoEnE5NativeLifetime<Unit, Any>()
        val bundle = events.bundle()
        assertSame(events.options, bundle.sessionOptions)
        assertTrue(lifetime.publish(bundle))

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val running = async {
            lifetime.withInference {
                entered.complete(Unit)
                release.await()
            }
        }
        runCurrent()
        entered.await()
        lifetime.close()

        assertFalse(events.sessionClosed)
        assertFalse(events.optionsClosed)
        release.complete(Unit)
        running.await()
        assertEquals(listOf("session", "options"), events.values)

        lifetime.close()
        assertEquals(listOf("session", "options"), events.values)
    }

    @Test
    fun `failed session creation closes options without producing a resource pair`() {
        val events = mutableListOf<String>()
        val created = runCatching {
            createKoEnE5NativeResourceBundle(
                sessionOptions = Any(),
                createSession = { throw IllegalStateException("create failed") },
                closeSession = { events += "session" },
                closeSessionOptions = { events += "options" },
            )
        }

        assertTrue(created.isFailure)
        assertEquals(listOf("options"), events)
    }

    @Test
    fun `publication after close rejects and closes candidate exactly once`() {
        val events = Events()
        val lifetime = KoEnE5NativeLifetime<Unit, Any>()
        lifetime.close()

        assertFalse(lifetime.publish(events.bundle()))
        assertEquals(listOf("session", "options"), events.values)
        assertTrue(lifetime.isClosed)
        assertFalse(lifetime.hasLiveResources)
    }

    @Test
    fun `close during active inference fences future inference and defers native close`() = runTest {
        val events = Events()
        val lifetime = KoEnE5NativeLifetime<Unit, Any>()
        assertTrue(lifetime.publish(events.bundle()))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val running = async {
            lifetime.withInference {
                entered.complete(Unit)
                release.await()
            }
        }
        runCurrent()
        entered.await()

        lifetime.close()
        assertFalse(events.sessionClosed)
        val future = async { lifetime.withInference { Unit } }
        runCurrent()
        assertFalse(future.isCompleted)

        release.complete(Unit)
        running.await()
        assertNull(future.await())
        assertEquals(listOf("session", "options"), events.values)
    }

    @Test
    fun `concurrent inference is serialized and second call proceeds after first`() = runTest {
        val events = Events()
        val lifetime = KoEnE5NativeLifetime<Unit, Any>()
        assertTrue(lifetime.publish(events.bundle()))
        val firstEntered = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = async {
            lifetime.withInference {
                firstEntered.complete(Unit)
                firstRelease.await()
            }
        }
        runCurrent()
        firstEntered.await()
        val second = async {
            lifetime.withInference {
                secondEntered.complete(Unit)
            }
        }
        runCurrent()
        assertFalse(secondEntered.isCompleted)

        firstRelease.complete(Unit)
        first.await()
        second.await()
        assertTrue(secondEntered.isCompleted)
        lifetime.close()
    }

    @Test
    fun `close while second inference waits prevents second native entry`() = runTest {
        val events = Events()
        val lifetime = KoEnE5NativeLifetime<Unit, Any>()
        assertTrue(lifetime.publish(events.bundle()))
        val firstEntered = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = async {
            lifetime.withInference {
                firstEntered.complete(Unit)
                firstRelease.await()
            }
        }
        runCurrent()
        firstEntered.await()
        val second = async {
            lifetime.withInference {
                secondEntered.complete(Unit)
            }
        }
        runCurrent()
        lifetime.close()
        assertFalse(events.sessionClosed)

        firstRelease.complete(Unit)
        first.await()
        assertNull(second.await())
        assertFalse(secondEntered.isCompleted)
        assertEquals(listOf("session", "options"), events.values)
    }

    @Test
    fun `waiting cancellation does not consume lease and active cancellation releases it`() = runTest {
        val events = Events()
        val lifetime = KoEnE5NativeLifetime<Unit, Any>()
        assertTrue(lifetime.publish(events.bundle()))
        val firstEntered = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = async {
            lifetime.withInference {
                firstEntered.complete(Unit)
                firstRelease.await()
            }
        }
        runCurrent()
        firstEntered.await()
        val waiting = async {
            lifetime.withInference {
                secondEntered.complete(Unit)
            }
        }
        runCurrent()
        waiting.cancelAndJoin()
        assertFalse(secondEntered.isCompleted)
        firstRelease.complete(Unit)
        first.await()

        val activeEntered = CompletableDeferred<Unit>()
        val active = async {
            lifetime.withInference {
                activeEntered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        runCurrent()
        activeEntered.await()
        active.cancelAndJoin()

        val afterCancellation = lifetime.withInference { Unit }
        assertNotNull(afterCancellation)
        lifetime.close()
        assertEquals(listOf("session", "options"), events.values)
    }

    @Test
    fun `cancellation remains primary when deferred close fails`() = runTest {
        val events = mutableListOf<String>()
        val closeFailure = IllegalStateException("session close failed")
        val primary = CancellationException("inference cancelled")
        val lifetime = KoEnE5NativeLifetime<Unit, Any>()
        assertTrue(lifetime.publish(failingBundle(events, closeFailure)))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val running = async {
            runCatching {
                lifetime.withInference {
                    entered.complete(Unit)
                    release.await()
                    throw primary
                }
            }
        }
        runCurrent()
        entered.await()
        lifetime.close()
        release.complete(Unit)

        val outcome = running.await()
        assertSame(primary, outcome.exceptionOrNull())
        assertSame(closeFailure, primary.suppressed.single())
        assertEquals(listOf("session", "options"), events)
    }

    @Test
    fun `ordinary inference failure remains primary when deferred close fails`() = runTest {
        val events = mutableListOf<String>()
        val closeFailure = IllegalStateException("session close failed")
        val primary = IllegalArgumentException("inference failed")
        val lifetime = KoEnE5NativeLifetime<Unit, Any>()
        assertTrue(lifetime.publish(failingBundle(events, closeFailure)))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val running = async {
            runCatching {
                lifetime.withInference {
                    entered.complete(Unit)
                    release.await()
                    throw primary
                }
            }
        }
        runCurrent()
        entered.await()
        lifetime.close()
        release.complete(Unit)

        val outcome = running.await()
        assertSame(primary, outcome.exceptionOrNull())
        assertSame(closeFailure, primary.suppressed.single())
        assertEquals(listOf("session", "options"), events)
    }

    @Test
    fun `successful inference propagates deferred close failure`() = runTest {
        val events = mutableListOf<String>()
        val closeFailure = IllegalStateException("session close failed")
        val lifetime = KoEnE5NativeLifetime<Unit, Any>()
        assertTrue(lifetime.publish(failingBundle(events, closeFailure)))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val running = async {
            runCatching {
                lifetime.withInference {
                    entered.complete(Unit)
                    release.await()
                    Unit
                }
            }
        }
        runCurrent()
        entered.await()
        lifetime.close()
        release.complete(Unit)

        val outcome = running.await()
        assertSame(closeFailure, outcome.exceptionOrNull())
        assertEquals(listOf("session", "options"), events)
    }

    @Test
    fun `fatal primary error remains primary when deferred close fails`() = runTest {
        val events = mutableListOf<String>()
        val closeFailure = IllegalStateException("session close failed")
        val primary = AssertionError("fatal inference error")
        val lifetime = KoEnE5NativeLifetime<Unit, Any>()
        assertTrue(lifetime.publish(failingBundle(events, closeFailure)))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val running = async {
            runCatching {
                lifetime.withInference {
                    entered.complete(Unit)
                    release.await()
                    throw primary
                }
            }
        }
        runCurrent()
        entered.await()
        lifetime.close()
        release.complete(Unit)

        val outcome = running.await()
        assertSame(primary, outcome.exceptionOrNull())
        assertSame(closeFailure, primary.suppressed.single())
        assertEquals(listOf("session", "options"), events)
    }

    @Test
    fun `bundle close invokes options after a session close failure only once`() {
        val events = mutableListOf<String>()
        val closeFailure = IllegalStateException("session close failed")
        val bundle = KoEnE5NativeResourceBundle<Unit, Any>(
            session = Unit,
            sessionOptions = Any(),
            closeSession = {
                events += "session-close-attempt"
                throw closeFailure
            },
            closeSessionOptions = { events += "options-close" },
        )

        val first = runCatching { bundle.close() }
        assertSame(closeFailure, first.exceptionOrNull())
        bundle.close()
        assertEquals(listOf("session-close-attempt", "options-close"), events)
    }
}
