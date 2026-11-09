package com.projectnuke.fusion.chat

import android.content.Context
import android.content.ContextWrapper
import com.projectnuke.fusion.data.ChatDao
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.MessageContentPageRow
import com.projectnuke.fusion.data.MessageIdentity
import com.projectnuke.fusion.ui.CommittedDraftReconciliationDebt
import com.projectnuke.fusion.ui.CommittedDraftReconciliationDebtStore
import com.projectnuke.fusion.ui.DraftReconciliationOwner
import com.projectnuke.fusion.ui.DraftReconciliationResult
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftAttachmentSafetyTest {
    private class TestContext(
        private val root: File,
        private val attachments: File,
    ) : ContextWrapper(null) {
        override fun getFilesDir(): File = root
        override fun getExternalFilesDir(type: String?): File = attachments
        override fun getApplicationContext(): Context = this
    }

    private class EmptyDao(
        private val messageContents: List<String> = emptyList(),
        private val failAfterId: Long? = null,
        private val pageStarted: CompletableDeferred<Unit>? = null,
        private val pageGate: CompletableDeferred<Unit>? = null,
    ) : ChatDao {
        var pageCalls: Int = 0
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
        override suspend fun getMessageIdentityById(messageId: Long): MessageIdentity? = null
        override suspend fun getMessageById(messageId: Long): MessageEntity? = null
        override suspend fun getMessagesLatestLimited(conversationId: Long, limit: Int): List<MessageEntity> = emptyList()
        override suspend fun getAllMessageContents(): List<String> = messageContents
        override suspend fun getMessageContentPage(afterId: Long, limit: Int): List<MessageContentPageRow> {
            pageCalls++
            pageStarted?.complete(Unit)
            pageGate?.await()
            if (failAfterId != null && afterId >= failAfterId) throw IllegalStateException("page failure")
            return messageContents.asSequence()
                .mapIndexed { index, content -> MessageContentPageRow(index + 1L, content) }
                .filter { it.id > afterId }
                .take(limit)
                .toList()
        }
        override suspend fun getConversationById(conversationId: Long): ConversationEntity? = null
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
        override suspend fun getMessageContentHitsLike(query: String, limit: Int, offset: Int) =
            emptyList<com.projectnuke.fusion.data.MessageContentHit>()
    }

    @Test
    fun `corrupt draft references are unknown, not empty`() {
        val dir = Files.createTempDirectory("fusion-draft-refs").toFile()
        File(dir, PersistentComposerDraftStore.FILE_NAME).writeText("not-json")
        val attachments = File(dir, "attachments").apply { mkdirs() }
        val refs = PersistentComposerDraftStore.durableDraftReferences(TestContext(dir, attachments))
        assertTrue(refs is DurableDraftReferences.Unknown)
        dir.deleteRecursively()
    }

    @Test
    fun `oversized draft references are unknown`() {
        val dir = Files.createTempDirectory("fusion-draft-refs").toFile()
        File(dir, PersistentComposerDraftStore.FILE_NAME).writeText("x".repeat(PersistentComposerDraftStore.MAX_FILE_BYTES + 1))
        val attachments = File(dir, "attachments").apply { mkdirs() }
        val refs = PersistentComposerDraftStore.durableDraftReferences(TestContext(dir, attachments))
        assertTrue(refs is DurableDraftReferences.Unknown)
        dir.deleteRecursively()
    }

    @Test
    fun `writer-impossible attachment path type is unknown`() {
        val dir = Files.createTempDirectory("fusion-draft-refs").toFile()
        val attachments = File(dir, "attachments").apply { mkdirs() }
        File(dir, PersistentComposerDraftStore.FILE_NAME).writeText(
            """[{"id":1,"input":"x","version":1,"attachments":[{"path":123}]}]""",
        )
        val refs = PersistentComposerDraftStore.durableDraftReferences(TestContext(dir, attachments))
        assertTrue(refs is DurableDraftReferences.Unknown)
        dir.deleteRecursively()
    }

    @Test
    fun `cleanup preserves files when draft references are unreadable`() = runTest {
        val dir = Files.createTempDirectory("fusion-draft-cleanup").toFile()
        val attachments = File(dir, "attachments").apply { mkdirs() }
        // Corrupt draft artifact: references unknown.
        File(dir, PersistentComposerDraftStore.FILE_NAME).writeText("not-json")
        val orphan = File(attachments, "draft-only.bin").apply {
            writeBytes(ByteArray(32))
            setLastModified(System.currentTimeMillis() - 60 * 60 * 1000L)
        }
        val context = TestContext(dir, attachments)
        val result = AttachmentStorageManager.cleanupUnreferencedAttachments(context, EmptyDao())
        assertTrue(result.deletedFiles == 0)
        assertTrue(orphan.isFile)
        dir.deleteRecursively()
    }

    @Test
    fun `missing draft references are available empty`() {
        val dir = Files.createTempDirectory("fusion-draft-refs").toFile()
        val attachments = File(dir, "attachments").apply { mkdirs() }
        val refs = PersistentComposerDraftStore.durableDraftReferences(TestContext(dir, attachments))
        assertTrue(refs is DurableDraftReferences.Available)
        assertTrue((refs as DurableDraftReferences.Available).paths.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `storage stats count draft-only files as referenced`() = runTest {
        val dir = Files.createTempDirectory("fusion-stats-refs").toFile()
        val attachments = File(dir, "attachments").apply { mkdirs() }
        val draftOnly = File(attachments, "draft-only.bin").apply { writeBytes(ByteArray(16)) }
        val trulyOrphaned = File(attachments, "orphan.bin").apply { writeBytes(ByteArray(16)) }
        File(dir, PersistentComposerDraftStore.FILE_NAME).writeText(
            """[{"id":1,"input":"x","version":1,"attachments":[{"name":"d","mime":"application/octet-stream","path":"${draftOnly.absolutePath.replace("\\", "\\\\")}"}]}]""",
        )
        val stats = AttachmentStorageManager.calculateAttachmentStorageStats(TestContext(dir, attachments), EmptyDao())
        org.junit.Assert.assertEquals(2, stats.totalFiles)
        org.junit.Assert.assertEquals(1, stats.referencedFiles)
        org.junit.Assert.assertEquals(1, stats.unreferencedFiles)
        assertTrue(draftOnly.isFile && trulyOrphaned.isFile)
        dir.deleteRecursively()
    }

    @Test
    fun `committed message protects attachment when reconciliation debt persistence fails`() = runTest {
        val dir = Files.createTempDirectory("fusion-committed-attachment").toFile()
        val attachments = File(dir, "attachments").apply { mkdirs() }
        val attachment = File(attachments, "committed.txt").apply {
            writeText("committed")
            setLastModified(System.currentTimeMillis() - 60 * 60 * 1000L)
        }
        val context = TestContext(dir, attachments)
        val canonicalPath = attachment.canonicalPath
        val message = AttachmentMessageCodec.serializeAttachmentMessage(
            attachments = listOf(AttachmentRecord("committed.txt", "text/plain", canonicalPath)),
            body = "committed message",
        )
        val debtFile = File(dir, "committed_draft_reconciliation_debt.json")
        val debt = CommittedDraftReconciliationDebt(
            draftKey = 7L,
            token = "token-a",
            capturedRawInput = "captured",
            committedPaths = setOf(canonicalPath),
            attempts = 0,
            lastAttemptAt = 0L,
        )
        check(CommittedDraftReconciliationDebtStore.record(debtFile, debt))
        check(AttachmentStorageManager.registerPendingAttachment(attachment) != null)

        val previousHook = CommittedDraftReconciliationDebtStore.retryPersistenceHook
        CommittedDraftReconciliationDebtStore.retryPersistenceHook = { _, _ -> false }
        try {
            val retry = CommittedDraftReconciliationDebtStore.retry(
                owner = DraftReconciliationOwner { DraftReconciliationResult(success = true, releasePaths = true) },
                unregisterPath = { path -> AttachmentStorageManager.unregisterPendingAttachment(path) },
                file = debtFile,
            )

            assertEquals(1, retry.reconciled)
            assertFalse(retry.persistenceOk)
            assertFalse(AttachmentStorageManager.isPendingAttachmentRegistered(canonicalPath))
            val persistedDebt = CommittedDraftReconciliationDebtStore.loadResult(debtFile)
            assertTrue(persistedDebt is com.projectnuke.fusion.ui.CommittedDraftDebtLoad.Valid)
            assertEquals(
                setOf(canonicalPath),
                (persistedDebt as com.projectnuke.fusion.ui.CommittedDraftDebtLoad.Valid)
                    .entries.single().committedPaths,
            )

            // This message content is the durable post-transaction owner of
            // the path. Cleanup must preserve it after pending registration
            // is released, even though the retry debt remains on disk.
            val cleanup = AttachmentStorageManager.cleanupUnreferencedAttachments(
                context,
                EmptyDao(listOf(message)),
            )
            assertEquals(0, cleanup.deletedFiles)
            assertTrue(attachment.isFile)
        } finally {
            CommittedDraftReconciliationDebtStore.retryPersistenceHook = previousHook
            AttachmentStorageManager.unregisterPendingAttachment(canonicalPath)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `message references are scanned in bounded pages and retain first middle last`() = runTest {
        val dir = Files.createTempDirectory("fusion-paged-attachments").toFile()
        val attachments = File(dir, "attachments").apply { mkdirs() }
        val first = File(attachments, "first.bin").apply { writeBytes(ByteArray(8)); setLastModified(0L) }
        val middle = File(attachments, "middle.bin").apply { writeBytes(ByteArray(8)); setLastModified(0L) }
        val last = File(attachments, "last.bin").apply { writeBytes(ByteArray(8)); setLastModified(0L) }
        val orphan = File(attachments, "orphan.bin").apply { writeBytes(ByteArray(8)); setLastModified(0L) }
        val contents = MutableList(1_200) { "ordinary message $it" }
        fun envelope(file: File) = AttachmentMessageCodec.serializeAttachmentMessage(
            attachments = listOf(AttachmentRecord(file.name, "application/octet-stream", file.canonicalPath)),
            body = "body",
        )
        contents[0] = envelope(first)
        contents[600] = envelope(middle)
        contents[1_199] = envelope(last)
        val dao = EmptyDao(contents)
        val context = TestContext(dir, attachments)

        val stats = AttachmentStorageManager.calculateAttachmentStorageStats(context, dao)
        assertEquals(4, dao.pageCalls)
        assertEquals(4, stats.totalFiles)
        assertEquals(3, stats.referencedFiles)
        assertEquals(1, stats.unreferencedFiles)

        val cleanup = AttachmentStorageManager.cleanupUnreferencedAttachments(context, dao)
        assertEquals(1, cleanup.deletedFiles)
        assertTrue(first.isFile && middle.isFile && last.isFile)
        assertFalse(orphan.exists())
        dir.deleteRecursively()
    }

    @Test
    fun `message page failure preserves all files and reports no dead statistics`() = runTest {
        val dir = Files.createTempDirectory("fusion-paged-failure").toFile()
        val attachments = File(dir, "attachments").apply { mkdirs() }
        val live = File(attachments, "live.bin").apply { writeBytes(ByteArray(8)); setLastModified(0L) }
        val orphan = File(attachments, "unknown.bin").apply { writeBytes(ByteArray(8)); setLastModified(0L) }
        val contents = List(700) { index ->
            if (index == 0) AttachmentMessageCodec.serializeAttachmentMessage(
                attachments = listOf(AttachmentRecord(live.name, "application/octet-stream", live.canonicalPath)),
                body = "live",
            ) else "message $index"
        }
        val dao = EmptyDao(contents, failAfterId = 512L)
        val context = TestContext(dir, attachments)

        val cleanup = AttachmentStorageManager.cleanupUnreferencedAttachments(context, dao)
        assertEquals(0, cleanup.deletedFiles)
        assertTrue(live.isFile && orphan.isFile)

        val stats = AttachmentStorageManager.calculateAttachmentStorageStats(context, dao)
        assertEquals(2, stats.referencedFiles)
        assertEquals(0, stats.unreferencedFiles)
        dir.deleteRecursively()
    }

    @Test
    fun `mixed trusted and suspicious message authority preserves every managed file`() = runTest {
        val dir = Files.createTempDirectory("fusion-suspicious-message-cleanup").toFile()
        val attachments = File(dir, "attachments").apply { mkdirs() }
        val valid = File(attachments, "valid.bin").apply {
            writeBytes(ByteArray(8))
            setLastModified(0L)
        }
        val orphan = File(attachments, "orphan.bin").apply {
            writeBytes(ByteArray(8))
            setLastModified(0L)
        }
        val outside = Files.createTempFile("fusion-suspicious-outside", ".bin").toFile()
        val validTag = AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(AttachmentRecord(valid.name, "application/octet-stream", valid.canonicalPath)),
            "",
        ).substringBefore("\n\n")
        val suspiciousTag = AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(AttachmentRecord("outside.bin", "application/octet-stream", outside.canonicalPath)),
            "",
        ).substringBefore("\n\n")
        val message = "$validTag$suspiciousTag mixed message"
        val context = TestContext(dir, attachments)

        val cleanup = AttachmentStorageManager.cleanupUnreferencedAttachments(context, EmptyDao(listOf(message)))
        val stats = AttachmentStorageManager.calculateAttachmentStorageStats(context, EmptyDao(listOf(message)))

        assertEquals(0, cleanup.deletedFiles)
        assertTrue(valid.isFile)
        assertTrue(orphan.isFile)
        assertEquals(2, stats.referencedFiles)
        assertEquals(0, stats.unreferencedFiles)
        dir.deleteRecursively()
        outside.delete()
    }

    @Test
    fun `cleanup propagates cancellation while scanning message references`() = runTest {
        val dir = Files.createTempDirectory("fusion-cancelled-message-scan").toFile()
        val attachments = File(dir, "attachments").apply { mkdirs() }
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val parent = Job()
        val context = TestContext(dir, attachments)
        val cleanup = CoroutineScope(parent).async {
            AttachmentStorageManager.cleanupUnreferencedAttachments(
                context,
                EmptyDao(pageStarted = started, pageGate = gate),
            )
        }

        try {
            started.await()
            parent.cancel()
            var cancelled = false
            try {
                cleanup.await()
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
        } finally {
            parent.cancel()
            gate.complete(Unit)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `failed cleanup leaves orphan eligible for a later lifecycle pass`() = runTest {
        val dir = Files.createTempDirectory("fusion-project-cleanup-retry").toFile()
        val attachments = File(dir, "attachments").apply { mkdirs() }
        val orphan = File(attachments, "orphan.bin").apply {
            writeBytes(ByteArray(8))
            setLastModified(0L)
        }
        val context = TestContext(dir, attachments)
        try {
            val failed = AttachmentStorageManager.cleanupUnreferencedAttachments(
                context,
                EmptyDao(failAfterId = 0L),
            )
            assertEquals(0, failed.deletedFiles)
            assertTrue(orphan.isFile)

            val retried = AttachmentStorageManager.cleanupUnreferencedAttachments(context, EmptyDao())
            assertEquals(1, retried.deletedFiles)
            assertFalse(orphan.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
