package com.projectnuke.fusion.chat

enum class BranchSynthesisIntent(val label: String) {
    OVERVIEW("전체 종합"),
    COMMON_CONCLUSIONS("공통 결론"),
    DIFFERENCES("차이점"),
    OPEN_QUESTIONS("미해결 쟁점"),
    NEXT_STEPS("다음 단계"),
}

data class BranchSynthesisRequest(
    val selection: SelectionContextSnapshot,
    val intent: BranchSynthesisIntent,
    val composerText: String,
)

sealed interface BranchSynthesisRequestPreparation {
    data class Ready(val request: BranchSynthesisRequest) : BranchSynthesisRequestPreparation
    data object InvalidSelection : BranchSynthesisRequestPreparation
}

object BranchSynthesisRequestPolicy {
    const val MAX_SOURCES = 4
    const val MAX_COMPOSER_CHARS = 2_400

    fun prepare(selection: SelectionContextSnapshot): BranchSynthesisRequestPreparation {
        val intent = selection.branchSynthesisIntent
        if (selection.purpose != SelectionContextPurpose.BRANCH_SYNTHESIS ||
            intent == null || selection.sources.size !in 2..MAX_SOURCES ||
            selection.sources.any { it.identity.kind != SelectionSourceKind.CONVERSATION }
        ) return BranchSynthesisRequestPreparation.InvalidSelection

        val text = buildString {
            appendLine("선택한 대화 갈래의 저장된 요약으로 새 대화를 준비해 주세요.")
            appendLine("의도: ${intent.label}")
            selection.sources.forEachIndexed { index, source ->
                appendLine("갈래 ${index + 1}: ${source.title}")
            }
            appendLine("위 자료만 사용하고 각 핵심 내용을 해당 갈래 번호와 제목으로 표시하세요.")
            append("원본 대화는 변경하지 마세요. 전체 대화 기록이나 다른 프로젝트 자료, 메모리, 외부 검색을 추가하지 마세요.")
        }
        if (text.length > MAX_COMPOSER_CHARS) return BranchSynthesisRequestPreparation.InvalidSelection
        return BranchSynthesisRequestPreparation.Ready(BranchSynthesisRequest(selection, intent, text))
    }
}

sealed interface BranchSynthesisContextLoadResult {
    data class Ready(val selection: SelectionContextSnapshot) : BranchSynthesisContextLoadResult
    data object InvalidSelection : BranchSynthesisContextLoadResult
    data object SourceUnavailable : BranchSynthesisContextLoadResult
    data object AccessDenied : BranchSynthesisContextLoadResult
    data object SummaryUnavailable : BranchSynthesisContextLoadResult
    data object LookupFailed : BranchSynthesisContextLoadResult
}

internal class BranchSynthesisSourceAdmissionException : Exception()
