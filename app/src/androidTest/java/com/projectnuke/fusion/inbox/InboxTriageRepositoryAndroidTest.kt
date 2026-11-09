package com.projectnuke.fusion.inbox

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.data.ProjectEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxTriageRepositoryAndroidTest {
    @Test
    fun `saved knowledge triage settles each row independently and preserves failed item`() = runBlocking {
        val db = database()
        try {
            val ready = item("ready", "usable text")
            val blank = item("blank", "")
            db.inboxItemDao().insert(ready)
            db.inboxItemDao().insert(blank)
            val removed = item("removed", "gone")

            val result = InboxTriageRepository(db) { true }.apply(
                listOf(ready, blank, removed),
                InboxTriageAction.TO_SAVED_KNOWLEDGE,
            )

            assertEquals(
                listOf(InboxTriageOutcome.COMPLETED, InboxTriageOutcome.FAILED, InboxTriageOutcome.MISSING),
                result.map { it.outcome },
            )
            assertNull(db.inboxItemDao().get(ready.id))
            assertNotNull(db.inboxItemDao().get(blank.id))
            assertEquals("ready", db.savedKnowledgeDao().latest(10).single().title)
            assertEquals(1, InboxTriagePolicy.summarize(result).completed)
            assertEquals(1, InboxTriagePolicy.summarize(result).failed)
            assertEquals(1, InboxTriagePolicy.summarize(result).missing)
        } finally {
            db.close()
        }
    }

    @Test
    fun `Project move reuses managed file identity without copying`() = runBlocking {
        val db = database()
        try {
            val projectId = db.projectDao().insertProject(ProjectEntity(name = "Project", createdAt = 1, updatedAt = 1))
            val captured = item("document", "", managedFileId = "/managed/shared/report.pdf")
            db.inboxItemDao().insert(captured)

            val result = InboxTriageRepository(db) { it == captured.managedFileId }.apply(
                listOf(captured),
                InboxTriageAction.TO_PROJECT(projectId),
            )

            assertEquals(InboxTriageOutcome.COMPLETED, result.single().outcome)
            assertNull(db.inboxItemDao().get(captured.id))
            val projectFile = db.projectDao().getProjectFiles(projectId).single()
            assertEquals(captured.managedFileId, projectFile.localPath)
            assertTrue(projectFile.sourceHandoffEventId!!.startsWith("inbox-"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `inbox move preserves tags and collection links on the new identity`() = runBlocking {
        val db = database()
        try {
            val ready = item("tagged", "usable text")
            db.inboxItemDao().insert(ready)
            val orgDao = db.knowledgeOrganizationDao()
            orgDao.insertTagIfAbsent(
                com.projectnuke.fusion.data.KnowledgeTagEntity("alpha", "Alpha", 1, 1),
            )
            orgDao.insertTagIfAbsent(
                com.projectnuke.fusion.data.KnowledgeTagEntity("beta", "Beta", 1, 1),
            )
            orgDao.insertInboxTag(com.projectnuke.fusion.data.InboxItemTagLinkEntity("tagged", "alpha"))
            orgDao.insertInboxTag(com.projectnuke.fusion.data.InboxItemTagLinkEntity("tagged", "beta"))
            orgDao.insertCollectionIfAbsent(
                com.projectnuke.fusion.data.KnowledgeCollectionEntity("c1", "reading", "Reading", 1, 1),
            )
            orgDao.insertInboxCollection(
                com.projectnuke.fusion.data.CollectionInboxLinkEntity("c1", "tagged"),
            )

            val result = InboxTriageRepository(db) { true }.apply(
                listOf(ready),
                InboxTriageAction.TO_SAVED_KNOWLEDGE,
            )

            assertEquals(InboxTriageOutcome.COMPLETED, result.single().outcome)
            assertNull(db.inboxItemDao().get(ready.id))
            val moved = db.savedKnowledgeDao().latest(10).single()
            assertEquals("usable text", moved.savedText)
            assertEquals(
                setOf("alpha", "beta"),
                orgDao.tagsForSavedKnowledge(moved.id).map { it.tagKey }.toSet(),
            )
            assertEquals(
                listOf("c1"),
                orgDao.collectionsForSavedKnowledge(moved.id).map { it.collectionId },
            )
            // Repeated move of the same (now deleted) inbox row stays MISSING
            // and creates no duplicate saved knowledge.
            val retry = InboxTriageRepository(db) { true }.apply(
                listOf(ready),
                InboxTriageAction.TO_SAVED_KNOWLEDGE,
            )
            assertEquals(InboxTriageOutcome.MISSING, retry.single().outcome)
            assertEquals(1, db.savedKnowledgeDao().latest(10).size)
        } finally {
            db.close()
        }
    }

    private fun database(): AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun item(id: String, content: String, managedFileId: String? = null) = InboxItemEntity(
        id = id,
        captureType = if (managedFileId == null) "TEXT" else "DOCUMENT",
        title = id,
        content = content,
        originalUri = null,
        managedFileId = managedFileId,
        managedMimeType = managedFileId?.let { "application/pdf" },
        sourceHandoffEventId = null,
        createdAt = 1,
        updatedAt = 1,
        processingState = "READY",
    )
}
