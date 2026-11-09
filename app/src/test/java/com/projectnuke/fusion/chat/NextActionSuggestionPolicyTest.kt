package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextActionSuggestionPolicyTest {

    private fun context(
        visibleAnswer: String = "평범한 답변입니다.",
        webSourceCount: Int = 0,
        localSourceCount: Int = 0,
        localDocumentCount: Int = 0,
        isProjectConversation: Boolean = false,
        projectFileCount: Int = 0,
    ) = NextActionContext(
        visibleAnswer = visibleAnswer,
        webSourceCount = webSourceCount,
        localSourceCount = localSourceCount,
        localDocumentCount = localDocumentCount,
        isProjectConversation = isProjectConversation,
        projectFileCount = projectFileCount,
    )

    private fun ids(result: List<NextActionSuggestion>): List<String> = result.map { it.id }

    // --- Required policy tests (A-M) ------------------------------------------

    @Test
    fun `A generic answer returns at most three stable generic suggestions`() {
        val first = NextActionSuggestionPolicy.suggestions(context())
        val second = NextActionSuggestionPolicy.suggestions(context())

        assertTrue(first.size in 1..3)
        assertEquals(first, second)
        assertEquals(
            listOf("generic_example", "generic_counterpoint", "generic_step_by_step"),
            ids(first),
        )
    }

    @Test
    fun `B one local document outranks generic`() {
        val result = NextActionSuggestionPolicy.suggestions(
            context(localSourceCount = 2, localDocumentCount = 1),
        )

        assertTrue(result.size in 1..3)
        assertEquals("doc_summary", result.first().id)
        assertTrue(ids(result).contains("doc_study_questions"))
    }

    @Test
    fun `C two distinct local docs include document comparison`() {
        val result = NextActionSuggestionPolicy.suggestions(
            context(localSourceCount = 4, localDocumentCount = 2),
        )

        assertTrue(ids(result).contains("doc_compare"))
    }

    @Test
    fun `D repeated chunks from same file use single-document policy`() {
        val result = NextActionSuggestionPolicy.suggestions(
            context(localSourceCount = 5, localDocumentCount = 1),
        )

        assertFalse(ids(result).contains("doc_compare"))
        assertEquals("doc_summary", result.first().id)
    }

    @Test
    fun `E web sources contain web comparison suggestion`() {
        val result = NextActionSuggestionPolicy.suggestions(context(webSourceCount = 2))

        assertTrue(ids(result).contains("web_compare_sources"))
    }

    @Test
    fun `F web plus one local doc stays diverse`() {
        val result = NextActionSuggestionPolicy.suggestions(
            context(
                webSourceCount = 2,
                localSourceCount = 1,
                localDocumentCount = 1,
            ),
        )

        assertTrue(result.size <= 3)
        // Document-oriented first, then a complementary generic slot: never
        // three near-identical source/document suggestions.
        assertEquals("doc_summary", result.first().id)
        val kinds = result.map { it.kind }.toSet()
        assertTrue(kinds.size >= 2)
    }

    @Test
    fun `G project with files may surface next-step candidate`() {
        val result = NextActionSuggestionPolicy.suggestions(
            context(isProjectConversation = true, projectFileCount = 3),
        )

        assertTrue(ids(result).contains("project_next_steps"))
        // Branch level: the file-backed synthesis variant is selected.
        assertEquals(
            listOf("project_decisions", "project_next_steps", "project_synthesis"),
            NextActionSuggestionPolicy.projectCandidates(3).map { it.id },
        )
    }

    @Test
    fun `H project without files never claims project materials exist`() {
        val result = NextActionSuggestionPolicy.suggestions(
            context(isProjectConversation = true, projectFileCount = 0),
        )
        val text = result.joinToString("\n") { it.label + "\n" + it.promptText.orEmpty() }

        assertFalse(text.contains("자료"))
        assertFalse(ids(result).contains("project_synthesis"))
        // Branch level: the file-neutral recap variant is used instead.
        assertEquals(
            listOf("project_decisions", "project_next_steps", "project_context_recap"),
            NextActionSuggestionPolicy.projectCandidates(0).map { it.id },
        )
    }

    @Test
    fun `I long answer unlocks short-summary candidate`() {
        val long = "가".repeat(NextActionSuggestionPolicy.LONG_ANSWER_CHARS + 1)
        val result = NextActionSuggestionPolicy.suggestions(context(visibleAnswer = long))

        assertTrue(ids(result).contains("answer_short_summary"))
        val short = NextActionSuggestionPolicy.suggestions(context(visibleAnswer = "짧은 답변"))
        assertFalse(ids(short).contains("answer_short_summary"))
    }

    @Test
    fun `J code answer unlocks code-flow candidate`() {
        val code = "설명입니다.\n```kotlin\nval x = 1\n```"
        val result = NextActionSuggestionPolicy.suggestions(context(visibleAnswer = code))

        assertTrue(ids(result).contains("code_explain_flow"))
        val plain = NextActionSuggestionPolicy.suggestions(context())
        assertFalse(ids(plain).contains("code_explain_flow"))
    }

    @Test
    fun `K blank answer yields no suggestions`() {
        assertTrue(NextActionSuggestionPolicy.suggestions(context(visibleAnswer = "")).isEmpty())
        assertTrue(NextActionSuggestionPolicy.suggestions(context(visibleAnswer = "   ")).isEmpty())
    }

    @Test
    fun `L duplicate ids and texts are deduped`() {
        val base = NextActionSuggestionPolicy.GENERIC_EXAMPLE
        val deduped = NextActionSuggestionPolicy.deduplicate(
            listOf(
                base,
                base.copy(label = "다른 라벨"),
                base.copy(id = "other_id", label = "다른 라벨"),
                base.copy(id = "other_id2", target = base.target),
                base.copy(
                    id = "other_id3",
                    label = base.label,
                    target = NextActionTarget.ComposerPrompt("다른 본문"),
                ),
                NextActionSuggestionPolicy.GENERIC_COUNTERPOINT,
            ),
        )

        assertEquals(
            listOf("generic_example", "generic_counterpoint"),
            ids(deduped),
        )
    }

    @Test
    fun `single-family context is complemented, never monopolized`() {
        assertEquals(
            listOf("web_compare_sources", "web_find_disagreements", "generic_example"),
            ids(NextActionSuggestionPolicy.suggestions(context(webSourceCount = 3))),
        )
        assertEquals(
            listOf("doc_compare", "doc_study_questions", "generic_example"),
            ids(
                NextActionSuggestionPolicy.suggestions(
                    context(localSourceCount = 3, localDocumentCount = 2),
                ),
            ),
        )
    }

    @Test
    fun `M never more than three suggestions across contexts`() {
        val answers = listOf(
            "평범한 답변",
            "```code```",
            "가".repeat(2_000),
            "| a | b |\n|---|---|\n| 1 | 2 |",
            "```x```\n" + "가".repeat(2_000),
        )
        for (answer in answers) {
            for (web in 0..3) {
                for (docs in 0..3) {
                    val result = NextActionSuggestionPolicy.suggestions(
                        context(
                            visibleAnswer = answer,
                            webSourceCount = web,
                            localSourceCount = docs * 2,
                            localDocumentCount = docs,
                            isProjectConversation = docs % 2 == 0,
                            projectFileCount = docs,
                        ),
                    )
                    assertTrue(result.size <= NextActionSuggestionPolicy.MAX_SUGGESTIONS)
                    assertEquals(ids(result).distinct(), ids(result))
                    assertEquals(
                        result.map { it.promptText }.distinct(),
                        result.map { it.promptText },
                    )
                }
            }
        }
    }

    // --- Bounds ------------------------------------------------------------------

    @Test
    fun `all built-in suggestions satisfy static bounds`() {
        for (candidate in NextActionSuggestionPolicy.allBuiltIn) {
            assertTrue(
                "invalid built-in ${candidate.id}",
                NextActionSuggestionPolicy.isValidSuggestion(candidate),
            )
        }
        assertEquals(
            NextActionSuggestionPolicy.allBuiltIn.map { it.id }.distinct(),
            NextActionSuggestionPolicy.allBuiltIn.map { it.id },
        )
    }

    @Test
    fun `invalid suggestions are omitted rather than truncated`() {
        assertFalse(
            NextActionSuggestionPolicy.isValidSuggestion(
                NextActionSuggestionPolicy.GENERIC_EXAMPLE.copy(label = "가".repeat(25)),
            ),
        )
        assertFalse(
            NextActionSuggestionPolicy.isValidSuggestion(
                NextActionSuggestionPolicy.GENERIC_EXAMPLE.copy(
                    target = NextActionTarget.ComposerPrompt("가".repeat(301)),
                ),
            ),
        )
        assertFalse(
            NextActionSuggestionPolicy.isValidSuggestion(
                NextActionSuggestionPolicy.GENERIC_EXAMPLE.copy(id = "x".repeat(65)),
            ),
        )
    }

    // --- Privacy / truth (N-P) ------------------------------------------------------

    @Test
    fun `N policy accepts only scalar context data`() {
        val fields = NextActionContext::class.java.declaredFields
        val allowed = setOf(
            String::class.java,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        for (field in fields) {
            if (field.isSynthetic) continue
            assertTrue(
                "forbidden context field ${field.name}: ${field.type}",
                field.type in allowed,
            )
        }
        val methods = NextActionSuggestionPolicy.javaClass.declaredMethods +
            NextActionSuggestionPolicy.javaClass.methods
        assertTrue(methods.none { m -> m.parameterTypes.any { it.name.contains("Function") } })
    }

    @Test
    fun `O web presence never claims verification`() {
        val banned = listOf("검증됨", "사실 확인", "진실", "신뢰할 수 있음")
        for (candidate in NextActionSuggestionPolicy.allBuiltIn) {
            for (word in banned) {
                assertFalse("${candidate.id} label overclaims", candidate.label.contains(word))
                assertFalse("${candidate.id} composer overclaims", (candidate.promptText.orEmpty()).contains(word))
            }
        }
        val webOnly = NextActionSuggestionPolicy.suggestions(context(webSourceCount = 3))
        val text = webOnly.joinToString("\n") { it.label + "\n" + it.promptText.orEmpty() }
        for (word in banned) {
            assertFalse(text.contains(word))
        }
    }

    @Test
    fun `P local suggestions ask to inspect, never claim proof`() {
        val banned = listOf("반드시 있다", "증명한다", "반드시 증명")
        for (candidate in NextActionSuggestionPolicy.allBuiltIn) {
            for (word in banned) {
                assertFalse("${candidate.id} label claims proof", candidate.label.contains(word))
                assertFalse("${candidate.id} composer claims proof", (candidate.promptText.orEmpty()).contains(word))
            }
        }
    }

    @Test
    fun `composer texts never interpolate answer excerpts`() {
        for (candidate in NextActionSuggestionPolicy.allBuiltIn) {
            assertFalse("${candidate.id} interpolates", (candidate.promptText.orEmpty()).contains("\${"))
            assertFalse("${candidate.id} quotes answer", (candidate.promptText.orEmpty()).contains("take("))
        }
    }

    // --- Multi-doc truth ------------------------------------------------------------

    @Test
    fun `multi-doc prompts ask to check, never claim disagreement`() {
        val result = NextActionSuggestionPolicy.suggestions(
            context(localSourceCount = 3, localDocumentCount = 2),
        )
        val text = result.joinToString("\n") { it.label + "\n" + it.promptText.orEmpty() }
        assertFalse(text.contains("모순된다"))
        assertFalse(text.contains("반드시 다르다"))
    }

    // --- Study entry + document identity (§23 A-J, §0) -------------------------------

    private fun ragEntry(fileName: String, attachmentId: String) =
        com.projectnuke.fusion.retrieval.LocalRagSourceEntry(
            fileName = fileName,
            attachmentId = attachmentId,
            chunkIndex = 0,
            startOffset = 0,
            endOffset = 10,
            contentHashHex = "ab",
            excerpt = "e",
            rank = 0,
            lexicalScore = 0,
            semanticScore = null,
            pageNumber = null,
        )

    @Test
    fun `Study entry appears for one local document`() {
        val result = NextActionSuggestionPolicy.suggestions(
            context(localSourceCount = 1, localDocumentCount = 1),
        )

        assertTrue(ids(result).contains("doc_study_questions"))
    }

    @Test
    fun `Study entry appears for multiple local documents`() {
        val result = NextActionSuggestionPolicy.suggestions(
            context(localSourceCount = 3, localDocumentCount = 2),
        )

        assertTrue(ids(result).contains("doc_study_questions"))
    }

    @Test
    fun `web-only answer does NOT get Study Start`() {
        val result = NextActionSuggestionPolicy.suggestions(context(webSourceCount = 3))

        assertFalse(ids(result).contains("doc_study_questions"))
        assertTrue(result.none { it.target is NextActionTarget.EnterStudyMode })
    }

    @Test
    fun `generic answer does NOT get Study Start`() {
        val result = NextActionSuggestionPolicy.suggestions(context())

        assertTrue(result.none { it.target is NextActionTarget.EnterStudyMode })
    }

    @Test
    fun `same filename different attachmentId counts as two documents`() {
        val count = NextActionSuggestionPolicy.countDistinctLocalDocuments(
            listOf(
                ragEntry("report.pdf", "att-A"),
                ragEntry("report.pdf", "att-B"),
            ),
        )

        assertEquals(2, count)
    }

    @Test
    fun `repeated chunks with same attachmentId count as one document`() {
        val count = NextActionSuggestionPolicy.countDistinctLocalDocuments(
            listOf(
                ragEntry("report.pdf", "att-A").copy(chunkIndex = 0),
                ragEntry("report.pdf", "att-A").copy(chunkIndex = 1),
                ragEntry("report.pdf", "att-A").copy(chunkIndex = 2),
            ),
        )

        assertEquals(1, count)
    }

    @Test
    fun `blank attachmentId fails safe to normalized fileName`() {
        assertEquals(
            2,
            NextActionSuggestionPolicy.countDistinctLocalDocuments(
                listOf(ragEntry("a.pdf", ""), ragEntry("b.pdf", "  ")),
            ),
        )
        assertEquals(
            1,
            NextActionSuggestionPolicy.countDistinctLocalDocuments(
                listOf(ragEntry("a.pdf", ""), ragEntry(" a.pdf ", "")),
            ),
        )
        assertEquals(
            0,
            NextActionSuggestionPolicy.countDistinctLocalDocuments(
                listOf(ragEntry("  ", "")),
            ),
        )
    }

    @Test
    fun `all non-Study actions retain ComposerPrompt targets`() {
        val prompts = NextActionSuggestionPolicy.allBuiltIn.filter {
            it.id != "doc_study_questions"
        }

        assertTrue(prompts.isNotEmpty())
        for (candidate in prompts) {
            val text = candidate.promptText
            assertTrue("${candidate.id} must stay a composer prompt", text != null)
            assertTrue(text!!.isNotBlank())
            assertTrue(text.length <= NextActionSuggestionPolicy.MAX_COMPOSER_CHARS)
        }
    }

    @Test
    fun `Study suggestion target is EnterStudyMode`() {
        val study = NextActionSuggestionPolicy.DOC_STUDY_QUESTIONS

        assertEquals("학습 모드로 계속", study.label)
        assertTrue(study.target is NextActionTarget.EnterStudyMode)
        assertEquals(null, study.promptText)
    }

    @Test
    fun `Study shortcut carries no composer text by construction`() {
        // Entering STUDY must not seed the composer: the target is a pure
        // UI action, so promptText stays null and no draft callback exists.
        assertEquals(null, NextActionSuggestionPolicy.DOC_STUDY_QUESTIONS.promptText)
    }

    @Test
    fun `deterministic ordering stable except intentional Study change`() {
        assertEquals(
            listOf("doc_summary", "doc_study_questions", "generic_example"),
            ids(
                NextActionSuggestionPolicy.suggestions(
                    context(localSourceCount = 1, localDocumentCount = 1),
                ),
            ),
        )
        assertEquals(
            listOf("doc_compare", "doc_study_questions", "generic_example"),
            ids(
                NextActionSuggestionPolicy.suggestions(
                    context(localSourceCount = 3, localDocumentCount = 2),
                ),
            ),
        )
    }
}
