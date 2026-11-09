package com.projectnuke.fusion.ui

import com.projectnuke.fusion.model.ComposerQuickGenerationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerQuickGenerationStatusPresentationTest {

    @Test
    fun `approved runtime marker maps to typed composer status`() {
        assertEquals(
            ComposerPresentedGenerationState(ComposerQuickGenerationStatus.PREPARING_MODEL, false),
            deriveComposerQuickGenerationState(GENERATION_STATUS_MODEL_PREPARING),
        )
        assertEquals(
            ComposerPresentedGenerationState(ComposerQuickGenerationStatus.LOADING_GPU_MODEL, false),
            deriveComposerQuickGenerationState(GENERATION_STATUS_ENGINE_LOADING_GPU),
        )
        assertEquals(
            ComposerPresentedGenerationState(ComposerQuickGenerationStatus.LOADING_CPU_MODEL, false),
            deriveComposerQuickGenerationState(GENERATION_STATUS_ENGINE_LOADING_CPU),
        )
        assertEquals(
            ComposerPresentedGenerationState(ComposerQuickGenerationStatus.PROCESSING_PROMPT, false),
            deriveComposerQuickGenerationState(GENERATION_STATUS_QUESTION_PROCESSING),
        )
        assertEquals(
            ComposerPresentedGenerationState(ComposerQuickGenerationStatus.GENERATING_RESPONSE, false),
            deriveComposerQuickGenerationState(GENERATION_STATUS_ANSWER_GENERATING),
        )
    }

    @Test
    fun `ambiguous engine loading without accelerator prefers no status`() {
        val ambiguous = deriveComposerQuickGenerationState(GENERATION_STATUS_ENGINE_LOADING)
        assertNull(ambiguous.status)
        assertFalse(ambiguous.reasoningActive)
    }

    @Test
    fun `reasoning wording requires the genuine active request path`() {
        val reasoning = deriveComposerQuickGenerationState(GENERATION_STATUS_REASONING)
        assertTrue(reasoning.reasoningActive)
        assertEquals(ComposerQuickGenerationStatus.GENERATING_RESPONSE, reasoning.status)
    }

    @Test
    fun `non approved statuses never become reasoning`() {
        val masked = deriveComposerQuickGenerationState("인터넷 검색 중...")
        assertNull(masked.status)
        assertFalse(masked.reasoningActive)

        val saving = deriveComposerQuickGenerationState("답변 저장 중...")
        assertNull(saving.status)
        assertFalse(saving.reasoningActive)

        val error = deriveComposerQuickGenerationState("오류 발생")
        assertNull(error.status)
        assertFalse(error.reasoningActive)

        val idle = deriveComposerQuickGenerationState(null)
        assertNull(idle.status)
        assertFalse(idle.reasoningActive)
    }
}