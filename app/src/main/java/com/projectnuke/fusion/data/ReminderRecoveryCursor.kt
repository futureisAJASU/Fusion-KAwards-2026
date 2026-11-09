package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Persisted round-robin cursor for bounded reminder recovery queues. */
@Entity(tableName = "reminder_recovery_cursors")
data class ReminderRecoveryCursorEntity(
    @PrimaryKey val recoveryKey: String,
    val cursor: String,
)

@Dao
interface ReminderRecoveryCursorDao {
    @Query("SELECT cursor FROM reminder_recovery_cursors WHERE recoveryKey = :recoveryKey LIMIT 1")
    suspend fun getCursor(recoveryKey: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: ReminderRecoveryCursorEntity)
}
