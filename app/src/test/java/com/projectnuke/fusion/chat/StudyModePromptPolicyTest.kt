package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Study Mode request-time model behavior (Phase 2).
 *
 * NORMAL contributes "" so prompts stay byte-identical when Study Mode is
 * off; STUDY contributes one bounded host-authored instruction that shapes
 * pedagogy without touching routing, retrieval, or formatting rules.
 */
class StudyModePromptPolicyTest {

    @Test
    fun `H NORMAL contributes zero Study instruction`() {
        assertEquals("", StudyModePromptPolicy.modelInstruction(ChatInteractionMode.NORMAL))
        assertFalse(
            StudyModePromptPolicy.modelInstruction(ChatInteractionMode.NORMAL).contains("학습 모드"),
        )
    }

    @Test
    fun `I STUDY contains the exact bounded instruction once`() {
        val instruction = StudyModePromptPolicy.modelInstruction(ChatInteractionMode.STUDY)

        assertTrue(instruction.isNotBlank())
        assertTrue(instruction.length <= StudyModePromptPolicy.MAX_INSTRUCTION_CHARS)
        assertEquals(1, occurrences(instruction, "현재 요청은 학습 모드입니다."))
        // Same content on every call: no per-request interpolation.
        assertEquals(instruction, StudyModePromptPolicy.modelInstruction(ChatInteractionMode.STUDY))
    }

    @Test
    fun `J instruction carries self-solving pedagogy`() {
        val instruction = StudyModePromptPolicy.modelInstruction(ChatInteractionMode.STUDY)

        // Self-solving guidance.
        assertTrue(instruction.contains("스스로 이해하고 문제를 풀 수 있도록 단계적으로 돕습니다."))
        // Answer withholding unless explicitly requested.
        assertTrue(instruction.contains("명시적으로 요청하지 않는 한 정답 전체를 먼저 공개하지"))
        // Hint behavior.
        assertTrue(instruction.contains("힌트를 우선합니다"))
        assertTrue(instruction.contains("답을 직접 공개하기보다"))
        // Grading behavior.
        assertTrue(instruction.contains("맞은 점, 틀린 점, 불확실한 점"))
        // Uncertainty / reference behavior.
        assertTrue(instruction.contains("추측해 확정하지 않습니다"))
        assertTrue(instruction.contains("이를 우선 활용합니다"))
        // Explicit user requests still win.
        assertTrue(instruction.contains("명시적으로 요청하면 답과 이유를 설명할 수 있습니다"))
        assertTrue(instruction.contains("사용자의 현재 명시적 요청을 무시하는 규칙이 아닙니다"))
    }

    @Test
    fun `K instruction never requests hidden reasoning`() {
        val instruction = StudyModePromptPolicy.modelInstruction(ChatInteractionMode.STUDY)

        assertFalse(instruction.contains("fusion_thinking"))
        assertFalse(instruction.contains("fusion_answer"))
        assertFalse(instruction.contains("chain-of-thought", ignoreCase = true))
        assertFalse(instruction.contains("chain of thought", ignoreCase = true))
        assertFalse(instruction.contains("hidden reasoning", ignoreCase = true))
        assertFalse(instruction.contains("숨은 추론"))
        assertFalse(instruction.contains("숨겨진 추론"))
    }

    @Test
    fun `instruction interferes with no other prompt rule`() {
        val instruction = StudyModePromptPolicy.modelInstruction(ChatInteractionMode.STUDY)

        // Output-formatting tags stay owned by the reasoning rule.
        assertFalse(instruction.contains("<fusion_thinking>"))
        assertFalse(instruction.contains("<fusion_answer>"))
        // Web-search behavior stays owned by the web rule.
        assertFalse(instruction.contains("웹 검색 참고 정보"))
        assertFalse(instruction.contains("FUSION_"))
        // Project instructions stay owned by the project message.
        assertFalse(instruction.contains("ProjectInstruction"))
        // No template slots: identical for every user and request.
        assertFalse(instruction.contains("\${"))
        assertFalse(instruction.contains("%s"))
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }
}
