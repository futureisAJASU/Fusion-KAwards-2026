package com.projectnuke.fusion.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.MainDispatcherIsolationRule
import com.projectnuke.fusion.chat.PendingAttachmentIdentity
import com.projectnuke.fusion.chat.cancelForTest
import com.projectnuke.fusion.data.ChatDao
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.MessageContentHit
import com.projectnuke.fusion.data.MessageContentPageRow
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.MessageIdentity
import com.projectnuke.fusion.data.ResponseVersionCommitWitnessDao
import com.projectnuke.fusion.data.ResponseVersionCommitWitnessEntity
import com.projectnuke.fusion.data.SubmissionCommitWitnessCodec
import com.projectnuke.fusion.data.SubmissionCommitWitnessDao
import com.projectnuke.fusion.data.SubmissionCommitWitnessEntity
import com.projectnuke.fusion.test.PlatformSharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * Exception-classification contract for startup crash recovery:
 *
 *  - CancellationException at any recovery boundary propagates immediately,
 *  - an ordinary Exception keeps the existing fail-safe log / preserve
 *    witness / retry-later behavior,
 *  - a fatal Error propagates instead of being logged and recovered past.
 *
 * The recovery decisions themselves (what is deleted, settled, or preserved)
 * are unchanged; only the exception classification is under test here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashConsistencyRecoveryFatalFailureTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule { scheduler ->
        UnconfinedTestDispatcher(scheduler)
    }

    private class TestContext : ContextWrapper(null)

    private class FailingSubmissionWitnessDao(
        private val witnesses: List<SubmissionCommitWitnessEntity>,
    ) : SubmissionCommitWitnessDao {
        var getBatchFailure: Throwable? = null
        var deleted = false

        override suspend fun insert(witness: SubmissionCommitWitnessEntity) = Unit

        override suspend fun get(token: String): SubmissionCommitWitnessEntity? =
            witnesses.firstOrNull { it.token == token }

        override suspend fun getBatch(limit: Int): List<SubmissionCommitWitnessEntity> {
            getBatchFailure?.let { throw it }
            return witnesses.take(limit)
        }

        override suspend fun getBatchAfter(
            createdAt: Long,
            token: String,
            limit: Int,
        ): List<SubmissionCommitWitnessEntity> {
            getBatchFailure?.let { throw it }
            return witnesses
                .sortedWith(compareBy({ it.createdAt }, { it.token }))
                .dropWhile { it.createdAt < createdAt || (it.createdAt == createdAt && it.token <= token) }
                .take(limit)
        }

        override suspend fun delete(token: String): Int {
            if (witnesses.any { it.token == token }) deleted = true
            return 1
        }
    }

    private class FailingResponseWitnessDao(
        private val witnesses: List<ResponseVersionCommitWitnessEntity>,
    ) : ResponseVersionCommitWitnessDao {
        var getBatchFailure: Throwable? = null
        var messageLookupFailure: (Long) -> Throwable? = { null }
        var deleteFailure: (Long) -> Throwable? = { null }
        val messages = mutableMapOf<Long, MessageEntity>()
        val messageLookups = mutableListOf<Long>()
        val deletedMessageIds = mutableListOf<Long>()

        override suspend fun insert(witness: ResponseVersionCommitWitnessEntity) = Unit

        override suspend fun getBatch(limit: Int): List<ResponseVersionCommitWitnessEntity> {
            getBatchFailure?.let { throw it }
            return witnesses.take(limit)
        }

        override suspend fun getBatchAfter(
            createdAt: Long,
            messageId: Long,
            limit: Int,
        ): List<ResponseVersionCommitWitnessEntity> {
            getBatchFailure?.let { throw it }
            return witnesses
                .sortedWith(compareBy({ it.createdAt }, { it.messageId }))
                .dropWhile { it.createdAt < createdAt || (it.createdAt == createdAt && it.messageId <= messageId) }
                .take(limit)
        }

        override suspend fun getByMessageId(messageId: Long): ResponseVersionCommitWitnessEntity? =
            witnesses.firstOrNull { it.messageId == messageId }

        override suspend fun delete(messageId: Long): Int {
            deleteFailure(messageId)?.let { throw it }
            deletedMessageIds += messageId
            return 1
        }

        override suspend fun getMessageById(messageId: Long): MessageEntity? {
            messageLookups += messageId
            messageLookupFailure(messageId)?.let { throw it }
            return messages[messageId]
        }
    }

    private class FakePrefsContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
    }

    private class TrackingChatDao : ChatDao {
        val conversationLookups = mutableListOf<Long>()
        val conversations = mutableMapOf<Long, ConversationEntity>()

        override suspend fun getConversationById(conversationId: Long): ConversationEntity? {
            conversationLookups += conversationId
            return conversations[conversationId]
        }
        override suspend fun getMessageIdentityById(messageId: Long): com.projectnuke.fusion.data.MessageIdentity? = null
        override suspend fun getMessageById(messageId: Long): MessageEntity? = null

        override suspend fun insertConversation(conversation: ConversationEntity): Long = 0L
        override suspend fun insertMessage(message: MessageEntity): Long = 0L
        override fun observeConversations(): Flow<List<ConversationEntity>> = emptyFlow()
        override fun observeConversationsLimited(limit: Int): Flow<List<ConversationEntity>> = emptyFlow()
        override fun observeArchivedConversations(): Flow<List<ConversationEntity>> = emptyFlow()
        override fun observeArchivedConversationsLimited(limit: Int): Flow<List<ConversationEntity>> = emptyFlow()
        override fun observeConversationIdsMatchingMessages(query: String): Flow<List<Long>> = emptyFlow()
        override fun observeConversationIdsMatchingMessagesLimited(query: String, limit: Int): Flow<List<Long>> = emptyFlow()
        override fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> = emptyFlow()
        override fun observeMessagesLatestLimited(conversationId: Long, limit: Int): Flow<List<MessageEntity>> = emptyFlow()
        override fun observeMessageCountForConversation(conversationId: Long): Flow<Int> = emptyFlow()
        override fun observeMessageIdentitiesForConversation(conversationId: Long): Flow<List<MessageIdentity>> = emptyFlow()
        override suspend fun getMessagesForConversation(conversationId: Long): List<MessageEntity> = emptyList()
        override suspend fun getMessageIdentitiesForConversation(conversationId: Long): List<MessageIdentity> = emptyList()
        override suspend fun getMessagesLatestLimited(conversationId: Long, limit: Int): List<MessageEntity> = emptyList()
        override suspend fun getAllMessageContents(): List<String> = emptyList()
        override suspend fun getMessageContentPage(afterId: Long, limit: Int): List<MessageContentPageRow> = emptyList()
        override suspend fun getLatestConversation(): ConversationEntity? = null
        override suspend fun getConversationCount(): Int = 0
        override suspend fun getMessageCount(): Int = 0
        override suspend fun getArchivedConversationCount(): Int = 0
        override suspend fun getMessageCountForConversation(conversationId: Long): Int = 0
        override suspend fun updateConversationTime(conversationId: Long, updatedAt: Long) = Unit
        override suspend fun updateMessageContent(messageId: Long, content: String) = Unit
        override suspend fun updateConversationPinned(conversationId: Long, isPinned: Boolean) = Unit
        override suspend fun updateConversationTitle(conversationId: Long, title: String) = Unit
        override suspend fun archiveConversation(conversationId: Long) = Unit
        override suspend fun setConversationArchived(conversationId: Long, archived: Boolean) = Unit
        override suspend fun deleteConversation(conversationId: Long) = Unit
        override suspend fun deleteMessagesForConversation(conversationId: Long) = Unit
        override suspend fun deleteMessageById(messageId: Long) = Unit
        override suspend fun getMessageContentHitsLike(query: String, limit: Int, offset: Int): List<MessageContentHit> = emptyList()
    }

    private fun submissionWitness(token: String, path: String) = SubmissionCommitWitnessEntity(
        token = token,
        draftKey = 0L,
        committedConversationId = 41L,
        userMessageId = 52L,
        capturedRawInput = "captured",
        committedPathsJson = SubmissionCommitWitnessCodec.encodePaths(listOf(path))!!,
        createdAt = 1L,
    )

    // P4.1: a CancellationException from the submission batch load must
    // propagate instead of becoming an ordinary recoverable startup failure.
    @Test
    fun `submission batch load cancellation propagates`() = runBlocking {
        val vm = ChatViewModel()
        val dao = FailingSubmissionWitnessDao(emptyList())
        dao.getBatchFailure = CancellationException("recovery cancelled")
        try {
            try {
                recoverSchema8SubmissionWitnesses(vm, dao) {}
                fail("expected CancellationException to propagate")
            } catch (_: CancellationException) {
                // expected: cancellation propagates immediately
            }
            assertFalse(dao.deleted)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P4.2: an ordinary submission batch load failure keeps the existing
    // bounded behavior: recovery stops and the witness truth is not consumed.
    @Test
    fun `submission batch ordinary failure preserves witness truth`() = runBlocking {
        val vm = ChatViewModel()
        val dao = FailingSubmissionWitnessDao(listOf(submissionWitness("s8:commit", "/managed/committed.txt")))
        dao.getBatchFailure = RuntimeException("witness table unavailable")
        try {
            recoverSchema8SubmissionWitnesses(vm, dao) {}
            assertFalse(dao.deleted)
            assertEquals("", vm.draft(0L).rawInput)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P4.3: an ordinary reconciliation failure must leave the witness in
    // place for retry; recovery must not perform a false successful
    // settlement (no delete, no unregister, ownership retained).
    @Test
    fun `submission reconciliation ordinary failure preserves witness`() = runBlocking {
        val vm = ChatViewModel()
        val token = "s8:commit"
        val path = "/managed/committed.txt"
        assertTrue(
            vm.draftMachine.updateCritical(0L) {
                it.copy(
                    rawInput = "captured",
                    pendingAttachments = listOf(PendingAttachmentIdentity("committed.txt", "text/plain", path)),
                    activeSubmissionToken = token,
                )
            }
        )
        val dao = FailingSubmissionWitnessDao(listOf(submissionWitness(token, path)))
        val unregistered = mutableListOf<String>()
        try {
            recoverSchema8SubmissionWitnesses(
                vm,
                dao,
                reconcileCommitted = { _, _ -> throw RuntimeException("reconciliation failed") },
            ) { unregistered += it }
            assertFalse(dao.deleted)
            assertTrue(unregistered.isEmpty())
            assertEquals("captured", vm.draft(0L).rawInput)
            assertEquals(token, vm.draft(0L).activeSubmissionToken)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P4.4: a fatal reconciliation failure propagates; the witness stays for
    // retry and ownership is not falsely settled.
    @Test
    fun `submission reconciliation linkage error propagates`() = runBlocking {
        val vm = ChatViewModel()
        val token = "s8:commit"
        val path = "/managed/committed.txt"
        assertTrue(
            vm.draftMachine.updateCritical(0L) {
                it.copy(
                    rawInput = "captured",
                    pendingAttachments = listOf(PendingAttachmentIdentity("committed.txt", "text/plain", path)),
                    activeSubmissionToken = token,
                )
            }
        )
        val dao = FailingSubmissionWitnessDao(listOf(submissionWitness(token, path)))
        val unregistered = mutableListOf<String>()
        try {
            try {
                recoverSchema8SubmissionWitnesses(
                    vm,
                    dao,
                    reconcileCommitted = { _, _ -> throw LinkageError("reconciliation fatal") },
                ) { unregistered += it }
                fail("expected LinkageError to propagate")
            } catch (_: LinkageError) {
                // expected: fatal propagates
            }
            assertFalse(dao.deleted)
            assertTrue(unregistered.isEmpty())
            assertEquals(token, vm.draft(0L).activeSubmissionToken)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P4.5: a CancellationException from the response-version batch load must
    // propagate.
    @Test
    fun `response version batch load cancellation propagates`() = runBlocking {
        val dao = FailingResponseWitnessDao(emptyList())
        dao.getBatchFailure = CancellationException("recovery cancelled")
        try {
            recoverResponseVersionCommitWitnesses(TestContext(), TrackingChatDao(), dao)
            fail("expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // expected: cancellation propagates immediately
        }
    }

    // P4.6: a fatal failure while processing one response-version witness
    // propagates and must NOT merely log and continue with the next witness.
    @Test
    fun `response version per-witness linkage error propagates and stops the batch`() = runBlocking {
        val witness1 = ResponseVersionCommitWitnessEntity(101L, 41L, 102L, 7L, 1L)
        val witness2 = ResponseVersionCommitWitnessEntity(201L, 42L, 202L, 8L, 2L)
        val chatDao = TrackingChatDao()
        chatDao.conversations[41L] = ConversationEntity(41L, "c41", 1L, 1L)
        val dao = FailingResponseWitnessDao(listOf(witness1, witness2))
        dao.messageLookupFailure = { messageId ->
            if (messageId == 101L) LinkageError("witness lookup fatal") else null
        }
        try {
            recoverResponseVersionCommitWitnesses(TestContext(), chatDao, dao)
            fail("expected LinkageError to propagate")
        } catch (_: LinkageError) {
            // expected: fatal propagates
        }
        // Witness 1's conversation was read; witness 2 must never be touched.
        assertEquals(listOf(41L), chatDao.conversationLookups)
        assertFalse(dao.messageLookups.contains(201L))
        assertFalse(dao.deletedMessageIds.contains(201L))
    }

    // P4.7: an ordinary response-version DAO failure keeps the existing
    // bounded behavior: the failed witness is preserved for retry and the
    // batch continues with the next witness.
    @Test
    fun `response version ordinary dao failure preserves witness and continues`() = runBlocking {
        val witness1 = ResponseVersionCommitWitnessEntity(101L, 41L, 102L, 7L, 1L)
        val witness2 = ResponseVersionCommitWitnessEntity(201L, 42L, 202L, 8L, 2L)
        val chatDao = TrackingChatDao()
        chatDao.conversations[41L] = ConversationEntity(41L, "c41", 1L, 1L)
        chatDao.conversations[42L] = ConversationEntity(42L, "c42", 1L, 1L)
        val dao = FailingResponseWitnessDao(listOf(witness1, witness2))
        dao.messageLookupFailure = { messageId ->
            if (messageId == 101L) RuntimeException("witness lookup failed") else null
        }

        recoverResponseVersionCommitWitnesses(TestContext(), chatDao, dao)

        // Witness 1 stays for retry; witness 2 is processed to completion
        // (its absent message makes the witness deletable as stale evidence).
        assertEquals(listOf(41L, 42L), chatDao.conversationLookups)
        assertFalse(dao.deletedMessageIds.contains(101L))
        assertTrue(dao.deletedMessageIds.contains(201L))
    }

    // P4.8: a CancellationException from any per-witness operation must
    // propagate out of the OUTER per-witness boundary instead of being logged
    // and continued with the next witness.
    @Test
    fun `response version per-witness lookup cancellation propagates and stops the batch`() = runBlocking {
        val witness1 = ResponseVersionCommitWitnessEntity(101L, 41L, 102L, 7L, 1L)
        val witness2 = ResponseVersionCommitWitnessEntity(201L, 42L, 202L, 8L, 2L)
        val chatDao = TrackingChatDao()
        chatDao.conversations[41L] = ConversationEntity(41L, "c41", 1L, 1L)
        val dao = FailingResponseWitnessDao(listOf(witness1, witness2))
        dao.messageLookupFailure = { messageId ->
            if (messageId == 101L) CancellationException("witness lookup cancelled") else null
        }
        try {
            recoverResponseVersionCommitWitnesses(TestContext(), chatDao, dao)
            fail("expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // expected: cancellation propagates immediately
        }
        // Witness 1's conversation was read; witness 2 must never be touched.
        assertEquals(listOf(41L), chatDao.conversationLookups)
        assertFalse(dao.messageLookups.contains(201L))
        assertFalse(dao.deletedMessageIds.contains(201L))
    }

    // P4.9: a CancellationException thrown by the post-repair witness delete
    // is rethrown by the INNER boundary and must survive the OUTER per-witness
    // boundary as cancellation, not be reclassified as an ordinary bounded
    // recovery failure.
    @Test
    fun `response version witness delete cancellation remains cancellation`() = runBlocking {
        val witness1 = ResponseVersionCommitWitnessEntity(101L, 41L, 102L, 7L, 1L)
        val witness2 = ResponseVersionCommitWitnessEntity(201L, 42L, 202L, 8L, 2L)
        val chatDao = TrackingChatDao()
        chatDao.conversations[41L] = ConversationEntity(id = 41L, title = "c41", createdAt = 1L, updatedAt = 1L)
        val dao = FailingResponseWitnessDao(listOf(witness1, witness2))
        dao.messages[101L] = MessageEntity(id = 101L, conversationId = 41L, role = "assistant", content = "a", createdAt = 1L)
        dao.messages[102L] = MessageEntity(id = 102L, conversationId = 41L, role = "assistant", content = "b", createdAt = 2L)
        dao.deleteFailure = { messageId ->
            if (messageId == 101L) CancellationException("witness delete cancelled") else null
        }
        val context = FakePrefsContext(PlatformSharedPreferences())
        try {
            recoverResponseVersionCommitWitnesses(context, chatDao, dao)
            fail("expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // expected: the inner rethrow survives the outer boundary
        }
        // Repair reached witness 1's durable delete; witness 2 must never be touched.
        assertEquals(listOf(41L), chatDao.conversationLookups)
        assertFalse(dao.messageLookups.contains(201L))
        assertFalse(dao.deletedMessageIds.contains(201L))
    }
}
