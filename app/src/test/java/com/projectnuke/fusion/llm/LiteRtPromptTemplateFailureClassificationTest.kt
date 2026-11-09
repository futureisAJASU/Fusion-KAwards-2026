package com.projectnuke.fusion.llm

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtPromptTemplateFailureClassificationTest {
    @Test
    fun `device prompt template INTERNAL error is prompt incompatible not load failure`() {
        val error = IllegalStateException(
            "INTERNAL: Failed to apply template: Template creation failed: " +
                "syntax error: unknown statement generation",
        )
        assertTrue(isLiteRtPromptTemplateFailure(error))
        assertFalse(isLiteRtIrrecoverableLoadException(error))
        assertEquals(
            FailureKind.PROMPT_TEMPLATE_INCOMPATIBLE,
            classifyLiteRtGenerationFailureKind(error, isMultimodal = false),
        )
    }

    @Test
    fun `actual engine creation failure remains model load failed`() {
        val error = IllegalStateException("INTERNAL: Failed to create engine")
        assertFalse(isLiteRtPromptTemplateFailure(error))
        assertTrue(isLiteRtIrrecoverableLoadException(error))
        assertEquals(
            FailureKind.MODEL_LOAD_FAILED,
            classifyLiteRtGenerationFailureKind(error, isMultimodal = false),
        )
    }

    @Test
    fun `oom VM and linkage classifications remain model load failed`() {
        listOf(
            OutOfMemoryError("native allocation failed"),
            StackOverflowError("runtime stack exhausted"),
            LinkageError("native linkage failed"),
        ).forEach { error ->
            assertEquals(
                FailureKind.MODEL_LOAD_FAILED,
                classifyLiteRtGenerationFailureKind(error, isMultimodal = false),
            )
        }
    }

    @Test
    fun `fatal multimodal failures retain their runtime failure classification`() {
        listOf(
            OutOfMemoryError("image tensor allocation failed"),
            StackOverflowError("vision runtime stack exhausted"),
            LinkageError("multimodal native symbol missing"),
        ).forEach { error ->
            assertEquals(
                error.javaClass.simpleName,
                FailureKind.MODEL_LOAD_FAILED,
                classifyLiteRtGenerationFailureKind(error, isMultimodal = true),
            )
        }
    }

    @Test
    fun `ordinary unsupported multimodal input retains its capability classification`() {
        assertEquals(
            FailureKind.MODEL_MULTIMODAL_UNSUPPORTED,
            classifyLiteRtGenerationFailureKind(
                IllegalStateException("vision input unsupported"), isMultimodal = true,
            ),
        )
    }

    @Test
    fun `ordinary generation exception behavior remains unchanged`() {
        assertEquals(
            FailureKind.GENERATION_IO,
            classifyLiteRtGenerationFailureKind(IOException("stream closed"), isMultimodal = false),
        )
        assertEquals(
            FailureKind.GENERATION_INTERRUPTED,
            classifyLiteRtGenerationFailureKind(IllegalStateException("cancelled"), isMultimodal = false),
        )
    }
}
