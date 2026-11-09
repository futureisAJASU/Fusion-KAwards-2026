package com.projectnuke.fusion.chat

import com.projectnuke.fusion.trust.TrustRoutingPresentationKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Study Mode v1.1: Hint + Answer/Explanation guided actions over the
 * explicit [ChatInteractionMode].
 *
 * Every action only appends an ordinary editable prompt through the durable
 * composer seam. No auto-send, no generation, no parsing, no persistent
 * Study state. Rejections (stale scope, invisible mode, disabled controls)
 * are typed as [StudyPromptRequestOutcome.Rejected] and must stay silent;
 * only [StudyPromptRequestOutcome.AppendFailed] may show the bounded failure
 * notice.
 */
class StudySessionV11PolicyTest {

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

    // --- A: bound ------------------------------------------------------------

    @Test
    fun `A all study prompts are bounded`() {
        assertEquals(5, StudySessionPromptPolicy.allPrompts.size)
        for (prompt in StudySessionPromptPolicy.allPrompts) {
            assertTrue(prompt.isNotBlank())
            assertTrue(prompt.length <= StudySessionPromptPolicy.MAX_PROMPT_CHARS)
        }
    }

    // --- B: question ---------------------------------------------------------

    @Test
    fun `B question asks one question and withholds answer`() {
        val prompt = StudySessionPromptPolicy.initialQuestionPrompt
        assertTrue(prompt.contains("하나만"))
        assertTrue(prompt.contains("말하지 마세요"))
        // Unknown topic clarifies instead of inventing a random subject.
        assertTrue(prompt.contains("무엇을 공부할지"))
        assertTrue(prompt.contains("임의의 문제를 만들지 말고"))
        assertTrue(prompt.length <= StudySessionPromptPolicy.MAX_PROMPT_CHARS)
    }

    // --- C: grading ----------------------------------------------------------

    @Test
    fun `C grading distinguishes correctness without verification guarantee`() {
        val prompt = StudySessionPromptPolicy.gradingPrompt
        assertTrue(prompt.contains("맞은 점, 틀린 점, 불확실한 점"))
        assertTrue(prompt.contains("확인 필요"))
        assertFalse(prompt.contains("정답 검증"))
        assertFalse(prompt.contains("정확한 채점"))
        assertFalse(prompt.contains("정답 보장"))
        assertFalse(prompt.contains("검증됨"))
        assertFalse(prompt.contains("보장"))
        assertFalse(prompt.contains("검증된 정답"))
        assertFalse(prompt.contains("정확한 정답"))
        assertTrue(prompt.length <= StudySessionPromptPolicy.MAX_PROMPT_CHARS)
    }

    // --- D: next question ----------------------------------------------------

    @Test
    fun `D next question asks a different single question and withholds answer`() {
        val prompt = StudySessionPromptPolicy.nextQuestionPrompt
        assertTrue(prompt.contains("겹치지 않도록"))
        assertTrue(prompt.contains("다른 핵심 내용"))
        assertTrue(prompt.contains("하나만"))
        assertTrue(prompt.contains("말하지 마세요"))
        assertTrue(prompt.contains("무엇을 공부할지"))
        assertTrue(prompt.length <= StudySessionPromptPolicy.MAX_PROMPT_CHARS)
    }

    // --- E: hint truth rule --------------------------------------------------

    @Test
    fun `E hint asks one hint without revealing the answer`() {
        val prompt = StudySessionPromptPolicy.hintPrompt
        assertTrue(prompt.contains("답을 직접 말하지 말고"))
        assertTrue(prompt.contains("힌트 하나만"))
        assertTrue(prompt.contains("스스로 풀 수 있도록"))
        assertTrue(prompt.contains("다음 사고 단계"))
        assertTrue(prompt.contains("참고 자료"))
        assertFalse(prompt.contains("참고 문서"))
        assertFalse(prompt.contains("정답은 숨기고 사실상 답을 알려"))
        assertTrue(prompt.length <= StudySessionPromptPolicy.MAX_PROMPT_CHARS)
    }

    // --- F: explanation truth rule -------------------------------------------

    @Test
    fun `F explanation reveals only by explicit action with uncertainty marker`() {
        val prompt = StudySessionPromptPolicy.explanationPrompt
        assertTrue(prompt.contains("답과 해설을 보여 주세요"))
        assertTrue(prompt.contains("우선 근거로"))
        assertTrue(prompt.contains("확인 필요"))
        assertFalse(prompt.contains("참고 문서"))
        assertFalse(prompt.contains("보장"))
        assertFalse(prompt.contains("검증된 정답"))
        assertFalse(prompt.contains("정확한 정답"))
        assertTrue(prompt.length <= StudySessionPromptPolicy.MAX_PROMPT_CHARS)
    }

    // --- G/H: mapping --------------------------------------------------------

    @Test
    fun `G every action maps to exactly one nonblank prompt`() {
        val mapped = StudySessionAction.entries.map { StudySessionPromptPolicy.promptFor(it) }
        assertEquals(StudySessionAction.entries.size, mapped.size)
        for (prompt in mapped) {
            assertTrue(prompt.isNotBlank())
        }
        // Each action prompt is present in the bounded set.
        for (prompt in mapped) {
            assertTrue(StudySessionPromptPolicy.allPrompts.contains(prompt))
        }
    }

    @Test
    fun `H action ids are unique`() {
        val ids = StudySessionAction.entries.map { it.name }
        assertEquals(ids.size, ids.toSet().size)
    }

    // --- I/J/K/L/M: generic request appends exact prompts --------------------

    @Test
    fun `I generic QUESTION appends exact question prompt`() = runTest {
        val composer = FakeComposer()
        val result = StudySessionFlow.request(
            StudySessionAction.QUESTION, study(), scope(), true, composer::mutate,
        )
        assertEquals(StudyPromptRequestOutcome.Appended, result.outcome)
        assertEquals(listOf(7L to StudySessionPromptPolicy.initialQuestionPrompt), composer.mutations)
    }

    @Test
    fun `J generic HINT appends exact hint prompt`() = runTest {
        val composer = FakeComposer()
        val result = StudySessionFlow.request(
            StudySessionAction.HINT, study(), scope(), true, composer::mutate,
        )
        assertEquals(StudyPromptRequestOutcome.Appended, result.outcome)
        assertEquals(listOf(7L to StudySessionPromptPolicy.hintPrompt), composer.mutations)
    }

    @Test
    fun `K generic GRADING appends exact grading prompt`() = runTest {
        val composer = FakeComposer()
        val result = StudySessionFlow.request(
            StudySessionAction.GRADING, study(), scope(), true, composer::mutate,
        )
        assertEquals(StudyPromptRequestOutcome.Appended, result.outcome)
        assertEquals(listOf(7L to StudySessionPromptPolicy.gradingPrompt), composer.mutations)
    }

    @Test
    fun `L generic EXPLANATION appends exact explanation prompt`() = runTest {
        val composer = FakeComposer()
        val result = StudySessionFlow.request(
            StudySessionAction.EXPLANATION, study(), scope(), true, composer::mutate,
        )
        assertEquals(StudyPromptRequestOutcome.Appended, result.outcome)
        assertEquals(listOf(7L to StudySessionPromptPolicy.explanationPrompt), composer.mutations)
    }

    @Test
    fun `M generic NEXT_QUESTION appends exact next prompt`() = runTest {
        val composer = FakeComposer()
        val result = StudySessionFlow.request(
            StudySessionAction.NEXT_QUESTION, study(), scope(), true, composer::mutate,
        )
        assertEquals(StudyPromptRequestOutcome.Appended, result.outcome)
        assertEquals(listOf(7L to StudySessionPromptPolicy.nextQuestionPrompt), composer.mutations)
    }

    // --- N/O/T: rejection ----------------------------------------------------

    @Test
    fun `N invisible mode performs zero appends with Rejected outcome`() = runTest {
        val composer = FakeComposer()
        val normal = ChatInteractionModeState()
        for (action in StudySessionAction.entries) {
            val result = StudySessionFlow.request(action, normal, scope(), true, composer::mutate)
            assertEquals(StudyPromptRequestOutcome.Rejected, result.outcome)
        }
        assertTrue(composer.mutations.isEmpty())
    }

    @Test
    fun `N2 stale scope performs zero appends with Rejected outcome`() = runTest {
        val composer = FakeComposer()
        val stale = study(7L, 0L)
        for (action in StudySessionAction.entries) {
            val result = StudySessionFlow.request(action, stale, scope(9L), true, composer::mutate)
            assertEquals(StudyPromptRequestOutcome.Rejected, result.outcome)
            assertEquals(stale, result.modeState)
        }
        assertTrue(composer.mutations.isEmpty())
    }

    @Test
    fun `O disabled controls perform zero appends with Rejected outcome`() = runTest {
        val composer = FakeComposer()
        for (action in StudySessionAction.entries) {
            val result = StudySessionFlow.request(action, study(), scope(), false, composer::mutate)
            assertEquals(StudyPromptRequestOutcome.Rejected, result.outcome)
        }
        assertTrue(composer.mutations.isEmpty())
    }

    @Test
    fun `T stale epoch performs zero appends`() = runTest {
        val composer = FakeComposer()
        val state = study(0L, 1L)
        for (action in StudySessionAction.entries) {
            val result = StudySessionFlow.request(
                action,
                state,
                ChatInteractionModeScope(0L, 2L),
                true,
                composer::mutate,
            )
            assertEquals(StudyPromptRequestOutcome.Rejected, result.outcome)
        }
        assertTrue(composer.mutations.isEmpty())
    }

    // --- O/P/Q: typed outcome ownership ---------------------------------------

    @Test
    fun `O Rejected shows no failure notice`() {
        assertFalse(
            StudyPromptFailureNoticePolicy.shouldShowFailureNotice(
                StudyPromptRequestOutcome.Rejected,
            ),
        )
    }

    @Test
    fun `P durable append success reports Appended`() = runTest {
        val composer = FakeComposer(appendResult = true)
        val before = study()
        val result = StudySessionFlow.request(
            StudySessionAction.HINT, before, scope(), true, composer::mutate,
        )
        assertEquals(StudyPromptRequestOutcome.Appended, result.outcome)
        assertEquals(before, result.modeState)
        assertEquals(1, composer.mutations.size)
        assertFalse(
            StudyPromptFailureNoticePolicy.shouldShowFailureNotice(result.outcome),
        )
    }

    @Test
    fun `Q durable append failure reports AppendFailed distinct from Rejected`() = runTest {
        val composer = FakeComposer(appendResult = false)
        val before = study()
        val result = StudySessionFlow.request(
            StudySessionAction.EXPLANATION, before, scope(), true, composer::mutate,
        )
        assertEquals(StudyPromptRequestOutcome.AppendFailed, result.outcome)
        assertEquals(before, result.modeState)
        // The callback ran once and reported failure; the mode stays STUDY.
        assertEquals(1, composer.mutations.size)
        assertEquals(ChatInteractionMode.STUDY, result.modeState.mode)
        // Only AppendFailed may show the bounded failure notice.
        assertTrue(
            StudyPromptFailureNoticePolicy.shouldShowFailureNotice(result.outcome),
        )
        assertFalse(
            StudyPromptFailureNoticePolicy.shouldShowFailureNotice(
                StudyPromptRequestOutcome.Rejected,
            ),
        )
    }

    // --- R: cancellation -----------------------------------------------------

    @Test
    fun `R cancellation propagates without failure conversion`() = runTest {
        val before = study()
        try {
            StudySessionFlow.request(
                StudySessionAction.HINT, before, scope(), true,
                { _, _ -> throw CancellationException("cancelled") },
            )
            fail("expected CancellationException to propagate")
        } catch (_: CancellationException) {
        }
    }

    // --- S: conversation id --------------------------------------------------

    @Test
    fun `S exact scope conversationId is forwarded`() = runTest {
        val composer = FakeComposer()
        val state = study(42L, 3L)
        val result = StudySessionFlow.request(
            StudySessionAction.QUESTION, state, ChatInteractionModeScope(42L, 3L), true, composer::mutate,
        )
        assertEquals(StudyPromptRequestOutcome.Appended, result.outcome)
        assertEquals(listOf(42L to StudySessionPromptPolicy.initialQuestionPrompt), composer.mutations)
    }

    // --- I/J: F2 eligibility follows the mode ----------------------------------

    @Test
    fun `I STUDY suppresses F2 next actions`() {
        assertFalse(
            NextActionEligibilityPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = false,
                isStudyMode = true,
            ),
        )
    }

    @Test
    fun `J NORMAL restores F2 eligibility`() {
        assertFalse(
            NextActionEligibilityPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = false,
                isStudyMode = true,
            ),
        )
        assertTrue(
            NextActionEligibilityPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = false,
                isStudyMode = false,
            ),
        )
    }

    // --- U/V/W/X/Y/Z: enablement ---------------------------------------------

    @Test
    fun `U grading blank composer is disabled`() {
        assertFalse(StudySessionFlow.gradingEnabled(true, "", false))
        assertFalse(StudySessionFlow.gradingEnabled(true, "   ", false))
    }

    @Test
    fun `V grading nonblank composer is enabled when ready`() {
        assertTrue(StudySessionFlow.gradingEnabled(true, "내 답", false))
        assertFalse(StudySessionFlow.gradingEnabled(false, "내 답", false))
    }

    @Test
    fun `W explanation blank composer is enabled`() {
        assertTrue(StudySessionFlow.explanationEnabled(true, "", false))
        assertTrue(StudySessionFlow.explanationEnabled(true, "   ", false))
    }

    @Test
    fun `X explanation nonblank composer is disabled`() {
        assertFalse(StudySessionFlow.explanationEnabled(true, "내 답", false))
        assertFalse(StudySessionFlow.explanationEnabled(true, "부분 시도", false))
    }

    @Test
    fun `Y in-flight disables both grading and explanation`() {
        assertFalse(StudySessionFlow.gradingEnabled(true, "내 답", true))
        assertFalse(StudySessionFlow.explanationEnabled(true, "", true))
    }

    @Test
    fun `Z hint uses common enablement without blank requirement`() {
        // HINT renders on the common gate: no composer-text precondition.
        // A partial attempt plus a hint is an ordinary transparent append.
        val draft = "전압 때문인 것 같은데 잘 모르겠습니다."
        val composed = appendPromptToDraft(draft, StudySessionPromptPolicy.hintPrompt)
        assertTrue(composed.startsWith(draft))
        assertTrue(composed.contains(StudySessionPromptPolicy.hintPrompt))
        // Common controls stay available independent of draft content.
        assertTrue(StudySessionFlow.controlsEnabled(false, false, false, false, false))
    }

    // --- Descriptor authority --------------------------------------------------

    @Test
    fun `R descriptor list is the single stable action order`() {
        val expectedOrder = listOf(
            StudySessionAction.QUESTION,
            StudySessionAction.HINT,
            StudySessionAction.GRADING,
            StudySessionAction.EXPLANATION,
            StudySessionAction.NEXT_QUESTION,
        )
        assertEquals(expectedOrder, STUDY_MODE_ACTION_OPTIONS.map { it.action })
        // END stays out of the prompt enum and last in presentation.
        assertEquals("일반 모드로", STUDY_MODE_END_LABEL)
        assertFalse(STUDY_MODE_ACTION_OPTIONS.map { it.label }.contains(STUDY_MODE_END_LABEL))
        assertTrue(StudySessionAction.entries.none { it.name == "END" })
        // Semantic identity is the enum, independent of the localized label.
        assertEquals(
            StudySessionAction.entries.toSet(),
            STUDY_MODE_ACTION_OPTIONS.map { it.action }.toSet(),
        )
    }

    @Test
    fun `S descriptor labels are unique`() {
        val labels = STUDY_MODE_ACTION_OPTIONS.map { it.label }
        assertEquals(
            listOf("문제 요청", "힌트", "채점 요청", "답·해설", "다음 문제"),
            labels,
        )
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `T semantic ids are not derived from labels`() {
        for (option in STUDY_MODE_ACTION_OPTIONS) {
            assertFalse(option.action.name == option.label)
            // Labels are localized Korean; ids are stable enum names.
            assertTrue(option.label.any { it.code > 127 })
            assertTrue(option.action.name.all { it.isUpperCase() || it == '_' })
        }
    }

    @Test
    fun `helper copy is general without document terminology`() {
        assertEquals("질문을 풀고, 막히면 힌트를 요청하거나 답을 검토해 보세요.", STUDY_MODE_HELPER_COPY)
        assertFalse(STUDY_MODE_HELPER_COPY.contains("문서"))
        assertFalse(STUDY_MODE_HELPER_COPY.contains("정확"))
        assertFalse(STUDY_MODE_HELPER_COPY.contains("보장"))
        assertFalse(STUDY_MODE_HELPER_COPY.contains("검증"))
    }

    @Test
    fun `A no Study prompt mandates a reference document`() {
        for (prompt in StudySessionPromptPolicy.allPrompts) {
            assertFalse(prompt.contains("참고 문서"))
            assertFalse(prompt.contains("검증됨"))
            assertFalse(prompt.contains("보장"))
        }
        // QUESTION and NEXT work from an empty chat: they clarify the topic
        // instead of assuming material.
        assertFalse(StudySessionPromptPolicy.initialQuestionPrompt.contains("참고 자료"))
        assertFalse(StudySessionPromptPolicy.nextQuestionPrompt.contains("참고 자료"))
        assertFalse(StudySessionPromptPolicy.nextQuestionPrompt.contains("참고 문서"))
    }

    @Test
    fun `G no prompt promises a verified or guaranteed answer`() {
        val banned = listOf("보장", "검증됨", "검증된", "정확한 채점", "정답 보장", "반드시 맞는", "확실한 정답")
        for (prompt in StudySessionPromptPolicy.allPrompts) {
            for (phrase in banned) {
                assertFalse("'$phrase' in: $prompt", prompt.contains(phrase))
            }
        }
    }

    @Test
    fun `wrappers delegate to generic path with identical prompts`() = runTest {
        val composer = FakeComposer()
        val state = study()
        val current = scope()
        val results = listOf(
            StudySessionFlow.requestQuestion(state, current, true, composer::mutate),
            StudySessionFlow.requestHint(state, current, true, composer::mutate),
            StudySessionFlow.requestGrading(state, current, true, composer::mutate),
            StudySessionFlow.requestExplanation(state, current, true, composer::mutate),
            StudySessionFlow.requestNextQuestion(state, current, true, composer::mutate),
        )
        assertTrue(results.all { it.outcome == StudyPromptRequestOutcome.Appended })
        assertEquals(
            listOf(
                7L to StudySessionPromptPolicy.initialQuestionPrompt,
                7L to StudySessionPromptPolicy.hintPrompt,
                7L to StudySessionPromptPolicy.gradingPrompt,
                7L to StudySessionPromptPolicy.explanationPrompt,
                7L to StudySessionPromptPolicy.nextQuestionPrompt,
            ),
            composer.mutations,
        )
    }

    @Test
    fun `flow exposes no send or generation entry point`() {
        val methods = (StudySessionFlow.javaClass.declaredMethods +
            StudySessionFlow.javaClass.methods)
            .filter { it.declaringClass != Any::class.java }
        for (method in methods) {
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
