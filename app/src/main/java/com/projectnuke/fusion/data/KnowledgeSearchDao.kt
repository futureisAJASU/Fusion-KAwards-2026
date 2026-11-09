package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Query

/** Bounded read projections over existing owners. No search database or duplicate persistence. */
@Dao
interface KnowledgeSearchDao {
    @Query("SELECT * FROM conversations WHERE (:includeArchived OR isArchived = 0) AND title LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    suspend fun searchConversationTitles(query: String, includeArchived: Boolean, limit: Int): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE (:includeArchived OR isArchived = 0) ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    suspend fun getSearchConversations(includeArchived: Boolean, limit: Int): List<ConversationEntity>

    @Query("SELECT id FROM conversations WHERE (:includeArchived OR isArchived = 0)")
    suspend fun getSearchConversationIds(includeArchived: Boolean): List<Long>

    @Query("SELECT conversationId, content FROM messages WHERE conversationId IN (:ids) AND length(content) <= 65536 ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun getSearchMessages(ids: List<Long>, limit: Int): List<MessageContentHit>

    @Query("""
        SELECT conversations.*,
            (SELECT matched.content
             FROM messages AS matched
             WHERE matched.conversationId = conversations.id
               AND length(matched.content) <= 65536
               AND matched.content LIKE '%' || :query || '%' ESCAPE '\'
             ORDER BY matched.createdAt DESC, matched.id DESC
             LIMIT 1) AS content
        FROM conversations
        WHERE (:includeArchived OR conversations.isArchived = 0)
          AND EXISTS (
              SELECT 1 FROM messages AS matching
              WHERE matching.conversationId = conversations.id
                AND length(matching.content) <= 65536
                AND matching.content LIKE '%' || :query || '%' ESCAPE '\'
          )
        ORDER BY conversations.updatedAt DESC, conversations.id DESC
        LIMIT :limit
    """)
    suspend fun searchConversationMessageHits(
        query: String,
        includeArchived: Boolean,
        limit: Int,
    ): List<ConversationMessageSearchHit>

    @Query("SELECT * FROM projects WHERE name LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    suspend fun searchProjects(query: String, limit: Int): List<ProjectEntity>

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    suspend fun getSearchProjects(limit: Int): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :projectId AND deletionPending = 0 AND (name LIKE '%' || :query || '%' ESCAPE '\\' OR instructions LIKE '%' || :query || '%' ESCAPE '\\') LIMIT 1")
    suspend fun searchProjectById(projectId: Long, query: String): ProjectEntity?

    @Query("SELECT * FROM project_files ORDER BY addedAt DESC, id DESC LIMIT :limit")
    suspend fun getSearchFiles(limit: Int): List<ProjectFileEntity>

    @Query("SELECT * FROM project_files WHERE projectId = :projectId ORDER BY addedAt DESC, id DESC LIMIT :limit")
    suspend fun getSearchFilesForProject(projectId: Long, limit: Int): List<ProjectFileEntity>

    @Query("SELECT id, content FROM messages WHERE conversationId = :conversationId AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getConversationMessagePage(
        conversationId: Long,
        afterId: Long,
        limit: Int,
    ): List<MessageContentPageRow>

    @Query("SELECT * FROM conversations WHERE branchedFromConversationId = :parentId AND (:includeArchived OR isArchived = 0) ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    suspend fun getBranchChildren(parentId: Long, includeArchived: Boolean, limit: Int): List<ConversationEntity>
}
