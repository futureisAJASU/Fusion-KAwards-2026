package com.projectnuke.fusion.model

import com.projectnuke.fusion.test.PlatformSharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias InMemoryPrefs = PlatformSharedPreferences

class LocalModelSettingsProfileDurabilityTest {
    @Test
    fun `failed quick profile write reports failure and retains effective setting`() {
        val prefs = InMemoryPrefs()
        val store = LocalModelSettingsProfileStore(prefs)
        assertTrue(store.writeGlobalDefaults(LocalModelSettingsOverride(reasoningEnabled = true)))
        prefs.enqueueCommitResults(false, true)
        assertFalse(store.writeModelReasoning("model-a", false))
        assertTrue(LocalModelSettingsResolver.resolve(
            "model-a", perModelOverride = store.read().modelOverrides["model-a"],
            globalDefaults = store.read().globalDefaults,
        ).reasoningEnabled)
    }

    private fun override(modelId: String = "model-a") = LocalModelSettingsOverride(
        maxOutputTokens = 1024,
        temperature = 0.7f,
    )

    @Test
    fun `each mutation takes a single snapshot read`() {
        val prefs = InMemoryPrefs()
        val store = LocalModelSettingsProfileStore(prefs)
        getStringCallsReset(prefs)
        assertTrue(store.writeModelOverride("model-a", override()))
        assertEquals(1, prefs.getStringCalls.get())
        getStringCallsReset(prefs)
        assertTrue(store.writeGlobalDefaults(override()))
        assertEquals(1, prefs.getStringCalls.get())
        getStringCallsReset(prefs)
        assertTrue(store.deleteModelOverride("model-a"))
        assertEquals(1, prefs.getStringCalls.get())
    }

    private fun getStringCallsReset(prefs: InMemoryPrefs) {
        prefs.getStringCalls.set(0)
    }

    /**
     * Bounded, deadline-based wait until [thread] observes [state]. Never
     * unbounded, and never a blind sleep used as the proof itself: callers
     * assert on the returned result AND on the thread's final state, so a
     * timeout fails the test instead of hanging it.
     */
    private fun awaitThreadState(
        thread: Thread,
        state: Thread.State,
        timeoutSeconds: Long = 10,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (thread.state != state && System.nanoTime() < deadline) {
            Thread.yield()
        }
        return thread.state == state
    }

    @Test
    fun `concurrent model and global writes do not lose each other`() {
        val prefs = InMemoryPrefs()
        val store = LocalModelSettingsProfileStore(prefs)
        val gate = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = BooleanArray(2)
        Thread {
            gate.await(10, TimeUnit.SECONDS)
            results[0] = store.writeModelOverride("model-a", override("model-a"))
            done.countDown()
        }.start()
        Thread {
            gate.await(10, TimeUnit.SECONDS)
            results[1] = store.writeGlobalDefaults(override())
            done.countDown()
        }.start()
        gate.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(results[0])
        assertTrue(results[1])
        val document = store.read()
        assertTrue(document.modelOverrides.containsKey("model-a"))
        assertTrue(document.globalDefaults != null)
    }

    @Test
    fun `corrupt state is quarantined, not silently replaced as empty`() {
        val prefs = InMemoryPrefs()
        prefs.values[LocalModelSettingsProfileStore.KEY] = "{corrupt-json"
        val store = LocalModelSettingsProfileStore(prefs)
        assertTrue(store.load() is SettingsProfileLoad.Corrupt)
        // An unrelated mutation quarantines the evidence, then applies onto a
        // fresh document instead of merging into a phantom empty one.
        assertTrue(store.writeModelOverride("model-a", override()))
        assertEquals("{corrupt-json", prefs.values["local_model_settings_profiles.corrupt.bak"])
        val document = store.read()
        assertEquals(setOf("model-a"), document.modelOverrides.keys)
    }

    @Test
    fun `commit failure returns false and preserves bytes`() {
        val prefs = InMemoryPrefs()
        val store = LocalModelSettingsProfileStore(prefs)
        assertTrue(store.writeModelOverride("model-a", override()))
        val goodBytes = prefs.values[LocalModelSettingsProfileStore.KEY]
        prefs.enqueueCommitResults(false, true)
        assertFalse(store.writeModelOverride("model-b", override("model-b")))
        assertEquals(goodBytes, prefs.values[LocalModelSettingsProfileStore.KEY])
        assertFalse(store.read().modelOverrides.containsKey("model-b"))
    }

    @Test
    fun `simple preset write reports durable failure instead of a success result`() {
        val prefs = InMemoryPrefs()
        val store = LocalModelSettingsProfileStore(prefs)
        assertTrue(store.writeModelOverride("model-a", override()))
        val current = LocalSettingsEditValues(
            settings = GenerationSettings(maxTokens = 1024, temperature = 1f, topK = 64, topP = .95f),
            reasoningEnabled = false,
            contextMode = LocalContextMode.AUTO,
            manualKvCapacityTokens = 4096,
            compactPolicy = LocalCompactPolicy(),
        )
        prefs.enqueueCommitResults(false, true)
        assertFalse(writeSimpleModelOverride(store, "model-a", current) {
            applySimpleStyle(it, SimpleResponseStyle.CAREFUL, null)
        })
        assertEquals(0.7f, store.read().modelOverrides.getValue("model-a").temperature!!, 0f)
    }

    @Test
    fun `typed load distinguishes missing valid and corrupt`() {
        val missing = LocalModelSettingsProfileStore(InMemoryPrefs())
        assertTrue(missing.load() is SettingsProfileLoad.Missing)

        val validPrefs = InMemoryPrefs()
        val validStore = LocalModelSettingsProfileStore(validPrefs)
        assertTrue(validStore.writeModelOverride("model-a", override()))
        assertTrue(validStore.load() is SettingsProfileLoad.Valid)

        val corruptPrefs = InMemoryPrefs()
        corruptPrefs.values[LocalModelSettingsProfileStore.KEY] = """{"version":1,"models":{"m":"not-an-object"}}"""
        assertTrue(LocalModelSettingsProfileStore(corruptPrefs).load() is SettingsProfileLoad.Corrupt)

        val futurePrefs = InMemoryPrefs()
        futurePrefs.values[LocalModelSettingsProfileStore.KEY] = """{"version":999,"models":{}}"""
        assertTrue(LocalModelSettingsProfileStore(futurePrefs).load() is SettingsProfileLoad.Corrupt)
    }

    @Test
    fun `writer-impossible integer inner field is corrupt`() {
        val prefs = InMemoryPrefs()
        prefs.values[LocalModelSettingsProfileStore.KEY] =
            """{"version":1,"models":{"model-a":{"maxOutputTokens":"1024"}}}"""

        assertTrue(LocalModelSettingsProfileStore(prefs).load() is SettingsProfileLoad.Corrupt)
    }

    @Test
    fun `writer-impossible boolean inner field is corrupt`() {
        val prefs = InMemoryPrefs()
        prefs.values[LocalModelSettingsProfileStore.KEY] =
            """{"version":1,"models":{"model-a":{"reasoningEnabled":"true"}}}"""

        assertTrue(LocalModelSettingsProfileStore(prefs).load() is SettingsProfileLoad.Corrupt)
    }

    @Test
    fun `invalid enum inner field is corrupt`() {
        val prefs = InMemoryPrefs()
        prefs.values[LocalModelSettingsProfileStore.KEY] =
            """{"version":1,"models":{"model-a":{"accelerator":"NOT_A_REAL_ACCELERATOR"}}}"""

        assertTrue(LocalModelSettingsProfileStore(prefs).load() is SettingsProfileLoad.Corrupt)
    }

    @Test
    fun `absent legacy inner fields remain compatible`() {
        val prefs = InMemoryPrefs()
        prefs.values[LocalModelSettingsProfileStore.KEY] =
            """{"version":1,"models":{"model-a":{}}}"""

        val loaded = LocalModelSettingsProfileStore(prefs).load()
        assertTrue(loaded is SettingsProfileLoad.Valid)
        assertTrue((loaded as SettingsProfileLoad.Valid).document.modelOverrides.containsKey("model-a"))
    }

    @Test
    fun `malformed inner field is quarantined before mutation and not merged`() {
        val raw = """{"version":1,"models":{"model-a":{"maxOutputTokens":"1024","temperature":0.1}}}"""
        val prefs = InMemoryPrefs()
        prefs.values[LocalModelSettingsProfileStore.KEY] = raw
        val store = LocalModelSettingsProfileStore(prefs)

        assertTrue(store.load() is SettingsProfileLoad.Corrupt)
        assertTrue(store.writeModelOverride("model-b", override("model-b")))
        assertEquals(raw, prefs.values["local_model_settings_profiles.corrupt.bak"])

        val loaded = store.load()
        assertTrue(loaded is SettingsProfileLoad.Valid)
        val document = (loaded as SettingsProfileLoad.Valid).document
        assertEquals(setOf("model-b"), document.modelOverrides.keys)
        assertEquals(override("model-b"), document.modelOverrides["model-b"])
        assertEquals(null, document.globalDefaults)
    }

    @Test
    fun `mutations persist with commit, never apply`() {
        val prefs = InMemoryPrefs()
        val store = LocalModelSettingsProfileStore(prefs)
        assertTrue(store.writeModelOverride("model-a", override()))
        assertTrue(store.writeGlobalDefaults(override()))
        assertTrue(store.deleteModelOverride("model-a"))
        assertTrue(prefs.commitCalls.get() >= 3)
        assertEquals(0, prefs.applyCalls.get())
    }

    @Test
    fun `profile recovery cannot roll back an unrelated preference key changed concurrently`() {
        val prefs = InMemoryPrefs()
        val store = LocalModelSettingsProfileStore(prefs)
        // An unrelated legacy/migration/settings key sharing the same file.
        assertTrue(prefs.edit().putBoolean("reasoning_enabled", true).commit())
        assertTrue(store.writeModelOverride("model-a", override()))

        // While the profile mutation is between its process mutation and its
        // recovery, another operation toggles an unrelated setting key.
        prefs.onCommitBeforeResult {
            assertTrue(prefs.edit().putBoolean("reasoning_enabled", false).commit())
        }
        // With the hook re-entrancy guard, nested commits from the hook keep
        // the scripted queue: #1 profile commit fails; #2 unrelated key update
        // succeeds; #3 the profile recovery restores ONLY the profile key.
        prefs.enqueueCommitResults(false, true, true)

        assertFalse(store.writeModelOverride("model-b", override("model-b")))

        // The unrelated setting survives; the profile is rolled back to its
        // pre-mutation content (only model-a present).
        assertFalse(prefs.values["reasoning_enabled"] as? Boolean ?: true)
        val document = store.read()
        assertEquals(setOf("model-a"), document.modelOverrides.keys)
        assertFalse(document.modelOverrides.containsKey("model-b"))
        prefs.onCommitBeforeResult(null)
    }

    /**
     * Two DISTINCT store instances sharing the same [PlatformSharedPreferences] and
     * profile KEY must serialize their snapshot -> commit -> recovery sequences via
     * the shared process-wide key lock. Store1 writes B (scripted to fail) and is
     * paused inside its failure window holding the lock; Store2 must be unable to
     * complete its write C until Store1 releases the lock, fails, and recovers A.
     * Final durable + process state must be C (Store2's value), never A.
     *
     * The blocked evidence is direct: while Store1 is paused owning the shared
     * key monitor, Store2's worker is observed in [Thread.State.BLOCKED] on
     * that monitor BEFORE Store1 is released.
     */
    @Test
    fun `two stores sharing prefs serialize same-key mutations via shared lock`() {
        val prefs = InMemoryPrefs()
        val store1 = LocalModelSettingsProfileStore(prefs)
        val store2 = LocalModelSettingsProfileStore(prefs)

        // Establish durable state A.
        assertTrue(store1.writeModelOverride("model-a", override("model-a")))
        val durableA = prefs.durableValues[LocalModelSettingsProfileStore.KEY]
        assertEquals(durableA, prefs.values[LocalModelSettingsProfileStore.KEY])

        // An unrelated key sharing the same file must survive untouched.
        assertTrue(prefs.edit().putBoolean("reasoning_enabled", true).commit())

        // Script: Store1 write B fails; Store1 recovery succeeds; Store2 write C succeeds.
        prefs.enqueueCommitResults(false, true, true)

        val store1Paused = CountDownLatch(1)
        val releaseStore1 = CountDownLatch(1)
        val store1Done = CountDownLatch(1)
        val store2Done = CountDownLatch(1)
        // Signals that Store2's worker thread is running up to the line
        // before the production mutation; the BLOCKED-state proof below then
        // shows the mutation actually reached the shared key monitor.
        val store2Started = CountDownLatch(1)
        val store1Result = booleanArrayOf(false)
        val store2Result = booleanArrayOf(false)

        prefs.onCommitBeforeResult {
            // One-shot: fire only for Store1's failing write, then disable.
            prefs.onCommitBeforeResult(null)
            store1Paused.countDown()
            // Block inside commit() so Store1 still holds the shared key lock,
            // deterministically reproducing the cross-instance failure window.
            releaseStore1.await(10, TimeUnit.SECONDS)
        }

        Thread {
            store1Result[0] = store1.writeModelOverride("model-b", override("model-b"))
            store1Done.countDown()
        }.start()

        // Wait until Store1 is paused inside its failure window (holding the lock).
        assertTrue(store1Paused.await(10, TimeUnit.SECONDS))
        // 2 setup commits (model-a, reasoning_enabled) + Store1's failing
        // commit have executed; the queued results still hold Store2's commit
        // and Store1's recovery restore.
        assertEquals(3, prefs.commitCalls.get())

        val store2Thread = Thread {
            store2Started.countDown()
            store2Result[0] = store2.writeModelOverride("model-c", override("model-c"))
            store2Done.countDown()
        }
        store2Thread.start()

        // PROVE Store2's worker actually started and is running (not merely
        // "was not scheduled" during the block window).
        assertTrue(store2Started.await(10, TimeUnit.SECONDS))

        // PROVE Store2 actually REACHED the shared process-wide key monitor:
        // withKeyLock() is a JVM synchronized monitor, so a writer that has
        // arrived while Store1 owns the lock is BLOCKED on it, never RUNNABLE.
        // This rules out the scheduler descheduling Store2 right after the
        // start latch: the thread state itself is the evidence, observed
        // while Store1 is still paused owning the lock.
        assertTrue(awaitThreadState(store2Thread, Thread.State.BLOCKED))
        assertEquals(Thread.State.BLOCKED, store2Thread.state)

        // PROVE Store2 cannot complete while Store1 still owns the shared scope.
        assertFalse(store2Done.await(500, TimeUnit.MILLISECONDS))
        // PROVE Store2 is blocked at the production mutation, not merely
        // unscheduled: still no commit beyond Store1's scripted failing one.
        assertEquals(3, prefs.commitCalls.get())

        // Release Store1: its commit returns FALSE, it restores A, releases the lock.
        releaseStore1.countDown()

        // Store1 finishes its recovery.
        assertTrue(store1Done.await(10, TimeUnit.SECONDS))
        // Store2 then proceeds and commits C.
        assertTrue(store2Done.await(10, TimeUnit.SECONDS))
        // 5 = 2 setup + Store1 fail + Store1 recovery restore + Store2's write
        // C: Store2's commit could only have run after Store1 released the
        // shared key lock and completed its recovery.
        assertEquals(5, prefs.commitCalls.get())

        // Store1 reported its failed write.
        assertFalse(store1Result[0])
        // Store2 reported success.
        assertTrue(store2Result[0])

        // Final process-visible profile is C.
        val processDoc = store1.read()
        assertEquals(setOf("model-a", "model-c"), processDoc.modelOverrides.keys)

        // Final durable profile is C.
        assertEquals(
            prefs.values[LocalModelSettingsProfileStore.KEY],
            prefs.durableValues[LocalModelSettingsProfileStore.KEY],
        )

        // Profile is not A ({model-a} only) and not B ({model-a, model-b}).
        assertFalse(processDoc.modelOverrides.containsKey("model-b"))

        // The successful Store2 mutation was not rolled back.
        assertTrue(store2Result[0])

        // No unrelated setting was modified.
        assertTrue(prefs.values["reasoning_enabled"] as? Boolean ?: false)

        prefs.onCommitBeforeResult(null)
    }
}
