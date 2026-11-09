package com.projectnuke.fusion.data

import androidx.room.withTransaction
import com.projectnuke.fusion.chat.CONVERSATION_CHECKPOINT_MAX_NOTE_CODE_POINTS
import com.projectnuke.fusion.chat.CONVERSATION_CHECKPOINT_MAX_PER_CONVERSATION
import com.projectnuke.fusion.chat.CONVERSATION_CHECKPOINT_MAX_TITLE_CODE_POINTS
import com.projectnuke.fusion.chat.boundedCheckpointText
import kotlinx.coroutines.CancellationException

internal sealed interface CheckpointWriteResult {
    data class Saved(val id: Long) : CheckpointWriteResult
    data object InvalidText : CheckpointWriteResult
    data object MissingSource : CheckpointWriteResult
    data object LimitReached : CheckpointWriteResult
    data object Failed : CheckpointWriteResult
}

internal class ConversationCheckpointRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun save(conversationId: Long, messageId: Long, title: String, note: String): CheckpointWriteResult {
        val boundedTitle = boundedCheckpointText(title, CONVERSATION_CHECKPOINT_MAX_TITLE_CODE_POINTS)
        val boundedNote = if (note.isBlank()) boundedCheckpointText("", 0) else boundedCheckpointText(note, CONVERSATION_CHECKPOINT_MAX_NOTE_CODE_POINTS)
        if (!boundedTitle.valid || (note.isNotBlank() && !boundedNote.valid)) return CheckpointWriteResult.InvalidText
        return try {
            db.withTransaction {
                val conversation = db.chatDao().getConversationById(conversationId)
                if (conversation == null || !db.conversationCheckpointDao().hasAssistantAnchor(conversationId, messageId)) {
                    return@withTransaction CheckpointWriteResult.MissingSource
                }
                val checkpoints = db.conversationCheckpointDao()
                if (checkpoints.countForConversation(conversationId) >= CONVERSATION_CHECKPOINT_MAX_PER_CONVERSATION) {
                    return@withTransaction CheckpointWriteResult.LimitReached
                }
                CheckpointWriteResult.Saved(
                    checkpoints.insert(
                        ConversationCheckpointEntity(
                            conversationId = conversationId,
                            messageId = messageId,
                            title = boundedTitle.value,
                            createdAt = clock(),
                            note = boundedNote.value,
                        ),
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CheckpointWriteResult.Failed
        }
    }

    suspend fun rename(id: Long, conversationId: Long, title: String, note: String): Boolean {
        val boundedTitle = boundedCheckpointText(title, CONVERSATION_CHECKPOINT_MAX_TITLE_CODE_POINTS)
        val boundedNote = if (note.isBlank()) boundedCheckpointText("", 0) else boundedCheckpointText(note, CONVERSATION_CHECKPOINT_MAX_NOTE_CODE_POINTS)
        if (!boundedTitle.valid || (note.isNotBlank() && !boundedNote.valid)) return false
        return try {
            val dao = db.conversationCheckpointDao()
            if (dao.getOwned(id, conversationId) == null) return false
            dao.rename(id, conversationId, boundedTitle.value, boundedNote.value) == 1
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}
