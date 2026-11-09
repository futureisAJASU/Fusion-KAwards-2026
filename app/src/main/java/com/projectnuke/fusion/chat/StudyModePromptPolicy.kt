package com.projectnuke.fusion.chat

/**
 * Request-time model behavior for the dedicated Study interaction mode.
 *
 * The returned instruction is host-authored generic pedagogy: it carries no
 * private user data, so external-provider paths need no extra consent for
 * it (the exact transport payload is still hashed/armed by DataEgress as
 * today). It never requests hidden chain-of-thought.
 *
 * Android-free and host-testable.
 */
internal object StudyModePromptPolicy {
    const val MAX_INSTRUCTION_CHARS = 2_000

    private val STUDY_INSTRUCTION =
        """
        현재 요청은 학습 모드입니다.

        사용자가 스스로 이해하고 문제를 풀 수 있도록 단계적으로 돕습니다.

        * 사용자가 개념 설명을 요청하면 핵심 개념을 구조적으로 설명하고, 필요할 때 예시나 이해 확인 질문을 활용합니다.
        * 사용자가 문제를 풀거나 답을 시도하는 상황에서는 사용자가 답·해설을 명시적으로 요청하지 않는 한 정답 전체를 먼저 공개하지 않습니다.
        * 힌트를 요청하면 답을 직접 공개하기보다 다음 사고 단계에 도움이 되는 힌트를 우선합니다.
        * 채점을 요청하면 사용자의 답에서 맞은 점, 틀린 점, 불확실한 점을 구분해서 설명합니다.
        * 대화나 참고 자료가 제공되어 있으면 이를 우선 활용합니다.
        * 참고 자료만으로 확인할 수 없는 내용은 추측해 확정하지 않습니다.
        * 사용자가 답이나 해설을 명시적으로 요청하면 답과 이유를 설명할 수 있습니다.
        * 학습 모드는 사용자의 현재 명시적 요청을 무시하는 규칙이 아닙니다.
        """.trimIndent()

    init {
        check(STUDY_INSTRUCTION.length <= MAX_INSTRUCTION_CHARS) {
            "Study Mode instruction exceeds prompt budget guard"
        }
    }

    /**
     * Bounded host-authored instruction for [mode]. NORMAL returns "" so
     * existing prompts and budgets are byte-identical when Study Mode is
     * off.
     */
    fun modelInstruction(mode: ChatInteractionMode): String =
        when (mode) {
            ChatInteractionMode.NORMAL, ChatInteractionMode.RESEARCH -> ""
            ChatInteractionMode.STUDY -> STUDY_INSTRUCTION
        }
}
