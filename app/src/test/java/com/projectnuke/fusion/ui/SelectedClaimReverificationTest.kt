package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.chat.AnswerContextRoute
import com.projectnuke.fusion.data.ClaimClassification
import com.projectnuke.fusion.data.EvidenceClaim
import com.projectnuke.fusion.data.EvidenceClock
import com.projectnuke.fusion.data.EvidenceDao
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceSearchExecutor
import com.projectnuke.fusion.data.EvidenceSearchResult
import com.projectnuke.fusion.data.EvidenceStore
import com.projectnuke.fusion.data.EvidenceVerificationFailureReason
import com.projectnuke.fusion.data.EvidenceVerificationPlanner
import com.projectnuke.fusion.data.EvidenceVerificationResult
import com.projectnuke.fusion.data.EvidenceVerdict
import com.projectnuke.fusion.data.EVIDENCE_VERIFIER_VERSION
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.MessageEvidenceEntity
import com.projectnuke.fusion.data.MessageEvidenceRevision
import com.projectnuke.fusion.data.PostAnswerVerificationSearchExecutor
import com.projectnuke.fusion.data.TargetedSearchRequest
import com.projectnuke.fusion.data.VerificationPlan
import com.projectnuke.fusion.privacy.PostAnswerVerificationSearchAuthorization
import com.projectnuke.fusion.privacy.PostAnswerVerificationSearchIdentity
import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.search.appendSearchSourcesMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * UX-P2B unit coverage for the pure selected-claim execution-plan policy and the
 * narrow single-flight reverification owner.
 *
 * These tests exercise the REAL [SearchBackedEvidenceUiVerifier] +
 * [com.projectnuke.fusion.data.EvidenceVerificationService] seams with only the
 * search transport faked, so verdict mapping, authorization and cancellation are
 * validated end to end. Every test also asserts the durable store is untouched
 * (zero persistence).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SelectedClaimReverificationTest {

    private val message = MessageEntity(1, 10, "assistant", "Acme released ProductX2 in 2026.", 1)

    /**
     * The canonical planner strips the terminal period, so the exact
     * reconstructible claim text (never the raw message body) is the identity
     * every stored claim must match byte for byte.
     */
    private val claim = EvidenceVerificationPlanner.candidates(message.content).first()
    private val identity = EvidenceAnswerIdentity.of(message)

    private fun report(
        owner: MessageEntity = message,
        time: Long = 1L,
        verifierVersion: String = EVIDENCE_VERIFIER_VERSION,
        claims: List<EvidenceClaim> = emptyList(),
        candidateWindowTruncated: Boolean = false,
        overlengthSegmentsExcluded: Boolean = false,
    ) = EvidenceReport(
        schemaVersion = 1,
        answerSha256 = EvidenceFingerprint.canonicalAnswerHash(owner.content),
        checkedAt = time,
        verifierVersion = verifierVersion,
        claims = claims,
        sources = emptyList(),
        candidateWindowTruncated = candidateWindowTruncated,
        overlengthSegmentsExcluded = overlengthSegmentsExcluded,
    )

    private fun storedClaim(
        id: String = claim.id,
        text: String = claim.text,
        verdict: EvidenceVerdict = EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
    ) = EvidenceClaim(id, text, verdict, emptyList(), null)

    private fun eligibleReport(
        owner: MessageEntity = message,
        id: String = claim.id,
        text: String = claim.text,
    ) = report(owner = owner, claims = listOf(storedClaim(id, text)))

    private fun plan(
        message: MessageEntity = this.message,
        identity: EvidenceAnswerIdentity = this.identity,
        report: EvidenceReport = eligibleReport(),
        claimId: String = claim.id,
        nowMillis: Long = 10L,
    ) = SelectedClaimVerificationPolicy.plan(message, identity, report, claimId, nowMillis)

    private fun eligiblePlan(): SelectedClaimExecutionPlan =
        (plan() as SelectedClaimExecutionDecision.Eligible).plan

    private fun ineligibleReason(decision: SelectedClaimExecutionDecision): SelectedClaimIneligibleReason {
        val ineligible = decision as SelectedClaimExecutionDecision.Ineligible
        assertTrue(ineligible.explanation.isNotBlank())
        return ineligible.reason
    }

    // ---------------------------------------------------------------------
    // Pure policy
    // ---------------------------------------------------------------------

    @Test fun `checkable fact is reconstructed as a bounded single-claim plan`() {
        assertEquals(ClaimClassification.CHECKABLE_FACT, claim.classification)
        val decision = plan()
        val execution = (decision as SelectedClaimExecutionDecision.Eligible).plan
        assertEquals(claim.id, execution.claimId)
        assertEquals(claim.text, execution.claimText)
        assertEquals(identity, execution.identity)
        assertEquals(claim, execution.candidate)
    }

    @Test fun `source-only plan isolates exactly one claim and one candidate with no search`() {
        val execution = eligiblePlan()
        val isolated = execution.sourceOnlyPlan
        assertEquals(1, isolated.claims.size)
        assertEquals(claim.id, isolated.claims.single().candidate.id)
        assertFalse(isolated.claims.single().needsTargetedSearch)
        assertTrue(isolated.targetedSearchRequests.isEmpty())
        assertEquals(listOf(claim.id), isolated.allCandidates.map { it.id })
        assertTrue(execution.offersWebSearch)
        val query = requireNotNull(execution.targetedQuery)
        assertEquals(claim.id, query.claimId)
        assertTrue(query.query.isNotBlank())
    }

    @Test fun `web plan reattaches exactly the single frozen query`() {
        val execution = eligiblePlan()
        val web = execution.webPlan()
        assertEquals(1, web.claims.size)
        assertTrue(web.claims.single().needsTargetedSearch)
        assertEquals(1, web.targetedSearchRequests.size)
        assertEquals(execution.targetedQuery, web.targetedSearchRequests.single())
    }

    @Test fun `strong attached source offers no web search`() {
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
        val withAttached = message.copy(content = appendSearchSourcesMetadata(message.content, listOf(attached)))
        val attachedClaim = EvidenceVerificationPlanner.candidates(withAttached.content).first()
        val decision = plan(
            message = withAttached,
            identity = EvidenceAnswerIdentity.of(withAttached),
            report = report(owner = withAttached, claims = listOf(storedClaim(attachedClaim.id, attachedClaim.text))),
            claimId = attachedClaim.id,
        )
        val execution = (decision as SelectedClaimExecutionDecision.Eligible).plan
        assertFalse(execution.offersWebSearch)
        assertNull(execution.targetedQuery)
    }

    @Test fun `unknown claim id fails closed`() {
        assertEquals(
            SelectedClaimIneligibleReason.UNKNOWN_CLAIM_ID,
            ineligibleReason(plan(report = report(), claimId = "C9")),
        )
    }

    @Test fun `mutated answer fails closed`() {
        val changed = message.copy(content = "Acme released ProductY in 2026.")
        assertEquals(
            SelectedClaimIneligibleReason.ANSWER_CHANGED,
            ineligibleReason(plan(message = changed)),
        )
    }

    @Test fun `distinct response version with identical text stays separate`() {
        val version2 = message.copy(id = 2L)
        assertNotEquals(identity, EvidenceAnswerIdentity.of(version2))
        assertEquals(
            SelectedClaimIneligibleReason.ANSWER_CHANGED,
            ineligibleReason(plan(message = version2)),
        )
    }

    @Test fun `report for a different answer fails closed`() {
        val other = message.copy(content = "Acme released ProductX2 in 2026. Extra sentence.")
        assertEquals(
            SelectedClaimIneligibleReason.REPORT_INCOMPATIBLE,
            ineligibleReason(plan(report = report(owner = other))),
        )
    }

    @Test fun `outdated verifier report fails closed`() {
        assertEquals(
            SelectedClaimIneligibleReason.REPORT_INCOMPATIBLE,
            ineligibleReason(plan(report = eligibleReport().copy(verifierVersion = "rules-v1"))),
        )
    }

    @Test fun `stored claim text mismatch fails closed`() {
        assertEquals(
            SelectedClaimIneligibleReason.CLAIM_TEXT_MISMATCH,
            ineligibleReason(plan(report = eligibleReport(text = "Different stored sentence."))),
        )
    }

    @Test fun `non reconstructible stored claim fails closed`() {
        assertEquals(
            SelectedClaimIneligibleReason.CLAIM_NOT_RECONSTRUCTIBLE,
            ineligibleReason(plan(report = report(claims = listOf(storedClaim(id = "C42"))), claimId = "C42")),
        )
    }

    @Test fun `non checkable stored claim fails closed`() {
        val greeting = message.copy(content = "안녕하세요.")
        val greetingClaim = EvidenceVerificationPlanner.candidates(greeting.content).first()
        assertNotEquals(ClaimClassification.CHECKABLE_FACT, greetingClaim.classification)
        val decision = plan(
            message = greeting,
            identity = EvidenceAnswerIdentity.of(greeting),
            report = report(
                owner = greeting,
                claims = listOf(storedClaim(greetingClaim.id, greetingClaim.text, EvidenceVerdict.NOT_CHECKED)),
            ),
            claimId = greetingClaim.id,
        )
        assertEquals(SelectedClaimIneligibleReason.NOT_CHECKABLE, ineligibleReason(decision))
    }

    @Test fun `claim outside the canonical execution window fails closed`() {
        val content = (1..7).joinToString(" ") { "Acme released Product$it in 2026." }
        val canonical = EvidenceVerificationPlanner.plan(content)
        val checkable = canonical.allCandidates.filter { it.classification == ClaimClassification.CHECKABLE_FACT }
        val selectedIds = canonical.claims.map { it.candidate.id }.toSet()
        assertTrue(checkable.size >= 7)
        val excluded = checkable.first { it.id !in selectedIds }
        val contentMessage = message.copy(content = content)
        val decision = plan(
            message = contentMessage,
            identity = EvidenceAnswerIdentity.of(contentMessage),
            report = report(
                owner = contentMessage,
                claims = listOf(storedClaim(excluded.id, excluded.text, EvidenceVerdict.NOT_CHECKED)),
            ),
            claimId = excluded.id,
        )
        assertEquals(SelectedClaimIneligibleReason.OUTSIDE_EXECUTION_WINDOW, ineligibleReason(decision))
    }

    @Test fun `ineligible explanation never substitutes a verdict`() {
        for (reason in SelectedClaimIneligibleReason.values()) {
            assertTrue(reason.name, selectedClaimIneligibleExplanation(reason).isNotBlank())
        }
    }

    // ---------------------------------------------------------------------
    // Owner — real verifier + service, only transport faked
    // ---------------------------------------------------------------------

    private class RecordingExecutor(
        private val result: EvidenceSearchResult = EvidenceSearchResult.Success(emptyList()),
    ) : PostAnswerVerificationSearchExecutor {
        val requests = mutableListOf<TargetedSearchRequest>()
        val authorizedRequests = mutableListOf<TargetedSearchRequest>()
        override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
            requests += request
            return result
        }
        override suspend fun searchAuthorizedPostAnswerVerification(
            request: TargetedSearchRequest,
            operationId: String,
            identity: PostAnswerVerificationSearchIdentity,
            authorization: PostAnswerVerificationSearchAuthorization,
            isCurrent: suspend () -> Boolean,
        ): EvidenceSearchResult {
            authorizedRequests += request
            return result
        }
    }

    private fun supportingSource() = WebSearchSource(
        title = "Acme released ProductX2 in 2026.",
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

    /**
     * Controlled verifier fake: it captures the exact plan, signals entry, then
     * suspends until released. This lets a test mutate persisted state while a
     * verification is genuinely in flight, which is the P2B-01 window.
     */
    private class ControllableVerifier(
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
        private val buildReport: (VerificationPlan) -> EvidenceReport,
    ) : EvidenceUiVerifier {
        val verifiedPlans = mutableListOf<VerificationPlan>()
        val authorizedPlans = mutableListOf<VerificationPlan>()

        override suspend fun verify(message: MessageEntity, plan: VerificationPlan): EvidenceVerificationResult {
            verifiedPlans += plan
            entered.complete(Unit)
            release.await()
            return EvidenceVerificationResult.Complete(buildReport(plan))
        }

        override suspend fun verifyWithSearchAuthorization(
            message: MessageEntity,
            plan: VerificationPlan,
            operationId: String,
            authorization: PostAnswerVerificationSearchAuthorization,
            isCurrent: suspend () -> Boolean,
        ): EvidenceVerificationResult {
            authorizedPlans += plan
            entered.complete(Unit)
            release.await()
            return EvidenceVerificationResult.Complete(buildReport(plan))
        }
    }

    /** A single-claim report bound to the plan's exact candidate text. */
    private fun validResultReport(plan: VerificationPlan): EvidenceReport {
        val candidate = plan.claims.single().candidate
        return report(
            claims = listOf(
                EvidenceClaim(candidate.id, candidate.text, EvidenceVerdict.SUPPORTED_BY_EVIDENCE, emptyList(), null),
            ),
        )
    }

    @Test fun `source-only reverification runs locally with zero outbound search and no writes`() = runTest {
        val dao = ReverificationEvidenceDao()
        val store = EvidenceStore(dao)
        store.saveComplete(message, eligibleReport())
        val before = dao.rows.getValue(message.id)
        val executor = RecordingExecutor()
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = SearchBackedEvidenceUiVerifier(executor),
            clock = EvidenceClock { 10L },
            privateTextsProvider = { emptyList() },
        )

        owner.reverify(message, identity, eligibleReport(), claim.id)
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        owner.choose(consent.operationId, SelectedClaimSourceChoice.EXISTING_SOURCES_ONLY)
        advanceUntilIdle()

        val result = owner.state.value as SelectedClaimReverificationState.Result
        assertFalse(result.webSearchAuthorized)
        assertEquals(claim.id, result.claimId)
        assertEquals(1, result.report.claims.size)
        assertEquals(claim.id, result.report.claims.single().id)
        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, result.report.claims.single().verdict)
        assertTrue(executor.requests.isEmpty())
        assertTrue(executor.authorizedRequests.isEmpty())
        assertEquals(before, dao.rows.getValue(message.id))
        assertEquals(1, dao.inserts)
    }

    @Test fun `web reverification sends exactly one authorized query and keeps the result transient`() = runTest {
        val dao = ReverificationEvidenceDao()
        val store = EvidenceStore(dao)
        store.saveComplete(message, eligibleReport())
        val before = dao.rows.getValue(message.id)
        val executor = RecordingExecutor(EvidenceSearchResult.Success(listOf(supportingSource())))
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = SearchBackedEvidenceUiVerifier(executor),
            clock = EvidenceClock { 10L },
            privateTextsProvider = { emptyList() },
        )

        owner.reverify(message, identity, eligibleReport(), claim.id)
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        val approved = consent.query
        assertEquals(claim.id, approved.claimId)
        owner.choose(consent.operationId, SelectedClaimSourceChoice.WEB)
        advanceUntilIdle()

        val result = owner.state.value as SelectedClaimReverificationState.Result
        assertTrue(result.webSearchAuthorized)
        assertEquals(
            listOf(approved.claimId to approved.query),
            executor.authorizedRequests.map { it.claimId to it.query },
        )
        assertTrue(executor.requests.isEmpty())
        assertEquals(EvidenceVerdict.SUPPORTED_BY_EVIDENCE, result.report.claims.single().verdict)
        assertEquals(before, dao.rows.getValue(message.id))
        assertEquals(1, dao.inserts)
    }

    @Test fun `cancel at consent performs no search and leaves idle state`() = runTest {
        val dao = ReverificationEvidenceDao()
        EvidenceStore(dao).saveComplete(message, eligibleReport())
        val executor = RecordingExecutor()
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = SearchBackedEvidenceUiVerifier(executor),
            clock = EvidenceClock { 10L },
            privateTextsProvider = { emptyList() },
        )

        owner.reverify(message, identity, eligibleReport(), claim.id)
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        owner.choose(consent.operationId, SelectedClaimSourceChoice.CANCEL)
        advanceUntilIdle()

        assertEquals(SelectedClaimReverificationState.Idle, owner.state.value)
        assertTrue(executor.requests.isEmpty())
        assertTrue(executor.authorizedRequests.isEmpty())
        assertEquals(1, dao.inserts)
    }

    @Test fun `consent bound to the exact operation id ignores foreign answers`() = runTest {
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = SearchBackedEvidenceUiVerifier(RecordingExecutor()),
            clock = EvidenceClock { 10L },
            privateTextsProvider = { emptyList() },
        )
        owner.reverify(message, identity, eligibleReport(), claim.id)
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent

        owner.choose("some-other-operation", SelectedClaimSourceChoice.WEB)
        runCurrent()
        assertEquals(consent.operationId, (owner.state.value as SelectedClaimReverificationState.AwaitingConsent).operationId)

        owner.cancel()
        runCurrent()
        assertEquals(SelectedClaimReverificationState.Idle, owner.state.value)
    }

    @Test fun `duplicate taps share one in-flight operation`() = runTest {
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = SearchBackedEvidenceUiVerifier(RecordingExecutor()),
            clock = EvidenceClock { 10L },
            privateTextsProvider = { emptyList() },
        )
        owner.reverify(message, identity, eligibleReport(), claim.id)
        runCurrent()
        val first = owner.state.value as SelectedClaimReverificationState.AwaitingConsent

        owner.reverify(message, identity, eligibleReport(), claim.id)
        runCurrent()
        val second = owner.state.value as SelectedClaimReverificationState.AwaitingConsent

        assertEquals(first.operationId, second.operationId)
        owner.cancel()
        runCurrent()
    }

    @Test fun `switching the selected claim drops the pending operation`() = runTest {
        var currentClaim = claim.id
        val executor = RecordingExecutor()
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = SearchBackedEvidenceUiVerifier(executor),
            clock = EvidenceClock { 10L },
            privateTextsProvider = { emptyList() },
            isSelectedClaimCurrent = { it == currentClaim },
        )
        owner.reverify(message, identity, eligibleReport(), claim.id)
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent

        currentClaim = "C-other"
        owner.choose(consent.operationId, SelectedClaimSourceChoice.WEB)
        advanceUntilIdle()

        assertEquals(SelectedClaimReverificationState.Idle, owner.state.value)
        assertTrue(executor.authorizedRequests.isEmpty())
    }

    @Test fun `losing the answer identity during authorized search drops the late result`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var currentAnswer = true
        val executor = object : PostAnswerVerificationSearchExecutor {
            override suspend fun search(request: TargetedSearchRequest) = EvidenceSearchResult.Success(emptyList())
            override suspend fun searchAuthorizedPostAnswerVerification(
                request: TargetedSearchRequest,
                operationId: String,
                identity: PostAnswerVerificationSearchIdentity,
                authorization: PostAnswerVerificationSearchAuthorization,
                isCurrent: suspend () -> Boolean,
            ): EvidenceSearchResult {
                entered.complete(Unit)
                release.await()
                return EvidenceSearchResult.Success(emptyList())
            }
        }
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = SearchBackedEvidenceUiVerifier(executor),
            clock = EvidenceClock { 10L },
            privateTextsProvider = { emptyList() },
            isCurrentAnswer = { currentAnswer },
        )
        owner.reverify(message, identity, eligibleReport(), claim.id)
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        owner.choose(consent.operationId, SelectedClaimSourceChoice.WEB)
        runCurrent()
        entered.await()

        currentAnswer = false
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(SelectedClaimReverificationState.Idle, owner.state.value)
    }

    @Test fun `private provenance suppresses web search and never offers consent`() = runTest {
        val privateMessage = message.copy(
            content = AnswerContextReceipt.append(
                "Acme released ProductX2 in 2026.",
                AnswerContextReceipt.build(
                    route = AnswerContextRoute.LOCAL_MODEL,
                    localRagDocumentCount = 1,
                    localRagChunkCount = 1,
                ),
            ),
        )
        val privateClaim = EvidenceVerificationPlanner.candidates(privateMessage.content).first()
        val privateReport = report(
            owner = privateMessage,
            claims = listOf(storedClaim(privateClaim.id, privateClaim.text)),
        )
        val executor = RecordingExecutor()
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = SearchBackedEvidenceUiVerifier(executor),
            clock = EvidenceClock { 10L },
            privateTextsProvider = { emptyList() },
        )

        owner.reverify(privateMessage, EvidenceAnswerIdentity.of(privateMessage), privateReport, privateClaim.id)
        advanceUntilIdle()

        val result = owner.state.value as SelectedClaimReverificationState.Result
        assertFalse(result.webSearchAuthorized)
        assertTrue(result.notice.orEmpty().contains("웹 검색으로 보내지 않습니다"))
        assertTrue(executor.authorizedRequests.isEmpty())
    }

    @Test fun `private overlap in the derived query falls back to source-only`() = runTest {
        val budgetMessage = message.copy(content = "The budget for ProjectX is $5M.")
        val budgetClaim = EvidenceVerificationPlanner.candidates(budgetMessage.content).first()
        val budgetReport = report(
            owner = budgetMessage,
            claims = listOf(storedClaim(budgetClaim.id, budgetClaim.text)),
        )
        val executor = RecordingExecutor()
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = SearchBackedEvidenceUiVerifier(executor),
            clock = EvidenceClock { 10L },
            privateTextsProvider = { listOf("ProjectX") },
        )

        owner.reverify(budgetMessage, EvidenceAnswerIdentity.of(budgetMessage), budgetReport, budgetClaim.id)
        advanceUntilIdle()

        val result = owner.state.value as SelectedClaimReverificationState.Result
        assertFalse(result.webSearchAuthorized)
        assertTrue(result.notice.orEmpty().contains("개인 자료"))
        assertTrue(executor.authorizedRequests.isEmpty())
    }

    @Test fun `missing private provider fails closed to source-only`() = runTest {
        val executor = RecordingExecutor()
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = SearchBackedEvidenceUiVerifier(executor),
            clock = EvidenceClock { 10L },
            privateTextsProvider = null,
        )
        owner.reverify(message, identity, eligibleReport(), claim.id)
        advanceUntilIdle()

        val result = owner.state.value as SelectedClaimReverificationState.Result
        assertFalse(result.webSearchAuthorized)
        assertEquals(PRIVATE_TEXT_UNAVAILABLE_NOTICE, result.notice)
        assertTrue(executor.authorizedRequests.isEmpty())
    }

    @Test fun `ineligible selection surfaces a truthful ineligible state`() = runTest {
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = SearchBackedEvidenceUiVerifier(RecordingExecutor()),
            clock = EvidenceClock { 10L },
            privateTextsProvider = { emptyList() },
        )
        owner.reverify(message, identity, eligibleReport(), "C-missing")
        advanceUntilIdle()

        val ineligible = owner.state.value as SelectedClaimReverificationState.Ineligible
        assertEquals("C-missing", ineligible.claimId)
        assertEquals(SelectedClaimIneligibleReason.UNKNOWN_CLAIM_ID, ineligible.reason)
        assertTrue(ineligible.explanation.isNotBlank())
    }

    @Test fun `authorization replay under another operation is refused before outbound search`() = runTest {
        val executor = RecordingExecutor()
        val verifier = SearchBackedEvidenceUiVerifier(executor)
        val execution = eligiblePlan()
        val egressIdentity = PostAnswerVerificationSearchIdentity(
            conversationId = message.conversationId,
            assistantMessageId = message.id,
            answerSha256 = EvidenceFingerprint.canonicalAnswerHash(message.content),
            responseVersionId = message.id,
        )
        val authorization = requireNotNull(
            PostAnswerVerificationSearchAuthorization.afterExplicitConfirmation(
                operationId = "operation-a",
                identity = egressIdentity,
                queries = listOf(com.projectnuke.fusion.privacy.AuthorizedTargetedQuery(claim.id, requireNotNull(execution.targetedQuery).query)),
            ),
        )
        val refused = verifier.verifyWithSearchAuthorization(
            message = message,
            plan = execution.webPlan(),
            operationId = "operation-b",
            authorization = authorization,
            isCurrent = { true },
        )
        assertEquals(EvidenceVerificationFailureReason.EGRESS_REFUSED, (refused as EvidenceVerificationResult.Failed).reason)
        assertTrue(executor.authorizedRequests.isEmpty())
        assertTrue(executor.requests.isEmpty())
    }

    @Test fun `invalid reverify input never starts an operation`() = runTest {
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = SearchBackedEvidenceUiVerifier(RecordingExecutor()),
            clock = EvidenceClock { 10L },
            privateTextsProvider = { emptyList() },
        )
        owner.reverify(message, identity, eligibleReport(), "   ")
        owner.reverify(message.copy(role = "user"), identity, eligibleReport(), claim.id)
        owner.reverify(message.copy(conversationId = 0L), identity, eligibleReport(), claim.id)
        advanceUntilIdle()
        assertEquals(SelectedClaimReverificationState.Idle, owner.state.value)
    }

    // ---------------------------------------------------------------------
    // P2B-01 — persisted-message revalidation after verification returns
    // ---------------------------------------------------------------------

    @Test fun `source-only late result is dropped when the persisted message changes mid-verification`() = runTest {
        val dao = ReverificationEvidenceDao()
        EvidenceStore(dao).saveComplete(message, eligibleReport())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var stored: MessageEntity? = message
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = ControllableVerifier(entered, release) { validResultReport(it) },
            clock = EvidenceClock { 10L },
            privateTextsProvider = { emptyList() },
            currentMessageReader = { stored },
        )
        owner.reverify(message, identity, eligibleReport(), claim.id)
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        owner.choose(consent.operationId, SelectedClaimSourceChoice.EXISTING_SOURCES_ONLY)
        runCurrent()
        entered.await()

        stored = message.copy(content = "A materially different answer body.")

        release.complete(Unit)
        advanceUntilIdle()

        assertFalse(
            "a stale result must not be published after the persisted message changed",
            owner.state.value is SelectedClaimReverificationState.Result,
        )
        val ineligible = owner.state.value as SelectedClaimReverificationState.Ineligible
        assertEquals(SelectedClaimIneligibleReason.ANSWER_CHANGED, ineligible.reason)
        assertEquals(1, dao.inserts)
    }

    @Test fun `source-only late result is dropped when the persisted message is deleted mid-verification`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var stored: MessageEntity? = message
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = ControllableVerifier(entered, release) { validResultReport(it) },
            clock = EvidenceClock { 10L },
            privateTextsProvider = { emptyList() },
            currentMessageReader = { stored },
        )
        owner.reverify(message, identity, eligibleReport(), claim.id)
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        owner.choose(consent.operationId, SelectedClaimSourceChoice.EXISTING_SOURCES_ONLY)
        runCurrent()
        entered.await()

        stored = null

        release.complete(Unit)
        advanceUntilIdle()

        assertFalse(owner.state.value is SelectedClaimReverificationState.Result)
        assertEquals(
            SelectedClaimIneligibleReason.ANSWER_CHANGED,
            (owner.state.value as SelectedClaimReverificationState.Ineligible).reason,
        )
    }

    @Test fun `web late result is dropped when the persisted message changes mid-verification`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var stored: MessageEntity? = message
        val verifier = ControllableVerifier(entered, release) { validResultReport(it) }
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = verifier,
            clock = EvidenceClock { 10L },
            privateTextsProvider = { emptyList() },
            currentMessageReader = { stored },
        )
        owner.reverify(message, identity, eligibleReport(), claim.id)
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        owner.choose(consent.operationId, SelectedClaimSourceChoice.WEB)
        runCurrent()
        entered.await()

        stored = message.copy(content = "A materially different answer body.")

        release.complete(Unit)
        advanceUntilIdle()

        assertFalse(
            "a stale web result must not be published after the persisted message changed",
            owner.state.value is SelectedClaimReverificationState.Result,
        )
        assertEquals(
            SelectedClaimIneligibleReason.ANSWER_CHANGED,
            (owner.state.value as SelectedClaimReverificationState.Ineligible).reason,
        )
        // The one-shot authorized query was still bound and consumed exactly once.
        assertEquals(1, verifier.authorizedPlans.single().targetedSearchRequests.size)
        assertTrue(verifier.verifiedPlans.isEmpty())
    }

    @Test fun `web late result is dropped when the persisted message is deleted mid-verification`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var stored: MessageEntity? = message
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = ControllableVerifier(entered, release) { validResultReport(it) },
            clock = EvidenceClock { 10L },
            privateTextsProvider = { emptyList() },
            currentMessageReader = { stored },
        )
        owner.reverify(message, identity, eligibleReport(), claim.id)
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        owner.choose(consent.operationId, SelectedClaimSourceChoice.WEB)
        runCurrent()
        entered.await()

        stored = null

        release.complete(Unit)
        advanceUntilIdle()

        assertFalse(owner.state.value is SelectedClaimReverificationState.Result)
        assertEquals(
            SelectedClaimIneligibleReason.ANSWER_CHANGED,
            (owner.state.value as SelectedClaimReverificationState.Ineligible).reason,
        )
    }

    // ---------------------------------------------------------------------
    // P2B-02 — result claim-text binding at settlement
    // ---------------------------------------------------------------------

    @Test fun `result with the same claim id but different claim text is rejected`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tampered = ControllableVerifier(entered, release) {
            report(
                claims = listOf(
                    EvidenceClaim(
                        claim.id,
                        "Tampered claim text that differs from the selected claim.",
                        EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
                        emptyList(),
                        null,
                    ),
                ),
            )
        }
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = tampered,
            clock = EvidenceClock { 10L },
            privateTextsProvider = { emptyList() },
        )
        owner.reverify(message, identity, eligibleReport(), claim.id)
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        owner.choose(consent.operationId, SelectedClaimSourceChoice.EXISTING_SOURCES_ONLY)
        runCurrent()
        entered.await()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            SelectedClaimReverificationState.Failed(claim.id, SelectedClaimFailureReason.INVALID),
            owner.state.value,
        )
    }

    @Test fun `result including an unrelated extra claim is rejected`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val extra = ControllableVerifier(entered, release) { plan ->
            val candidate = plan.claims.single().candidate
            report(
                claims = listOf(
                    EvidenceClaim(candidate.id, candidate.text, EvidenceVerdict.SUPPORTED_BY_EVIDENCE, emptyList(), null),
                    EvidenceClaim("C-unrelated", "Unrelated injected claim.", EvidenceVerdict.NOT_CHECKED, emptyList(), null),
                ),
            )
        }
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = extra,
            clock = EvidenceClock { 10L },
            privateTextsProvider = { emptyList() },
        )
        owner.reverify(message, identity, eligibleReport(), claim.id)
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        owner.choose(consent.operationId, SelectedClaimSourceChoice.EXISTING_SOURCES_ONLY)
        runCurrent()
        entered.await()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            SelectedClaimReverificationState.Failed(claim.id, SelectedClaimFailureReason.INVALID),
            owner.state.value,
        )
    }

    @Test fun `result bound to a different answer hash is rejected`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val otherAnswer = ControllableVerifier(entered, release) { plan ->
            val candidate = plan.claims.single().candidate
            report(
                owner = message.copy(content = "Some other answer entirely."),
                claims = listOf(
                    EvidenceClaim(candidate.id, candidate.text, EvidenceVerdict.SUPPORTED_BY_EVIDENCE, emptyList(), null),
                ),
            )
        }
        val owner = SelectedClaimReverificationOwner(
            scope = this,
            verifier = otherAnswer,
            clock = EvidenceClock { 10L },
            privateTextsProvider = { emptyList() },
        )
        owner.reverify(message, identity, eligibleReport(), claim.id)
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        owner.choose(consent.operationId, SelectedClaimSourceChoice.EXISTING_SOURCES_ONLY)
        runCurrent()
        entered.await()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            SelectedClaimReverificationState.Failed(claim.id, SelectedClaimFailureReason.INVALID),
            owner.state.value,
        )
    }

    // ---------------------------------------------------------------------
    // P2B regression — valid unchanged answers still publish (source + web)
    // ---------------------------------------------------------------------

    @Test fun `unchanged persisted message still publishes source-only and web temporary results`() = runTest {
        // Source-only
        run {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val owner = SelectedClaimReverificationOwner(
                scope = this,
                verifier = ControllableVerifier(entered, release) { validResultReport(it) },
                clock = EvidenceClock { 10L },
                privateTextsProvider = { emptyList() },
            )
            owner.reverify(message, identity, eligibleReport(), claim.id)
            runCurrent()
            val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
            owner.choose(consent.operationId, SelectedClaimSourceChoice.EXISTING_SOURCES_ONLY)
            runCurrent()
            entered.await()
            release.complete(Unit)
            advanceUntilIdle()

            val result = owner.state.value as SelectedClaimReverificationState.Result
            assertFalse(result.webSearchAuthorized)
            assertEquals(claim.id, result.report.claims.single().id)
            assertEquals(claim.text, result.report.claims.single().text)
        }
        // Web-authorized
        run {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val verifier = ControllableVerifier(entered, release) { validResultReport(it) }
            val owner = SelectedClaimReverificationOwner(
                scope = this,
                verifier = verifier,
                clock = EvidenceClock { 10L },
                privateTextsProvider = { emptyList() },
            )
            owner.reverify(message, identity, eligibleReport(), claim.id)
            runCurrent()
            val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
            owner.choose(consent.operationId, SelectedClaimSourceChoice.WEB)
            runCurrent()
            entered.await()
            release.complete(Unit)
            advanceUntilIdle()

            val result = owner.state.value as SelectedClaimReverificationState.Result
            assertTrue(result.webSearchAuthorized)
            assertEquals(claim.id, result.report.claims.single().id)
            assertEquals(claim.text, result.report.claims.single().text)
        }
    }
}

private class ReverificationEvidenceDao : EvidenceDao {
    val rows = mutableMapOf<Long, MessageEvidenceEntity>()
    var inserts = 0
    override suspend fun insertEvidence(evidence: MessageEvidenceEntity) {
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
