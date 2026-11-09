package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Durable source identity for an editable Saved Knowledge note; never stores excerpts or paths. */
@Entity(
    tableName = "saved_knowledge_note_sources",
    foreignKeys = [
        ForeignKey(
            entity = SavedKnowledgeEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["noteId", "orderIndex"]),
        Index(value = ["noteId", "sourceKey"], unique = true),
    ],
)
data class SavedKnowledgeNoteSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: String,
    val orderIndex: Int,
    val sourceKey: String,
    val sourceKind: String,
    val conversationId: Long? = null,
    val projectId: Long? = null,
    val projectFileId: Long? = null,
    val savedKnowledgeId: String? = null,
    val sourceTitle: String,
    val createdAt: Long,
)

@Dao
interface SavedKnowledgeNoteSourceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<SavedKnowledgeNoteSourceEntity>)

    @Query("SELECT * FROM saved_knowledge_note_sources WHERE noteId = :noteId ORDER BY orderIndex ASC, id ASC LIMIT :limit")
    suspend fun getForNote(noteId: String, limit: Int): List<SavedKnowledgeNoteSourceEntity>

    @Query("DELETE FROM saved_knowledge_note_sources WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: String): Int
}
