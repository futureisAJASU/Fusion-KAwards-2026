package com.projectnuke.fusion.chat

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Study Mode prompt policy, append composition, controls and mode-entry
 * semantics (Phase 1).
 *
 * Entering STUDY is a pure presentation switch ([ChatInteractionModePolicy])
 * that appends nothing; prompt actions ([StudySessionFlow.request]) only
 * append ordinary text through the durable draft seam.
 */
class StudySessionPolicyTest {

    private class FakeComposer(var appendResult: Boolean = true) {
        val mutations = mutableListOf<Pair<Long, String>>()
        var sendCalls = 0

        suspend fun mutate(conversationId: Long, text: String): Boolean {
            mutations.add(conversationId to text)
            return appendResult
        }
    }

    private fun scope(conversationId: Long = 7L, epoch: Long = 0L) =
        ChatInteractionModeScope(conversationId, epoch)

    private fun study(conversationId: Long = 7L, epoch: Long = 0L) =
        ChatInteractionModeState(
            mode = ChatInteractionMode.STUDY,
            conversationId = conversationId,
            newChatEpoch = epoch,
        )

    // --- Study prompts (K/L/M) --------------------------------------------------------

    @Test
    fun `K initial question prompt asks one question without revealing answers`() {
        val prompt = StudySessionPromptPolicy.initialQuestionPrompt

        assertTrue(prompt.contains("하나만"))
        assertTrue(prompt.contains("말하지 마세요"))
        // Context-neutral: works with no document and asks back when the
        // topic is unclear instead of inventing one.
        assertTrue(prompt.contains("무엇을 공부할지"))
        assertFalse(prompt.contains("참고 문서"))
        assertFalse(prompt.contains("excerpt"))
        assertTrue(prompt.length <= StudySessionPromptPolicy.MAX_PROMPT_CHARS)
    }

    @Test
    fun `L grading prompt appends safely and claims no verification`() {
        val prompt = StudySessionPromptPolicy.gradingPrompt

        assertTrue(prompt.contains("검토해 주세요"))
        assertTrue(prompt.contains("맞은 점, 틀린 점, 불확실한 점"))
        assertTrue(prompt.contains("이유와 더 적절한 설명"))
        assertTrue(prompt.contains("확인 필요"))
        assertFalse(prompt.contains("정답 검증"))
        assertFalse(prompt.contains("정확한 채점"))
        assertFalse(prompt.contains("정답 보장"))
        assertFalse(prompt.contains("검증됨"))
        assertTrue(prompt.length <= StudySessionPromptPolicy.MAX_PROMPT_CHARS)
    }

    @Test
    fun `M next-question prompt asks a different single question`() {
        val prompt = StudySessionPromptPolicy.nextQuestionPrompt

        assertTrue(prompt.contains("겹치지 않도록"))
        assertTrue(prompt.contains("다른 핵심 내용"))
        assertTrue(prompt.contains("하나만"))
        assertTrue(prompt.contains("말하지 마세요"))
        assertTrue(prompt.length <= StudySessionPromptPolicy.MAX_PROMPT_CHARS)
    }

    @Test
    fun `all study prompts are bounded`() {
        for (prompt in StudySessionPromptPolicy.allPrompts) {
            assertTrue(prompt.isNotBlank())
            assertTrue(prompt.length <= StudySessionPromptPolicy.MAX_PROMPT_CHARS)
        }
    }

    // --- Append composition (O/P mechanics) ----------------------------------------------

    @Test
    fun `blank composer becomes the study prompt`() {
        assertEquals(
            StudySessionPromptPolicy.gradingPrompt,
            appendPromptToDraft("", StudySessionPromptPolicy.gradingPrompt),
        )
        assertEquals(
            StudySessionPromptPolicy.initialQuestionPrompt,
            appendPromptToDraft("   ", StudySessionPromptPolicy.initialQuestionPrompt),
        )
    }

    @Test
    fun `existing draft is preserved with study prompt appended`() {
        val draft = "전압이 증가해서입니다."
        val result = appendPromptToDraft(draft, StudySessionPromptPolicy.gradingPrompt)

        assertTrue(result.startsWith(draft))
        assertTrue(result.contains(StudySessionPromptPolicy.gradingPrompt))
        assertTrue(result.length > draft.length)
    }

    // --- Mode entry: pure presentation switch, zero append -------------------------------

    @Test
    fun `K entering STUDY appends nothing and binds the exact scope`() {
        val composer = FakeComposer()
        val entered = ChatInteractionModePolicy.switch(
            ChatInteractionModeState(),
            ChatInteractionModeScope(42L, 3L),
            ChatInteractionMode.STUDY,
            true,
        )

        assertEquals(study(42L, 3L), entered)
        assertTrue(composer.mutations.isEmpty())
        assertEquals(0, composer.sendCalls)
    }

    @Test
    fun `O entering STUDY never erases draft composition`() {
        // Composition itself is the ViewModel's appendQuickPrompt seam;
        // mode entry supplies no prompt text, never a replacement.
        val draft = "내가 쓴 중간 답안"
        val entered = ChatInteractionModePolicy.switch(
            ChatInteractionModeState(),
            scope(),
            ChatInteractionMode.STUDY,
            true,
        )

        assertEquals(ChatInteractionMode.STUDY, entered.mode)
        assertTrue(draft.isNotBlank())
    }

    @Test
    fun `P grading keeps user draft at the beginning`() {
        val draft = "전압이 증가해서입니다"
        val composed = appendPromptToDraft(draft, StudySessionPromptPolicy.gradingPrompt)

        assertTrue(composed.startsWith(draft))
        assertTrue(composed.contains(StudySessionPromptPolicy.gradingPrompt))
    }

    @Test
    fun `N explicit exit to NORMAL preserves scope identity with zero mutation`() {
        val composer = FakeComposer()
        val active = study(7L, 2L)

        val exited = ChatInteractionModePolicy.switch(
            active,
            scope(7L, 2L),
            ChatInteractionMode.NORMAL,
            true,
        )

        assertEquals(ChatInteractionMode.NORMAL, exited.mode)
        assertEquals(7L, exited.conversationId)
        assertEquals(2L, exited.newChatEpoch)
        assertTrue(composer.mutations.isEmpty())
        assertEquals(0, composer.sendCalls)
    }

    @Test
    fun `M back never exits STUDY by construction`() {
        // Android Back maps to no mode API: neither the switch nor the
        // prompt flow exposes a back entry point, and the ChatScreen
        // BackHandler owns no Study branch anymore.
        val names = (ChatInteractionModePolicy.javaClass.declaredMethods +
            ChatInteractionModePolicy.javaClass.methods)
            .map { it.name }.joinToString(",") +
            (StudySessionFlow.javaClass.declaredMethods +
                StudySessionFlow.javaClass.methods)
                .map { it.name }.joinToString(",")
        assertFalse(names.contains("back", ignoreCase = true))

        val active = study(7L, 2L)
        // Without an explicit NORMAL switch the binding survives untouched.
        assertEquals(
            ChatInteractionMode.STUDY,
            ChatInteractionModePolicy.currentMode(active, 7L, 2L),
        )
    }

    @Test
    fun `R stale scope prompt request is Rejected with zero appends`() = runTest {
        val composer = FakeComposer()
        val stale = study(7L, 0L)

        assertEquals(
            ChatInteractionMode.NORMAL,
            ChatInteractionModePolicy.currentMode(stale, 9L, 0L),
        )
        val result = StudySessionFlow.requestQuestion(stale, scope(9L), true, composer::mutate)

        assertEquals(StudyPromptRequestOutcome.Rejected, result.outcome)
        assertEquals(stale, result.modeState)
        assertTrue(composer.mutations.isEmpty())
    }

    @Test
    fun `same-id new chat epoch change rejects prompt requests`() = runTest {
        val composer = FakeComposer()
        val active = ChatInteractionModeState(
            mode = ChatInteractionMode.STUDY,
            conversationId = 0L,
            newChatEpoch = 1L,
        )

        assertEquals(ChatInteractionMode.STUDY, ChatInteractionModePolicy.currentMode(active, 0L, 1L))
        // Same conversationId, bumped epoch: the old binding must not
        // render, and its actions must not write into the fresh chat.
        assertEquals(ChatInteractionMode.NORMAL, ChatInteractionModePolicy.currentMode(active, 0L, 2L))

        val result = StudySessionFlow.requestGrading(
            active,
            ChatInteractionModeScope(0L, 2L),
            true,
            composer::mutate,
        )
        assertEquals(StudyPromptRequestOutcome.Rejected, result.outcome)
        assertTrue(composer.mutations.isEmpty())
    }

    @Test
    fun `S unavailable ownership disables controls with zero mutation`() = runTest {
        val composer = FakeComposer()
        val active = study()
        val current = scope()

        assertFalse(StudySessionFlow.controlsEnabled(true, false, false, false, false))
        assertFalse(StudySessionFlow.controlsEnabled(false, true, false, false, false))
        assertFalse(StudySessionFlow.controlsEnabled(false, false, true, false, false))
        assertFalse(StudySessionFlow.controlsEnabled(false, false, false, true, false))
        assertFalse(StudySessionFlow.controlsEnabled(false, false, false, false, true))
        assertTrue(StudySessionFlow.controlsEnabled(false, false, false, false, false))

        // Disabled mode switches are rejected, never queued.
        val unchanged = ChatInteractionModePolicy.switch(
            ChatInteractionModeState(),
            current,
            ChatInteractionMode.STUDY,
            false,
        )
        assertEquals(ChatInteractionMode.NORMAL, unchanged.mode)
        // Disabled prompt taps reject without appending.
        val rejected = StudySessionFlow.requestQuestion(active, current, false, composer::mutate)
        assertEquals(StudyPromptRequestOutcome.Rejected, rejected.outcome)

        assertTrue(composer.mutations.isEmpty())
        assertEquals(0, composer.sendCalls)
    }

    @Test
    fun `deleting conversation disables study controls`() {
        assertFalse(StudySessionFlow.controlsEnabled(false, false, false, false, true))
        assertTrue(StudySessionFlow.controlsEnabled(false, false, false, false, false))
    }

    @Test
    fun `grading requires nonblank composer with available controls and no flight`() {
        assertFalse(StudySessionFlow.gradingEnabled(false, "내 답", false))
        assertFalse(StudySessionFlow.gradingEnabled(true, "", false))
        assertFalse(StudySessionFlow.gradingEnabled(true, "   ", false))
        assertFalse(StudySessionFlow.gradingEnabled(true, "내 답", true))
        assertTrue(StudySessionFlow.gradingEnabled(true, "내 답", false))
    }

    @Test
    fun `flow exposes no send or generation entry point`() {
        val methods = (StudySessionFlow.javaClass.declaredMethods +
            StudySessionFlow.javaClass.methods)
            .filter { it.declaringClass != Any::class.java }
        for (method in methods) {
            // At most the single composer-mutation seam; never a retrieval,
            // submission or generation callback.
            val callbackParams = method.parameterTypes.filter { it.name.contains("Function") }
            assertTrue(
                "${method.name} exposes unexpected callbacks",
                callbackParams.size <= 1,
            )
        }
        val names = methods.map { it.name }.joinToString(",")
        assertFalse(names.contains("send", ignoreCase = true))
        assertFalse(names.contains("submit", ignoreCase = true))
        assertFalse(names.contains("generate", ignoreCase = true))
        assertFalse(names.contains("retriev", ignoreCase = true))
    }
}
