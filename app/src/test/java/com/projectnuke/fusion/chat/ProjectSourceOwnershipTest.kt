package com.projectnuke.fusion.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectSourceOwnershipTest {
    private val file = ProjectFileSnapshot(1, "shared.txt", "text/plain", "/still-readable/shared.txt")
    private val captured = ProjectContextSnapshot(7, "", listOf(file))
    private val selected = ProjectSourceScope(ProjectSourceScopeKind.SELECTED_FILES, 7, listOf(1))
    @Test fun removedOwnershipRejectsFileThatRemainsReadable() = runBlocking {
        assertFalse(validateProjectSources(selected, captured, { captured.copy(files = emptyList()) }, { true }))
    }
    @Test fun deletedProjectRejectsFileThatRemainsReadable() = runBlocking {
        assertFalse(validateProjectSources(selected, captured, { null }, { true }))
    }
    @Test fun reboundIdentityAndOtherProjectAreRejected() = runBlocking {
        assertFalse(validateProjectSources(selected, captured, { captured.copy(files = listOf(file.copy(localPath = "/other.txt"))) }, { true }))
        assertFalse(validateProjectSources(selected, captured, { captured.copy(projectId = 8) }, { true }))
    }
    @Test fun projectWideRemovalCannotSilentlyShrinkFrozenScope() = runBlocking {
        val two = captured.copy(files = listOf(file, file.copy(id = 2, localPath = "/two.txt")))
        assertFalse(validateProjectSources(ProjectSourceScope(ProjectSourceScopeKind.PROJECT, 7), two, { captured }, { true }))
    }
    @Test fun intactOwnershipStillRequiresReadableSource() = runBlocking {
        assertTrue(validateProjectSources(selected, captured, { captured }, { true }))
        assertFalse(validateProjectSources(selected, captured, { captured }, { false }))
    }
    @Test fun attachmentOnlyDoesNotReadProjectOrExpandItsSources() = runBlocking {
        assertTrue(validateProjectSources(ProjectSourceScope(ProjectSourceScopeKind.CURRENT_ATTACHMENTS), null,
            { error("project lookup forbidden") }, { error("project reading forbidden") }))
    }
    @Test fun ownershipLookupPreservesCancellationAndFatalIdentity() = runBlocking {
        val cancel = CancellationException("cancel")
        try { validateProjectSources(selected, captured, { throw cancel }, { true }); fail() }
        catch (actual: CancellationException) { assertSame(cancel, actual) }
        val fatal = AssertionError("fatal")
        try { validateProjectSources(selected, captured, { throw fatal }, { true }); fail() }
        catch (actual: AssertionError) { assertSame(fatal, actual) }
    }
}
