package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ChatDao
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.EvidenceDao
import com.projectnuke.fusion.data.MessageContentHit
import com.projectnuke.fusion.data.MessageContentPageRow
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.MessageEvidenceEntity
import com.projectnuke.fusion.data.MessageEvidenceRevision
import com.projectnuke.fusion.data.MessageIdentity
import com.projectnuke.fusion.data.ProjectConversationEntity
import com.projectnuke.fusion.data.ProjectDao
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import com.projectnuke.fusion.util.extractReferencedAttachmentPaths
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Product conversation branch: exact active-prefix copy with new IDs,
// evidence re-keyed, one Room transaction, shared managed attachments.
class ConversationBranchCoordinatorTest {

    private class FakeChatDao : ChatDao {
        val conversations = linkedMapOf<Long, ConversationEntity>()
        val messages = linkedMapOf<Long, MessageEntity>()
        var nextConversationId = 100L
        var nextMessageId = 1000L
        var failOnMessageInsertNumber: Int? = null
        var cancelOnMessageInsertNumber: Int? = null
        private var messageInserts = 0
        var messagesReadStarted: CompletableDeferred<Unit>? = null
        var releaseMessagesRead: CompletableDeferred<Unit>? = null

        override suspend fun insertConversation(conversation: ConversationEntity): Long {
            val id = nextConversationId++
            conversations[id] = conversation.copy(id = id)
            return id
        }

        override suspend fun insertMessage(message: MessageEntity): Long {
            messageInserts++
            cancelOnMessageInsertNumber?.let { cancelAt ->
                if (messageInserts == cancelAt) throw CancellationException("fake branch cancellation")
            }
            failOnMessageInsertNumber?.let { failAt ->
                if (messageInserts == failAt) throw IllegalStateException("fake insert failure")
            }
            val id = nextMessageId++
            messages[id] = message.copy(id = id)
            return id
        }

        override suspend fun getConversationById(conversationId: Long): ConversationEntity? =
            conversations[conversationId]
        override suspend fun getMessageIdentityById(messageId: Long): com.projectnuke.fusion.data.MessageIdentity? =
            messages[messageId]?.let { com.projectnuke.fusion.data.MessageIdentity(it.id, it.conversationId, it.role, it.createdAt) }
        override suspend fun getMessageById(messageId: Long): MessageEntity? = messages[messageId]

        override suspend fun getMessagesForConversation(conversationId: Long): List<MessageEntity> {
            messagesReadStarted?.complete(Unit)
            releaseMessagesRead?.await()
            return messages.values.filter { it.conversationId == conversationId }
                .sortedWith(compareBy({ it.createdAt }, { it.id }))
        }

        override suspend fun getAllMessageContents(): List<String> = messages.values.map { it.content }
        override suspend fun getMessageContentPage(afterId: Long, limit: Int): List<MessageContentPageRow> =
            messages.values.sortedBy { it.id }
                .map { MessageContentPageRow(it.id, it.content) }
                .filter { it.id > afterId }
                .take(limit)

        fun snapshot(): Triple<Map<Long, ConversationEntity>, Map<Long, MessageEntity>, Pair<Long, Long>> =
            Triple(HashMap(conversations), HashMap(messages), nextConversationId to nextMessageId)

        fun restore(snap: Triple<Map<Long, ConversationEntity>, Map<Long, MessageEntity>, Pair<Long, Long>>) {
            conversations.clear(); conversations.putAll(snap.first)
            messages.clear(); messages.putAll(snap.second)
            nextConversationId = snap.third.first
            nextMessageId = snap.third.second
            messageInserts = 0
        }

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
        override suspend fun getMessageIdentitiesForConversation(conversationId: Long): List<MessageIdentity> = emptyList()
        override suspend fun getMessagesLatestLimited(conversationId: Long, limit: Int): List<MessageEntity> = emptyList()
        override suspend fun getLatestConversation(): ConversationEntity? = null
        override suspend fun getConversationCount(): Int = conversations.size
        override suspend fun getMessageCount(): Int = messages.size
        override suspend fun getArchivedConversationCount(): Int = 0
        override suspend fun getMessageCountForConversation(conversationId: Long): Int =
            messages.values.count { it.conversationId == conversationId }
        override suspend fun updateConversationTime(conversationId: Long, updatedAt: Long) = Unit
        override suspend fun updateMessageContent(messageId: Long, content: String) = Unit
        override suspend fun updateConversationPinned(conversationId: Long, isPinned: Boolean) = Unit
        override suspend fun updateConversationTitle(conversationId: Long, title: String) = Unit
        override suspend fun archiveConversation(conversationId: Long) = Unit
        override suspend fun setConversationArchived(conversationId: Long, archived: Boolean) = Unit
        override suspend fun deleteConversation(conversationId: Long) {
            conversations.remove(conversationId)
        }
        override suspend fun deleteMessagesForConversation(conversationId: Long) {
            messages.entries.removeIf { it.value.conversationId == conversationId }
        }
        override suspend fun deleteMessageById(messageId: Long) {
            messages.remove(messageId)
        }
        override suspend fun getMessageContentHitsLike(query: String, limit: Int, offset: Int): List<MessageContentHit> = emptyList()
    }

    private class FakeEvidenceDao : EvidenceDao {
        val store = linkedMapOf<Long, MessageEvidenceEntity>()

        override suspend fun insertEvidence(evidence: MessageEvidenceEntity) {
            store[evidence.messageId] = evidence
        }

        override suspend fun getEvidenceForMessage(messageId: Long): MessageEvidenceEntity? =
            store[messageId]

        override fun observeEvidenceForConversation(conversationId: Long): Flow<List<MessageEvidenceEntity>> = emptyFlow()

        override fun observeEvidenceRevisionsForConversation(conversationId: Long): Flow<List<MessageEvidenceRevision>> = emptyFlow()

        override suspend fun getEvidenceForConversation(conversationId: Long): List<MessageEvidenceEntity> =
            // Fake infers conversation membership from the chat DAO mirror.
            store.values.toList()

        override suspend fun deleteEvidenceForMessage(messageId: Long) {
            store.remove(messageId)
        }

        fun snapshot(): Map<Long, MessageEvidenceEntity> = HashMap(store)

        fun restore(snap: Map<Long, MessageEvidenceEntity>) {
            store.clear(); store.putAll(snap)
        }
    }

    private class FakeProjectDao : ProjectDao {
        val relations = linkedMapOf<Long, ProjectConversationEntity>()
        val projects = linkedMapOf<Long, ProjectEntity>()
        val files = linkedMapOf<Long, ProjectFileEntity>()
        var failAssignment = false
        var transactionActive = false
        var membershipReadsInsideTransaction = 0
        var projectIdToSetOnTransactionEntry: Long? = null

        fun snapshot(): Map<Long, ProjectConversationEntity> = HashMap(relations)

        fun restore(snapshot: Map<Long, ProjectConversationEntity>) {
            relations.clear()
            relations.putAll(snapshot)
        }

        override suspend fun insertProject(project: ProjectEntity): Long {
            val id = (projects.keys.maxOrNull() ?: 0L) + 1L
            projects[id] = project.copy(id = id)
            return id
        }

        override fun observeProjects(): Flow<List<ProjectEntity>> = emptyFlow()
        override suspend fun getProjects(): List<ProjectEntity> = projects.values.toList()
        override suspend fun getRecentProjectsBounded(limit: Int): List<ProjectEntity> = projects.values.take(limit)
        override suspend fun getProjectFileById(fileId: Long): ProjectFileEntity? = files[fileId]
        override suspend fun getProjectById(projectId: Long): ProjectEntity? = projects[projectId]
        override suspend fun getProjectForDeletion(projectId: Long): ProjectEntity? = projects[projectId]
        override suspend fun getPendingDeletionsAfter(afterId: Long, limit: Int): List<Long> =
            projects.values.filter { it.deletionPending && it.id > afterId }.sortedBy { it.id }.take(limit).map { it.id }
        override suspend fun beginDeletion(projectId: Long): Int =
            projects[projectId]?.let { if (it.deletionPending) 0 else { projects[projectId] = it.copy(deletionPending = true); 1 } } ?: 0
        override suspend fun finishDeletion(projectId: Long): Int =
            if (projects[projectId]?.deletionPending == true) { projects.remove(projectId); 1 } else 0
        override suspend fun hasProjectName(name: String): Boolean = projects.values.any { it.name.equals(name, ignoreCase = true) }
        override suspend fun updateProjectName(projectId: Long, name: String, updatedAt: Long) = Unit
        override suspend fun updateProjectInstructions(projectId: Long, instructions: String, updatedAt: Long): Int {
            return if (projects[projectId] != null) {
                projects[projectId] = projects[projectId]!!.copy(instructions = instructions, updatedAt = updatedAt)
                1
            } else 0
        }
        override suspend fun deleteProject(projectId: Long) = Unit
        override suspend fun assignConversation(relation: ProjectConversationEntity) {
            if (failAssignment) throw IllegalStateException("project assignment failed")
            relations[relation.conversationId] = relation
        }
        override suspend fun removeAssignment(conversationId: Long) { relations.remove(conversationId) }
        override suspend fun getProjectIdForConversation(conversationId: Long): Long? {
            if (transactionActive) membershipReadsInsideTransaction++
            return relations[conversationId]?.projectId
        }
        override suspend fun getConversationsForProject(projectId: Long): List<ConversationEntity> = emptyList()
        override suspend fun getConversationsForProjectBounded(projectId: Long, limit: Int): List<ConversationEntity> = emptyList()
        override suspend fun getActiveConversationsForProjectBounded(projectId: Long, limit: Int): List<ConversationEntity> = emptyList()
        override suspend fun getAssignmentCount(projectId: Long): Int = relations.values.count { it.projectId == projectId }
        override suspend fun getProjectFiles(projectId: Long): List<ProjectFileEntity> = files.values.filter { it.projectId == projectId }

        override suspend fun getProjectFilesBounded(projectId: Long, limit: Int): List<ProjectFileEntity> =
            files.values.filter { it.projectId == projectId }.take(limit)
        override suspend fun getRecentProjectFilesBounded(projectId: Long, limit: Int): List<ProjectFileEntity> =
            files.values.filter { it.projectId == projectId }.sortedByDescending { it.addedAt }.take(limit)
        override fun observeProjectFiles(projectId: Long): Flow<List<ProjectFileEntity>> = emptyFlow()
        override suspend fun insertProjectFile(file: ProjectFileEntity): Long {
            val id = (files.keys.maxOrNull() ?: 0L) + 1L
            files[id] = file.copy(id = id)
            return id
        }
        override suspend fun deleteProjectFile(fileId: Long): Int =
            if (files.remove(fileId) != null) 1 else 0
        override suspend fun getProjectFileByHandoffEventId(eventId: String): ProjectFileEntity? =
            files.values.firstOrNull { it.sourceHandoffEventId == eventId }
        override suspend fun getProjectFilesByHandoffPrefix(eventIdPrefix: String, limit: Int): List<ProjectFileEntity> =
            files.values.filter { it.sourceHandoffEventId?.startsWith(eventIdPrefix.removeSuffix("%")) == true }.take(limit)
        override suspend fun getProjectFileByPath(projectId: Long, localPath: String): ProjectFileEntity? =
            files.values.firstOrNull { it.projectId == projectId && it.localPath == localPath }
        override suspend fun countProjectFiles(projectId: Long): Int = files.values.count { it.projectId == projectId }
        override suspend fun getAllProjectFilePaths(): List<String> = files.values.map { it.localPath }
    }

    private data class Fixture(
        val chat: FakeChatDao,
        val evidence: FakeEvidenceDao,
        val projects: FakeProjectDao,
        val versions: MutableMap<Long, ResponseVersionState>,
        val coordinator: ConversationBranchCoordinator,
    )

    private fun evidenceRow(messageId: Long, answer: String) = MessageEvidenceEntity(
        messageId = messageId,
        answerSha256 = "sha-$messageId",
        schemaVersion = 1,
        verifierVersion = "v1",
        checkedAt = 1700000000000L,
        reportJson = """{"answer":$answer}""",
    )

    // U1 A1 U2 A2(+inactive A2') U3 A3. A1/A2 carry evidence; A3 none.
    private suspend fun standardFixture(): Fixture {
        val chat = FakeChatDao()
        val evidence = FakeEvidenceDao()
        val projects = FakeProjectDao()
        val sourceId = 1L
        chat.conversations[sourceId] = ConversationEntity(
            id = sourceId, title = "HBM 공부", createdAt = 1000L, updatedAt = 6000L,
        )
        val u1 = MessageEntity(id = 1, conversationId = sourceId, role = "user", content = "U1 질문", createdAt = 1001L)
        val a1 = MessageEntity(id = 2, conversationId = sourceId, role = "assistant", content = "A1 답변", createdAt = 1002L)
        val u2 = MessageEntity(id = 3, conversationId = sourceId, role = "user", content = "U2 질문", createdAt = 1003L)
        val a2 = MessageEntity(id = 4, conversationId = sourceId, role = "assistant", content = "A2 답변", createdAt = 1004L)
        val u3 = MessageEntity(id = 5, conversationId = sourceId, role = "user", content = "U3 질문", createdAt = 1005L)
        val a3 = MessageEntity(id = 6, conversationId = sourceId, role = "assistant", content = "A3 답변", createdAt = 1006L)
        val a2alt = MessageEntity(id = 7, conversationId = sourceId, role = "assistant", content = "A2 숨은 대체 답변", createdAt = 1007L)
        listOf(u1, a1, u2, a2, u3, a3, a2alt).forEach { chat.messages[it.id] = it }
        evidence.store[2] = evidenceRow(2, "A1")
        evidence.store[4] = evidenceRow(4, "A2")
        val versions = mutableMapOf(
            sourceId to ResponseVersionState(
                groupByMessageId = mapOf(4L to 40L, 7L to 40L),
                activeMessageIdByGroup = mapOf(40L to 4L),
            ),
        )
        // The fake evidence DAO is source-scoped in these tests: only the
        // source conversation exists until a branch commits.
        val coordinator = ConversationBranchCoordinator(
            chatDao = chat,
            evidenceDao = evidence,
            runInTransaction = { block ->
                val chatSnap = chat.snapshot()
                val evSnap = evidence.snapshot()
                val projectSnap = projects.snapshot()
                projects.transactionActive = true
                projects.projectIdToSetOnTransactionEntry?.let { projectId ->
                    projects.relations[1L] = projects.relations.getValue(1L).copy(projectId = projectId)
                    projects.projectIdToSetOnTransactionEntry = null
                }
                try {
                    block()
                } catch (failure: Exception) {
                    chat.restore(chatSnap)
                    evidence.restore(evSnap)
                    projects.restore(projectSnap)
                    throw failure
                } finally {
                    projects.transactionActive = false
                }
            },
            projectDao = projects,
            loadVersions = { id -> versions[id] ?: ResponseVersionState() },
            clock = { 9999L },
        )
        return Fixture(chat, evidence, projects, versions, coordinator)
    }

    @Test
    fun `branch through A2 copies exact prefix and excludes later turns`() = runTest {
        val f = standardFixture()
        val outcome = f.coordinator.branch(1L, 4L)
        assertTrue(outcome is BranchOutcome.Success)
        val newId = (outcome as BranchOutcome.Success).newConversationId
        assertNotEquals(1L, newId)

        val branched = f.chat.getMessagesForConversation(newId)
        assertEquals(listOf("U1 질문", "A1 답변", "U2 질문", "A2 답변"), branched.map { it.content })
        assertEquals(listOf("user", "assistant", "user", "assistant"), branched.map { it.role })
        // Later turns excluded; inactive alternative excluded.
        assertTrue(branched.none { it.content.contains("U3") || it.content.contains("A3") })
        assertTrue(branched.none { it.content.contains("숨은 대체") })
        // New message IDs differ from the source IDs.
        assertTrue(branched.all { it.id >= 1000L })
        assertTrue(branched.none { it.id in listOf(1L, 2L, 3L, 4L) })
        // Original conversation unchanged.
        assertEquals(7, f.chat.getMessagesForConversation(1L).size)
        assertEquals(1L, f.chat.getConversationById(newId)?.branchedFromConversationId)
        assertEquals(4L, f.chat.getConversationById(newId)?.branchedFromMessageId)
        assertEquals("HBM 공부", f.chat.getConversationById(1L)?.title)
        // Branch navigation result identifies the new conversation (semantic
        // source title, no " · 가지" suffix).
        assertEquals("HBM 공부", f.chat.getConversationById(newId)?.title)
    }

    @Test
    fun `research refresh branch records exact answer provenance and rejects changed project scope`() = runTest {
        val f = standardFixture()
        f.projects.relations[1L] = ProjectConversationEntity(1L, 77L, 1L)
        val refreshed = f.coordinator.branch(
            sourceConversationId = 1L,
            branchAssistantMessageId = 4L,
            researchRefreshFrom = 1L to 4L,
            expectedProjectScope = ConversationBranchCoordinator.ProjectScopeExpectation(true, 77L),
        ) as BranchOutcome.Success
        val link = f.chat.getConversationById(refreshed.newConversationId)!!
        assertEquals(1L, link.refreshedFromConversationId)
        assertEquals(4L, link.refreshedFromMessageId)
        assertEquals(77L, f.projects.relations[refreshed.newConversationId]?.projectId)

        val g = standardFixture()
        g.projects.relations[1L] = ProjectConversationEntity(1L, 77L, 1L)
        g.projects.projectIdToSetOnTransactionEntry = 88L
        val stale = g.coordinator.branch(
            sourceConversationId = 1L,
            branchAssistantMessageId = 4L,
            researchRefreshFrom = 1L to 4L,
            expectedProjectScope = ConversationBranchCoordinator.ProjectScopeExpectation(true, 77L),
        )
        assertEquals(BranchOutcome.Failure(BranchFailure.PROJECT_SCOPE_CHANGED), stale)
        assertEquals(setOf(1L), g.chat.conversations.keys)
    }

    @Test
    fun `branch of branch records immediate parent and exact anchor`() = runTest {
        val f = standardFixture()
        val first = f.coordinator.branch(1L, 4L) as BranchOutcome.Success
        val firstAnchor = f.chat.getMessagesForConversation(first.newConversationId).last().id

        val second = f.coordinator.branch(first.newConversationId, firstAnchor) as BranchOutcome.Success

        assertEquals(first.newConversationId, f.chat.getConversationById(second.newConversationId)?.branchedFromConversationId)
        assertEquals(firstAnchor, f.chat.getConversationById(second.newConversationId)?.branchedFromMessageId)
        f.chat.deleteConversation(first.newConversationId)
        assertTrue(f.chat.getConversationById(second.newConversationId) != null)
    }

    @Test
    fun `active selected version included with exact raw content`() = runTest {
        val f = standardFixture()
        val outcome = f.coordinator.branch(1L, 4L)
        val newId = (outcome as BranchOutcome.Success).newConversationId
        val branched = f.chat.getMessagesForConversation(newId)
        assertEquals("A2 답변", branched.last().content)
    }

    @Test
    fun `evidence mapped to new ids with fields preserved and missing stays missing`() = runTest {
        val f = standardFixture()
        val outcome = f.coordinator.branch(1L, 4L)
        val newId = (outcome as BranchOutcome.Success).newConversationId
        val branched = f.chat.getMessagesForConversation(newId)
        val newA1 = branched.first { it.content == "A1 답변" }
        val newA2 = branched.first { it.content == "A2 답변" }
        val copiedA1 = f.evidence.store[newA1.id]
        val copiedA2 = f.evidence.store[newA2.id]
        assertEquals("sha-2", copiedA1?.answerSha256)
        assertEquals(1, copiedA1?.schemaVersion)
        assertEquals("v1", copiedA1?.verifierVersion)
        assertEquals(1700000000000L, copiedA1?.checkedAt)
        assertEquals("""{"answer":A1}""", copiedA1?.reportJson)
        assertEquals("sha-4", copiedA2?.answerSha256)
        // Source rows untouched.
        assertEquals("sha-2", f.evidence.store[2]?.answerSha256)
        assertEquals("sha-4", f.evidence.store[4]?.answerSha256)

        // A3 had no evidence: branching through A3 fabricates nothing.
        val full = f.coordinator.branch(1L, 6L)
        val fullId = (full as BranchOutcome.Success).newConversationId
        val fullMessages = f.chat.getMessagesForConversation(fullId)
        val newA3 = fullMessages.first { it.content == "A3 답변" }
        assertEquals(null, f.evidence.store[newA3.id])
    }

    @Test
    fun `branch transaction failure rolls back with source untouched`() = runTest {
        val f = standardFixture()
        f.chat.failOnMessageInsertNumber = 3
        val outcome = f.coordinator.branch(1L, 4L)
        assertEquals(BranchOutcome.Failure(BranchFailure.DB_ERROR), outcome)
        // No partial new branch remains.
        assertEquals(1, f.chat.conversations.size)
        assertEquals(7, f.chat.getMessagesForConversation(1L).size)
        assertEquals(setOf(2L, 4L), f.evidence.store.keys)
        assertTrue(f.chat.conversations.values.none { it.branchedFromConversationId != null || it.branchedFromMessageId != null })
    }

    @Test
    fun `cancelled branch propagates rolls back all branch state and releases lease`() = runTest {
        val f = standardFixture()
        f.projects.relations[1L] = ProjectConversationEntity(
            conversationId = 1L,
            projectId = 77L,
            assignedAt = 1L,
        )
        f.chat.cancelOnMessageInsertNumber = 1

        var propagated = false
        try {
            f.coordinator.branch(1L, 4L)
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue(propagated)
        assertEquals(1, f.chat.conversations.size)
        assertEquals(7, f.chat.getMessagesForConversation(1L).size)
        assertEquals(setOf(2L, 4L), f.evidence.store.keys)
        assertEquals(setOf(1L), f.projects.relations.keys)

        // The lease's finally block must run even when cancellation escapes.
        val deletion = com.projectnuke.fusion.chat.ConversationStructuralMutationRegistry.beginDeletion(1L)
        withTimeout(2_000) { deletion.awaitQuiescent() }
        deletion.finish()
    }

    @Test
    fun `branch inherits source project membership inside the branch transaction`() = runTest {
        val f = standardFixture()
        f.projects.relations[1L] = ProjectConversationEntity(
            conversationId = 1L,
            projectId = 77L,
            assignedAt = 1L,
        )

        val outcome = f.coordinator.branch(1L, 4L)
        val newId = (outcome as BranchOutcome.Success).newConversationId

        assertEquals(77L, f.projects.relations[newId]?.projectId)
        assertEquals(1, f.projects.membershipReadsInsideTransaction)
    }

    @Test
    fun `branch observes a membership mutation at transaction entry`() = runTest {
        val f = standardFixture()
        f.projects.relations[1L] = ProjectConversationEntity(1L, 77L, 1L)
        f.projects.projectIdToSetOnTransactionEntry = 88L

        val outcome = f.coordinator.branch(1L, 4L)
        val newId = (outcome as BranchOutcome.Success).newConversationId

        assertEquals(88L, f.projects.relations[newId]?.projectId)
    }

    @Test
    fun `failed branch project assignment rolls back conversation messages and membership`() = runTest {
        val f = standardFixture()
        f.projects.relations[1L] = ProjectConversationEntity(1L, 77L, 1L)
        f.projects.failAssignment = true

        assertEquals(BranchOutcome.Failure(BranchFailure.DB_ERROR), f.coordinator.branch(1L, 4L))
        assertEquals(1, f.chat.conversations.size)
        assertEquals(7, f.chat.getMessagesForConversation(1L).size)
        assertEquals(setOf(1L), f.projects.relations.keys)
    }

    @Test
    fun `attachment envelope raw content preserved and shared reference survives source deletion`() = runTest {
        val root = Files.createTempDirectory("fusion-branch-attachments").toFile()
        try {
            val managed = File(root, "abc_notes.txt").apply { writeText("로컬 문서", Charsets.UTF_8) }
            val envelope = AttachmentMessageCodec.serializeAttachmentMessage(
                listOf(AttachmentRecord("notes.txt", "text/plain", managed.canonicalPath)),
                "이 문서 요약해줘",
            )
            val chat = FakeChatDao()
            val evidence = FakeEvidenceDao()
            chat.conversations[1L] = ConversationEntity(id = 1L, title = "문서", createdAt = 1L, updatedAt = 2L)
            chat.messages[1L] = MessageEntity(id = 1, conversationId = 1L, role = "user", content = envelope, createdAt = 1L)
            chat.messages[2L] = MessageEntity(id = 2, conversationId = 1L, role = "assistant", content = "요약 답변", createdAt = 2L)
            val coordinator = ConversationBranchCoordinator(
                chatDao = chat,
                evidenceDao = evidence,
                runInTransaction = { block -> block() },
                loadVersions = { ResponseVersionState() },
                clock = { 9999L },
            )
            val outcome = coordinator.branch(1L, 2L)
            val newId = (outcome as BranchOutcome.Success).newConversationId
            val branched = chat.getMessagesForConversation(newId)
            // Exact raw envelope preserved (no parse/reserialize).
            assertEquals(envelope, branched.first { it.role == "user" }.content)

            // Deleting the source conversation must not orphan the shared
            // managed attachment: reference scanning over the remaining
            // (branch) contents still classifies it as referenced.
            val remainingContents = chat.getMessagesForConversation(newId).map { it.content }
            val referenced = extractReferencedAttachmentPaths(remainingContents, root)
            assertTrue(referenced.contains(managed.canonicalPath))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `inactive version unknown message and user anchor fail closed`() = runTest {
        val f = standardFixture()
        assertEquals(BranchOutcome.Failure(BranchFailure.INACTIVE_VERSION), f.coordinator.branch(1L, 7L))
        assertEquals(BranchOutcome.Failure(BranchFailure.UNKNOWN_MESSAGE), f.coordinator.branch(1L, 4242L))
        assertEquals(BranchOutcome.Failure(BranchFailure.UNKNOWN_MESSAGE), f.coordinator.branch(1L, 3L))
        assertEquals(BranchOutcome.Failure(BranchFailure.SOURCE_MISSING), f.coordinator.branch(4242L, 4L))
        // Failures commit nothing.
        assertEquals(1, f.chat.conversations.size)
    }

    @Test
    fun `branch writes no response version state and copies no draft or generation state`() = runTest {
        val f = standardFixture()
        val outcome = f.coordinator.branch(1L, 4L)
        val newId = (outcome as BranchOutcome.Success).newConversationId
        // New conversation begins with ordinary messages and no stale
        // grouping state.
        assertEquals(null, f.versions[newId])
        assertEquals(emptyMap<Long, Long>(), (f.versions[newId] ?: ResponseVersionState()).groupByMessageId)
        // The coordinator API owns no draft/generation handles, so there is
        // nothing to copy by construction; the source row is byte-identical.
        assertEquals(ConversationEntity(id = 1L, title = "HBM 공부", createdAt = 1000L, updatedAt = 6000L), f.chat.getConversationById(1L))
    }

    @Test
    fun `active branch lease keeps deletion from committing until transaction settles`() = runTest {
        val f = standardFixture()
        f.chat.messagesReadStarted = CompletableDeferred()
        f.chat.releaseMessagesRead = CompletableDeferred()
        val branch = async { f.coordinator.branch(1L, 4L) }
        withTimeout(2_000) { f.chat.messagesReadStarted!!.await() }

        val deletion = com.projectnuke.fusion.chat.ConversationStructuralMutationRegistry.beginDeletion(1L)
        val quiescent = async { deletion.awaitQuiescent() }
        assertTrue(!quiescent.isCompleted)
        f.chat.releaseMessagesRead!!.complete(Unit)
        assertTrue(branch.await() is BranchOutcome.Success)
        withTimeout(2_000) { quiescent.await() }
        deletion.finish()
    }

    @Test
    fun `branch snapshot waits for assistant row and response metadata to become coherent`() = runTest {
        val f = standardFixture()
        val persistenceEntered = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        val persistence = async {
            ResponseVersionMutationCoordinator.withMutation(1L) {
                f.chat.messages[8L] = MessageEntity(
                    id = 8L,
                    conversationId = 1L,
                    role = "assistant",
                    content = "A2 새 답변",
                    createdAt = 1008L,
                )
                persistenceEntered.complete(Unit)
                releasePersistence.await()
                f.versions[1L] = f.versions.getValue(1L).copy(
                    groupByMessageId = f.versions.getValue(1L).groupByMessageId + (8L to 40L),
                    activeMessageIdByGroup = mapOf(40L to 8L),
                )
            }
        }
        persistenceEntered.await()

        val branch = async { f.coordinator.branch(1L, 8L) }
        assertTrue(!branch.isCompleted)
        releasePersistence.complete(Unit)
        persistence.await()

        val outcome = branch.await()
        assertTrue(outcome is BranchOutcome.Success)
        val newId = (outcome as BranchOutcome.Success).newConversationId
        assertEquals("A2 새 답변", f.chat.getMessagesForConversation(newId).last().content)
    }

    @Test
    fun `branch requested after deletion fails without creating a conversation`() = runTest {
        val f = standardFixture()
        val deletion = com.projectnuke.fusion.chat.ConversationStructuralMutationRegistry.beginDeletion(1L)
        try {
            assertEquals(
                BranchOutcome.Failure(BranchFailure.SOURCE_DELETING),
                f.coordinator.branch(1L, 4L),
            )
            assertEquals(1, f.chat.conversations.size)
            assertEquals(7, f.chat.getMessagesForConversation(1L).size)
        } finally {
            deletion.finish()
        }
    }

    @Test
    fun `branch title is the semantic source title without a suffix`() {
        assertEquals("HBM 공부", ConversationBranchCoordinator.branchTitle("HBM 공부"))
        assertEquals("새 대화", ConversationBranchCoordinator.branchTitle("   "))
        val long = "제목".repeat(100)
        val titled = ConversationBranchCoordinator.branchTitle(long)
        assertFalse(titled.contains("· 가지"))
        assertTrue(titled.codePointCount(0, titled.length) <= 80)
    }

    @Test
    fun `arbitrary user title containing ga-ji is never mutated`() {
        // branchTitle is only applied at branch creation. A plain user-created
        // title that happens to contain "가지" is returned verbatim (bounded):
        // no suffix is appended and the literal word is never stripped.
        val userTitle = "우리 집 정원에 가지 심기"
        assertEquals(userTitle, ConversationBranchCoordinator.branchTitle(userTitle))
    }
}
