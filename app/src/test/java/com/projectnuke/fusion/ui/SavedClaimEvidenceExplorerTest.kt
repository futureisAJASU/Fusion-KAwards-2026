package com.projectnuke.fusion.ui

import com.projectnuke.fusion.audit.AuditCoverage
import com.projectnuke.fusion.audit.auditCoverageLabel
import com.projectnuke.fusion.data.EVIDENCE_VERIFIER_VERSION
import com.projectnuke.fusion.data.EvidenceClaim
import com.projectnuke.fusion.data.EvidenceClock
import com.projectnuke.fusion.data.EvidenceDao
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.data.EvidenceOutdatedReason
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceSearchExecutor
import com.projectnuke.fusion.data.EvidenceSearchResult
import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.data.EvidenceStore
import com.projectnuke.fusion.data.EvidenceVerdict
import com.projectnuke.fusion.data.EvidenceVerificationFailureReason
import com.projectnuke.fusion.data.EvidenceVerificationResult
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.MessageEvidenceEntity
import com.projectnuke.fusion.data.MessageEvidenceRevision
import com.projectnuke.fusion.data.TargetedSearchRequest
import com.projectnuke.fusion.data.VerificationPlan
import com.projectnuke.fusion.privacy.PostAnswerVerificationSearchAuthorization
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-P2A — read-only saved-claim evidence explorer.
 *
 * These tests prove that the explorer path is genuinely read-only (no
 * verification / search / Evidence write / message mutation), that it fails
 * closed on missing/stale/outdated/corrupt/cross-answer reports, that it protects
 * answer identity across asynchronous loads and lifecycle transitions, and that
 * claim selection is by exact stable id.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SavedClaimEvidenceExplorerTest {

    private val message = MessageEntity(1, 10, "assistant", "Acme released ProductX2 in 2026.", 1)

    private fun source(id: String = "E1") = EvidenceSource(
        id = id,
        title = "Acme release",
        source = "Acme",
        url = "https://example.com/release",
        snippet = "Acme released ProductX2.",
        publishedAt = "2026-01-01",
        providerType = "FREE_DEFAULT",
        providerDisplayName = "Search",
        queryUsed = "Acme ProductX2 2026",
    )

    private fun report(
        owner: MessageEntity = message,
        time: Long = 1L,
        verifierVersion: String = EVIDENCE_VERIFIER_VERSION,
        claims: List<EvidenceClaim> = listOf(
            EvidenceClaim("C1", "Acme released ProductX2.", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, listOf("E1"), null),
        ),
        sources: List<EvidenceSource> = listOf(source()),
        candidateWindowTruncated: Boolean = false,
        overlengthSegmentsExcluded: Boolean = false,
    ) = EvidenceReport(
        schemaVersion = 1,
        answerSha256 = EvidenceFingerprint.canonicalAnswerHash(owner.content),
        checkedAt = time,
        verifierVersion = verifierVersion,
        claims = claims,
        sources = sources,
        candidateWindowTruncated = candidateWindowTruncated,
        overlengthSegmentsExcluded = overlengthSegmentsExcluded,
    )

    // ---- Read-only execution contract --------------------------------------

    @Test
    fun `valid stored report opens and selects a claim with zero verification or writes`() = runTest {
        val harness = ReadOnlyHarness()
        harness.seed(message, report())
        val explorer = harness.explorer(this)

        explorer.open(message)
        advanceUntilIdle()

        val ready = explorer.state.value as SavedClaimExplorerState.Ready
        assertEquals(EvidenceAnswerIdentity.of(message), ready.identity)
        assertEquals(1, ready.report.claims.size)

        explorer.selectClaim("C1")
        assertEquals("C1", (explorer.state.value as SavedClaimExplorerState.Ready).selectedClaimId)
        explorer.backToClaims()
        assertNull((explorer.state.value as SavedClaimExplorerState.Ready).selectedClaimId)

        assertEquals("exactly one read-only load", 1, harness.store.loads)
        harness.assertReadOnly()
    }

    @Test
    fun `missing stored report stays missing and never triggers verification`() = runTest {
        val harness = ReadOnlyHarness()
        val explorer = harness.explorer(this)

        explorer.open(message)
        advanceUntilIdle()

        val state = explorer.state.value as SavedClaimExplorerState.Unavailable
        assertEquals(SavedClaimAbsence.MISSING, state.absence)
        assertEquals(1, harness.store.loads)
        harness.assertReadOnly()
    }

    @Test
    fun `repeated open select back and dismiss perform zero writes and zero verification`() = runTest {
        val harness = ReadOnlyHarness()
        harness.seed(message, report())
        val explorer = harness.explorer(this)

        repeat(3) {
            explorer.open(message)
            advanceUntilIdle()
            explorer.selectClaim("C1")
            explorer.backToClaims()
            explorer.selectClaim("unknown-claim")
            explorer.dismiss()
            advanceUntilIdle()
        }

        assertEquals(SavedClaimExplorerState.Closed, explorer.state.value)
        harness.assertReadOnly()
    }

    @Test
    fun `rapid claim switching never runs verification`() = runTest {
        val harness = ReadOnlyHarness()
        harness.seed(
            message,
            report(
                claims = listOf(
                    EvidenceClaim("C1", "first", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, listOf("E1"), null),
                    EvidenceClaim("C2", "second", EvidenceVerdict.CONFLICTING_EVIDENCE, listOf("E1"), null),
                ),
            ),
        )
        val explorer = harness.explorer(this)
        explorer.open(message)
        advanceUntilIdle()

        explorer.selectClaim("C2")
        explorer.selectClaim("C1")
        explorer.selectClaim("C2")
        assertEquals("C2", (explorer.state.value as SavedClaimExplorerState.Ready).selectedClaimId)
        harness.assertReadOnly()
    }

    // ---- Identity and lifecycle --------------------------------------------

    @Test
    fun `report for a different content hash fails closed as stale`() = runTest {
        val harness = ReadOnlyHarness()
        harness.seed(message, report())
        val changed = message.copy(content = "Acme released a different product.")
        val explorer = harness.explorer(this)

        explorer.open(changed)
        advanceUntilIdle()

        val state = explorer.state.value as SavedClaimExplorerState.Unavailable
        assertEquals(SavedClaimAbsence.STALE, state.absence)
        harness.assertReadOnly()
    }

    @Test
    fun `identical text in a different response version never reuses the other version report`() = runTest {
        val harness = ReadOnlyHarness()
        harness.seed(message, report())
        // Version 2 has byte-identical content but a distinct persisted id.
        val versionTwo = message.copy(id = 2)
        val explorer = harness.explorer(this)

        explorer.open(versionTwo)
        advanceUntilIdle()

        val state = explorer.state.value as SavedClaimExplorerState.Unavailable
        assertEquals(SavedClaimAbsence.MISSING, state.absence)
        harness.assertReadOnly()
    }

    @Test
    fun `outdated verifier report fails closed without exposing verdicts`() = runTest {
        val harness = ReadOnlyHarness()
        harness.seed(message, report(verifierVersion = "rules-v1"))
        val explorer = harness.explorer(this)

        explorer.open(message)
        advanceUntilIdle()

        val state = explorer.state.value as SavedClaimExplorerState.Unavailable
        assertEquals(SavedClaimAbsence.OUTDATED, state.absence)
        assertEquals(EvidenceOutdatedReason.RULES_CHANGED, state.outdatedReason)
        harness.assertReadOnly()
    }

    @Test
    fun `expired current-sensitive report fails closed`() = runTest {
        val harness = ReadOnlyHarness()
        val now = Instant.parse("2026-09-05T00:00:00Z").toEpochMilli()
        val currentMessage = message.copy(content = "Acme currently supports ProductX2.")
        val claim = EvidenceClaim("C1", currentMessage.content, EvidenceVerdict.SUPPORTED_BY_EVIDENCE, emptyList(), null)
        harness.seed(
            currentMessage,
            report(
                owner = currentMessage,
                time = now - 25L * 60L * 60L * 1_000L,
                claims = listOf(claim),
                sources = emptyList(),
            ),
        )
        val explorer = harness.explorer(this, clock = EvidenceClock { now })

        explorer.open(currentMessage)
        advanceUntilIdle()

        val state = explorer.state.value as SavedClaimExplorerState.Unavailable
        assertEquals(SavedClaimAbsence.OUTDATED, state.absence)
        assertEquals(EvidenceOutdatedReason.TIME_EXPIRED, state.outdatedReason)
        harness.assertReadOnly()
    }

    @Test
    fun `corrupt stored report fails closed and preserves the forensic row`() = runTest {
        val harness = ReadOnlyHarness()
        harness.seed(message, report())
        harness.dao.rows[message.id] = harness.dao.rows.getValue(message.id).copy(reportJson = "{broken")
        val before = harness.dao.rows.getValue(message.id)
        val explorer = harness.explorer(this)

        explorer.open(message)
        advanceUntilIdle()

        val state = explorer.state.value as SavedClaimExplorerState.Unavailable
        assertEquals(SavedClaimAbsence.CORRUPT, state.absence)
        assertEquals(before, harness.dao.rows[message.id])
        harness.assertReadOnly()
    }

    @Test
    fun `response-version switch during asynchronous load dismisses without stale data`() = runTest {
        val blocking = BlockingStore()
        var current = true
        val explorer = SavedClaimEvidenceExplorerOwner(
            scope = this,
            store = blocking,
            isCurrentAnswer = { current },
        )

        explorer.open(message)
        runCurrent()
        blocking.entered.await()
        current = false
        blocking.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(SavedClaimExplorerState.Closed, explorer.state.value)
        assertEquals("blocked read must not save", 0, blocking.saves)
    }

    @Test
    fun `conversation deletion during load dismisses without displaying data`() = runTest {
        val blocking = BlockingStore()
        blocking.result = EvidenceStore.LoadResult.Valid(report())
        var stored: MessageEntity? = message
        val explorer = SavedClaimEvidenceExplorerOwner(
            scope = this,
            store = blocking,
            currentMessageReader = { stored },
        )

        explorer.open(message)
        runCurrent()
        blocking.entered.await()
        stored = null
        blocking.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(SavedClaimExplorerState.Closed, explorer.state.value)
    }

    @Test
    fun `wrong conversation id during load dismisses without displaying data`() = runTest {
        val blocking = BlockingStore()
        blocking.result = EvidenceStore.LoadResult.Valid(report())
        var stored: MessageEntity? = message
        val explorer = SavedClaimEvidenceExplorerOwner(
            scope = this,
            store = blocking,
            currentMessageReader = { stored },
        )

        explorer.open(message)
        runCurrent()
        blocking.entered.await()
        stored = message.copy(conversationId = 999)
        blocking.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(SavedClaimExplorerState.Closed, explorer.state.value)
    }

    @Test
    fun `disposal cancels an in-flight load and leaves nothing displayed`() = runTest {
        val blocking = BlockingStore()
        blocking.result = EvidenceStore.LoadResult.Valid(report())
        val explorer = SavedClaimEvidenceExplorerOwner(scope = this, store = blocking)

        explorer.open(message)
        runCurrent()
        blocking.entered.await()
        explorer.dismiss()
        advanceUntilIdle()

        assertEquals(SavedClaimExplorerState.Closed, explorer.state.value)
        assertEquals(0, blocking.saves)
    }

    @Test
    fun `read failure surfaces the read-failed state`() = runTest {
        val explorer = SavedClaimEvidenceExplorerOwner(
            scope = this,
            store = object : EvidenceUiStore {
                override suspend fun load(message: MessageEntity): EvidenceStore.LoadResult = error("io")
                override suspend fun saveComplete(message: MessageEntity, report: EvidenceReport) = error("must not save")
            },
        )
        explorer.open(message)
        advanceUntilIdle()
        val state = explorer.state.value as SavedClaimExplorerState.Unavailable
        assertEquals(SavedClaimAbsence.READ_FAILED, state.absence)
    }

    // ---- Claim selection ---------------------------------------------------

    @Test
    fun `selects claim by exact stable id only`() = runTest {
        val harness = ReadOnlyHarness()
        harness.seed(
            message,
            report(
                claims = listOf(
                    EvidenceClaim("C1", "first", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, listOf("E1"), null),
                    EvidenceClaim("C2", "second", EvidenceVerdict.INSUFFICIENT_EVIDENCE, emptyList(), "NO_USABLE_SOURCE"),
                ),
            ),
        )
        val explorer = harness.explorer(this)
        explorer.open(message)
        advanceUntilIdle()

        // Unrecognized id (and a positional "0") is ignored.
        explorer.selectClaim("0")
        explorer.selectClaim("C9")
        assertNull((explorer.state.value as SavedClaimExplorerState.Ready).selectedClaimId)

        explorer.selectClaim("C2")
        assertEquals("C2", (explorer.state.value as SavedClaimExplorerState.Ready).selectedClaimId)
        harness.assertReadOnly()
    }

    @Test
    fun `claim with no source refs reports an empty linked-source list`() = runTest {
        val harness = ReadOnlyHarness()
        harness.seed(
            message,
            report(
                claims = listOf(EvidenceClaim("C1", "unsourced", EvidenceVerdict.NOT_CHECKED, emptyList(), null)),
                sources = emptyList(),
            ),
        )
        val explorer = harness.explorer(this)
        explorer.open(message)
        advanceUntilIdle()
        val ready = explorer.state.value as SavedClaimExplorerState.Ready
        val detail = requireNotNull(SavedClaimEvidenceProjection.detailOf(ready.report, "C1"))
        assertTrue(detail.sources.isEmpty())
    }

    @Test
    fun `dangling source reference is a conservative missing-source view`() {
        // A codec-validated report never contains dangling refs, but the
        // projection must still fail closed if one is ever handed to it.
        val malformed = report().copy(
            claims = listOf(EvidenceClaim("C1", "claim", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, listOf("E9"), null)),
        )
        val detail = requireNotNull(SavedClaimEvidenceProjection.detailOf(malformed, "C1"))
        assertEquals(1, detail.sources.size)
        assertNull(detail.sources.single().source)
        assertEquals("E9", detail.sources.single().danglingReferenceId)
    }

    @Test
    fun `multiple sources keep claim sourceIds order and attribution`() {
        val report = report(
            claims = listOf(EvidenceClaim("C1", "claim", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, listOf("E2", "E1"), null)),
            sources = listOf(source("E1"), source("E2")),
        )
        val detail = requireNotNull(SavedClaimEvidenceProjection.detailOf(report, "C1"))
        assertEquals(listOf("E2", "E1"), detail.sources.map { it.source?.id })
    }

    @Test
    fun `summaries preserve stored claims order and count linked sources`() {
        val report = report(
            claims = listOf(
                EvidenceClaim("C2", "second", EvidenceVerdict.CONFLICTING_EVIDENCE, listOf("E1"), null),
                EvidenceClaim("C1", "first", EvidenceVerdict.NOT_CHECKED, emptyList(), null),
            ),
        )
        val summaries = SavedClaimEvidenceProjection.summariesOf(report)
        assertEquals(listOf("C2", "C1"), summaries.map { it.claimId })
        assertEquals(listOf(1, 0), summaries.map { it.sourceCount })
    }

    @Test
    fun `all four verdicts remain distinct in the read-only projection`() {
        val report = report(
            claims = listOf(
                EvidenceClaim("C1", "a", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, emptyList(), null),
                EvidenceClaim("C2", "b", EvidenceVerdict.CONFLICTING_EVIDENCE, emptyList(), null),
                EvidenceClaim("C3", "c", EvidenceVerdict.INSUFFICIENT_EVIDENCE, emptyList(), null),
                EvidenceClaim("C4", "d", EvidenceVerdict.NOT_CHECKED, emptyList(), null),
            ),
        )
        val labels = SavedClaimEvidenceProjection.summariesOf(report).map { it.verdict.evidenceLabel() }
        assertEquals(
            listOf("\uADFC\uAC70\uB85C \uD655\uC778\uB428", "\uC0C1\uCDA9\uD558\uB294 \uADFC\uAC70\uAC00 \uC788\uC74C", "\uADFC\uAC70 \uBD80\uC871", "\uD655\uC778\uD558\uC9C0 \uC54A\uC74C"),
            labels,
        )
        assertFalse(labels[2] == labels[3])
    }

    @Test
    fun `coverage warnings are preserved for the read-only view`() {
        val report = report(
            claims = emptyList(),
            sources = emptyList(),
            candidateWindowTruncated = true,
            overlengthSegmentsExcluded = true,
        )
        val lines = SavedClaimEvidenceProjection.limitationLines(report)
        assertTrue(lines.any { it.contains("\uD655\uC778 \uD6C4\uBCF4\uB294 \uCD5C\uB300") })
        assertTrue(lines.any { it.contains("\uD5C8\uC6A9 \uAE38\uC774") })
        assertTrue(lines.any { it.contains("\uD655\uC778 \uBC94\uC704\uC5D0 \uD3EC\uD568\uB418\uC9C0 \uC54A\uC558") })
    }

    // ---- Audit coverage guidance (P2 audit follow-up) ----------------------

    @Test
    fun `mixed checked and unchecked claims report partially audited coverage`() {
        val report = report(
            claims = listOf(
                EvidenceClaim("C1", "checked", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, emptyList(), null),
                EvidenceClaim("C2", "unchecked", EvidenceVerdict.NOT_CHECKED, emptyList(), null),
            ),
        )
        val guidance = requireNotNull(SavedClaimEvidenceProjection.coverageGuidance(report))
        assertEquals(AuditCoverage.PARTIALLY_AUDITED, guidance.coverage)
        assertEquals(auditCoverageLabel(AuditCoverage.PARTIALLY_AUDITED), guidance.label)
        assertTrue(guidance.guidance.contains("\uC77C\uBD80 \uC8FC\uC7A5\uB9CC \uD655\uC778"))
        assertTrue(guidance.guidance.contains("\uD310\uC815\uB418\uC9C0 \uC54A\uC558"))
    }

    @Test
    fun `zero checked claims report not audited coverage`() {
        val report = report(
            claims = listOf(
                EvidenceClaim("C1", "one", EvidenceVerdict.NOT_CHECKED, emptyList(), null),
                EvidenceClaim("C2", "two", EvidenceVerdict.NOT_CHECKED, emptyList(), null),
            ),
        )
        val guidance = requireNotNull(SavedClaimEvidenceProjection.coverageGuidance(report))
        assertEquals(AuditCoverage.NOT_AUDITED, guidance.coverage)
        assertEquals(auditCoverageLabel(AuditCoverage.NOT_AUDITED), guidance.label)
        assertTrue(guidance.guidance.contains("\uBCF4\uC7A5\uD558\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4"))
    }

    @Test
    fun `normal partial coverage reports partially audited for a checked minority`() {
        val report = report(
            claims = listOf(
                EvidenceClaim("C1", "a", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, emptyList(), null),
                EvidenceClaim("C2", "b", EvidenceVerdict.INSUFFICIENT_EVIDENCE, emptyList(), "NO_USABLE_SOURCE"),
                EvidenceClaim("C3", "c", EvidenceVerdict.NOT_CHECKED, emptyList(), null),
            ),
        )
        val guidance = requireNotNull(SavedClaimEvidenceProjection.coverageGuidance(report))
        assertEquals(AuditCoverage.PARTIALLY_AUDITED, guidance.coverage)
        assertEquals(2, report.claims.count { it.verdict != EvidenceVerdict.NOT_CHECKED })
        assertEquals(auditCoverageLabel(AuditCoverage.PARTIALLY_AUDITED), guidance.label)
    }

    @Test
    fun `fully checked stored claims never assert authoritative complete coverage`() {
        // Every stored claim is resolved, yet the read-only explorer must NOT
        // claim the authoritative AUDITED state; it stays PARTIALLY_AUDITED and
        // states that unchecked claims were not adjudicated.
        val report = report(
            claims = listOf(
                EvidenceClaim("C1", "a", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, emptyList(), null),
                EvidenceClaim("C2", "b", EvidenceVerdict.CONFLICTING_EVIDENCE, emptyList(), null),
            ),
        )
        val guidance = requireNotNull(SavedClaimEvidenceProjection.coverageGuidance(report))
        assertEquals(AuditCoverage.PARTIALLY_AUDITED, guidance.coverage)
        assertTrue(guidance.coverage != AuditCoverage.AUDITED)
    }

    @Test
    fun `coverage guidance is stable across repeated projections`() {
        val report = report(
            claims = listOf(
                EvidenceClaim("C1", "checked", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, emptyList(), null),
                EvidenceClaim("C2", "unchecked", EvidenceVerdict.NOT_CHECKED, emptyList(), null),
            ),
        )
        val first = SavedClaimEvidenceProjection.coverageGuidance(report)
        val second = SavedClaimEvidenceProjection.coverageGuidance(report)
        assertEquals(first, second)
    }

    @Test
    fun `projectLoad fails closed for every non-compatible load result`() {
        val compatible = report()
        assertEquals(
            SavedClaimLoadProjection.Unavailable(SavedClaimAbsence.MISSING),
            SavedClaimEvidenceProjection.projectLoad(EvidenceStore.LoadResult.Missing, compatible.answerSha256, 10L),
        )
        assertEquals(
            SavedClaimLoadProjection.Unavailable(SavedClaimAbsence.CORRUPT),
            SavedClaimEvidenceProjection.projectLoad(EvidenceStore.LoadResult.Corrupt, compatible.answerSha256, 10L),
        )
        assertEquals(
            SavedClaimLoadProjection.Unavailable(SavedClaimAbsence.STALE),
            SavedClaimEvidenceProjection.projectLoad(EvidenceStore.LoadResult.Stale(compatible), compatible.answerSha256, 10L),
        )
        assertEquals(
            SavedClaimLoadProjection.Ready(compatible),
            SavedClaimEvidenceProjection.projectLoad(EvidenceStore.LoadResult.Valid(compatible), compatible.answerSha256, 10L),
        )
        // Wrong hash for a valid load still fails closed as outdated/rules-changed.
        val wrongHash = SavedClaimEvidenceProjection.projectLoad(
            EvidenceStore.LoadResult.Valid(compatible),
            "b".repeat(64),
            10L,
        ) as SavedClaimLoadProjection.Unavailable
        assertEquals(SavedClaimAbsence.OUTDATED, wrongHash.absence)
    }

    // ---- Harness -----------------------------------------------------------

    private inner class ReadOnlyHarness {
        val dao = CountingEvidenceDao()
        private val raw = EvidenceStore(dao)
        val store = CountingStore(raw)
        val search = CountingSearchExecutor()
        val verificationPath = RecordingVerificationPath(search)
        private var writesBaseline = 0

        /** Seeds a durable row directly (outside the explorer) so save counters stay clean. */
        suspend fun seed(message: MessageEntity, report: EvidenceReport) {
            raw.saveComplete(message, report)
            writesBaseline = dao.inserts
        }

        fun explorer(
            scope: CoroutineScope,
            clock: EvidenceClock = EvidenceClock { 10L },
        ): SavedClaimEvidenceExplorerOwner = SavedClaimEvidenceExplorerOwner(
            scope = scope,
            store = store,
            clock = clock,
        )

        /**
         * The explorer shares the store with the verification path in production.
         * Proving the verification path (verifier -> search) and every write stay
         * untouched after explorer use is the read-only regression guard.
         */
        fun assertReadOnly() {
            assertEquals("no verifier calls", 0, verificationPath.verifyCalls)
            assertEquals("no authorized verifier calls", 0, verificationPath.authorizedCalls)
            assertEquals("no web search", 0, search.searchCalls)
            assertEquals("no evidence writes", 0, store.saves)
            assertEquals("no additional inserts", writesBaseline, dao.inserts)
            assertEquals("no deletes", 0, dao.deletes)
        }
    }
}

private class CountingStore(private val delegate: EvidenceStore) : EvidenceUiStore {
    var loads = 0
    var saves = 0
    override suspend fun load(message: MessageEntity): EvidenceStore.LoadResult {
        loads++
        return delegate.load(message)
    }
    override suspend fun saveComplete(message: MessageEntity, report: EvidenceReport): EvidenceStore.SaveResult {
        saves++
        return delegate.saveComplete(message, report)
    }
}

private class CountingSearchExecutor : EvidenceSearchExecutor {
    var searchCalls = 0
    override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
        searchCalls++
        return EvidenceSearchResult.Success(emptyList())
    }
}

private class RecordingVerificationPath(
    private val search: CountingSearchExecutor,
) : EvidenceUiVerifier {
    var verifyCalls = 0
    var authorizedCalls = 0
    override suspend fun verify(message: MessageEntity, plan: VerificationPlan): EvidenceVerificationResult {
        verifyCalls++
        search.search(TargetedSearchRequest("C1", "q"))
        return EvidenceVerificationResult.Failed(EvidenceVerificationFailureReason.DEADLINE_EXCEEDED)
    }
    override suspend fun verifyWithSearchAuthorization(
        message: MessageEntity,
        plan: VerificationPlan,
        operationId: String,
        authorization: PostAnswerVerificationSearchAuthorization,
        isCurrent: suspend () -> Boolean,
    ): EvidenceVerificationResult {
        authorizedCalls++
        search.search(TargetedSearchRequest("C1", "q"))
        return EvidenceVerificationResult.Failed(EvidenceVerificationFailureReason.EGRESS_REFUSED)
    }
}

private class BlockingStore : EvidenceUiStore {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var result: EvidenceStore.LoadResult = EvidenceStore.LoadResult.Missing
    var saves = 0
    override suspend fun load(message: MessageEntity): EvidenceStore.LoadResult {
        entered.complete(Unit)
        release.await()
        return result
    }
    override suspend fun saveComplete(message: MessageEntity, report: EvidenceReport): EvidenceStore.SaveResult {
        saves++
        error("read-only explorer must never save")
    }
}

private class CountingEvidenceDao : EvidenceDao {
    val rows = mutableMapOf<Long, MessageEvidenceEntity>()
    var inserts = 0
    var deletes = 0
    override suspend fun insertEvidence(evidence: MessageEvidenceEntity) {
        inserts++
        rows[evidence.messageId] = evidence
    }
    override suspend fun getEvidenceForMessage(messageId: Long): MessageEvidenceEntity? = rows[messageId]
    override fun observeEvidenceForConversation(conversationId: Long) = flowOf(rows.values.toList())
    override fun observeEvidenceRevisionsForConversation(conversationId: Long) = flowOf(
        rows.values.map {
            MessageEvidenceRevision(it.messageId, it.answerSha256, it.schemaVersion, it.verifierVersion, it.checkedAt, it.reportJson.length)
        },
    )
    override suspend fun getEvidenceForConversation(conversationId: Long): List<MessageEvidenceEntity> = rows.values.toList()
    override suspend fun deleteEvidenceForMessage(messageId: Long) {
        deletes++
        rows.remove(messageId)
    }
}
