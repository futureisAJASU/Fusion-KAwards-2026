package com.projectnuke.fusion.ui

import android.content.Context
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.MessageContentPageRow
import com.projectnuke.fusion.knowledge.SourceAccessLookups
import com.projectnuke.fusion.knowledge.SourceAccessAuthority
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun scanConversationAttachmentReference(
    conversationId: Long,
    managedPath: String,
    pageSize: Int = 64,
    loadPage: suspend (conversationId: Long, afterId: Long, limit: Int) -> List<MessageContentPageRow>,
    matches: (content: String, managedPath: String) -> Boolean,
): Boolean {
    require(pageSize > 0)
    var afterId = 0L
    while (true) {
        val page = loadPage(conversationId, afterId, pageSize)
        if (page.isEmpty()) return false
        if (page.any { matches(it.content, managedPath) }) return true
        val lastId = page.last().id
        if (lastId <= afterId) return false
        afterId = lastId
    }
}

/**
 * Issue 5: production lookups behind the shared [SourceAccessAuthority].
 * Implements the exact canonical rules previously inline in
 * `FusionKnowledgeSearchRepository.accessible()` — archived-conversation
 * gating, managed-attachment resolution plus reference scan,
 * project-file usability, memory scope/visibility, and the
 * saved-knowledge archive-source policy.
 */
internal class RoomSourceAccessLookups(
    private val context: Context,
    private val database: AppDatabase,
) : SourceAccessLookups {
    override suspend fun conversation(conversationId: Long): SourceAccessLookups.ConversationState? =
        withContext(Dispatchers.IO) {
            database.chatDao().getConversationById(conversationId)?.let {
                SourceAccessLookups.ConversationState(it.id, it.isArchived)
            }
        }

    override suspend fun attachmentReferenced(conversationId: Long, managedPath: String): Boolean =
        withContext(Dispatchers.IO) {
            val attachmentRoot = AttachmentStorageManager.getAttachmentDirectory(context)
            val dao = database.knowledgeSearchDao()
            scanConversationAttachmentReference(
                conversationId = conversationId,
                managedPath = managedPath,
                loadPage = dao::getConversationMessagePage,
                matches = { content, requestedPath ->
                    AttachmentMessageCodec.parseTrustedAttachmentMessage(content, attachmentRoot)
                        .records.any { it.localPath == requestedPath }
                },
            )
        }

    override suspend fun managedAttachmentResolvable(managedPath: String): Boolean =
        withContext(Dispatchers.IO) {
            AttachmentStorageManager.resolveManagedAttachment(context, managedPath) != null
        }

    override suspend fun managedAttachmentExists(managedPath: String): Boolean =
        withContext(Dispatchers.IO) {
            AttachmentStorageManager.resolveManagedAttachment(context, managedPath) != null
        }

    override suspend fun projectExists(projectId: Long): Boolean =
        withContext(Dispatchers.IO) {
            database.projectDao().getProjectById(projectId) != null
        }

    override suspend fun projectFileResolvable(projectId: Long, fileId: Long): Boolean =
        withContext(Dispatchers.IO) {
            database.projectDao().getProjectFiles(projectId).any {
                it.id == fileId &&
                    AttachmentStorageManager.resolveManagedAttachment(context, it.localPath) != null
            }
        }

    override suspend fun projectFileOwned(projectId: Long, fileId: Long): Boolean =
        withContext(Dispatchers.IO) {
            database.projectDao().getProjectFileById(fileId)?.projectId == projectId
        }

    override suspend fun memory(memoryId: String): SourceAccessLookups.MemoryState? =
        withContext(Dispatchers.IO) {
            loadAllConversationMemoryCandidates(context).firstOrNull { it.id == memoryId }?.let {
                SourceAccessLookups.MemoryState(
                    enabled = it.enabled,
                    savedByUser = it.savedByUser,
                    scopeName = it.scope.name,
                    conversationId = it.conversationId,
                )
            }
        }

    override suspend fun memoryRecorded(memoryId: String): Boolean =
        withContext(Dispatchers.IO) {
            loadAllConversationMemoryCandidates(context).any { it.id == memoryId }
        }

    override suspend fun savedKnowledge(savedKnowledgeId: String): SourceAccessLookups.SavedKnowledgeState? =
        withContext(Dispatchers.IO) {
            database.savedKnowledgeDao().getById(savedKnowledgeId)?.let {
                SourceAccessLookups.SavedKnowledgeState(it.sourceConversationId, it.provenanceType)
            }
        }

    override suspend fun savedKnowledgeExists(savedKnowledgeId: String): Boolean =
        withContext(Dispatchers.IO) {
            database.savedKnowledgeDao().getById(savedKnowledgeId) != null
        }

    override suspend fun noteSourceArchivedStates(noteId: String): List<Boolean> =
        withContext(Dispatchers.IO) {
            database.savedKnowledgeNoteSourceDao().getForNote(noteId, 8).mapNotNull { source ->
                when (source.sourceKind) {
                    "CONVERSATION" -> source.conversationId?.let { id ->
                        database.chatDao().getConversationById(id)?.isArchived
                    }
                    "SAVED_KNOWLEDGE" -> source.savedKnowledgeId?.let { id ->
                        database.savedKnowledgeDao().getById(id)?.sourceConversationId?.let { conversationId ->
                            database.chatDao().getConversationById(conversationId)?.isArchived
                        }
                    }
                    else -> null
                }
            }
        }
}

/** Session-archive-aware authority for Work Plan gates and result display. */
internal fun workPlanAccessAuthority(
    context: Context,
    database: AppDatabase,
    archiveAccessIncluded: Boolean,
): SourceAccessAuthority = SourceAccessAuthority(
    archiveAccessIncluded = { archiveAccessIncluded },
    lookups = RoomSourceAccessLookups(context.applicationContext, database),
)
