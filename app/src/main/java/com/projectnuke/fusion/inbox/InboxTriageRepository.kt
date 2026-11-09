package com.projectnuke.fusion.inbox

import androidx.room.withTransaction
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.BoundedProjectFileInsertResult
import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.knowledge.KnowledgeOrganizationRepository
import com.projectnuke.fusion.knowledge.OrganizationWriteResult
import com.projectnuke.fusion.knowledge.SavedKnowledgePolicy
import com.projectnuke.fusion.knowledge.SavedKnowledgeProvenance
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface InboxTriageAction {
    data object TO_SAVED_KNOWLEDGE : InboxTriageAction
    data class TO_PROJECT(val projectId: Long) : InboxTriageAction
    data class ADD_TAG(val tag: String) : InboxTriageAction
    data object DELETE : InboxTriageAction
}

enum class InboxTriageOutcome { COMPLETED, MISSING, FAILED }

data class InboxTriageSettlement(
    val itemId: String,
    val title: String,
    val outcome: InboxTriageOutcome,
)

data class InboxTriageSummary(val completed: Int, val failed: Int, val missing: Int, val total: Int)

object InboxTriagePolicy {
    const val MAX_SELECTION = 100

    fun select(items: List<InboxItemEntity>): List<InboxItemEntity>? {
        if (items.size > MAX_SELECTION || items.any { it.id.isBlank() }) return null
        return items.distinctBy(InboxItemEntity::id)
    }

    fun summarize(settlements: List<InboxTriageSettlement>): InboxTriageSummary = InboxTriageSummary(
        completed = settlements.count { it.outcome == InboxTriageOutcome.COMPLETED },
        failed = settlements.count { it.outcome == InboxTriageOutcome.FAILED },
        missing = settlements.count { it.outcome == InboxTriageOutcome.MISSING },
        total = settlements.size,
    )
}

/** Each selected object settles in its own durable Room transaction. */
class InboxTriageRepository(
    private val database: AppDatabase,
    private val managedAttachmentExists: suspend (String) -> Boolean,
) {
    suspend fun apply(
        selectedItems: List<InboxItemEntity>,
        action: InboxTriageAction,
    ): List<InboxTriageSettlement> {
        val selected = InboxTriagePolicy.select(selectedItems)
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        return withContext(Dispatchers.IO) {
            buildList(selected.size) {
                for (selectedItem in selected) {
                    currentCoroutineContext().ensureActive()
                    add(settleOne(selectedItem, action))
                }
            }
        }
    }

    private suspend fun settleOne(item: InboxItemEntity, action: InboxTriageAction): InboxTriageSettlement {
        val outcome = try {
            when (action) {
                InboxTriageAction.TO_SAVED_KNOWLEDGE -> moveToKnowledge(item, projectId = null)
                is InboxTriageAction.TO_PROJECT -> moveToProject(item, action.projectId)
                is InboxTriageAction.ADD_TAG -> when (KnowledgeOrganizationRepository(database).addInboxTag(
                    item.id,
                    action.tag,
                    expectedItem = item,
                )) {
                    OrganizationWriteResult.Updated -> InboxTriageOutcome.COMPLETED
                    OrganizationWriteResult.ItemUnavailable -> InboxTriageOutcome.MISSING
                    else -> InboxTriageOutcome.FAILED
                }
                InboxTriageAction.DELETE -> deleteOne(item)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            InboxTriageOutcome.FAILED
        }
        return InboxTriageSettlement(item.id, item.title, outcome)
    }

    private suspend fun moveToKnowledge(item: InboxItemEntity, projectId: Long?): InboxTriageOutcome =
        database.withTransaction {
            if (database.inboxItemDao().get(item.id) != item) return@withTransaction InboxTriageOutcome.MISSING
            if (item.content.isBlank() || SavedKnowledgePolicy.containsSensitiveMaterial(item.title + "\n" + item.content)) {
                return@withTransaction InboxTriageOutcome.FAILED
            }
            if (projectId != null && database.projectDao().getProjectById(projectId) == null) {
                return@withTransaction InboxTriageOutcome.FAILED
            }
            val now = System.currentTimeMillis()
            val newId = UUID.randomUUID().toString()
            database.savedKnowledgeDao().insert(
                SavedKnowledgeEntity(
                    id = newId,
                    scope = if (projectId == null) "PERSONAL" else "PROJECT",
                    projectId = projectId,
                    title = item.title.take(SavedKnowledgePolicy.MAX_TITLE_CHARS),
                    savedText = item.content.take(SavedKnowledgePolicy.MAX_TEXT_CHARS),
                    provenanceType = SavedKnowledgeProvenance.INBOX_CAPTURE.name,
                    sourceConversationId = null,
                    sourceMessageId = null,
                    createdAt = now,
                    updatedAt = now,
                    sourceUrl = item.originalUri,
                    effectiveSourceUrl = item.effectiveSourceUri,
                ),
            )
            // Moving is a location transition, not metadata reset (C18):
            // carry tags and collection memberships to the new identity in
            // the same transaction before the CASCADE delete of inbox links.
            transferOrganizationLinks(item.id, newId)
            if (database.inboxItemDao().delete(item.id) != 1) throw InboxTriageRollback()
            InboxTriageOutcome.COMPLETED
        }

    private suspend fun moveToProject(item: InboxItemEntity, projectId: Long): InboxTriageOutcome {
        val managedFileId = item.managedFileId?.takeIf { it.isNotBlank() }
        if (managedFileId != null && !managedAttachmentExists(managedFileId)) return InboxTriageOutcome.FAILED
        return database.withTransaction {
            if (database.inboxItemDao().get(item.id) != item) return@withTransaction InboxTriageOutcome.MISSING
            if (database.projectDao().getProjectById(projectId) == null) return@withTransaction InboxTriageOutcome.FAILED
            if (managedFileId != null) {
                val file = ProjectFileEntity(
                    projectId = projectId,
                    displayName = item.title,
                    mimeType = item.managedMimeType ?: "application/octet-stream",
                    localPath = managedFileId,
                    addedAt = System.currentTimeMillis(),
                    sourceHandoffEventId = "inbox-${item.id}",
                )
                val result = database.projectDao().insertHandoffProjectFileBounded(file)
                if (result !is BoundedProjectFileInsertResult.Inserted && result !is BoundedProjectFileInsertResult.Existing) {
                    return@withTransaction InboxTriageOutcome.FAILED
                }
            } else {
                if (item.content.isBlank() || SavedKnowledgePolicy.containsSensitiveMaterial(item.title + "\n" + item.content)) {
                    return@withTransaction InboxTriageOutcome.FAILED
                }
                val now = System.currentTimeMillis()
                val newId = UUID.randomUUID().toString()
                database.savedKnowledgeDao().insert(
                    SavedKnowledgeEntity(
                        id = newId,
                        scope = "PROJECT",
                        projectId = projectId,
                        title = item.title.take(SavedKnowledgePolicy.MAX_TITLE_CHARS),
                        savedText = item.content.take(SavedKnowledgePolicy.MAX_TEXT_CHARS),
                        provenanceType = SavedKnowledgeProvenance.INBOX_CAPTURE.name,
                        sourceConversationId = null,
                        sourceMessageId = null,
                        createdAt = now,
                        updatedAt = now,
                        sourceUrl = item.originalUri,
                        effectiveSourceUrl = item.effectiveSourceUri,
                    ),
                )
                // Same location-transition preservation as moveToKnowledge (C18).
                transferOrganizationLinks(item.id, newId)
            }
            if (database.inboxItemDao().delete(item.id) != 1) throw InboxTriageRollback()
            InboxTriageOutcome.COMPLETED
        }
    }

    /**
     * Carries inbox tag and collection links to the moved saved-knowledge
     * identity. Must run inside the move transaction before the inbox delete
     * (whose CASCADE would otherwise destroy the classification). IGNORE
     * conflicts keep the move idempotent on retry.
     */
    private suspend fun transferOrganizationLinks(inboxItemId: String, savedKnowledgeId: String) {
        val orgDao = database.knowledgeOrganizationDao()
        orgDao.tagsForInboxItem(inboxItemId).forEach { tag ->
            orgDao.insertSavedTag(
                com.projectnuke.fusion.data.SavedKnowledgeTagLinkEntity(savedKnowledgeId, tag.tagKey),
            )
        }
        orgDao.collectionsForInboxItem(inboxItemId).forEach { collection ->
            orgDao.insertSavedCollection(
                com.projectnuke.fusion.data.CollectionSavedKnowledgeLinkEntity(
                    collection.collectionId,
                    savedKnowledgeId,
                ),
            )
        }
    }

    private suspend fun deleteOne(item: InboxItemEntity): InboxTriageOutcome = database.withTransaction {
        if (database.inboxItemDao().get(item.id) != item) return@withTransaction InboxTriageOutcome.MISSING
        if (database.inboxItemDao().delete(item.id) == 1) InboxTriageOutcome.COMPLETED else InboxTriageOutcome.FAILED
    }

    private class InboxTriageRollback : IllegalStateException()
}
