package com.projectnuke.fusion.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectKnowledgeDigestRepositoryTest {
    private lateinit var db: AppDatabase
    private var now = 1_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `digest uses requested range and excludes archived source material until authorized`() = runBlocking {
        val projectId = db.projectDao().insertProject(ProjectEntity(name = "Knowledge", createdAt = now, updatedAt = now))
        val activeId = db.chatDao().insertConversation(
            ConversationEntity(title = "Active conversation", createdAt = now - 100, updatedAt = now - 2 * DAY),
        )
        val archivedId = db.chatDao().insertConversation(
            ConversationEntity(title = "Archived conversation", createdAt = now - 100, updatedAt = now - DAY, isArchived = true),
        )
        db.projectDao().assignConversation(ProjectConversationEntity(activeId, projectId, now - DAY))
        db.projectDao().assignConversation(ProjectConversationEntity(archivedId, projectId, now - DAY))
        db.savedKnowledgeDao().insert(SavedKnowledgeEntity(
            "active-saved", "PROJECT", projectId, "Active saved item", "body", "ANSWER", activeId, null, now - DAY, now - DAY,
        ))
        db.savedKnowledgeDao().insert(SavedKnowledgeEntity(
            "archived-saved", "PROJECT", projectId, "Archived saved item", "body", "ANSWER", archivedId, null, now - DAY, now - DAY,
        ))
        db.savedKnowledgeDao().insert(SavedKnowledgeEntity(
            "older-saved", "PROJECT", projectId, "Older saved item", "body", "ANSWER", activeId, null,
            now - 15 * DAY, now - 15 * DAY,
        ))
        db.savedKnowledgeDao().insert(SavedKnowledgeEntity(
            "stale-saved", "PROJECT", projectId, "Stale source item", "body", "ANSWER", 9999, null,
            now - DAY, now - DAY,
        ))
        db.projectDecisionDao().insert(ProjectDecisionEntity(
            "active-decision", projectId, "Active decision", "", "ACTIVE", sourceConversationId = activeId,
            createdAt = now - DAY, updatedAt = now - DAY,
        ))
        db.projectDecisionDao().insert(ProjectDecisionEntity(
            "archived-decision", projectId, "Archived decision", "", "ACTIVE", sourceConversationId = archivedId,
            createdAt = now - DAY, updatedAt = now - DAY,
        ))
        db.projectTaskDao().insert(ProjectTaskEntity(
            projectId = projectId, title = "Active task", note = "", sourceConversationId = activeId,
            createdAt = now - DAY, updatedAt = now - DAY,
        ))
        db.projectTaskDao().insert(ProjectTaskEntity(
            projectId = projectId, title = "Archived task", note = "", sourceConversationId = archivedId,
            createdAt = now - DAY, updatedAt = now - DAY,
        ))
        db.projectDao().insertProjectFile(ProjectFileEntity(
            projectId = projectId, displayName = "new.pdf", mimeType = "application/pdf", localPath = "managed-file",
            addedAt = now - DAY,
        ))
        db.studyReviewCardDao().insert(StudyReviewCardEntity(
            id = "archived-card", scope = "PROJECT", projectId = projectId, front = "Archived study card", back = "Back",
            sourceKind = "CONVERSATION", sourceConversationId = archivedId, sourceMessageId = null,
            sourceSavedKnowledgeId = null, sourceProjectId = projectId, sourceProjectFileId = null,
            dueAt = now - 1, createdAt = now - DAY, updatedAt = now - DAY,
        ))

        val repository = ProjectKnowledgeDigestRepository(ApplicationProvider.getApplicationContext(), db) { now }
        val sevenDay = repository.load(projectId, 7, includeArchived = false).sections.flatMap { it.entries }.map { it.title }
        assertTrue("Active saved item" in sevenDay)
        assertTrue("Active decision" in sevenDay)
        assertTrue("Active task" in sevenDay)
        assertTrue("new.pdf" in sevenDay)
        assertFalse("Archived saved item" in sevenDay)
        assertFalse("Archived decision" in sevenDay)
        assertFalse("Archived task" in sevenDay)
        assertFalse("Archived study card" in sevenDay)
        assertFalse("Older saved item" in sevenDay)
        assertFalse("Stale source item" in sevenDay)

        val thirtyDay = repository.load(projectId, 30, includeArchived = false).sections.flatMap { it.entries }.map { it.title }
        assertTrue("Older saved item" in thirtyDay)
        val authorized = repository.load(projectId, 7, includeArchived = true).sections.flatMap { it.entries }.map { it.title }
        assertTrue("Archived saved item" in authorized)
        assertTrue("Archived decision" in authorized)
        assertTrue("Archived task" in authorized)
        assertTrue("Archived study card" in authorized)
    }

    private companion object { const val DAY = 24L * 60 * 60 * 1000 }
}
