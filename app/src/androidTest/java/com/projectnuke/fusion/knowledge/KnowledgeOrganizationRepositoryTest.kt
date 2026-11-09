package com.projectnuke.fusion.knowledge

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnowledgeOrganizationRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: KnowledgeOrganizationRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = KnowledgeOrganizationRepository(database)
        runBlocking { insertItems() }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `tag aliases collapse and rename and delete update all item assignments`() = runBlocking {
        assertEquals(OrganizationWriteResult.Updated, repository.setTags(
            OrganizationObjectKind.SAVED_KNOWLEDGE, "saved-1", listOf(" HBM ", "hbm", "학교"),
        ))
        assertEquals(listOf("HBM", "학교"), repository.tags(OrganizationObjectKind.SAVED_KNOWLEDGE, "saved-1").map { it.displayName })
        val hbmKey = repository.tags(OrganizationObjectKind.SAVED_KNOWLEDGE, "saved-1").first().tagKey
        assertEquals(OrganizationWriteResult.Updated, repository.setTags(OrganizationObjectKind.INBOX, "inbox-1", listOf("hbm")))
        assertEquals(OrganizationWriteResult.Updated, repository.renameTag(hbmKey, "Semiconductor"))
        assertTrue(repository.tags(OrganizationObjectKind.SAVED_KNOWLEDGE, "saved-1").any { it.tagKey == "semiconductor" })
        assertTrue(repository.tags(OrganizationObjectKind.INBOX, "inbox-1").any { it.displayName == "Semiconductor" })
        assertEquals(OrganizationWriteResult.Updated, repository.deleteTag("semiconductor"))
        assertEquals(listOf("학교"), repository.tags(OrganizationObjectKind.SAVED_KNOWLEDGE, "saved-1").map { it.displayName })
        assertTrue(repository.tags(OrganizationObjectKind.INBOX, "inbox-1").isEmpty())
    }

    @Test
    fun `deleting a collection deletes memberships but preserves captured objects`() = runBlocking {
        assertEquals(OrganizationWriteResult.Updated, repository.setCollections(
            OrganizationObjectKind.SAVED_KNOWLEDGE, "saved-1", listOf("공모전", " 공모전 "),
        ))
        val collection = repository.collections(OrganizationObjectKind.SAVED_KNOWLEDGE, "saved-1").single()
        assertEquals(OrganizationWriteResult.Updated, repository.setCollections(OrganizationObjectKind.INBOX, "inbox-1", listOf("공모전")))
        assertEquals(OrganizationWriteResult.Updated, repository.deleteCollection(collection.collectionId))
        assertTrue(repository.collections(OrganizationObjectKind.SAVED_KNOWLEDGE, "saved-1").isEmpty())
        assertTrue(repository.collections(OrganizationObjectKind.INBOX, "inbox-1").isEmpty())
        assertNotNull(database.savedKnowledgeDao().getById("saved-1"))
        assertNotNull(database.inboxItemDao().get("inbox-1"))
    }

    private suspend fun insertItems() {
        database.savedKnowledgeDao().insert(
            SavedKnowledgeEntity("saved-1", "PERSONAL", null, "saved", "text", "INBOX_CAPTURE", null, null, 1, 1),
        )
        database.inboxItemDao().insert(
            InboxItemEntity("inbox-1", "NOTE", "inbox", "text", null, null, null, null, 1, 1, "READY"),
        )
    }
}
