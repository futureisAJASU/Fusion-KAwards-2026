package com.projectnuke.fusion.documents

import androidx.room.withTransaction
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.DocumentHighlightSourceEntity
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.knowledge.SavedKnowledgeProvenance
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface DocumentHighlightWriteResult {
    data class Stored(val savedKnowledgeId: String) : DocumentHighlightWriteResult
    data object Invalid : DocumentHighlightWriteResult
    data object SensitiveContent : DocumentHighlightWriteResult
    data object SourceUnavailable : DocumentHighlightWriteResult
    data object CapacityReached : DocumentHighlightWriteResult
    data object Failed : DocumentHighlightWriteResult
}

/** Stores a quote as Saved Knowledge with an independently durable source anchor. */
class DocumentHighlightRepository(private val database: AppDatabase) {
    suspend fun save(draft: DocumentHighlightDraft): DocumentHighlightWriteResult = withContext(Dispatchers.IO) {
        val prepared = when (val result = DocumentHighlightPolicy.prepare(draft)) {
            is DocumentHighlightPreparation.Ready -> result.value
            DocumentHighlightPreparation.Invalid -> return@withContext DocumentHighlightWriteResult.Invalid
            DocumentHighlightPreparation.SensitiveContent -> return@withContext DocumentHighlightWriteResult.SensitiveContent
        }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        try {
            database.withTransaction {
                val source = database.projectDao().getProjectFileById(prepared.projectFileId)
                if (source == null || source.projectId != prepared.projectId) {
                    return@withTransaction DocumentHighlightWriteResult.SourceUnavailable
                }
                if (database.documentHighlightSourceDao().count() >= DocumentHighlightPolicy.MAX_HIGHLIGHTS) {
                    return@withTransaction DocumentHighlightWriteResult.CapacityReached
                }
                database.savedKnowledgeDao().insert(
                    SavedKnowledgeEntity(
                        id = id,
                        scope = "PROJECT",
                        projectId = prepared.projectId,
                        title = prepared.title,
                        savedText = prepared.quotedText,
                        provenanceType = SavedKnowledgeProvenance.DOCUMENT_HIGHLIGHT.name,
                        sourceConversationId = null,
                        sourceMessageId = null,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                database.documentHighlightSourceDao().insert(
                    DocumentHighlightSourceEntity(
                        savedKnowledgeId = id,
                        projectId = prepared.projectId,
                        projectFileId = prepared.projectFileId,
                        pageNumber = prepared.pageNumber,
                        sourceChunkId = prepared.sourceChunkId,
                        sourceContentHashHex = prepared.sourceContentHashHex,
                        userNote = prepared.userNote,
                        createdAt = now,
                    ),
                )
                DocumentHighlightWriteResult.Stored(id)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DocumentHighlightWriteResult.Failed
        }
    }
}
