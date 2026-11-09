package com.projectnuke.fusion.knowledge

import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface SavedKnowledgeWriteResult {
    data object Stored : SavedKnowledgeWriteResult
    data object SourceUnavailable : SavedKnowledgeWriteResult
    data object ProjectUnavailable : SavedKnowledgeWriteResult
    data object AccessUnavailable : SavedKnowledgeWriteResult
    data object SensitiveContent : SavedKnowledgeWriteResult
    data object Failed : SavedKnowledgeWriteResult
}

fun interface SavedKnowledgeWriter {
    suspend fun save(proposal: SavedKnowledgeProposal, archiveAccessIncluded: Boolean): SavedKnowledgeWriteResult
}

/** Durable writes revalidate the exact answer owner and project binding on IO. */
class SavedKnowledgeRepository(private val database: AppDatabase) : SavedKnowledgeWriter {
    override suspend fun save(proposal: SavedKnowledgeProposal, archiveAccessIncluded: Boolean): SavedKnowledgeWriteResult = withContext(Dispatchers.IO) {
        try {
            // Content policy does not read durable source state, so it stays
            // outside the transaction.
            if (SavedKnowledgePolicy.containsSensitiveMaterial(proposal.title + "\n" + proposal.visibleText)) {
                return@withContext SavedKnowledgeWriteResult.SensitiveContent
            }
            // Source authority is revalidated inside the SAME Room transaction as
            // the INSERT. Checking outside left a window where the conversation
            // could be deleted, archived, or reassigned between validation and
            // the write, so a row could be stored with provenance pointing at a
            // source the access policy no longer admits.
            database.withTransaction {
                val conversation = database.chatDao().getConversationById(proposal.sourceConversationId)
                    ?: return@withTransaction SavedKnowledgeWriteResult.SourceUnavailable
                if (conversation.isArchived && !archiveAccessIncluded) {
                    return@withTransaction SavedKnowledgeWriteResult.AccessUnavailable
                }
                val answer = database.savedKnowledgeDao().getSourceAnswer(proposal.sourceMessageId, conversation.id)
                    ?.takeIf { it.messageId == proposal.sourceMessageId && it.conversationId == conversation.id && it.role == "assistant" }
                    ?: return@withTransaction SavedKnowledgeWriteResult.SourceUnavailable
                if (answer.messageId <= 0L) return@withTransaction SavedKnowledgeWriteResult.SourceUnavailable
                if (proposal.scope == SavedKnowledgeScope.PROJECT) {
                    val projectId = proposal.projectId ?: return@withTransaction SavedKnowledgeWriteResult.ProjectUnavailable
                    if (database.projectDao().getProjectById(projectId) == null ||
                        database.projectDao().getProjectIdForConversation(conversation.id) != projectId
                    ) return@withTransaction SavedKnowledgeWriteResult.ProjectUnavailable
                }
                database.savedKnowledgeDao().insert(SavedKnowledgePolicy.toEntity(proposal))
                SavedKnowledgeWriteResult.Stored
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SavedKnowledgeWriteResult.Failed
        }
    }

    suspend fun get(id: String): SavedKnowledgeEntity? = withContext(Dispatchers.IO) {
        database.savedKnowledgeDao().getById(id)
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        database.savedKnowledgeDao().delete(id) == 1
    }
}

data class ProjectSavedKnowledgeState(
    val items: List<SavedKnowledgeEntity> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val writeError: String? = null,
)

/** Project-scoped read/delete owner; Compose only observes its bounded state. */
class ProjectSavedKnowledgeOwner(
    private val database: AppDatabase,
    private val projectId: Long,
    private val scope: CoroutineScope,
    private val archiveAccessIncluded: () -> Boolean = { false },
) {
    private val mutableState = MutableStateFlow(ProjectSavedKnowledgeState())
    val state = mutableState.asStateFlow()
    private val observeJob = scope.launch {
        try {
            val savedFlow = database.savedKnowledgeDao().observeProjectItems(projectId, 256)
            val archiveFlow = database.chatDao().observeConversations()
            combine(savedFlow, archiveFlow) { _, _ ->
                val visible = try {
                    withContext(Dispatchers.IO) {
                        SavedKnowledgeAccessGate.selectAccessibleBounded(
                            database,
                            projectId,
                            pageSize = 24,
                            requiredCount = PROJECT_SAVED_ITEMS_LIMIT,
                            archiveAllowed = archiveAccessIncluded(),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.value = mutableState.value.copy(loading = false, failed = true)
                    return@combine
                }
                mutableState.value = mutableState.value.copy(
                    items = visible, loading = false, failed = false, writeError = null,
                )
            }.collect { }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = ProjectSavedKnowledgeState(loading = false, failed = true)
        }
    }

    fun delete(id: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val current = database.savedKnowledgeDao().getById(id)
                    if (current?.projectId != projectId || database.savedKnowledgeDao().delete(id) != 1) {
                        mutableState.value = mutableState.value.copy(writeError = "저장 지식을 삭제하지 못했습니다. 다시 시도해 주세요.")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(writeError = "저장 지식을 삭제하지 못했습니다. 다시 시도해 주세요.")
            }
        }
    }

    fun close() = observeJob.cancel()

    private companion object {
        const val PROJECT_SAVED_ITEMS_LIMIT = 24
    }
}
