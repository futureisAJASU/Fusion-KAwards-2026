package com.projectnuke.fusion.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.CollectionInboxLinkEntity
import com.projectnuke.fusion.data.CollectionSavedKnowledgeLinkEntity
import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.data.InboxItemTagLinkEntity
import com.projectnuke.fusion.data.KnowledgeCollectionEntity
import com.projectnuke.fusion.data.KnowledgeTagEntity
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.data.SavedKnowledgeTagLinkEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * H15 saturation: membership (tag/collection) is part of candidate selection
 * before LIMIT. Each test first demonstrates the old starvation shape with
 * the unfiltered bounded query, then proves the filtered query returns the
 * older legitimate match. NOT RUN on host (Room); compile-gated here.
 */
@RunWith(AndroidJUnit4::class)
class FilteredSearchSaturationTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    private suspend fun seedSavedTagFixture(tagKey: String, query: String): String {
        database.knowledgeOrganizationDao().insertTagIfAbsent(KnowledgeTagEntity(tagKey, "Wanted", 1, 1))
        // 64 newer rows matching the query text but WITHOUT the tag.
        repeat(64) { i ->
            val id = "s-new-$i"
            database.savedKnowledgeDao().insert(
                SavedKnowledgeEntity(id, "PERSONAL", null, "$query filler $i", "body $i", "USER_NOTE", null, null, 2000L + i, 2000L + i),
            )
        }
        // One older row matching query AND tag.
        val wanted = "s-wanted"
        database.savedKnowledgeDao().insert(
            SavedKnowledgeEntity(wanted, "PERSONAL", null, "$query wanted", "wanted body", "USER_NOTE", null, null, 1000, 1000),
        )
        database.knowledgeOrganizationDao().insertSavedTag(SavedKnowledgeTagLinkEntity(wanted, tagKey))
        return wanted
    }

    @Test
    fun savedTagFilter_returnsOlderMatchHiddenByRawCap() = runBlocking {
        val query = "fusion saturation"
        val wanted = seedSavedTagFixture("wanted", query)
        val pattern = "%$query%"
        val raw = database.savedKnowledgeDao().search(pattern, 64).map { it.id }.toSet()
        assertFalse("old raw cap hides the older tagged match", raw.contains(wanted))
        val filtered = database.savedKnowledgeDao().searchInTag(pattern, "wanted", 64).map { it.id }
        assertTrue(filtered.contains(wanted))
        val latestFiltered = database.savedKnowledgeDao().latestInTag("wanted", 48).map { it.id }
        assertTrue(latestFiltered.contains(wanted))
    }

    private suspend fun seedSavedCollectionFixture(collectionId: String, query: String): String {
        database.knowledgeOrganizationDao().insertCollectionIfAbsent(
            KnowledgeCollectionEntity(collectionId, "key-$collectionId", "Wanted", 1, 1),
        )
        repeat(64) { i ->
            database.savedKnowledgeDao().insert(
                SavedKnowledgeEntity("c-new-$i", "PERSONAL", null, "$query filler $i", "body", "USER_NOTE", null, null, 2000L + i, 2000L + i),
            )
        }
        val wanted = "c-wanted"
        database.savedKnowledgeDao().insert(
            SavedKnowledgeEntity(wanted, "PERSONAL", null, "$query wanted", "wanted body", "USER_NOTE", null, null, 1000, 1000),
        )
        database.knowledgeOrganizationDao().insertSavedCollection(CollectionSavedKnowledgeLinkEntity(collectionId, wanted))
        return wanted
    }

    @Test
    fun savedCollectionFilter_returnsOlderMatchHiddenByRawCap() = runBlocking {
        val query = "fusion collection"
        val wanted = seedSavedCollectionFixture("col-1", query)
        val pattern = "%$query%"
        assertFalse(database.savedKnowledgeDao().search(pattern, 64).map { it.id }.contains(wanted))
        assertTrue(database.savedKnowledgeDao().searchInCollection(pattern, "col-1", 64).map { it.id }.contains(wanted))
        assertTrue(database.savedKnowledgeDao().latestInCollection("col-1", 48).map { it.id }.contains(wanted))
    }

    @Test
    fun savedTagAndCollectionFilter_requiresBothMemberships() = runBlocking {
        val query = "fusion both"
        database.knowledgeOrganizationDao().insertTagIfAbsent(KnowledgeTagEntity("t-both", "T", 1, 1))
        database.knowledgeOrganizationDao().insertCollectionIfAbsent(
            KnowledgeCollectionEntity("c-both", "key-c-both", "C", 1, 1),
        )
        database.savedKnowledgeDao().insert(
            SavedKnowledgeEntity("both-tag-only", "PERSONAL", null, "$query tag only", "b", "USER_NOTE", null, null, 1000, 1000),
        )
        database.knowledgeOrganizationDao().insertSavedTag(SavedKnowledgeTagLinkEntity("both-tag-only", "t-both"))
        database.savedKnowledgeDao().insert(
            SavedKnowledgeEntity("both-wanted", "PERSONAL", null, "$query wanted", "b", "USER_NOTE", null, null, 999, 999),
        )
        database.knowledgeOrganizationDao().insertSavedTag(SavedKnowledgeTagLinkEntity("both-wanted", "t-both"))
        database.knowledgeOrganizationDao().insertSavedCollection(CollectionSavedKnowledgeLinkEntity("c-both", "both-wanted"))
        val pattern = "%$query%"
        val ids = database.savedKnowledgeDao().searchInTagAndCollection(pattern, "t-both", "c-both", 64).map { it.id }
        assertTrue(ids.contains("both-wanted"))
        assertFalse(ids.contains("both-tag-only"))
        assertTrue(database.savedKnowledgeDao().latestInTagAndCollection("t-both", "c-both", 48).map { it.id }.contains("both-wanted"))
    }

    @Test
    fun savedProjectFilterRunsBeforeTheBoundedPageLimit() = runBlocking {
        val projectId = 71L
        val otherProjectId = 72L
        database.projectDao().insertProject(
            ProjectEntity(id = projectId, name = "Requested", createdAt = 1, updatedAt = 1),
        )
        database.projectDao().insertProject(
            ProjectEntity(id = otherProjectId, name = "Other", createdAt = 1, updatedAt = 1),
        )
        val query = "project saturation"
        repeat(96) { index ->
            database.savedKnowledgeDao().insert(
                SavedKnowledgeEntity(
                    "other-project-$index", "PROJECT", otherProjectId,
                    "$query filler $index", "body", "USER_NOTE", null, null,
                    2_000L + index, 2_000L + index,
                ),
            )
        }
        val wanted = SavedKnowledgeEntity(
            "requested-project-match", "PROJECT", projectId,
            "$query wanted", "wanted body", "USER_NOTE", null, null, 1_000, 1_000,
        )
        database.savedKnowledgeDao().insert(wanted)

        val pattern = "%$query%"
        assertFalse(database.savedKnowledgeDao().searchPage(pattern, 96, 0).any { it.id == wanted.id })
        assertTrue(database.savedKnowledgeDao().searchProjectPage(pattern, projectId, 96, 0).any { it.id == wanted.id })
        assertTrue(database.savedKnowledgeDao().latestInProject(projectId, 48).any { it.id == wanted.id })
    }

    private suspend fun seedInboxTagFixture(tagKey: String, query: String): String {
        database.knowledgeOrganizationDao().insertTagIfAbsent(KnowledgeTagEntity(tagKey, "Wanted", 1, 1))
        repeat(32) { i ->
            database.inboxItemDao().insert(
                InboxItemEntity("i-new-$i", "NOTE", "$query filler $i", "content $i", null, null, null, null, 2000L + i, 2000L + i, "READY"),
            )
        }
        val wanted = "i-wanted"
        database.inboxItemDao().insert(
            InboxItemEntity(wanted, "NOTE", "$query wanted", "wanted content", null, null, null, null, 1000, 1000, "READY"),
        )
        database.knowledgeOrganizationDao().insertInboxTag(InboxItemTagLinkEntity(wanted, tagKey))
        return wanted
    }

    @Test
    fun inboxTagFilter_returnsOlderMatchHiddenByRawCap() = runBlocking {
        val query = "inbox saturation"
        val wanted = seedInboxTagFixture("iwanted", query)
        assertFalse(database.inboxItemDao().search(query, 32).map { it.id }.contains(wanted))
        assertTrue(database.inboxItemDao().searchInTag(query, "iwanted", 32).map { it.id }.contains(wanted))
        assertTrue(database.inboxItemDao().latestInTag("iwanted", 24).map { it.id }.contains(wanted))
    }

    @Test
    fun inboxCollectionFilter_returnsOlderMatchHiddenByRawCap() = runBlocking {
        val query = "inbox collection"
        database.knowledgeOrganizationDao().insertCollectionIfAbsent(
            KnowledgeCollectionEntity("icol-1", "key-icol-1", "Wanted", 1, 1),
        )
        repeat(32) { i ->
            database.inboxItemDao().insert(
                InboxItemEntity("ic-new-$i", "NOTE", "$query filler $i", "content", null, null, null, null, 2000L + i, 2000L + i, "READY"),
            )
        }
        val wanted = "ic-wanted"
        database.inboxItemDao().insert(
            InboxItemEntity(wanted, "NOTE", "$query wanted", "wanted", null, null, null, null, 1000, 1000, "READY"),
        )
        database.knowledgeOrganizationDao().insertInboxCollection(CollectionInboxLinkEntity("icol-1", wanted))
        assertFalse(database.inboxItemDao().search(query, 32).map { it.id }.contains(wanted))
        assertTrue(database.inboxItemDao().searchInCollection(query, "icol-1", 32).map { it.id }.contains(wanted))
        assertTrue(database.inboxItemDao().latestInCollection("icol-1", 24).map { it.id }.contains(wanted))
    }

    @Test
    fun filteredQueries_remainBounded() = runBlocking {
        val query = "bounded check"
        val wanted = seedSavedTagFixture("bounded-tag", query)
        assertTrue(database.savedKnowledgeDao().searchInTag("%$query%", "bounded-tag", 64).size <= 64)
        assertTrue(database.savedKnowledgeDao().latestInTag("bounded-tag", 48).size <= 48)
        assertTrue(wanted.isNotBlank())
    }
}
