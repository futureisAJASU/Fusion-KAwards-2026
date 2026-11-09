package com.projectnuke.fusion.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["refreshedFromConversationId", "refreshedFromMessageId"])],
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val branchedFromConversationId: Long? = null,
    val branchedFromMessageId: Long? = null,
    val refreshedFromConversationId: Long? = null,
    val refreshedFromMessageId: Long? = null,
)
