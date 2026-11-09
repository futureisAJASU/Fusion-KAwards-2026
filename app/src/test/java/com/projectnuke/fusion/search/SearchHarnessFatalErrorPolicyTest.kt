package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.SearchStrategy
import com.projectnuke.fusion.chat.SearchStrategySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SearchHarnessFatalErrorPolicyTest {
    @Test
    fun structuredJsonParserMustNotConvertFatalOrCancellationErrorsIntoMalformedInput() = runTest {
        val oom = OutOfMemoryError("synthetic parser OOM")
        expectSameFailure(oom) {
            SearchHarnessProtocol.parseStructuredIntent("{}", jsonObjectParser = { throw oom })
        }
        val linkage = LinkageError("synthetic parser linkage")
        expectSameFailure(linkage) {
            SearchHarnessProtocol.parseStructuredIntent("{}", jsonObjectParser = { throw linkage })
        }
        expectCancellation("parser cancelled") {
            SearchHarnessProtocol.parseStructuredIntent("{}", jsonObjectParser = {
                throw CancellationException("parser cancelled")
            })
        }
        val recoverable = SearchHarnessProtocol.parseStructuredIntent("{}", jsonObjectParser = {
            throw IllegalStateException("recoverable parser failure")
        })
        assertEquals("malformed_json", recoverable.errorCode)
    }

    @Test
    fun recoverablePlannerFailureRemainsABoundedModelFailure() = runTest {
        val result = AgenticSearchHarness(
            model = throwingModel(IllegalStateException("recoverable")),
            searchTool = noSearch(),
            queryAuthorizer = allowAll(),
        ).execute(request())

        assertEquals(AgenticSearchOutcome.MODEL_UNAVAILABLE, result.outcome)
        assertEquals("planner_exception", result.diagnostics.failureCode)
    }

    @Test
    fun fatalPlannerErrorsPropagateInsteadOfBecomingModelUnavailable() = runTest {
        val oom = OutOfMemoryError("synthetic OOM")
        expectSameFailure(oom) {
            AgenticSearchHarness(throwingModel(oom), noSearch(), allowAll()).execute(request())
        }
        val linkage = LinkageError("synthetic linkage")
        expectSameFailure(linkage) {
            AgenticSearchHarness(throwingModel(linkage), noSearch(), allowAll()).execute(request())
        }
    }

    @Test
    fun cancellationFromPlannerPropagates() = runTest {
        val cancelled = CancellationException("cancel search planning")
        expectCancellation("cancel search planning") {
            AgenticSearchHarness(throwingModel(cancelled), noSearch(), allowAll()).execute(request())
        }
    }

    @Test
    fun fatalExactTransportErrorPropagatesInsteadOfBecomingToolFailure() = runTest {
        val model = oneSearchModel()
        val fatal = OutOfMemoryError("transport OOM")
        expectSameFailure(fatal) {
            AgenticSearchHarness(
                model,
                AgenticExactSearchTool { _, _, _ -> throw fatal },
                allowAll(),
            ).execute(request())
        }
    }

    @Test
    fun fatalLegacyAttemptAndLegacyWrapperErrorsPropagate() = runTest {
        val fatalAttempt = LinkageError("legacy transport linkage")
        val legacy = RuleBasedLegacySearchFallback(
            exactSearchTool = AgenticExactSearchTool { _, _, _ -> throw fatalAttempt },
            queryAuthorizer = allowAll(),
        )
        expectSameFailure(fatalAttempt) {
            legacy.search(
                LegacySearchFallbackRequest(
                    agentRequest = request(),
                    previousUserMessage = null,
                    safePriorUserTurns = emptyList(),
                    excludedQueryIdentities = emptySet(),
                    maxAdditionalQueries = 1,
                ),
            )
        }

        val fatalWrapper = OutOfMemoryError("fallback wrapper OOM")
        val router = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { _, _, _ -> error("no direct search expected") },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor { throw fatalWrapper },
        )
        expectSameFailure(fatalWrapper) {
            router.execute(
                SearchStrategyRouteRequest(
                    agentRequest = request(),
                    strategy = SearchStrategySnapshot(
                        strategy = SearchStrategy.LEGACY_RULE_BASED,
                        allowLegacyFallback = true,
                        manualQuery = com.projectnuke.fusion.chat.ManualSearchQueryValidation.Missing,
                    ),
                ),
                agentModel = null,
            )
        }
    }

    private fun throwingModel(failure: Throwable) = object : SearchAgentModel {
        override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
        override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply = throw failure
    }

    private fun oneSearchModel() = object : SearchAgentModel {
        override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
        override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
            SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["public query"]}""")
    }

    private fun noSearch() = AgenticExactSearchTool { _, _, _ -> error("no search expected") }

    private fun request() = AgenticSearchRequest(
        requestId = "fatal-policy-request",
        originalUserQuery = "public query",
        webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
        limits = AgenticSearchLimits.EXTERNAL_DEFAULT,
        ownerStillActive = { true },
        searchPermissionStillValid = { true },
    )

    private fun allowAll() = AgenticSearchQueryAuthorizer { _, query -> AgenticQueryAuthorization.Authorized(query) }

    private suspend fun expectSameFailure(expected: Throwable, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected the original ${expected::class.java.simpleName} to propagate")
        } catch (actual: Throwable) {
            // Coroutine exception propagation may copy Error instances while preserving type and message.
            assertEquals(expected::class, actual::class)
            assertEquals(expected.message, actual.message)
        }
    }

    private suspend fun expectCancellation(expectedMessage: String, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected cancellation to propagate")
        } catch (actual: CancellationException) {
            assertEquals(expectedMessage, actual.message)
        }
    }
}
