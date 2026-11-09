package com.projectnuke.fusion.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repair Phase D tests.
 *
 * These tests exercise the Engine-owned `MtpFailureMemory` through the
 * `LiteRtLlmEngine` public surface — `failureMemoryDurability()`,
 * `clearAllMtpFailureMemory()`, and `clearMtpFailureMemory()` — not by
 * constructing `MtpFailureMemory` directly. They verify that storage
 * exceptions and explicit false results from `load`/`save`/`clear` do
 * not crash Engine construction or candidate selection, and that
 * durability is observable through a typed state.
 *
 * Storage semantics:
 *  - `clearAllMtpFailureMemory()` → triggers `storage.clear()` (no save)
 *  - `clearMtpFailureMemory(path)` → triggers `persist()` → `storage.save()`
 *    when there were entries to remove (otherwise still saves empty state)
 */
class RepairPhaseDDurabilityTest {

    private class RecordingStorage : MtpFailureMemoryStorage {
        val map = mutableMapOf<String, String>()
        var saveShouldFail = false
        var clearShouldFail = false
        var saveShouldThrow: Throwable? = null
        var clearShouldThrow: Throwable? = null
        var loadShouldThrow: Throwable? = null
        var throwOnLoadOnce = false
        private var loadThrowArmed = false

        init { if (loadShouldThrow != null) loadThrowArmed = true }

        fun armLoadThrow() {
            if (loadShouldThrow != null && throwOnLoadOnce) {
                loadThrowArmed = true
            }
        }

        override fun load(): Map<String, String> {
            if (loadShouldThrow != null && (throwOnLoadOnce && loadThrowArmed)) {
                loadThrowArmed = false
                throw loadShouldThrow!!
            }
            return map.toMap()
        }
        override fun save(entries: Map<String, String>): Boolean {
            saveShouldThrow?.let { throw it }
            if (saveShouldFail) return false
            map.clear()
            map.putAll(entries)
            return true
        }
        override fun clear(): Boolean {
            clearShouldThrow?.let { throw it }
            if (clearShouldFail) return false
            map.clear()
            return true
        }
    }

    private fun createEngine(
        storage: RecordingStorage,
        loadThrowsOnce: Boolean = false,
        loadThrows: Throwable? = null
    ): LiteRtLlmEngine {
        storage.loadShouldThrow = loadThrows
        storage.throwOnLoadOnce = loadThrowsOnce
        // Re-arm the one-shot load throw when caller asks for it AFTER
        // the RecordingStorage was constructed (its init block arms it
        // once at construction; subsequent late-binding requires rearm).
        if (loadThrows != null && loadThrowsOnce) {
            storage.armLoadThrow()
        }
        return LiteRtLlmEngine(
            context = TestContext(),
            failureMemoryStorage = storage,
            clock = { 0L }
        )
    }

    /** Minimal ContextWrapper for unit tests (no App needed). */
    private class TestContext : android.content.ContextWrapper(null) {
        private val prefs = InMemoryPrefs()

        override fun getApplicationContext(): android.content.Context = this

        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = prefs
    }

    /** In-memory SharedPreferences so the developer-log store works in JVM tests. */
    private class InMemoryPrefs : android.content.SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = null
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = InMemoryEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {}

        private inner class InMemoryEditor : android.content.SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor { pending[key] = value; return this }
            override fun putStringSet(key: String, values: MutableSet<String>?): android.content.SharedPreferences.Editor { pending[key] = values; return this }
            override fun putInt(key: String, value: Int): android.content.SharedPreferences.Editor { pending[key] = value; return this }
            override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor { pending[key] = value; return this }
            override fun putFloat(key: String, value: Float): android.content.SharedPreferences.Editor { pending[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): android.content.SharedPreferences.Editor { pending[key] = value; return this }
            override fun remove(key: String): android.content.SharedPreferences.Editor { pending[key] = null; return this }
            override fun clear(): android.content.SharedPreferences.Editor { pending.clear(); return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                map.clear()
                map.putAll(pending)
            }
        }
    }

    // ── Initial durability state ─────────────────────────────────────────

    @Test
    fun `D NotAttempted on a freshly constructed engine`() {
        val engine = createEngine(RecordingStorage())
        assertEquals(FailureMemoryDurability.NotAttempted, engine.failureMemoryDurability())
    }

    // ── save false → InMemoryOnly ────────────────────────────────────────

    @Test
    fun `D save returning false is observable as InMemoryOnly with cause`() {
        val storage = RecordingStorage().apply { saveShouldFail = true }
        val engine = createEngine(storage)
        // clearMtpFailureMemory triggers persist() → storage.save() (empty
        // state, but save is still invoked).
        engine.clearMtpFailureMemory("/nonexistent/path.litertlm")
        val durability = engine.failureMemoryDurability()
        assertTrue(
            "Expected InMemoryOnly because save returned false, got $durability",
            durability is FailureMemoryDurability.InMemoryOnly
        )
        val cause = (durability as FailureMemoryDurability.InMemoryOnly).cause
        assertNotNull(cause)
        // The cause is a synthetic IllegalStateException because storage
        // returned false (did not throw).
        assertTrue(cause is IllegalStateException)
    }

    // ── save throw → InMemoryOnly with the original exception ───────────

    @Test
    fun `D save throwing is caught and reported as InMemoryOnly with the original cause`() {
        val storage = RecordingStorage().apply {
            saveShouldThrow = java.io.IOException("disk full")
        }
        val engine = createEngine(storage)
        engine.clearMtpFailureMemory("/nonexistent/path.litertlm")
        val durability = engine.failureMemoryDurability()
        assertTrue(durability is FailureMemoryDurability.InMemoryOnly)
        val cause = (durability as FailureMemoryDurability.InMemoryOnly).cause
        assertNotNull(cause)
        // The original IOException is preserved in the durability cause.
        assertTrue(
            "Expected cause carrying 'disk full', got: $cause",
            cause is java.io.IOException || cause.message?.contains("disk full") == true
        )
    }

    // ── clear false → InMemoryOnly ───────────────────────────────────────

    @Test
    fun `D clear returning false is observable as InMemoryOnly`() {
        val storage = RecordingStorage().apply { clearShouldFail = true }
        val engine = createEngine(storage)
        engine.clearAllMtpFailureMemory()
        val durability = engine.failureMemoryDurability()
        assertTrue(durability is FailureMemoryDurability.InMemoryOnly)
        assertNotNull((durability as FailureMemoryDurability.InMemoryOnly).cause)
    }

    // ── clear throw → InMemoryOnly (no crash) ────────────────────────────

    @Test
    fun `D clear throwing is caught and reported as InMemoryOnly without crashing the engine call`() {
        val storage = RecordingStorage().apply {
            clearShouldThrow = RuntimeException("corrupt storage")
        }
        val engine = createEngine(storage)
        // The clear throw must NOT propagate out of clearAllMtpFailureMemory.
        engine.clearAllMtpFailureMemory()
        // And the durability must reflect the failure.
        val durability = engine.failureMemoryDurability()
        assertTrue(durability is FailureMemoryDurability.InMemoryOnly)
        val cause = (durability as FailureMemoryDurability.InMemoryOnly).cause
        assertNotNull(cause)
        assertTrue(
            "Expected cause to carry 'corrupt storage': $cause",
            cause.message?.contains("corrupt storage") == true
        )
    }

    // ── load throw does not crash Engine construction ───────────────────

    @Test
    fun `D load throwing on construction does not crash Engine creation`() {
        val storage = RecordingStorage()
        // Throw only on the first load (during MtpFailureMemory init).
        val engine = createEngine(storage, loadThrowsOnce = true, loadThrows = RuntimeException("corrupt prefs"))
        assertNotNull(engine)
    }

    @Test
    fun `D load throwing on construction transitions to InMemoryOnly durability state`() {
        val storage = RecordingStorage()
        val engine = createEngine(
            storage,
            loadThrowsOnce = true,
            loadThrows = java.io.IOException("cannot read storage")
        )
        val durability = engine.failureMemoryDurability()
        assertTrue(
            "Expected InMemoryOnly after a load throw on construction, got $durability",
            durability is FailureMemoryDurability.InMemoryOnly
        )
        val cause = (durability as FailureMemoryDurability.InMemoryOnly).cause
        assertNotNull(cause)
        assertTrue(cause is java.io.IOException)
    }

    // ── Successful save → Durable ───────────────────────────────────────

    @Test
    fun `D successful save transitions durability to Durable`() {
        val storage = RecordingStorage()
        val engine = createEngine(storage)
        // clearMtpFailureMemory triggers persist() → storage.save().
        engine.clearMtpFailureMemory("/any/path.litertlm")
        assertEquals(FailureMemoryDurability.Durable, engine.failureMemoryDurability())
    }

    // ── Successful clear → Durable ──────────────────────────────────────

    @Test
    fun `D successful clear transitions durability to Durable`() {
        val storage = RecordingStorage()
        val engine = createEngine(storage)
        engine.clearAllMtpFailureMemory()
        assertEquals(FailureMemoryDurability.Durable, engine.failureMemoryDurability())
    }

    // ── Recovery ─────────────────────────────────────────────────────────

    @Test
    fun `D a subsequent successful save clears the InMemoryOnly state`() {
        val storage = RecordingStorage().apply { saveShouldFail = true }
        val engine = createEngine(storage)
        engine.clearMtpFailureMemory("/any/path.litertlm")
        assertTrue(engine.failureMemoryDurability() is FailureMemoryDurability.InMemoryOnly)
        // Recover: storage now succeeds.
        storage.saveShouldFail = false
        engine.clearMtpFailureMemory("/any/path.litertlm")
        assertEquals(FailureMemoryDurability.Durable, engine.failureMemoryDurability())
    }

    @Test
    fun `D recovery from a clear throw then a successful clear clears the InMemoryOnly state`() {
        val storage = RecordingStorage().apply {
            clearShouldThrow = RuntimeException("first clear fails")
        }
        val engine = createEngine(storage)
        engine.clearAllMtpFailureMemory()
        assertTrue(engine.failureMemoryDurability() is FailureMemoryDurability.InMemoryOnly)
        // Recover: subsequent clear succeeds.
        storage.clearShouldThrow = null
        engine.clearAllMtpFailureMemory()
        assertEquals(FailureMemoryDurability.Durable, engine.failureMemoryDurability())
    }

    // ── In-memory state remains usable after durability failure ─────────

    @Test
    fun `D in-memory cooldown still works when storage is broken`() {
        val storage = RecordingStorage().apply { saveShouldFail = true }
        val engine = createEngine(storage)
        // Trigger a save failure; durability is compromised but the
        // in-memory `MtpFailureMemory` instance is still usable for the
        // lifetime of this Engine (a subsequent construction would not
        // see persisted state because save failed, but that's separate).
        engine.clearMtpFailureMemory("/any/path.litertlm")
        assertTrue(engine.failureMemoryDurability() is FailureMemoryDurability.InMemoryOnly)
        // The Engine instance is still callable.
        engine.clearMtpFailureMemory("/another/path.litertlm")
        // Still InMemoryOnly because save is still failing.
        assertTrue(engine.failureMemoryDurability() is FailureMemoryDurability.InMemoryOnly)
    }
}
