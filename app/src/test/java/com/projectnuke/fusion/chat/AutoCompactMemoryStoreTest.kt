package com.projectnuke.fusion.chat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.test.PlatformSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

private typealias AutoCompactPrefs = PlatformSharedPreferences

class AutoCompactMemoryStoreTest {
    private class TestContext(private val prefs: AutoCompactPrefs = AutoCompactPrefs()) : ContextWrapper(null) {

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun `saved compact memory is removed by conversation cleanup`() {
        val store = AutoCompactMemoryStore(TestContext())
        val fingerprint = AutoCompactionBranchIdentity.fromMessageIds(listOf(13L))
        assertTrue(store.save(AutoCompactMemory(7L, "summary", 13L, 1L, 2, branchFingerprint = fingerprint)))

        assertNotNull(store.load(7L))
        assertEquals(fingerprint, store.load(7L)?.branchFingerprint)
        assertTrue(store.deleteDurably(7L))
        assertNull(store.load(7L))
    }

    @Test
    fun `durable delete reports failure when commit fails`() {
        val store = AutoCompactMemoryStore(TestContext(AutoCompactPrefs(commitResult = false)))
        // Nothing to compare against disk; deleteDurably must report false.
        assertFalse(store.deleteDurably(9L))
    }

    @Test
    fun `failed durable compact write is reported and not adopted`() = runBlocking {
        val store = AutoCompactMemoryStore(TestContext(AutoCompactPrefs(commitResult = false)))

        assertFalse(store.save(AutoCompactMemory(8L, "summary", 13L, 1L, 2)))
        assertNull(store.load(8L))
    }

    @Test
    fun `failed compact commit restores process-visible previous memory`() {
        val prefs = AutoCompactPrefs()
        val store = AutoCompactMemoryStore(TestContext(prefs))
        val before = AutoCompactMemory(10L, "before", 13L, 1L, 2)
        val after = before.copy(summary = "after")

        assertTrue(store.save(before))
        val durableBefore = prefs.durableValues["auto_compact_10"]
        prefs.enqueueCommitResults(false, true)

        assertFalse(store.save(after))
        assertEquals(before, store.load(10L))
        assertEquals(durableBefore, prefs.durableValues["auto_compact_10"])
    }

    @Test
    fun `cross-conversation failed recovery cannot roll back a successful compact write`() {
        val prefs = AutoCompactPrefs()
        val context = TestContext(prefs)
        assertTrue(AutoCompactMemoryStore(context).save(AutoCompactMemory(1L, "A", 13L, 1L, 2)))
        assertTrue(AutoCompactMemoryStore(context).save(AutoCompactMemory(2L, "B", 13L, 1L, 2)))

        // While conversation 1's failed mutation is between its process
        // mutation and its recovery, conversation 2's write succeeds.
        prefs.onCommitBeforeResult {
            assertTrue(AutoCompactMemoryStore(context).save(AutoCompactMemory(2L, "B2", 13L, 1L, 2)))
        }
        // With the hook re-entrancy guard, nested commits from the hook keep
        // the scripted queue: #1 conversation 1 commit fails; #2 conversation 2
        // commit succeeds; #3 conversation 1 recovery restores only its owned
        // key.
        prefs.enqueueCommitResults(false, true, true)

        assertFalse(AutoCompactMemoryStore(context).save(AutoCompactMemory(1L, "A2", 13L, 1L, 2)))

        assertEquals("B2", AutoCompactMemoryStore(context).load(2L)?.summary)
        assertEquals("A", AutoCompactMemoryStore(context).load(1L)?.summary)
        prefs.onCommitBeforeResult(null)
    }

    @Test
    fun `framed historical representation reloads without trimming marker payload`() {
        val framed = buildString {
            append("[FUSION_AUTO_COMPACT_REPRESENTATION]\n")
            append("[FUSION_AUTO_COMPACT_PAYLOAD_LENGTH=0]\n")
            append("[VERBATIM_USER]\n")
            append("\n")
            append("[FUSION_AUTO_COMPACT_PAYLOAD_LENGTH=7]\n")
            append("[COMPACTED_COMPLETE_TURNS]\n")
            append("summary\n")
        }
        val store = AutoCompactMemoryStore(TestContext())
        val memory = AutoCompactMemory(9L, framed, 13L, 1L, 2)

        assertTrue(store.save(memory))
        assertEquals(framed, store.load(9L)?.summary)
    }

    @Test
    fun `string where exact long is required is rejected`() {
        val store = storeWithRaw(validJson().put("updatedAt", "1"))
        assertNull(store.load(7L))
    }

    @Test
    fun `fractional number where exact integral is required is rejected`() {
        val store = storeWithRaw(validJson().put("updatedAt", 1.5))
        assertNull(store.load(7L))
    }

    @Test
    fun `wrong conversation id is rejected`() {
        val store = storeWithRaw(validJson().put("conversationId", 8L))
        assertNull(store.load(7L))
    }

    @Test
    fun `wrong schema is rejected`() {
        val store = storeWithRaw(validJson().put("schemaVersion", AutoCompactionMemorySchemaTestSupport.OLD_SCHEMA))
        assertNull(store.load(7L))
    }

    @Test
    fun `blank summary is rejected`() {
        val store = storeWithRaw(validJson().put("summary", " \n "))
        assertNull(store.load(7L))
    }

    @Test
    fun `malformed framed summary is rejected`() {
        val store = storeWithRaw(
            validJson().put(
                "summary",
                "[FUSION_AUTO_COMPACT_REPRESENTATION]\nnot-a-framed-record",
            ),
        )
        assertNull(store.load(7L))
    }

    @Test
    fun `impossible recent history percentage is rejected`() {
        val store = storeWithRaw(validJson().put("recentHistoryPercent", 51))
        assertNull(store.load(7L))
    }

    @Test
    fun `absurd source turn count is rejected`() {
        val store = storeWithRaw(validJson().put("sourceTurnCount", Int.MAX_VALUE))
        assertNull(store.load(7L))
    }

    private fun validJson(): JSONObject = JSONObject().apply {
        put("conversationId", 7L)
        put("summary", "summary")
        put("compactedThroughMessageId", 13L)
        put("updatedAt", 1L)
        put("sourceTurnCount", 2)
        put("schemaVersion", 4)
        put("recentHistoryPercent", 25)
    }

    private fun storeWithRaw(value: JSONObject): AutoCompactMemoryStore {
        val prefs = AutoCompactPrefs()
        assertTrue(prefs.edit().putString("auto_compact_7", value.toString()).commit())
        return AutoCompactMemoryStore(TestContext(prefs))
    }
}

private object AutoCompactionMemorySchemaTestSupport {
    const val OLD_SCHEMA = 3
}
