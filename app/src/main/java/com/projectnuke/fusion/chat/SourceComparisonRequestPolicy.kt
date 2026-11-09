package com.projectnuke.fusion.chat

data class SourceComparisonRequest(
    val selection: SelectionContextSnapshot,
    val composerText: String,
)

sealed interface SourceComparisonRequestPreparation {
    data class Ready(val request: SourceComparisonRequest) : SourceComparisonRequestPreparation
    data object InvalidSourceCount : SourceComparisonRequestPreparation
}

/** Bounded comparison uses the exact Search selection already installed for Chat. */
object SourceComparisonRequestPolicy {
    const val MIN_SOURCES = 2
    const val MAX_SOURCES = 4
    val COMPOSER_TEXT = """
        선택한 자료만 비교해 주세요. 다른 대화, 프로젝트 자료, 메모리나 외부 검색을 추가하지 마세요.
        다음 항목을 구분해 간결하게 정리해 주세요.
        - 공통점
        - 차이점
        - 충돌하는 주장
        - 각 자료의 근거

        중요한 주장마다 [자료 번호: 제목]을 표시하세요. 같은 사실 질문에 대한 서로 다른 주장이 발췌문에 있을 때만 충돌이라고 부르세요. 표현이나 강조가 다른 것만으로 충돌이라고 하지 마세요. 자료 중 하나를 승자로 정하지 마세요.
    """.trimIndent()

    fun prepare(selection: SelectionContextSnapshot): SourceComparisonRequestPreparation {
        if (selection.sources.size !in MIN_SOURCES..MAX_SOURCES) {
            return SourceComparisonRequestPreparation.InvalidSourceCount
        }
        return SourceComparisonRequestPreparation.Ready(
            SourceComparisonRequest(selection, COMPOSER_TEXT),
        )
    }
}
