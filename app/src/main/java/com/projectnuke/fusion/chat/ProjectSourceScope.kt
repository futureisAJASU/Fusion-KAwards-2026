package com.projectnuke.fusion.chat

import com.projectnuke.fusion.retrieval.ProjectKnowledgeAttachmentPolicy
import com.projectnuke.fusion.util.AttachmentRecord
import java.io.File

/** Shared admission seam; Android supplies current ownership and bounded readability on IO. */
internal suspend fun validateProjectSources(
    scope: ProjectSourceScope,
    snapshot: ProjectContextSnapshot?,
    current: suspend () -> ProjectContextSnapshot?,
    readable: suspend (ProjectFileSnapshot) -> Boolean,
): Boolean {
    if (scope.kind == ProjectSourceScopeKind.AVAILABLE || scope.kind == ProjectSourceScopeKind.CURRENT_ATTACHMENTS) return true
    val owned = current() ?: return false
    if (snapshot == null || snapshot.projectId != scope.projectId || owned.projectId != scope.projectId) return false
    val required = if (scope.kind == ProjectSourceScopeKind.SELECTED_FILES) scope.fileIds else snapshot.files.map { it.id }
    if (required.isEmpty() || required.any { id ->
        val frozen = snapshot.files.firstOrNull { it.id == id }
        frozen == null || owned.files.none { it.id == id && it.localPath == frozen.localPath && it.mimeType == frozen.mimeType }
    }) return false
    val readableIds = snapshot?.files.orEmpty().filter { readable(it) }.map { it.id }.toSet()
    return ProjectSourceScopePolicy.valid(scope, snapshot, readableIds)
}

enum class ProjectSourceScopeKind { AVAILABLE, PROJECT, CURRENT_ATTACHMENTS, SELECTED_FILES }

data class ProjectSourceScope(
    val kind: ProjectSourceScopeKind = ProjectSourceScopeKind.AVAILABLE,
    val projectId: Long? = null,
    val fileIds: List<Long> = emptyList(),
) {
    fun frozen(): ProjectSourceScope = copy(fileIds = fileIds.distinct().toList())
    val label: String get() = when (kind) {
        ProjectSourceScopeKind.AVAILABLE -> "전체 자료"
        ProjectSourceScopeKind.PROJECT -> "프로젝트 전체"
        ProjectSourceScopeKind.CURRENT_ATTACHMENTS -> "현재 첨부"
        ProjectSourceScopeKind.SELECTED_FILES -> "선택한 파일 ${fileIds.size}개"
    }
}

object ProjectSourceScopePolicy {
    fun currentImagePaths(scope: ProjectSourceScope, paths: List<String>): List<String> = when (scope.kind) {
        ProjectSourceScopeKind.PROJECT, ProjectSourceScopeKind.SELECTED_FILES -> emptyList()
        ProjectSourceScopeKind.AVAILABLE, ProjectSourceScopeKind.CURRENT_ATTACHMENTS -> paths.toList()
    }

    fun mayProceed(scope: ProjectSourceScope, sourceCount: Int, prepared: Boolean): Boolean = when (scope.kind) {
        ProjectSourceScopeKind.PROJECT, ProjectSourceScopeKind.SELECTED_FILES -> sourceCount > 0 && prepared
        ProjectSourceScopeKind.CURRENT_ATTACHMENTS -> sourceCount == 0 || prepared
        ProjectSourceScopeKind.AVAILABLE -> true
    }

    fun valid(scope: ProjectSourceScope, project: ProjectContextSnapshot?, readableIds: Set<Long>): Boolean = when (scope.kind) {
        ProjectSourceScopeKind.AVAILABLE, ProjectSourceScopeKind.CURRENT_ATTACHMENTS -> true
        ProjectSourceScopeKind.PROJECT -> project != null && project.projectId == scope.projectId && project.files.any { it.id in readableIds }
        ProjectSourceScopeKind.SELECTED_FILES -> project != null && project.projectId == scope.projectId &&
            scope.fileIds.isNotEmpty() && scope.fileIds.size <= 8 && scope.fileIds.distinct().size == scope.fileIds.size &&
            scope.fileIds.all { id -> id in readableIds && project.files.any { it.id == id } }
    }

    fun resolve(
        scope: ProjectSourceScope,
        project: ProjectContextSnapshot?,
        current: List<AttachmentRecord>,
        historical: List<String>,
        attachmentRoot: File,
    ): List<AttachmentRecord> {
        val projectOnly = scope.kind == ProjectSourceScopeKind.PROJECT || scope.kind == ProjectSourceScopeKind.SELECTED_FILES
        if (projectOnly) require(project != null && project.projectId == scope.projectId) { "선택한 프로젝트를 찾을 수 없습니다." }
        val files = when (scope.kind) {
            ProjectSourceScopeKind.CURRENT_ATTACHMENTS -> emptyList()
            ProjectSourceScopeKind.SELECTED_FILES -> {
                require(scope.fileIds.isNotEmpty() && scope.fileIds.all { id -> project!!.files.any { it.id == id } }) {
                    "선택한 파일이 삭제되었습니다. 자료 범위를 다시 선택해 주세요."
                }
                project!!.copy(files = project.files.filter { it.id in scope.fileIds }).attachmentRecords
            }
            else -> project?.attachmentRecords.orEmpty()
        }
        val resolved = (ProjectKnowledgeAttachmentPolicy.resolve(
            currentAttachments = if (projectOnly) emptyList() else current,
            projectFiles = files,
            historicalMessages = if (scope.kind == ProjectSourceScopeKind.AVAILABLE) historical else emptyList(),
            attachmentRoot = attachmentRoot,
        ) + files.filter { record ->
            if (!com.projectnuke.fusion.retrieval.LocalDocumentKindPolicy.isOcrImage(record.mimeType, record.name)) false
            else com.projectnuke.fusion.retrieval.DocumentIndexStore(File(attachmentRoot.parentFile, "document_index"))
                .readStatus(record.localPath, File(record.localPath))?.kind == com.projectnuke.fusion.retrieval.LocalDocumentKind.OCR_IMAGE
        }).distinctBy { it.localPath }.take(8)
        if (scope.kind == ProjectSourceScopeKind.SELECTED_FILES) {
            require(files.all { file -> resolved.any { File(it.localPath).canonicalPath == File(file.localPath).canonicalPath } }) {
                "선택한 파일을 읽을 수 없습니다. 자료 범위를 다시 선택해 주세요."
            }
        }
        if (scope.kind == ProjectSourceScopeKind.PROJECT) require(resolved.isNotEmpty()) { "프로젝트 자료를 읽을 수 없습니다." }
        return resolved
    }
}
