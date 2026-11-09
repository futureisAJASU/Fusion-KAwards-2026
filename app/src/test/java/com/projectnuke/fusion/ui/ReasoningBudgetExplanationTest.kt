package com.projectnuke.fusion.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningBudgetExplanationTest {
    @Test
    fun `reasoning budget explanation states best effort semantics`() {
        val explanation = reasoningBudgetExplanation()

        assertTrue(explanation.contains("모델에 보내는 권장 추론 예산"))
        assertTrue(explanation.contains("실제 추론 길이나 계산량을 강제하지 않습니다"))
        assertTrue(explanation.contains("모델은 더 적게 사용할 수 있으며"))
        assertTrue(explanation.contains("최대 출력 토큰 설정"))
    }
}
