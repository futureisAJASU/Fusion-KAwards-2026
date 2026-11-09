package com.projectnuke.fusion.ui

import com.projectnuke.fusion.audit.*
import com.projectnuke.fusion.data.*
import com.projectnuke.fusion.search.*
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EvidenceUiControllerTest {
    private val message = MessageEntity(1, 10, "assistant", "Acme released ProductX2 in 2026.", 1)
    private fun report(
        owner: MessageEntity = message,
        time: Long = 1,
        verifierVersion: String = EVIDENCE_VERIFIER_VERSION,
        claims: List<EvidenceClaim> = emptyList(),
        candidateWindowTruncated: Boolean = false,
        overlengthSegmentsExcluded: Boolean = false,
    ) = EvidenceReport(
        1, EvidenceFingerprint.canonicalAnswerHash(owner.content), time, verifierVersion, claims, emptyList(),
        candidateWindowTruncated, overlengthSegmentsExcluded,
    )

    @Test fun `candidate window warning does not depend on checked claim count`() {
        val smallPartialReport = report(
            claims = listOf(EvidenceClaim("C1", "one checked claim", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, emptyList(), null)),
            candidateWindowTruncated = true,
        )
        assertTrue(evidenceCandidateWindowWarningVisible(smallPartialReport))
        assertFalse(evidenceCandidateWindowWarningVisible(report(claims = smallPartialReport.claims)))
    }

    @Test fun `zero-claim overlength exclusion is verified and durably reported`() = runTest {
        val owner = message.copy(content = "가".repeat(EvidencePlanningLimits.MAX_CLAIM_TEXT_LENGTH + 1))
        val dao = UiEvidenceDao()
        var executedPlan: VerificationPlan? = null
        var verifyCalls = 0
        val verifier = EvidenceUiVerifier { current, plan ->
            verifyCalls++
            executedPlan = plan
            EvidenceVerificationResult.Complete(
                report(current, claims = emptyList(), overlengthSegmentsExcluded = plan.overlengthSegmentsExcluded),
            )
        }
        val controller = EvidenceUiController(this, DurableEvidenceUiStore(EvidenceStore(dao)), verifier)

        controller.open(owner)
        advanceUntilIdle()

        assertEquals(1, verifyCalls)
        assertTrue(requireNotNull(executedPlan).overlengthSegmentsExcluded)
        assertEquals(EvidenceExecutionState.COMPLETE, controller.state.value.execution)
        assertTrue(requireNotNull(controller.state.value.report).overlengthSegmentsExcluded)
        assertTrue(dao.rows.containsKey(owner.id))
    }

    private suspend fun TestScope.openSourceOnly(
        controller: EvidenceUiController,
        owner: MessageEntity,
    ) {
        controller.open(owner)
        runCurrent()
        controller.searchPrompt.value?.let { prompt ->
            controller.chooseSearch(prompt.operationId, PostAnswerVerificationSearchChoice.EXISTING_SOURCES_ONLY)
        }
        runCurrent()
    }

    private suspend fun TestScope.retrySourceOnly(controller: EvidenceUiController) {
        controller.retry()
        runCurrent()
        controller.searchPrompt.value?.let { prompt ->
            controller.chooseSearch(prompt.operationId, PostAnswerVerificationSearchChoice.EXISTING_SOURCES_ONLY)
        }
        runCurrent()
    }

    private suspend fun TestScope.openWebAuthorized(controller: EvidenceUiController, owner: MessageEntity) {
        controller.open(owner)
        runCurrent()
        controller.searchPrompt.value?.let { prompt ->
            controller.chooseSearch(prompt.operationId, PostAnswerVerificationSearchChoice.WEB)
        }
        runCurrent()
    }

    @Test fun `valid durable report opens without verification or save`() = runTest {
        val dao = UiEvidenceDao()
        val store = EvidenceStore(dao)
        store.saveComplete(message, report())
        val controller = EvidenceUiController(this, DurableEvidenceUiStore(store), EvidenceUiVerifier { _, _ -> error("network called") })
        openSourceOnly(controller, message)
        runCurrent()
        assertEquals(EvidenceExecutionState.COMPLETE, controller.state.value.execution)
        assertEquals(report(), controller.state.value.report)
        assertEquals(1, dao.inserts)
    }

    @Test fun `fresh semantic result stays in UI state and stored report reopens without it`() = runTest {
        val dao = UiEvidenceDao()
        val store = EvidenceStore(dao)
        val transient = semanticSnapshot("C1")
        val verifier = EvidenceUiVerifier { owner, _ -> EvidenceVerificationResult.Complete(report(owner), transient) }
        val controller = EvidenceUiController(this, DurableEvidenceUiStore(store), verifier)

        openSourceOnly(controller, message)
        runCurrent()

        assertEquals(transient, controller.state.value.transientAudit)
        assertEquals(EvidenceStore.LoadResult.Valid(report()), store.load(message))
        assertFalse(dao.rows.getValue(message.id).reportJson.contains("test-experimental"))

        val reopened = EvidenceUiController(
            this,
            DurableEvidenceUiStore(store),
            EvidenceUiVerifier { _, _ -> error("stored reports must not rerun semantic verification") },
        )
        openSourceOnly(reopened, message)
        runCurrent()
        assertEquals(EvidenceExecutionState.COMPLETE, reopened.state.value.execution)
        assertNull(reopened.state.value.transientAudit)
    }

    @Test fun `experimental backend status reaches the existing surface while unavailable remains hidden`() = runTest {
        val source = WebSearchSource(
            title = "Acme launched ProductX2 in 2026.",
            source = "Example", url = "https://example.com/release",
            snippet = "Acme launched ProductX2 in 2026.", publishedAt = null,
            providerType = WebSearchProviderType.FREE_DEFAULT, providerDisplayName = "Free Search",
            searchCategory = null, queryUsed = "ProductX2 release", rank = 1,
        )
        val search = object : EvidenceSearchExecutor {
            override suspend fun search(request: TargetedSearchRequest) = EvidenceSearchResult.Success(listOf(source))
        }
        val experimentalBackend = object : VerifierBackend {
            override val descriptor = VerifierBackendDescriptor("ui-test", "1", setOf("en"), experimental = true)
            override suspend fun verify(request: VerificationPairRequest) =
                VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED)
        }
        val experimentalController = EvidenceUiController(
            this,
            DurableEvidenceUiStore(EvidenceStore(UiEvidenceDao())),
            SearchBackedEvidenceUiVerifier(search, verifierBackend = experimentalBackend),
        )
        openWebAuthorized(experimentalController, message)
        runCurrent()
        val transient = requireNotNull(experimentalController.state.value.transientAudit)
        assertEquals("\uC2E4\uD5D8\uC801 \uC790\uB3D9 \uC758\uBBF8 \uAC80\uC99D", auditSemanticSectionLabel(transient))
        assertEquals(AuditAggregateStatus.CONTRADICTED, transient.displayableClaims.single().aggregateStatus)
        assertEquals(EvidenceVerdict.SUPPORTED_BY_EVIDENCE, experimentalController.state.value.report!!.claims.single().verdict)

        val unavailableController = EvidenceUiController(
            this,
            DurableEvidenceUiStore(EvidenceStore(UiEvidenceDao())),
            SearchBackedEvidenceUiVerifier(search),
        )
        openWebAuthorized(unavailableController, message)
        runCurrent()
        assertNull(auditSemanticSectionLabel(requireNotNull(unavailableController.state.value.transientAudit)))
    }

    @Test fun `semantic result for old answer hash cannot appear under new answer version`() = runTest {
        val dao = UiEvidenceDao()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val controller = EvidenceUiController(this, DurableEvidenceUiStore(EvidenceStore(dao)), EvidenceUiVerifier { owner, _ ->
            if (owner.content == message.content) withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
            EvidenceVerificationResult.Complete(
                report(owner),
                semanticSnapshot("C1").takeIf { owner.content == message.content },
            )
        })
        openSourceOnly(controller, message)
        runCurrent()
        entered.await()
        val nextVersion = message.copy(content = "ProductX2 now has 16GB RAM.")
        openSourceOnly(controller, nextVersion)
        runCurrent()
        release.complete(Unit)
        runCurrent()

        assertEquals(EvidenceAnswerIdentity.of(nextVersion), controller.state.value.identity)
        assertNull(controller.state.value.transientAudit)
        assertEquals(EvidenceFingerprint.canonicalAnswerHash(nextVersion.content), dao.rows.getValue(message.id).answerSha256)
    }

    @Test fun `missing verifies exact message and saves complete`() = runTest {
        val dao = UiEvidenceDao()
        var calls = 0
        val controller = EvidenceUiController(this, DurableEvidenceUiStore(EvidenceStore(dao)), EvidenceUiVerifier { owner, plan ->
            assertEquals(message, owner)
            assertTrue(plan.claims.isNotEmpty())
            calls++
            EvidenceVerificationResult.Complete(report(owner))
        })
        openSourceOnly(controller, message)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(1, dao.inserts)
        assertEquals(EvidenceExecutionState.COMPLETE, controller.state.value.execution)
    }

    @Test fun `failed and cancelled results never save`() = runTest {
        for (result in listOf(EvidenceVerificationResult.Failed(EvidenceVerificationFailureReason.DEADLINE_EXCEEDED), EvidenceVerificationResult.Cancelled)) {
            val dao = UiEvidenceDao()
            val controller = EvidenceUiController(this, DurableEvidenceUiStore(EvidenceStore(dao)), EvidenceUiVerifier { _, _ -> result })
            openSourceOnly(controller, message)
            runCurrent()
            assertEquals(0, dao.inserts)
            assertNull(controller.state.value.report)
            assertEquals(if (result == EvidenceVerificationResult.Cancelled) EvidenceExecutionState.CANCELLED else EvidenceExecutionState.FAILED,
                controller.state.value.execution)
        }
    }

    @Test fun `retry failure and cancellation preserve old complete then success replaces`() = runTest {
        val dao = UiEvidenceDao()
        val store = EvidenceStore(dao)
        store.saveComplete(message, report())
        var result: EvidenceVerificationResult = EvidenceVerificationResult.Failed(EvidenceVerificationFailureReason.DEADLINE_EXCEEDED)
        val controller = EvidenceUiController(this, DurableEvidenceUiStore(store), EvidenceUiVerifier { _, _ -> result })
        openSourceOnly(controller, message)
        runCurrent()
        retrySourceOnly(controller)
        runCurrent()
        assertEquals(EvidenceStore.LoadResult.Valid(report()), store.load(message))
        result = EvidenceVerificationResult.Cancelled
        retrySourceOnly(controller)
        runCurrent()
        assertEquals(1, dao.inserts)
        assertEquals(EvidenceStore.LoadResult.Valid(report()), store.load(message))
        result = EvidenceVerificationResult.Complete(report(time = 2))
        retrySourceOnly(controller)
        runCurrent()
        assertEquals(2, dao.inserts)
        assertEquals(EvidenceStore.LoadResult.Valid(report(time = 2)), store.load(message))
    }

    @Test fun `stale and corrupt require explicit retry and never show old verdicts`() = runTest {
        for (corrupt in listOf(false, true)) {
            val dao = UiEvidenceDao()
            val store = EvidenceStore(dao)
            store.saveComplete(message, report())
            if (corrupt) dao.rows[message.id] = dao.rows.getValue(message.id).copy(reportJson = "{broken")
            val oldBytes = dao.rows.getValue(message.id)
            val current = if (corrupt) message else message.copy(content = "Changed answer.")
            var calls = 0
            val controller = EvidenceUiController(this, DurableEvidenceUiStore(store), EvidenceUiVerifier { owner, _ ->
                calls++
                EvidenceVerificationResult.Complete(report(owner))
            })
            openSourceOnly(controller, current)
            runCurrent()
            assertEquals(0, calls)
            assertNull(controller.state.value.report)
            assertEquals(if (corrupt) EvidenceStoredState.CORRUPT else EvidenceStoredState.STALE, controller.state.value.stored)
            assertEquals(oldBytes, dao.rows[message.id])
            retrySourceOnly(controller)
            runCurrent()
            assertEquals(1, calls)
            assertEquals(EvidenceExecutionState.COMPLETE, controller.state.value.execution)
        }
    }

    @Test fun `old verifier report is outdated until explicit recheck and survives failed retry`() = runTest {
        val dao = UiEvidenceDao()
        val store = EvidenceStore(dao)
        val oldReport = report(verifierVersion = "rules-v1")
        store.saveComplete(message, oldReport)
        val before = dao.rows.getValue(message.id)
        var calls = 0
        var verificationResult: EvidenceVerificationResult =
            EvidenceVerificationResult.Failed(EvidenceVerificationFailureReason.DEADLINE_EXCEEDED)
        val controller = EvidenceUiController(
            this,
            DurableEvidenceUiStore(store),
            EvidenceUiVerifier { _, _ ->
                calls++
                verificationResult
            },
            EvidenceClock { 2_000L },
        )

        openSourceOnly(controller, message)
        runCurrent()
        assertEquals(EvidenceStoredState.OUTDATED, controller.state.value.stored)
        assertNull(controller.state.value.report)
        assertEquals(0, calls)

        retrySourceOnly(controller)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(before, dao.rows.getValue(message.id))
        assertEquals(EvidenceStoredState.OUTDATED, controller.state.value.stored)

        verificationResult = EvidenceVerificationResult.Complete(report(time = 3L))
        retrySourceOnly(controller)
        runCurrent()
        assertEquals(EVIDENCE_VERIFIER_VERSION, dao.rows.getValue(message.id)?.verifierVersion)
        assertEquals(EvidenceExecutionState.COMPLETE, controller.state.value.execution)
    }

    @Test fun `current sensitive report expires while historical report remains valid`() = runTest {
        val now = Instant.parse("2026-09-05T00:00:00Z").toEpochMilli()
        val currentMessage = message.copy(content = "Acme currently supports ProductX2.")
        val currentClaim = EvidenceClaim(
            "C1",
            currentMessage.content,
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            emptyList(),
            null,
        )
        val oldCurrent = report(
            owner = currentMessage,
            time = now - EvidenceReportCompatibilityPolicy.CURRENT_SENSITIVE_MAX_AGE_MS - 1L,
            claims = listOf(currentClaim),
        )
        val historical = report(time = 1L)
        val dao = UiEvidenceDao()
        val store = EvidenceStore(dao)
        store.saveComplete(currentMessage, oldCurrent)
        val currentController = EvidenceUiController(
            this,
            DurableEvidenceUiStore(store),
            EvidenceUiVerifier { _, _ -> error("network called") },
            EvidenceClock { now },
        )
        openSourceOnly(currentController, currentMessage)
        runCurrent()
        assertEquals(EvidenceStoredState.OUTDATED, currentController.state.value.stored)
        assertNull(currentController.state.value.report)

        val historicalDao = UiEvidenceDao()
        val historicalStore = EvidenceStore(historicalDao)
        historicalStore.saveComplete(message, historical)
        val historicalController = EvidenceUiController(
            this,
            DurableEvidenceUiStore(historicalStore),
            EvidenceUiVerifier { _, _ -> error("network called") },
            EvidenceClock { now },
        )
        openSourceOnly(historicalController, message)
        runCurrent()
        assertEquals(EvidenceStoredState.VALID, historicalController.state.value.stored)
        assertEquals(historical, historicalController.state.value.report)
    }

    @Test fun `regenerated ids own independent durable reports`() = runTest {
        val dao = UiEvidenceDao()
        val store = EvidenceStore(dao)
        val controller = EvidenceUiController(this, DurableEvidenceUiStore(store), EvidenceUiVerifier { owner, _ -> EvidenceVerificationResult.Complete(report(owner)) })
        openSourceOnly(controller, message)
        runCurrent()
        val version = message.copy(id = 2)
        openSourceOnly(controller, version)
        runCurrent()
        assertEquals(setOf(1L, 2L), dao.rows.keys)
        assertEquals(2L, controller.state.value.identity?.messageId)
    }

    @Test fun `late wrong-message result cannot save or overwrite new owner`() = runTest {
        val dao = UiEvidenceDao()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val controller = EvidenceUiController(this, DurableEvidenceUiStore(EvidenceStore(dao)), EvidenceUiVerifier { owner, _ ->
            if (owner.id == 1L) withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
            EvidenceVerificationResult.Complete(report(owner))
        })
        openSourceOnly(controller, message)
        runCurrent()
        entered.await()
        openSourceOnly(controller, message.copy(id = 2, content = "Acme released ProductY in 2026."))
        runCurrent()
        assertEquals(2L, controller.state.value.identity?.messageId)
        release.complete(Unit)
        runCurrent()
        assertEquals(setOf(2L), dao.rows.keys)
        assertEquals(2L, controller.state.value.identity?.messageId)
        assertEquals(EvidenceExecutionState.COMPLETE, controller.state.value.execution)
    }

    @Test fun `dismiss propagates cancellation and settles job without save`() = runTest {
        val dao = UiEvidenceDao()
        val settled = CompletableDeferred<Unit>()
        val controller = EvidenceUiController(this, DurableEvidenceUiStore(EvidenceStore(dao)), EvidenceUiVerifier { _, _ ->
            try { awaitCancellation() } finally { settled.complete(Unit) }
        })
        openSourceOnly(controller, message)
        runCurrent()
        controller.dismiss()
        runCurrent()
        settled.await()
        assertEquals(EvidenceUiState(), controller.state.value)
        assertEquals(0, dao.inserts)
    }

    @Test fun `changed hash complete is rejected without saving`() = runTest {
        val dao = UiEvidenceDao()
        val controller = EvidenceUiController(this, DurableEvidenceUiStore(EvidenceStore(dao)), EvidenceUiVerifier { _, _ ->
            EvidenceVerificationResult.Complete(report(message.copy(content = "Other")))
        })
        openSourceOnly(controller, message)
        runCurrent()
        assertEquals(EvidenceExecutionState.CANCELLED, controller.state.value.execution)
        assertEquals(0, dao.inserts)
    }

    @Test fun `persistence exception preserves assistant and old report`() = runTest {
        val dao = UiEvidenceDao()
        val store = EvidenceStore(dao)
        store.saveComplete(message, report())
        dao.failInsert = true
        val controller = EvidenceUiController(this, DurableEvidenceUiStore(store), EvidenceUiVerifier { _, _ -> EvidenceVerificationResult.Complete(report(time = 2)) })
        openSourceOnly(controller, message)
        runCurrent()
        retrySourceOnly(controller)
        runCurrent()
        assertEquals(EvidenceExecutionState.FAILED, controller.state.value.execution)
        assertEquals(EvidenceStore.LoadResult.Valid(report()), store.load(message))
        assertEquals("Acme released ProductX2 in 2026.", message.content)
    }

    @Test fun `no checkable claims completes empty without search`() = runTest {
        val dao = UiEvidenceDao()
        val verifier = SearchBackedEvidenceUiVerifier(object : EvidenceSearchExecutor {
            override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult = error("unexpected search")
        })
        val controller = EvidenceUiController(this, DurableEvidenceUiStore(EvidenceStore(dao)), verifier)
        openSourceOnly(controller, message.copy(content = "안녕하세요"))
        runCurrent()
        assertEquals(EvidenceExecutionState.COMPLETE, controller.state.value.execution)
        assertNull(controller.state.value.report)
        assertEquals("확인할 사실 주장을 찾지 못했습니다.", controller.state.value.notice)
    }

    @Test fun `strong attached evidence runs without a web authorization prompt`() = runTest {
        val attached = WebSearchSource(
            title = "Acme ProductX2 2026 release",
            source = "Example",
            url = "https://example.com/release",
            snippet = "Acme released ProductX2 in 2026.",
            publishedAt = null,
            providerType = WebSearchProviderType.FREE_DEFAULT,
            providerDisplayName = "Search",
            searchCategory = null,
            queryUsed = "Acme ProductX2 2026",
            rank = 1,
        )
        val withAttachedEvidence = message.copy(
            content = appendSearchSourcesMetadata(message.content, listOf(attached)),
        )
        var searchCalls = 0
        val controller = EvidenceUiController(
            this,
            DurableEvidenceUiStore(EvidenceStore(UiEvidenceDao())),
            SearchBackedEvidenceUiVerifier(object : EvidenceSearchExecutor {
                override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                    searchCalls++
                    error("attached evidence must not trigger web search")
                }
            }),
        )

        controller.open(withAttachedEvidence)
        runCurrent()

        assertNull(controller.searchPrompt.value)
        assertEquals(0, searchCalls)
        assertEquals(EvidenceExecutionState.COMPLETE, controller.state.value.execution)
        assertTrue(controller.state.value.report!!.claims.isNotEmpty())
    }

    @Test fun `network failure becomes operation failed without report`() = runTest {
        val dao = UiEvidenceDao()
        var searchCalls = 0
        val verifier = SearchBackedEvidenceUiVerifier(object : EvidenceSearchExecutor {
            override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                searchCalls++
                return EvidenceSearchResult.Failed("secret raw provider diagnostic")
            }
        })
        val controller = EvidenceUiController(this, DurableEvidenceUiStore(EvidenceStore(dao)), verifier)
        controller.open(message)
        runCurrent()
        val prompt = requireNotNull(controller.searchPrompt.value)
        assertEquals(0, dao.inserts)
        assertEquals(0, (controller.state.value.report?.claims?.size ?: 0))
        controller.chooseSearch(prompt.operationId, PostAnswerVerificationSearchChoice.WEB)
        runCurrent()
        assertEquals(EvidenceExecutionState.FAILED, controller.state.value.execution)
        assertTrue(searchCalls > 0)
        assertEquals(0, dao.inserts)
        assertNull(controller.state.value.report)
    }

    @Test fun `production adapter distinguishes transport failure from successful empty search`() {
        val response = FusionSearchResponse("q", "q", SearchIntent.GENERAL, emptyList(), null)
        assertTrue(response.toEvidenceSearchResult() is EvidenceSearchResult.Success)
        val failed = response.copy(traces = listOf(WebSearchExecutionTrace(WebSearchProviderType.FREE_DEFAULT, "search", "q", exceptionClass = "IOException")))
        assertEquals(EvidenceSearchResult.Failed("SEARCH_UNAVAILABLE"), failed.toEvidenceSearchResult())
    }

    @Test fun `verdict labels and URL policy`() {
        assertEquals(listOf("근거로 확인됨", "상충하는 근거가 있음", "근거 부족", "확인하지 않음"), listOf(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE, EvidenceVerdict.CONFLICTING_EVIDENCE,
            EvidenceVerdict.INSUFFICIENT_EVIDENCE, EvidenceVerdict.NOT_CHECKED,
        ).map { it.evidenceLabel() })
        assertTrue(isEvidenceWebUrl("https://example.com/source"))
        for (url in listOf("javascript:alert(1)", "intent://app", "file:///secret", "https://user:pass@example.com", "x".repeat(5000))) {
            assertFalse(isEvidenceWebUrl(url))
        }
    }

    @Test fun `bounded verification and source limitations have explicit safe labels`() {
        for (reason in listOf(
            "CLAIM_BUDGET_EXHAUSTED",
            "SEARCH_BUDGET_EXHAUSTED",
            "SEARCH_FAILED",
            "SEARCH_SUPPRESSED_PRIVATE_SOURCE",
            "NO_USABLE_SOURCE",
            "INSUFFICIENT_RULE_ALIGNMENT",
        )) {
            assertTrue(reason, !evidenceClaimReasonLabel(reason).isNullOrBlank())
        }
        assertEquals(null, evidenceClaimReasonLabel("secret raw provider diagnostic"))
        assertEquals(null, evidenceClaimReasonLabel(null))
    }

    @Test fun `private text in derived query fails closed to source-only verification`() = runTest {
        val dao = UiEvidenceDao()
        var searchCalls = 0
        val search = object : EvidenceSearchExecutor {
            override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                searchCalls++
                return EvidenceSearchResult.Success(emptyList())
            }
        }
        val privateText = "ProjectX"
        val controller = EvidenceUiController(
            this,
            DurableEvidenceUiStore(EvidenceStore(dao)),
            SearchBackedEvidenceUiVerifier(search),
            privateTextsProvider = { listOf(privateText) },
        )
        openSourceOnly(controller, message.copy(content = "The budget for ProjectX is $5M."))
        runCurrent()
        assertNull(controller.searchPrompt.value)
        assertEquals(0, searchCalls)
        assertEquals(EvidenceExecutionState.COMPLETE, controller.state.value.execution)
        assertTrue(controller.state.value.notice.orEmpty().contains("개인 자료"))
        assertEquals(0, searchCalls)
    }

    @Test fun `private-derived answer never offers web escalation`() = runTest {
        val dao = UiEvidenceDao()
        var searchCalls = 0
        val search = object : EvidenceSearchExecutor {
            override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                searchCalls++
                return EvidenceSearchResult.Success(emptyList())
            }
        }
        val receipt = com.projectnuke.fusion.chat.AnswerContextReceipt.build(
            route = com.projectnuke.fusion.chat.AnswerContextRoute.LOCAL_MODEL,
            localRagDocumentCount = 1,
            localRagChunkCount = 1,
        )
        val privateMessage = message.copy(
            content = com.projectnuke.fusion.chat.AnswerContextReceipt.append(
                "Acme released ProductX2 in 2026.",
                receipt,
            ),
        )
        val controller = EvidenceUiController(
            this,
            DurableEvidenceUiStore(EvidenceStore(dao)),
            SearchBackedEvidenceUiVerifier(search),
        )
        openSourceOnly(controller, privateMessage)
        runCurrent()
        assertNull(controller.searchPrompt.value)
        assertEquals(EvidenceExecutionState.COMPLETE, controller.state.value.execution)
        assertTrue(controller.state.value.notice.orEmpty().contains("웹 검색으로 보내지 않습니다"))
        assertEquals(0, searchCalls)
    }

    @Test fun `public targeted queries wait for explicit confirmation before search`() = runTest {
        val dao = UiEvidenceDao()
        var searchCalls = 0
        val search = object : EvidenceSearchExecutor {
            override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                searchCalls++
                return EvidenceSearchResult.Success(emptyList())
            }
        }
        val controller = EvidenceUiController(
            this,
            DurableEvidenceUiStore(EvidenceStore(dao)),
            SearchBackedEvidenceUiVerifier(search),
            privateTextsProvider = { listOf("private_text") },
        )
        controller.open(message)
        runCurrent()
        val prompt = requireNotNull(controller.searchPrompt.value)
        assertTrue(prompt.queries.size in 1..EvidencePlanningLimits.MAX_TARGETED_SEARCH_REQUESTS)
        assertTrue(prompt.queries.all { it.query.length <= EvidencePlanningLimits.MAX_TARGETED_QUERY_LENGTH })
        assertEquals(0, searchCalls)
        assertEquals(EvidenceExecutionState.AWAITING_SEARCH_AUTHORIZATION, controller.state.value.execution)
        controller.cancel()
        runCurrent()
    }

    @Test fun `existing sources choice verifies locally and never calls web search`() = runTest {
        val dao = UiEvidenceDao()
        var searchCalls = 0
        var executedPlan: VerificationPlan? = null
        val executor = object : EvidenceSearchExecutor {
            override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                searchCalls++
                return EvidenceSearchResult.Success(emptyList())
            }
        }
        val searchBackedVerifier = SearchBackedEvidenceUiVerifier(executor)
        val verifier = EvidenceUiVerifier { owner, plan ->
            executedPlan = plan
            searchBackedVerifier.verify(owner, plan)
        }
        val controller = EvidenceUiController(this, DurableEvidenceUiStore(EvidenceStore(dao)), verifier)

        controller.open(message)
        runCurrent()
        val prompt = requireNotNull(controller.searchPrompt.value)
        controller.chooseSearch(prompt.operationId, PostAnswerVerificationSearchChoice.EXISTING_SOURCES_ONLY)
        runCurrent()

        assertEquals(0, searchCalls)
        assertTrue(requireNotNull(executedPlan).targetedSearchRequests.isEmpty())
        assertTrue(executedPlan!!.claims.none { it.needsTargetedSearch })
        assertEquals(EvidenceExecutionState.COMPLETE, controller.state.value.execution)
        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, controller.state.value.report!!.claims.single().verdict)
    }

    @Test fun `web choice sends only the exact frozen approved queries`() = runTest {
        val dao = UiEvidenceDao()
        val outbound = mutableListOf<TargetedSearchRequest>()
        val executor = object : EvidenceSearchExecutor {
            override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                outbound += request
                return EvidenceSearchResult.Success(emptyList())
            }
        }
        val controller = EvidenceUiController(
            this,
            DurableEvidenceUiStore(EvidenceStore(dao)),
            SearchBackedEvidenceUiVerifier(executor),
            operationIdFactory = { "fact-check-operation-1" },
        )

        controller.open(message)
        runCurrent()
        val prompt = requireNotNull(controller.searchPrompt.value)
        val approved = prompt.queries.map { TargetedSearchRequest(it.claimId, it.query) }
        assertTrue(outbound.isEmpty())
        controller.chooseSearch(prompt.operationId, PostAnswerVerificationSearchChoice.WEB)
        runCurrent()

        assertEquals(approved, outbound)
        assertEquals(EvidenceExecutionState.COMPLETE, controller.state.value.execution)
        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, controller.state.value.report!!.claims.single().verdict)
    }

    @Test fun `unauthorized or mutated targeted plan is refused before outbound search`() = runTest {
        val outbound = mutableListOf<TargetedSearchRequest>()
        val verifier = SearchBackedEvidenceUiVerifier(object : EvidenceSearchExecutor {
            override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                outbound += request
                return EvidenceSearchResult.Success(emptyList())
            }
        })
        val plan = EvidenceVerificationPlanner.plan(message.content)
        val noAuthorization = verifier.verify(message, plan)
        assertEquals(EvidenceVerificationFailureReason.EGRESS_REFUSED, (noAuthorization as EvidenceVerificationResult.Failed).reason)

        val egressIdentity = com.projectnuke.fusion.privacy.PostAnswerVerificationSearchIdentity(
            message.conversationId,
            message.id,
            EvidenceFingerprint.canonicalAnswerHash(message.content),
            message.id,
        )
        val approved = plan.targetedSearchRequests.map {
            com.projectnuke.fusion.privacy.AuthorizedTargetedQuery(it.claimId, it.query)
        }
        val authorization = requireNotNull(
            com.projectnuke.fusion.privacy.PostAnswerVerificationSearchAuthorization.afterExplicitConfirmation(
                "operation-a", egressIdentity, approved,
            ),
        )
        val mutatedPlan = plan.copy(
            targetedSearchRequests = plan.targetedSearchRequests.mapIndexed { index, request ->
                if (index == 0) request.copy(query = request.query + " altered") else request
            },
        )
        val mutated = verifier.verifyWithSearchAuthorization(message, mutatedPlan, "operation-a", authorization) { true }
        assertEquals(EvidenceVerificationFailureReason.EGRESS_REFUSED, (mutated as EvidenceVerificationResult.Failed).reason)
        val reusedForAnotherOperation = verifier.verifyWithSearchAuthorization(message, plan, "operation-b", authorization) { true }
        assertEquals(EvidenceVerificationFailureReason.EGRESS_REFUSED, (reusedForAnotherOperation as EvidenceVerificationResult.Failed).reason)
        assertTrue(outbound.isEmpty())
    }

    @Test fun `answer deletion while confirmation is open drops the operation`() = runTest {
        var currentMessage: MessageEntity? = message
        var searchCalls = 0
        val dao = UiEvidenceDao()
        val controller = EvidenceUiController(
            this,
            DurableEvidenceUiStore(EvidenceStore(dao)),
            SearchBackedEvidenceUiVerifier(object : EvidenceSearchExecutor {
                override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                    searchCalls++
                    return EvidenceSearchResult.Success(emptyList())
                }
            }),
            currentMessageReader = { currentMessage },
        )
        controller.open(message)
        runCurrent()
        val prompt = requireNotNull(controller.searchPrompt.value)
        currentMessage = null
        controller.chooseSearch(prompt.operationId, PostAnswerVerificationSearchChoice.WEB)
        runCurrent()

        assertEquals(0, searchCalls)
        assertEquals(0, dao.inserts)
        assertEquals(EvidenceExecutionState.CANCELLED, controller.state.value.execution)
    }

    @Test fun `cancellation during authorized search propagates and never persists`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val settled = CompletableDeferred<Unit>()
        val dao = UiEvidenceDao()
        val controller = EvidenceUiController(
            this,
            DurableEvidenceUiStore(EvidenceStore(dao)),
            SearchBackedEvidenceUiVerifier(object : EvidenceSearchExecutor {
                override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        settled.complete(Unit)
                    }
                }
            }),
        )
        controller.open(message)
        runCurrent()
        val prompt = requireNotNull(controller.searchPrompt.value)
        controller.chooseSearch(prompt.operationId, PostAnswerVerificationSearchChoice.WEB)
        runCurrent()
        entered.await()
        controller.cancel()
        runCurrent()
        settled.await()

        assertEquals(0, dao.inserts)
        assertNull(controller.state.value.report)
        assertEquals(EvidenceExecutionState.CANCELLED, controller.state.value.execution)
    }

    @Test fun `answer mutation during search drops the completed report`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dao = UiEvidenceDao()
        var currentMessage: MessageEntity? = message
        val controller = EvidenceUiController(
            this,
            DurableEvidenceUiStore(EvidenceStore(dao)),
            SearchBackedEvidenceUiVerifier(object : EvidenceSearchExecutor {
                override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                    entered.complete(Unit)
                    release.await()
                    return EvidenceSearchResult.Success(emptyList())
                }
            }),
            currentMessageReader = { currentMessage },
        )
        controller.open(message)
        runCurrent()
        val prompt = requireNotNull(controller.searchPrompt.value)
        controller.chooseSearch(prompt.operationId, PostAnswerVerificationSearchChoice.WEB)
        runCurrent()
        entered.await()
        currentMessage = message.copy(content = "Acme released a different version in 2026.")
        release.complete(Unit)
        runCurrent()

        assertEquals(0, dao.inserts)
        assertNull(controller.state.value.report)
        assertEquals(EvidenceExecutionState.CANCELLED, controller.state.value.execution)
    }

    @Test fun `duplicate taps share the same active operation`() = runTest {
        var searchCalls = 0
        val controller = EvidenceUiController(
            this,
            DurableEvidenceUiStore(EvidenceStore(UiEvidenceDao())),
            SearchBackedEvidenceUiVerifier(object : EvidenceSearchExecutor {
                override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                    searchCalls++
                    return EvidenceSearchResult.Success(emptyList())
                }
            }),
        )
        controller.open(message)
        runCurrent()
        val firstPrompt = requireNotNull(controller.searchPrompt.value)
        controller.open(message)
        runCurrent()
        assertEquals(firstPrompt.operationId, controller.searchPrompt.value?.operationId)
        assertEquals(0, searchCalls)
        controller.cancel()
        runCurrent()
    }

    @Test fun `cancelling exact-query confirmation performs zero web calls`() = runTest {
        val dao = UiEvidenceDao()
        var searchCalls = 0
        val search = object : EvidenceSearchExecutor {
            override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                searchCalls++
                return EvidenceSearchResult.Success(emptyList())
            }
        }
        val controller = EvidenceUiController(
            this,
            DurableEvidenceUiStore(EvidenceStore(dao)),
            SearchBackedEvidenceUiVerifier(search),
        )
        controller.open(message)
        runCurrent()
        val prompt = requireNotNull(controller.searchPrompt.value)
        controller.chooseSearch(prompt.operationId, PostAnswerVerificationSearchChoice.CANCEL)
        runCurrent()
        assertEquals(EvidenceExecutionState.CANCELLED, controller.state.value.execution)
        assertNull(controller.searchPrompt.value)
        assertEquals(0, dao.inserts)
        assertEquals(0, searchCalls)
    }

    @Test fun `public-only receipt skips consent without private provider`() = runTest {
        val dao = UiEvidenceDao()
        val search = object : EvidenceSearchExecutor {
            override suspend fun search(request: TargetedSearchRequest) = EvidenceSearchResult.Success(emptyList())
        }
        val controller = EvidenceUiController(
            this,
            DurableEvidenceUiStore(EvidenceStore(dao)),
            SearchBackedEvidenceUiVerifier(search),
        )
        val receiptContent = com.projectnuke.fusion.chat.AnswerContextReceipt.build(
            route = com.projectnuke.fusion.chat.AnswerContextRoute.EXTERNAL_AI_API,
            interactionMode = com.projectnuke.fusion.chat.ChatInteractionMode.NORMAL?.name,
        ).let { receipt ->
            com.projectnuke.fusion.chat.AnswerContextReceipt.append("안녕하세요.", receipt)
        }
        openSourceOnly(controller, MessageEntity(1, 10, "assistant", receiptContent, 1))
        runCurrent()
        assertNull(controller.searchPrompt.value)
        assertEquals(EvidenceExecutionState.COMPLETE, controller.state.value.execution)
        assertEquals("확인할 사실 주장을 찾지 못했습니다.", controller.state.value.notice)
    }

    private fun semanticSnapshot(claimId: String) = AnswerAuditExecutionSnapshot(
        listOf(
            ClaimSemanticVerification(
                claimId = claimId,
                backend = VerifierBackendDescriptor("test-experimental", "1", setOf("en"), experimental = true),
                aggregateStatus = AuditAggregateStatus.CONTRADICTED,
                expectedEvidenceIdentities = listOf("E1"),
                pairExecutions = listOf(
                    VerifierPairExecution("E1", VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED)),
                ),
            ),
        ),
    )
}

private class UiEvidenceDao : EvidenceDao {
    val rows = mutableMapOf<Long, MessageEvidenceEntity>()
    var inserts = 0
    var failInsert = false
    override suspend fun insertEvidence(evidence: MessageEvidenceEntity) {
        if (failInsert) error("storage unavailable")
        inserts++
        rows[evidence.messageId] = evidence
    }
    override suspend fun getEvidenceForMessage(messageId: Long) = rows[messageId]
    override fun observeEvidenceForConversation(conversationId: Long) = flowOf(rows.values.toList())
    override fun observeEvidenceRevisionsForConversation(conversationId: Long) = flowOf(rows.values.map {
        MessageEvidenceRevision(it.messageId, it.answerSha256, it.schemaVersion, it.verifierVersion, it.checkedAt, it.reportJson.length)
    })
    override suspend fun getEvidenceForConversation(conversationId: Long) = rows.values.toList()
    override suspend fun deleteEvidenceForMessage(messageId: Long) { error("must never delete") }
}
