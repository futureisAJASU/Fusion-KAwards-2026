package com.projectnuke.fusion.retrieval

import com.projectnuke.fusion.util.AttachmentClassification
import com.projectnuke.fusion.util.AttachmentRecord
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.io.File

/**
 * Composes the existing bounded attachment continuity policy with explicit
 * project files. Priority is current request, project files, then historical
 * conversation attachments. Trust/support checks remain owned by the
 * existing attachment and LocalRagInputPolicy implementations.
 */
object ProjectKnowledgeAttachmentPolicy {
    fun resolve(
        currentAttachments: List<AttachmentRecord>,
        projectFiles: List<AttachmentRecord>,
        historicalMessages: List<String>,
        attachmentRoot: File,
        maxDocs: Int = LocalRagBounds.MAX_DOCS_PER_REQUEST,
    ): List<AttachmentRecord> {
        if (maxDocs <= 0) return emptyList()
        val trustedProjectFiles = projectFiles.mapNotNull { record ->
            if (!LocalRagInputPolicy.isSupported(record.mimeType, record.name)) return@mapNotNull null
            trusted(record, attachmentRoot)?.let { r ->
                usablePhysicalFile(r, attachmentRoot)?.let { file ->
                    r.copy(localPath = file.canonicalPath)
                }
            }
        }
        val trustedCurrent = currentAttachments.mapNotNull { record ->
            if (!LocalRagInputPolicy.isSupported(record.mimeType, record.name)) return@mapNotNull null
            trusted(record, attachmentRoot)?.let { r ->
                r.copy(localPath = r.localPath)
            }
        }
        return ConversationAttachmentContinuity.collectEffectiveRagAttachments(
            currentAttachments = trustedCurrent + trustedProjectFiles,
            historicalMessages = historicalMessages,
            attachmentRoot = attachmentRoot,
            maxDocs = maxDocs,
        )
    }

    private fun trusted(record: AttachmentRecord, attachmentRoot: File): AttachmentRecord? =
        when (val classification = AttachmentStorageManager.classifyAttachment(attachmentRoot, record.localPath)) {
            is AttachmentClassification.Trusted -> record.copy(localPath = classification.file.canonicalPath)
            else -> null
        }

    /**
     * Physical usability gate: PDF project files obey the Document
     * Intelligence PDF bounds (verified at admission and fail-closed at
     * retrieval); plain text keeps the existing whole-file 32,000-char
     * admission contract. A physically present PDF is NOT rejected here by
     * the text-only size rule.
     */
    private fun usablePhysicalFile(record: AttachmentRecord, attachmentRoot: File): File? {
        val file = AttachmentStorageManager.resolveManagedAttachment(attachmentRoot, record.localPath)
            ?: return null
        val usable = when {
            LocalDocumentKindPolicy.isPdf(record.mimeType, record.name) -> true
            else -> LocalRagInputPolicy.admitWholeFile(file) == ProjectFileAdmission.ACCEPTED
        }
        return file.takeIf { usable }
    }
}
