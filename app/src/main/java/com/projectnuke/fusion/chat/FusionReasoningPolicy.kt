package com.projectnuke.fusion.chat

/**
 * LiteRT-LM 0.14.0 has no native per-request reasoning/thinking budget.
 * This is therefore a semantic prompt policy, not a hard token or compute cap.
 */
internal fun fusionReasoningBudgetInstruction(
    reasoningEnabled: Boolean,
    reasoningBudgetTokens: Int,
): String = if (reasoningEnabled) {
    """
    Fusion 추론 예산(권장): 내부 <fusion_thinking> 내용은 약 $reasoningBudgetTokens 토큰 이내로 간결하게 유지하십시오. 이 값은 모델에 전달되는 권장 예산이며 하드 토큰 제한이나 추론 계산량 제한이 아닙니다. 전체 출력의 하드 제한은 별도의 maxOutputTokens 설정입니다.
    """.trimIndent()
} else {
    ""
}
