package com.projectnuke.fusion.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.test.PlatformSharedPreferences
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias InMemoryPrefs = PlatformSharedPreferences

class ResponseVersionStoreTest {

    private class TestContext(private val prefs: InMemoryPrefs) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun `fake commit failure mutates process memory but not durable map`() {
        val prefs = InMemoryPrefs()
        assertTrue(prefs.edit().putString("state", "A").commit())

        prefs.commitResult = false
        assertFalse(prefs.edit().putString("state", "B").commit())

        assertEquals("B", prefs.values["state"])
        assertEquals("A", prefs.durableValues["state"])
    }

    @Test
    fun `deleted conversation navigation writes no response-version metadata`() = runTest {
        val prefs = InMemoryPrefs()
        val context = TestContext(prefs)
        val original = ResponseVersionState(
            groupByMessageId = mapOf(1L to 2L, 2L to 2L),
            activeMessageIdByGroup = mapOf(2L to 2L),
        )
        assertTrue(saveResponseVersionState(context, 33L, original))
        val before = prefs.values["33"]

        val result = moveResponseVersionDurably(
            context = context,
            conversationId = 33L,
            groupId = 2L,
            expectedOrderedMessageIds = listOf(1L, 2L),
            direction = ResponseVersionDirection.PREVIOUS,
            validateConversationLive = { false },
        )

        assertNull(result)
        assertEquals(before, prefs.values["33"])
    }

    @Test
    fun `corrupt entry loads empty fallback and save quarantines before replacing`() {
        val prefs = InMemoryPrefs()
        prefs.values["7"] = "{corrupt-json"
        val context = TestContext(prefs)

        assertEquals(ResponseVersionState(), loadResponseVersionState(context, 7L))
        assertTrue(loadResponseVersionStateResult(context, 7L) is ResponseVersionLoad.Corrupt)

        val fresh = ResponseVersionState(mapOf(11L to 100L), mapOf(100L to 11L))
        assertTrue(saveResponseVersionState(context, 7L, fresh))
        assertEquals("{corrupt-json", prefs.values["7.corrupt.bak"])
        assertEquals(fresh, loadResponseVersionState(context, 7L))
    }

    @Test
    fun `oversized raw state loads empty without quarantine or replacement`() {
        val prefs = InMemoryPrefs()
        val oversized = "x".repeat(MAX_RESPONSE_VERSION_BYTES + 1)
        prefs.values["7"] = oversized
        val context = TestContext(prefs)

        assertTrue(loadResponseVersionStateResult(context, 7L) is ResponseVersionLoad.Oversized)
        assertEquals(ResponseVersionState(), loadResponseVersionState(context, 7L))
        assertFalse(saveResponseVersionState(context, 7L, ResponseVersionState(mapOf(1L to 2L), emptyMap())))
        assertEquals(oversized, prefs.values["7"])
        assertFalse(prefs.values.containsKey("7.corrupt.bak"))

        var threw = false
        try {
            saveResponseVersionStateDurably(context, 7L, ResponseVersionState(mapOf(1L to 2L), emptyMap()))
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(oversized, prefs.values["7"])
    }

    @Test
    fun `response version UTF-8 byte cap rejects before JSON parsing`() {
        val raw = "é".repeat(MAX_RESPONSE_VERSION_BYTES / 2 + 1)
        assertTrue(raw.length <= MAX_RESPONSE_VERSION_BYTES)
        assertTrue(parseResponseVersionState(raw) is ResponseVersionLoad.Oversized)
    }

    @Test
    fun `groups and active entry bounds classify independently as oversized`() {
        val groups = JSONObject()
        repeat(MAX_RESPONSE_VERSION_ENTRIES_PER_MAP + 1) { index -> groups.put(index.toString(), index + 1) }
        val active = JSONObject().put("1", 1)
        assertTrue(
            parseResponseVersionState(JSONObject().put("groups", groups).put("active", active).toString())
                is ResponseVersionLoad.Oversized,
        )

        val activeOverflow = JSONObject()
        repeat(MAX_RESPONSE_VERSION_ENTRIES_PER_MAP + 1) { index -> activeOverflow.put(index.toString(), index + 1) }
        assertTrue(
            parseResponseVersionState(JSONObject().put("groups", JSONObject()).put("active", activeOverflow).toString())
                is ResponseVersionLoad.Oversized,
        )
    }

    @Test
    fun `writer rejects oversized map without partial save`() {
        val prefs = InMemoryPrefs()
        val context = TestContext(prefs)
        val oversized = ResponseVersionState(
            groupByMessageId = (0..MAX_RESPONSE_VERSION_ENTRIES_PER_MAP).associate { it.toLong() to (it + 1).toLong() },
        )

        assertFalse(saveResponseVersionState(context, 7L, oversized))
        assertFalse(prefs.values.containsKey("7"))
        var threw = false
        try {
            saveResponseVersionStateDurably(context, 7L, oversized)
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        assertFalse(prefs.values.containsKey("7"))
    }

    @Test
    fun `non-integral identity makes the whole state corrupt`() {
        val prefs = InMemoryPrefs()
        prefs.values["7"] = """{"groups":{"5":1.5,"6":106},"active":{"106":6}}"""
        val context = TestContext(prefs)

        // R4: skipping the malformed pair would silently rewrite durable
        // response-version evidence; one malformed entry corrupts the whole
        // conversation version state instead.
        assertTrue(loadResponseVersionStateResult(context, 7L) is ResponseVersionLoad.Corrupt)
        assertEquals(ResponseVersionState(), loadResponseVersionState(context, 7L))
    }

    @Test
    fun `malformed identity key and wrong map type are corrupt`() {
        val prefs = InMemoryPrefs()
        prefs.values["1"] = """{"groups":{"not-a-number":106},"active":{}}"""
        prefs.values["2"] = """{"groups":["not","an","object"],"active":{}}"""
        prefs.values["3"] = """{"groups":{},"active":"not-an-object"}"""
        val context = TestContext(prefs)

        assertTrue(loadResponseVersionStateResult(context, 1L) is ResponseVersionLoad.Corrupt)
        assertTrue(loadResponseVersionStateResult(context, 2L) is ResponseVersionLoad.Corrupt)
        assertTrue(loadResponseVersionStateResult(context, 3L) is ResponseVersionLoad.Corrupt)
    }

    @Test
    fun `semantically corrupt state is quarantined like syntactic corruption`() {
        val prefs = InMemoryPrefs()
        val semanticallyCorrupt = """{"groups":{"5":1.5,"6":106},"active":{"106":6}}"""
        prefs.values["7"] = semanticallyCorrupt
        val context = TestContext(prefs)

        val fresh = ResponseVersionState(mapOf(11L to 100L), mapOf(100L to 11L))
        assertTrue(saveResponseVersionState(context, 7L, fresh))
        assertEquals(semanticallyCorrupt, prefs.values["7.corrupt.bak"])
        assertEquals(fresh, loadResponseVersionState(context, 7L))
    }

    @Test
    fun `failed quarantine leaves the corrupt original untouched`() {
        val prefs = InMemoryPrefs(commitResult = false)
        val corrupt = """{"groups":{"5":1.5}}"""
        prefs.values["7"] = corrupt
        val context = TestContext(prefs)

        // Neither save path may overwrite CORRUPT state without quarantine.
        assertFalse(saveResponseVersionState(context, 7L, ResponseVersionState(mapOf(1L to 2L), emptyMap())))
        assertEquals(corrupt, prefs.values["7"])
        assertFalse(prefs.values.containsKey("7.corrupt.bak"))

        var threw = false
        try {
            saveResponseVersionStateDurably(context, 7L, ResponseVersionState(mapOf(1L to 2L), emptyMap()))
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(corrupt, prefs.values["7"])
    }

    @Test
    fun `valid state round trips and legacy missing fields stay compatible`() {
        val prefs = InMemoryPrefs()
        val context = TestContext(prefs)

        val state = ResponseVersionState(mapOf(11L to 100L, 12L to 101L), mapOf(100L to 12L))
        assertTrue(saveResponseVersionState(context, 7L, state))
        assertEquals(state, loadResponseVersionState(context, 7L))

        // Older writers may omit a map: absence means empty, never corrupt.
        prefs.values["8"] = """{"groups":{"5":6}}"""
        assertEquals(
            ResponseVersionState(mapOf(5L to 6L), emptyMap()),
            loadResponseVersionState(context, 8L),
        )
    }

    @Test
    fun `durable save still reports commit failure truthfully`() {
        val prefs = InMemoryPrefs(commitResult = false)
        val context = TestContext(prefs)
        var threw = false
        try {
            saveResponseVersionStateDurably(context, 7L, ResponseVersionState(mapOf(1L to 2L), emptyMap()))
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `boolean save reports commit failure for missing state`() {
        val prefs = InMemoryPrefs(commitResult = false)
        val context = TestContext(prefs)

        assertFalse(saveResponseVersionState(context, 7L, ResponseVersionState(mapOf(1L to 2L), emptyMap())))
        assertFalse(prefs.values.containsKey("7"))
    }

    @Test
    fun `boolean save reports commit failure and fails closed when restoration fails`() {
        val prefs = InMemoryPrefs()
        val context = TestContext(prefs)
        val before = ResponseVersionState(mapOf(1L to 2L), emptyMap())
        assertTrue(saveResponseVersionState(context, 7L, before))
        val durableBefore = prefs.durableValues["7"]

        prefs.commitResult = false
        assertFalse(saveResponseVersionState(context, 7L, ResponseVersionState(mapOf(3L to 4L), emptyMap())))
        assertEquals(ResponseVersionState(), loadResponseVersionState(context, 7L))
        assertTrue(loadResponseVersionStateResult(context, 7L) is ResponseVersionLoad.Corrupt)
        assertEquals(durableBefore, prefs.durableValues["7"])
    }

    @Test
    fun `missing entry loads empty state`() {
        val context = TestContext(InMemoryPrefs())
        assertEquals(ResponseVersionState(), loadResponseVersionState(context, 7L))
        assertTrue(loadResponseVersionStateResult(context, 7L) is ResponseVersionLoad.Missing)
    }

    @Test
    fun `conversation 7 failed recovery cannot roll back conversation 8 successful save`() {
        val prefs = InMemoryPrefs()
        val context = TestContext(prefs)
        val s7 = ResponseVersionState(mapOf(1L to 100L), mapOf(100L to 1L))
        val s8 = ResponseVersionState(mapOf(2L to 200L), mapOf(200L to 2L))
        val s8v2 = ResponseVersionState(mapOf(3L to 300L), mapOf(300L to 3L))
        assertTrue(saveResponseVersionState(context, 7L, s7))
        assertTrue(saveResponseVersionState(context, 8L, s8))

        // While conversation 7's failed save is between its process mutation
        // and its recovery, conversation 8's save succeeds.
        prefs.onCommitBeforeResult {
            assertTrue(saveResponseVersionState(context, 8L, s8v2))
        }
        // With the hook re-entrancy guard, nested commits from the hook keep
        // the scripted queue: #1 conversation 7 commit fails; #2 conversation 8
        // succeeds; #3 conversation 7 recovery restores ONLY its owned keys
        // (7 and the quarantine key), never touching conversation 8.
        prefs.enqueueCommitResults(false, true, true)

        assertFalse(saveResponseVersionState(context, 7L, ResponseVersionState(mapOf(9L to 900L), mapOf(900L to 9L))))

        assertEquals(s7, loadResponseVersionState(context, 7L))
        assertEquals(s8v2, loadResponseVersionState(context, 8L))
        prefs.onCommitBeforeResult(null)
    }

    @Test
    fun `durable previous and next moves keep exact integral identities`() {
        val current = ResponseVersionState(
            groupByMessageId = mapOf(1L to 10L, 2L to 10L, 3L to 10L),
            activeMessageIdByGroup = mapOf(10L to 2L),
        )
        val previous = moveResponseVersionFromDurableState(
            current,
            10L,
            listOf(1L, 2L, 3L),
            ResponseVersionDirection.PREVIOUS,
        )
        assertEquals(1L, previous?.activeMessageIdByGroup?.get(10L))
        val next = moveResponseVersionFromDurableState(
            previous!!,
            10L,
            listOf(1L, 2L, 3L),
            ResponseVersionDirection.NEXT,
        )
        assertEquals(2L, next?.activeMessageIdByGroup?.get(10L))
    }
}
