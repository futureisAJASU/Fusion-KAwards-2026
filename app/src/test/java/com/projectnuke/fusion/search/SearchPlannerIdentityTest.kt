package com.projectnuke.fusion.search

import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPlannerIdentityTest {
    @Test
    fun plannerKeepsDottedVersionIdentityTokens() {
        val qwenPlan = FusionWebSearch.buildSearchPlan(
            userInput = "What is the context length of Qwen2.5?",
            previousUserMessage = null,
        )
        assertTrue(
            "executed query dropped the model version: ${qwenPlan.primaryQuery}",
            qwenPlan.primaryQuery.contains("Qwen2.5"),
        )

        val gptPlan = FusionWebSearch.buildSearchPlan(
            userInput = "Tell me about GPT-5.6 benchmarks",
            previousUserMessage = null,
        )
        assertTrue(
            "executed query dropped the model version: ${gptPlan.primaryQuery}",
            gptPlan.primaryQuery.contains("GPT-5.6"),
        )
    }

    @Test
    fun plannerKeepsRomanNumeralEventIdentity() {
        val plan = FusionWebSearch.buildSearchPlan(
            userInput = "When did Artemis II launch?",
            previousUserMessage = null,
        )
        assertTrue(
            "executed query dropped the mission number: ${plan.primaryQuery}",
            plan.primaryQuery.contains("Artemis II"),
        )
    }
}
