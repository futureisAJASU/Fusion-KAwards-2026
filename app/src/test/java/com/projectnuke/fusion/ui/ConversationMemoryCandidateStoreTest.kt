package com.projectnuke.fusion.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConversationMemoryCandidateStoreTest {
    private class InMemoryPrefs(
        private val removeCommitResult: Boolean = true,
    ) : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun edit(): SharedPreferences.Editor = Editor()

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()

            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean {
                if (removed.isNotEmpty() && !removeCommitResult) return false
                removed.forEach(values::remove)
                values.putAll(pending)
                return true
            }
            override fun apply() { commit() }
        }
    }

    private class TestContext(
        private val root: File,
        private val prefs: InMemoryPrefs,
    ) : ContextWrapper(null) {
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
    }

    private fun candidate(
        index: Int,
        text: String = "memory-$index",
        scope: MemoryScope = MemoryScope.CONVERSATION_ONLY,
        modelId: String? = null,
    ): JSONObject = JSONObject()
            .put("id", "candidate-$index")
            .put("text", text)
            .put("conversationId", 7L)
            .put("createdAt", index.toLong())
            .put("updatedAt", index.toLong())
            .put("enabled", true)
            .put("scope", scope.name)
            .put("savedByUser", true)
            .apply { modelId?.let { put("modelId", it) } }

    private fun candidateArray(
        count: Int,
        text: String = "memory",
    ): JSONArray = JSONArray().also { array ->
        repeat(count) { index -> array.put(candidate(index, "$text-$index")) }
    }

    private fun legacyPrefs(
        raw: String,
        prefs: InMemoryPrefs = InMemoryPrefs(),
    ): Pair<TestContext, InMemoryPrefs> {
        if (raw == "[]") {
            prefs.edit().remove("saved_candidates").commit()
        } else {
            prefs.edit().putString("saved_candidates", raw).commit()
        }
        return TestContext(Files.createTempDirectory("fusion-memory-candidates").toFile(), prefs) to prefs
    }

    private fun nearLimitLegacy(): String {
        val array = JSONArray()
        val text = "x".repeat(2_000)
        while (array.toString().toByteArray(Charsets.UTF_8).size < 1_047_000) {
            array.put(candidate(array.length(), text))
        }
        if (array.toString().toByteArray(Charsets.UTF_8).size >= 1_048_576) {
            array.remove(array.length() - 1)
        }
        return array.toString()
    }

    @Test
    fun `normal legacy migration is readable after reload and keeps all candidates`() {
        val raw = JSONArray().put(candidate(1)).put(candidate(2)).toString()
        val (context, prefs) = legacyPrefs(raw)

        val loaded = kotlinx.coroutines.runBlocking { loadAllConversationMemoryCandidates(context) }

        assertEquals(2, loaded.size)
        assertTrue(File(context.filesDir, "conversation_memory_candidates.json").isFile)
        assertFalse(prefs.contains("saved_candidates"))

        val reloaded = kotlinx.coroutines.runBlocking {
            loadAllConversationMemoryCandidates(TestContext(context.filesDir, prefs))
        }
        assertEquals(loaded.map { it.id }.toSet(), reloaded.map { it.id }.toSet())
    }

    @Test
    fun `legacy payload just below file limit migrates safely`() {
        val raw = nearLimitLegacy()
        assertTrue(raw.toByteArray(Charsets.UTF_8).size < 1_048_576)
        val (context, prefs) = legacyPrefs(raw)

        val loaded = kotlinx.coroutines.runBlocking { loadAllConversationMemoryCandidates(context) }

        assertTrue(loaded.isNotEmpty())
        assertFalse(prefs.contains("saved_candidates"))
        assertTrue(File(context.filesDir, "conversation_memory_candidates.json").length() <= 1_048_576)
    }

    @Test
    fun `oversized legacy payload preserves the only readable source`() {
        val oversized = JSONArray(nearLimitLegacy())
        while (oversized.toString().toByteArray(Charsets.UTF_8).size <= 1_048_576) {
            oversized.put(candidate(100_000 + oversized.length(), "y".repeat(2_000)))
        }
        val raw = oversized.toString()
        assertTrue(raw.toByteArray(Charsets.UTF_8).size > 1_048_576)
        val (context, prefs) = legacyPrefs(raw)

        val first = kotlinx.coroutines.runBlocking { loadAllConversationMemoryCandidates(context) }
        val second = kotlinx.coroutines.runBlocking {
            loadAllConversationMemoryCandidates(TestContext(context.filesDir, prefs))
        }

        assertTrue(first.isNotEmpty())
        assertEquals(first.size, second.size)
        assertEquals(raw, prefs.getString("saved_candidates", null))
        assertFalse(File(context.filesDir, "conversation_memory_candidates.json").isFile)
    }

    @Test
    fun `oversized save reports capacity rejection without deleting existing candidates`() {
        val raw = nearLimitLegacy()
        val (context, prefs) = legacyPrefs(raw)
        val before = kotlinx.coroutines.runBlocking { loadAllConversationMemoryCandidates(context) }

        val result = kotlinx.coroutines.runBlocking {
            saveConversationMemoryCandidatesWithResult(
                context,
                7L,
                (0 until 10).map { "new candidate $it" }
            )
        }

        assertEquals(ConversationMemorySaveStatus.CAPACITY_REJECTED, result.status)
        assertEquals(0, result.savedCount)
        assertTrue(prefs.contains("saved_candidates").not())
        val after = kotlinx.coroutines.runBlocking { loadAllConversationMemoryCandidates(context) }
        assertEquals(before.size, after.size)
        assertNotNull(after.firstOrNull())
    }

    @Test
    fun `legacy cleanup failure leaves file authoritative through a newer save and reload`() {
        val prefs = InMemoryPrefs(removeCommitResult = false)
        val raw = JSONArray().put(candidate(1, "legacy L")).toString()
        val (context, _) = legacyPrefs(raw, prefs)

        kotlinx.coroutines.runBlocking { loadAllConversationMemoryCandidates(context) }
        val result = kotlinx.coroutines.runBlocking {
            saveConversationMemoryCandidatesWithResult(context, 7L, listOf("new N"))
        }

        assertEquals(ConversationMemorySaveStatus.SAVED, result.status)
        assertTrue(prefs.contains("saved_candidates"))
        assertTrue(File(context.filesDir, "conversation_memory_candidates.json").readText().contains("new N"))

        val reloaded = kotlinx.coroutines.runBlocking {
            loadAllConversationMemoryCandidates(TestContext(context.filesDir, prefs))
        }
        assertTrue(reloaded.any { it.text == "legacy L" })
        assertTrue(reloaded.any { it.text == "new N" })
        assertEquals(raw, prefs.getString("saved_candidates", null))
    }

    @Test
    fun `valid current file wins over different stale legacy data`() {
        val prefs = InMemoryPrefs(removeCommitResult = false)
        val staleLegacy = JSONArray().put(candidate(1, "stale legacy")).toString()
        val current = JSONArray().put(candidate(2, "newer file")).toString()
        val (context, _) = legacyPrefs(staleLegacy, prefs)
        File(context.filesDir, "conversation_memory_candidates.json").writeText(current)

        val loaded = kotlinx.coroutines.runBlocking { loadAllConversationMemoryCandidates(context) }

        assertEquals(listOf("newer file"), loaded.map { it.text })
        assertEquals(staleLegacy, prefs.getString("saved_candidates", null))
    }

    @Test
    fun `historical 1001 record file remains readable and survives recreation`() {
        val current = candidateArray(1_001)
        val (context, prefs) = legacyPrefs("[]")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText(current.toString())

        val loaded = kotlinx.coroutines.runBlocking { loadAllConversationMemoryCandidates(context) }
        val reloaded = kotlinx.coroutines.runBlocking {
            loadAllConversationMemoryCandidates(TestContext(context.filesDir, prefs))
        }

        assertEquals(1_000, loaded.size)
        assertEquals(1_000, reloaded.size)
        assertTrue(loaded.any { it.id == "candidate-0" })
        assertTrue(loaded.none { it.id == "candidate-1000" })
        assertEquals(1_001, JSONArray(file.readText()).length())
    }

    @Test
    fun `historical overflow file remains authoritative over stale legacy`() {
        val current = candidateArray(1_001)
        val stale = JSONArray().put(candidate(90_000, "stale legacy")).toString()
        val prefs = InMemoryPrefs(removeCommitResult = false)
        val (context, _) = legacyPrefs(stale, prefs)
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText(current.toString())
        val before = file.readText()

        val loaded = kotlinx.coroutines.runBlocking { loadAllConversationMemoryCandidates(context) }

        assertEquals(1_000, loaded.size)
        assertTrue(loaded.none { it.text == "stale legacy" })
        assertEquals(before, file.readText())
        assertEquals(stale, prefs.getString("saved_candidates", null))
    }

    @Test
    fun `model normalization does not rewrite an overflow file`() {
        val spec = com.projectnuke.fusion.modelzoo.FusionModelCatalog.builtIn.first()
        val current = JSONArray().put(
            candidate(0, "model-only", MemoryScope.MODEL_ONLY, spec.displayName)
        )
        repeat(1_000) { index -> current.put(candidate(index + 1)) }
        val (context, prefs) = legacyPrefs("[]")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText(current.toString())
        val before = file.readText()

        val loaded = kotlinx.coroutines.runBlocking { loadAllConversationMemoryCandidates(context) }

        assertEquals(spec.id, loaded.first { it.id == "candidate-0" }.modelId)
        assertEquals(before, file.readText())
        assertEquals(1_001, JSONArray(file.readText()).length())
        assertFalse(prefs.contains("saved_candidates"))
    }

    @Test
    fun `mutating an overflow file cannot silently truncate unrelated records`() {
        val (context, _) = legacyPrefs("[]")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText(candidateArray(1_001).toString())
        val before = file.readText()

        assertFalse(kotlinx.coroutines.runBlocking {
            updateConversationMemoryCandidate(context, "candidate-0", "edited")
        })
        assertFalse(kotlinx.coroutines.runBlocking {
            setConversationMemoryCandidateEnabled(context, "candidate-0", false)
        })

        assertEquals(before, file.readText())
        assertEquals(1_001, JSONArray(file.readText()).length())
    }

    @Test
    fun `deleting enough overflow records may commit bounded representation`() {
        val (context, _) = legacyPrefs("[]")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText(candidateArray(1_001).toString())

        assertTrue(kotlinx.coroutines.runBlocking {
            deleteConversationMemoryCandidate(context, "candidate-1000")
        })

        assertEquals(1_000, JSONArray(file.readText()).length())
    }

    @Test
    fun `new save over record capacity preserves existing authoritative file`() {
        val (context, _) = legacyPrefs("[]")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText(candidateArray(1_000).toString())
        val before = file.readText()

        val result = kotlinx.coroutines.runBlocking {
            saveConversationMemoryCandidatesWithResult(context, 7L, listOf("new overflow"))
        }

        assertEquals(ConversationMemorySaveStatus.CAPACITY_REJECTED, result.status)
        assertEquals(before, file.readText())
        assertEquals(1_000, JSONArray(file.readText()).length())
    }

    @Test
    fun `normal bounded current file remains writable`() {
        val (context, _) = legacyPrefs("[]")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText(candidateArray(2).toString())

        assertTrue(kotlinx.coroutines.runBlocking {
            updateConversationMemoryCandidate(context, "candidate-0", "edited")
        })

        assertTrue(file.readText().contains("edited"))
        assertEquals(2, JSONArray(file.readText()).length())
    }

    @Test
    fun `overlong candidate edit is rejected without truncating persisted text`() {
        val (context, _) = legacyPrefs("[]")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText(candidateArray(1, "original").toString())
        val before = file.readText()

        assertFalse(kotlinx.coroutines.runBlocking {
            updateConversationMemoryCandidate(
                context,
                "candidate-0",
                "x".repeat(MaxCandidateTextChars + 1),
            )
        })

        assertEquals(before, file.readText())
        assertEquals("original-0", JSONArray(file.readText()).getJSONObject(0).getString("text"))
    }

    @Test
    fun `historical file over byte limit is recoverable without destructive rewrite`() {
        val largeText = "x".repeat(2_000)
        val current = candidateArray(1_000, largeText)
        val (context, prefs) = legacyPrefs("[]")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText(current.toString())
        assertTrue(file.length() > 1_048_576)
        val before = file.readBytes()

        val loaded = kotlinx.coroutines.runBlocking { loadAllConversationMemoryCandidates(context) }

        assertEquals(1_000, loaded.size)
        assertEquals(before.toList(), file.readBytes().toList())
        assertFalse(prefs.contains("saved_candidates"))
    }

    @Test
    fun `save on unreadable artifact fails closed without replacing evidence`() {
        val (context, _) = legacyPrefs("[]")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText("[{broken")
        val before = file.readBytes()

        val result = kotlinx.coroutines.runBlocking {
            saveConversationMemoryCandidatesWithResult(context, 7L, listOf("brand new memory candidate text"))
        }

        assertEquals(ConversationMemorySaveStatus.WRITE_FAILED, result.status)
        assertEquals(0, result.savedCount)
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test
    fun `conversation delete on unreadable artifact fails closed without replacing evidence`() {
        val (context, _) = legacyPrefs("[]")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText("[{broken")
        val before = file.readBytes()

        val deleted = kotlinx.coroutines.runBlocking {
            deleteConversationOnlyMemoryCandidates(context, 7L)
        }

        assertFalse(deleted)
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test
    fun `delete preserves malformed raw entries it was not asked to remove`() {
        val (context, _) = legacyPrefs("[]")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        val raw = JSONArray().put(candidate(1)).put("not-an-object").put(42).toString()
        file.writeText(raw)

        val deleted = kotlinx.coroutines.runBlocking {
            deleteConversationMemoryCandidate(context, "candidate-1")
        }

        assertTrue(deleted)
        val remaining = JSONArray(file.readText())
        assertEquals(2, remaining.length())
        assertEquals("not-an-object", remaining.getString(0))
        assertEquals(42, remaining.getInt(1))
    }

    // D1 regression tests: unreadable current artifact must never be replaced
    // from legacy fallback through mutation paths.

    @Test
    fun `D1_A - unreadable current file plus legacy candidate update returns false and preserves bytes`() {
        val (context, prefs) = legacyPrefs("""[{"id":"legacy-1","text":"legacy memory","conversationId":7}]""")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText("[{broken")
        val before = file.readBytes()

        val result = kotlinx.coroutines.runBlocking {
            updateConversationMemoryCandidate(context, "legacy-1", "edited text")
        }

        assertFalse("update must return false on unreadable current artifact", result)
        assertTrue("unreadable current file must remain byte-identical", before.contentEquals(file.readBytes()))
    }

    @Test
    fun `D1_B - unreadable current file plus legacy candidate enabled toggle returns false and preserves bytes`() {
        val (context, prefs) = legacyPrefs("""[{"id":"legacy-1","text":"legacy memory","conversationId":7}]""")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText("[{broken")
        val before = file.readBytes()

        val result = kotlinx.coroutines.runBlocking {
            setConversationMemoryCandidateEnabled(context, "legacy-1", false)
        }

        assertFalse("enabled toggle must return false on unreadable current artifact", result)
        assertTrue("unreadable current file must remain byte-identical", before.contentEquals(file.readBytes()))
    }

    @Test
    fun `D1_C - unreadable current file plus legacy candidate scope mutation returns false and preserves bytes`() {
        val (context, prefs) = legacyPrefs("""[{"id":"legacy-1","text":"legacy memory","conversationId":7}]""")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText("[{broken")
        val before = file.readBytes()

        val result = kotlinx.coroutines.runBlocking {
            setConversationMemoryCandidateScope(context, "legacy-1", MemoryScope.GLOBAL)
        }

        assertFalse("scope mutation must return false on unreadable current artifact", result)
        assertTrue("unreadable current file must remain byte-identical", before.contentEquals(file.readBytes()))
    }

    @Test
    fun `D1_D - unreadable current file plus legacy candidate delete returns false and preserves bytes`() {
        val (context, prefs) = legacyPrefs("""[{"id":"legacy-1","text":"legacy memory","conversationId":7}]""")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText("[{broken")
        val before = file.readBytes()

        val result = kotlinx.coroutines.runBlocking {
            deleteConversationMemoryCandidate(context, "legacy-1")
        }

        assertFalse("delete must return false on unreadable current artifact", result)
        assertTrue("unreadable current file must remain byte-identical", before.contentEquals(file.readBytes()))
    }

    @Test
    fun `D1_E - unreadable current file plus legacy MODEL_ONLY candidate load returns display-normalized without write`() {
        val (context, prefs) = legacyPrefs(
            """[{"id":"legacy-1","text":"legacy memory","conversationId":7,"scope":"MODEL_ONLY","modelId":"old-model-id"}]"""
        )
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText("[{broken")
        val before = file.readBytes()

        val loaded = kotlinx.coroutines.runBlocking { loadAllConversationMemoryCandidates(context) }

        // Load may return display-normalized candidate.
        assertTrue("load should return the legacy candidate for display", loaded.isNotEmpty())
        // But NO write to the current file.
        assertTrue("unreadable current file must remain byte-identical after load", before.contentEquals(file.readBytes()))
    }

    @Test
    fun `D1_F - missing current file plus valid legacy allows normal migration`() {
        val (context, prefs) = legacyPrefs("""[{"id":"legacy-1","text":"legacy memory","conversationId":7}]""")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        // No current file exists.
        assertFalse("current file must not exist", file.exists())

        val loaded = kotlinx.coroutines.runBlocking { loadAllConversationMemoryCandidates(context) }

        // Normal existing migration remains allowed.
        assertEquals(1, loaded.size)
        assertEquals("legacy-1", loaded.first().id)
    }

    @Test
    fun `D1_G - valid current file allows all normal mutations`() {
        val (context, _) = legacyPrefs("[]")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText(candidateArray(2).toString())

        // Update
        assertTrue(kotlinx.coroutines.runBlocking {
            updateConversationMemoryCandidate(context, "candidate-0", "edited")
        })
        assertTrue(file.readText().contains("edited"))

        // Enabled toggle
        assertTrue(kotlinx.coroutines.runBlocking {
            setConversationMemoryCandidateEnabled(context, "candidate-1", false)
        })

        // Scope mutation
        assertTrue(kotlinx.coroutines.runBlocking {
            setConversationMemoryCandidateScope(context, "candidate-0", MemoryScope.GLOBAL)
        })

        // Delete
        assertTrue(kotlinx.coroutines.runBlocking {
            deleteConversationMemoryCandidate(context, "candidate-1")
        })
        assertEquals(1, JSONArray(file.readText()).length())
    }

    // ── P2 mutation-authority regression tests ─────────────────────────────
    //
    // Mutation must obtain its base through ONE authoritative read. The old
    // code performed a readability pre-check and then separately called the
    // DISPLAY-capable reader, which is allowed to fall back to legacy/"[]".
    // When the current artifact became unreadable between those two
    // observations, the fallback silently became the write base and the
    // current candidates were lost.

    /**
     * Simulates a transient current-artifact read failure: the current file is
     * authoritative when the first authority observation happens, then becomes
     * unreadable before any later, display-capable observation. The flip runs
     * on the first getSharedPreferences access, which in production is exactly
     * where source resolution begins.
     */
    private class TransientFailureContext(
        private val root: File,
        private val prefs: InMemoryPrefs,
        private val onFirstPrefsAccess: () -> Unit,
    ) : ContextWrapper(null) {
        private var fired = false
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            if (!fired) {
                fired = true
                onFirstPrefsAccess()
            }
            return prefs
        }
        override fun getApplicationContext(): Context = this
    }

    private fun candidateFileIn(root: File): File =
        File(root, "conversation_memory_candidates.json")

    @Test
    fun `P2_1 - transient current read failure cannot fall back to an empty base`() {
        val prefs = InMemoryPrefs()
        val root = Files.createTempDirectory("fusion-memory-p21").toFile()
        val file = candidateFileIn(root)
        file.writeText(candidateArray(3).toString())
        var atFailure: ByteArray = byteArrayOf()

        val context = TransientFailureContext(root, prefs) {
            // Current artifact becomes unreadable after the authority
            // observation; its original records stay in the artifact.
            file.writeText("x" + file.readText())
            atFailure = file.readBytes()
        }

        val result = kotlinx.coroutines.runBlocking {
            saveConversationMemoryCandidatesWithResult(context, 7L, listOf("candidate D"))
        }

        assertEquals(
            "an unavailable authoritative base must fail the save closed",
            ConversationMemorySaveStatus.WRITE_FAILED,
            result.status,
        )
        assertEquals(0, result.savedCount)
        assertTrue(
            "current artifact must be untouched by the failed mutation",
            atFailure.contentEquals(file.readBytes()),
        )
        val artifact = file.readText()
        assertTrue("candidate-0 must survive", artifact.contains("candidate-0"))
        assertTrue("candidate-1 must survive", artifact.contains("candidate-1"))
        assertTrue("candidate-2 must survive", artifact.contains("candidate-2"))
        assertFalse("empty fallback must not become the write base", artifact.contains("candidate D"))
    }

    @Test
    fun `P2_2 - stale legacy cannot replace current candidates`() {
        val prefs = InMemoryPrefs()
        val staleLegacy = JSONArray().put(candidate(90_000, "stale legacy")).toString()
        prefs.edit().putString("saved_candidates", staleLegacy).commit()
        val root = Files.createTempDirectory("fusion-memory-p22").toFile()
        val file = candidateFileIn(root)
        file.writeText(candidateArray(3).toString())
        var atFailure: ByteArray = byteArrayOf()

        val context = TransientFailureContext(root, prefs) {
            file.writeText("x" + file.readText())
            atFailure = file.readBytes()
        }

        val result = kotlinx.coroutines.runBlocking {
            saveConversationMemoryCandidatesWithResult(context, 7L, listOf("candidate D"))
        }

        assertEquals(
            "stale legacy must never authorize a rewrite",
            ConversationMemorySaveStatus.WRITE_FAILED,
            result.status,
        )
        assertTrue(
            "current candidates must remain byte-equivalent",
            atFailure.contentEquals(file.readBytes()),
        )
        val artifact = file.readText()
        assertTrue(artifact.contains("candidate-0"))
        assertTrue(artifact.contains("candidate-1"))
        assertTrue(artifact.contains("candidate-2"))
        assertFalse("stale legacy must not be written", artifact.contains("stale legacy"))
        assertEquals("legacy source must remain untouched", staleLegacy, prefs.getString("saved_candidates", null))
    }

    @Test
    fun `P2_3 - missing current file plus valid legacy still migrates for mutation`() {
        val (context, prefs) = legacyPrefs(
            """[{"id":"legacy-1","text":"legacy memory","conversationId":7}]"""
        )
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        assertFalse("current file must not exist", file.exists())

        val result = kotlinx.coroutines.runBlocking {
            saveConversationMemoryCandidatesWithResult(context, 7L, listOf("new N"))
        }

        assertEquals(ConversationMemorySaveStatus.SAVED, result.status)
        assertEquals(1, result.savedCount)
        val persisted = JSONArray(file.readText())
        assertEquals(2, persisted.length())
        val texts = (0 until persisted.length()).map { persisted.getJSONObject(it).getString("text") }
        assertTrue(texts.contains("legacy memory"))
        assertTrue(texts.contains("new N"))
        assertFalse("bounded migration consumes the legacy source", prefs.contains("saved_candidates"))
    }

    @Test
    fun `P2_5 - normal valid current file save appends to the authoritative candidates`() {
        val (context, _) = legacyPrefs("[]")
        val file = File(context.filesDir, "conversation_memory_candidates.json")
        file.writeText(candidateArray(3).toString())

        val result = kotlinx.coroutines.runBlocking {
            saveConversationMemoryCandidatesWithResult(context, 7L, listOf("candidate D"))
        }

        assertEquals(ConversationMemorySaveStatus.SAVED, result.status)
        assertEquals(1, result.savedCount)
        val persisted = JSONArray(file.readText())
        assertEquals(4, persisted.length())
        val artifact = file.readText()
        assertTrue(artifact.contains("candidate-0"))
        assertTrue(artifact.contains("candidate-1"))
        assertTrue(artifact.contains("candidate-2"))
        assertTrue(artifact.contains("candidate D"))
    }

    @Test
    fun `P2_authority - mutation base reader resolves each source state correctly`() {
        // CURRENT_VALID -> authoritative current JSON.
        val validRoot = Files.createTempDirectory("fusion-mutation-valid").toFile()
        val validFile = candidateFileIn(validRoot)
        val validJson = candidateArray(2).toString()
        validFile.writeText(validJson)
        val validContext = TestContext(validRoot, InMemoryPrefs())
        assertEquals(validJson, kotlinx.coroutines.runBlocking { readCandidateJsonForMutation(validContext) })

        // CURRENT_UNREADABLE -> null (never the legacy fallback).
        val unreadableRoot = Files.createTempDirectory("fusion-mutation-unreadable").toFile()
        candidateFileIn(unreadableRoot).writeText("[{broken")
        val unreadablePrefs = InMemoryPrefs()
        unreadablePrefs.edit()
            .putString("saved_candidates", JSONArray().put(candidate(1, "legacy")).toString())
            .commit()
        val unreadableContext = TestContext(unreadableRoot, unreadablePrefs)
        assertNull(kotlinx.coroutines.runBlocking { readCandidateJsonForMutation(unreadableContext) })

        // CURRENT_MISSING + no legacy -> authoritative empty list.
        val missingRoot = Files.createTempDirectory("fusion-mutation-missing").toFile()
        val missingContext = TestContext(missingRoot, InMemoryPrefs())
        assertEquals("[]", kotlinx.coroutines.runBlocking { readCandidateJsonForMutation(missingContext) })
        assertFalse(candidateFileIn(missingRoot).exists())

        // CURRENT_MISSING + valid legacy -> migrated authoritative JSON.
        val migrateRoot = Files.createTempDirectory("fusion-mutation-migrate").toFile()
        val migratePrefs = InMemoryPrefs()
        val legacyRaw = JSONArray().put(candidate(3, "legacy kept")).toString()
        migratePrefs.edit().putString("saved_candidates", legacyRaw).commit()
        val migrateContext = TestContext(migrateRoot, migratePrefs)
        assertEquals(
            JSONArray(legacyRaw).toString(),
            kotlinx.coroutines.runBlocking { readCandidateJsonForMutation(migrateContext) },
        )
        assertTrue(candidateFileIn(migrateRoot).isFile)
        assertFalse(migratePrefs.contains("saved_candidates"))
    }

    @Test
    fun `malformed persisted data keeps corruption behavior without crashing`() {
        val root = Files.createTempDirectory("fusion-memory-malformed").toFile()
        candidateFileIn(root).writeText("[{broken")
        val context = TestContext(root, InMemoryPrefs())
        // Ordinary malformed data reads as empty; mutations fail closed.
        assertTrue(kotlinx.coroutines.runBlocking { loadAllConversationMemoryCandidates(context) }.isEmpty())
        val result = kotlinx.coroutines.runBlocking {
            saveConversationMemoryCandidatesWithResult(context, 7L, listOf("fresh memory"))
        }
        assertEquals(ConversationMemorySaveStatus.WRITE_FAILED, result.status)
    }

    @Test
    fun `cancellation propagates instead of empty or failed results`() {
        val (context, _) = legacyPrefs("[]")
        val cancelled = kotlinx.coroutines.Job().apply { cancel() }
        try {
            kotlinx.coroutines.runBlocking(cancelled) { loadAllConversationMemoryCandidates(context) }
            org.junit.Assert.fail("expected CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        try {
            kotlinx.coroutines.runBlocking(cancelled) {
                saveConversationMemoryCandidatesWithResult(context, 7L, listOf("memory"))
            }
            org.junit.Assert.fail("expected CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
    }
}
