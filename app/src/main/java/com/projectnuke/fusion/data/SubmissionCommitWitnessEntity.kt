package com.projectnuke.fusion.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Historical proof that a schema-8 submission committed its user message.
 *
 * This row deliberately has no foreign key to the message: the witness must
 * survive later conversation deletion until composer ownership is reconciled.
 */
@Entity(tableName = "submission_commit_witnesses")
data class SubmissionCommitWitnessEntity(
    @PrimaryKey
    val token: String,
    val draftKey: Long,
    val committedConversationId: Long,
    val userMessageId: Long,
    val capturedRawInput: String,
    val committedPathsJson: String,
    val createdAt: Long,
)

internal object SubmissionCommitWitnessContract {
    const val PROTOCOL_PREFIX = "s8:"
    const val MAX_TOKEN_CHARS = 128
    const val MAX_RAW_INPUT_CHARS = 32_768
    const val MAX_PATHS = 32
    const val MAX_PATH_CHARS = 4_096

    fun isSchema8Token(token: String): Boolean =
        token.startsWith(PROTOCOL_PREFIX) && token.length in (PROTOCOL_PREFIX.length + 1)..MAX_TOKEN_CHARS
}
