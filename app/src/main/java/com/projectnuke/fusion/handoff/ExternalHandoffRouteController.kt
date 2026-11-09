package com.projectnuke.fusion.handoff

import androidx.room.withTransaction
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.inbox.InboxCapture
import com.projectnuke.fusion.inbox.InboxCapturePolicy
import com.projectnuke.fusion.inbox.InboxCaptureType
import com.projectnuke.fusion.inbox.InboxRepository
import com.projectnuke.fusion.inbox.InboxWriteResult
import com.projectnuke.fusion.knowledge.SavedKnowledgePolicy
import com.projectnuke.fusion.knowledge.SavedKnowledgeProvenance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Applies one durable user-selected destination through the existing data owners. */
internal class ExternalHandoffRouteController(
    private val database: AppDatabase,
    private val chatViewModel: ChatViewModel,
    private val textCoordinator: TextHandoffCoordinator,
    private val documentCoordinator: DocumentHandoffCoordinator,
) {
    suspend fun route(handoff: ExternalHandoff, selection: ExternalHandoffSelection): HandoffImportOutcome {
        if (!ExternalHandoffDestinationPolicy.accepts(selection, handoff)) return HandoffImportOutcome.FAILED_IMPORT
        val receipts = ExternalHandoffSelectionRepository(database)
        when (receipts.select(selection)) {
            HandoffSelectionResult.Stored,
            HandoffSelectionResult.Existing -> Unit
            else -> return HandoffImportOutcome.FAILED_IMPORT
        }
        val receipt = receipts.get(handoff.id) ?: return HandoffImportOutcome.FAILED_IMPORT
        if (receipt.settledAt != null) {
            return when (receipt.outcome) {
                HandoffImportOutcome.IMPORTED.name -> HandoffImportOutcome.IMPORTED
                HandoffImportOutcome.PARTIALLY_IMPORTED.name -> HandoffImportOutcome.PARTIALLY_IMPORTED
                else -> HandoffImportOutcome.FAILED_IMPORT
            }
        }
        val result = try {
            when (selection.destination) {
                ExternalHandoffDestination.CHAT -> when (handoff) {
                    is ExternalTextHandoff -> textCoordinator.handle(handoff, chatViewModel)
                    is ExternalDocumentHandoff -> documentCoordinator.handle(handoff, chatViewModel)
                }
                ExternalHandoffDestination.INBOX -> when (handoff) {
                    is ExternalTextHandoff -> saveTextToInbox(handoff)
                    is ExternalDocumentHandoff -> documentCoordinator.handleToInbox(handoff)
                }
                ExternalHandoffDestination.SAVED_KNOWLEDGE -> {
                    val text = handoff as? ExternalTextHandoff ?: return HandoffImportOutcome.FAILED_IMPORT
                    saveTextToKnowledge(text, null)
                }
                ExternalHandoffDestination.PROJECT -> when (handoff) {
                    is ExternalTextHandoff -> saveTextToKnowledge(handoff, selection.projectId)
                    is ExternalDocumentHandoff -> documentCoordinator.handleToProject(handoff, checkNotNull(selection.projectId))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            HandoffImportOutcome.FAILED_IMPORT
        }
        withContext(NonCancellable) { receipts.settle(handoff.id, result.receiptValue()) }
        return result
    }

    private suspend fun saveTextToInbox(handoff: ExternalTextHandoff): HandoffImportOutcome {
        val sharedUrl = ExternalHandoffDestinationPolicy.sharedUrl(handoff.text)
        // Never silently truncate externally supplied user text and report
        // success (C14): over-bound input is explicitly rejected.
        if (sharedUrl == null && ExternalHandoffTextBounds.isTooLargeForInbox(handoff.text)) {
            return HandoffImportOutcome.REJECTED_TOO_LARGE
        }
        val text = sharedUrl ?: handoff.text.take(InboxCapturePolicy.MAX_CONTENT_CHARS)
        val title = sharedUrl?.let(::hostTitle)
            ?: handoff.text.trim().lineSequence().firstOrNull().orEmpty().take(InboxCapturePolicy.MAX_TITLE_CHARS)
                .ifBlank { "공유한 텍스트" }
        val capture = InboxCapture(
            type = if (sharedUrl == null) InboxCaptureType.SHARED_TEXT else InboxCaptureType.URL,
            title = title,
            content = text,
            originalUri = sharedUrl,
            sourceHandoffEventId = handoff.id,
        )
        return when (InboxRepository(database).capture(capture, id = handoff.id)) {
            is InboxWriteResult.Stored -> HandoffImportOutcome.IMPORTED
            else -> HandoffImportOutcome.FAILED_IMPORT
        }
    }

    private suspend fun saveTextToKnowledge(handoff: ExternalTextHandoff, projectId: Long?): HandoffImportOutcome {
        // Never silently truncate externally supplied user text and report
        // success (C14): over-bound input is explicitly rejected.
        if (ExternalHandoffTextBounds.isTooLargeForKnowledge(handoff.text)) {
            return HandoffImportOutcome.REJECTED_TOO_LARGE
        }
        val content = handoff.text.take(SavedKnowledgePolicy.MAX_TEXT_CHARS).trim()
        val title = handoff.text.trim().lineSequence().firstOrNull().orEmpty()
            .take(SavedKnowledgePolicy.MAX_TITLE_CHARS).ifBlank { "공유한 텍스트" }
        if (content.isBlank() || SavedKnowledgePolicy.containsSensitiveMaterial("$title\n$content")) {
            return HandoffImportOutcome.FAILED_IMPORT
        }
        return try {
            database.withTransaction {
                if (projectId != null && database.projectDao().getProjectById(projectId) == null) {
                    return@withTransaction HandoffImportOutcome.FAILED_IMPORT
                }
                val existing = database.savedKnowledgeDao().getById(handoff.id)
                if (existing != null) {
                    return@withTransaction if (existing.scope == (if (projectId == null) "PERSONAL" else "PROJECT") &&
                        existing.projectId == projectId && existing.title == title && existing.savedText == content
                    ) HandoffImportOutcome.IMPORTED else HandoffImportOutcome.FAILED_IMPORT
                }
                database.savedKnowledgeDao().insert(
                    SavedKnowledgeEntity(
                        id = handoff.id,
                        scope = if (projectId == null) "PERSONAL" else "PROJECT",
                        projectId = projectId,
                        title = title,
                        savedText = content,
                        provenanceType = SavedKnowledgeProvenance.SHARE_CAPTURE.name,
                        sourceConversationId = null,
                        sourceMessageId = null,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                HandoffImportOutcome.IMPORTED
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            HandoffImportOutcome.FAILED_IMPORT
        }
    }

    private fun hostTitle(url: String): String = try {
        java.net.URI(url).host?.take(InboxCapturePolicy.MAX_TITLE_CHARS) ?: "공유한 링크"
    } catch (_: Exception) {
        "공유한 링크"
    }
}
