package com.projectnuke.fusion.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message_evidence",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["messageId"])]
)
data class MessageEvidenceEntity(
    @PrimaryKey
    val messageId: Long,
    val answerSha256: String,
    val schemaVersion: Int,
    val verifierVersion: String,
    val checkedAt: Long,
    val reportJson: String
)

/** Room projection used for citation invalidation without observing reportJson. */
data class MessageEvidenceRevision(
    val messageId: Long,
    val answerSha256: String,
    val schemaVersion: Int,
    val verifierVersion: String,
    val checkedAt: Long,
    val reportJsonLength: Int,
)
