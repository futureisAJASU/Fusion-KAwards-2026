package com.projectnuke.fusion.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BranchSynthesisSourceDaoTest {
    @Test
    fun `source deletion preserves provenance and synthesis deletion cascades`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "branch-synthesis-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        try {
            val sourceId = database.chatDao().insertConversation(
                ConversationEntity(title = "source", createdAt = 1, updatedAt = 1),
            )
            val synthesisId = database.chatDao().insertConversation(
                ConversationEntity(title = "synthesis", createdAt = 2, updatedAt = 2),
            )
            database.branchSynthesisSourceDao().insertAll(
                listOf(
                    BranchSynthesisSourceEntity(
                        synthesisConversationId = synthesisId,
                        sourceConversationId = sourceId,
                        sourceTitle = "source branch",
                        sourceOrdinal = 0,
                        intent = "OVERVIEW",
                        createdAt = 3,
                    ),
                ),
            )

            database.chatDao().deleteConversation(sourceId)
            val retained = database.branchSynthesisSourceDao().getForSynthesisConversation(synthesisId)
            assertEquals(1, retained.size)
            assertEquals(sourceId, retained.single().sourceConversationId)
            assertEquals("source branch", retained.single().sourceTitle)
            assertTrue(database.chatDao().getConversationById(sourceId) == null)

            database.chatDao().deleteConversation(synthesisId)
            assertEquals(emptyList<BranchSynthesisSourceEntity>(),
                database.branchSynthesisSourceDao().getForSynthesisConversation(synthesisId))
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
