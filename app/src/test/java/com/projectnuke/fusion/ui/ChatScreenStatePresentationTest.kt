package com.projectnuke.fusion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenStatePresentationTest {

    @Test
    fun `null empty and blank streaming text keep the loading presentation`() {
        assertEquals(
            StreamingResponsePresentation.LOADING,
            streamingResponsePresentation(null, reasoningEnabled = false),
        )
        assertEquals(
            StreamingResponsePresentation.LOADING,
            streamingResponsePresentation("", reasoningEnabled = false),
        )
        assertEquals(
            StreamingResponsePresentation.LOADING,
            streamingResponsePresentation(" \n\t", reasoningEnabled = false),
        )
    }

    @Test
    fun `nonblank streaming text presents the assistant response`() {
        assertEquals(
            StreamingResponsePresentation.ASSISTANT,
            streamingResponsePresentation("첫 토큰", reasoningEnabled = false),
        )
    }

    @Test
    fun `reasoning wording is only selected for the genuine reasoning path`() {
        assertEquals(GENERATION_STATUS_REASONING, localGenerationResponseStatus(reasoningEnabled = true))
        assertEquals(GENERATION_STATUS_ANSWER_GENERATING, localGenerationResponseStatus(reasoningEnabled = false))
        assertFalse(hasMeaningfulVisibleStreamingText(" \n"))
        assertTrue(hasMeaningfulVisibleStreamingText("답"))
    }

    @Test
    fun `selected model presentation gives path truth precedence over display name`() {
        assertTrue(
            isSelectedModelPresentation(
                candidateDisplayName = "같은 이름",
                candidatePath = "/models/active.litertlm",
                selectedModel = "같은 이름",
                selectedModelPath = "/models/active.litertlm",
            ),
        )
        assertFalse(
            isSelectedModelPresentation(
                candidateDisplayName = "같은 이름",
                candidatePath = "/models/other.litertlm",
                selectedModel = "같은 이름",
                selectedModelPath = "/models/active.litertlm",
            ),
        )
        assertTrue(
            isSelectedModelPresentation(
                candidateDisplayName = "기본 모델",
                candidatePath = null,
                selectedModel = "기본 모델",
                selectedModelPath = null,
            ),
        )
    }
}
