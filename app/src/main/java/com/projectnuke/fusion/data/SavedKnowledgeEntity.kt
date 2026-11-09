package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A deliberate, bounded copy of visible material. Source IDs are provenance only. */
@Entity(
    tableName = "saved_knowledge",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId"), Index(value = ["scope", "updatedAt"])],
)
data class SavedKnowledgeEntity(
    @PrimaryKey val id: String,
    val scope: String,
    val projectId: Long?,
    val title: String,
    val savedText: String,
    val provenanceType: String,
    val sourceConversationId: Long?,
    val sourceMessageId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceUrl: String? = null,
    val sourcePageTitle: String? = null,
    val retrievedAt: Long? = null,
    /** Effective page origin for web clips; null for legacy/unknown provenance. */
    val effectiveSourceUrl: String? = null,
)

@Dao
interface SavedKnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: SavedKnowledgeEntity)

    @Query("SELECT messages.id AS messageId, messages.conversationId AS conversationId, messages.role AS role FROM messages INNER JOIN conversations ON conversations.id = messages.conversationId WHERE messages.id = :messageId AND messages.conversationId = :conversationId LIMIT 1")
    suspend fun getSourceAnswer(messageId: Long, conversationId: Long): SavedKnowledgeSourceAnswer?

    @Query("SELECT * FROM saved_knowledge WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SavedKnowledgeEntity?

    @Query("SELECT * FROM saved_knowledge WHERE projectId = :projectId ORDER BY updatedAt DESC, id LIMIT :limit")
    fun observeProjectItems(projectId: Long, limit: Int = 24): Flow<List<SavedKnowledgeEntity>>

    @Query("SELECT * FROM saved_knowledge WHERE projectId = :projectId ORDER BY updatedAt DESC, id LIMIT :limit")
    suspend fun getProjectItemsBounded(projectId: Long, limit: Int): List<SavedKnowledgeEntity>

    @Query("SELECT * FROM saved_knowledge WHERE projectId = :projectId ORDER BY updatedAt DESC, id LIMIT :limit OFFSET :offset")
    suspend fun getProjectItemsBoundedOffset(projectId: Long, limit: Int, offset: Int): List<SavedKnowledgeEntity>

    @Query("SELECT * FROM saved_knowledge WHERE title LIKE :pattern ESCAPE '\\' OR savedText LIKE :pattern ESCAPE '\\' OR id IN (SELECT savedKnowledgeId FROM document_highlight_sources WHERE userNote LIKE :pattern ESCAPE '\\') ORDER BY updatedAt DESC, id LIMIT :limit")
    suspend fun search(pattern: String, limit: Int): List<SavedKnowledgeEntity>

    @Query("SELECT * FROM saved_knowledge WHERE title LIKE :pattern ESCAPE '\\' OR savedText LIKE :pattern ESCAPE '\\' OR id IN (SELECT savedKnowledgeId FROM document_highlight_sources WHERE userNote LIKE :pattern ESCAPE '\\') ORDER BY updatedAt DESC, id LIMIT :limit OFFSET :offset")
    suspend fun searchPage(pattern: String, limit: Int, offset: Int): List<SavedKnowledgeEntity>

    @Query("SELECT * FROM saved_knowledge WHERE projectId = :projectId AND (title LIKE :pattern ESCAPE '\\' OR savedText LIKE :pattern ESCAPE '\\' OR id IN (SELECT savedKnowledgeId FROM document_highlight_sources WHERE userNote LIKE :pattern ESCAPE '\\')) ORDER BY updatedAt DESC, id LIMIT :limit OFFSET :offset")
    suspend fun searchProjectPage(pattern: String, projectId: Long, limit: Int, offset: Int): List<SavedKnowledgeEntity>

    @Query("SELECT * FROM saved_knowledge ORDER BY updatedAt DESC, id LIMIT :limit")
    suspend fun latest(limit: Int): List<SavedKnowledgeEntity>

    @Query("SELECT * FROM saved_knowledge WHERE projectId = :projectId ORDER BY updatedAt DESC, id LIMIT :limit")
    suspend fun latestInProject(projectId: Long, limit: Int): List<SavedKnowledgeEntity>

    /**
     * Membership-first candidate selection for an active tag/collection
     * filter: the tag/collection JOIN runs inside the query, so the LIMIT
     * applies to rows already within the requested membership instead of an
     * arbitrary raw window that unrelated rows can consume.
     */
    @Query("SELECT sk.* FROM saved_knowledge AS sk INNER JOIN saved_knowledge_tags AS lk ON lk.itemId = sk.id WHERE lk.tagKey = :tagKey AND (sk.title LIKE :pattern ESCAPE '\\' OR sk.savedText LIKE :pattern ESCAPE '\\' OR sk.id IN (SELECT savedKnowledgeId FROM document_highlight_sources WHERE userNote LIKE :pattern ESCAPE '\\')) ORDER BY sk.updatedAt DESC, sk.id LIMIT :limit")
    suspend fun searchInTag(pattern: String, tagKey: String, limit: Int): List<SavedKnowledgeEntity>

    @Query("SELECT sk.* FROM saved_knowledge AS sk INNER JOIN saved_knowledge_tags AS lk ON lk.itemId = sk.id WHERE lk.tagKey = :tagKey AND (sk.title LIKE :pattern ESCAPE '\\' OR sk.savedText LIKE :pattern ESCAPE '\\' OR sk.id IN (SELECT savedKnowledgeId FROM document_highlight_sources WHERE userNote LIKE :pattern ESCAPE '\\')) ORDER BY sk.updatedAt DESC, sk.id LIMIT :limit OFFSET :offset")
    suspend fun searchInTagPage(pattern: String, tagKey: String, limit: Int, offset: Int): List<SavedKnowledgeEntity>

    @Query("SELECT sk.* FROM saved_knowledge AS sk INNER JOIN saved_knowledge_tags AS lk ON lk.itemId = sk.id WHERE lk.tagKey = :tagKey AND sk.projectId = :projectId AND (sk.title LIKE :pattern ESCAPE '\\' OR sk.savedText LIKE :pattern ESCAPE '\\' OR sk.id IN (SELECT savedKnowledgeId FROM document_highlight_sources WHERE userNote LIKE :pattern ESCAPE '\\')) ORDER BY sk.updatedAt DESC, sk.id LIMIT :limit OFFSET :offset")
    suspend fun searchInTagProjectPage(pattern: String, tagKey: String, projectId: Long, limit: Int, offset: Int): List<SavedKnowledgeEntity>

    @Query("SELECT sk.* FROM saved_knowledge AS sk INNER JOIN saved_knowledge_tags AS lk ON lk.itemId = sk.id WHERE lk.tagKey = :tagKey ORDER BY sk.updatedAt DESC, sk.id LIMIT :limit")
    suspend fun latestInTag(tagKey: String, limit: Int): List<SavedKnowledgeEntity>

    @Query("SELECT sk.* FROM saved_knowledge AS sk INNER JOIN saved_knowledge_tags AS lk ON lk.itemId = sk.id WHERE lk.tagKey = :tagKey AND sk.projectId = :projectId ORDER BY sk.updatedAt DESC, sk.id LIMIT :limit")
    suspend fun latestInTagProject(tagKey: String, projectId: Long, limit: Int): List<SavedKnowledgeEntity>

    @Query("SELECT sk.* FROM saved_knowledge AS sk INNER JOIN collection_saved_knowledge AS lk ON lk.itemId = sk.id WHERE lk.collectionId = :collectionId AND (sk.title LIKE :pattern ESCAPE '\\' OR sk.savedText LIKE :pattern ESCAPE '\\' OR sk.id IN (SELECT savedKnowledgeId FROM document_highlight_sources WHERE userNote LIKE :pattern ESCAPE '\\')) ORDER BY sk.updatedAt DESC, sk.id LIMIT :limit")
    suspend fun searchInCollection(pattern: String, collectionId: String, limit: Int): List<SavedKnowledgeEntity>

    @Query("SELECT sk.* FROM saved_knowledge AS sk INNER JOIN collection_saved_knowledge AS lk ON lk.itemId = sk.id WHERE lk.collectionId = :collectionId AND (sk.title LIKE :pattern ESCAPE '\\' OR sk.savedText LIKE :pattern ESCAPE '\\' OR sk.id IN (SELECT savedKnowledgeId FROM document_highlight_sources WHERE userNote LIKE :pattern ESCAPE '\\')) ORDER BY sk.updatedAt DESC, sk.id LIMIT :limit OFFSET :offset")
    suspend fun searchInCollectionPage(pattern: String, collectionId: String, limit: Int, offset: Int): List<SavedKnowledgeEntity>

    @Query("SELECT sk.* FROM saved_knowledge AS sk INNER JOIN collection_saved_knowledge AS lk ON lk.itemId = sk.id WHERE lk.collectionId = :collectionId AND sk.projectId = :projectId AND (sk.title LIKE :pattern ESCAPE '\\' OR sk.savedText LIKE :pattern ESCAPE '\\' OR sk.id IN (SELECT savedKnowledgeId FROM document_highlight_sources WHERE userNote LIKE :pattern ESCAPE '\\')) ORDER BY sk.updatedAt DESC, sk.id LIMIT :limit OFFSET :offset")
    suspend fun searchInCollectionProjectPage(pattern: String, collectionId: String, projectId: Long, limit: Int, offset: Int): List<SavedKnowledgeEntity>

    @Query("SELECT sk.* FROM saved_knowledge AS sk INNER JOIN collection_saved_knowledge AS lk ON lk.itemId = sk.id WHERE lk.collectionId = :collectionId ORDER BY sk.updatedAt DESC, sk.id LIMIT :limit")
    suspend fun latestInCollection(collectionId: String, limit: Int): List<SavedKnowledgeEntity>

    @Query("SELECT sk.* FROM saved_knowledge AS sk INNER JOIN collection_saved_knowledge AS lk ON lk.itemId = sk.id WHERE lk.collectionId = :collectionId AND sk.projectId = :projectId ORDER BY sk.updatedAt DESC, sk.id LIMIT :limit")
    suspend fun latestInCollectionProject(collectionId: String, projectId: Long, limit: Int): List<SavedKnowledgeEntity>

    @Query("SELECT sk.* FROM saved_knowledge AS sk INNER JOIN saved_knowledge_tags AS lt ON lt.itemId = sk.id INNER JOIN collection_saved_knowledge AS lc ON lc.itemId = sk.id WHERE lt.tagKey = :tagKey AND lc.collectionId = :collectionId AND (sk.title LIKE :pattern ESCAPE '\\' OR sk.savedText LIKE :pattern ESCAPE '\\' OR sk.id IN (SELECT savedKnowledgeId FROM document_highlight_sources WHERE userNote LIKE :pattern ESCAPE '\\')) ORDER BY sk.updatedAt DESC, sk.id LIMIT :limit")
    suspend fun searchInTagAndCollection(pattern: String, tagKey: String, collectionId: String, limit: Int): List<SavedKnowledgeEntity>

    @Query("SELECT sk.* FROM saved_knowledge AS sk INNER JOIN saved_knowledge_tags AS lt ON lt.itemId = sk.id INNER JOIN collection_saved_knowledge AS lc ON lc.itemId = sk.id WHERE lt.tagKey = :tagKey AND lc.collectionId = :collectionId AND (sk.title LIKE :pattern ESCAPE '\\' OR sk.savedText LIKE :pattern ESCAPE '\\' OR sk.id IN (SELECT savedKnowledgeId FROM document_highlight_sources WHERE userNote LIKE :pattern ESCAPE '\\')) ORDER BY sk.updatedAt DESC, sk.id LIMIT :limit OFFSET :offset")
    suspend fun searchInTagAndCollectionPage(pattern: String, tagKey: String, collectionId: String, limit: Int, offset: Int): List<SavedKnowledgeEntity>

    @Query("SELECT sk.* FROM saved_knowledge AS sk INNER JOIN saved_knowledge_tags AS lt ON lt.itemId = sk.id INNER JOIN collection_saved_knowledge AS lc ON lc.itemId = sk.id WHERE lt.tagKey = :tagKey AND lc.collectionId = :collectionId AND sk.projectId = :projectId AND (sk.title LIKE :pattern ESCAPE '\\' OR sk.savedText LIKE :pattern ESCAPE '\\' OR sk.id IN (SELECT savedKnowledgeId FROM document_highlight_sources WHERE userNote LIKE :pattern ESCAPE '\\')) ORDER BY sk.updatedAt DESC, sk.id LIMIT :limit OFFSET :offset")
    suspend fun searchInTagAndCollectionProjectPage(pattern: String, tagKey: String, collectionId: String, projectId: Long, limit: Int, offset: Int): List<SavedKnowledgeEntity>

    @Query("SELECT sk.* FROM saved_knowledge AS sk INNER JOIN saved_knowledge_tags AS lt ON lt.itemId = sk.id INNER JOIN collection_saved_knowledge AS lc ON lc.itemId = sk.id WHERE lt.tagKey = :tagKey AND lc.collectionId = :collectionId ORDER BY sk.updatedAt DESC, sk.id LIMIT :limit")
    suspend fun latestInTagAndCollection(tagKey: String, collectionId: String, limit: Int): List<SavedKnowledgeEntity>

    @Query("SELECT sk.* FROM saved_knowledge AS sk INNER JOIN saved_knowledge_tags AS lt ON lt.itemId = sk.id INNER JOIN collection_saved_knowledge AS lc ON lc.itemId = sk.id WHERE lt.tagKey = :tagKey AND lc.collectionId = :collectionId AND sk.projectId = :projectId ORDER BY sk.updatedAt DESC, sk.id LIMIT :limit")
    suspend fun latestInTagAndCollectionProject(tagKey: String, collectionId: String, projectId: Long, limit: Int): List<SavedKnowledgeEntity>

    @Query("DELETE FROM saved_knowledge WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("UPDATE saved_knowledge SET title = :title, savedText = :savedText, updatedAt = :updatedAt WHERE id = :id AND provenanceType = 'USER_NOTE'")
    suspend fun updateUserNote(id: String, title: String, savedText: String, updatedAt: Long): Int
}

data class SavedKnowledgeSourceAnswer(
    val messageId: Long,
    val conversationId: Long,
    val role: String,
)
