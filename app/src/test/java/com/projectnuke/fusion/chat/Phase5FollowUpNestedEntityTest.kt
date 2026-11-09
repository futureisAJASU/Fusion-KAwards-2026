package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 adversarial tests: a loaded follow-up premise may not authenticate a
 * nested/adjacent named entity. Exact identity, not raw prefix containment,
 * grounds entity-like stems; ordinary Korean agglutination is unchanged.
 */
class Phase5FollowUpNestedEntityTest {

    private fun kept(question: String, answer: String): List<String> =
        DynamicFollowUpPolicy.filterSupported(listOf(question), "", answer)

    @Test
    fun `B1 nested Hangul entity cannot be authenticated by a longer entity`() {
        val question = "삼성전자와 삼성전자서비스 중 매출이 더 큰 곳은?"
        val answer = "삼성전자서비스의 매출이 더 크다."
        assertTrue(
            "the answer never named 삼성전자 as its own entity, kept=${kept(question, answer)}",
            kept(question, answer).isEmpty(),
        )
    }

    @Test
    fun `B2 adjacent alphanumeric entity is not grounded by its longer sibling`() {
        val question = "P1와 P10 중 가격이 더 높은 것은?"
        val answer = "P10의 가격이 더 높다."
        assertTrue(
            "P1 must not bind inside P10, kept=${kept(question, answer)}",
            kept(question, answer).isEmpty(),
        )
    }

    @Test
    fun `B3 model suffix is not grounded by a longer model name`() {
        val question = "S24와 S24FE 중 가격이 더 높은 것은?"
        val answer = "S24FE의 가격이 더 높다."
        assertTrue(
            "S24 must not bind inside S24FE, kept=${kept(question, answer)}",
            kept(question, answer).isEmpty(),
        )
    }

    @Test
    fun `B3 numeric model suffix is not grounded by a longer model name`() {
        val question = "A1와 A10 중 성능이 더 좋은 것은?"
        val answer = "A10의 성능이 더 좋다."
        assertTrue(
            "A1 must not bind inside A10, kept=${kept(question, answer)}",
            kept(question, answer).isEmpty(),
        )
    }

    @Test
    fun `B3 ordinal model suffix is not grounded by a longer model name`() {
        val question = "Model 3와 Model 30 중 가격이 더 높은 것은?"
        val answer = "Model 30의 가격이 더 높다."
        assertTrue(
            "Model 3 must not bind inside Model 30, kept=${kept(question, answer)}",
            kept(question, answer).isEmpty(),
        )
    }

    @Test
    fun `B3 Roman numeral entity is not grounded by its longer sibling`() {
        val question = "Artemis II가 Artemis III보다 빠른가요?"
        val answer = "Artemis III가 더 빠릅니다."
        assertTrue(
            "Artemis II must not bind inside Artemis III, kept=${kept(question, answer)}",
            kept(question, answer).isEmpty(),
        )
    }

    @Test
    fun `B4 ordinary Korean agglutination still grounds`() {
        val question = "사용 방법이 빠른 이유는?"
        val answer = "사용 방법이 빠릅니다."
        assertEquals(
            "normal conjugation must keep grounding",
            listOf(question),
            kept(question, answer),
        )
    }

    @Test
    fun `B4 particle-bearing entity still grounds`() {
        val question = "삼성전자의 매출이 더 큰 이유는?"
        val answer = "삼성전자는 매출이 더 큽니다."
        assertEquals(
            "a trailing particle must not break exact entity grounding",
            listOf(question),
            kept(question, answer),
        )
    }

    @Test
    fun `B5 both explicit entities in one clause still ground the comparison`() {
        val question = "삼성전자와 삼성전자서비스 중 매출이 더 큰 곳은?"
        val answer = "삼성전자와 삼성전자서비스의 매출이 더 크다."
        assertEquals(
            "explicitly named entities remain eligible",
            listOf(question),
            kept(question, answer),
        )
    }
}
