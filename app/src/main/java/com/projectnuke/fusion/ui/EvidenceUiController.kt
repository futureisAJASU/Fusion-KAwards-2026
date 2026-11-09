package com.projectnuke.fusion.ui

import com.projectnuke.fusion.audit.AnswerAuditExecutionOrchestrator
import com.projectnuke.fusion.audit.AnswerAuditExecutionSnapshot
import com.projectnuke.fusion.audit.UnavailableVerifierBackend
import com.projectnuke.fusion.audit.VerifierBackend
import com.projectnuke.fusion.data.*
import com.projectnuke.fusion.privacy.AuthorizedTargetedQuery
import com.projectnuke.fusion.privacy.DataEgressPolicy
import com.projectnuke.fusion.privacy.PostAnswerVerificationSearchAuthorization
import com.projectnuke.fusion.privacy.PostAnswerVerificationSearchIdentity
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class EvidenceExecutionState {
    IDLE,
    PLANNING,
    AWAITING_SEARCH_AUTHORIZATION,
    CHECKING,
    COMPLETE,
    FAILED,
    CANCELLED,
}

enum class EvidenceStoredState { MISSING, VALID, OUTDATED, STALE, CORRUPT }

data class EvidenceAnswerIdentity(
    val messageId: Long,
    val answerSha256: String,
    val conversationId: Long = 0L,
) {
    companion object {
        fun of(message: MessageEntity) = EvidenceAnswerIdentity(
            messageId = message.id,
            answerSha256 = EvidenceFingerprint.canonicalAnswerHash(message.content),
            conversationId = message.conversationId,
        )
    }
}

data class EvidenceUiState(
    val identity: EvidenceAnswerIdentity? = null,
    val execution: EvidenceExecutionState = EvidenceExecutionState.IDLE,
    val stored: EvidenceStoredState = EvidenceStoredState.MISSING,
    val report: EvidenceReport? = null,
    val outdatedReason: EvidenceOutdatedReason? = null,
    val transientAudit: AnswerAuditExecutionSnapshot? = null,
    val notice: String? = null,
)

data class PostAnswerVerificationSearchPrompt(
    val operationId: String,
    val identity: PostAnswerVerificationSearchIdentity,
    val queries: List<AuthorizedTargetedQuery>,
) {
    val queryBoundTextLength: Int get() = queries.sumOf { it.query.length }
}

enum class PostAnswerVerificationSearchChoice { EXISTING_SOURCES_ONLY, WEB, CANCEL }

interface EvidenceUiStore {
    suspend fun load(message: MessageEntity): EvidenceStore.LoadResult
    suspend fun saveComplete(message: MessageEntity, report: EvidenceReport): EvidenceStore.SaveResult
}

class DurableEvidenceUiStore(private val store: EvidenceStore) : EvidenceUiStore {
    override suspend fun load(message: MessageEntity) = store.load(message)
    override suspend fun saveComplete(message: MessageEntity, report: EvidenceReport) = store.saveComplete(message, report)
}

fun interface EvidenceUiVerifier {
    suspend fun verify(message: MessageEntity, plan: VerificationPlan): EvidenceVerificationResult

    /** Generated queries are refused by default unless an implementation owns this explicit seam. */
    suspend fun verifyWithSearchAuthorization(
        message: MessageEntity,
        plan: VerificationPlan,
        operationId: String,
        authorization: PostAnswerVerificationSearchAuthorization,
        isCurrent: suspend () -> Boolean,
    ): EvidenceVerificationResult =
        EvidenceVerificationResult.Failed(EvidenceVerificationFailureReason.EGRESS_REFUSED)
}

/** Search failures remain operation failures, separate from claim verdicts. */
class SearchBackedEvidenceUiVerifier(
    private val executor: EvidenceSearchExecutor,
    private val semanticMatcher: com.projectnuke.fusion.retrieval.SemanticCandidateMatcher? = null,
    private val verifierBackend: VerifierBackend = UnavailableVerifierBackend,
    private val auditBackendDeadlineMs: Long = com.projectnuke.fusion.audit.AUDIT_BACKEND_TOTAL_DEADLINE_MS,
) : EvidenceUiVerifier {
    override suspend fun verify(message: MessageEntity, plan: VerificationPlan): EvidenceVerificationResult =
        execute(message, plan, searchAllowed = { false })

    override suspend fun verifyWithSearchAuthorization(
        message: MessageEntity,
        plan: VerificationPlan,
        operationId: String,
        authorization: PostAnswerVerificationSearchAuthorization,
        isCurrent: suspend () -> Boolean,
    ): EvidenceVerificationResult {
        val identity = PostAnswerVerificationSearchIdentity(
            conversationId = message.conversationId,
            assistantMessageId = message.id,
            answerSha256 = EvidenceFingerprint.canonicalAnswerHash(message.content),
            responseVersionId = message.id,
        )
        val plannedQueries = plan.targetedSearchRequests.map { AuthorizedTargetedQuery(it.claimId, it.query) }
        if (plannedQueries.isEmpty() || plannedQueries != authorization.queries ||
            !authorization.claimExecution(operationId, identity) || !isCurrent()
        ) {
            return EvidenceVerificationResult.Failed(EvidenceVerificationFailureReason.EGRESS_REFUSED)
        }
        if (plannedQueries.any { approved ->
                !DataEgressPolicy.isAuthorizedPostAnswerVerificationQuery(
                    authorization = authorization,
                    operationId = operationId,
                    identity = identity,
                    claimId = approved.claimId,
                    query = approved.query,
                )
            }
        ) return EvidenceVerificationResult.Failed(EvidenceVerificationFailureReason.EGRESS_REFUSED)

        val used = mutableSetOf<AuthorizedTargetedQuery>()
        return execute(
            message = message,
            plan = plan,
            searchAllowed = { request ->
                currentCoroutineContext().ensureActive()
                if (!isCurrent() || message.role != EvidenceStore.ASSISTANT_ROLE ||
                    EvidenceFingerprint.canonicalAnswerHash(message.content) != authorization.identity.answerSha256
                ) return@execute false
                val approved = AuthorizedTargetedQuery(request.claimId, request.query)
                val exactPlannedRequest = plan.targetedSearchRequests.any {
                    it.claimId == request.claimId && it.query.contentEquals(request.query)
                }
                val approvedByPolicy = DataEgressPolicy.isAuthorizedPostAnswerVerificationQuery(
                    authorization = authorization,
                    operationId = operationId,
                    identity = identity,
                    claimId = request.claimId,
                    query = request.query,
                )
                exactPlannedRequest && approvedByPolicy && used.add(approved)
            },
            authorizedSearch = { request ->
                val postAnswerExecutor = executor as? PostAnswerVerificationSearchExecutor
                if (postAnswerExecutor != null) {
                    postAnswerExecutor.searchAuthorizedPostAnswerVerification(
                        request = request,
                        operationId = operationId,
                        identity = identity,
                        authorization = authorization,
                        isCurrent = isCurrent,
                    )
                } else executor.search(request)
            },
        )
    }

    private suspend fun execute(
        message: MessageEntity,
        plan: VerificationPlan,
        searchAllowed: suspend (TargetedSearchRequest) -> Boolean,
        authorizedSearch: (suspend (TargetedSearchRequest) -> EvidenceSearchResult)? = null,
    ): EvidenceVerificationResult {
        var failure: EvidenceVerificationFailureReason? = null
        val gatedExecutor = object : EvidenceSearchExecutor {
            override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                if (!searchAllowed(request)) {
                    failure = EvidenceVerificationFailureReason.EGRESS_REFUSED
                    return EvidenceSearchResult.Failed("EGRESS_REFUSED")
                }
                val result = try {
                    authorizedSearch?.invoke(request) ?: executor.search(request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    EvidenceSearchResult.Failed("SEARCH_UNAVAILABLE")
                }
                if (result is EvidenceSearchResult.Failed) {
                    failure = if (result.reason == "EGRESS_REFUSED") {
                        EvidenceVerificationFailureReason.EGRESS_REFUSED
                    } else EvidenceVerificationFailureReason.SEARCH_UNAVAILABLE
                }
                return result
            }
        }
        val result = EvidenceVerificationService(
            gatedExecutor,
            semanticMatcher = semanticMatcher,
            auditExecutionOrchestrator = AnswerAuditExecutionOrchestrator(verifierBackend, auditBackendDeadlineMs),
        ).verify(message, plan)
        return failure?.let(EvidenceVerificationResult::Failed) ?: result
    }
}

/**
 * Narrow owner for explicit post-answer fact verification. Planning, user
 * authorization, verification and Evidence persistence stay bound to one
 * immutable answer identity and one operation id.
 */
class PostAnswerFactVerificationOwner(
    private val scope: CoroutineScope,
    private val store: EvidenceUiStore,
    private val verifier: EvidenceUiVerifier,
    private val clock: EvidenceClock = EvidenceClock { System.currentTimeMillis() },
    private val privateTextsProvider: (() -> List<String>)? = null,
    private val currentMessageReader: suspend (MessageEntity) -> MessageEntity? = { it },
    private val isCurrentAnswer: () -> Boolean = { true },
    private val operationIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutableState = MutableStateFlow(EvidenceUiState())
    val state = mutableState.asStateFlow()
    private val _searchPrompt = MutableStateFlow<PostAnswerVerificationSearchPrompt?>(null)
    val searchPrompt = _searchPrompt.asStateFlow()

    private var job: Job? = null
    private var epoch = 0L
    private var message: MessageEntity? = null
    private var pendingChoice: CompletableDeferred<PostAnswerVerificationSearchChoice>? = null

    fun open(message: MessageEntity) = start(message, retry = false)

    fun retry() {
        if (state.value.execution == EvidenceExecutionState.AWAITING_SEARCH_AUTHORIZATION ||
            state.value.execution == EvidenceExecutionState.PLANNING ||
            state.value.execution == EvidenceExecutionState.CHECKING
        ) return
        message?.let { start(it, retry = true) }
    }

    fun chooseSearch(operationId: String, choice: PostAnswerVerificationSearchChoice) {
        if (_searchPrompt.value?.operationId != operationId) return
        pendingChoice?.complete(choice)
        pendingChoice = null
    }

    fun cancel() {
        epoch++
        pendingChoice?.cancel()
        pendingChoice = null
        _searchPrompt.value = null
        job?.cancel()
        mutableState.value = mutableState.value.copy(
            execution = EvidenceExecutionState.CANCELLED,
            report = null,
            transientAudit = null,
        )
    }

    fun dismiss() {
        cancel()
        message = null
        mutableState.value = EvidenceUiState()
    }

    private fun start(message: MessageEntity, retry: Boolean) {
        if (message.id <= 0L || message.role != EvidenceStore.ASSISTANT_ROLE || message.conversationId <= 0L) return
        val identity = EvidenceAnswerIdentity.of(message)
        if (job?.isActive == true && mutableState.value.identity == identity) return

        val previousJob = job
        previousJob?.cancel()
        pendingChoice?.cancel()
        pendingChoice = null
        _searchPrompt.value = null
        val run = ++epoch
        val operationId = operationIdFactory()
        this.message = message
        mutableState.value = EvidenceUiState(identity, EvidenceExecutionState.PLANNING)
        job = scope.launch {
            fun owns() = epoch == run && mutableState.value.identity == identity && isCurrentAnswer()
            suspend fun requireOwner() {
                currentCoroutineContext().ensureActive()
                if (!owns()) throw CancellationException("post-answer verification owner changed")
            }
            suspend fun currentStoredMessage(): MessageEntity? {
                requireOwner()
                val current = currentMessageReader(message)
                requireOwner()
                return current
            }

            try {
                // A replaced owner is fenced by epoch/identity checks. Do not
                // make the new answer wait for an old verifier that is
                // cancelling or unwinding; that would delay its consent UI.
                requireOwner()
                if (currentStoredMessage() != message) {
                    mutableState.value = mutableState.value.copy(
                        execution = EvidenceExecutionState.CANCELLED,
                        notice = "답변이 변경되었거나 삭제되어 확인을 시작하지 않았습니다.",
                    )
                    return@launch
                }

                val privateNotice = privateSearchNotice(message)
                val initialPlan = EvidenceVerificationPlanner.plan(
                    message.content,
                    allowTargetedSearch = privateNotice == null,
                )
                // If an exact planner query overlaps known private text or a
                // local filename, suppress every targeted request before any
                // search authorization can be presented.
                val privateTexts = try {
                    privateTextsProvider?.invoke().orEmpty()
                } catch (_: Exception) {
                    null
                }
                val localFileNames = LocalRagSourceMetadata.parse(message.content).map { it.fileName }
                val queryContainsPrivate = initialPlan.targetedSearchRequests.any { request ->
                    DataEgressPolicy.webQueryContainsPrivate(
                        request.query,
                        privateTexts.orEmpty(),
                        localFileNames,
                    )
                }
                val effectivePlan = if (privateNotice != null || queryContainsPrivate || privateTexts == null) {
                    EvidenceVerificationPlanner.plan(message.content, allowTargetedSearch = false)
                } else {
                    initialPlan
                }
                val notice = privateNotice ?: if (queryContainsPrivate || privateTexts == null) {
                    "개인 자료와 겹치거나 안전성을 확인할 수 없는 검색어는 웹 검색으로 보내지 않습니다."
                } else null

                requireOwner()
                if (effectivePlan.claims.isEmpty() &&
                    !effectivePlan.candidateWindowTruncated &&
                    !effectivePlan.overlengthSegmentsExcluded
                ) {
                    mutableState.value = EvidenceUiState(
                        identity = identity,
                        execution = EvidenceExecutionState.COMPLETE,
                        notice = "확인할 사실 주장을 찾지 못했습니다.",
                    )
                    return@launch
                }

                val loaded = store.load(message)
                requireOwner()
                var outdatedReason: EvidenceOutdatedReason? = null
                val stored = when (loaded) {
                    EvidenceStore.LoadResult.Missing -> EvidenceStoredState.MISSING
                    is EvidenceStore.LoadResult.Valid -> if (
                        EvidenceReportCompatibilityPolicy.isCurrentDisplayCompatible(
                            loaded.report,
                            identity.answerSha256,
                            clock.nowMillis(),
                        )
                    ) EvidenceStoredState.VALID else {
                        outdatedReason = EvidenceReportCompatibilityPolicy.outdatedReason(
                            loaded.report,
                            identity.answerSha256,
                            clock.nowMillis(),
                        )
                        EvidenceStoredState.OUTDATED
                    }
                    is EvidenceStore.LoadResult.Stale -> EvidenceStoredState.STALE
                    EvidenceStore.LoadResult.Corrupt -> EvidenceStoredState.CORRUPT
                }
                mutableState.value = mutableState.value.copy(
                    stored = stored,
                    outdatedReason = outdatedReason,
                    notice = notice,
                )
                if (!retry) {
                    when (loaded) {
                        is EvidenceStore.LoadResult.Valid -> {
                            if (stored == EvidenceStoredState.VALID && validFor(identity, loaded.report)) {
                                mutableState.value = mutableState.value.copy(
                                    execution = EvidenceExecutionState.COMPLETE,
                                    report = loaded.report,
                                )
                            } else {
                                mutableState.value = mutableState.value.copy(execution = EvidenceExecutionState.IDLE)
                            }
                            return@launch
                        }
                        is EvidenceStore.LoadResult.Stale, EvidenceStore.LoadResult.Corrupt -> {
                            mutableState.value = mutableState.value.copy(execution = EvidenceExecutionState.IDLE)
                            return@launch
                        }
                        EvidenceStore.LoadResult.Missing -> Unit
                    }
                }

                var planToVerify = effectivePlan
                var authorization: PostAnswerVerificationSearchAuthorization? = null
                if (effectivePlan.targetedSearchRequests.isNotEmpty()) {
                    val egressIdentity = identity.toPostAnswerSearchIdentity()
                    val queries = effectivePlan.targetedSearchRequests.map {
                        AuthorizedTargetedQuery(it.claimId, it.query)
                    }
                    val prompt = PostAnswerVerificationSearchPrompt(operationId, egressIdentity, queries)
                    val decision = CompletableDeferred<PostAnswerVerificationSearchChoice>()
                    pendingChoice = decision
                    _searchPrompt.value = prompt
                    mutableState.value = mutableState.value.copy(
                        execution = EvidenceExecutionState.AWAITING_SEARCH_AUTHORIZATION,
                    )
                    val choice = decision.await()
                    pendingChoice = null
                    _searchPrompt.value = null
                    requireOwner()
                    when (choice) {
                        PostAnswerVerificationSearchChoice.CANCEL -> {
                            mutableState.value = mutableState.value.copy(execution = EvidenceExecutionState.CANCELLED)
                            return@launch
                        }
                        PostAnswerVerificationSearchChoice.EXISTING_SOURCES_ONLY -> {
                            planToVerify = sourceOnlyPlan(effectivePlan)
                        }
                        PostAnswerVerificationSearchChoice.WEB -> {
                            authorization = PostAnswerVerificationSearchAuthorization.afterExplicitConfirmation(
                                operationId = operationId,
                                identity = egressIdentity,
                                queries = queries,
                            )
                            if (authorization == null) {
                                mutableState.value = mutableState.value.copy(execution = EvidenceExecutionState.FAILED)
                                return@launch
                            }
                        }
                    }
                }

                requireOwner()
                if (currentStoredMessage() != message) {
                    mutableState.value = mutableState.value.copy(
                        execution = EvidenceExecutionState.CANCELLED,
                        report = null,
                        notice = "답변이 변경되어 확인 결과를 저장하지 않았습니다.",
                    )
                    return@launch
                }
                mutableState.value = mutableState.value.copy(execution = EvidenceExecutionState.CHECKING)
                val result = if (authorization != null) {
                    verifier.verifyWithSearchAuthorization(
                        message = message,
                        plan = planToVerify,
                        operationId = operationId,
                        authorization = authorization,
                        isCurrent = { owns() && currentMessageReader(message) == message },
                    )
                } else {
                    verifier.verify(message, planToVerify)
                }
                when (result) {
                    is EvidenceVerificationResult.Complete -> {
                        requireOwner()
                        if (currentStoredMessage() != message || !validFor(identity, result.report)) {
                            mutableState.value = mutableState.value.copy(
                                execution = EvidenceExecutionState.CANCELLED,
                                report = null,
                                transientAudit = null,
                                notice = "답변이 변경되어 확인 결과를 저장하지 않았습니다.",
                            )
                            return@launch
                        }
                        // Re-read immediately before persistence; the captured
                        // Compose message is never treated as current by itself.
                        if (store.saveComplete(message, result.report) !is EvidenceStore.SaveResult.Saved) {
                            mutableState.value = mutableState.value.copy(execution = EvidenceExecutionState.FAILED, report = null)
                            return@launch
                        }
                        requireOwner()
                        mutableState.value = EvidenceUiState(
                            identity = identity,
                            execution = EvidenceExecutionState.COMPLETE,
                            stored = EvidenceStoredState.VALID,
                            report = result.report,
                            transientAudit = result.transientAudit,
                            notice = notice,
                        )
                    }
                    is EvidenceVerificationResult.Failed -> {
                        requireOwner()
                        mutableState.value = mutableState.value.copy(execution = EvidenceExecutionState.FAILED)
                    }
                    EvidenceVerificationResult.Cancelled -> {
                        requireOwner()
                        mutableState.value = mutableState.value.copy(execution = EvidenceExecutionState.CANCELLED)
                    }
                }
            } catch (cancelled: CancellationException) {
                if (epoch == run && mutableState.value.identity == identity) mutableState.value = mutableState.value.copy(
                    execution = EvidenceExecutionState.CANCELLED,
                    report = null,
                    transientAudit = null,
                )
                throw cancelled
            } catch (_: Exception) {
                if (owns()) mutableState.value = mutableState.value.copy(execution = EvidenceExecutionState.FAILED, report = null)
            }
        }
    }

    private fun privateSearchNotice(message: MessageEntity): String? =
        com.projectnuke.fusion.privacy.PostAnswerSearchPrivacy.privateSearchNotice(message)

    private fun validFor(identity: EvidenceAnswerIdentity, report: EvidenceReport): Boolean =
        report.answerSha256 == identity.answerSha256 &&
            report.verifierVersion == EVIDENCE_VERIFIER_VERSION &&
            EvidenceReportCompatibilityPolicy.isCurrentDisplayCompatible(
                report,
                identity.answerSha256,
                clock.nowMillis(),
            )

    private fun sourceOnlyPlan(plan: VerificationPlan): VerificationPlan = plan.copy(
        claims = plan.claims.map { it.copy(needsTargetedSearch = false) },
        targetedSearchRequests = emptyList(),
    )

    private fun EvidenceAnswerIdentity.toPostAnswerSearchIdentity() = PostAnswerVerificationSearchIdentity(
        conversationId = conversationId,
        assistantMessageId = messageId,
        answerSha256 = answerSha256,
        responseVersionId = messageId,
    )
}

/** Kept as a source-compatible name for the existing Evidence UI host/tests. */
typealias EvidenceUiController = PostAnswerFactVerificationOwner

internal fun evidenceClaimReasonLabel(reasonCode: String?): String? = when (reasonCode) {
    "CLAIM_BUDGET_EXHAUSTED" -> "\uD655\uC778 \uC8FC\uC7A5 \uC608\uC0B0\uC73C\uB85C \uD655\uC778\uD558\uC9C0 \uC54A\uC74C"
    "SEARCH_BUDGET_EXHAUSTED" -> "\uCD94\uAC00 \uAC80\uC0C9 \uC608\uC0B0\uC73C\uB85C \uC790\uB8CC\uB97C \uCC3E\uC9C0 \uC54A\uC74C"
    "SEARCH_FAILED" -> "\uAC80\uC0C9 \uC2E4\uD328\uB85C \uCD94\uAC00 \uC790\uB8CC\uB97C \uD655\uC778\uD558\uC9C0 \uBABB\uD568"
    "SEARCH_SUPPRESSED_PRIVATE_SOURCE" -> "\uAC1C\uC778 \uC790\uB8CC \uBCF4\uD638\uB97C \uC704\uD574 \uC678\uBD80 \uAC80\uC0C9\uD558\uC9C0 \uC54A\uC74C"
    "NO_USABLE_SOURCE" -> "\uC0AC\uC6A9\uD560 \uC218 \uC788\uB294 \uC790\uB8CC\uAC00 \uC5C6\uC74C"
    "INSUFFICIENT_RULE_ALIGNMENT" -> "\uC120\uD0DD\uD55C \uC790\uB8CC\uB85C\uB294 \uC8FC\uC7A5\uC744 \uD655\uC778\uD558\uAE30 \uBD80\uC871\uD568"
    else -> null
}

fun EvidenceVerdict.evidenceLabel(): String = when (this) {
    EvidenceVerdict.SUPPORTED_BY_EVIDENCE -> "근거로 확인됨"
    EvidenceVerdict.CONFLICTING_EVIDENCE -> "상충하는 근거가 있음"
    EvidenceVerdict.INSUFFICIENT_EVIDENCE -> "근거 부족"
    EvidenceVerdict.NOT_CHECKED -> "확인하지 않음"
}
