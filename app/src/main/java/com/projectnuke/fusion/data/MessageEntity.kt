package com.projectnuke.fusion.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Embedded

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversationId"])]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val createdAt: Long
)

data class MessageIdentity(
    val id: Long,
    val conversationId: Long,
    val role: String,
    val createdAt: Long,
)

data class MessageContentHit(
    val conversationId: Long,
    val content: String,
)

data class ConversationMessageSearchHit(
    @Embedded val conversation: ConversationEntity,
    val content: String,
)

data class MessageContentPageRow(
    val id: Long,
    val content: String,
)

data class KnowledgeUsageCandidate(
    val messageId: Long,
    val conversationId: Long,
    val content: String,
    val createdAt: Long,
    val conversationTitle: String,
)
