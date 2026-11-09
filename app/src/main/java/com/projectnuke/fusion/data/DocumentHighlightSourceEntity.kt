package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Durable source anchor for a Saved Knowledge highlight. Source removal intentionally cascades. */
@Entity(
    tableName = "document_highlight_sources",
    foreignKeys = [
        ForeignKey(SavedKnowledgeEntity::class, ["id"], ["savedKnowledgeId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ProjectFileEntity::class, ["id"], ["projectFileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("projectId"), Index("projectFileId")],
)
data class DocumentHighlightSourceEntity(
    @PrimaryKey val savedKnowledgeId: String,
    val projectId: Long,
    val projectFileId: Long,
    val pageNumber: Int?,
    val sourceChunkId: String?,
    val sourceContentHashHex: String?,
    val userNote: String?,
    val createdAt: Long,
)

@Dao
interface DocumentHighlightSourceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: DocumentHighlightSourceEntity)

    @Query("SELECT * FROM document_highlight_sources WHERE savedKnowledgeId = :savedKnowledgeId LIMIT 1")
    suspend fun getByKnowledgeId(savedKnowledgeId: String): DocumentHighlightSourceEntity?

    @Query("SELECT COUNT(*) FROM document_highlight_sources")
    suspend fun count(): Int
}
