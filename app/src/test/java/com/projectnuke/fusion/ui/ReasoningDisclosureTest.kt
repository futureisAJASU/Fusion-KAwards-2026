package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.AssistantSharePayloadPolicy
import com.projectnuke.fusion.chat.DynamicFollowUpPolicy
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.util.splitExplicitReasoning
import com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningDisclosureTest {

    @Test
    fun `reasoning capable output is panel eligible`() {
        val gemma = FusionPromptAdapters.forFamily(ModelFamily.GEMMA)
        val raw = "<fusion_thinking>emitted reasoning</fusion_thinking><fusion_answer>final</fusion_answer>"
        val sanitized = gemma.sanitizeOutput(raw)
        val parsed = parseAssistantOutput(sanitized, reasoningEnabled = true)
        assertEquals("emitted reasoning", parsed.thinking)
        assertEquals("final", parsed.answer)
        assertTrue(gemma.explicitReasoningTags.isNotEmpty())
    }

    @Test
    fun `non reasoning model has no panel`() {
        val kanana = FusionPromptAdapters.forFamily(ModelFamily.KANANA)
        assertTrue(kanana.explicitReasoningTags.isEmpty())
        val sanitized = kanana.sanitizeOutput("<think>literal</think><thinking>literal</thinking>answer")
        assertEquals("<think>literal</think><thinking>literal</thinking>answer", sanitized)
        val parsed = parseAssistantOutput(sanitized, reasoningEnabled = true)
        assertNull(parsed.thinking)
        assertEquals("<think>literal</think><thinking>literal</thinking>answer", parsed.answer)
    }

    @Test
    fun `empty reasoning has no panel`() {
        val parsed = parseAssistantOutput("<fusion_thinking>   </fusion_thinking>answer", reasoningEnabled = true)
        assertNull(parsed.thinking)
        assertEquals("answer", parsed.answer)
        val live = streamingResponsePresentation("<fusion_thinking>   </fusion_thinking>", reasoningEnabled = true, reasoningTags = setOf("fusion_thinking"))
        assertEquals(StreamingResponsePresentation.LOADING, live)
    }

    @Test
    fun `live reasoning eligible only with exact tags`() {
        val withTags = streamingResponsePresentation("<think>partial</think>", reasoningEnabled = true, reasoningTags = setOf("think"))
        assertEquals(StreamingResponsePresentation.ASSISTANT, withTags)
        val withoutTags = streamingResponsePresentation("<think>partial</think>", reasoningEnabled = true, reasoningTags = emptySet())
        assertEquals(StreamingResponsePresentation.ASSISTANT, withoutTags)
        val parsed = parseAssistantOutput("<think>partial</think>answer", reasoningEnabled = true, reasoningTags = setOf("think"))
        assertEquals("partial", parsed.thinking)
        assertEquals("answer", parsed.answer)
        val gated = parseAssistantOutput("<think>partial</think>answer", reasoningEnabled = true, reasoningTags = emptySet())
        assertNull(gated.thinking)
        assertEquals("<think>partial</think>answer", gated.answer)
    }

    @Test
    fun `stale live text without capability never presents reasoning`() {
        val presentation = streamingResponsePresentation("plain answer", reasoningEnabled = false, reasoningTags = emptySet())
        assertEquals(StreamingResponsePresentation.ASSISTANT, presentation)
        val leaked = streamingResponsePresentation("<fusion_thinking>secret</fusion_thinking>", reasoningEnabled = false, reasoningTags = setOf("fusion_thinking"))
        assertEquals(StreamingResponsePresentation.LOADING, leaked)
    }

    @Test
    fun `response version switch resolves that version reasoning only`() {
        val v1 = "<fusion_thinking>first reasoning</fusion_thinking><fusion_answer>first answer</fusion_answer>"
        val v2 = "<fusion_thinking>second reasoning</fusion_thinking><fusion_answer>second answer</fusion_answer>"
        val p1 = parseAssistantOutput(v1, reasoningEnabled = true)
        val p2 = parseAssistantOutput(v2, reasoningEnabled = true)
        assertEquals("first reasoning", p1.thinking)
        assertEquals("first answer", p1.answer)
        assertEquals("second reasoning", p2.thinking)
        assertEquals("second answer", p2.answer)
        assertFalse(p1.thinking == p2.thinking)
    }

    @Test
    fun `final visible answer strips reasoning`() {
        val raw = "<fusion_thinking>secret</fusion_thinking><fusion_answer>visible</fusion_answer>"
        assertEquals("visible", assistantVisibleAnswerForHandoff(raw, reasoningEnabled = true, generationModeKey = null))
        assertEquals("visible", visibleMessageContentPreservingWhitespace(raw))
        val parsed = parseAssistantOutput(raw, reasoningEnabled = true, reasoningTags = setOf("fusion_thinking", "think"))
        assertEquals("visible", parsed.answer)
        assertFalse(parsed.answer.contains("secret"))
    }

    @Test
    fun `translation share tts followup evidence history strip reasoning`() {
        val raw = "<fusion_thinking>secret reasoning</fusion_thinking><fusion_answer>visible answer</fusion_answer>"
        val handoff = assistantVisibleAnswerForHandoff(raw, reasoningEnabled = true, generationModeKey = null)
        assertEquals("visible answer", handoff)
        val share = AssistantSharePayloadPolicy.buildAnswerOnly(handoff)
        assertTrue(share is com.projectnuke.fusion.chat.AssistantShareBuildResult.Ready)
        assertFalse((share as com.projectnuke.fusion.chat.AssistantShareBuildResult.Ready).text.contains("secret"))
        val followup = DynamicFollowUpPolicy.buildMessages(rawUserRequest = "question", rawAnswer = handoff)
        assertTrue(followup == null || followup.none { it.content.contains("secret") })
        val history = visibleMessageContentPreservingWhitespace(raw)
        assertEquals("visible answer", history)
        assertFalse(history.contains("secret"))
    }

    @Test
    fun `assistant compatibility path may recognize declared legacy native tags`() {
        val raw = "<think>legacy reasoning</think><fusion_answer>visible answer</fusion_answer>"
        assertEquals(
            "visible answer",
            assistantVisibleAnswerForHandoff(
                raw,
                reasoningEnabled = true,
                generationModeKey = null,
                reasoningTags = setOf("think"),
            ),
        )
        assertEquals("<think>legacy reasoning</think>visible answer", visibleMessageContentPreservingWhitespace(raw))
    }
}
