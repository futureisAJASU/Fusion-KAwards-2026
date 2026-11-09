package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth/premise closure for dynamic semantic follow-ups v1.
 *
 * The parser accepts question-shaped lines; this validator additionally
 * rejects questions that assert an unsupported factual comparison, causal
 * claim or superiority/inferiority premise. Neutral exploratory questions
 * about adjacent concepts stay eligible, as do relations the visible source
 * explicitly supports.
 */
class DynamicFollowUpPremiseTest {
    private val request = "SRAM과 DRAM의 차이가 뭐야?"
    private val answer =
        "SRAM은 속도가 빠르고 DRAM은 집적도가 높습니다. " +
            "SRAM은 주로 캐시 메모리에, DRAM은 주기억장치에 사용됩니다."

    private fun supported(vararg questions: String): List<String> =
        DynamicFollowUpPolicy.filterSupported(questions.toList(), request, answer)

    @Test
    fun `unsupported comparative causal question is rejected`() {
        assertTrue(supported("eDRAM이 SRAM보다 빠른 이유는?").isEmpty())
    }

    @Test
    fun `unsupported superiority question is rejected`() {
        assertTrue(supported("DRAM이 SRAM보다 전력 효율이 좋은 이유는?").isEmpty())
    }

    @Test
    fun `unsupported replacement assertion is rejected`() {
        assertTrue(supported("eDRAM은 SRAM을 대체할까?").isEmpty())
    }

    @Test
    fun `unsupported english comparative question is rejected`() {
        val englishAnswer = "SRAM is fast and used for caches. DRAM is dense and used for main memory."
        val rejected = DynamicFollowUpPolicy.filterSupported(
            listOf("Why is eDRAM faster than SRAM?"),
            "What is the difference between SRAM and DRAM?",
            englishAnswer,
        )
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun `neutral unseen adjacent concept is accepted`() {
        assertEquals(
            listOf("eDRAM은 무엇인가요?"),
            supported("eDRAM은 무엇인가요?"),
        )
    }

    @Test
    fun `neutral relation question is accepted`() {
        assertEquals(
            listOf("eDRAM은 SRAM·DRAM과 어떤 관계가 있나요?"),
            supported("eDRAM은 SRAM·DRAM과 어떤 관계가 있나요?"),
        )
    }

    @Test
    fun `neutral english adjacent concept is accepted`() {
        val accepted = DynamicFollowUpPolicy.filterSupported(
            listOf("What is eDRAM?"),
            "What is the difference between SRAM and DRAM?",
            "SRAM is fast and used for caches. DRAM is dense and used for main memory.",
        )
        assertEquals(listOf("What is eDRAM?"), accepted)
    }

    @Test
    fun `explicitly supported comparison remains eligible`() {
        val supportingAnswer = "SRAM은 DRAM보다 빠릅니다."
        assertEquals(
            listOf("SRAM이 DRAM보다 빠른 이유는?"),
            DynamicFollowUpPolicy.filterSupported(
                listOf("SRAM이 DRAM보다 빠른 이유는?"),
                "SRAM과 DRAM 중 뭐가 더 빨라?",
                supportingAnswer,
            ),
        )
    }

    @Test
    fun `established causal question remains eligible`() {
        assertEquals(
            listOf("SRAM은 왜 캐시에 주로 사용돼?"),
            supported("SRAM은 왜 캐시에 주로 사용돼?"),
        )
    }

    @Test
    fun `unestablished causal question is rejected`() {
        // The source never establishes that SRAM is used in smartphones.
        assertTrue(supported("SRAM은 왜 스마트폰에 사용돼?").isEmpty())
    }

    @Test
    fun `empty source fails every loaded question closed`() {
        assertTrue(
            DynamicFollowUpPolicy.filterSupported(
                listOf("SRAM이 DRAM보다 빠른 이유는?"),
                "",
                "   ",
            ).isEmpty(),
        )
    }

    @Test
    fun `generation instruction forbids unsupported premises`() {
        val messages = DynamicFollowUpPolicy.buildMessages(request, answer)!!
        val system = messages.first { it.role == "system" }.content
        assertTrue(system.contains("전제"))
        assertTrue(system.contains("보다"))
        assertTrue(system.contains("than"))
    }

    // Relational locality: scattered vocabulary is not proof. --------------
    // Loading every stem somewhere across the whole source does NOT ground a
    // comparison or causal relation; the anchors must be jointly established
    // inside one source clause, otherwise the question is rejected.

    @Test
    fun `scattered vocabulary does not ground an unsupported comparison`() {
        val scatteredRequest = "eDRAM과 SRAM의 차이를 알려줘."
        val scatteredAnswer = "eDRAM과 SRAM은 메모리 종류입니다.\n캐시는 빠른 메모리를 사용합니다."
        // eDRAM, SRAM and 빠르 each appear somewhere, but no single clause
        // establishes eDRAM > SRAM; the claim is invented.
        assertTrue(
            DynamicFollowUpPolicy.filterSupported(
                listOf("eDRAM이 SRAM보다 빠른 이유는?"),
                scatteredRequest,
                scatteredAnswer,
            ).isEmpty(),
        )
    }

    @Test
    fun `scattered vocabulary does not ground an unsupported causal relation`() {
        val scatteredRequest = "SRAM에 대해 설명해줘."
        val scatteredAnswer = "SRAM은 캐시에 사용됩니다.\n스마트폰에는 다양한 메모리가 있습니다."
        // SRAM, 스마트폰 and 사용 exist in the source but never in the same
        // clause, so "SRAM is used in smartphones" was never established.
        assertTrue(
            DynamicFollowUpPolicy.filterSupported(
                listOf("SRAM은 왜 스마트폰에 사용돼?"),
                scatteredRequest,
                scatteredAnswer,
            ).isEmpty(),
        )
    }

    @Test
    fun `scattered english vocabulary does not ground an unsupported comparison`() {
        val scatteredRequest = "What is the difference between eDRAM and SRAM?"
        val scatteredAnswer = "eDRAM and SRAM are memory types.\nCaches can make programs run faster."
        assertTrue(
            DynamicFollowUpPolicy.filterSupported(
                listOf("Why is eDRAM faster than SRAM?"),
                scatteredRequest,
                scatteredAnswer,
            ).isEmpty(),
        )
    }

    @Test
    fun `explicitly local supported comparison remains accepted`() {
        val localRequest = "SRAM과 DRAM 중 뭐가 더 빨라?"
        val localAnswer = "SRAM은 DRAM보다 빠릅니다."
        assertEquals(
            listOf("SRAM이 DRAM보다 빠른 이유는?"),
            DynamicFollowUpPolicy.filterSupported(
                listOf("SRAM이 DRAM보다 빠른 이유는?"),
                localRequest,
                localAnswer,
            ),
        )
    }

    @Test
    fun `explicitly local supported causal fact remains accepted`() {
        val localRequest = "SRAM은 어디에 주로 쓰여?"
        val localAnswer = "SRAM은 주로 캐시 메모리에 사용됩니다."
        assertEquals(
            listOf("SRAM은 왜 캐시에 주로 사용돼?"),
            DynamicFollowUpPolicy.filterSupported(
                listOf("SRAM은 왜 캐시에 주로 사용돼?"),
                localRequest,
                localAnswer,
            ),
        )
    }

    @Test
    fun `neutral adjacent concept remains accepted under scattered source`() {
        val scatteredRequest = "eDRAM과 SRAM의 차이를 알려줘."
        val scatteredAnswer = "eDRAM과 SRAM은 메모리 종류입니다.\n캐시는 빠른 메모리를 사용합니다."
        assertEquals(
            listOf("eDRAM은 무엇인가요?"),
            DynamicFollowUpPolicy.filterSupported(
                listOf("eDRAM은 무엇인가요?"),
                scatteredRequest,
                scatteredAnswer,
            ),
        )
    }

    // Fact authority: the assistant answer is the factual authority. -------
    // The user request is topic/context. A false premise typed by the user
    // must never authenticate itself through its own vocabulary.

    @Test
    fun `false premise in the user request does not self ground`() {
        assertTrue(
            DynamicFollowUpPolicy.filterSupported(
                listOf("eDRAM이 SRAM보다 빠른 이유는?"),
                "왜 eDRAM이 SRAM보다 빠른 거야?",
                "그 전제를 일반화할 수 없습니다.",
            ).isEmpty(),
        )
    }

    @Test
    fun `assistant confirmed relation grounds`() {
        assertEquals(
            listOf("SRAM이 DRAM보다 빠른 이유는?"),
            DynamicFollowUpPolicy.filterSupported(
                listOf("SRAM이 DRAM보다 빠른 이유는?"),
                "SRAM과 DRAM 중 뭐가 빨라?",
                "SRAM은 DRAM보다 빠릅니다.",
            ),
        )
    }

    @Test
    fun `neutral concept from the user request remains eligible`() {
        assertEquals(
            listOf("eDRAM은 무엇인가요?"),
            DynamicFollowUpPolicy.filterSupported(
                listOf("eDRAM은 무엇인가요?"),
                "왜 eDRAM이 SRAM보다 빠른 거야?",
                "그 전제를 일반화할 수 없습니다.",
            ),
        )
    }

    // Refutation / negation gate: an answer that rejects the relation is
    // support, not refutation -> the loaded premise is rejected. -----------

    @Test
    fun `korean negated comparison is rejected`() {
        assertTrue(
            DynamicFollowUpPolicy.filterSupported(
                listOf("eDRAM이 SRAM보다 빠른 이유는?"),
                "eDRAM과 SRAM의 차이가 뭐야?",
                "eDRAM이 SRAM보다 빠르다는 근거는 없습니다.",
            ).isEmpty(),
        )
    }

    @Test
    fun `english negated comparison is rejected`() {
        assertTrue(
            DynamicFollowUpPolicy.filterSupported(
                listOf("Why is eDRAM faster than SRAM?"),
                "What is the difference between eDRAM and SRAM?",
                "eDRAM is not faster than SRAM.",
            ).isEmpty(),
        )
    }

    @Test
    fun `positive equivalent comparison stays accepted`() {
        assertEquals(
            listOf("Why is SRAM faster than DRAM?"),
            DynamicFollowUpPolicy.filterSupported(
                listOf("Why is SRAM faster than DRAM?"),
                "Which is faster, SRAM or DRAM?",
                "SRAM is faster than DRAM.",
            ),
        )
    }

    // Line locality: the factual-authority path preserves newlines, so
    // newline-only unrelated bullet lines are never merged into one clause.

    @Test
    fun `newline only unrelated bullets remain separate`() {
        // No sentence punctuation: only the newline separates the lines. The
        // compact visible path would collapse them into one clause and
        // wrongly ground eDRAM/SRAM/빠르 together.
        val answer = "eDRAM과 SRAM은 메모리 종류입니다\n캐시는 빠른 메모리를 사용합니다"
        assertTrue(
            DynamicFollowUpPolicy.filterSupported(
                listOf("eDRAM이 SRAM보다 빠른 이유는?"),
                "eDRAM과 SRAM의 차이를 알려줘",
                answer,
            ).isEmpty(),
        )
    }

    @Test
    fun `same line supported relation stays accepted across newlines`() {
        val answer = "SRAM은 DRAM보다 빠릅니다\n캐시에 자주 사용됩니다"
        assertEquals(
            listOf("SRAM이 DRAM보다 빠른 이유는?"),
            DynamicFollowUpPolicy.filterSupported(
                listOf("SRAM이 DRAM보다 빠른 이유는?"),
                "SRAM과 DRAM 중 뭐가 빨라?",
                answer,
            ),
        )
    }
}
