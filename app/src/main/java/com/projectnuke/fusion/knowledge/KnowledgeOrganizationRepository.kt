package com.projectnuke.fusion.knowledge

import androidx.room.withTransaction
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.CollectionInboxLinkEntity
import com.projectnuke.fusion.data.CollectionSavedKnowledgeLinkEntity
import com.projectnuke.fusion.data.InboxItemTagLinkEntity
import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.data.KnowledgeCollectionEntity
import com.projectnuke.fusion.data.KnowledgeTagEntity
import com.projectnuke.fusion.data.SavedKnowledgeTagLinkEntity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class OrganizationObjectKind { SAVED_KNOWLEDGE, INBOX }

sealed interface OrganizationWriteResult {
    data object Updated : OrganizationWriteResult
    data object Invalid : OrganizationWriteResult
    data object ItemUnavailable : OrganizationWriteResult
    data object CapacityReached : OrganizationWriteResult
    data object Conflict : OrganizationWriteResult
    data object Failed : OrganizationWriteResult
}

/** Owns normalized labels and collection memberships; content rows remain owned by their feature stores. */
class KnowledgeOrganizationRepository(private val database: AppDatabase) {
    suspend fun tags(kind: OrganizationObjectKind, itemId: String) = withContext(Dispatchers.IO) {
        if (itemId.isBlank()) return@withContext emptyList()
        when (kind) {
            OrganizationObjectKind.SAVED_KNOWLEDGE -> database.knowledgeOrganizationDao().tagsForSavedKnowledge(itemId)
            OrganizationObjectKind.INBOX -> database.knowledgeOrganizationDao().tagsForInboxItem(itemId)
        }
    }

    suspend fun collections(kind: OrganizationObjectKind, itemId: String) = withContext(Dispatchers.IO) {
        if (itemId.isBlank()) return@withContext emptyList()
        when (kind) {
            OrganizationObjectKind.SAVED_KNOWLEDGE -> database.knowledgeOrganizationDao().collectionsForSavedKnowledge(itemId)
            OrganizationObjectKind.INBOX -> database.knowledgeOrganizationDao().collectionsForInboxItem(itemId)
        }
    }

    suspend fun availableTags(limit: Int = KnowledgeOrganizationPolicy.MAX_TAGS) = withContext(Dispatchers.IO) {
        database.knowledgeOrganizationDao().listTags(limit.coerceIn(1, KnowledgeOrganizationPolicy.MAX_TAGS))
    }

    suspend fun availableCollections(limit: Int = KnowledgeOrganizationPolicy.MAX_COLLECTIONS) = withContext(Dispatchers.IO) {
        database.knowledgeOrganizationDao().listCollections(limit.coerceIn(1, KnowledgeOrganizationPolicy.MAX_COLLECTIONS))
    }

    suspend fun addInboxTag(
        itemId: String,
        rawTag: String,
        expectedItem: InboxItemEntity? = null,
    ): OrganizationWriteResult = withContext(Dispatchers.IO) {
        val label = KnowledgeOrganizationPolicy.normalizeTag(rawTag)
            ?: return@withContext OrganizationWriteResult.Invalid
        try {
            database.withTransaction {
                val inbox = database.inboxItemDao()
                val currentItem = inbox.get(itemId)
                    ?: return@withTransaction OrganizationWriteResult.ItemUnavailable
                if (expectedItem != null && currentItem != expectedItem) {
                    return@withTransaction OrganizationWriteResult.Conflict
                }
                val dao = database.knowledgeOrganizationDao()
                if (dao.inboxItemHasTag(itemId, label.key) != null) return@withTransaction OrganizationWriteResult.Updated
                if (dao.tagsForInboxItem(itemId).size >= KnowledgeOrganizationPolicy.MAX_TAGS_PER_ITEM) {
                    return@withTransaction OrganizationWriteResult.CapacityReached
                }
                if (dao.getTag(label.key) == null && dao.countTags() >= KnowledgeOrganizationPolicy.MAX_TAGS) {
                    return@withTransaction OrganizationWriteResult.CapacityReached
                }
                val now = System.currentTimeMillis()
                dao.insertTagIfAbsent(KnowledgeTagEntity(label.key, label.displayName, now, now))
                dao.insertInboxTag(InboxItemTagLinkEntity(itemId, label.key))
                OrganizationWriteResult.Updated
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            OrganizationWriteResult.Failed
        }
    }

    suspend fun setTags(kind: OrganizationObjectKind, itemId: String, rawTags: List<String>): OrganizationWriteResult =
        withContext(Dispatchers.IO) {
            val normalized = KnowledgeOrganizationPolicy.normalizeTagSet(rawTags) ?: return@withContext OrganizationWriteResult.Invalid
            if (itemId.isBlank()) return@withContext OrganizationWriteResult.Invalid
            try {
                database.withTransaction {
                    val dao = database.knowledgeOrganizationDao()
                    val exists = when (kind) {
                        OrganizationObjectKind.SAVED_KNOWLEDGE -> database.savedKnowledgeDao().getById(itemId) != null
                        OrganizationObjectKind.INBOX -> database.inboxItemDao().get(itemId) != null
                    }
                    if (!exists) return@withTransaction OrganizationWriteResult.ItemUnavailable
                    val newKeys = normalized.map { it.key }.filter { dao.getTag(it) == null }
                    if (dao.countTags() + newKeys.size > KnowledgeOrganizationPolicy.MAX_TAGS) {
                        return@withTransaction OrganizationWriteResult.CapacityReached
                    }
                    val now = System.currentTimeMillis()
                    normalized.forEach { label ->
                        dao.insertTagIfAbsent(KnowledgeTagEntity(label.key, label.displayName, now, now))
                    }
                    when (kind) {
                        OrganizationObjectKind.SAVED_KNOWLEDGE -> {
                            dao.clearSavedTags(itemId)
                            normalized.forEach { dao.insertSavedTag(SavedKnowledgeTagLinkEntity(itemId, it.key)) }
                        }
                        OrganizationObjectKind.INBOX -> {
                            dao.clearInboxTags(itemId)
                            normalized.forEach { dao.insertInboxTag(InboxItemTagLinkEntity(itemId, it.key)) }
                        }
                    }
                    OrganizationWriteResult.Updated
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OrganizationWriteResult.Failed
            }
        }

    suspend fun setCollections(
        kind: OrganizationObjectKind,
        itemId: String,
        rawCollections: List<String>,
    ): OrganizationWriteResult = withContext(Dispatchers.IO) {
        val normalized = KnowledgeOrganizationPolicy.normalizeCollectionSet(rawCollections)
            ?: return@withContext OrganizationWriteResult.Invalid
        if (itemId.isBlank()) return@withContext OrganizationWriteResult.Invalid
        try {
            database.withTransaction {
                val dao = database.knowledgeOrganizationDao()
                val exists = when (kind) {
                    OrganizationObjectKind.SAVED_KNOWLEDGE -> database.savedKnowledgeDao().getById(itemId) != null
                    OrganizationObjectKind.INBOX -> database.inboxItemDao().get(itemId) != null
                }
                if (!exists) return@withTransaction OrganizationWriteResult.ItemUnavailable
                val missing = normalized.filter { dao.getCollectionByKey(it.key) == null }
                if (dao.countCollections() + missing.size > KnowledgeOrganizationPolicy.MAX_COLLECTIONS) {
                    return@withTransaction OrganizationWriteResult.CapacityReached
                }
                val now = System.currentTimeMillis()
                val collections = normalized.map { label ->
                    dao.getCollectionByKey(label.key) ?: run {
                        dao.insertCollectionIfAbsent(
                            KnowledgeCollectionEntity(
                                collectionId = UUID.randomUUID().toString(),
                                collectionKey = label.key,
                                displayName = label.displayName,
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                        dao.getCollectionByKey(label.key)!!
                    }
                }
                when (kind) {
                    OrganizationObjectKind.SAVED_KNOWLEDGE -> {
                        dao.clearSavedCollections(itemId)
                        collections.forEach { dao.insertSavedCollection(CollectionSavedKnowledgeLinkEntity(it.collectionId, itemId)) }
                    }
                    OrganizationObjectKind.INBOX -> {
                        dao.clearInboxCollections(itemId)
                        collections.forEach { dao.insertInboxCollection(CollectionInboxLinkEntity(it.collectionId, itemId)) }
                    }
                }
                OrganizationWriteResult.Updated
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            OrganizationWriteResult.Failed
        }
    }

    suspend fun renameTag(oldKey: String, rawName: String): OrganizationWriteResult = withContext(Dispatchers.IO) {
        val label = KnowledgeOrganizationPolicy.normalizeTag(rawName) ?: return@withContext OrganizationWriteResult.Invalid
        try {
            database.withTransaction {
                val dao = database.knowledgeOrganizationDao()
                if (dao.getTag(oldKey) == null) return@withTransaction OrganizationWriteResult.ItemUnavailable
                if (oldKey != label.key && dao.getTag(label.key) != null) return@withTransaction OrganizationWriteResult.Conflict
                if (dao.renameTagKey(oldKey, label.key, label.displayName, System.currentTimeMillis()) == 1) {
                    OrganizationWriteResult.Updated
                } else OrganizationWriteResult.ItemUnavailable
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            OrganizationWriteResult.Failed
        }
    }

    suspend fun deleteTag(key: String): OrganizationWriteResult = deleteSafely {
        database.knowledgeOrganizationDao().deleteTag(key)
    }

    suspend fun renameCollection(id: String, rawName: String): OrganizationWriteResult = withContext(Dispatchers.IO) {
        val label = KnowledgeOrganizationPolicy.normalizeCollection(rawName) ?: return@withContext OrganizationWriteResult.Invalid
        try {
            database.withTransaction {
                val dao = database.knowledgeOrganizationDao()
                val current = dao.getCollection(id) ?: return@withTransaction OrganizationWriteResult.ItemUnavailable
                val conflict = dao.getCollectionByKey(label.key)
                if (conflict != null && conflict.collectionId != id) return@withTransaction OrganizationWriteResult.Conflict
                if (dao.renameCollectionId(current.collectionId, label.key, label.displayName, System.currentTimeMillis()) == 1) {
                    OrganizationWriteResult.Updated
                } else OrganizationWriteResult.ItemUnavailable
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            OrganizationWriteResult.Failed
        }
    }

    /** Removing a collection deletes only membership links through the database foreign keys. */
    suspend fun deleteCollection(id: String): OrganizationWriteResult = deleteSafely {
        database.knowledgeOrganizationDao().deleteCollection(id)
    }

    private suspend fun deleteSafely(delete: suspend () -> Int) = withContext(Dispatchers.IO) {
        try {
            if (delete() == 1) OrganizationWriteResult.Updated else OrganizationWriteResult.ItemUnavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            OrganizationWriteResult.Failed
        }
    }
}
