package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.*
import com.projectnuke.fusion.model.GenerationSettings
import org.junit.Assert.*
import org.junit.Test

class ScopedImageRequestTest {
    private fun snapshot(kind: ProjectSourceScopeKind): GenerationRequestSnapshot {
        val capture=RegenerationRequestCapture(1,2,ResponseVersionState(),ChatGenerationMode.LOCAL_MODEL,"local","/model","id",null,
            localProfile=null,generationSettings=GenerationSettings(),reasoningEnabled=false,webSearchEnabled=false,
            promptLabInstruction="",externalProviderId=null,externalMaxOutputTokens=null,sourceScope=ProjectSourceScope(kind,1,listOf(10)))
        return buildRegenerationGenerationRequestSnapshot(capture,"request",GenerationSettings(),false,
            GenerationRequestSnapshot.WebSearchPolicy.DISABLED,listOf("private-image"),listOf("private-image"),"question","question",0,
            emptyList(),emptyList())
    }
    @Test fun projectOnlyRequestDoesNotConsumeCurrentImage() { assertTrue(snapshot(ProjectSourceScopeKind.PROJECT).multimodalImagePaths.isEmpty()) }
    @Test fun selectedProjectFilesDoNotConsumeCurrentImage() { assertTrue(snapshot(ProjectSourceScopeKind.SELECTED_FILES).multimodalImagePaths.isEmpty()) }
    @Test fun currentAndAvailableScopesRetainExplicitImages() { assertEquals(listOf("private-image"),snapshot(ProjectSourceScopeKind.CURRENT_ATTACHMENTS).multimodalImagePaths)
        assertEquals(listOf("private-image"),snapshot(ProjectSourceScopeKind.AVAILABLE).multimodalImagePaths) }
    @Test fun explicitDocumentScopesRejectFailedOrEmptyPreparation() {
        listOf(ProjectSourceScopeKind.PROJECT,ProjectSourceScopeKind.SELECTED_FILES,ProjectSourceScopeKind.CURRENT_ATTACHMENTS).forEach {
            assertFalse(ProjectSourceScopePolicy.mayProceed(ProjectSourceScope(it),1,false))
            assertTrue(ProjectSourceScopePolicy.mayProceed(ProjectSourceScope(it),1,true))
        }
        assertFalse(ProjectSourceScopePolicy.mayProceed(ProjectSourceScope(ProjectSourceScopeKind.SELECTED_FILES),0,false))
        assertTrue(ProjectSourceScopePolicy.mayProceed(ProjectSourceScope(),1,false))
    }
}
