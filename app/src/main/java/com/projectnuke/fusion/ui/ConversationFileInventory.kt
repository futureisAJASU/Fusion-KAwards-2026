package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.PendingAttachmentIdentity
import com.projectnuke.fusion.util.AttachmentClassification
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Trusted local-file inventory for the ACTIVE conversation ("업로드한
 * 파일"). NOT a filesystem browser: entries come only from active
 * conversation message contents plus current durable composer attachments,
 * parsed with the existing trusted codec (no new tag regex).
 *
 * Deduplicated by canonical managed path; suspicious records never appear
 * as valid files; missing files are represented truthfully as UNAVAILABLE.
 */
internal enum class InventoryAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

internal data class InventoryFile(
    val name: String,
    val mimeType: String,
    val localPath: String,
    val availability: InventoryAvailability,
    val inDraft: Boolean,
)

internal object ConversationFileInventory {
    fun collect(
        historyContents: List<String>,
        draftAttachments: List<AttachmentRecord>,
        attachmentRoot: File,
    ): List<InventoryFile> {
        val byPath = LinkedHashMap<String, InventoryFile>()
        for (content in historyContents) {
            val parsed = AttachmentMessageCodec.parseTrustedAttachmentMessage(content, attachmentRoot)
            if (parsed.suspiciousEnvelope) continue
            for (record in parsed.records) {
                byPath.getOrPut(record.localPath) {
                    InventoryFile(
                        name = record.name,
                        mimeType = record.mimeType,
                        localPath = record.localPath,
                        availability = InventoryAvailability.AVAILABLE,
                        inDraft = false,
                    )
                }
            }
            for (record in parsed.unavailableRecords) {
                byPath.getOrPut(record.localPath) {
                    InventoryFile(
                        name = record.name,
                        mimeType = record.mimeType,
                        localPath = record.localPath,
                        availability = InventoryAvailability.UNAVAILABLE,
                        inDraft = false,
                    )
                }
            }
        }
        for (attachment in draftAttachments) {
            val classification =
                AttachmentStorageManager.classifyAttachment(attachmentRoot, attachment.localPath)
            val key = when (classification) {
                is AttachmentClassification.Trusted -> classification.file.canonicalPath
                is AttachmentClassification.Unavailable -> classification.canonicalPath
                is AttachmentClassification.Suspicious -> continue
            }
            val existing = byPath[key]
            if (existing != null) {
                byPath[key] = existing.copy(inDraft = true)
            } else {
                byPath[key] = InventoryFile(
                    name = attachment.name,
                    mimeType = attachment.mimeType,
                    localPath = key,
                    availability = if (classification is AttachmentClassification.Trusted) {
                        InventoryAvailability.AVAILABLE
                    } else {
                        InventoryAvailability.UNAVAILABLE
                    },
                    inDraft = true,
                )
            }
        }
        return byPath.values.toList()
    }
}

internal enum class ReattachDecision {
    OK,
    ALREADY_ATTACHED,
    LIMIT_REACHED,
    BLOCKED_EXTERNAL,
    UNAVAILABLE_FILE,
}

/**
 * Pure reattach gate for "다시 첨부": the SAME managed file joins the
 * current composer (no physical duplication) through normal durable
 * attachment identity semantics.
 */
internal object InventoryReattachPolicy {
    const val MAX_COMPOSER_ATTACHMENTS = 5

    fun decide(
        file: InventoryFile,
        draftPaths: Set<String>,
        maxTotal: Int = MAX_COMPOSER_ATTACHMENTS,
        externalAttachmentsBlocked: Boolean = false,
    ): ReattachDecision = when {
        file.availability != InventoryAvailability.AVAILABLE -> ReattachDecision.UNAVAILABLE_FILE
        externalAttachmentsBlocked -> ReattachDecision.BLOCKED_EXTERNAL
        draftPaths.contains(file.localPath) -> ReattachDecision.ALREADY_ATTACHED
        draftPaths.size >= maxTotal -> ReattachDecision.LIMIT_REACHED
        else -> ReattachDecision.OK
    }
}

internal enum class InventoryReattachOutcome {
    ATTACHED,
    ALREADY_ATTACHED,
    LIMIT_REACHED,
    BLOCKED_EXTERNAL,
    UNAVAILABLE_FILE,
    BUSY,
    FAILED,
}

/**
 * Inventory "다시 첨부": the SAME managed file joins [conversationId]'s
 * composer through normal durable attachment ownership. Nothing is copied
 * and no physical file is ever deleted here — the existing managed file is
 * shared by reference; cleanup of adopted attachments stays with the normal
 * attachment lifecycle.
 *
 * Ownership safety mirrors [com.projectnuke.fusion.handoff.HandoffAttachmentImporter]
 * and `DocumentHandoffCoordinator`: once
 * [ChatViewModel.tryBeginAttachmentImport] succeeds, the import owner (and
 * its `ManualCompactionOperationRegistry` lease) is settled on every
 * non-adopted path — including coroutine cancellation — under
 * [NonCancellable], and cancellation is rethrown so the caller keeps the
 * event retryable.
 */
internal suspend fun performInventoryReattach(
    conversationId: Long,
    file: InventoryFile,
    draftPaths: Set<String>,
    externalAttachmentsBlocked: Boolean,
    chatViewModel: ChatViewModel,
): InventoryReattachOutcome {
    when (InventoryReattachPolicy.decide(file, draftPaths, externalAttachmentsBlocked = externalAttachmentsBlocked)) {
        ReattachDecision.OK -> Unit
        ReattachDecision.ALREADY_ATTACHED -> return InventoryReattachOutcome.ALREADY_ATTACHED
        ReattachDecision.LIMIT_REACHED -> return InventoryReattachOutcome.LIMIT_REACHED
        ReattachDecision.BLOCKED_EXTERNAL -> return InventoryReattachOutcome.BLOCKED_EXTERNAL
        ReattachDecision.UNAVAILABLE_FILE -> return InventoryReattachOutcome.UNAVAILABLE_FILE
    }
    val owner = when (val admission = chatViewModel.tryBeginAttachmentImport(conversationId)) {
        is com.projectnuke.fusion.chat.AttachmentImportAdmission.Accepted -> admission.owner
        is com.projectnuke.fusion.chat.AttachmentImportAdmission.Rejected ->
            return InventoryReattachOutcome.BUSY
    }
    var adopted = false
    try {
        if (!chatViewModel.beginAttachmentCopy(conversationId, owner.token)) {
            return InventoryReattachOutcome.FAILED
        }
        adopted = chatViewModel.completeAttachmentImport(
            conversationId = conversationId,
            token = owner.token,
            attachments = listOf(
                PendingAttachmentIdentity(
                    name = file.name,
                    mimeType = file.mimeType,
                    localPath = file.localPath,
                ),
            ),
        )
        return if (adopted) InventoryReattachOutcome.ATTACHED else InventoryReattachOutcome.FAILED
    } finally {
        if (!adopted) {
            withContext(NonCancellable) {
                // Idempotent: no-ops when the successful adoption already
                // cleared the owner and released the registry lease. Never
                // deletes the shared managed file.
                chatViewModel.settleAttachmentImportAfterCallback(conversationId, owner.token)
            }
        }
    }
}
