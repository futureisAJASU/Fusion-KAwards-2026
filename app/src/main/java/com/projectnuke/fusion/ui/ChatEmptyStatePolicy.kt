package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.ChatInteractionMode

/** Host-testable copy and visibility rules for an empty conversation body. */
internal data class ChatEmptyStateCopy(
    val title: String,
    val supportingText: String,
    val secondaryText: String? = null,
)

internal object ChatEmptyStatePolicy {
    val normal = ChatEmptyStateCopy(
        title = "Fusion",
        supportingText = "새 채팅을 시작해 보세요.",
        secondaryText = "모델과 모드는 위쪽 칩에서 바꿀 수 있습니다.",
    )

    val study = ChatEmptyStateCopy(
        title = "무엇을 공부할까요?",
        supportingText = "개념을 물어보거나, 문제를 풀거나, 이해가 어려운 부분부터 시작해 보세요.",
        secondaryText = "학습 모드는 질문과 힌트를 활용해 단계적으로 도와줍니다.",
    )

    fun shouldShowStudyState(
        interactionMode: ChatInteractionMode,
        visibleMessageCount: Int,
        isGenerating: Boolean,
    ): Boolean = interactionMode == ChatInteractionMode.STUDY &&
        visibleMessageCount == 0 &&
        !isGenerating

    fun copyFor(
        interactionMode: ChatInteractionMode,
        visibleMessageCount: Int,
        isGenerating: Boolean,
    ): ChatEmptyStateCopy =
        if (shouldShowStudyState(interactionMode, visibleMessageCount, isGenerating)) study else normal
}
