package com.projectnuke.fusion.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.test.PlatformSharedPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private typealias FatalFailureInMemoryPrefs = PlatformSharedPreferences

/**
 * Fatal-failure contract for response-version parsing and post-commit
 * witness cleanup:
 *
 *  - malformed durable input stays CORRUPT, bounded overflow stays
 *    OVERSIZED,
 *  - a fatal runtime Error at the JSON document boundary is NOT evidence of
 *    corrupt durable input and must propagate, never become Corrupt (which
 *    would turn a fatal load into an empty previous state that regeneration
 *    can then save over real durable grouping),
 *  - post-commit witness deletion failure stays best-effort for ordinary
 *    Exceptions but a fatal Error must propagate without rolling back the
 *    already durable message/state.
 */
class ResponseVersionFatalFailureRegressionTest {

    private class TestContext(private val prefs: FatalFailureInMemoryPrefs) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
    }

    // P3.1: malformed JSON remains Corrupt.
    @Test
    fun `malformed JSON remains corrupt`() {
        assertTrue(parseResponseVersionState("{not-valid-json") is ResponseVersionLoad.Corrupt)
    }

    // P3.2: malformed map identity remains Corrupt.
    @Test
    fun `malformed map identity remains corrupt`() {
        assertTrue(
            parseResponseVersionState("""{"groups":{"not-a-number":106},"active":{}}""")
                is ResponseVersionLoad.Corrupt,
        )
        assertTrue(
            parseResponseVersionState("""{"groups":{"5":1.5,"6":106},"active":{"106":6}}""")
                is ResponseVersionLoad.Corrupt,
        )
    }

    // P3.3: explicit bounded-overflow conditions remain Oversized.
    @Test
    fun `oversized remains oversized`() {
        assertTrue(
            parseResponseVersionState("x".repeat(MAX_RESPONSE_VERSION_BYTES + 1))
                is ResponseVersionLoad.Oversized,
        )
        val groups = JSONObject()
        repeat(MAX_RESPONSE_VERSION_ENTRIES_PER_MAP + 1) { index -> groups.put(index.toString(), index + 1) }
        assertTrue(
            parseResponseVersionState(JSONObject().put("groups", groups).toString())
                is ResponseVersionLoad.Oversized,
        )
    }

    // P3.4a: a fatal parser failure at the document boundary propagates and
    // must not be classified as Corrupt.
    @Test
    fun `fatal parser linkage error propagates instead of corrupt`() {
        val original = responseVersionDocumentFactory
        try {
            responseVersionDocumentFactory = {
                throw LinkageError("simulated fatal document parse failure")
            }
            try {
                parseResponseVersionState("""{"groups":{"5":6}}""")
                fail("expected LinkageError to propagate")
            } catch (_: LinkageError) {
                // expected: fatal propagates, never becomes Corrupt
            }
        } finally {
            responseVersionDocumentFactory = original
        }
    }

    // P3.4b: a fatal failure while ACCESSING the strict map propagates and
    // must not be reclassified as an unreadable (corrupt) value.
    @Test
    fun `fatal map access linkage error propagates instead of corrupt`() {
        val original = responseVersionDocumentFactory
        try {
            // Mirrors the raw document {"groups":{"5":6}} with the NESTED
            // groups object (the strict map) made fatal on value access.
            responseVersionDocumentFactory = { _ ->
                val fatalGroups = object : JSONObject(mapOf("5" to 6)) {
                    override fun get(key: String): Any =
                        throw LinkageError("simulated fatal map access failure")
                }
                JSONObject(mapOf("groups" to fatalGroups))
            }
            try {
                parseResponseVersionState("""{"groups":{"5":6}}""")
                fail("expected LinkageError to propagate")
            } catch (_: LinkageError) {
                // expected: fatal propagates, never becomes Corrupt
            }
        } finally {
            responseVersionDocumentFactory = original
        }
    }

    // P3.5: a fatal load cannot be converted into an empty previous state
    // and then saved as replacement state: no insert and no save may occur
    // after the fatal load, and the durable state is left untouched.
    @Test
    fun `fatal load stops the regeneration save`() = runBlocking {
        val prefs = FatalFailureInMemoryPrefs()
        val existing = """{"groups":{"5":6},"active":{"6":5}}"""
        prefs.values["41"] = existing
        val context = TestContext(prefs)
        val events = mutableListOf<String>()
        val original = responseVersionDocumentFactory
        try {
            responseVersionDocumentFactory = {
                throw LinkageError("simulated fatal load failure")
            }
            try {
                persistAssistantVersion<ResponseVersionState>(
                    loadPreviousState = { loadResponseVersionState(context, 41L) },
                    buildCommitPlan = { ResponseVersionCommitPlan(5L, 6L) },
                    insertMessage = { events += "insert"; 9L },
                    buildUpdatedState = { previous, _, id ->
                        previous.copy(groupByMessageId = previous.groupByMessageId + (id to 6L))
                    },
                    saveState = { events += "save"; saveResponseVersionState(context, 41L, it) },
                    restoreState = { events += "restore" },
                    deleteMessage = { events += "delete:$it" },
                    updateConversationTimestamp = { events += "timestamp" },
                )
                fail("expected LinkageError to propagate")
            } catch (_: LinkageError) {
                // expected: fatal load propagates
            }
        } finally {
            responseVersionDocumentFactory = original
        }

        assertTrue("no write may follow a fatal load, got $events", events.isEmpty())
        assertEquals(existing, prefs.values["41"])
    }

    // P3.6: an ordinary RuntimeException from post-commit witness deletion
    // stays best-effort: the committed message/state are kept and the
    // bounded failure callback is invoked.
    @Test
    fun `ordinary witness deletion failure stays best effort after commit`() = runBlocking {
        val events = mutableListOf<String>()
        var reported: Throwable? = null
        val expected = RuntimeException("witness cleanup failed")

        val persisted = persistAssistantVersion(
            loadPreviousState = { "old" },
            buildCommitPlan = { ResponseVersionCommitPlan(11L, 7L) },
            insertMessage = { events += "insert"; 13L },
            buildUpdatedState = { previous, _, id -> "$previous+$id" },
            saveState = { events += "save:$it" },
            restoreState = { events += "restore:$it" },
            deleteMessage = { events += "delete:$it" },
            deleteWitness = { events += "witness:$it"; throw expected },
            updateConversationTimestamp = { events += "timestamp" },
            onWitnessDeletionFailure = { reported = it },
        )

        assertEquals(13L, persisted.messageId)
        assertEquals("old+13", persisted.state)
        assertEquals(listOf("insert", "save:old+13", "timestamp", "witness:13"), events)
        assertEquals(expected, reported)
        assertTrue(events.none { it.startsWith("delete") || it.startsWith("restore") })
    }

    // P3.7: a fatal Error from post-commit witness deletion propagates; the
    // already committed message/state are NOT rolled back and the cleanup is
    // neither reported as a bounded failure nor as success.
    @Test
    fun `fatal witness deletion failure propagates without rollback`() = runBlocking {
        val events = mutableListOf<String>()
        var reported: Throwable? = null

        try {
            persistAssistantVersion(
                loadPreviousState = { "old" },
                buildCommitPlan = { ResponseVersionCommitPlan(11L, 7L) },
                insertMessage = { events += "insert"; 14L },
                buildUpdatedState = { previous, _, id -> "$previous+$id" },
                saveState = { events += "save:$it" },
                restoreState = { events += "restore:$it" },
                deleteMessage = { events += "delete:$it" },
                deleteWitness = { events += "witness:$it"; throw LinkageError("witness cleanup fatal") },
                updateConversationTimestamp = { events += "timestamp" },
                onWitnessDeletionFailure = { reported = it },
            )
            fail("expected LinkageError to propagate")
        } catch (_: LinkageError) {
            // expected: fatal propagates
        }

        assertEquals(listOf("insert", "save:old+14", "timestamp", "witness:14"), events)
        assertNull("cleanup must not be reported as a bounded failure", reported)
        assertTrue(events.none { it.startsWith("delete") || it.startsWith("restore") })
    }
}
