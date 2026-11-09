package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.ui.FrozenSearchRetry
import com.projectnuke.fusion.ui.ResponseVersionState
import com.projectnuke.fusion.ui.buildFrozenSearchRetryCapture
import com.projectnuke.fusion.util.AttachmentRecord
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ProjectSourceScopeTest {
    private fun project(root: java.io.File): ProjectContextSnapshot = ProjectContextSnapshot(7, "", (1..3).map {
        val file = java.io.File(root, "$it.txt").apply { writeText("source $it") }
        ProjectFileSnapshot(it.toLong(), file.name, "text/plain", file.canonicalPath)
    })
    private fun scope(vararg ids: Long) = ProjectSourceScope(ProjectSourceScopeKind.SELECTED_FILES, 7, ids.toList())
    @Test fun projectWideUsesOnlyProjectFiles() {
        val root = Files.createTempDirectory("fusion-scope").toFile()
        try {
            val context = project(root)
            val current = java.io.File(root, "current.txt").apply { writeText("current") }
            val rows = ProjectSourceScopePolicy.resolve(ProjectSourceScope(ProjectSourceScopeKind.PROJECT, 7), context,
                listOf(AttachmentRecord("current.txt", "text/plain", current.canonicalPath)), emptyList(), root)
            assertEquals(context.attachmentRecords, rows)
        } finally { root.deleteRecursively() }
    }
    @Test fun singleAndMultipleSelectedFilesConstrainActualInputs() {
        val root = Files.createTempDirectory("fusion-scope").toFile()
        try {
            val context = project(root)
            assertEquals(listOf("2.txt"), ProjectSourceScopePolicy.resolve(scope(2), context, emptyList(), emptyList(), root).map { it.name })
            assertEquals(listOf("1.txt", "3.txt"), ProjectSourceScopePolicy.resolve(scope(1,3), context, emptyList(), emptyList(), root).map { it.name })
        } finally { root.deleteRecursively() }
    }
    @Test fun currentAttachmentsCannotBorrowProjectOrBranchHistory() {
        val root = Files.createTempDirectory("fusion-scope").toFile()
        try {
            assertTrue(ProjectSourceScopePolicy.resolve(ProjectSourceScope(ProjectSourceScopeKind.CURRENT_ATTACHMENTS), project(root),
                emptyList(), emptyList(), root).isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun deletedUnreadableAndChangedProjectFailClosed() {
        val p = ProjectContextSnapshot(7, "", listOf(ProjectFileSnapshot(1, "one", "text/plain", "/one")))
        assertFalse(ProjectSourceScopePolicy.valid(scope(2), p, setOf(1)))
        assertFalse(ProjectSourceScopePolicy.valid(scope(1), p, emptySet()))
        assertFalse(ProjectSourceScopePolicy.valid(scope(1), p.copy(projectId = 8), setOf(1)))
        assertFalse(ProjectSourceScopePolicy.valid(scope(1), null, setOf(1)))
    }
    @Test fun physicalDeletionAfterFreezeCannotExpandToOtherFiles() {
        val root = Files.createTempDirectory("fusion-scope").toFile()
        try {
            val context = project(root)
            java.io.File(context.files[0].localPath).delete()
            try { ProjectSourceScopePolicy.resolve(scope(1), context, context.attachmentRecords.drop(1), emptyList(), root); fail() }
            catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }
    @Test fun requestSnapshotCopiesMutableSelection() {
        val ids = mutableListOf(1L, 2L)
        val captured = ProjectSourceScope(ProjectSourceScopeKind.SELECTED_FILES, 7, ids).frozen()
        ids.clear()
        assertEquals(listOf(1L, 2L), captured.fileIds)
    }
    @Test fun selectedBoundsAndEmptyScopeAreRejected() {
        val p = ProjectContextSnapshot(7, "", (1..9).map { ProjectFileSnapshot(it.toLong(), "x", "text/plain", "/$it") })
        assertFalse(ProjectSourceScopePolicy.valid(scope(), p, (1L..9L).toSet()))
        assertFalse(ProjectSourceScopePolicy.valid(scope(*(1L..9L).toList().toLongArray()), p, (1L..9L).toSet()))
    }
    @Test fun frozenSearchRetryPreservesScopeAndProjectProvenance() {
        val p = ProjectContextSnapshot(7, "", emptyList())
        val request = GenerationRequestSnapshot(requestId = "request", conversationId = 2, generationModeKey = "LOCAL_MODEL",
            selectedModelId = "local", selectedModelPath = null, settings = GenerationSettings(), reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED, attachmentIds = emptyList(), multimodalImagePaths = emptyList(),
            promptText = "question", rawUserText = "question", createdAt = 0, sourceScope = scope(1), projectContext = p)
        val capture = buildFrozenSearchRetryCapture(FrozenSearchRetry.from(request, 3), 3, ResponseVersionState())
        assertEquals(request.sourceScope, capture.sourceScope)
        assertEquals(p, capture.projectContext)
    }
}
