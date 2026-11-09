package com.projectnuke.fusion.ui

import com.projectnuke.fusion.util.AttachmentClassification
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.retrieval.DocumentExtractionStatus
import com.projectnuke.fusion.retrieval.DocumentStatusPolicy
import com.projectnuke.fusion.retrieval.LocalDocumentKindPolicy
import com.projectnuke.fusion.retrieval.LocalRagBounds
import com.projectnuke.fusion.retrieval.LocalRagInputPolicy
import com.projectnuke.fusion.retrieval.ProjectFileAdmission
import java.io.File

data class ProjectFileAdmissionResult(
    val status: ProjectFileAdmission,
    val trustedFile: File? = null,
    // Typed Document Intelligence status when a PDF admission ran.
    val documentStatus: DocumentExtractionStatus? = null,
)

object ProjectFilePolicy {
    const val MAX_PROJECT_FILES = com.projectnuke.fusion.data.ProjectFileContract.MAX_PROJECT_FILES

    fun canAdd(currentCount: Int): Boolean = currentCount in 0 until MAX_PROJECT_FILES

    fun unsupportedMessage(): String = "이 형식은 프로젝트 자료로 읽을 수 없습니다. txt, md, json, csv, pdf 파일을 사용해 주세요."

    fun capacityMessage(): String = "프로젝트 자료는 최대 ${MAX_PROJECT_FILES}개까지 추가할 수 있어요."

    fun tooLargeMessage(): String = "이 파일은 현재 로컬 문서 한도를 넘어 프로젝트 자료로 추가할 수 없어요."

    fun emptyMessage(): String = "빈 파일은 프로젝트 자료로 추가할 수 없어요."

    fun unreadableMessage(): String = "파일을 읽을 수 없어 프로젝트 자료로 추가하지 못했습니다."

    fun isSupported(mimeType: String?, displayName: String): Boolean =
        LocalRagInputPolicy.isSupported(mimeType, displayName) || LocalDocumentKindPolicy.isOcrImage(mimeType, displayName)

    fun admitWholeFile(file: File): ProjectFileAdmission =
        LocalRagInputPolicy.admitWholeFile(file)

    /**
     * One shared trusted-managed-file + supported-type + whole-file seam.
     *
     * Plain text keeps the existing whole-file 32,000-char admission.
     * PDFs follow Document Intelligence bounds: [pdfStatus] (resolved by the
     * caller through the index/extraction seam) must be READY to accept;
     * every typed status rejects truthfully. null pdfStatus never accepts a
     * PDF (no unsafe fallback claim).
     */
    fun admitManagedFile(
        attachmentRoot: File,
        mimeType: String?,
        displayName: String,
        localPath: String,
        pdfStatus: DocumentExtractionStatus? = null,
    ): ProjectFileAdmissionResult {
        if (!isSupported(mimeType, displayName)) {
            return ProjectFileAdmissionResult(ProjectFileAdmission.UNSUPPORTED)
        }
        return when (val classification = AttachmentStorageManager.classifyAttachment(attachmentRoot, localPath)) {
            is AttachmentClassification.Trusted -> {
                val trusted = classification.file
                if (LocalDocumentKindPolicy.isOcrImage(mimeType, displayName)) {
                    return ProjectFileAdmissionResult(
                        if (trusted.length() in 1..com.projectnuke.fusion.retrieval.PdfDocumentBounds.MAX_PDF_FILE_BYTES) ProjectFileAdmission.ACCEPTED
                        else ProjectFileAdmission.TOO_LARGE, trustedFile = trusted)
                }
                if (LocalDocumentKindPolicy.isPdf(mimeType, displayName)) {
                    return when (pdfStatus) {
                        DocumentExtractionStatus.IMAGE_ONLY_NO_OCR, DocumentExtractionStatus.NO_TEXT_LAYER -> ProjectFileAdmissionResult(
                            ProjectFileAdmission.ACCEPTED, trustedFile = trusted, documentStatus = pdfStatus)
                        DocumentExtractionStatus.READY -> ProjectFileAdmissionResult(
                            status = ProjectFileAdmission.ACCEPTED,
                            trustedFile = trusted,
                            documentStatus = pdfStatus,
                        )
                        else -> ProjectFileAdmissionResult(
                            status = when (pdfStatus) {
                                DocumentExtractionStatus.TOO_MANY_PAGES,
                                DocumentExtractionStatus.TOO_MUCH_TEXT,
                                DocumentExtractionStatus.TOO_LARGE_FILE -> ProjectFileAdmission.TOO_LARGE
                                else -> ProjectFileAdmission.UNREADABLE
                            },
                            documentStatus = pdfStatus,
                        )
                    }
                }
                val status = admitWholeFile(trusted)
                ProjectFileAdmissionResult(
                    status = status,
                    trustedFile = trusted.takeIf { status == ProjectFileAdmission.ACCEPTED },
                )
            }
            else -> ProjectFileAdmissionResult(ProjectFileAdmission.UNREADABLE)
        }
    }

    fun admissionMessage(result: ProjectFileAdmissionResult): String {
        result.documentStatus?.let { status ->
            if (status != DocumentExtractionStatus.READY) {
                return DocumentStatusPolicy.statusMessage(status)
            }
        }
        return admissionMessage(result.status)
    }

    fun admissionMessage(status: ProjectFileAdmission): String = when (status) {
        ProjectFileAdmission.ACCEPTED -> ""
        ProjectFileAdmission.EMPTY -> emptyMessage()
        ProjectFileAdmission.TOO_LARGE -> tooLargeMessage()
        ProjectFileAdmission.UNREADABLE -> unreadableMessage()
        ProjectFileAdmission.UNSUPPORTED -> unsupportedMessage()
    }

    fun isReadableBounded(file: File): Boolean =
        admitWholeFile(file) == ProjectFileAdmission.ACCEPTED
}
