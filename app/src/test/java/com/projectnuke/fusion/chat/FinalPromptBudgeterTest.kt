package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.search.FusionSearchResponse
import com.projectnuke.fusion.search.FusionSearchResult
import com.projectnuke.fusion.search.SearchIntent
import com.projectnuke.fusion.search.toAnswerContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalPromptBudgeterTest {
    private fun assertLegacyLimit(modelId: String?, expected: Int) {
        assertEquals(
            expected,
            FinalPromptBudgeter.computeLimit(
                FinalPromptBudget(modelId = modelId, external = false, maxOutputTokens = 512),
            ),
        )
    }

    @Test
    fun `legacy model scale fallback recognizes only bounded 2B and 4B tokens`() {
        listOf("2b", "2B", "Gemma 4 E2B-it", "gemma-4-e2b-it", "model_2b", "BitNet-2B")
            .forEach { modelId -> assertLegacyLimit(modelId, 16_000) }
        listOf("4b", "4B", "Gemma 4 E4B-it", "gemma-4-e4b-it", "model_4b")
            .forEach { modelId -> assertLegacyLimit(modelId, 24_000) }
    }

    @Test
    fun `legacy model scale fallback rejects larger suffix collisions and unknowns`() {
        listOf(
            "12b", "22b", "32b", "42b", "14b", "24b", "34b", "44b",
            "model-12b-it", "model-24b-it", "27B", "70B", "unrelated2bword",
        ).forEach { modelId -> assertLegacyLimit(modelId, 20_000) }
        assertLegacyLimit(null, 20_000)
        assertLegacyLimit("", 20_000)
        assertLegacyLimit("unknown-model", 20_000)
    }

    @Test
    fun `higher authority limits and KV fitting remain independent of legacy scale`() {
        assertEquals(
            123_456,
            FinalPromptBudgeter.computeLimit(
                FinalPromptBudget(
                    modelId = "model-32b",
                    external = false,
                    maxOutputTokens = 512,
                    externaModelContextChars = 123_456,
                ),
            ),
        )
        assertEquals(
            ExternalPromptCapacityPolicy.CONTEXT_LIMIT_CHARS,
            FinalPromptBudgeter.computeLimit(
                FinalPromptBudget(modelId = "model-4b", external = true, maxOutputTokens = 512),
            ),
        )

        val messages = listOf(
            ChatMessage("system", "system"),
            ChatMessage("user", "old user"),
            ChatMessage("assistant", "old answer"),
            ChatMessage("user", "current"),
        )
        val misleadingName = FinalPromptBudgeter.fit(
            messages,
            FinalPromptBudget(
                modelId = "something-32b",
                external = false,
                maxOutputTokens = 512,
                engineKvCapacityTokens = 2_048,
            ),
        )
        val referenceName = FinalPromptBudgeter.fit(
            messages,
            FinalPromptBudget(
                modelId = "2b",
                external = false,
                maxOutputTokens = 512,
                engineKvCapacityTokens = 2_048,
            ),
        )
        assertEquals(referenceName, misleadingName)
    }

    @Test
    fun `large current request is preserved verbatim when KV fits`() {
        val current = "FIRST_SENTINEL\n" + "m".repeat(19_950) + "\nLAST_SENTINEL"
        val result = FinalPromptBudgeter.fit(
            listOf(ChatMessage("system", "small system"), ChatMessage("user", current)),
            FinalPromptBudget(null, false, 512, engineKvCapacityTokens = 32_768),
        )
        assertFalse(result.isTooLarge)
        assertEquals(current, result.messages.last().content)
        assertFalse(result.messages.last().content.contains("content truncated"))
        assertTrue(result.messages.last().content.contains("FIRST_SENTINEL"))
        assertTrue(result.messages.last().content.contains("LAST_SENTINEL"))
    }

    @Test
    fun `large mandatory current request returns typed TooLarge instead of partial content`() {
        val current = "FIRST_SENTINEL\n" + "m".repeat(20_000) + "\nLAST_SENTINEL"
        val result = FinalPromptBudgeter.fit(
            listOf(ChatMessage("system", "small system"), ChatMessage("user", current)),
            FinalPromptBudget(null, false, 128, engineKvCapacityTokens = 1_024),
        )
        assertTrue(result.isTooLarge)
        assertTrue(result.messages.isEmpty())
    }

    @Test
    fun `external current request is preserved when within provider budget`() {
        val current = "FIRST_SENTINEL\n" + "m".repeat(19_950) + "\nLAST_SENTINEL"
        val result = FinalPromptBudgeter.fit(
            listOf(ChatMessage("system", "small system"), ChatMessage("user", current)),
            FinalPromptBudget(null, true, 512),
        )
        assertFalse(result.isTooLarge)
        assertEquals(current, result.messages.last().content)
    }

    @Test
    fun `external mandatory request over provider budget returns TooLarge`() {
        val current = "m".repeat(60_000)
        val result = FinalPromptBudgeter.fit(
            listOf(ChatMessage("system", "small system"), ChatMessage("user", current)),
            FinalPromptBudget(null, true, 512),
        )
        assertTrue(result.isTooLarge)
        assertTrue(result.messages.isEmpty())
    }

    @Test
    fun `long old turn that fits is retained verbatim`() {
        val oldUser = "OLD_FIRST\n" + "u".repeat(9_950) + "\nOLD_LAST"
        val oldAssistant = "ANSWER_FIRST\n" + "a".repeat(9_950) + "\nANSWER_LAST"
        val messages = listOf(
            ChatMessage("system", "policy"),
            ChatMessage("user", oldUser),
            ChatMessage("assistant", oldAssistant),
            ChatMessage("user", "current"),
        )
        val retained = FinalPromptBudgeter.fit(
            messages,
            FinalPromptBudget(null, false, 128, engineKvCapacityTokens = 16_384),
        )
        assertFalse(retained.isTooLarge)
        assertEquals(oldUser, retained.messages[1].content)
        assertEquals(oldAssistant, retained.messages[2].content)
    }

    @Test
    fun `long old turn that does not fit is omitted whole`() {
        val oldUser = "OLD_FIRST\n" + "u".repeat(9_950) + "\nOLD_LAST"
        val oldAssistant = "ANSWER_FIRST\n" + "a".repeat(9_950) + "\nANSWER_LAST"
        val result = FinalPromptBudgeter.fit(
            listOf(
                ChatMessage("system", "policy"),
                ChatMessage("user", oldUser),
                ChatMessage("assistant", oldAssistant),
                ChatMessage("user", "current"),
            ),
            FinalPromptBudget(null, false, 128, engineKvCapacityTokens = 4_096),
        )
        assertFalse(result.isTooLarge)
        assertTrue(result.messages.none { it.content.contains("OLD_FIRST") })
        assertTrue(result.messages.none { it.content.contains("ANSWER_FIRST") })
    }

    @Test
    fun `local search fit preserves a useful output floor by compacting complete evidence units`() {
        val query = "LATEST_USER_QUERY_SENTINEL: summarize the Samsung earnings news"
        val response = FusionSearchResponse(
            query = "Samsung earnings news",
            normalizedQuery = "Samsung earnings news",
            intent = SearchIntent.NEWS,
            results = (1..4).map { index ->
                FusionSearchResult(
                    title = "Samsung earnings result $index",
                    source = "Publisher $index",
                    url = "https://example.test/$index",
                    snippet = "삼성전자 실적 발표와 반도체 사업에 관한 확인 가능한 최신 정보입니다. ".repeat(12),
                    publishedAt = "2026-11-01",
                    queryUsed = "Samsung earnings news",
                    rank = index,
                )
            },
            debugMessage = null,
        )
        val explicitUserContent = ChatPromptComposition.finalUserContent(
            body = query,
            attachments = emptyList(),
            webSearchEnabled = true,
            webSearchResult = response.toAnswerContext(),
            evidenceStatus = "USABLE",
        )
        val result = FinalPromptBudgeter.fit(
            listOf(
                ChatMessage("system", "Core Fusion policy"),
                ChatMessage("user", explicitUserContent),
            ),
            FinalPromptBudget(
                modelId = "Gemma 4 E2B",
                external = false,
                maxOutputTokens = 1_024,
                engineKvCapacityTokens = 2_048,
            ),
        )

        assertFalse(result.isTooLarge)
        assertTrue("applied output ${result.appliedOutputTokens}", result.appliedOutputTokens >= 256)
        assertTrue(result.messages.last().content.contains(query))
        assertEquals(
            explicitUserContent.substringAfter("Current user request:\n"),
            result.messages.last().content.substringAfter("Current user request:\n"),
        )
        val admittedUnits = Regex("UNTRUSTED_RESULT_\\d+_BEGIN")
            .findAll(result.messages.last().content)
            .count()
        assertTrue("Expected complete evidence reduction, got $admittedUnits units", admittedUnits in 1..4)
        assertTrue(
            PromptTokenEstimator.estimateTokens(result.messages) + result.appliedOutputTokens + 128 <= 2_048,
        )
    }

    @Test
    fun `local fit returns typed TooLarge instead of a sub-floor answer allowance`() {
        val result = FinalPromptBudgeter.fit(
            listOf(
                ChatMessage("system", "s".repeat(1_010)),
                ChatMessage("user", "current explicit request"),
            ),
            FinalPromptBudget(
                modelId = "Gemma 4 E2B",
                external = false,
                maxOutputTokens = 1_024,
                engineKvCapacityTokens = 1_024,
            ),
        )

        assertTrue(result.isTooLarge)
        assertTrue(result.messages.isEmpty())
    }
    @Test
    fun `final prompt preserves system and current request while trimming whole turns`() {
        val messages = buildList {
            add(ChatMessage("system", "policy"))
            repeat(12) { add(ChatMessage("user", "u".repeat(2_000))); add(ChatMessage("assistant", "a".repeat(2_000))) }
            add(ChatMessage("user", "current"))
        }
        val result = FinalPromptBudgeter.fit(messages, FinalPromptBudget(null, false, 512)).messages
        assertEquals("policy", result.first().content)
        assertEquals("current", result.last().content)
        assertTrue(result.size % 2 == 0)
    }

    @Test
    fun `mandatory context larger than total capacity returns TooLarge marker`() {
        val messages = buildList {
            add(ChatMessage("system", "s".repeat(8_000)))
            add(ChatMessage("system", "s2".repeat(8_000)))
            add(ChatMessage("system", "s3".repeat(8_000)))
            add(ChatMessage("system", "s4".repeat(8_000)))
            add(ChatMessage("user", "current"))
        }
        val result = FinalPromptBudgeter.fit(messages, FinalPromptBudget("2b", false, 512))
        assertTrue(result.isTooLarge)
        assertTrue(result.messages.isEmpty())
    }

    @Test
    fun `assembler counts removed whole turns in contextsRemoved`() {
        val messages = buildList {
            add(ChatMessage("system", "policy"))
            repeat(12) { add(ChatMessage("user", "u".repeat(2_000))); add(ChatMessage("assistant", "a".repeat(2_000))) }
            add(ChatMessage("user", "current"))
        }
        val result = FinalPromptAssembler.assemble(FinalPromptRequest(messages, FinalPromptBudget("4b", false, 512)))
        assertTrue(result is PromptAssemblyResult.Ready)
        val ready = result as PromptAssemblyResult.Ready
        assertTrue(ready.messages.size % 2 == 0)
        assertEquals((messages.size - ready.messages.size) / 2, ready.contextsRemoved)
    }

    @Test
    fun `uses externaModelContextChars when provided instead of model name heuristic`() {
        val messages = buildList {
            add(ChatMessage("system", "s"))
            add(ChatMessage("user", "u".repeat(10_000)))
            add(ChatMessage("assistant", "a"))
        }
        val result = FinalPromptBudgeter.fit(messages, FinalPromptBudget("4b", false, 512, externaModelContextChars = 128_000))
        assertEquals(3, result.messages.size)
    }

    @Test
    fun malformed_history_beginning_with_assistant_is_handled() {
        val messages = listOf(
            ChatMessage("assistant", "orphan"),
            ChatMessage("user", "current"),
        )
        val result = FinalPromptBudgeter.fit(messages, FinalPromptBudget(null, false, 512))
        assertTrue(result.messages.last().role == "user")
    }

    @Test
    fun five_attachments_plus_web_search_memory_fits_within_budget() {
        val messages = buildList {
            add(ChatMessage("system", "policy"))
            repeat(5) { add(ChatMessage("user", "u".repeat(500))); add(ChatMessage("assistant", "a".repeat(500))) }
            add(ChatMessage("user", "current"))
        }
        val result = FinalPromptBudgeter.fit(messages, FinalPromptBudget(null, false, 2048))
        assertTrue(result.messages.sumOf { it.content.length } <= 48_000)
    }

    @Test
    fun external_request_uses_48k_budget() {
        val messages = buildList {
            add(ChatMessage("system", "s"))
            repeat(15) { add(ChatMessage("user", "u".repeat(1_500))); add(ChatMessage("assistant", "a".repeat(1_500))) }
            add(ChatMessage("user", "current"))
        }
        val result = FinalPromptBudgeter.fit(messages, FinalPromptBudget(null, true, 4096))
        val totalChars = result.messages.sumOf { it.content.length }
        assertTrue(totalChars <= 40_000)
    }

    // ── KV-aware local budget regression tests ───────────────────────────

    @Test
    fun `default Gemma E2B with default output and KV capacity is Ready not TooLarge`() {
        // Production defaults: Gemma 4 E2B-it, maxOutput=4000, KV=8000.
        // The pre-fix budgeter reserved maxOutput*4 = 16000 against the 16000
        // "2b" char limit, leaving zero prompt capacity and wrongly returning
        // TooLarge for any mandatory system+user prompt. The KV-aware path must
        // produce Ready with the requested output preserved.
        val messages = listOf(
            ChatMessage("system", "You are a concise assistant."),
            ChatMessage("user", "Hello"),
        )
        val result = FinalPromptAssembler.assemble(
            FinalPromptRequest(
                messages,
                FinalPromptBudget(
                    modelId = "Gemma 4 E2B-it",
                    external = false,
                    maxOutputTokens = 4000,
                    engineKvCapacityTokens = 8000,
                ),
            )
        )
        assertTrue("Expected Ready, got $result", result is PromptAssemblyResult.Ready)
        val ready = result as PromptAssemblyResult.Ready
        assertEquals(4000, ready.requestedOutputTokens)
        assertEquals(4000, ready.appliedOutputTokens)
        assertEquals(OutputAdjustmentReason.None, ready.adjustmentReason)
    }

    @Test
    fun `prompt estimate exceeding KV capacity returns typed TooLarge when mandatory alone cannot fit`() {
        // mandatory prompt tokens alone exceed the KV capacity even with
        // output reduced to 1 token -> typed TooLarge, not a near-empty Ready.
        // 12_000 chars of system content ~ 3_000+ tokens; KV = 1024 tokens.
        val messages = listOf(
            ChatMessage("system", "s".repeat(12_000)),
            ChatMessage("user", "current"),
        )
        val result = FinalPromptBudgeter.fit(
            messages,
            FinalPromptBudget(
                modelId = "Gemma 4 E2B-it",
                external = false,
                maxOutputTokens = 1024,
                engineKvCapacityTokens = 1024,
            ),
        )
        assertTrue("Expected TooLarge for mandatory-only overflow", result.isTooLarge)
        assertTrue(result.messages.isEmpty())
    }

    @Test
    fun `prompt estimate exceeding KV capacity trims history when mandatory still fits`() {
        // mandatory prompt fits KV but full history does not -> history is
        // trimmed to the most recent whole turns; output may be reduced but the
        // request remains Ready.
        val messages = buildList {
            add(ChatMessage("system", "policy"))
            repeat(20) { add(ChatMessage("user", "u".repeat(1_000))); add(ChatMessage("assistant", "a".repeat(1_000))) }
            add(ChatMessage("user", "current"))
        }
        val result = FinalPromptBudgeter.fit(
            messages,
            FinalPromptBudget(
                modelId = "Gemma 4 E2B-it",
                external = false,
                maxOutputTokens = 1024,
                engineKvCapacityTokens = 2048,
            ),
        )
        assertFalse("Expected Ready (trimmed), got TooLarge", result.isTooLarge)
        assertEquals("policy", result.messages.first().content)
        assertEquals("current", result.messages.last().content)
    }

    @Test
    fun `prompt plus requested output fits KV leaves requested output unchanged`() {
        val messages = listOf(
            ChatMessage("system", "tiny system prompt"),
            ChatMessage("user", "tiny user message"),
        )
        val result = FinalPromptAssembler.assemble(
            FinalPromptRequest(
                messages,
                FinalPromptBudget(
                    modelId = "Gemma 4 E2B-it",
                    external = false,
                    maxOutputTokens = 1024,
                    engineKvCapacityTokens = 8192,
                ),
            )
        )
        assertTrue(result is PromptAssemblyResult.Ready)
        val ready = result as PromptAssemblyResult.Ready
        assertEquals(1024, ready.requestedOutputTokens)
        assertEquals(1024, ready.appliedOutputTokens)
        assertEquals(OutputAdjustmentReason.None, ready.adjustmentReason)
    }

    @Test
    fun `prompt fits but full requested output does not fit KV reduces applied output and preserves requested`() {
        // KV = 2048 tokens. The prompt consumes enough capacity that full
        // output does not fit, but the selected useful-answer floor does; the
        // requested value is still preserved on the Ready result.
        val messages = buildList {
            add(ChatMessage("system", "s".repeat(800)))
            add(ChatMessage("user", "u".repeat(1_600)))
        }
        val budget = FinalPromptBudget(
            modelId = "Gemma 4 E2B-it",
            external = false,
            maxOutputTokens = 1024,
            engineKvCapacityTokens = 2048,
            safetyMarginTokens = 0,
        )
        val result = FinalPromptAssembler.assemble(FinalPromptRequest(messages, budget))
        assertTrue("Expected Ready, got $result", result is PromptAssemblyResult.Ready)
        val ready = result as PromptAssemblyResult.Ready
        assertEquals("requested must be preserved", 1024, ready.requestedOutputTokens)
        assertTrue(
            "applied must preserve the useful floor (${ready.appliedOutputTokens})",
            ready.appliedOutputTokens in FinalPromptBudgeter.MIN_USEFUL_OUTPUT_TOKENS until 1024,
        )
        assertEquals(OutputAdjustmentReason.ReducedForKvCapacity, ready.adjustmentReason)
    }

    // ── Default E2B multi-turn history retention regression ───────────────

    @Test
    fun `default E2B output 4000 KV 8000 retains previous whole turns`() {
        // Production defaults: Gemma 4 E2B-it, requested output = 4000,
        // effective KV = 8000. The previous behaviour reserved 4000*4 = 16000
        // chars of the 16000-char E2B heuristic and silently dropped every
        // prior turn while still returning Ready. The authoritative capacity
        // for a local LiteRT request is the Engine prompt+output KV budget.
        val messages = listOf(
            ChatMessage("system", "정책"),
            ChatMessage("user", "이전 사용자 질문"),
            ChatMessage("assistant", "이전 답변"),
            ChatMessage("user", "현재 질문"),
        )
        val result = FinalPromptAssembler.assemble(
            FinalPromptRequest(
                messages,
                FinalPromptBudget(
                    modelId = "Gemma 4 E2B-it",
                    external = false,
                    maxOutputTokens = 4000,
                    engineKvCapacityTokens = 8000,
                ),
            )
        )
        assertTrue("Expected Ready, got $result", result is PromptAssemblyResult.Ready)
        val ready = result as PromptAssemblyResult.Ready
        assertEquals(listOf("system", "user", "assistant", "user"), ready.messages.map { it.role })
        assertEquals("정책", ready.messages[0].content)
        assertEquals("이전 사용자 질문", ready.messages[1].content)
        assertEquals("이전 답변", ready.messages[2].content)
        assertEquals("현재 질문", ready.messages[3].content)
        assertEquals(4000, ready.appliedOutputTokens)
        assertEquals(OutputAdjustmentReason.None, ready.adjustmentReason)
    }

    @Test
    fun `default E2B retains the most recent whole turns up to the KV budget`() {
        // Twelve ~2k-char turns fill far more than the 8K KV budget; only the
        // newest WHOLE user/assistant turns may remain, oldest ones dropped as
        // whole turns (never a user split from its answering assistant).
        val messages = buildList {
            add(ChatMessage("system", "정책"))
            repeat(12) { index ->
                add(ChatMessage("user", "u$index-" + "x".repeat(2_000)))
                add(ChatMessage("assistant", "a$index-" + "y".repeat(2_000)))
            }
            add(ChatMessage("user", "현재 질문"))
        }
        val result = FinalPromptAssembler.assemble(
            FinalPromptRequest(
                messages,
                FinalPromptBudget(
                    modelId = "Gemma 4 E2B-it",
                    external = false,
                    maxOutputTokens = 4000,
                    engineKvCapacityTokens = 8000,
                ),
            )
        )
        assertTrue("Expected Ready, got $result", result is PromptAssemblyResult.Ready)
        val ready = result as PromptAssemblyResult.Ready
        // mandatory (system + current user) plus at least one whole previous turn.
        assertTrue(ready.messages.size >= 4)
        assertEquals("정책", ready.messages.first().content)
        assertEquals("현재 질문", ready.messages.last().content)
        // History must never be empty at these defaults, and must be whole turns.
        val history = ready.messages.drop(1).dropLast(1)
        assertTrue("history must retain the newest whole turns", history.isNotEmpty())
        assertTrue(history.size % 2 == 0)
        history.chunked(2).forEach { turn ->
            assertEquals("user", turn[0].role)
            assertEquals("assistant", turn[1].role)
        }
        // Newest previous turn survives even when older turns are trimmed away.
        assertTrue(ready.messages.any { it.content.startsWith("u11-") })
        assertTrue(ready.messages.any { it.content.startsWith("a11-") })
    }

    @Test
    fun `mandatory prompt exceeding Engine KV returns TooLarge even with output clamped to one token`() {
        val messages = buildList {
            add(ChatMessage("system", "s".repeat(8_000)))
            add(ChatMessage("system", "s2".repeat(8_000)))
            add(ChatMessage("system", "s3".repeat(8_000)))
            add(ChatMessage("user", "current"))
        }
        val result = FinalPromptBudgeter.fit(
            messages,
            FinalPromptBudget(
                modelId = "Gemma 4 E2B-it",
                external = false,
                maxOutputTokens = 4000,
                engineKvCapacityTokens = 1024,
            ),
        )
        assertTrue("mandatory prompt alone exceeds KV => TooLarge", result.isTooLarge)
    }

    @Test
    fun `character fitting keeps malformed history in coherent semantic groups`() {
        val messages = listOf(
            ChatMessage("system", "system"),
            ChatMessage("user", "orphan user"),
            ChatMessage("user", "paired user"),
            ChatMessage("assistant", "paired answer"),
            ChatMessage("assistant", "orphan assistant"),
            ChatMessage("user", "current user"),
        )

        val result = FinalPromptBudgeter.fit(
            messages,
            FinalPromptBudget(
                modelId = null,
                external = true,
                maxOutputTokens = 0,
                externaModelContextChars = 200,
            ),
        )

        assertFalse(result.isTooLarge)
        assertEquals(
            listOf("system", "user", "assistant", "user"),
            result.messages.map { it.role },
        )
        assertEquals("paired user", result.messages[1].content)
        assertEquals("paired answer", result.messages[2].content)
        assertEquals("current user", result.messages.last().content)
    }

    @Test
    fun `character fitting always retains the current user with orphan assistant input`() {
        val result = FinalPromptBudgeter.fit(
            listOf(
                ChatMessage("system", "system"),
                ChatMessage("assistant", "orphan"),
                ChatMessage("user", "current"),
            ),
            FinalPromptBudget(
                modelId = null,
                external = true,
                maxOutputTokens = 0,
                externaModelContextChars = 100,
            ),
        )

        assertFalse(result.isTooLarge)
        assertEquals(listOf("system", "user"), result.messages.map { it.role })
        assertEquals("current", result.messages.last().content)
    }
}
