package com.projectnuke.fusion.chat

import com.projectnuke.fusion.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Corrective coverage for dynamic semantic follow-ups v1: bounded partial
 * composition, fail-closed hidden-residue input, and the unresolved-origin
 * gate. Existing DynamicFollowUpPolicyTest coverage is untouched.
 */
class DynamicFollowUpCorrectiveTest {
    private fun det(id: String, label: String, prompt: String) = NextActionSuggestion(
        id = id,
        kind = NextActionKind.EXAMPLE,
        label = label,
        target = NextActionTarget.ComposerPrompt(prompt),
    )

    private val deterministic = listOf(
        det("det_0", "핵심만 정리", "방금 답변의 핵심만 짧게 다시 정리해 주세요."),
        det("det_1", "예시로 설명", "방금 설명한 내용을 구체적인 예시를 들어 다시 설명해 주세요."),
        det("det_2", "반대 관점 보기", "방금 답변과 다른 관점이나 반론도 함께 설명해 주세요."),
    )

    // Partial composition: dynamic first, deterministic fill, max 3. --------
    @Test
    fun `zero dynamic leaves the deterministic row unchanged`() {
        assertEquals(
            deterministic,
            DynamicFollowUpPolicy.composeRow(emptyList(), deterministic),
        )
    }

    @Test
    fun `three dynamic show exactly the dynamic three`() {
        val dynamic = listOf("첫 질문이야?", "두 번째야?", "세 번째야?")
        val composed = DynamicFollowUpPolicy.composeRow(dynamic, deterministic)
        assertEquals(3, composed.size)
        assertEquals(dynamic, composed.map { it.promptText })
        assertTrue(composed.all { it.kind == NextActionKind.DYNAMIC_FOLLOW_UP })
    }

    @Test
    fun `two dynamic add at most one deterministic`() {
        val composed = DynamicFollowUpPolicy.composeRow(
            listOf("첫 질문이야?", "두 번째야?"),
            deterministic,
        )
        assertEquals(3, composed.size)
        assertEquals("첫 질문이야?", composed[0].promptText)
        assertEquals("두 번째야?", composed[1].promptText)
        assertEquals("det_0", composed[2].id)
    }

    @Test
    fun `one dynamic fills up to three total`() {
        val composed = DynamicFollowUpPolicy.composeRow(
            listOf("첫 질문이야?"),
            deterministic,
        )
        assertEquals(3, composed.size)
        assertEquals("첫 질문이야?", composed[0].promptText)
        assertEquals("det_0", composed[1].id)
        assertEquals("det_1", composed[2].id)
    }

    @Test
    fun `duplicate dynamic static targets are removed from the fill`() {
        val composed = DynamicFollowUpPolicy.composeRow(
            listOf("핵심만 정리?"),
            deterministic,
        )
        assertEquals(3, composed.size)
        assertEquals("핵심만 정리?", composed[0].promptText)
        // det_0 shares its normalized text with the dynamic question, so
        // the fill skips ahead instead of duplicating it.
        assertEquals("det_1", composed[1].id)
        assertEquals("det_2", composed[2].id)
    }

    @Test
    fun `duplicate dynamic static prompts are removed from the fill`() {
        val dupe = det(
            "det_dupe",
            "다른 라벨",
            "첫 질문이야?",
        )
        val composed = DynamicFollowUpPolicy.composeRow(
            listOf("첫 질문이야?"),
            listOf(dupe) + deterministic,
        )
        assertEquals(3, composed.size)
        assertEquals("첫 질문이야?", composed[0].promptText)
        assertEquals("det_0", composed[1].id)
        assertEquals("det_1", composed[2].id)
    }

    @Test
    fun `rejected deterministic candidate does not pollute the seen labels`() {
        // rejectedA is rejected ONLY at the late prompt-duplicate predicate
        // (its prompt normalizes to the shown dynamic question) while its
        // id/target/label/"공통 라벨" are otherwise unique; under the old
        // mutate-while-deciding fill that label would suppress validB below.
        val rejectedA = det("det_rejected_a", "공통 라벨", "첫 질문이야!")
        val validB = det("det_rejected_b", "공통 라벨", "완전히 다른 유효 프롬프트")
        val composed = DynamicFollowUpPolicy.composeRow(
            listOf("첫 질문이야?"),
            listOf(rejectedA, validB),
        )
        // rejectedA never entered the presented row; B with the same label
        // must remain eligible because it was never a presented duplicate.
        assertEquals(2, composed.size)
        assertEquals("첫 질문이야?", composed[0].promptText)
        assertTrue(composed.any { it.id == "det_rejected_b" })
        assertEquals("det_rejected_b", composed[1].id)
    }

    @Test
    fun `rejected candidate does not prevent filling to the max cap`() {
        val rejectedA = det("det_rejected_a", "공통 라벨", "첫 질문이야!")
        val validB = det("det_rejected_b", "공통 라벨", "완전히 다른 유효 프롬프트")
        val validC = det("det_rejected_c", "세 번째 라벨", "완전히 다른 두 번째 프롬프트")
        val composed = DynamicFollowUpPolicy.composeRow(
            listOf("첫 질문이야?"),
            listOf(rejectedA, validB, validC),
        )
        assertEquals(3, composed.size)
        assertEquals(listOf("det_rejected_b", "det_rejected_c"), composed.drop(1).map { it.id })
    }

    // Normalized-prompt dedup: a presented normalized label OR prompt blocks
    // a later candidate whose label or prompt normalizes to the same text.

    @Test
    fun `presented prompt identity blocks a later duplicate label`() {
        val a = det("det_a", "자세히 보기", "캐시가 뭐야?")
        val b = det("det_b", "캐시가 뭐야?", "완전히 다른 질문")
        val c = det("det_c", "세 번째 라벨", "세 번째 프롬프트")
        val composed = DynamicFollowUpPolicy.composeRow(
            listOf("첫 질문이야?"),
            listOf(a, b, c),
        )
        // b's label is the normalized duplicate of a's presented prompt.
        assertEquals(3, composed.size)
        assertFalse(composed.any { it.id == "det_b" })
        assertEquals(listOf("det_a", "det_c"), composed.drop(1).map { it.id })
    }

    @Test
    fun `presented label blocks a later duplicate prompt`() {
        val a = det("det_a", "캐시가 뭐야?", "완전히 다른 질문")
        val b = det("det_b", "자세히 보기", "캐시가 뭐야?")
        val c = det("det_c", "재질문", "다른 프롬프트")
        val composed = DynamicFollowUpPolicy.composeRow(
            listOf("첫 질문이야?"),
            listOf(a, b, c),
        )
        // b's prompt is the normalized duplicate of a's presented label.
        assertEquals(3, composed.size)
        assertFalse(composed.any { it.id == "det_b" })
        assertEquals(listOf("det_a", "det_c"), composed.drop(1).map { it.id })
    }

    // Strict hidden-residue input: fail closed, fallback survives. ----------
    @Test
    fun `malformed attachment envelope residue fails closed`() {
        // Malformed envelopes are intentionally preserved by the general
        // visible-content contract; the follow-up input must refuse them.
        assertNull(
            DynamicFollowUpPolicy.buildMessages(
                "SRAM이 뭐야?",
                "SRAM 설명입니다. <fusion_attachment_v2>incomplete|no-close-tag",
            ),
        )
    }

    @Test
    fun `malformed hidden metadata marker residue fails closed`() {
        assertNull(
            DynamicFollowUpPolicy.buildMessages(
                "SRAM이 뭐야?",
                "내용입니다. [FUSION_TRUST_ROUTE_JSON]garbage-without-close",
            ),
        )
        assertNull(
            DynamicFollowUpPolicy.buildMessages(
                "SRAM이 뭐야?",
                "내용입니다. [FUSION_LOCAL_RAG_SOURCES_JSON]garbage-without-close",
            ),
        )
    }

    @Test
    fun `residue in the user request fails closed`() {
        assertNull(
            DynamicFollowUpPolicy.buildMessages(
                "이 문서 <fusion_attachment>name=x without close",
                "SRAM 설명입니다.",
            ),
        )
    }

    @Test
    fun `no internal path reaches the request when residue fails closed`() {
        val hiddenPath = "/data/data/com.projectnuke.fusion/files/secret.bin"
        assertNull(
            DynamicFollowUpPolicy.buildMessages(
                "SRAM이 뭐야?",
                "본문 <fusion_attachment_v2>doc|text/plain|$hiddenPath",
            ),
        )
    }

    @Test
    fun `ordinary visible text with harmless brackets remains valid`() {
        val messages = DynamicFollowUpPolicy.buildMessages(
            "a < b 일 때 [메모]를 정리해줘?",
            "a < b 이면 [참고] 항목을 보세요.",
        )
        assertNotNull(messages)
        val joined = messages!!.joinToString("\n") { it.content }
        assertTrue(joined.contains("a < b"))
        assertTrue(joined.contains("[메모]"))
    }

    @Test
    fun `visible user typed path without envelope stays valid`() {
        // A path the user visibly typed is user-visible content, not hidden
        // implementation metadata; only machine-reserved residue fails.
        val messages = DynamicFollowUpPolicy.buildMessages(
            "이 경로 /data/user/0/app/files/x.txt 가 뭐야?",
            "해당 경로는 앱 저장소입니다.",
        )
        assertNotNull(messages)
    }

    @Test
    fun `residue failure keeps the deterministic fallback usable`() {
        assertNull(
            DynamicFollowUpPolicy.buildMessages(
                "SRAM이 뭐야?",
                "본문 [FUSION_SEARCH_SOURCES_JSON]broken",
            ),
        )
        val fallback = NextActionSuggestionPolicy.suggestions(
            NextActionContext(
                visibleAnswer = "SRAM 설명입니다.",
                webSourceCount = 0,
                localSourceCount = 0,
                localDocumentCount = 0,
                isProjectConversation = false,
                projectFileCount = 0,
            ),
        )
        assertTrue(fallback.isNotEmpty())
    }

    // Unresolved origin: no dynamic generation. ------------------------------
    private fun msg(id: Long, role: String) = MessageEntity(
        id = id,
        conversationId = 7L,
        role = role,
        content = "content-$id",
        createdAt = id,
    )

    @Test
    fun `synthetic negative user turn id never resolves`() {
        val active = listOf(msg(101L, "user"), msg(102L, "assistant"))
        assertFalse(DynamicFollowUpPolicy.isOriginUserTurnResolved(active, -102L))
    }

    @Test
    fun `missing user turn id never resolves`() {
        val active = listOf(msg(101L, "user"), msg(102L, "assistant"))
        assertFalse(DynamicFollowUpPolicy.isOriginUserTurnResolved(active, 999L))
    }

    @Test
    fun `assistant id as user turn never resolves`() {
        val active = listOf(msg(101L, "user"), msg(102L, "assistant"))
        assertFalse(DynamicFollowUpPolicy.isOriginUserTurnResolved(active, 102L))
    }

    @Test
    fun `live user turn resolves`() {
        val active = listOf(msg(101L, "user"), msg(102L, "assistant"))
        assertTrue(DynamicFollowUpPolicy.isOriginUserTurnResolved(active, 101L))
    }
}
