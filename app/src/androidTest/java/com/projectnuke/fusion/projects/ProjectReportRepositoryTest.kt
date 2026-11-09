package com.projectnuke.fusion.projects

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.ProjectConversationEntity
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectReportRepositoryTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `report rechecks active Project membership and uses only bounded current file summaries`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Current project", createdAt = 1, updatedAt = 1))
        val activeConversationId = database.chatDao().insertConversation(
            ConversationEntity(title = "Active research", createdAt = 2, updatedAt = 2),
        )
        val archivedConversationId = database.chatDao().insertConversation(
            ConversationEntity(title = "Archived private source", createdAt = 2, updatedAt = 2, isArchived = true),
        )
        database.projectDao().assignConversation(ProjectConversationEntity(activeConversationId, 10, 2))
        database.projectDao().assignConversation(ProjectConversationEntity(archivedConversationId, 10, 2))
        val fileId = database.projectDao().insertProjectFile(
            ProjectFileEntity(
                projectId = 10,
                displayName = "call.pdf",
                mimeType = "application/pdf",
                localPath = "managed/call.pdf",
                addedAt = 3,
            ),
        )
        val repository = ProjectReportRepository(database)

        assertNull(
            repository.buildMarkdown(
                projectId = 10,
                authorizedConversationIds = setOf(activeConversationId, archivedConversationId),
                researchSummaries = emptyList(),
                archivedContentAuthorized = false,
                generatedAt = 4,
            ),
        )

        val report = repository.buildMarkdown(
            projectId = 10,
            authorizedConversationIds = setOf(activeConversationId),
            researchSummaries = listOf(ProjectReportResearchSummary(archivedConversationId, "must not leak")),
            sourceSummaries = listOf(
                ProjectReportSourceSummary(fileId, "Deadline is 2026-11-01"),
                ProjectReportSourceSummary(fileId + 1, "stale file summary"),
            ),
            archivedContentAuthorized = false,
            generatedAt = 4,
        )

        assertTrue(report.orEmpty().contains("Deadline is 2026\\-11\\-01"))
        assertFalse(report.orEmpty().contains("Archived private source"))
        assertFalse(report.orEmpty().contains("must not leak"))
        assertFalse(report.orEmpty().contains("stale file summary"))
    }

    @Test
    fun `archive-saturated conversations do not starve active report membership`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 20, name = "P20", createdAt = 0, updatedAt = 0))
        repeat(32) { i ->
            val id = database.chatDao().insertConversation(
                ConversationEntity(title = "Archived $i", createdAt = i.toLong(), updatedAt = 1000L + i, isArchived = true),
            )
            database.projectDao().assignConversation(ProjectConversationEntity(id, 20, i.toLong()))
        }
        val activeOld = database.chatDao().insertConversation(
            ConversationEntity(title = "Active old research", createdAt = 0, updatedAt = 1),
        )
        database.projectDao().assignConversation(ProjectConversationEntity(activeOld, 20, 2000L))

        val report = ProjectReportRepository(database).buildMarkdown(
            projectId = 20,
            authorizedConversationIds = setOf(activeOld),
            researchSummaries = emptyList(),
            archivedContentAuthorized = false,
            generatedAt = 4,
        )

        assertNotNull(report)
        assertTrue(report!!.contains("Active old research"))
        assertFalse(report.contains("Archived 0"))
    }

    @Test
    fun `inaccessible saved knowledge rows do not starve accessible project knowledge`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 30, name = "P30", createdAt = 0, updatedAt = 0))
        repeat(24) { i ->
            val conv = database.chatDao().insertConversation(
                ConversationEntity(title = "source $i", createdAt = 0, updatedAt = 0, isArchived = true),
            )
            database.savedKnowledgeDao().insert(
                SavedKnowledgeEntity(
                    id = "inaccessible-$i",
                    scope = "ASSISTANT_COPY",
                    projectId = 30,
                    title = "inaccessible title $i",
                    savedText = "inaccessible body $i",
                    provenanceType = "ASSISTANT_COPY",
                    sourceConversationId = conv,
                    sourceMessageId = null,
                    createdAt = 100L + i,
                    updatedAt = 100L + i,
                ),
            )
        }
        val accessibleConv = database.chatDao().insertConversation(
            ConversationEntity(title = "accessible source", createdAt = 0, updatedAt = 0),
        )
        database.savedKnowledgeDao().insert(
            SavedKnowledgeEntity(
                id = "accessible-0",
                scope = "ASSISTANT_COPY",
                projectId = 30,
                title = "accessible knowledge title",
                savedText = "accessible knowledge body",
                provenanceType = "ASSISTANT_COPY",
                sourceConversationId = accessibleConv,
                sourceMessageId = null,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val report = ProjectReportRepository(database).buildMarkdown(
            projectId = 30,
            authorizedConversationIds = emptySet(),
            researchSummaries = emptyList(),
            archivedContentAuthorized = false,
            generatedAt = 4,
        )

        assertNotNull(report)
        assertTrue(report!!.contains("accessible knowledge title"))
        assertTrue(report.contains("accessible knowledge body"))
        assertFalse(report.contains("inaccessible title 0"))
        assertFalse(report.contains("inaccessible body 0"))
    }

    @Test
    fun `accessible knowledge is not starved when inaccessible rows exceed previous scan bound`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 31, name = "P31", createdAt = 0, updatedAt = 0))
        repeat(97) { i ->
            val conv = database.chatDao().insertConversation(
                ConversationEntity(title = "archived source $i", createdAt = 0, updatedAt = 0, isArchived = true),
            )
            database.savedKnowledgeDao().insert(
                SavedKnowledgeEntity(
                    id = "inaccessible-$i",
                    scope = "ASSISTANT_COPY",
                    projectId = 31,
                    title = "inaccessible title $i",
                    savedText = "inaccessible body $i",
                    provenanceType = "ASSISTANT_COPY",
                    sourceConversationId = conv,
                    sourceMessageId = null,
                    createdAt = 100L + i,
                    updatedAt = 100L + i,
                ),
            )
        }
        val accessibleConv = database.chatDao().insertConversation(
            ConversationEntity(title = "accessible source", createdAt = 0, updatedAt = 0),
        )
        database.savedKnowledgeDao().insert(
            SavedKnowledgeEntity(
                id = "accessible-boundary",
                scope = "ASSISTANT_COPY",
                projectId = 31,
                title = "accessible boundary knowledge",
                savedText = "accessible boundary body",
                provenanceType = "ASSISTANT_COPY",
                sourceConversationId = accessibleConv,
                sourceMessageId = null,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val report = ProjectReportRepository(database).buildMarkdown(
            projectId = 31,
            authorizedConversationIds = emptySet(),
            researchSummaries = emptyList(),
            archivedContentAuthorized = false,
            generatedAt = 4,
        )

        assertNotNull(report)
        assertTrue(report!!.contains("accessible boundary knowledge"))
        assertTrue(report.contains("accessible boundary body"))
        assertFalse(report.contains("inaccessible title 0"))
        assertFalse(report.contains("inaccessible body 0"))
    }
}
