package com.projectnuke.fusion.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LiteRtMtpFailureMemoryTest {

    private class FakeStorage : MtpFailureMemoryStorage {
        val map = mutableMapOf<String, String>()
        var clearCount = 0
        var saveCount = 0
        var saveSuccess = true
        override fun load(): Map<String, String> = map.toMap()
        override fun save(entries: Map<String, String>): Boolean {
            saveCount++
            if (saveSuccess) {
                map.clear()
                map.putAll(entries)
            }
            return saveSuccess
        }

        override fun clear(): Boolean {
            clearCount++
            map.clear()
            return true
        }
    }

    private class FatalStorage : MtpFailureMemoryStorage {
        var fatalLoad = false
        var fatalSave = false
        var fatalClear = false

        override fun load(): Map<String, String> {
            if (fatalLoad) throw object : LinkageError("fatal load") {}
            return emptyMap()
        }

        override fun save(entries: Map<String, String>): Boolean {
            if (fatalSave) throw object : LinkageError("fatal save") {}
            return true
        }

        override fun clear(): Boolean {
            if (fatalClear) throw object : LinkageError("fatal clear") {}
            return true
        }
    }

    private fun record(
        memory: MtpFailureMemory,
        modelPath: String = "/data/models/gemma.litertlm",
        backend: String = "GPU",
        mtpEnabled: Boolean = true,
        reason: String = "MTP initialization failed, fell back to non-MTP",
        fileSize: Long = 100L,
        modifiedAt: Long = 1000L,
        mtpSupported: Boolean = true,
        clock: () -> Long = { System.currentTimeMillis() }
    ) = memory.recordFailure(
        modelPath = modelPath,
        backendName = backend,
        mtpEnabled = mtpEnabled,
        validationVersion = 2,
        kvCacheCapacityTokens = 4096,
        enableVisionBackend = false,
        fileSize = fileSize,
        modifiedAt = modifiedAt,
        mtpSupported = mtpSupported,
        fallbackReason = reason
    )

    private fun shouldSkip(
        memory: MtpFailureMemory,
        modelPath: String = "/data/models/gemma.litertlm",
        backend: String = "GPU",
        mtpEnabled: Boolean = true,
        clock: () -> Long = { System.currentTimeMillis() }
    ) = memory.shouldSkipMtp(
        modelPath = modelPath,
        backendName = backend,
        mtpEnabled = mtpEnabled,
        validationVersion = 2,
        kvCacheCapacityTokens = 4096,
        enableVisionBackend = false,
        fileSize = 100L,
        modifiedAt = 1000L,
        mtpSupported = true
    )

    @Test
    fun `recorded failure is skipped during cooldown`() {
        val memory = MtpFailureMemory(NoopMtpFailureMemoryStorage, clock = { 0L })
        record(memory)
        assertTrue(shouldSkip(memory) != null)
    }

    @Test
    fun `failure memory survives a new instance sharing the same storage`() {
        val storage = FakeStorage()
        val first = MtpFailureMemory(storage, clock = { 0L })
        record(first, reason = "MTP flag application failed")
        assertEquals(1, first.persistedEntryCount())

        val reloaded = MtpFailureMemory(storage, clock = { 0L })
        assertEquals("MTP flag application failed", shouldSkip(reloaded))
    }

    @Test
    fun `failure is keyed by exact backend and MTP state`() {
        val storage = FakeStorage()
        val first = MtpFailureMemory(storage, clock = { 0L })
        record(first, backend = "GPU")
        val reloaded = MtpFailureMemory(storage, clock = { 0L })

        // A GPU+MTP failure must never poison a CPU request or a plain GPU request.
        assertNull(shouldSkip(reloaded, backend = "CPU"))
        assertNull(shouldSkip(reloaded, backend = "GPU", mtpEnabled = false))
        assertNull(shouldSkip(reloaded, modelPath = "/data/models/other.litertlm"))
        // Different KV capacity is a different engine configuration.
        assertNull(
            reloaded.shouldSkipMtp(
                modelPath = "/data/models/gemma.litertlm",
                backendName = "GPU",
                mtpEnabled = true,
                validationVersion = 2,
                kvCacheCapacityTokens = 8192,
                enableVisionBackend = false,
                fileSize = 100L,
                modifiedAt = 1000L,
                mtpSupported = true
            )
        )
    }

    @Test
    fun `failure is keyed by file identity so same-path replacement invalidates stale entries`() {
        val storage = FakeStorage()
        val first = MtpFailureMemory(storage, clock = { 0L })
        record(first, modelPath = "/data/models/gemma.litertlm")
        val reloaded = MtpFailureMemory(storage, clock = { 0L })
        assertTrue(shouldSkip(reloaded) != null)

        // Simulate model replacement at the same path (different file size / modified time).
        record(first, modelPath = "/data/models/gemma.litertlm", fileSize = 200L, modifiedAt = 2000L)
        val reloadedAfterReplacement = MtpFailureMemory(storage, clock = { 0L })
        // Old entry (size=100, modified=1000) should no longer match the new fingerprint.
        assertNull(
            reloadedAfterReplacement.shouldSkipMtp(
                modelPath = "/data/models/gemma.litertlm",
                backendName = "GPU",
                mtpEnabled = true,
                validationVersion = 2,
                kvCacheCapacityTokens = 4096,
                enableVisionBackend = false,
                fileSize = 100L,
                modifiedAt = 1000L,
                mtpSupported = true
            )
        )
        // New entry (size=200, modified=2000) should still match.
        assertTrue(
            reloadedAfterReplacement.shouldSkipMtp(
                modelPath = "/data/models/gemma.litertlm",
                backendName = "GPU",
                mtpEnabled = true,
                validationVersion = 2,
                kvCacheCapacityTokens = 4096,
                enableVisionBackend = false,
                fileSize = 200L,
                modifiedAt = 2000L,
                mtpSupported = true
            ) != null
        )
    }

    @Test
    fun `concurrent candidate failures for same fingerprint survive process recreation`() {
        val storage = FakeStorage()
        val first = MtpFailureMemory(storage, clock = { 0L })

        // Record GPU+MTP failure
        record(first, backend = "GPU", mtpEnabled = true, reason = "MTP init failed")
        // Record GPU plain failure
        record(first, backend = "GPU", mtpEnabled = false, reason = "Backend init failed")
        // Record CPU plain failure
        record(first, backend = "CPU", mtpEnabled = false, reason = "Backend init failed")

        assertEquals(3, first.persistedEntryCount())

        // Reload from storage — all three failures must survive
        val reloaded = MtpFailureMemory(storage, clock = { 0L })
        assertEquals(3, reloaded.persistedEntryCount())
        assertTrue(shouldSkip(reloaded, backend = "GPU", mtpEnabled = true) != null)
        assertTrue(shouldSkip(reloaded, backend = "GPU", mtpEnabled = false) != null)
        assertTrue(shouldSkip(reloaded, backend = "CPU", mtpEnabled = false) != null)
    }

    @Test
    fun `clearForModel removes only that model and persists the removal`() {
        val storage = FakeStorage()
        val memory = MtpFailureMemory(storage, clock = { 0L })
        record(memory, modelPath = "/a/one.litertlm")
        record(memory, modelPath = "/b/two.litertlm")
        assertEquals(2, memory.persistedEntryCount())

        memory.clearForModel("/a/one.litertlm")
        val reloaded = MtpFailureMemory(storage, clock = { 0L })
        assertNull(shouldSkip(reloaded, modelPath = "/a/one.litertlm"))
        assertTrue(shouldSkip(reloaded, modelPath = "/b/two.litertlm") != null)
    }

    @Test
    fun `clearAll wipes the storage`() {
        val storage = FakeStorage()
        val memory = MtpFailureMemory(storage, clock = { 0L })
        record(memory)
        memory.clearAll()
        assertEquals(0, memory.persistedEntryCount())
        assertEquals(1, storage.clearCount)
        assertNull(MtpFailureMemory(storage, clock = { 0L }).shouldSkipMtp(
            modelPath = "/data/models/gemma.litertlm",
            backendName = "GPU",
            mtpEnabled = true,
            validationVersion = 2,
            kvCacheCapacityTokens = 4096,
            enableVisionBackend = false,
            fileSize = 100L,
            modifiedAt = 1000L,
            mtpSupported = true
        ))
    }

    @Test
    fun `corrupt persisted entries are ignored`() {
        val storage = FakeStorage()
        storage.map["not-enough-fields"] = "12345|reason"
        storage.map["a\u001fb\u001ftrue\u001f1\u001fc\u001f2"] = "not-a-timestamp|reason"
        storage.map["a\u001fb\u001fwat\u001f1\u001fc\u001f2"] = "12345|reason"
        storage.map["a\u001fb\u001ftrue\u001f1\u001fc\u001f2"] = "12345" // valid key, value has one field only
        val memory = MtpFailureMemory(storage, clock = { 0L })
        assertNull(shouldSkip(memory, modelPath = "x", backend = "b"))
    }

    @Test
    fun `clock controls cooldown deterministically before after`() {
        var now = 0L
        val clock = { now }
        val storage = FakeStorage()
        val memory = MtpFailureMemory(storage, clock = clock)

        record(memory, clock = clock)
        // At t=0, within cooldown: should skip
        assertTrue(shouldSkip(memory, clock = clock) != null)

        // Advance clock past cooldown
        now = MtpFailureMemory.COOLDOWN_MS + 1
        // After cooldown: should not skip
        assertNull(shouldSkip(memory, clock = clock))
    }

    @Test
    fun `wall-clock rollback does not prematurely expire cooldown`() {
        var now = 1000L
        val clock = { now }
        val storage = FakeStorage()
        val memory = MtpFailureMemory(storage, clock = clock)

        record(memory, clock = clock)
        // Within cooldown at t=1000
        assertTrue(shouldSkip(memory, clock = clock) != null)

        // Clock rolls back (e.g. NTP correction or device time change)
        now = 0L
        // Entry should still be within cooldown because elapsed time
        // is computed from the recorded failure timestamp, not wall clock.
        // With rollback, elapsed = clock() - failedAt = 0 - 1000 = negative,
        // which is < COOLDOWN_MS, so the entry is still skipped.
        assertTrue(shouldSkip(memory, clock = clock) != null)
    }

    @Test
    fun `in-memory and durably persisted entries share the same cooldown`() {
        var now = 0L
        val clock = { now }
        val storage = FakeStorage()
        val memory = MtpFailureMemory(storage, clock = clock)

        record(memory, clock = clock)
        assertEquals(1, memory.persistedEntryCount())

        // Reload from storage — the cooldown state persists.
        val reloaded = MtpFailureMemory(storage, clock = clock)
        assertTrue(shouldSkip(reloaded, clock = clock) != null)

        // Advance past cooldown in the reloaded instance.
        now = MtpFailureMemory.COOLDOWN_MS + 1
        assertNull(shouldSkip(reloaded, clock = clock))
    }

    @Test
    fun `save failure is observable through durability status`() {
        val storage = FakeStorage()
        storage.saveSuccess = false
        val memory = MtpFailureMemory(storage, clock = { 0L })

        record(memory)
        // Durability should report failure
        assertEquals(false, memory.lastDurabilityResult())
        assertTrue(memory.isDurabilityCompromised())
        assertTrue(memory.lastDurabilityException() != null)

        // In-memory state should still be usable
        assertTrue(shouldSkip(memory) != null)

        // Next successful save should clear the error
        storage.saveSuccess = true
        memory.clearForModel("/data/models/gemma.litertlm")
        assertEquals(true, memory.lastDurabilityResult())
        assertFalse(memory.isDurabilityCompromised())
        assertNull(memory.lastDurabilityException())
    }

    @Test
    fun `clear failure is observable through durability status`() {
        val storage = FakeStorage()
        val memory = MtpFailureMemory(storage, clock = { 0L })
        record(memory)
        assertEquals(1, memory.persistedEntryCount())

        // Make clear fail
        val failingStorage = object : MtpFailureMemoryStorage {
            val map = mutableMapOf<String, String>()
            override fun load(): Map<String, String> = map.toMap()
            override fun save(entries: Map<String, String>): Boolean = true
            override fun clear(): Boolean = false
        }
        val memory2 = MtpFailureMemory(failingStorage, clock = { 0L })
        record(memory2)
        memory2.clearAll()
        assertEquals(false, memory2.lastDurabilityResult())
        assertTrue(memory2.isDurabilityCompromised())
        assertTrue(memory2.lastDurabilityException() != null)
    }

    @Test
    fun `fatal storage errors propagate from every MTP storage seam`() {
        val loadStorage = FatalStorage().apply { fatalLoad = true }
        assertThrows(LinkageError::class.java) { MtpFailureMemory(loadStorage, clock = { 0L }) }

        val clearStorage = FatalStorage().apply { fatalClear = true }
        val clearMemory = MtpFailureMemory(clearStorage, clock = { 0L })
        assertThrows(LinkageError::class.java) { clearMemory.clearAll() }

        val countStorage = FatalStorage()
        val countMemory = MtpFailureMemory(countStorage, clock = { 0L })
        countStorage.fatalLoad = true
        assertThrows(LinkageError::class.java) { countMemory.persistedEntryCount() }

        val saveStorage = FatalStorage().apply { fatalSave = true }
        val saveMemory = MtpFailureMemory(saveStorage, clock = { 0L })
        assertThrows(LinkageError::class.java) { record(saveMemory) }
    }

    @Test
    fun `recoverable storage failure remains best effort`() {
        val storage = object : MtpFailureMemoryStorage {
            override fun load(): Map<String, String> = throw IllegalStateException("disk unavailable")
            override fun save(entries: Map<String, String>): Boolean = true
            override fun clear(): Boolean = true
        }

        val memory = MtpFailureMemory(storage, clock = { 0L })

        assertEquals(false, memory.lastDurabilityResult())
        assertTrue(memory.lastDurabilityException() is IllegalStateException)
    }
}
