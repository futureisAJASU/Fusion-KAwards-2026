package com.projectnuke.fusion.ui

import android.content.Context
import com.projectnuke.fusion.chat.*
import com.projectnuke.fusion.documents.DocumentHighlightPolicy
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.retrieval.*
import com.projectnuke.fusion.util.AttachmentStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal data class ProjectKnowledgeStatus(val label: String, val eligible: Boolean, val pages: Int? = null, val needsOcr: Boolean = false)
internal data class DocumentPagePassage(
    val pageNumber: Int?,
    val sourceChunkId: String?,
    val sourceContentHashHex: String?,
    val text: String,
)

internal class ProjectKnowledgeController(private val context: Context, private val database: AppDatabase? = null) {
    suspend fun status(file: ProjectFileEntity): ProjectKnowledgeStatus = withContext(Dispatchers.IO) {
        val managed = AttachmentStorageManager.resolveManagedAttachment(context, file.localPath)
            ?: return@withContext ProjectKnowledgeStatus("파일을 읽을 수 없음", false)
        if (LocalDocumentKindPolicy.isPdf(file.mimeType, file.displayName) || LocalDocumentKindPolicy.isOcrImage(file.mimeType, file.displayName)) {
            val status = DocumentIntelligenceProvider.indexRepository(context).status(managed.canonicalPath, file.mimeType)
            val readable = status != null && status.totalExtractedChars > 0 && status.textPageCount > 0
            ProjectKnowledgeStatus(if (readable) {
                if (status!!.kind == LocalDocumentKind.OCR_IMAGE) "이미지 · OCR · 검색 가능"
                else "PDF · ${status.pageCount}쪽 · 검색 가능" + if (status.hasOcrText) " · OCR" else ""
            } else "텍스트를 읽을 수 없음", readable, status?.pageCount, status == null || status.textPageCount < status.pageCount)
        } else {
            val admitted = ProjectFilePolicy.admitManagedFile(AttachmentStorageManager.getAttachmentDirectory(context),
                file.mimeType, file.displayName, file.localPath).status == ProjectFileAdmission.ACCEPTED
            ProjectKnowledgeStatus(if (admitted) "텍스트 · 검색 가능" else "텍스트를 읽을 수 없음", admitted)
        }
    }

    suspend fun valid(scope: ProjectSourceScope, snapshot: ProjectContextSnapshot?): Boolean = withContext(Dispatchers.IO) {
        validateProjectSources(scope, snapshot, current = {
            snapshot?.let {
                (database ?: AppDatabase.getInstance(context)).projectDao().getProjectContextForConversation(0L, it.projectId)
                    ?.let { owned -> ProjectContextSnapshot(owned.project.id, owned.project.instructions,
                        owned.files.map { file -> ProjectFileSnapshot(file.id, file.displayName, file.mimeType, file.localPath) }) }
            }
        }, readable = { file ->
            coroutineContext.ensureActive()
            status(ProjectFileEntity(id = file.id, projectId = snapshot!!.projectId, displayName = file.displayName,
                mimeType = file.mimeType, localPath = file.localPath, addedAt = 0)).eligible
        })
    }

    suspend fun inspect(file: ProjectFileEntity, reindex: Boolean = false): String = withContext(Dispatchers.IO) {
        val source = AttachmentStorageManager.resolveManagedAttachment(context, file.localPath)
            ?: return@withContext "파일을 읽을 수 없습니다."
        val document = if (LocalDocumentKindPolicy.isPdf(file.mimeType, file.displayName) || LocalDocumentKindPolicy.isOcrImage(file.mimeType, file.displayName)) {
            val repository = DocumentIntelligenceProvider.indexRepository(context)
            if (reindex) repository.deleteIndex(source.canonicalPath)
            (repository.obtain(source.canonicalPath, file.mimeType) as? DocumentIndexResult.Ready)?.index?.toExtractedLocalDocument()
        } else (DocumentIntelligenceProvider.extractorRegistry(context)
            .extract(source.canonicalPath, file.mimeType, file.displayName) as? DocumentExtractionResult.Ready)?.document
        document?.pages?.filter { it.text.isNotBlank() }?.take(3)?.joinToString("\n\n") {
            (if (document.isPdf) "p.${it.pageNumber}\n" else "") + it.text.take(1200)
        }?.takeIf { it.isNotBlank() } ?: "텍스트를 읽을 수 없습니다."
    }

    suspend fun inspectPassages(file: ProjectFileEntity, reindex: Boolean = false): List<DocumentPagePassage> = withContext(Dispatchers.IO) {
        val source = AttachmentStorageManager.resolveManagedAttachment(context, file.localPath)
            ?: return@withContext emptyList()
        val document = if (LocalDocumentKindPolicy.isPdf(file.mimeType, file.displayName) ||
            LocalDocumentKindPolicy.isOcrImage(file.mimeType, file.displayName)
        ) {
            val repository = DocumentIntelligenceProvider.indexRepository(context)
            if (reindex) repository.deleteIndex(source.canonicalPath)
            (repository.obtain(source.canonicalPath, file.mimeType) as? DocumentIndexResult.Ready)?.index?.toExtractedLocalDocument()
        } else {
            (DocumentIntelligenceProvider.extractorRegistry(context).extract(source.canonicalPath, file.mimeType, file.displayName)
                as? DocumentExtractionResult.Ready)?.document
        } ?: return@withContext emptyList()
        document.pages.asSequence().filter { it.text.isNotBlank() }.take(3).map { page ->
            val isPdf = document.kind == LocalDocumentKind.PDF_TEXT_LAYER
            DocumentPagePassage(
                pageNumber = page.pageNumber.takeIf { isPdf },
                sourceChunkId = if (isPdf) "page:${page.pageNumber}" else null,
                sourceContentHashHex = page.contentHashHex.takeIf { it.length == 64 },
                text = page.text.take(DocumentHighlightPolicy.MAX_QUOTE_CHARS),
            )
        }.toList()
    }

    suspend fun inspectSectionOutline(file: ProjectFileEntity): DocumentSectionOutline? = withContext(Dispatchers.IO) {
        val source = AttachmentStorageManager.resolveManagedAttachment(context, file.localPath)
            ?: return@withContext null
        val document = if (LocalDocumentKindPolicy.isPdf(file.mimeType, file.displayName) ||
            LocalDocumentKindPolicy.isOcrImage(file.mimeType, file.displayName)
        ) {
            (DocumentIntelligenceProvider.indexRepository(context).obtain(source.canonicalPath, file.mimeType)
                as? DocumentIndexResult.Ready)?.index?.toExtractedLocalDocument()
        } else {
            (DocumentIntelligenceProvider.extractorRegistry(context).extract(source.canonicalPath, file.mimeType, file.displayName)
                as? DocumentExtractionResult.Ready)?.document
        } ?: return@withContext null
        val isPdf = document.kind == LocalDocumentKind.PDF_TEXT_LAYER
        DocumentSectionPolicy.detect(
            document.pages.asSequence()
                .filter { it.text.isNotBlank() }
                .take(DocumentSectionPolicy.MAX_SOURCE_PAGES + 1)
                .map { page ->
                    DocumentSectionPageInput(
                        pageNumber = page.pageNumber.takeIf { isPdf },
                        sourceChunkId = if (isPdf) "page:${page.pageNumber}" else null,
                        sourceContentHashHex = page.contentHashHex.takeIf { it.length == 64 },
                        text = page.text,
                    )
                }
                .toList(),
        )
    }

    suspend fun recognize(file: ProjectFileEntity, progress: (Int, Int) -> Unit): String = kotlinx.coroutines.withTimeout(120_000) {
        withContext(Dispatchers.IO) {
            val source = AttachmentStorageManager.resolveManagedAttachment(context, file.localPath)
                ?: return@withContext "파일을 읽을 수 없습니다."
            when (val result = DocumentIntelligenceProvider.indexRepository(context).recognizeText(source.canonicalPath, file.mimeType, progress)) {
                is LocalOcrResult.Complete -> if (result.pages.any { it.ocrDerived }) "텍스트 인식 완료 · OCR" else "텍스트 사용 가능"
                is LocalOcrResult.Partial -> "일부 페이지를 인식하지 못했습니다. ${result.completed}개 처리 · 저장하지 않음"
                LocalOcrResult.TooManyPages -> "텍스트 인식은 최대 32개 페이지까지 지원합니다."
                LocalOcrResult.TooMuchText -> "인식한 텍스트가 문서 처리 한도를 초과했습니다."
                LocalOcrResult.NoText -> "인식한 텍스트가 없습니다."
            }
        }
    }
}
