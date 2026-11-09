package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A local capture waiting for the user to choose its lasting destination. */
@Entity(
    tableName = "inbox_items",
    indices = [Index(value = ["updatedAt", "id"]), Index("captureType"), Index("sourceHandoffEventId")],
)
data class InboxItemEntity(
    @PrimaryKey val id: String,
    val captureType: String,
    val title: String,
    val content: String,
    val originalUri: String?,
    /** Internal managed identity only; never render this value to the user. */
    val managedFileId: String?,
    val managedMimeType: String?,
    val sourceHandoffEventId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val processingState: String,
    /** Effective content origin for web clips; null for legacy/unknown provenance. */
    val effectiveSourceUri: String? = null,
)

@Dao
interface InboxItemDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: InboxItemEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<InboxItemEntity>)

    @Query("SELECT COUNT(*) FROM inbox_items")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM inbox_items")
    fun observeCount(): Flow<Int>

    @Transaction
    suspend fun insertBounded(item: InboxItemEntity): InboxInsertResult {
        if (count() >= MAX_INBOX_ITEMS) return InboxInsertResult.CapacityReached
        insert(item)
        return InboxInsertResult.Inserted
    }

    @Transaction
    suspend fun insertOrConfirmBounded(item: InboxItemEntity): InboxInsertResult {
        val existing = get(item.id)
        if (existing != null) {
            return if (
                existing.captureType == item.captureType &&
                existing.title == item.title &&
                existing.content == item.content &&
                existing.originalUri == item.originalUri &&
                existing.effectiveSourceUri == item.effectiveSourceUri &&
                existing.managedFileId == item.managedFileId &&
                existing.managedMimeType == item.managedMimeType &&
                existing.sourceHandoffEventId == item.sourceHandoffEventId
            ) InboxInsertResult.AlreadyStored else InboxInsertResult.Conflict
        }
        if (count() >= MAX_INBOX_ITEMS) return InboxInsertResult.CapacityReached
        insert(item)
        return InboxInsertResult.Inserted
    }

    @Transaction
    suspend fun insertAllBounded(items: List<InboxItemEntity>): InboxInsertResult {
        if (items.isEmpty() || items.size > MAX_INBOX_ITEMS || count() + items.size > MAX_INBOX_ITEMS) {
            return InboxInsertResult.CapacityReached
        }
        insertAll(items)
        return InboxInsertResult.Inserted
    }

    @Query("SELECT * FROM inbox_items ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<InboxItemEntity>>

    @Query("SELECT * FROM inbox_items WHERE id = :id LIMIT 1")
    suspend fun get(id: String): InboxItemEntity?

    @Query("DELETE FROM inbox_items WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM inbox_items WHERE id IN (:ids)")
    suspend fun deleteMany(ids: List<String>): Int

    @Query("SELECT managedFileId FROM inbox_items WHERE managedFileId IS NOT NULL")
    suspend fun getAllManagedFileIds(): List<String>

    @Query("SELECT * FROM inbox_items WHERE sourceHandoffEventId = :eventId ORDER BY id LIMIT :limit")
    suspend fun getForHandoff(eventId: String, limit: Int): List<InboxItemEntity>

    @Query("SELECT * FROM inbox_items WHERE title LIKE '%' || :pattern || '%' ESCAPE '\\' OR content LIKE '%' || :pattern || '%' ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    suspend fun search(pattern: String, limit: Int): List<InboxItemEntity>

    @Query("SELECT * FROM inbox_items ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    suspend fun getLatestBounded(limit: Int): List<InboxItemEntity>

    /**
     * Membership-first candidate selection for an active tag/collection
     * filter (same contract as saved knowledge): the JOIN runs inside the
     * query so LIMIT applies within the requested membership.
     */
    @Query("SELECT ii.* FROM inbox_items AS ii INNER JOIN inbox_item_tags AS lk ON lk.itemId = ii.id WHERE lk.tagKey = :tagKey AND (ii.title LIKE '%' || :pattern || '%' ESCAPE '\\' OR ii.content LIKE '%' || :pattern || '%') ORDER BY ii.updatedAt DESC, ii.id DESC LIMIT :limit")
    suspend fun searchInTag(pattern: String, tagKey: String, limit: Int): List<InboxItemEntity>

    @Query("SELECT ii.* FROM inbox_items AS ii INNER JOIN inbox_item_tags AS lk ON lk.itemId = ii.id WHERE lk.tagKey = :tagKey ORDER BY ii.updatedAt DESC, ii.id DESC LIMIT :limit")
    suspend fun latestInTag(tagKey: String, limit: Int): List<InboxItemEntity>

    @Query("SELECT ii.* FROM inbox_items AS ii INNER JOIN collection_inbox_items AS lk ON lk.itemId = ii.id WHERE lk.collectionId = :collectionId AND (ii.title LIKE '%' || :pattern || '%' ESCAPE '\\' OR ii.content LIKE '%' || :pattern || '%') ORDER BY ii.updatedAt DESC, ii.id DESC LIMIT :limit")
    suspend fun searchInCollection(pattern: String, collectionId: String, limit: Int): List<InboxItemEntity>

    @Query("SELECT ii.* FROM inbox_items AS ii INNER JOIN collection_inbox_items AS lk ON lk.itemId = ii.id WHERE lk.collectionId = :collectionId ORDER BY ii.updatedAt DESC, ii.id DESC LIMIT :limit")
    suspend fun latestInCollection(collectionId: String, limit: Int): List<InboxItemEntity>

    @Query("SELECT ii.* FROM inbox_items AS ii INNER JOIN inbox_item_tags AS lt ON lt.itemId = ii.id INNER JOIN collection_inbox_items AS lc ON lc.itemId = ii.id WHERE lt.tagKey = :tagKey AND lc.collectionId = :collectionId AND (ii.title LIKE '%' || :pattern || '%' ESCAPE '\\' OR ii.content LIKE '%' || :pattern || '%') ORDER BY ii.updatedAt DESC, ii.id DESC LIMIT :limit")
    suspend fun searchInTagAndCollection(pattern: String, tagKey: String, collectionId: String, limit: Int): List<InboxItemEntity>

    @Query("SELECT ii.* FROM inbox_items AS ii INNER JOIN inbox_item_tags AS lt ON lt.itemId = ii.id INNER JOIN collection_inbox_items AS lc ON lc.itemId = ii.id WHERE lt.tagKey = :tagKey AND lc.collectionId = :collectionId ORDER BY ii.updatedAt DESC, ii.id DESC LIMIT :limit")
    suspend fun latestInTagAndCollection(tagKey: String, collectionId: String, limit: Int): List<InboxItemEntity>
}

sealed interface InboxInsertResult {
    data object Inserted : InboxInsertResult
    data object AlreadyStored : InboxInsertResult
    data object Conflict : InboxInsertResult
    data object CapacityReached : InboxInsertResult
}

const val MAX_INBOX_ITEMS = 200
