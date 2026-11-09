package com.projectnuke.fusion.handoff

import android.content.Context
import android.widget.Toast
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.PreparedExternalHandoff
import com.projectnuke.fusion.retrieval.LocalRagBounds
import com.projectnuke.fusion.util.AttachmentImportCoordinator
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.util.RemovedAttachmentReleaseResult
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Prepares an Android text share before any destructive New Chat operation.
 * Staged files remain coordinator-owned until ChatViewModel durably adopts the
 * complete prepared payload and records the event receipt.
 */
internal class TextHandoffCoordinator(
    attachmentRoot: File,
    private val coordinatorFactory: (text: String) -> AttachmentImportCoordinator,
    private val deletePendingFile: suspend (String?) -> Boolean,
    private val releaseRemovedDraftPath: suspend (String?) -> RemovedAttachmentReleaseResult,
    private val notify: (String) -> Unit,
) {
    private val importer = HandoffAttachmentImporter(
        attachmentRoot = attachmentRoot,
        coordinatorFactory = coordinatorFactory,
        deletePendingFile = deletePendingFile,
        notify = notify,
    )

    constructor(context: Context) : this(
        attachmentRoot = AttachmentStorageManager.getAttachmentDirectory(context),
        coordinatorFactory = { text ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            AttachmentImportCoordinator(
                attachmentRoot = AttachmentStorageManager.getAttachmentDirectory(context),
                inputFactory = { ByteArrayInputStream(bytes) },
            )
        },
        deletePendingFile = { path -> AttachmentStorageManager.deletePendingAttachmentFile(context, path) },
        releaseRemovedDraftPath = { path ->
            val database = com.projectnuke.fusion.data.AppDatabase.getInstance(context)
            AttachmentStorageManager.releaseRemovedDraftAttachment(
                context = context,
                dao = database.chatDao(),
                projectDao = database.projectDao(),
                path = path,
            )
        },
        notify = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() },
    )

    suspend fun handle(
        handoff: ExternalTextHandoff,
        chatViewModel: ChatViewModel,
    ): HandoffImportOutcome {
        // This check happens after hydration and before document/file work, so
        // a replay after process death never copies or replaces the draft.
        if (chatViewModel.isExternalHandoffAdopted(handoff.id)) {
            return HandoffImportOutcome.IMPORTED
        }

        val presentation = ExternalTextHandoffPresentationPolicy.classify(handoff.text)
        if (presentation == ExternalTextHandoffPresentation.REJECT_TOO_LARGE) {
            notify(
                "공유된 텍스트가 너무 길어요. " +
                    "${LocalRagBounds.MAX_DOC_CHARS}자 이내로 줄인 뒤 다시 공유해 주세요.",
            )
            return HandoffImportOutcome.REJECTED_TOO_LARGE
        }

        val prepared = when (presentation) {
            ExternalTextHandoffPresentation.INLINE_COMPOSER -> PreparedExternalHandoff(
                eventId = handoff.id,
                rawInput = handoff.text,
                pendingAttachments = emptyList(),
                generatedPaths = emptyList(),
            )
            ExternalTextHandoffPresentation.MANAGED_TEXT_ATTACHMENT ->
                importer.prepareTextHandoff(handoff) ?: return HandoffImportOutcome.FAILED_IMPORT
            ExternalTextHandoffPresentation.REJECT_TOO_LARGE ->
                return HandoffImportOutcome.REJECTED_TOO_LARGE
        }
        if (!prepared.isBounded()) {
            cleanupGenerated(prepared.generatedPaths)
            notify("공유된 텍스트를 가져오지 못했습니다.")
            return HandoffImportOutcome.FAILED_IMPORT
        }

        val adoption = try {
            chatViewModel.adoptPreparedExternalHandoff(prepared)
        } catch (cancellation: CancellationException) {
            cleanupGenerated(prepared.generatedPaths)
            throw cancellation
        } catch (_: RuntimeException) {
            // Bounded recoverable failure only: fatal Errors (LinkageError /
            // VirtualMachineError) propagate per the fatal-error policy, so a
            // broken runtime is never reported as FAILED_IMPORT with the
            // one-shot event consumed.
            cleanupGenerated(prepared.generatedPaths)
            notify("새 채팅에 공유된 텍스트를 반영하지 못했습니다.")
            return HandoffImportOutcome.FAILED_IMPORT
        }
        if (!adoption.success) {
            cleanupGenerated(prepared.generatedPaths)
            notify("새 채팅에 공유된 텍스트를 반영하지 못했습니다.")
            return HandoffImportOutcome.FAILED_IMPORT
        }

        // The new composer is durable. Old files are now post-commit cleanup;
        // a cleanup failure cannot change the imported result.
        cleanupOldPaths(
            oldPaths = adoption.oldPendingAttachmentPaths,
            adoptedPaths = prepared.pendingAttachments.map { it.localPath }.toSet(),
        )
        return HandoffImportOutcome.IMPORTED
    }

    private suspend fun cleanupGenerated(paths: Collection<String>) = withContext(NonCancellable) {
        paths.distinct().forEach { path ->
            val deleted = boundedHandoffOutcome { deletePendingFile(path) } ?: false
            if (deleted) AttachmentStorageManager.unregisterPendingAttachment(path)
        }
    }

    private suspend fun cleanupOldPaths(
        oldPaths: Collection<String>,
        adoptedPaths: Set<String>,
    ) = withContext(NonCancellable) {
        oldPaths.asSequence()
            .distinct()
            .filterNot { it in adoptedPaths }
            .forEach { path ->
                // The durable replacement committed, so these are removed
                // durable-draft references: they must settle through the
                // authority-aware release seam, never the destructive
                // pending-file delete. A conservative outcome (shared
                // reference, unreadable authority, failed delete) leaves the
                // physical file to normal attachment cleanup. Fatal Errors
                // from the release propagate (the adoption is durable and
                // its receipt keeps any replay idempotent).
                boundedHandoffOutcome { releaseRemovedDraftPath(path) }
            }
    }
}
