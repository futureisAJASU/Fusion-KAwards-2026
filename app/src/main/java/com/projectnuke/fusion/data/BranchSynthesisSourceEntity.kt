package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Durable provenance for the selected source branches of a synthesis conversation. */
@Entity(
    tableName = "branch_synthesis_sources",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["synthesisConversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["synthesisConversationId", "sourceOrdinal"], unique = true),
        Index(value = ["sourceConversationId"]),
    ],
)
data class BranchSynthesisSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val synthesisConversationId: Long,
    /** Historical identity; deliberately not an FK so source deletion keeps provenance. */
    val sourceConversationId: Long,
    val sourceTitle: String,
    val sourceOrdinal: Int,
    val intent: String,
    val createdAt: Long,
)

@Dao
interface BranchSynthesisSourceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(sources: List<BranchSynthesisSourceEntity>)

    @Query("SELECT * FROM branch_synthesis_sources WHERE synthesisConversationId = :conversationId ORDER BY sourceOrdinal ASC")
    suspend fun getForSynthesisConversation(conversationId: Long): List<BranchSynthesisSourceEntity>
}
