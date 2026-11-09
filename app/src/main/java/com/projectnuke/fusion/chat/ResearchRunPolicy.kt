package com.projectnuke.fusion.chat

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException

internal object ResearchRunPolicy {
    const val TOTAL_DEADLINE_MS = 180_000L
    const val MAX_WEB_SOURCES = 6
    const val MAX_AUDIT_CLAIMS = 4
    const val AUDIT_DEADLINE_MS = 35_000L
    suspend fun execute(mode: ChatInteractionMode, deadlineMs: Long = TOTAL_DEADLINE_MS, block: suspend () -> Unit): Boolean {
        if (mode != ChatInteractionMode.RESEARCH) { block(); return true }
        require(deadlineMs > 0)
        // Keep the original failure outside coroutine stack-trace recovery.
        // This catch performs no recovery: every non-deadline failure is rethrown.
        var terminalFailure: Throwable? = null
        val complete = withTimeoutOrNull(deadlineMs) {
            try { block(); true } catch (failure: Throwable) { terminalFailure = failure; true }
        }
        val failure = terminalFailure
        if (complete == null && failure is TimeoutCancellationException) return false
        if (failure != null) throw failure
        return complete == true
    }
    fun modelInstruction(mode: ChatInteractionMode): String = if (mode != ChatInteractionMode.RESEARCH) "" else
        "현재 요청은 조사 모드입니다. 제공된 대화·프로젝트 자료와 명시적으로 허용된 검색 자료를 종합해 질문에 답하세요. 출처가 뒷받침하는 사실, 추론, 확인하지 못한 내용을 구분하세요. 자료가 부족하면 그 한계를 짧게 밝히세요. 출처를 꾸며내거나 모든 문장이 검증되었다고 말하지 마세요. 숨겨진 사고 과정은 공개하지 마세요."
    fun auditLabel(totalClaims: Int, verifiedClaims: Int): String = when {
        totalClaims <= 0 || verifiedClaims <= 0 -> "의미 검증 미실행"
        verifiedClaims < totalClaims -> "일부 주장 검증"
        else -> "일부 주장 검증"
    }
    fun stage(raw: String?): String = when {
        raw == null -> "자료 준비 중"
        raw.contains("검색") && !raw.contains("정리") -> "검색 중"
        raw.contains("근거") -> "근거 확인 중"
        raw.contains("정리") || raw.contains("준비") || raw.contains("압축") || raw.contains("처리") -> "자료 정리 중"
        else -> "답변 작성 중"
    }
}
