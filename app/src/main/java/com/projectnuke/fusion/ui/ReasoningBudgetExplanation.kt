package com.projectnuke.fusion.ui

internal const val REASONING_BUDGET_TITLE = "추론 예산(권장)"

internal fun reasoningBudgetExplanation(): String =
    "모델에 보내는 권장 추론 예산입니다. 모델은 더 적게 사용할 수 있으며 실제 추론 길이나 계산량을 강제하지 않습니다. 전체 출력은 최대 출력 토큰 설정의 제한을 받습니다."
