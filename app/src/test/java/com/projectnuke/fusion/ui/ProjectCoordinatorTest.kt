package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ChatDao
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.MessageContentHit
import com.projectnuke.fusion.data.MessageContentPageRow
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.MessageIdentity
import com.projectnuke.fusion.data.ProjectConversationEntity
import com.projectnuke.fusion.data.ProjectDao
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.data.BoundedProjectFileInsertResult
import com.projectnuke.fusion.chat.ProjectInstructionPromptPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Projects v1 organization: create/assign/move/remove/delete-preserves,
// deterministic listing, normalized titles, no invalid relations.
class ProjectCoordinatorTest {

    private class FakeProjectDao : ProjectDao {
        val projects = linkedMapOf<Long, ProjectEntity>()
        val relations = linkedMapOf<Long, ProjectConversationEntity>()
        val files = linkedMapOf<Long, ProjectFileEntity>()
        var nextId = 1L
        var nextFileId = 1L
        var failAssign = false
        var failDeleteProjectFile = false
        var cancelInsertProject = false
        var cancelAssign = false
        var cancelUpdateInstructions = false
        var cancelInsertProjectFile = false
        var cancelDeleteProjectFile = false
        var cancelRemoveAssignment = false
        var cancelDeleteProject = false
        private val boundedFileInsertLock = Any()

        override suspend fun insertProject(project: ProjectEntity): Long {
            if (cancelInsertProject) throw CancellationException("project insert cancelled")
            val id = nextId++
            projects[id] = project.copy(id = id)
            return id
        }

        override fun observeProjects(): Flow<List<ProjectEntity>> = emptyFlow()

        override suspend fun getRecentProjectsBounded(limit: Int): List<ProjectEntity> =
            projects.values.sortedWith(compareByDescending<ProjectEntity> { it.updatedAt }.thenByDescending { it.id }).take(limit)

        override suspend fun getProjectFileById(fileId: Long): ProjectFileEntity? = files[fileId]

        override suspend fun getProjects(): List<ProjectEntity> =
            projects.values.sortedWith(compareByDescending<ProjectEntity> { it.updatedAt }.thenByDescending { it.id })

        override suspend fun getProjectById(projectId: Long): ProjectEntity? = projects[projectId]
        override suspend fun getProjectForDeletion(projectId: Long): ProjectEntity? = projects[projectId]
        override suspend fun getPendingDeletionsAfter(afterId: Long, limit: Int): List<Long> =
            projects.values.filter { it.deletionPending && it.id > afterId }.sortedBy { it.id }.take(limit).map { it.id }
        override suspend fun beginDeletion(projectId: Long): Int =
            projects[projectId]?.let { if (it.deletionPending) 0 else { projects[projectId] = it.copy(deletionPending = true); 1 } } ?: 0
        override suspend fun finishDeletion(projectId: Long): Int =
            if (projects[projectId]?.deletionPending == true) { projects.remove(projectId); 1 } else 0

        override suspend fun hasProjectName(name: String): Boolean = projects.values.any { it.name.equals(name, ignoreCase = true) }

        override suspend fun updateProjectName(projectId: Long, name: String, updatedAt: Long) {
            projects[projectId]?.let { projects[projectId] = it.copy(name = name, updatedAt = updatedAt) }
        }

        override suspend fun updateProjectInstructions(projectId: Long, instructions: String, updatedAt: Long): Int {
            if (cancelUpdateInstructions) throw CancellationException("instructions update cancelled")
            return if (projects[projectId] != null) {
                projects[projectId] = projects[projectId]!!.copy(instructions = instructions, updatedAt = updatedAt)
                1
            } else 0
        }

        override suspend fun deleteProject(projectId: Long) {
            if (cancelDeleteProject) throw CancellationException("project deletion cancelled")
            projects.remove(projectId)
            relations.entries.removeIf { it.value.projectId == projectId }
            files.entries.removeIf { it.value.projectId == projectId }
        }

        override suspend fun getProjectFiles(projectId: Long): List<ProjectFileEntity> =
            files.values.filter { it.projectId == projectId }

        override suspend fun getProjectFilesBounded(projectId: Long, limit: Int): List<ProjectFileEntity> =
            files.values.filter { it.projectId == projectId }.take(limit)

        override suspend fun getRecentProjectFilesBounded(projectId: Long, limit: Int): List<ProjectFileEntity> =
            files.values.filter { it.projectId == projectId }.sortedByDescending { it.addedAt }.take(limit)

        override fun observeProjectFiles(projectId: Long): Flow<List<ProjectFileEntity>> = emptyFlow()

        override suspend fun insertProjectFile(file: ProjectFileEntity): Long {
            val id = nextFileId++
            files[id] = file.copy(id = id)
            return id
        }

        override suspend fun insertProjectFileBounded(file: ProjectFileEntity): BoundedProjectFileInsertResult =
            synchronized(boundedFileInsertLock) {
                if (cancelInsertProjectFile) throw CancellationException("project file insert cancelled")
                if (projects[file.projectId] == null) {
                    BoundedProjectFileInsertResult.UnknownProject
                } else {
                    val existing = files.values.firstOrNull {
                        it.projectId == file.projectId && it.localPath == file.localPath
                    }
                    when {
                        existing != null -> BoundedProjectFileInsertResult.Existing(existing.id)
                        files.values.count { it.projectId == file.projectId } >= ProjectFilePolicy.MAX_PROJECT_FILES ->
                            BoundedProjectFileInsertResult.CapacityReached
                        else -> {
                            val id = nextFileId++
                            files[id] = file.copy(id = id)
                            BoundedProjectFileInsertResult.Inserted(id)
                        }
                    }
                }
            }

        override suspend fun deleteProjectFile(fileId: Long): Int =
            if (cancelDeleteProjectFile) throw CancellationException("project file deletion cancelled")
            else if (failDeleteProjectFile) throw IllegalStateException("delete failure")
            else if (files.remove(fileId) != null) 1 else 0

        override suspend fun getProjectFileByHandoffEventId(eventId: String): ProjectFileEntity? =
            files.values.firstOrNull { it.sourceHandoffEventId == eventId }

        override suspend fun getProjectFilesByHandoffPrefix(eventIdPrefix: String, limit: Int): List<ProjectFileEntity> =
            files.values.filter { it.sourceHandoffEventId?.startsWith(eventIdPrefix.removeSuffix("%")) == true }.take(limit)

        override suspend fun getProjectFileByPath(projectId: Long, localPath: String): ProjectFileEntity? =
            files.values.firstOrNull { it.projectId == projectId && it.localPath == localPath }

        override suspend fun countProjectFiles(projectId: Long): Int =
            files.values.count { it.projectId == projectId }

        override suspend fun getAllProjectFilePaths(): List<String> = files.values.map { it.localPath }

        override suspend fun assignConversation(relation: ProjectConversationEntity) {
            if (cancelAssign) throw CancellationException("assignment cancelled")
            if (failAssign) throw IllegalStateException("failing assignment")
            relations[relation.conversationId] = relation
        }

        override suspend fun removeAssignment(conversationId: Long) {
            if (cancelRemoveAssignment) throw CancellationException("assignment removal cancelled")
            relations.remove(conversationId)
        }

        override suspend fun getProjectIdForConversation(conversationId: Long): Long? =
            relations[conversationId]?.projectId

        override suspend fun getConversationsForProject(projectId: Long): List<ConversationEntity> =
            emptyList()

        override suspend fun getConversationsForProjectBounded(projectId: Long, limit: Int): List<ConversationEntity> =
            emptyList()

        override suspend fun getActiveConversationsForProjectBounded(projectId: Long, limit: Int): List<ConversationEntity> =
            emptyList()

        override suspend fun getAssignmentCount(projectId: Long): Int =
            relations.values.count { it.projectId == projectId }
    }

    private class FakeChatDao(
        private val projects: FakeProjectDao,
    ) : ChatDao {
        val conversations = linkedMapOf<Long, ConversationEntity>()

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
        override suspend fun getConversationById(conversationId: Long): ConversationEntity? =
            conversations[conversationId]
        override suspend fun getMessageIdentityById(messageId: Long): MessageIdentity? = null
        override suspend fun getMessageById(messageId: Long): MessageEntity? = null
        override suspend fun getLatestConversation(): ConversationEntity? = null
        override suspend fun getConversationCount(): Int = conversations.size
        override suspend fun getMessageCount(): Int = 0
        override suspend fun getArchivedConversationCount(): Int = 0
        override suspend fun getMessageCountForConversation(conversationId: Long): Int = 0
        override suspend fun updateConversationTime(conversationId: Long, updatedAt: Long) = Unit
        override suspend fun updateMessageContent(messageId: Long, content: String) = Unit
        override suspend fun updateConversationPinned(conversationId: Long, isPinned: Boolean) = Unit
        override suspend fun updateConversationTitle(conversationId: Long, title: String) = Unit
        override suspend fun archiveConversation(conversationId: Long) = Unit
        override suspend fun setConversationArchived(conversationId: Long, archived: Boolean) = Unit
        override suspend fun deleteConversation(conversationId: Long) {
            conversations.remove(conversationId)
            // Simulates the FK cascade: relation dies with the conversation.
            projects.relations.remove(conversationId)
        }

        override suspend fun deleteMessagesForConversation(conversationId: Long) = Unit
        override suspend fun deleteMessageById(messageId: Long) = Unit
        override suspend fun getMessageContentHitsLike(query: String, limit: Int, offset: Int): List<MessageContentHit> =
            emptyList()
    }

    private fun fixture(
        clock: () -> Long = { 1000L },
        runInTransaction: (suspend (suspend () -> Long) -> Long)? = null,
    ): Triple<ProjectCoordinator, FakeProjectDao, FakeChatDao> {
        val projects = FakeProjectDao()
        val chat = FakeChatDao(projects)
        return Triple(
            ProjectCoordinator(
                projects, chat, runInTransaction ?: { block -> block() }, clock,
                deleteProjectSafely = { projectId -> projects.deleteProject(projectId); true },
            ),
            projects,
            chat,
        )
    }

    private fun conversation(id: Long, title: String = "chat"): ConversationEntity =
        ConversationEntity(id = id, title = title, createdAt = 1L, updatedAt = 2L)

    private suspend fun assertCancellation(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("expected CancellationException")
        } catch (_: CancellationException) {
            // The coordinator must rethrow cancellation, not return DB_ERROR.
        }
    }

    @Test
    fun `create project assigns current conversation`() = runTest {
        val (coordinator, projects, chat) = fixture()
        chat.conversations[10L] = conversation(10L)
        val outcome = coordinator.createProject("공부", 10L)
        assertTrue(outcome is ProjectOutcome.Ok)
        val projectId = (outcome as ProjectOutcome.Ok).value
        assertEquals("공부", projects.projects[projectId]?.name)
        assertEquals(projectId, projects.getProjectIdForConversation(10L))
    }

    @Test
    fun `create project without assignment fabricates nothing`() = runTest {
        val (coordinator, projects, _) = fixture()
        val outcome = coordinator.createProject("독서")
        assertTrue(outcome is ProjectOutcome.Ok)
        assertEquals(1, projects.projects.size)
        assertTrue(projects.relations.isEmpty())
    }

    @Test
    fun `blank name and invalid conversation fail closed`() = runTest {
        val (coordinator, projects, chat) = fixture()
        chat.conversations[10L] = conversation(10L)
        assertEquals(ProjectOutcome.Failure(ProjectFailure.EMPTY_NAME), coordinator.createProject("   "))
        assertEquals(ProjectOutcome.Failure(ProjectFailure.INVALID_CONVERSATION), coordinator.createProject("x", 0L))
        assertEquals(ProjectOutcome.Failure(ProjectFailure.INVALID_CONVERSATION), coordinator.createProject("x", -3L))
        assertEquals(ProjectOutcome.Failure(ProjectFailure.UNKNOWN_CONVERSATION), coordinator.createProject("x", 4242L))
        assertTrue(projects.projects.isEmpty())
        assertEquals(ProjectOutcome.Failure(ProjectFailure.INVALID_CONVERSATION), coordinator.assignConversation(1L, 0L))
        assertEquals(ProjectOutcome.Failure(ProjectFailure.UNKNOWN_PROJECT), coordinator.assignConversation(4242L, 10L))
        val existing = (coordinator.createProject("exists") as ProjectOutcome.Ok).value
        assertEquals(ProjectOutcome.Failure(ProjectFailure.UNKNOWN_CONVERSATION), coordinator.assignConversation(existing, 4242L))
        assertTrue(projects.relations.isEmpty())
    }

    @Test
    fun `assign move and same-project reassignment`() = runTest {
        val (coordinator, projects, chat) = fixture()
        chat.conversations[10L] = conversation(10L)
        val first = (coordinator.createProject("A") as ProjectOutcome.Ok).value
        val second = (coordinator.createProject("B") as ProjectOutcome.Ok).value
        assertEquals(ProjectOutcome.Ok(Unit), coordinator.assignConversation(first, 10L))
        assertEquals(first, projects.getProjectIdForConversation(10L))
        // Move: REPLACE keeps exactly one relation.
        assertEquals(ProjectOutcome.Ok(Unit), coordinator.assignConversation(second, 10L))
        assertEquals(second, projects.getProjectIdForConversation(10L))
        assertEquals(0, projects.getAssignmentCount(first))
        assertEquals(1, projects.getAssignmentCount(second))
        // Same-project reassignment is a harmless no-op.
        assertEquals(ProjectOutcome.Ok(Unit), coordinator.assignConversation(second, 10L))
        assertEquals(1, projects.relations.size)
    }

    @Test
    fun `remove assignment keeps project and conversation`() = runTest {
        val (coordinator, projects, chat) = fixture()
        chat.conversations[10L] = conversation(10L)
        val projectId = (coordinator.createProject("A", 10L) as ProjectOutcome.Ok).value
        assertEquals(ProjectOutcome.Ok(Unit), coordinator.removeAssignment(10L))
        assertEquals(null, projects.getProjectIdForConversation(10L))
        assertTrue(projects.projects.containsKey(projectId))
        assertTrue(chat.conversations.containsKey(10L))
    }

    @Test
    fun `delete project preserves conversations`() = runTest {
        val (coordinator, projects, chat) = fixture()
        chat.conversations[10L] = conversation(10L, "pinned chat").copy(isPinned = true, isArchived = false)
        chat.conversations[11L] = conversation(11L, "other")
        val projectId = (coordinator.createProject("A", 10L) as ProjectOutcome.Ok).value
        assertEquals(ProjectOutcome.Ok(Unit), coordinator.deleteProject(projectId))
        assertTrue(projects.projects.isEmpty())
        assertTrue(projects.relations.isEmpty())
        // Member conversations remain in normal history, fields untouched.
        assertEquals(conversation(10L, "pinned chat").copy(isPinned = true), chat.conversations[10L])
        assertEquals(conversation(11L, "other"), chat.conversations[11L])
    }

    @Test
    fun `deleting conversation removes its relation`() = runTest {
        val (coordinator, projects, chat) = fixture()
        chat.conversations[10L] = conversation(10L)
        (coordinator.createProject("A", 10L) as ProjectOutcome.Ok)
        chat.deleteConversation(10L)
        assertTrue(projects.relations.isEmpty())
        assertEquals(1, projects.projects.size)
    }

    @Test
    fun `project list is deterministic and titles normalized`() = runTest {
        val (coordinator, projects, _) = fixture()
        (coordinator.createProject("B") as ProjectOutcome.Ok)
        (coordinator.createProject("A") as ProjectOutcome.Ok)
        val listed = projects.getProjects()
        assertEquals(2, listed.size)
        assertEquals(listed.map { it.id }, listed.map { it.id }.sortedDescending())
        assertEquals("공부", ProjectCoordinator.projectName("  공부  "))
        assertEquals("새 프로젝트", ProjectCoordinator.projectName("   "))
        val long = ProjectCoordinator.projectName("제".repeat(200))
        assertTrue(long.codePointCount(0, long.length) <= 80)
    }

    // ---- atomic create + optional assign ----------------------------------------------

    /**
     * Emulates a Room transaction over the in-memory fake: both statements
     * run inside ONE boundary, and any failure rolls every statement back.
     * A coordinator that performed the insert outside the transaction would
     * leave the project behind.
     */
    private class RollbackTransaction(val projects: FakeProjectDao) {
        var calls = 0

        suspend fun run(block: suspend () -> Long): Long {
            calls++
            val projectSnapshot = LinkedHashMap(projects.projects)
            val relationSnapshot = LinkedHashMap(projects.relations)
            try {
                return block()
            } catch (failure: Exception) {
                projects.projects.clear()
                projects.projects.putAll(projectSnapshot)
                projects.relations.clear()
                projects.relations.putAll(relationSnapshot)
                throw failure
            }
        }
    }

    @Test
    fun `create with failing assignment leaves no orphan project`() = runTest {
        val probe = FakeProjectDao()
        val chat = FakeChatDao(probe)
        val tx = RollbackTransaction(probe)
        val coordinator = ProjectCoordinator(probe, chat, tx::run, clock = { 1000L })
        chat.conversations[10L] = conversation(10L)
        probe.failAssign = true
        val outcome = coordinator.createProject("공부", 10L)
        assertEquals(ProjectOutcome.Failure(ProjectFailure.DB_ERROR), outcome)
        // No-orphan contract: the rolled-back insert leaves nothing behind.
        assertTrue(probe.projects.isEmpty())
        assertTrue(probe.relations.isEmpty())
        // Both statements ran inside exactly one transaction boundary.
        assertEquals(1, tx.calls)
    }

    @Test
    fun `project mutations propagate cancellation and create rolls back atomically`() = runTest {
        run {
            val projects = FakeProjectDao()
            val chat = FakeChatDao(projects)
            val tx = RollbackTransaction(projects)
            val coordinator = ProjectCoordinator(projects, chat, tx::run, clock = { 1000L })
            chat.conversations[10L] = conversation(10L)
            projects.cancelAssign = true
            assertCancellation { coordinator.createProject("cancel", 10L) }
            assertTrue(projects.projects.isEmpty())
            assertTrue(projects.relations.isEmpty())
        }

        run {
            val (coordinator, projects, chat) = fixture()
            val projectId = (coordinator.createProject("assign") as ProjectOutcome.Ok).value
            chat.conversations[10L] = conversation(10L)
            projects.cancelAssign = true
            assertCancellation { coordinator.assignConversation(projectId, 10L) }
        }

        run {
            val (coordinator, projects, _) = fixture()
            val projectId = (coordinator.createProject("instructions") as ProjectOutcome.Ok).value
            projects.cancelUpdateInstructions = true
            assertCancellation { coordinator.updateInstructions(projectId, "cancel") }
        }

        run {
            val (coordinator, projects, _) = fixture()
            val projectId = (coordinator.createProject("file") as ProjectOutcome.Ok).value
            projects.cancelInsertProjectFile = true
            assertCancellation {
                coordinator.insertProjectFile(
                    ProjectFileEntity(
                        projectId = projectId,
                        displayName = "cancel.txt",
                        mimeType = "text/plain",
                        localPath = "/managed/cancel.txt",
                        addedAt = 1L,
                    ),
                )
            }
        }

        run {
            val (coordinator, projects, _) = fixture()
            val projectId = (coordinator.createProject("file") as ProjectOutcome.Ok).value
            val file = coordinator.insertProjectFile(
                ProjectFileEntity(
                    projectId = projectId,
                    displayName = "cancel.txt",
                    mimeType = "text/plain",
                    localPath = "/managed/cancel.txt",
                    addedAt = 1L,
                ),
            ) as ProjectOutcome.Ok
            projects.cancelDeleteProjectFile = true
            assertCancellation { coordinator.removeProjectFile(file.value) }
        }

        run {
            val (coordinator, projects, chat) = fixture()
            chat.conversations[10L] = conversation(10L)
            val projectId = (coordinator.createProject("assignment", 10L) as ProjectOutcome.Ok).value
            assertEquals(projectId, projects.getProjectIdForConversation(10L))
            projects.cancelRemoveAssignment = true
            assertCancellation { coordinator.removeAssignment(10L) }
        }

        run {
            val (coordinator, projects, _) = fixture()
            val projectId = (coordinator.createProject("delete") as ProjectOutcome.Ok).value
            projects.cancelDeleteProject = true
            assertCancellation { coordinator.deleteProject(projectId) }
        }
    }

    @Test
    fun `create with assignment commits atomically in one transaction`() = runTest {
        val probe = FakeProjectDao()
        val chat = FakeChatDao(probe)
        val tx = RollbackTransaction(probe)
        val coordinator = ProjectCoordinator(probe, chat, tx::run, clock = { 1000L })
        chat.conversations[10L] = conversation(10L)
        val outcome = coordinator.createProject("공부", 10L)
        assertTrue(outcome is ProjectOutcome.Ok)
        val projectId = (outcome as ProjectOutcome.Ok).value
        assertEquals("공부", probe.projects[projectId]?.name)
        assertEquals(projectId, probe.getProjectIdForConversation(10L))
        assertEquals(1, tx.calls)
    }

    @Test
    fun `create without assignment succeeds without relations`() = runTest {
        val probe = FakeProjectDao()
        val chat = FakeChatDao(probe)
        val tx = RollbackTransaction(probe)
        val coordinator = ProjectCoordinator(probe, chat, tx::run, clock = { 1000L })
        val outcome = coordinator.createProject("독서")
        assertTrue(outcome is ProjectOutcome.Ok)
        assertEquals(1, probe.projects.size)
        assertTrue(probe.relations.isEmpty())
        assertEquals(1, tx.calls)
    }

    @Test
    fun `validation happens before any mutation or transaction`() = runTest {
        val probe = FakeProjectDao()
        val chat = FakeChatDao(probe)
        val tx = RollbackTransaction(probe)
        val coordinator = ProjectCoordinator(probe, chat, tx::run, clock = { 1000L })
        chat.conversations[10L] = conversation(10L)
        assertEquals(
            ProjectOutcome.Failure(ProjectFailure.UNKNOWN_CONVERSATION),
            coordinator.createProject("x", 4242L),
        )
        assertEquals(
            ProjectOutcome.Failure(ProjectFailure.INVALID_CONVERSATION),
            coordinator.createProject("x", 0L),
        )
        assertEquals(
            ProjectOutcome.Failure(ProjectFailure.EMPTY_NAME),
            coordinator.createProject("   ", 10L),
        )
        assertEquals(0, tx.calls)
        assertTrue(probe.projects.isEmpty())
        assertTrue(probe.relations.isEmpty())
    }

    @Test
    fun `instructions save preserves newlines and rejects over-limit input`() = runTest {
        val (coordinator, projects, _) = fixture()
        val projectId = (coordinator.createProject("instructions") as ProjectOutcome.Ok).value
        val instructions = "첫 줄\n\n둘째 줄"

        assertEquals(ProjectOutcome.Ok(Unit), coordinator.updateInstructions(projectId, instructions))
        assertEquals(instructions, projects.projects[projectId]?.instructions)

        val tooLong = "x".repeat(ProjectInstructionPromptPolicy.MAX_PROJECT_INSTRUCTION_CHARS + 1)
        assertEquals(
            ProjectOutcome.Failure(ProjectFailure.INSTRUCTIONS_TOO_LONG),
            coordinator.updateInstructions(projectId, tooLong),
        )
        assertEquals(instructions, projects.projects[projectId]?.instructions)
    }

    @Test
    fun `project file insertion is idempotent and bounded at eight`() = runTest {
        val (coordinator, projects, _) = fixture()
        val projectId = (coordinator.createProject("files") as ProjectOutcome.Ok).value
        fun file(index: Int) = ProjectFileEntity(
            projectId = projectId,
            displayName = "file-$index.txt",
            mimeType = "text/plain",
            localPath = "/managed/file-$index.txt",
            addedAt = index.toLong(),
        )

        val first = coordinator.insertProjectFile(file(1)) as ProjectOutcome.Ok
        assertEquals(first.value, (coordinator.insertProjectFile(file(1)) as ProjectOutcome.Ok).value)
        (2..8).forEach { assertTrue(coordinator.insertProjectFile(file(it)) is ProjectOutcome.Ok) }
        assertEquals(
            ProjectOutcome.Failure(ProjectFailure.PROJECT_FILE_LIMIT),
            coordinator.insertProjectFile(file(9)),
        )
        assertEquals(8, projects.files.size)
    }

    @Test
    fun `concurrent bounded project file insertion never exceeds eight`() = runTest {
        val (coordinator, projects, _) = fixture()
        val projectId = (coordinator.createProject("concurrent files") as ProjectOutcome.Ok).value

        val outcomes = coroutineScope {
            (1..32).map { index ->
                async(Dispatchers.Default) {
                    coordinator.insertProjectFile(
                        ProjectFileEntity(
                            projectId = projectId,
                            displayName = "file-$index.txt",
                            mimeType = "text/plain",
                            localPath = "/managed/file-$index.txt",
                            addedAt = index.toLong(),
                        ),
                    )
                }
            }.awaitAll()
        }

        assertEquals(8, projects.files.size)
        assertEquals(8, outcomes.count { it is ProjectOutcome.Ok })
        assertEquals(24, outcomes.count { it == ProjectOutcome.Failure(ProjectFailure.PROJECT_FILE_LIMIT) })
    }

    @Test
    fun `project file removal reports missing and database failures`() = runTest {
        val (coordinator, projects, _) = fixture()
        val projectId = (coordinator.createProject("remove") as ProjectOutcome.Ok).value
        val inserted = coordinator.insertProjectFile(
            ProjectFileEntity(
                projectId = projectId,
                displayName = "remove.txt",
                mimeType = "text/plain",
                localPath = "/managed/remove.txt",
                addedAt = 1L,
            ),
        ) as ProjectOutcome.Ok

        projects.failDeleteProjectFile = true
        assertEquals(ProjectOutcome.Failure(ProjectFailure.DB_ERROR), coordinator.removeProjectFile(inserted.value))
        assertEquals(1, projects.files.size)

        projects.failDeleteProjectFile = false
        assertEquals(ProjectOutcome.Ok(Unit), coordinator.removeProjectFile(inserted.value))
        assertEquals(ProjectOutcome.Failure(ProjectFailure.PROJECT_FILE_NOT_FOUND), coordinator.removeProjectFile(inserted.value))
    }

    @Test
    fun `context snapshot does not change after project edits`() = runTest {
        val (coordinator, projects, chat) = fixture()
        chat.conversations[10L] = conversation(10L)
        val projectId = (coordinator.createProject("snapshot", 10L) as ProjectOutcome.Ok).value
        coordinator.updateInstructions(projectId, "before")
        coordinator.insertProjectFile(
            ProjectFileEntity(
                projectId = projectId,
                displayName = "before.txt",
                mimeType = "text/plain",
                localPath = "/managed/before.txt",
                addedAt = 1L,
            ),
        )
        val snapshot = coordinator.loadContextSnapshot(10L)!!

        coordinator.updateInstructions(projectId, "after")
        projects.files[2L] = ProjectFileEntity(
            id = 2L,
            projectId = projectId,
            displayName = "after.txt",
            mimeType = "text/plain",
            localPath = "/managed/after.txt",
            addedAt = 2L,
        )

        assertEquals("before", snapshot.instructions)
        assertEquals(listOf("/managed/before.txt"), snapshot.files.map { it.localPath })
    }

    @Test
    fun `shared context is available to each member but absent from non-project chat`() = runTest {
        val (coordinator, _, chat) = fixture()
        chat.conversations[10L] = conversation(10L, "A")
        chat.conversations[11L] = conversation(11L, "B")
        chat.conversations[12L] = conversation(12L, "outside")
        val projectId = (coordinator.createProject("shared") as ProjectOutcome.Ok).value
        coordinator.insertProjectFile(
            ProjectFileEntity(projectId = projectId, displayName = "shared.txt", mimeType = "text/plain", localPath = "/managed/shared", addedAt = 1L),
        )
        coordinator.assignConversation(projectId, 10L)
        coordinator.assignConversation(projectId, 11L)

        assertEquals("/managed/shared", coordinator.loadContextSnapshot(10L)?.files?.single()?.localPath)
        assertEquals("/managed/shared", coordinator.loadContextSnapshot(11L)?.files?.single()?.localPath)
        assertEquals(null, coordinator.loadContextSnapshot(12L))
    }
}
