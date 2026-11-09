package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionWorkflowPolicyTest {
    @Test fun researchAndStudyPresetsConfigureExistingControlsAndBoundProjectSources() {
        val research = FusionWorkflowPolicy.configure(FusionWorkflowPreset.PAPER_COMPARISON, 12L, 3)!!
        assertEquals(ChatInteractionMode.RESEARCH, research.interactionMode)
        assertTrue(research.webSearchEnabled)
        assertEquals(ProjectSourceScope(ProjectSourceScopeKind.PROJECT, 12L), research.sourceScope)

        val study = FusionWorkflowPolicy.configure(FusionWorkflowPreset.EXAM_PREPARATION, null, 0)!!
        assertEquals(ChatInteractionMode.STUDY, study.interactionMode)
        assertFalse(study.webSearchEnabled)
        assertEquals(ProjectSourceScopeKind.AVAILABLE, study.sourceScope.kind)
    }

    @Test fun projectReviewRequiresAnExplicitProjectWithReadableSources() {
        assertNull(FusionWorkflowPolicy.configure(FusionWorkflowPreset.PROJECT_REVIEW, null, 0))
        assertNull(FusionWorkflowPolicy.configure(FusionWorkflowPreset.PROJECT_REVIEW, 12L, 0))
        val configured = FusionWorkflowPolicy.configure(FusionWorkflowPreset.PROJECT_REVIEW, 12L, 1)
        assertNotNull(configured)
        assertEquals(ProjectSourceScope(ProjectSourceScopeKind.PROJECT, 12L), configured?.sourceScope)
    }

    @Test fun applyingWorkflowKeepsGuidanceVisibleEditableAndDoesNotDuplicateIt() {
        val preset = FusionWorkflowPreset.PAPER_COMPARISON
        val first = FusionWorkflowPolicy.applyGuidance("Compare HBM approaches", preset)
        assertEquals("Compare HBM approaches\n\n${preset.guidance}", first)
        assertEquals(first, FusionWorkflowPolicy.applyGuidance(first, preset))
        assertTrue(FusionWorkflowPolicy.matches(
            preset = preset,
            projectId = null,
            projectFileCount = 0,
            interactionMode = ChatInteractionMode.RESEARCH,
            webSearchEnabled = true,
            sourceScope = ProjectSourceScope(),
            composerDraft = first,
        ))
        assertFalse(FusionWorkflowPolicy.matches(
            preset = preset,
            projectId = null,
            projectFileCount = 0,
            interactionMode = ChatInteractionMode.NORMAL,
            webSearchEnabled = true,
            sourceScope = ProjectSourceScope(),
            composerDraft = first,
        ))
    }

    @Test fun changingWorkflowReplacesOnlyItsPriorGuidance() {
        val question = "HBM3E와 HBM4 비교"
        val paperDraft = FusionWorkflowPolicy.applyGuidance(question, FusionWorkflowPreset.PAPER_COMPARISON)
        val examDraft = FusionWorkflowPolicy.applyGuidance(paperDraft, FusionWorkflowPreset.EXAM_PREPARATION)

        assertEquals("$question\n\n${FusionWorkflowPreset.EXAM_PREPARATION.guidance}", examDraft)
    }
}
