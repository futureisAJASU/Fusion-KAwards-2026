package com.projectnuke.fusion.provenance

import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.KnowledgeUsageCandidate
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadata
import com.projectnuke.fusion.util.AssistantHistoryResult
import com.projectnuke.fusion.util.visibleAssistantHistoryText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class KnowledgeBacklink(
    val conversationId: Long,
    val messageId: Long,
    val conversationTitle: String,
    val answerPreview: String,
    val createdAt: Long,
)

object KnowledgeBacklinkPolicy {
    const val MAX_RESULTS = 3
    const val MAX_PREVIEW_CHARS = 240
    const val MAX_CANDIDATES = 128

    /**
     * Uses only an exact persisted managed-file identity. Metadata whose path
     * was truncated at write time cannot prove which source file was used.
     */
    fun select(
        managedFileIdentity: String,
        candidates: List<KnowledgeUsageCandidate>,
    ): List<KnowledgeBacklink> {
        if (managedFileIdentity.isBlank()) return emptyList()
        val matching = candidates.asSequence()
            .sortedWith(compareByDescending<KnowledgeUsageCandidate> { it.createdAt }.thenByDescending { it.messageId })
            .filter { candidate ->
                LocalRagSourceMetadata.parse(candidate.content).any { source ->
                    source.attachmentId == managedFileIdentity
                }
            }
            .distinctBy { it.conversationId }
            .mapNotNull { candidate ->
                val answer = when (val visible = visibleAssistantHistoryText(candidate.content)) {
                    is AssistantHistoryResult.Visible -> visible.text.replace(Regex("\\s+"), " ").trim()
                    AssistantHistoryResult.Withhold -> ""
                }
                KnowledgeBacklink(
                    conversationId = candidate.conversationId,
                    messageId = candidate.messageId,
                    conversationTitle = candidate.conversationTitle.take(180).ifBlank { "새 대화" },
                    answerPreview = answer.take(MAX_PREVIEW_CHARS).ifBlank { "답변 미리 보기를 표시할 수 없습니다." },
                    createdAt = candidate.createdAt,
                )
            }
            .take(MAX_RESULTS)
            .toList()
        return matching
    }
}

class KnowledgeBacklinkRepository(private val database: AppDatabase) {
    suspend fun findForProjectFile(
        projectFileId: Long,
        includeArchived: Boolean,
    ): List<KnowledgeBacklink> = withContext(Dispatchers.IO) {
        val file = database.projectDao().getProjectFileById(projectFileId) ?: return@withContext emptyList()
        val candidates = database.knowledgeBacklinkDao().getRecentKnowledgeUsageCandidates(
            includeArchived = includeArchived,
            limit = KnowledgeBacklinkPolicy.MAX_CANDIDATES,
        )
        KnowledgeBacklinkPolicy.select(file.localPath, candidates)
    }

    /** Rechecks source and archive access immediately before following a link. */
    suspend fun targetStillAccessible(
        projectFileId: Long,
        backlink: KnowledgeBacklink,
        includeArchived: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val file: ProjectFileEntity = database.projectDao().getProjectFileById(projectFileId)
                ?: return@withContext false
            val candidate = database.knowledgeBacklinkDao().getAccessibleUsageCandidate(
                messageId = backlink.messageId,
                conversationId = backlink.conversationId,
                includeArchived = includeArchived,
            ) ?: return@withContext false
            LocalRagSourceMetadata.parse(candidate.content).any { it.attachmentId == file.localPath }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}
