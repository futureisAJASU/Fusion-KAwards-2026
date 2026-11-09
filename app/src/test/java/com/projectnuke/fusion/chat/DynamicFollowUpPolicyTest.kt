package com.projectnuke.fusion.chat

import com.projectnuke.fusion.trust.TrustRoutingPresentationKind
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Dynamic semantic follow-up questions v1: focused ownership, bounds,
 * parsing, fallback, STUDY suppression and composer-seam tests.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DynamicFollowUpPolicyTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule { scheduler ->
        UnconfinedTestDispatcher(scheduler)
    }

    private val viewModels = mutableListOf<ChatViewModel>()

    @After
    fun tearDown() {
        viewModels.forEach { it.cancelForTest(mainDispatcherRule.dispatcher.scheduler) }
        viewModels.clear()
    }

    private fun ownership() = DynamicFollowUpOwnership(
        conversationId = 7L,
        newChatEpoch = 2L,
        userTurnId = 101L,
        assistantMessageId = 102L,
        versionGroupId = -102L,
        activeResponseMessageId = 102L,
    )

    private fun sramDramInput(): Pair<String, String> = Pair(
        "SRAM과 DRAM의 차이가 뭐야?",
        "SRAM은 속도가 빠르고 DRAM은 집적도가 높습니다. SRAM은 주로 캐시 메모리에, DRAM은 주기억장치에 사용됩니다.",
    )

    // 1. Semantic input carries topic-specific questions through the seam. ---
    @Test
    fun `sram dram input produces bounded topic specific questions`() {
        val (request, answer) = sramDramInput()
        val messages = DynamicFollowUpPolicy.buildMessages(request, answer)
        assertNotNull(messages)
        val joined = messages!!.joinToString("\n") { it.content }
        assertTrue(joined.contains("SRAM"))
        assertTrue(joined.contains("DRAM"))

        val modelOutput = "1. SRAM은 왜 캐시에 주로 사용돼?\n" +
            "2. DRAM의 장점은 뭐야?\n" +
            "3. eDRAM은 어떤 메모리야?"
        val questions = DynamicFollowUpPolicy.parseQuestions(modelOutput)
        assertEquals(3, questions.size)
        assertTrue(questions[0].contains("SRAM"))
        assertTrue(questions[1].contains("DRAM"))
        assertTrue(questions[2].contains("eDRAM"))
        for (question in questions) {
            assertTrue(question.length <= DynamicFollowUpPolicy.MAX_QUESTION_CHARS)
        }
        // Never the generic fixed strings.
        assertFalse(questions.any { it.contains("예시로 설명") })
        assertFalse(questions.any { it.contains("반대 관점") })
        assertFalse(questions.any { it.contains("단계별로") })
    }

    // 2. Max count is 3. -----------------------------------------------
    @Test
    fun `at most three questions are published`() {
        val modelOutput = (1..5).joinToString("\n") { "${it}번째 후속 질문이야?" }
        val questions = DynamicFollowUpPolicy.parseQuestions(modelOutput)
        assertEquals(DynamicFollowUpPolicy.MAX_QUESTIONS, questions.size)
        assertEquals(3, DynamicFollowUpPolicy.suggestions(questions).size)
    }

    // 3. Blank, malformed and duplicate output is filtered. --------------
    @Test
    fun `blank malformed duplicate candidates are filtered`() {
        val modelOutput = "\n" +
            "   \n" +
            "1. 첫 번째 질문이야?\n" +
            "그냥 설명 문장입니다.\n" +
            "2. 첫 번째 질문이야?\n" +
            "3. " + "가".repeat(DynamicFollowUpPolicy.MAX_QUESTION_CHARS + 1) + "?\n" +
            "- 두 번째 질문이야?\n"
        val questions = DynamicFollowUpPolicy.parseQuestions(modelOutput)
        assertEquals(listOf("첫 번째 질문이야?", "두 번째 질문이야?"), questions)
    }

    @Test
    fun `duplicate keys ignore trailing punctuation`() {
        val questions = DynamicFollowUpPolicy.parseQuestions(
            "1. 같은 질문?\n2. 같은 질문!\n3. 같은 질문.\n",
        )
        assertEquals(listOf("같은 질문?"), questions)
    }

    @Test
    fun `numbering and bullets are normalized`() {
        val questions = DynamicFollowUpPolicy.parseQuestions(
            "1) 첫 질문이야?\n- 두 번째야?\n• 세 번째야?\n",
        )
        assertEquals(listOf("첫 질문이야?", "두 번째야?", "세 번째야?"), questions)
    }

    // 4. Unsafe/malformed candidates fail safe. ---------------------------
    @Test
    fun `premise heavy statements without questions fail safe to empty`() {
        // Statements (no question mark) are never published as questions:
        // they may carry invented premises, and suggestions must be
        // questions, not new factual claims.
        val modelOutput = "1. eDRAM은 SRAM보다 빠르다.\n" +
            "2. DRAM은 항상 느리다.\n" +
            "3. SRAM이 최고다."
        assertTrue(DynamicFollowUpPolicy.parseQuestions(modelOutput).isEmpty())
    }

    @Test
    fun `empty and garbage output fail safe to empty`() {
        assertTrue(DynamicFollowUpPolicy.parseQuestions("").isEmpty())
        assertTrue(DynamicFollowUpPolicy.parseQuestions("   \n  \n").isEmpty())
        assertTrue(DynamicFollowUpPolicy.parseQuestions("안녕하세요 무엇을 도와드릴까요").isEmpty())
    }

    // 5. No dynamic result keeps the deterministic fallback. --------------
    @Test
    fun `empty dynamic result maps to no suggestions while deterministic fallback survives`() {
        assertTrue(DynamicFollowUpPolicy.suggestions(emptyList()).isEmpty())
        val fallback = NextActionSuggestionPolicy.suggestions(
            NextActionContext(
                visibleAnswer = "SRAM과 DRAM에 대한 답변입니다.",
                webSourceCount = 0,
                localSourceCount = 0,
                localDocumentCount = 0,
                isProjectConversation = false,
                projectFileCount = 0,
            ),
        )
        assertTrue(fallback.isNotEmpty())
    }

    // 6-9. Ownership staleness. -------------------------------------------
    @Test
    fun `matching ownership is current`() {
        val frozen = ownership()
        assertTrue(DynamicFollowUpPolicy.isOwnershipCurrent(frozen, frozen.copy()))
    }

    @Test
    fun `stale conversation result is discarded`() {
        val frozen = ownership()
        assertFalse(
            DynamicFollowUpPolicy.isOwnershipCurrent(
                frozen,
                frozen.copy(conversationId = 8L),
            ),
        )
    }

    @Test
    fun `fresh new chat epoch result is discarded`() {
        val frozen = ownership()
        assertFalse(
            DynamicFollowUpPolicy.isOwnershipCurrent(
                frozen,
                frozen.copy(newChatEpoch = frozen.newChatEpoch + 1),
            ),
        )
    }

    @Test
    fun `response version switch and regeneration stale results are discarded`() {
        val frozen = ownership()
        assertFalse(
            DynamicFollowUpPolicy.isOwnershipCurrent(
                frozen,
                frozen.copy(activeResponseMessageId = 105L),
            ),
        )
        assertFalse(
            DynamicFollowUpPolicy.isOwnershipCurrent(
                frozen,
                frozen.copy(versionGroupId = 999L),
            ),
        )
        assertFalse(
            DynamicFollowUpPolicy.isOwnershipCurrent(
                frozen,
                frozen.copy(assistantMessageId = 104L),
            ),
        )
        assertFalse(
            DynamicFollowUpPolicy.isOwnershipCurrent(
                frozen,
                frozen.copy(userTurnId = 100L),
            ),
        )
    }

    @Test
    fun `deleted target result is discarded`() {
        // A deleted target has no live ownership: current is null.
        assertFalse(DynamicFollowUpPolicy.isOwnershipCurrent(ownership(), null))
    }

    // 10. STUDY suppression and eligibility parity. ------------------------
    @Test
    fun `study mode suppresses ordinary dynamic follow ups`() {
        for (kind in TrustRoutingPresentationKind.entries) {
            assertFalse(
                "STUDY must suppress dynamic follow-ups for $kind",
                DynamicFollowUpPolicy.isEligible(
                    routingKind = kind,
                    isRetrySearchRecovery = false,
                    isStudyMode = true,
                    isAutoGenerateEnabled = true,
                ),
            )
        }
    }

    @Test
    fun `dynamic eligibility never drifts from the deterministic gate`() {
        for (kind in TrustRoutingPresentationKind.entries) {
            for (recovery in listOf(false, true)) {
                for (study in listOf(false, true)) {
                    assertEquals(
                        "eligibility drift for kind=$kind recovery=$recovery study=$study",
                        NextActionEligibilityPolicy.isEligible(
                            routingKind = kind,
                            isRetrySearchRecovery = recovery,
                            isStudyMode = study,
                        ),
                        DynamicFollowUpPolicy.isEligible(
                            routingKind = kind,
                            isRetrySearchRecovery = recovery,
                            isStudyMode = study,
                            isAutoGenerateEnabled = true,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `normal answer stays eligible while abstain and recovery do not`() {
        assertTrue(
            DynamicFollowUpPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = false,
                isStudyMode = false,
                isAutoGenerateEnabled = true,
            ),
        )
        assertFalse(
            DynamicFollowUpPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.ABSTAIN,
                isRetrySearchRecovery = false,
                isStudyMode = false,
                isAutoGenerateEnabled = true,
            ),
        )
        assertFalse(
            DynamicFollowUpPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = true,
                isStudyMode = false,
                isAutoGenerateEnabled = true,
            ),
        )
    }

    // 10b. Automatic follow-up preference + lifecycle gates. ---------------
    @Test
    fun `auto generate defaults enabled`() {
        assertTrue(DynamicFollowUpPreference.DEFAULT_AUTO_GENERATE)
    }

    @Test
    fun `disabled auto generate makes dynamic eligibility false everywhere`() {
        for (kind in TrustRoutingPresentationKind.entries) {
            assertFalse(
                "disabled preference must suppress dynamic follow-ups for $kind",
                DynamicFollowUpPolicy.isEligible(
                    routingKind = kind,
                    isRetrySearchRecovery = false,
                    isStudyMode = false,
                    isAutoGenerateEnabled = false,
                ),
            )
        }
    }

    @Test
    fun `disabled auto generate leaves the deterministic gate untouched`() {
        // The deterministic NextActionSuggestionPolicy row never consults the
        // preference (no such parameter exists by construction): an eligible
        // NORMAL answer keeps its deterministic gate while dynamic is off.
        assertTrue(
            NextActionEligibilityPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = false,
                isStudyMode = false,
            ),
        )
        assertFalse(
            DynamicFollowUpPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = false,
                isStudyMode = false,
                isAutoGenerateEnabled = false,
            ),
        )
    }

    @Test
    fun `toggling off invalidates publication ownership`() {
        // Mid-generation toggle-off (or surface backgrounding) nulls live
        // ownership; the frozen in-flight identity must no longer publish.
        assertFalse(DynamicFollowUpPolicy.isOwnershipCurrent(ownership(), null))
    }

    @Test
    fun `lifecycle gate runs only while the surface is active`() {
        assertTrue(DynamicFollowUpLifecyclePolicy.shouldGenerate(isSurfaceActive = true))
        assertFalse(DynamicFollowUpLifecyclePolicy.shouldGenerate(isSurfaceActive = false))
    }

    @Test
    fun `preference owner defaults enabled and round trips stored values`() {
        fun prefsWith(values: Map<String, Boolean>): android.content.SharedPreferences {
            return java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(android.content.SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getBoolean" -> values[args[0] as String] ?: (args[1] as Boolean)
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as android.content.SharedPreferences
        }
        assertTrue(DynamicFollowUpPreference.isAutoGenerateEnabled(prefsWith(emptyMap())))
        assertFalse(
            DynamicFollowUpPreference.isAutoGenerateEnabled(
                prefsWith(mapOf(DynamicFollowUpPreference.KEY_AUTO_GENERATE to false)),
            ),
        )
        assertTrue(
            DynamicFollowUpPreference.isAutoGenerateEnabled(
                prefsWith(mapOf(DynamicFollowUpPreference.KEY_AUTO_GENERATE to true)),
            ),
        )
    }

    // 11. Tap seeds the composer and does not send. ------------------------
    @Test
    fun `dynamic suggestions are composer prompts carrying the exact question`() {
        val questions = listOf("SRAM은 왜 캐시에 주로 사용돼?", "DRAM의 장점은 뭐야?")
        val suggestions = DynamicFollowUpPolicy.suggestions(questions)
        assertEquals(2, suggestions.size)
        suggestions.forEachIndexed { index, suggestion ->
            val target = suggestion.target
            assertTrue(target is NextActionTarget.ComposerPrompt)
            assertEquals(questions[index], (target as NextActionTarget.ComposerPrompt).text)
            assertEquals(questions[index], suggestion.promptText)
            assertEquals(questions[index], suggestion.label)
        }
    }

    @Test
    fun `seeding a dynamic question appends durably without sending`() = runTest {
        val vm = ChatViewModel().also { viewModels += it }
        assertTrue(vm.replaceDraftTextDurably(71L, "old draft"))
        val question = "SRAM은 왜 캐시에 주로 사용돼?"
        val suggestion = DynamicFollowUpPolicy.suggestions(listOf(question)).single()
        val seeded = suggestion.promptText!!
        assertTrue(vm.appendPromptDurably(71L, seeded, ::appendPromptToDraft))
        // The composer now carries the seeded question for user inspection
        // before Send; no submission was started by the append seam.
        assertEquals(appendPromptToDraft("old draft", seeded), vm.draft(71L).rawInput)
    }

    @Test
    fun `oversized dynamic candidates never reach the composer seam`() {
        val oversized = "가".repeat(DynamicFollowUpPolicy.MAX_QUESTION_CHARS + 1) + "?"
        assertTrue(DynamicFollowUpPolicy.suggestions(listOf(oversized)).isEmpty())
        assertTrue(DynamicFollowUpPolicy.suggestions(listOf("   ")).isEmpty())
    }

    // 12. Generation failure cannot fail the primary chat answer. ----------
    @Test
    fun `parser is total over hostile input`() {
        val hostile = listOf(
            "",
            " ".repeat(10_000),
            "\u0000\uFFFD\u202E\u200B".repeat(500),
            "1.".repeat(2_000),
            "?\n?".repeat(1_000),
            "가".repeat(50_000),
        )
        for (input in hostile) {
            val parsed = DynamicFollowUpPolicy.parseQuestions(input)
            assertTrue(parsed.size <= DynamicFollowUpPolicy.MAX_QUESTIONS)
            assertTrue(parsed.all { it.length <= DynamicFollowUpPolicy.MAX_QUESTION_CHARS })
        }
    }

    @Test
    fun `blank visible input builds no recommendation request`() {
        assertNull(DynamicFollowUpPolicy.buildMessages("", "   "))
        assertNull(DynamicFollowUpPolicy.buildMessages("  ", "\n"))
    }

    // 13. No hidden metadata enters the recommendation input. --------------
    @Test
    fun `hidden envelopes hashes and paths never enter the recommendation input`() {
        val hiddenHash = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val hiddenPath = "/data/data/com.projectnuke.fusion/files/rag/chunk-42.bin"
        val rawAnswer = "SRAM은 빠릅니다.\n\n" +
            "[FUSION_SEARCH_SOURCES_JSON]\n" +
            "[{\"url\":\"https://internal.example/x\",\"queryUsed\":\"secret-query\"}]\n" +
            "[/FUSION_SEARCH_SOURCES_JSON]\n" +
            "[FUSION_LOCAL_RAG_SOURCES_JSON]\n" +
            "[{\"fileName\":\"secret-doc.pdf\",\"attachmentId\":\"att-007\",\"semanticScore\":0.99}]\n" +
            "[/FUSION_LOCAL_RAG_SOURCES_JSON]\n" +
            "<fusion_attachment_v2>doc.txt|text/plain|$hiddenPath</fusion_attachment_v2>\n" +
            "<fusion_thinking>hidden reasoning trace</fusion_thinking>\n" +
            "[FUSION_TRUST_ROUTE_JSON]\n" +
            "{\"route\":\"LOCAL\",\"contentHash\":\"$hiddenHash\"}\n" +
            "[/FUSION_TRUST_ROUTE_JSON]"
        val rawRequest = "SRAM과 DRAM의 차이가 뭐야?\n" +
            "<fusion_attachment>\nname=doc.txt\nmime=text/plain\npath=$hiddenPath\n</fusion_attachment>"
        val messages = DynamicFollowUpPolicy.buildMessages(rawRequest, rawAnswer)
        assertNotNull(messages)
        val joined = messages!!.joinToString("\n") { it.content }
        assertTrue(joined.contains("SRAM은 빠릅니다."))
        assertTrue(joined.contains("SRAM과 DRAM의 차이가 뭐야?"))
        assertFalse(joined.contains("FUSION_SEARCH_SOURCES_JSON"))
        assertFalse(joined.contains("FUSION_TRUST_ROUTE_JSON"))
        assertFalse(joined.contains("FUSION_LOCAL_RAG_SOURCES_JSON"))
        assertFalse(joined.contains("fusion_attachment"))
        assertFalse(joined.contains("fusion_thinking"))
        assertFalse(joined.contains(hiddenHash))
        assertFalse(joined.contains(hiddenPath))
        assertFalse(joined.contains("secret-query"))
        assertFalse(joined.contains("att-007"))
        assertFalse(joined.contains("secret-doc.pdf"))
    }

    @Test
    fun `recommendation input is hard bounded`() {
        val (request, answer) = sramDramInput()
        val longAnswer = answer + "가".repeat(100_000)
        val messages = DynamicFollowUpPolicy.buildMessages(request, longAnswer)!!
        val total = messages.sumOf { it.content.length }
        assertTrue(total <= DynamicFollowUpPolicy.MAX_USER_REQUEST_CHARS +
            DynamicFollowUpPolicy.MAX_ANSWER_CHARS + 2_000)
    }

    // 14. No web/RAG/retrieval operation in the recommendation seam. -------
    @Test
    fun `recommendation seam is pure with no suspend entry points`() {
        // The whole seam (build -> parse -> map -> gate) runs on this plain
        // JUnit thread with zero I/O: no web search, no attachment
        // retrieval, no PDF parsing, no embeddings, no RAG retrieval.
        val (request, answer) = sramDramInput()
        val messages = DynamicFollowUpPolicy.buildMessages(request, answer)!!
        val questions = DynamicFollowUpPolicy.parseQuestions(
            "1. SRAM은 왜 캐시에 주로 사용돼?\n2. DRAM의 장점은 뭐야?\n3. eDRAM은 어떤 메모리야?",
        )
        val suggestions = DynamicFollowUpPolicy.suggestions(questions)
        assertEquals(3, suggestions.size)
        assertTrue(messages.isNotEmpty())

        val suspendEntries = DynamicFollowUpPolicy.javaClass.declaredMethods.filter { method ->
            method.parameterTypes.any { it.name == "kotlin.coroutines.Continuation" }
        }
        assertTrue(
            "recommendation seam must expose no suspending entry points: $suspendEntries",
            suspendEntries.isEmpty(),
        )
    }
}
