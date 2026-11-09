package com.projectnuke.fusion.chat

/**
 * Dedicated Study Mode prompts, presentation tools and tap flow.
 *
 * Study Mode is an explicit [ChatInteractionMode] over the SAME chat
 * conversation, not a second generation pipeline: every action only appends
 * an ordinary prompt to the existing composer through the durable draft seam.
 * Nothing auto-sends, nothing is parsed or scored, and no retrieval,
 * embedding, PDF parsing, web request or generation runs when a Study control
 * is tapped. Retrieval happens only when the user explicitly presses normal
 * Send.
 *
 * Android-free and host-testable. The flow object takes an acknowledged
 * suspend append callback (backed by the durable draft seam) and has no
 * Send/submission/generation entry point by construction. Entering STUDY mode
 * itself (see [ChatInteractionModePolicy.switch]) appends nothing.
 */
internal enum class StudySessionAction {
    QUESTION,
    HINT,
    GRADING,
    EXPLANATION,
    NEXT_QUESTION,
}

/**
 * Context-neutral Study prompts: dedicated Study Mode works with no
 * attachment, an ordinary conversation topic, project context, local
 * documents, web-backed discussion, or coding/math/science learning. Every
 * prompt prefers available conversation/reference material when present but
 * never requires a document and never invents a topic when none is clear.
 */
internal object StudySessionPromptPolicy {
    const val MAX_PROMPT_CHARS = 500

    val initialQuestionPrompt =
        "현재 대화의 학습 주제와 제공된 자료가 있다면 그 내용을 바탕으로 핵심 이해를 확인할 질문을 하나만 내 주세요. 아직 답과 해설은 말하지 마세요. 학습 주제가 분명하지 않다면 임의의 문제를 만들지 말고 무엇을 공부할지 짧게 물어보세요."

    val gradingPrompt =
        "내 답을 현재 문제와 이용 가능한 대화·참고 자료에 비추어 검토해 주세요. 맞은 점, 틀린 점, 불확실한 점을 구분하고, 틀렸다면 이유와 더 적절한 설명을 알려 주세요. 확인할 근거가 부족한 부분은 '확인 필요'로 표시해 주세요."

    val nextQuestionPrompt =
        "앞에서 다룬 문제와 겹치지 않도록 현재 학습 주제에서 다른 핵심 내용을 골라 질문을 하나만 내 주세요. 아직 답과 해설은 말하지 마세요. 학습 주제가 충분히 분명하지 않다면 먼저 무엇을 공부할지 확인해 주세요."

    val hintPrompt =
        "현재 문제의 답을 직접 말하지 말고, 스스로 풀 수 있도록 다음 사고 단계에 도움이 되는 핵심 힌트 하나만 주세요. 대화나 참고 자료가 있다면 그 범위 안에서 우선 힌트를 주세요."

    val explanationPrompt =
        "현재 문제의 답과 해설을 보여 주세요. 대화나 참고 자료가 있다면 이를 우선 근거로 사용하고, 왜 그런지 이해할 수 있게 설명해 주세요. 확인할 수 없는 부분은 '확인 필요'로 표시해 주세요."

    fun promptFor(action: StudySessionAction): String =
        when (action) {
            StudySessionAction.QUESTION -> initialQuestionPrompt
            StudySessionAction.HINT -> hintPrompt
            StudySessionAction.GRADING -> gradingPrompt
            StudySessionAction.EXPLANATION -> explanationPrompt
            StudySessionAction.NEXT_QUESTION -> nextQuestionPrompt
        }

    val allPrompts: List<String> = listOf(
        initialQuestionPrompt,
        hintPrompt,
        gradingPrompt,
        explanationPrompt,
        nextQuestionPrompt,
    )
}

/**
 * Pure descriptor for one Study prompt chip: stable semantic id + visible
 * label. This list is the single authority for Study action order and
 * labels; the panel iterates it instead of hardcoding them again.
 */
internal data class StudyModeActionOption(
    val action: StudySessionAction,
    val label: String,
)

/** Stable Study Mode panel order for the five prompt actions. Exit stays last and separate. */
internal val STUDY_MODE_ACTION_OPTIONS: List<StudyModeActionOption> = listOf(
    StudyModeActionOption(StudySessionAction.QUESTION, "문제 요청"),
    StudyModeActionOption(StudySessionAction.HINT, "힌트"),
    StudyModeActionOption(StudySessionAction.GRADING, "채점 요청"),
    StudyModeActionOption(StudySessionAction.EXPLANATION, "답·해설"),
    StudyModeActionOption(StudySessionAction.NEXT_QUESTION, "다음 문제"),
)

internal const val STUDY_MODE_END_LABEL = "일반 모드로"

internal const val STUDY_MODE_HELPER_COPY = "질문을 풀고, 막히면 힌트를 요청하거나 답을 검토해 보세요."

/**
 * Typed outcome for a Study prompt request, so the UI never shows a
 * persistence-failure notice when no append was even attempted:
 * - [Rejected]: stale scope, invisible mode, or disabled controls. Zero
 *   append callback. Silent.
 * - [AppendFailed]: durable append attempted but reported failure. Bounded
 *   failure notice.
 * - [Appended]: durable append succeeded. Silent.
 *
 * Cancellation propagates without conversion to any outcome.
 */
internal sealed interface StudyPromptRequestOutcome {
    data object Rejected : StudyPromptRequestOutcome
    data object AppendFailed : StudyPromptRequestOutcome
    data object Appended : StudyPromptRequestOutcome
}

internal data class StudyPromptRequestResult(
    val modeState: ChatInteractionModeState,
    val outcome: StudyPromptRequestOutcome,
)

/**
 * Pure mapping from a Study prompt outcome to failure-notice visibility.
 * Only [StudyPromptRequestOutcome.AppendFailed] may show the bounded
 * failure notice; [StudyPromptRequestOutcome.Rejected] must stay silent
 * because no append was attempted.
 */
internal object StudyPromptFailureNoticePolicy {
    fun shouldShowFailureNotice(outcome: StudyPromptRequestOutcome): Boolean =
        outcome == StudyPromptRequestOutcome.AppendFailed
}

internal object StudySessionFlow {
    /**
     * Generic Study prompt request. The prompt text is owned here via
     * [StudySessionPromptPolicy.promptFor]; the acknowledged append result is
     * owned here as [StudyPromptRequestResult.outcome] so callers never
     * infer success by mutating a captured Boolean inside the callback and
     * never mistake a rejection for a persistence failure.
     *
     * The mode itself is never mutated here: entering/exiting STUDY is owned
     * by [ChatInteractionModePolicy.switch]. Cancellation propagates without
     * conversion to a failure outcome.
     */
    suspend fun request(
        action: StudySessionAction,
        modeState: ChatInteractionModeState,
        scope: ChatInteractionModeScope,
        controlsEnabled: Boolean,
        appendPrompt: suspend (conversationId: Long, text: String) -> Boolean,
    ): StudyPromptRequestResult {
        if (!controlsEnabled || !ChatInteractionModePolicy.isStudyMode(modeState, scope)) {
            return StudyPromptRequestResult(modeState, StudyPromptRequestOutcome.Rejected)
        }
        val appended =
            appendPrompt(scope.conversationId, StudySessionPromptPolicy.promptFor(action))
        return StudyPromptRequestResult(
            modeState,
            if (appended) StudyPromptRequestOutcome.Appended else StudyPromptRequestOutcome.AppendFailed,
        )
    }

    suspend fun requestQuestion(
        modeState: ChatInteractionModeState,
        scope: ChatInteractionModeScope,
        controlsEnabled: Boolean,
        appendPrompt: suspend (conversationId: Long, text: String) -> Boolean,
    ): StudyPromptRequestResult =
        request(StudySessionAction.QUESTION, modeState, scope, controlsEnabled, appendPrompt)

    suspend fun requestHint(
        modeState: ChatInteractionModeState,
        scope: ChatInteractionModeScope,
        controlsEnabled: Boolean,
        appendPrompt: suspend (conversationId: Long, text: String) -> Boolean,
    ): StudyPromptRequestResult =
        request(StudySessionAction.HINT, modeState, scope, controlsEnabled, appendPrompt)

    suspend fun requestGrading(
        modeState: ChatInteractionModeState,
        scope: ChatInteractionModeScope,
        controlsEnabled: Boolean,
        appendPrompt: suspend (conversationId: Long, text: String) -> Boolean,
    ): StudyPromptRequestResult =
        request(StudySessionAction.GRADING, modeState, scope, controlsEnabled, appendPrompt)

    suspend fun requestExplanation(
        modeState: ChatInteractionModeState,
        scope: ChatInteractionModeScope,
        controlsEnabled: Boolean,
        appendPrompt: suspend (conversationId: Long, text: String) -> Boolean,
    ): StudyPromptRequestResult =
        request(StudySessionAction.EXPLANATION, modeState, scope, controlsEnabled, appendPrompt)

    suspend fun requestNextQuestion(
        modeState: ChatInteractionModeState,
        scope: ChatInteractionModeScope,
        controlsEnabled: Boolean,
        appendPrompt: suspend (conversationId: Long, text: String) -> Boolean,
    ): StudyPromptRequestResult =
        request(StudySessionAction.NEXT_QUESTION, modeState, scope, controlsEnabled, appendPrompt)

    fun controlsEnabled(
        isGenerating: Boolean,
        manualCompactionActive: Boolean,
        submissionActive: Boolean,
        attachmentImportActive: Boolean,
        deletionActive: Boolean,
    ): Boolean =
        !isGenerating && !manualCompactionActive && !submissionActive &&
            !attachmentImportActive && !deletionActive

    /**
     * Grading needs something to grade: the common controls must be
     * available, the current composer must hold non-blank user text, and no
     * F2/Study prompt action may be in flight. Never judges answer quality or
     * length beyond blankness.
     */
    fun gradingEnabled(
        controlsEnabled: Boolean,
        composerRawInput: String,
        promptActionInFlight: Boolean,
    ): Boolean =
        controlsEnabled && composerRawInput.isNotBlank() && !promptActionInFlight

    /**
     * Reveal-answer behavior: enabled only when the composer holds no unsent
     * user text, so an unsent answer is never silently mixed with a
     * reference-answer request. With a partial answer the user should use
     * grading, clear the draft, then reveal. Hint stays on the common gate
     * because a partial attempt plus a hint is an ordinary transparent
     * append.
     */
    fun explanationEnabled(
        controlsEnabled: Boolean,
        composerRawInput: String,
        promptActionInFlight: Boolean,
    ): Boolean =
        controlsEnabled && composerRawInput.isBlank() && !promptActionInFlight
}

/**
 * Shared composer append semantics for Quick Prompt and Study flows.
 * Blank composer becomes the prompt; existing drafts are extended
 * non-destructively. This is the exact behavior ChatScreen's draft seam
 * applies; kept pure and host-testable here.
 */
internal fun appendPromptToDraft(currentInput: String, prompt: String): String {
    val trimmed = currentInput.trimEnd()
    if (trimmed.isEmpty()) return prompt
    val separator =
        if (trimmed.endsWith(".") || trimmed.endsWith("?") || trimmed.endsWith("!")) "\n" else " "
    return trimmed + separator + prompt
}
