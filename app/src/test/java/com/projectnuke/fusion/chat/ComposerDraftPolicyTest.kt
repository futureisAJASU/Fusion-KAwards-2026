package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerDraftPolicyTest {
    @Test
    fun `durable store and presentation use the same input bound`() {
        assertEquals(
            ComposerDraftPolicy.MAX_INPUT_CHARS,
            PersistentComposerDraftStore.MAX_INPUT_CHARS,
        )
    }

    @Test
    fun `exact durable maximum is accepted`() {
        val input = "x".repeat(ComposerDraftPolicy.MAX_INPUT_CHARS)

        assertEquals(
            ComposerTextEditResult.Accepted(input),
            ComposerDraftPolicy.proposeText("previous", input),
        )
    }

    @Test
    fun `one over maximum is rejected without truncation`() {
        val oversized = "x".repeat(ComposerDraftPolicy.MAX_INPUT_CHARS + 1)
        val previous = "previous authoritative text"

        assertEquals(
            ComposerTextEditResult.Rejected(previous),
            ComposerDraftPolicy.proposeText(previous, oversized),
        )
        assertTrue(oversized.length > ComposerDraftPolicy.MAX_INPUT_CHARS)
    }

    @Test
    fun `oversized replacement preserves existing composer text`() {
        val previous = "keep this text"
        val result = ComposerDraftPolicy.proposeText(
            currentText = previous,
            proposedText = "new".repeat(ComposerDraftPolicy.MAX_INPUT_CHARS + 1),
        )

        assertEquals(previous, (result as ComposerTextEditResult.Rejected).preservedText)
    }

    @Test
    fun `input limit policy is deterministic and bounded`() {
        val oversized = "x".repeat(ComposerDraftPolicy.MAX_INPUT_CHARS + 1)

        assertEquals(
            ComposerDraftPolicy.proposeText("old", oversized),
            ComposerDraftPolicy.proposeText("old", oversized),
        )
        assertTrue(ComposerDraftPolicy.inputLimitMessage().isNotBlank())
        assertTrue(ComposerDraftPolicy.inputLimitMessage().length <= 160)
    }
}
