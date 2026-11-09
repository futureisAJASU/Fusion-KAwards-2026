package com.projectnuke.fusion.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnowledgeSearchDaoAndroidTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `body search finds old conversation outside title and recent conversation caps`() = runBlocking {
        val dao = database.knowledgeSearchDao()
        val targetId = database.chatDao().insertConversation(
            ConversationEntity(title = "unrelated title", createdAt = 1, updatedAt = 1),
        )
        repeat(49) { index ->
            val conversationId = database.chatDao().insertConversation(
                ConversationEntity(title = "recent $index", createdAt = 10L + index, updatedAt = 10L + index),
            )
            if (index == 48) {
                repeat(100) { messageIndex ->
                    database.chatDao().insertMessage(
                        MessageEntity(
                            conversationId = conversationId,
                            role = "user",
                            content = "recent message $messageIndex",
                            createdAt = 100L + messageIndex,
                        ),
                    )
                }
            }
        }
        database.chatDao().insertMessage(
            MessageEntity(conversationId = targetId, role = "assistant", content = "needle in old body", createdAt = 2),
        )

        val titleRows = dao.searchConversationTitles("needle", includeArchived = false, limit = 48)
        val recentRows = dao.getSearchConversations(includeArchived = false, limit = 48)
        val bodyRows = dao.searchConversationMessageHits("needle", includeArchived = false, limit = 96)

        assertFalse(titleRows.any { it.id == targetId })
        assertFalse(recentRows.any { it.id == targetId })
        assertEquals(targetId, bodyRows.single().conversation.id)
        assertEquals("needle in old body", bodyRows.single().content)
    }

    @Test
    fun `project search and files apply selected project before their row limits`() = runBlocking {
        val dao = database.knowledgeSearchDao()
        val projectDao = database.projectDao()
        val targetProjectId = projectDao.insertProject(
            ProjectEntity(name = "Older project", instructions = "needle in instructions", createdAt = 1, updatedAt = 1),
        )
        val targetFileId = projectDao.insertProjectFile(
            ProjectFileEntity(
                projectId = targetProjectId,
                displayName = "needle.pdf",
                mimeType = "application/pdf",
                localPath = "managed-target",
                addedAt = 1,
            ),
        )
        repeat(32) { index ->
            val otherProjectId = projectDao.insertProject(
                ProjectEntity(name = "New project $index", createdAt = 2, updatedAt = 100L + index),
            )
            projectDao.insertProjectFile(
                ProjectFileEntity(
                    projectId = otherProjectId,
                    displayName = "new-$index.pdf",
                    mimeType = "application/pdf",
                    localPath = "managed-$index",
                    addedAt = 100L + index,
                ),
            )
        }

        assertFalse(dao.getSearchFiles(32).any { it.id == targetFileId })
        assertEquals(targetFileId, dao.getSearchFilesForProject(targetProjectId, 32).single().id)
        assertNotNull(dao.searchProjectById(targetProjectId, "needle"))
    }

    @Test
    fun `attachment reference projection pages every message without the body search length cap`() = runBlocking {
        val conversationId = database.chatDao().insertConversation(
            ConversationEntity(title = "long history", createdAt = 1, updatedAt = 1),
        )
        val firstMessageId = database.chatDao().insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = "user",
                content = "x".repeat(70_000),
                createdAt = 1,
            ),
        )
        repeat(128) { index ->
            database.chatDao().insertMessage(
                MessageEntity(
                    conversationId = conversationId,
                    role = "user",
                    content = "newer $index",
                    createdAt = 2L + index,
                ),
            )
        }

        val firstPage = database.knowledgeSearchDao().getConversationMessagePage(conversationId, 0, 64)
        assertEquals(firstMessageId, firstPage.first().id)
        assertEquals(70_000, firstPage.first().content.length)

        val allIds = mutableListOf<Long>()
        var afterId = 0L
        while (true) {
            val page = database.knowledgeSearchDao().getConversationMessagePage(conversationId, afterId, 64)
            if (page.isEmpty()) break
            allIds += page.map { it.id }
            afterId = page.last().id
        }
        assertEquals(129, allIds.size)
        assertEquals(firstMessageId, allIds.first())
    }
}
