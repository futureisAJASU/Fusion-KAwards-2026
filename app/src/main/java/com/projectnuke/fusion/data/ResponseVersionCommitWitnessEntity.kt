package com.projectnuke.fusion.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Structural proof for an assistant message whose response-version state is pending. */
@Entity(
    tableName = "response_version_commit_witnesses",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversationId"])],
)
data class ResponseVersionCommitWitnessEntity(
    @androidx.room.PrimaryKey
    val messageId: Long,
    val conversationId: Long,
    val targetAssistantMessageId: Long,
    val groupId: Long,
    val createdAt: Long,
)
