package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.EvidenceClaim
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceSearchExecutor
import com.projectnuke.fusion.data.EvidenceSearchResult
import com.projectnuke.fusion.data.EvidenceStore
import com.projectnuke.fusion.data.EvidenceVerificationPlanner
import com.projectnuke.fusion.data.EvidenceVerificationResult
import com.projectnuke.fusion.data.EvidenceVerdict
import com.projectnuke.fusion.data.EVIDENCE_VERIFIER_VERSION
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.PostAnswerVerificationSearchExecutor
import com.projectnuke.fusion.data.TargetedSearchRequest
import com.projectnuke.fusion.data.VerificationPlan
import com.projectnuke.fusion.privacy.PostAnswerVerificationSearchAuthorization
import com.projectnuke.fusion.privacy.PostAnswerVerificationSearchIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SelectedAnswerTextVerificationTest {
    private val fact = "Acme released ProductX2 in 2026."
    private val answer = "$fact\n\nBeta launched ProductY3 in 2025."
    private val message = MessageEntity(501L, 77L, "assistant", answer, 1L)
    private val document = parseMarkdownDocument(answer)
    private val renderedIdentity = AnswerTextSelectionPolicy.renderedIdentity(
        AnswerTextSelectionPolicy.renderedBlocks(document),
    )
    private val selected = (AnswerTextSelectionPolicy.capture(
        conversationId = message.conversationId,
        message = message,
        answerSurfaceIdentity = AnswerSurfaceIdentity.of(message.conversationId, message),
        selectedParts = listOf(fact),
        document = document,
        selectionId = "selection-one",
    ) as AnswerTextSelectionDecision.Eligible).snapshot

    private class MemoryStore(var loadResult: EvidenceStore.LoadResult = EvidenceStore.LoadResult.Missing) : EvidenceUiStore {
        var loads = 0
        var saves = 0
        override suspend fun load(message: MessageEntity): EvidenceStore.LoadResult {
            loads++
            return loadResult
        }
        override suspend fun saveComplete(message: MessageEntity, report: EvidenceReport): EvidenceStore.SaveResult {
            saves++
            return EvidenceStore.SaveResult.Rejected(EvidenceStore.SaveRejection.INVALID_REPORT)
        }
    }

    private class RecordingExecutor : PostAnswerVerificationSearchExecutor {
        val unauthenticated = mutableListOf<TargetedSearchRequest>()
        val authorized = mutableListOf<TargetedSearchRequest>()
        override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
            unauthenticated += request
            return EvidenceSearchResult.Success(emptyList())
        }
        override suspend fun searchAuthorizedPostAnswerVerification(
            request: TargetedSearchRequest,
            operationId: String,
            identity: PostAnswerVerificationSearchIdentity,
            authorization: PostAnswerVerificationSearchAuthorization,
            isCurrent: suspend () -> Boolean,
        ): EvidenceSearchResult {
            authorized += request
            return EvidenceSearchResult.Success(emptyList())
        }
    }

    private data class VerificationTrace(val message: MessageEntity, val plan: VerificationPlan)

    private fun recordingVerifier(
        executor: RecordingExecutor,
        traces: MutableList<VerificationTrace>,
    ): EvidenceUiVerifier {
        val delegate = SearchBackedEvidenceUiVerifier(executor)
        return object : EvidenceUiVerifier {
            override suspend fun verify(
                message: MessageEntity,
                plan: VerificationPlan,
            ): EvidenceVerificationResult {
                traces += VerificationTrace(message, plan)
                return delegate.verify(message, plan)
            }

            override suspend fun verifyWithSearchAuthorization(
                message: MessageEntity,
                plan: VerificationPlan,
                operationId: String,
                authorization: PostAnswerVerificationSearchAuthorization,
                isCurrent: suspend () -> Boolean,
            ): EvidenceVerificationResult {
                traces += VerificationTrace(message, plan)
                return delegate.verifyWithSearchAuthorization(message, plan, operationId, authorization, isCurrent)
            }
        }
    }

    private fun storedReport(claimId: String, text: String) = EvidenceReport(
        schemaVersion = 1,
        answerSha256 = EvidenceFingerprint.canonicalAnswerHash(message.content),
        checkedAt = 10L,
        verifierVersion = EVIDENCE_VERIFIER_VERSION,
        claims = listOf(EvidenceClaim(claimId, text, EvidenceVerdict.NOT_CHECKED, emptyList(), null)),
        sources = emptyList(),
    )

    private fun owner(
        scope: kotlinx.coroutines.CoroutineScope,
        verifier: EvidenceUiVerifier,
        store: EvidenceUiStore,
        currentMessageReader: suspend (MessageEntity) -> MessageEntity? = { it },
        isSelectionCurrent: (String) -> Boolean = { it == selected.selectionId },
    ) = SelectedClaimReverificationOwner(
        scope = scope,
        verifier = verifier,
        clock = com.projectnuke.fusion.data.EvidenceClock { 10L },
        privateTextsProvider = { emptyList() },
        currentMessageReader = currentMessageReader,
        evidenceStore = store,
        isCurrentAnswer = { true },
        isCapturedSelectionCurrent = isSelectionCurrent,
    )

    @Test fun `new selection executes one real source only verification and never persists`() = runTest {
        val store = MemoryStore()
        val executor = RecordingExecutor()
        val traces = mutableListOf<VerificationTrace>()
        val owner = owner(this, recordingVerifier(executor, traces), store)

        owner.verifySelectedText(message, selected) { renderedIdentity }
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        assertEquals(selected.claimId, consent.claimId)
        owner.choose(consent.operationId, SelectedClaimSourceChoice.EXISTING_SOURCES_ONLY)
        advanceUntilIdle()

        val result = owner.state.value as SelectedClaimReverificationState.Result
        val trace = traces.single()
        assertEquals(message, trace.message)
        assertEquals(1, trace.plan.claims.size)
        assertEquals(1, trace.plan.allCandidates.size)
        assertEquals(fact, trace.plan.claims.single().candidate.text)
        assertEquals(selected.claimId, result.report.claims.single().id)
        assertEquals(fact, result.report.claims.single().text)
        assertTrue(executor.unauthenticated.isEmpty())
        assertTrue(executor.authorized.isEmpty())
        assertEquals(1, store.loads)
        assertEquals(0, store.saves)
        assertEquals(EvidenceStore.LoadResult.Missing, store.loadResult)
        assertEquals(null, result.storedReport)
    }

    @Test fun `exact compatible stored claim reuses canonical claim identity`() = runTest {
        val canonical = EvidenceVerificationPlanner.plan(message.content).allCandidates.single { it.text == fact }
        val store = MemoryStore(EvidenceStore.LoadResult.Valid(storedReport(canonical.id, fact)))
        val executor = RecordingExecutor()
        val traces = mutableListOf<VerificationTrace>()
        val owner = owner(this, recordingVerifier(executor, traces), store)

        owner.verifySelectedText(message, selected) { renderedIdentity }
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        assertEquals(canonical.id, consent.claimId)
        owner.choose(consent.operationId, SelectedClaimSourceChoice.EXISTING_SOURCES_ONLY)
        advanceUntilIdle()

        val result = owner.state.value as SelectedClaimReverificationState.Result
        assertEquals(canonical.id, traces.single().plan.claims.single().candidate.id)
        assertEquals(canonical.id, result.claimId)
        assertEquals((store.loadResult as EvidenceStore.LoadResult.Valid).report, result.storedReport)
        assertNotEquals(selected.claimId, result.claimId)
        assertEquals(0, store.saves)
    }

    @Test fun `web consent is exact one shot and bound to this selection operation`() = runTest {
        val store = MemoryStore()
        val executor = RecordingExecutor()
        val traces = mutableListOf<VerificationTrace>()
        val owner = owner(this, recordingVerifier(executor, traces), store)

        owner.verifySelectedText(message, selected) { renderedIdentity }
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        assertEquals(selected.claimId, consent.query.claimId)
        assertTrue(consent.query.query.isNotBlank())
        owner.choose(consent.operationId, SelectedClaimSourceChoice.WEB)
        advanceUntilIdle()

        val result = owner.state.value as SelectedClaimReverificationState.Result
        assertTrue(result.webSearchAuthorized)
        assertEquals(
            listOf(consent.query.claimId to consent.query.query),
            executor.authorized.map { it.claimId to it.query },
        )
        assertTrue(executor.unauthenticated.isEmpty())
        assertEquals(1, traces.single().plan.claims.size)
        assertEquals(message, traces.single().message)
        assertEquals(0, store.saves)
    }

    @Test fun `selection change while verifier is suspended drops late transient result`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var currentSelection = selected.selectionId
        val store = MemoryStore()
        var trace: VerificationTrace? = null
        val verifier = object : EvidenceUiVerifier {
            override suspend fun verify(message: MessageEntity, plan: VerificationPlan): EvidenceVerificationResult {
                trace = VerificationTrace(message, plan)
                entered.complete(Unit)
                release.await()
                val claim = plan.claims.single().candidate
                return EvidenceVerificationResult.Complete(
                    EvidenceReport(
                        schemaVersion = 1,
                        answerSha256 = EvidenceFingerprint.canonicalAnswerHash(message.content),
                        checkedAt = 10L,
                        verifierVersion = EVIDENCE_VERIFIER_VERSION,
                        claims = listOf(EvidenceClaim(claim.id, claim.text, EvidenceVerdict.NOT_CHECKED, emptyList(), null)),
                        sources = emptyList(),
                    ),
                )
            }
        }
        val owner = owner(
            this,
            verifier,
            store,
            isSelectionCurrent = { it == currentSelection },
        )

        owner.verifySelectedText(message, selected) { renderedIdentity }
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        owner.choose(consent.operationId, SelectedClaimSourceChoice.EXISTING_SOURCES_ONLY)
        runCurrent()
        entered.await()
        currentSelection = "another-selection"
        release.complete(Unit)
        advanceUntilIdle()

        assertFalse(owner.state.value is SelectedClaimReverificationState.Result)
        assertEquals(1, requireNotNull(trace).plan.claims.size)
        assertEquals(0, store.saves)
    }

    @Test fun `persisted message deletion during selected verification prevents result settlement`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var persisted: MessageEntity? = message
        val verifier = object : EvidenceUiVerifier {
            override suspend fun verify(message: MessageEntity, plan: VerificationPlan): EvidenceVerificationResult {
                entered.complete(Unit)
                release.await()
                val claim = plan.claims.single().candidate
                return EvidenceVerificationResult.Complete(
                    EvidenceReport(
                        schemaVersion = 1,
                        answerSha256 = EvidenceFingerprint.canonicalAnswerHash(message.content),
                        checkedAt = 10L,
                        verifierVersion = EVIDENCE_VERIFIER_VERSION,
                        claims = listOf(EvidenceClaim(claim.id, claim.text, EvidenceVerdict.NOT_CHECKED, emptyList(), null)),
                        sources = emptyList(),
                    ),
                )
            }
        }
        val store = MemoryStore()
        val owner = owner(this, verifier, store, currentMessageReader = { persisted })

        owner.verifySelectedText(message, selected) { renderedIdentity }
        runCurrent()
        val consent = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        owner.choose(consent.operationId, SelectedClaimSourceChoice.EXISTING_SOURCES_ONLY)
        runCurrent()
        entered.await()
        persisted = null
        release.complete(Unit)
        advanceUntilIdle()

        assertFalse(owner.state.value is SelectedClaimReverificationState.Result)
        val ineligible = owner.state.value as SelectedClaimReverificationState.Ineligible
        assertEquals(SelectedClaimIneligibleReason.ANSWER_CHANGED, ineligible.reason)
        assertEquals(0, store.saves)
    }

    @Test fun `new native selection supersedes another pending selection consent`() = runTest {
        val store = MemoryStore()
        val owner = owner(
            this,
            recordingVerifier(RecordingExecutor(), mutableListOf()),
            store,
            isSelectionCurrent = { true },
        )
        owner.verifySelectedText(message, selected) { renderedIdentity }
        runCurrent()
        val first = owner.state.value as SelectedClaimReverificationState.AwaitingConsent

        val second = selected.copy(selectionId = "selection-two")
        owner.verifySelectedText(message, second) { renderedIdentity }
        runCurrent()
        val secondPrompt = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        assertNotEquals(first.operationId, secondPrompt.operationId)
        assertNotEquals(first.claimId, secondPrompt.claimId)
        assertEquals(second.claimId, secondPrompt.query.claimId)
        owner.cancel()
        advanceUntilIdle()
    }

    @Test fun `repeated tap for the same native selection is single flight`() = runTest {
        val store = MemoryStore()
        val owner = owner(
            this,
            recordingVerifier(RecordingExecutor(), mutableListOf()),
            store,
        )
        owner.verifySelectedText(message, selected) { renderedIdentity }
        runCurrent()
        val first = owner.state.value as SelectedClaimReverificationState.AwaitingConsent

        owner.verifySelectedText(message, selected) { renderedIdentity }
        runCurrent()
        val second = owner.state.value as SelectedClaimReverificationState.AwaitingConsent
        assertEquals(first.operationId, second.operationId)
        assertEquals(1, store.loads)
        owner.cancel()
        advanceUntilIdle()
    }
}
