package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "knowledge_tags", indices = [Index("updatedAt")])
data class KnowledgeTagEntity(
    @PrimaryKey val tagKey: String,
    val displayName: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "saved_knowledge_tags",
    primaryKeys = ["itemId", "tagKey"],
    foreignKeys = [
        ForeignKey(SavedKnowledgeEntity::class, ["id"], ["itemId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(KnowledgeTagEntity::class, ["tagKey"], ["tagKey"], onDelete = ForeignKey.CASCADE, onUpdate = ForeignKey.CASCADE),
    ],
    indices = [Index("tagKey")],
)
data class SavedKnowledgeTagLinkEntity(val itemId: String, val tagKey: String)

@Entity(
    tableName = "inbox_item_tags",
    primaryKeys = ["itemId", "tagKey"],
    foreignKeys = [
        ForeignKey(InboxItemEntity::class, ["id"], ["itemId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(KnowledgeTagEntity::class, ["tagKey"], ["tagKey"], onDelete = ForeignKey.CASCADE, onUpdate = ForeignKey.CASCADE),
    ],
    indices = [Index("tagKey")],
)
data class InboxItemTagLinkEntity(val itemId: String, val tagKey: String)

@Entity(tableName = "knowledge_collections", indices = [Index(value = ["collectionKey"], unique = true), Index("updatedAt")])
data class KnowledgeCollectionEntity(
    @PrimaryKey val collectionId: String,
    val collectionKey: String,
    val displayName: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "collection_saved_knowledge",
    primaryKeys = ["collectionId", "itemId"],
    foreignKeys = [
        ForeignKey(KnowledgeCollectionEntity::class, ["collectionId"], ["collectionId"], onDelete = ForeignKey.CASCADE, onUpdate = ForeignKey.CASCADE),
        ForeignKey(SavedKnowledgeEntity::class, ["id"], ["itemId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("itemId")],
)
data class CollectionSavedKnowledgeLinkEntity(val collectionId: String, val itemId: String)

@Entity(
    tableName = "collection_inbox_items",
    primaryKeys = ["collectionId", "itemId"],
    foreignKeys = [
        ForeignKey(KnowledgeCollectionEntity::class, ["collectionId"], ["collectionId"], onDelete = ForeignKey.CASCADE, onUpdate = ForeignKey.CASCADE),
        ForeignKey(InboxItemEntity::class, ["id"], ["itemId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("itemId")],
)
data class CollectionInboxLinkEntity(val collectionId: String, val itemId: String)

@Dao
interface KnowledgeOrganizationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagIfAbsent(tag: KnowledgeTagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollectionIfAbsent(collection: KnowledgeCollectionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSavedTag(link: SavedKnowledgeTagLinkEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInboxTag(link: InboxItemTagLinkEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSavedCollection(link: CollectionSavedKnowledgeLinkEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInboxCollection(link: CollectionInboxLinkEntity)

    @Query("SELECT * FROM knowledge_tags WHERE tagKey = :key LIMIT 1")
    suspend fun getTag(key: String): KnowledgeTagEntity?

    @Query("SELECT COUNT(*) FROM knowledge_tags")
    suspend fun countTags(): Int

    @Query("SELECT * FROM knowledge_tags ORDER BY displayName COLLATE NOCASE, tagKey LIMIT :limit")
    suspend fun listTags(limit: Int): List<KnowledgeTagEntity>

    @Query("SELECT tags.* FROM knowledge_tags AS tags INNER JOIN saved_knowledge_tags AS links ON links.tagKey = tags.tagKey WHERE links.itemId = :itemId ORDER BY tags.displayName COLLATE NOCASE LIMIT 12")
    suspend fun tagsForSavedKnowledge(itemId: String): List<KnowledgeTagEntity>

    @Query("SELECT tags.* FROM knowledge_tags AS tags INNER JOIN inbox_item_tags AS links ON links.tagKey = tags.tagKey WHERE links.itemId = :itemId ORDER BY tags.displayName COLLATE NOCASE LIMIT 12")
    suspend fun tagsForInboxItem(itemId: String): List<KnowledgeTagEntity>

    @Query("DELETE FROM saved_knowledge_tags WHERE itemId = :itemId")
    suspend fun clearSavedTags(itemId: String)

    @Query("DELETE FROM inbox_item_tags WHERE itemId = :itemId")
    suspend fun clearInboxTags(itemId: String)

    @Query("UPDATE knowledge_tags SET tagKey = :newKey, displayName = :newName, updatedAt = :now WHERE tagKey = :oldKey")
    suspend fun renameTagKey(oldKey: String, newKey: String, newName: String, now: Long): Int

    @Query("DELETE FROM knowledge_tags WHERE tagKey = :key")
    suspend fun deleteTag(key: String): Int

    @Query("SELECT * FROM knowledge_collections WHERE collectionKey = :key LIMIT 1")
    suspend fun getCollectionByKey(key: String): KnowledgeCollectionEntity?

    @Query("SELECT * FROM knowledge_collections WHERE collectionId = :id LIMIT 1")
    suspend fun getCollection(id: String): KnowledgeCollectionEntity?

    @Query("SELECT COUNT(*) FROM knowledge_collections")
    suspend fun countCollections(): Int

    @Query("SELECT * FROM knowledge_collections ORDER BY updatedAt DESC, collectionId LIMIT :limit")
    suspend fun listCollections(limit: Int): List<KnowledgeCollectionEntity>

    @Query("SELECT collections.* FROM knowledge_collections AS collections INNER JOIN collection_saved_knowledge AS links ON links.collectionId = collections.collectionId WHERE links.itemId = :itemId ORDER BY collections.displayName COLLATE NOCASE LIMIT 100")
    suspend fun collectionsForSavedKnowledge(itemId: String): List<KnowledgeCollectionEntity>

    @Query("SELECT collections.* FROM knowledge_collections AS collections INNER JOIN collection_inbox_items AS links ON links.collectionId = collections.collectionId WHERE links.itemId = :itemId ORDER BY collections.displayName COLLATE NOCASE LIMIT 100")
    suspend fun collectionsForInboxItem(itemId: String): List<KnowledgeCollectionEntity>

    @Query("DELETE FROM collection_saved_knowledge WHERE itemId = :itemId")
    suspend fun clearSavedCollections(itemId: String)

    @Query("DELETE FROM collection_inbox_items WHERE itemId = :itemId")
    suspend fun clearInboxCollections(itemId: String)

    @Query("UPDATE knowledge_collections SET collectionKey = :newKey, displayName = :newName, updatedAt = :now WHERE collectionId = :oldId")
    suspend fun renameCollectionId(oldId: String, newKey: String, newName: String, now: Long): Int

    @Query("DELETE FROM knowledge_collections WHERE collectionId = :id")
    suspend fun deleteCollection(id: String): Int

    @Query("SELECT itemId FROM collection_saved_knowledge WHERE collectionId = :collectionId ORDER BY itemId LIMIT :limit")
    suspend fun savedItemsInCollection(collectionId: String, limit: Int): List<String>

    @Query("SELECT itemId FROM collection_inbox_items WHERE collectionId = :collectionId ORDER BY itemId LIMIT :limit")
    suspend fun inboxItemsInCollection(collectionId: String, limit: Int): List<String>

    @Query("SELECT 1 FROM saved_knowledge_tags WHERE itemId = :itemId AND tagKey = :tagKey LIMIT 1")
    suspend fun savedItemHasTag(itemId: String, tagKey: String): Int?

    @Query("SELECT 1 FROM inbox_item_tags WHERE itemId = :itemId AND tagKey = :tagKey LIMIT 1")
    suspend fun inboxItemHasTag(itemId: String, tagKey: String): Int?

    @Query("SELECT 1 FROM collection_saved_knowledge WHERE itemId = :itemId AND collectionId = :collectionId LIMIT 1")
    suspend fun savedItemInCollection(itemId: String, collectionId: String): Int?

    @Query("SELECT 1 FROM collection_inbox_items WHERE itemId = :itemId AND collectionId = :collectionId LIMIT 1")
    suspend fun inboxItemInCollection(itemId: String, collectionId: String): Int?
}
