package com.projectnuke.fusion.retrieval

import android.content.Context
import com.projectnuke.fusion.ui.ProjectFileAdmissionResult
import com.projectnuke.fusion.ui.ProjectFilePolicy
import com.projectnuke.fusion.util.AttachmentClassification
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.io.File

/**
 * ONE authoritative project-knowledge admission seam (section 11).
 *
 * Ordinary text/md/json/csv project files keep the STRICT whole-file
 * 32,000-char contract (current truth). PDFs use the PDF extractor-derived
 * policy: a READY extraction/index is required, every typed failure rejects
 * truthfully, and PDF bounds (pages/chars/file size) are separate from the
 * text-file limit because PDF text is DERIVED INDEXED content, never raw
 * bytes.
 *
 * The project-file picker import and the conversation-file -> project
 * promotion BOTH go through this seam, so they can never disagree about
 * whether the same managed PDF is usable.
 */
object ProjectKnowledgeAdmissionPolicy {

    /**
     * Core seam. [pdfStateResolver] supplies the typed extraction status for
     * PDFs (production resolves through the derived index; host tests inject
     * fakes). Returns the same [ProjectFileAdmissionResult] the promotion
     * flow consumes.
     */
    suspend fun admitManagedFile(
        attachmentRoot: File,
        mimeType: String?,
        displayName: String,
        localPath: String,
        pdfStateResolver: suspend (File) -> DocumentExtractionStatus,
    ): ProjectFileAdmissionResult {
        if (!ProjectFilePolicy.isSupported(mimeType, displayName)) {
            return ProjectFileAdmissionResult(ProjectFileAdmission.UNSUPPORTED)
        }
        val trusted = when (val classification = AttachmentStorageManager.classifyAttachment(attachmentRoot, localPath)) {
            is AttachmentClassification.Trusted -> classification.file
            else -> return ProjectFileAdmissionResult(ProjectFileAdmission.UNREADABLE)
        }
        val pdfStatus = if (LocalDocumentKindPolicy.isPdf(mimeType, displayName)) {
            pdfStateResolver(trusted)
        } else {
            null
        }
        return ProjectFilePolicy.admitManagedFile(
            attachmentRoot = attachmentRoot,
            mimeType = mimeType,
            displayName = displayName,
            localPath = localPath,
            pdfStatus = pdfStatus,
        )
    }

    /**
     * Production convenience seam: resolves PDF state through the derived
     * index (building it exactly once at admission), which also guarantees
     * the document index exists before authoritative promotion.
     */
    suspend fun admitManagedPdfViaIndex(
        context: Context,
        attachmentRoot: File,
        mimeType: String?,
        displayName: String,
        localPath: String,
    ): ProjectFileAdmissionResult {
        val repository = DocumentIntelligenceProvider.indexRepository(context)
        return admitManagedFile(
            attachmentRoot = attachmentRoot,
            mimeType = mimeType,
            displayName = displayName,
            localPath = localPath,
            pdfStateResolver = { trusted ->
                when (val result = repository.obtain(trusted.canonicalPath, mimeType)) {
                    is DocumentIndexResult.Ready -> DocumentExtractionStatus.READY
                    is DocumentIndexResult.NotReady -> result.status
                }
            },
        )
    }
}