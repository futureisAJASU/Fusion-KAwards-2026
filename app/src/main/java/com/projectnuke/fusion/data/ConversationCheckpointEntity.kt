package com.projectnuke.fusion.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_checkpoints",
    foreignKeys = [
        ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["conversationId", "createdAt"]), Index(value = ["messageId"])],
)
data class ConversationCheckpointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val messageId: Long,
    val title: String,
    val createdAt: Long,
    val note: String = "",
)

data class ConversationCheckpointSearchRow(
    val id: Long,
    val conversationId: Long,
    val messageId: Long,
    val title: String,
    val createdAt: Long,
    val note: String,
    val conversationTitle: String,
    val isArchived: Boolean,
)
