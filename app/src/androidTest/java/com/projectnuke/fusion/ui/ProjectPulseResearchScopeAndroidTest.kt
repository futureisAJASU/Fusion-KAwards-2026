package com.projectnuke.fusion.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.chat.AnswerContextRoute
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.ProjectSourceScopeKind
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.ProjectConversationEntity
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.projects.ProjectPulseItem
import com.projectnuke.fusion.projects.ProjectReviewSessionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectPulseResearchScopeAndroidTest {
    private val now = 1_800_000_000_000L
    private val projectA = 1L
    private val projectB = 2L
    private val projectBConversation = 100L
    private val projectBMessage = projectBConversation * 100L + 1L
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        database.projectDao().insertProject(ProjectEntity(projectA, "Project A", createdAt = 1, updatedAt = 1))
        database.projectDao().insertProject(ProjectEntity(projectB, "Project B", createdAt = 1, updatedAt = 1))

        repeat(65) { index ->
            insertResearchSource(conversationId = index + 1L, ownerProjectId = projectA)
        }
        insertResearchSource(conversationId = projectBConversation, ownerProjectId = projectB)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun projectPulseCandidateRevalidatesAndResolvesFromTheSameProjectScopedQueue() = runBlocking {
        val repository = projectPulseRepository(context, database) { false }
        val snapshot = repository.load(projectB, now) ?: error("Project B Pulse snapshot should load")
        val researchItem = snapshot.items.filterIsInstance<ProjectPulseItem.Research>().single()
        val requestedTarget = ProjectReviewSessionPolicy.buildItems(snapshot).single().target

        assertEquals(projectB, researchItem.projectId)
        assertEquals(projectBConversation, researchItem.conversationId)
        assertEquals(projectBMessage, researchItem.messageId)
        assertTrue(repository.revalidate(researchItem, now))
        assertEquals(
            ProjectPulseItem.Research(projectB, projectBConversation, projectBMessage, "Research $projectBConversation"),
            repository.resolveCurrentReviewTarget(requestedTarget, now),
        )

        val wrongProjectTarget = ProjectReviewSessionPolicy.ProjectReviewTarget.Research(
            projectA,
            projectBConversation,
            projectBMessage,
        )
        assertEquals(null, repository.resolveCurrentReviewTarget(wrongProjectTarget, now))
    }

    @Test
    fun archiveReassignmentDeletionAndNewerRefreshAreRecheckedAtOpenTime() = runBlocking {
        var includeArchived = false
        val repository = projectPulseRepository(context, database) { includeArchived }
        val snapshot = repository.load(projectB, now) ?: error("Project B Pulse snapshot should load")
        val item = snapshot.items.filterIsInstance<ProjectPulseItem.Research>().single()
        val target = ProjectReviewSessionPolicy.ProjectReviewTarget.Research(projectB, projectBConversation, projectBMessage)

        database.chatDao().archiveConversation(projectBConversation)
        assertFalse(repository.revalidate(item, now))
        assertEquals(null, repository.resolveCurrentReviewTarget(target, now))
        includeArchived = true
        assertTrue(repository.revalidate(item, now))

        includeArchived = false
        database.chatDao().setConversationArchived(projectBConversation, false)
        database.projectDao().removeAssignment(projectBConversation)
        database.projectDao().assignConversation(ProjectConversationEntity(projectBConversation, projectA, now))
        assertFalse(repository.revalidate(item, now))
        assertEquals(null, repository.resolveCurrentReviewTarget(target, now))

        database.projectDao().removeAssignment(projectBConversation)
        database.projectDao().assignConversation(ProjectConversationEntity(projectBConversation, projectB, now))
        insertNewerRefresh()
        assertFalse(repository.revalidate(item, now))
        assertEquals(null, repository.resolveCurrentReviewTarget(target, now))

        database.chatDao().deleteMessageById(projectBMessage)
        assertFalse(repository.revalidate(item, now))
        assertEquals(null, repository.resolveCurrentReviewTarget(target, now))
    }

    @Test
    fun requestedResearchOutsideGlobalVisibleSixteenIsExactlyRevalidatedAndPinned() = runBlocking {
        val projectC = 3L
        database.projectDao().insertProject(ProjectEntity(projectC, "Project C", createdAt = 1, updatedAt = 1))
        repeat(17) { index -> insertResearchSource(200L + index, projectC) }
        val conversationId = 216L
        val messageId = conversationId * 100L + 1L
        val repository = ResearchRefreshQueueRepository(RoomResearchRefreshQueueDataSource(database, context))

        val visible = repository.load(now = now, requestedTarget = conversationId to messageId)
        assertEquals(ResearchRefreshQueueTargetStatus.ELIGIBLE, visible.requestedTargetStatus)
        assertTrue(visible.visibleLimitReached)
        assertEquals(ResearchRefreshQueuePolicy.MAX_CANDIDATES, visible.candidates.size)
        assertTrue(visible.candidates.any {
            it.sourceConversationId == conversationId && it.sourceMessageId == messageId
        })

        val inactiveVersionId = messageId + 1L
        database.chatDao().insertMessage(
            MessageEntity(inactiveVersionId, conversationId, "assistant", researchAnswer(projectC), now),
        )
        assertTrue(
            saveResponseVersionState(
                context,
                conversationId,
                ResponseVersionState(
                    groupByMessageId = mapOf(messageId to 77L, inactiveVersionId to 77L),
                    activeMessageIdByGroup = mapOf(77L to inactiveVersionId),
                ),
            ),
        )
        val inactive = repository.load(now = now, requestedTarget = conversationId to messageId)
        assertEquals(ResearchRefreshQueueTargetStatus.INELIGIBLE, inactive.requestedTargetStatus)
        assertFalse(inactive.candidates.any { it.sourceConversationId == conversationId && it.sourceMessageId == messageId })
        assertTrue(deleteResponseVersionState(context, conversationId))
        database.chatDao().deleteMessageById(inactiveVersionId)

        database.chatDao().archiveConversation(conversationId)
        val locked = repository.load(now = now, includeArchived = false, requestedTarget = conversationId to messageId)
        assertEquals(ResearchRefreshQueueTargetStatus.INELIGIBLE, locked.requestedTargetStatus)
        assertFalse(locked.candidates.any { it.sourceConversationId == conversationId && it.sourceMessageId == messageId })

        val unlocked = repository.load(now = now, includeArchived = true, requestedTarget = conversationId to messageId)
        assertEquals(ResearchRefreshQueueTargetStatus.ELIGIBLE, unlocked.requestedTargetStatus)

        database.chatDao().setConversationArchived(conversationId, false)
        database.projectDao().removeAssignment(conversationId)
        database.projectDao().assignConversation(ProjectConversationEntity(conversationId, projectA, now))
        val reassigned = repository.load(now = now, requestedTarget = conversationId to messageId)
        assertEquals(ResearchRefreshQueueTargetStatus.INELIGIBLE, reassigned.requestedTargetStatus)

        database.projectDao().removeAssignment(conversationId)
        database.projectDao().assignConversation(ProjectConversationEntity(conversationId, projectC, now))
        database.chatDao().insertConversation(
            ConversationEntity(
                id = 999L,
                title = "Newer refresh",
                createdAt = now - 2_000,
                updatedAt = now - 1_000,
                refreshedFromConversationId = conversationId,
                refreshedFromMessageId = messageId,
            ),
        )
        database.chatDao().insertMessage(
            MessageEntity(99_901L, 999L, "assistant", researchAnswer(projectC), now - 1_000),
        )
        val refreshed = repository.load(now = now, requestedTarget = conversationId to messageId)
        assertEquals(ResearchRefreshQueueTargetStatus.INELIGIBLE, refreshed.requestedTargetStatus)

        database.chatDao().deleteMessageById(messageId)
        val deleted = repository.load(now = now, requestedTarget = conversationId to messageId)
        assertEquals(ResearchRefreshQueueTargetStatus.INELIGIBLE, deleted.requestedTargetStatus)
    }

    private suspend fun insertResearchSource(conversationId: Long, ownerProjectId: Long) {
        val sourceTime = now - 40L * DAY_MS
        database.chatDao().insertConversation(
            ConversationEntity(
                id = conversationId,
                title = "Research $conversationId",
                createdAt = sourceTime - 100,
                updatedAt = sourceTime,
            ),
        )
        database.projectDao().assignConversation(ProjectConversationEntity(conversationId, ownerProjectId, now))
        database.chatDao().insertMessage(
            MessageEntity(
                id = conversationId * 100L,
                conversationId = conversationId,
                role = "user",
                content = "Question $conversationId",
                createdAt = sourceTime - 1,
            ),
        )
        database.chatDao().insertMessage(
            MessageEntity(
                id = conversationId * 100L + 1L,
                conversationId = conversationId,
                role = "assistant",
                content = researchAnswer(ownerProjectId),
                createdAt = sourceTime,
            ),
        )
    }

    private suspend fun insertNewerRefresh() {
        val refreshedConversationId = 100L
        database.chatDao().insertConversation(
            ConversationEntity(
                id = refreshedConversationId,
                title = "Newer Research",
                createdAt = now - 2_000,
                updatedAt = now - 1_000,
                refreshedFromConversationId = projectBConversation,
                refreshedFromMessageId = projectBMessage,
            ),
        )
        database.chatDao().insertMessage(
            MessageEntity(
                id = 10_001L,
                conversationId = refreshedConversationId,
                role = "assistant",
                content = researchAnswer(projectB),
                createdAt = now - 1_000,
            ),
        )
    }

    private fun researchAnswer(projectId: Long): String = AnswerContextReceipt.append(
        "Previous visible Research answer",
        AnswerContextReceipt.build(
            route = AnswerContextRoute.LOCAL_MODEL,
            interactionMode = ChatInteractionMode.RESEARCH.name,
            projectContextUsed = true,
            projectContextId = projectId,
            sourceScopeKind = ProjectSourceScopeKind.PROJECT.name,
            sourceScopeProjectId = projectId,
        ),
    )

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
