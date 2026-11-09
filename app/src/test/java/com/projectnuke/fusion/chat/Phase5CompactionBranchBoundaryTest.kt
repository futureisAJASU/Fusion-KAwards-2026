package com.projectnuke.fusion.chat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.test.PlatformSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 adversarial tests: the durable compaction boundary must be
 * monotonic *within one semantic branch*, while a proven response-version
 * branch switch must be able to adopt its own valid (numerically smaller)
 * boundary without letting a late inactive-branch result reclaim ownership.
 */
class Phase5CompactionBranchBoundaryTest {

    private class TestContext(
        private val prefs: PlatformSharedPreferences = PlatformSharedPreferences(),
    ) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
    }

    private val conversationId = 11L

    // Two response-version branches of the same conversation. Each branch has
    // its own assistant answer id in the shared response-version group.
    private val branchAIds = listOf(10L, 11L, 12L, 100L)
    private val branchBIds = listOf(10L, 11L, 12L, 200L)
    private val branchAFingerprint =
        AutoCompactionBranchIdentity.fromMessageIds(branchAIds, 100L)!!
    private val branchBFingerprint =
        AutoCompactionBranchIdentity.fromMessageIds(branchBIds, 200L)!!

    private fun memory(
        summary: String,
        boundary: Long,
        fingerprint: String,
        updatedAt: Long = 1_000L,
    ) = AutoCompactMemory(
        conversationId = conversationId,
        summary = summary,
        compactedThroughMessageId = boundary,
        updatedAt = updatedAt,
        sourceTurnCount = 1,
        branchFingerprint = fingerprint,
    )

    @Test
    fun `A1 same branch stale regression is still rejected`() {
        val store = AutoCompactMemoryStore(TestContext())
        assertTrue(
            store.save(
                memory("newer B", 200L, branchBFingerprint, updatedAt = 2_000L),
                activeBranchFingerprint = branchBFingerprint,
            ),
        )
        assertFalse(
            "same branch must never regress its durable boundary",
            store.save(
                memory("stale B", 100L, branchBFingerprint, updatedAt = 1_000L),
                activeBranchFingerprint = branchBFingerprint,
            ),
        )
        assertEquals(200L, store.load(conversationId)?.compactedThroughMessageId)
        assertEquals("newer B", store.load(conversationId)?.summary)
    }

    @Test
    fun `A2 proven response-version branch replacement may lower the numeric boundary`() {
        val store = AutoCompactMemoryStore(TestContext())
        // The large assistant id belongs to branch B, the currently persisted
        // branch. The user then switches the active response version to branch
        // A whose own valid boundary is numerically smaller.
        assertTrue(
            store.save(
                memory("branch B memory", 200L, branchBFingerprint, updatedAt = 2_000L),
                activeBranchFingerprint = branchBFingerprint,
            ),
        )
        assertTrue(
            "the proven active branch must adopt its own valid memory",
            store.save(
                memory("branch A memory", 100L, branchAFingerprint, updatedAt = 3_000L),
                activeBranchFingerprint = branchAFingerprint,
            ),
        )
        val loaded = store.load(conversationId)
        assertNotNull(loaded)
        assertEquals(100L, loaded!!.compactedThroughMessageId)
        assertEquals("branch A memory", loaded.summary)
        assertEquals(branchAFingerprint, loaded.branchFingerprint)
    }

    @Test
    fun `A3 branch fingerprint distinguishes replacement from same-branch regression`() {
        assertNotEquals(branchAFingerprint, branchBFingerprint)
        val store = AutoCompactMemoryStore(TestContext())
        assertTrue(
            store.save(
                memory("branch B memory", 200L, branchBFingerprint),
                activeBranchFingerprint = branchBFingerprint,
            ),
        )
        // The memory is numerically lower but proves a different, active
        // branch: replacement is branch identity driven, not id ordering.
        assertTrue(
            store.save(
                memory("branch A memory", 100L, branchAFingerprint, updatedAt = 2_000L),
                activeBranchFingerprint = branchAFingerprint,
            ),
        )
        // A numerically larger write that does not prove the active branch is
        // still refused: numeric ordering alone never grants ownership.
        assertFalse(
            store.save(
                memory("late branch B", 200L, branchBFingerprint, updatedAt = 4_000L),
                activeBranchFingerprint = branchAFingerprint,
            ),
        )
        assertEquals(100L, store.load(conversationId)?.compactedThroughMessageId)
    }

    @Test
    fun `A4 same branch retry race still fails closed`() {
        val store = AutoCompactMemoryStore(TestContext())
        assertTrue(
            store.save(
                memory("newer branch A", 200L, branchAFingerprint, updatedAt = 2_000L),
                activeBranchFingerprint = branchAFingerprint,
            ),
        )
        // An older result belonging to the SAME branch completes late.
        assertFalse(
            store.save(
                memory("older branch A", 150L, branchAFingerprint, updatedAt = 1_000L),
                activeBranchFingerprint = branchAFingerprint,
            ),
        )
        assertEquals("newer branch A", store.load(conversationId)?.summary)
    }

    @Test
    fun `late inactive branch result cannot reclaim durable ownership`() {
        val store = AutoCompactMemoryStore(TestContext())
        assertTrue(
            store.save(
                memory("branch B memory", 200L, branchBFingerprint),
                activeBranchFingerprint = branchBFingerprint,
            ),
        )
        assertTrue(
            store.save(
                memory("branch A memory", 100L, branchAFingerprint, updatedAt = 2_000L),
                activeBranchFingerprint = branchAFingerprint,
            ),
        )
        // Branch B's compaction was started before the switch and completes
        // late. Its numeric boundary is larger, but it is not the active
        // branch any more.
        assertFalse(
            store.save(
                memory("late branch B", 200L, branchBFingerprint, updatedAt = 3_000L),
                activeBranchFingerprint = branchAFingerprint,
            ),
        )
        assertEquals("branch A memory", store.load(conversationId)?.summary)
    }

    @Test
    fun `response version switch back is a valid replacement again`() {
        val store = AutoCompactMemoryStore(TestContext())
        assertTrue(
            store.save(memory("A100", 100L, branchAFingerprint), activeBranchFingerprint = branchAFingerprint),
        )
        assertTrue(
            store.save(
                memory("B200", 200L, branchBFingerprint, updatedAt = 2_000L),
                activeBranchFingerprint = branchBFingerprint,
            ),
        )
        assertTrue(
            store.save(
                memory("A150", 150L, branchAFingerprint, updatedAt = 3_000L),
                activeBranchFingerprint = branchAFingerprint,
            ),
        )
        assertEquals("A150", store.load(conversationId)?.summary)
    }

    @Test
    fun `equal boundary refresh on the active branch is still accepted`() {
        val store = AutoCompactMemoryStore(TestContext())
        assertTrue(
            store.save(memory("first", 100L, branchAFingerprint), activeBranchFingerprint = branchAFingerprint),
        )
        assertTrue(
            store.save(
                memory("refresh", 100L, branchAFingerprint, updatedAt = 2_000L),
                activeBranchFingerprint = branchAFingerprint,
            ),
        )
        assertEquals("refresh", store.load(conversationId)?.summary)
    }

    @Test
    fun `unproven branch replacement keeps the legacy fail-closed guard`() {
        val store = AutoCompactMemoryStore(TestContext())
        assertTrue(store.save(memory("branch B memory", 200L, branchBFingerprint)))
        assertFalse(
            "without a proven active branch identity a lower boundary must not replace",
            store.save(memory("branch A memory", 100L, branchAFingerprint, updatedAt = 2_000L)),
        )
        assertEquals("branch B memory", store.load(conversationId)?.summary)
    }

    @Test
    fun `active proof of a foreign boundary is the full active branch, not null`() {
        // The live timeline is branch A; the candidate is branch B whose
        // boundary id 200 is not in it. A null proof would reopen the numeric
        // path, so the seam must prove the whole active branch instead.
        val proof = AutoCompactionBranchIdentity.activeProofFor(
            currentMessageIds = branchAIds,
            candidate = memory("late branch B", 200L, branchBFingerprint),
        )
        assertNotNull(proof)
        assertNotEquals(branchBFingerprint, proof)

        val store = AutoCompactMemoryStore(TestContext())
        assertTrue(store.save(memory("branch A memory", 100L, branchAFingerprint)))
        assertFalse(
            "a late inactive candidate must be refused even when numerically larger",
            store.save(memory("late branch B", 200L, branchBFingerprint, updatedAt = 2_000L), proof),
        )
        assertEquals("branch A memory", store.load(conversationId)?.summary)
    }

    @Test
    fun `active proof of the live boundary is its own branch fingerprint`() {
        val proof = AutoCompactionBranchIdentity.activeProofFor(
            currentMessageIds = branchAIds,
            candidate = memory("branch A memory", 100L, branchAFingerprint),
        )
        assertEquals(branchAFingerprint, proof)

        val store = AutoCompactMemoryStore(TestContext())
        assertTrue(store.save(memory("branch A memory", 100L, branchAFingerprint), proof))
        assertEquals(branchAFingerprint, store.load(conversationId)?.branchFingerprint)
    }
}
