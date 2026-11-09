package com.projectnuke.fusion.ui

import com.projectnuke.fusion.audit.ResearchDraftClaimFindingStatus
import com.projectnuke.fusion.audit.ResearchInterventionAction
import com.projectnuke.fusion.audit.ResearchInterventionAuditClaimRecord
import com.projectnuke.fusion.audit.ResearchInterventionAuditRecord
import com.projectnuke.fusion.audit.ResearchInterventionReason
import com.projectnuke.fusion.test.PlatformSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchInterventionAuditStoreTest {
    @Test
    fun `saved audit is bound to the exact message id and answer hash`() {
        val prefs = PlatformSharedPreferences()
        val store = ResearchInterventionAuditStore(prefs)
        val record = record(messageId = 42L, conversationId = 71L)

        assertTrue(store.save(record))
        assertEquals(record, store.load(42L, record.answerSha256))
        assertNull(store.load(42L, "c".repeat(64)))
        assertNull(store.load(43L, record.answerSha256))
    }

    @Test
    fun `conversation scoped load returns only that conversation`() {
        val prefs = PlatformSharedPreferences()
        val store = ResearchInterventionAuditStore(prefs)
        store.save(record(messageId = 42L, conversationId = 71L))
        store.save(record(messageId = 43L, conversationId = 72L))

        val scoped = store.loadForConversation(71L)

        assertEquals(1, scoped.size)
        assertEquals(42L, scoped.single().messageId)
    }

    @Test
    fun `failed commit restores the previous value and marks the key durability unknown`() {
        val prefs = PlatformSharedPreferences()
        val store = ResearchInterventionAuditStore(prefs)
        val first = record(messageId = 42L, conversationId = 71L)
        assertTrue(store.save(first))

        prefs.commitResult = false
        val second = first.copy(savedAt = 2_000L, answerSha256 = "d".repeat(64))
        assertFalse(store.save(second))

        // The failed mutation is rolled back to the previously stored value and
        // the key is marked durability-unknown, so no stale verdict is shown.
        val restored = com.projectnuke.fusion.audit.ResearchInterventionAuditCodec.decode(
            prefs.getString(ResearchInterventionAuditStore.keyFor(42L), null) ?: "",
        )
        assertEquals(first, restored)
        assertNull(store.load(42L, first.answerSha256))
        assertNull(store.load(42L, second.answerSha256))
    }

    @Test
    fun `successful save after failed commit clears unknown state for the key`() {
        val prefs = PlatformSharedPreferences()
        val store = ResearchInterventionAuditStore(prefs)
        val original = record(messageId = 42L, conversationId = 71L)
        assertTrue(store.save(original))

        prefs.commitResult = false
        val failed = original.copy(savedAt = 2_000L, answerSha256 = "c".repeat(64))
        assertFalse(store.save(failed))
        assertNull(store.load(42L, original.answerSha256))

        prefs.commitResult = true
        val recovered = failed.copy(savedAt = 3_000L, answerSha256 = "d".repeat(64))
        assertTrue(store.save(recovered))

        assertEquals(recovered, store.load(42L, recovered.answerSha256))
        assertEquals(listOf(recovered), store.loadForConversation(71L))
    }

    @Test
    fun `updating a full audit store does not prune an unrelated record`() {
        val prefs = PlatformSharedPreferences()
        val store = ResearchInterventionAuditStore(prefs)
        repeat(200) { index ->
            assertTrue(store.save(record(messageId = index + 1L, conversationId = 71L).copy(savedAt = index.toLong())))
        }
        val updated = record(messageId = 200L, conversationId = 71L)
            .copy(savedAt = 500L, answerSha256 = "e".repeat(64))

        assertTrue(store.save(updated))

        assertEquals(200, store.loadForConversation(71L).size)
        assertEquals(1L, store.loadForConversation(71L).minOf { it.messageId })
        assertEquals(updated, store.load(200L, updated.answerSha256))
    }

    @Test
    fun `inserting a new audit into a full store still prunes only the oldest entry`() {
        val prefs = PlatformSharedPreferences()
        val store = ResearchInterventionAuditStore(prefs)
        repeat(200) { index ->
            assertTrue(store.save(record(messageId = index + 1L, conversationId = 71L).copy(savedAt = index.toLong())))
        }

        assertTrue(store.save(record(messageId = 201L, conversationId = 71L).copy(savedAt = 201L)))

        val records = store.loadForConversation(71L)
        assertEquals(200, records.size)
        assertNull(store.load(1L, "a".repeat(64)))
        assertTrue(records.any { it.messageId == 201L })
    }

    @Test
    fun `remove deletes only the requested message audit`() {
        val prefs = PlatformSharedPreferences()
        val store = ResearchInterventionAuditStore(prefs)
        store.save(record(messageId = 42L, conversationId = 71L))
        store.save(record(messageId = 43L, conversationId = 71L))

        assertTrue(store.remove(42L))

        assertNull(store.load(42L, "a".repeat(64)))
        assertEquals(1, store.loadForConversation(71L).size)
    }

    @Test
    fun `orphan cleanup removes audits without a live message row`() {
        val prefs = PlatformSharedPreferences()
        val store = ResearchInterventionAuditStore(prefs)
        store.save(record(messageId = 42L, conversationId = 71L))
        store.save(record(messageId = 43L, conversationId = 71L))
        store.save(record(messageId = 44L, conversationId = 72L))

        assertTrue(store.removeOrphansForConversation(71L, setOf(42L)))

        assertEquals(listOf(42L), store.loadForConversation(71L).map { it.messageId })
        assertEquals(listOf(44L), store.loadForConversation(72L).map { it.messageId })
    }

    @Test
    fun `conversation deletion clears every audit through the shared cleanup operation`() {
        val prefs = PlatformSharedPreferences()
        val store = ResearchInterventionAuditStore(prefs)
        store.save(record(messageId = 42L, conversationId = 71L))
        store.save(record(messageId = 43L, conversationId = 71L))
        store.save(record(messageId = 44L, conversationId = 72L))

        assertTrue(store.removeConversation(71L))

        assertTrue(store.loadForConversation(71L).isEmpty())
        assertEquals(listOf(44L), store.loadForConversation(72L).map { it.messageId })
    }

    @Test
    fun `failed orphan cleanup rolls back and hides durability unknown audit`() {
        val prefs = PlatformSharedPreferences()
        val store = ResearchInterventionAuditStore(prefs)
        store.save(record(messageId = 42L, conversationId = 71L))
        store.save(record(messageId = 43L, conversationId = 71L))
        prefs.commitResult = false

        assertFalse(store.removeOrphansForConversation(71L, setOf(42L)))

        assertTrue(prefs.contains(ResearchInterventionAuditStore.keyFor(43L)))
        assertEquals(listOf(42L), store.loadForConversation(71L).map { it.messageId })
    }

    private fun record(messageId: Long, conversationId: Long) = ResearchInterventionAuditRecord(
        messageId = messageId,
        conversationId = conversationId,
        answerSha256 = "a".repeat(64),
        savedAt = 1_000L,
        action = ResearchInterventionAction.DELIVER_WITH_SCOPED_AUDIT,
        reason = ResearchInterventionReason.SUPPORTED_CLAIMS_COVERAGE_INCOMPLETE,
        wholeAnswerCertified = false,
        claimScopedOnly = true,
        revisionAttempted = false,
        retrievalAttempted = false,
        claims = listOf(
            ResearchInterventionAuditClaimRecord(
                claimId = "AC1",
                claimText = "Acme released Alpha in 2024.",
                claimTextSha256 = "b".repeat(64),
                status = ResearchDraftClaimFindingStatus.SUPPORTED,
                evidence = emptyList(),
            ),
        ),
    )
}
