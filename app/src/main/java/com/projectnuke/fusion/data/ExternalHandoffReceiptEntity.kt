package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Durable routing choice only; shared content and source URI are never copied here. */
@Entity(tableName = "external_handoff_receipts")
data class ExternalHandoffReceiptEntity(
    @PrimaryKey val eventId: String,
    val destination: String,
    val targetProjectId: Long?,
    val selectedAt: Long,
    val settledAt: Long?,
    val outcome: String,
)

@Dao
interface ExternalHandoffReceiptDao {
    @Query("SELECT * FROM external_handoff_receipts WHERE eventId = :eventId LIMIT 1")
    suspend fun get(eventId: String): ExternalHandoffReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(receipt: ExternalHandoffReceiptEntity): Long

    @Query("UPDATE external_handoff_receipts SET settledAt = :settledAt, outcome = :outcome WHERE eventId = :eventId AND settledAt IS NULL")
    suspend fun settle(eventId: String, settledAt: Long, outcome: String): Int

    @Query("DELETE FROM external_handoff_receipts WHERE settledAt IS NOT NULL AND eventId NOT IN (SELECT eventId FROM external_handoff_receipts WHERE settledAt IS NOT NULL ORDER BY selectedAt DESC, eventId DESC LIMIT :maxSettled)")
    suspend fun pruneSettled(maxSettled: Int): Int
}
