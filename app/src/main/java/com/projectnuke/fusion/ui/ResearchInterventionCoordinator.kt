package com.projectnuke.fusion.ui

import android.content.Context
import android.content.SharedPreferences
import com.projectnuke.fusion.audit.ResearchInterventionAudit
import com.projectnuke.fusion.audit.ResearchInterventionAuditClaimRecord
import com.projectnuke.fusion.audit.ResearchInterventionAuditEvidenceRecord
import com.projectnuke.fusion.audit.ResearchInterventionAuditRecord
import com.projectnuke.fusion.audit.ResearchInterventionDraftStage
import com.projectnuke.fusion.audit.ResearchInterventionFindingSubject
import com.projectnuke.fusion.audit.ResearchInterventionOrchestrator
import com.projectnuke.fusion.audit.ResearchInterventionOutcome
import com.projectnuke.fusion.audit.ResearchInterventionReason
import com.projectnuke.fusion.audit.ResearchInterventionSearchAuthorizationContext
import com.projectnuke.fusion.audit.ResearchInterventionSearchAuthorizer
import com.projectnuke.fusion.audit.ResearchInterventionRetriever
import com.projectnuke.fusion.audit.ResearchInterventionRetrievalResult
import com.projectnuke.fusion.audit.ResearchInterventionVerification
import com.projectnuke.fusion.audit.ResearchInterventionVerifier
import com.projectnuke.fusion.audit.ResearchRevisionGenerator
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.data.EvidenceVerificationLimits
import com.projectnuke.fusion.privacy.DataEgressPrivateTextLedger
import com.projectnuke.fusion.privacy.ResearchInterventionSearchPermit
import com.projectnuke.fusion.retrieval.LocalRagSelection
import com.projectnuke.fusion.search.FusionWebSearch
import com.projectnuke.fusion.search.WebSearchProviderRepository
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace

/**
 * Evaluation-only activation gate for the Research intervention pipeline.
 *
 * It is OFF by default and additionally requires developer mode, so a normal
 * production build never runs the pre-persistence verifier, revision, or
 * retrieval path unless it was explicitly enabled for evaluation.
 */
internal object ResearchInterventionSettings {
    private const val PreferencesName = "fusion_chat_settings"
    private const val KeyEnabled = "fusion_research_intervention_enabled"
    private const val DeveloperModeKey = "fusion_developer_mode_enabled"

    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KeyEnabled, false) && prefs.getBoolean(DeveloperModeKey, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyEnabled, enabled)
            .apply()
    }
}

/** Result of one pre-persistence intervention attempt. */
internal enum class ResearchInterventionResolutionDisposition {
    NOT_APPLICABLE,
    COMPLETED,
    OWNERSHIP_LOST,
    ALREADY_RUNNING,
    FAILED_OR_DEGRADED,
}

internal data class ResearchInterventionResolution(
    val storedAssistantContent: String,
    val visibleAnswer: String,
    val audit: ResearchInterventionAudit?,
    val revised: Boolean,
    val abstained: Boolean,
    val disposition: ResearchInterventionResolutionDisposition,
) {
    val mayPersist: Boolean
        get() = disposition != ResearchInterventionResolutionDisposition.OWNERSHIP_LOST &&
            disposition != ResearchInterventionResolutionDisposition.ALREADY_RUNNING
}

/**
 * Binds the request-owned orchestrator to the request-owned shadow verifier
 * and maps the typed outcome onto exactly one durable assistant content
 * string. It never persists anything by itself.
 */
internal class ResearchInterventionCoordinator(
    private val orchestrator: ResearchInterventionOrchestrator,
    private val shadowOwner: ResearchDraftShadowOwner,
) {
    suspend fun resolve(
        request: GenerationRequestSnapshot,
        exactStoredAssistantContent: String,
        webSources: List<WebSearchSource>,
        localSelections: List<LocalRagSelection>,
        responseVersionTarget: ResearchDraftResponseVersionTarget?,
        composeStoredAnswer: (String, List<WebSearchSource>) -> String,
        revisionGenerator: ResearchRevisionGenerator?,
        searchAuthorizer: ResearchInterventionSearchAuthorizer?,
        retriever: ResearchInterventionRetriever?,
        owns: () -> Boolean,
    ): ResearchInterventionResolution {
        val canonicalDraft = visibleMessageContentPreservingWhitespace(exactStoredAssistantContent)
        if (canonicalDraft.isBlank()) {
            return ResearchInterventionResolution(
                storedAssistantContent = exactStoredAssistantContent,
                visibleAnswer = canonicalDraft,
                audit = null,
                revised = false,
                abstained = false,
                disposition = ResearchInterventionResolutionDisposition.NOT_APPLICABLE,
            )
        }
        val verifier = ResearchInterventionVerifier { draft, expectedFingerprint ->
            when (
                val result = shadowOwner.inspectBeforePersistence(
                    request = request,
                    exactStoredAssistantContent = draft.storedAnswer,
                    webSources = draft.webSources,
                    localSelections = draft.localSelections,
                    responseVersionTarget = responseVersionTarget,
                    expectedCanonicalDraftFingerprint = expectedFingerprint,
                    ownsDraft = owns,
                )
            ) {
                is ResearchDraftShadowOutcome.Completed ->
                    ResearchInterventionVerification.Observed(result.diagnostic)
                ResearchDraftShadowOutcome.NotOwned -> ResearchInterventionVerification.NotOwned
                is ResearchDraftShadowOutcome.Stale ->
                    ResearchInterventionVerification.Failed(ResearchInterventionReason.STALE_DRAFT)
                ResearchDraftShadowOutcome.Disabled,
                ResearchDraftShadowOutcome.NotResearchRequest,
                is ResearchDraftShadowOutcome.Failed,
                -> ResearchInterventionVerification.Failed(
                    ResearchInterventionReason.VERIFIER_RESULT_INCOMPLETE,
                )
            }
        }
        val outcome = orchestrator.run(
            com.projectnuke.fusion.audit.ResearchInterventionInput(
                request = request,
                responseVersionTarget = responseVersionTarget,
                originalVisibleAnswer = canonicalDraft,
                originalStoredAnswer = exactStoredAssistantContent,
                webSources = webSources,
                localSelections = localSelections,
                composeStoredAnswer = composeStoredAnswer,
                verifier = verifier,
                revisionGenerator = revisionGenerator,
                searchAuthorizer = searchAuthorizer,
                retriever = retriever,
                owns = owns,
            ),
        )
        return when (outcome) {
            is ResearchInterventionOutcome.Delivered -> ResearchInterventionResolution(
                storedAssistantContent = outcome.storedAnswer,
                visibleAnswer = outcome.visibleAnswer,
                audit = outcome.audit,
                revised = outcome.revised,
                abstained = false,
                disposition = if (
                    outcome.decision.action == com.projectnuke.fusion.audit.ResearchInterventionAction.DELIVER_UNVERIFIED
                ) {
                    ResearchInterventionResolutionDisposition.FAILED_OR_DEGRADED
                } else {
                    ResearchInterventionResolutionDisposition.COMPLETED
                },
            )
            is ResearchInterventionOutcome.Abstained -> ResearchInterventionResolution(
                // The final notice makes no factual use of either the initial
                // or newly retrieved evidence. Draft findings remain in the
                // explicitly labeled audit record; no source is attached as
                // provenance for the generic abstention message.
                storedAssistantContent = composeStoredAnswer(outcome.message, emptyList()),
                visibleAnswer = outcome.message,
                audit = outcome.audit,
                revised = false,
                abstained = true,
                disposition = if (outcome.decision.reason in FAILED_INTERVENTION_REASONS) {
                    ResearchInterventionResolutionDisposition.FAILED_OR_DEGRADED
                } else {
                    ResearchInterventionResolutionDisposition.COMPLETED
                },
            )
            ResearchInterventionOutcome.NotApplicable,
            ResearchInterventionOutcome.NotOwned,
            ResearchInterventionOutcome.AlreadyRunning,
            -> ResearchInterventionResolution(
                storedAssistantContent = exactStoredAssistantContent,
                visibleAnswer = canonicalDraft,
                audit = null,
                revised = false,
                abstained = false,
                disposition = when (outcome) {
                    ResearchInterventionOutcome.NotApplicable ->
                        ResearchInterventionResolutionDisposition.NOT_APPLICABLE
                    ResearchInterventionOutcome.NotOwned ->
                        ResearchInterventionResolutionDisposition.OWNERSHIP_LOST
                    ResearchInterventionOutcome.AlreadyRunning ->
                        ResearchInterventionResolutionDisposition.ALREADY_RUNNING
                    else -> error("Handled research intervention outcome unexpectedly")
                },
            )
        }
    }

    private companion object {
        val FAILED_INTERVENTION_REASONS = setOf(
            ResearchInterventionReason.REVISION_FAILED,
            ResearchInterventionReason.SEARCH_ATTEMPT_FAILED,
            ResearchInterventionReason.VERIFIER_TIMEOUT,
            ResearchInterventionReason.VERIFIER_UNAVAILABLE,
            ResearchInterventionReason.VERIFIER_RESULT_INCOMPLETE,
            ResearchInterventionReason.STALE_DRAFT,
            ResearchInterventionReason.INTERVENTION_TIMEOUT,
        )
    }
}

/**
 * Exact, request-scoped search authorization for the intervention path.
 *
 * The permit is minted only when the user already enabled web search for this
 * exact request and no private local attachment participates in the request.
 */
internal object ResearchInterventionSearchFactory {
    fun authorizer(
        request: GenerationRequestSnapshot,
        localSelections: List<LocalRagSelection>,
        evaluationEnabled: Boolean,
    ): ResearchInterventionSearchAuthorizer? {
        if (!evaluationEnabled) return null
        if (request.interactionMode != com.projectnuke.fusion.chat.ChatInteractionMode.RESEARCH) return null
        if (request.webSearchPolicy != GenerationRequestSnapshot.WebSearchPolicy.ENABLED) return null
        // Claim-derived queries may only use the exact user's explicit query
        // material. Reject inherited conversational, attachment, project, or
        // selection context even when web search is enabled for this request.
        if (request.rawUserText.isBlank()) return null
        if (request.history.isNotEmpty() || request.historyItems.isNotEmpty() ||
            request.safeWebSearchPriorUserTurns.isNotEmpty() || request.historyMessageIds.isNotEmpty()
        ) return null
        if (request.attachmentIds.isNotEmpty() || request.multimodalImagePaths.isNotEmpty() ||
            request.localRag?.chunkKeys?.isNotEmpty() == true || request.projectContext != null ||
            request.sourceScope.kind != com.projectnuke.fusion.chat.ProjectSourceScopeKind.AVAILABLE ||
            request.selectionContext != null || request.promptLabInstruction.isNotBlank()
        ) return null
        if (localSelections.isNotEmpty()) return null
        return ResearchInterventionSearchAuthorizer { context: ResearchInterventionSearchAuthorizationContext ->
            val userTokens = webQueryTokens(request.rawUserText)
            val queryTokens = context.queries.flatMap { webQueryTokens(it.query) }.toSet()
            if (context.conversationId != request.conversationId ||
                context.requestId != request.requestId ||
                queryTokens.isEmpty() ||
                !userTokens.containsAll(queryTokens)
            ) {
                null
            } else {
                ResearchInterventionSearchPermit.grant(
                    conversationId = context.conversationId,
                    requestId = context.requestId,
                    draftFingerprint = context.draftFingerprint,
                    queries = context.queries,
                    explicitConsent = true,
                    forbiddenPrivateExcerpts = DataEgressPrivateTextLedger.currentTexts(),
                    forbiddenFileNames = context.localFileNames,
                )
            }
        }
    }

    fun retriever(
        providerRepository: WebSearchProviderRepository?,
        requestToken: String?,
        authorizationStillValid: () -> Boolean = { true },
    ): ResearchInterventionRetriever = ResearchInterventionRetriever { queries, permit, owns ->
        if (!owns() || !authorizationStillValid()) return@ResearchInterventionRetriever ResearchInterventionRetrievalResult.Denied
        val collected = ArrayList<WebSearchSource>()
        for (query in queries) {
            if (!owns() || !authorizationStillValid()) return@ResearchInterventionRetriever ResearchInterventionRetrievalResult.Denied
            if (!permit.authorizes(query.claimId, query.query)) {
                return@ResearchInterventionRetriever ResearchInterventionRetrievalResult.Denied
            }
            val response = FusionWebSearch.searchExactApprovedQuery(
                query = query.query,
                providerRepository = providerRepository,
                requestToken = requestToken,
                isStillAuthorized = {
                    owns() && authorizationStillValid() && permit.authorizes(query.claimId, query.query)
                },
            )
            if (!owns() || !authorizationStillValid() || !permit.authorizes(query.claimId, query.query)) {
                return@ResearchInterventionRetriever ResearchInterventionRetrievalResult.Denied
            }
            collected.addAll(
                response.sources.take(EvidenceVerificationLimits.MAX_SOURCES_PER_SEARCH_RESULT),
            )
        }
        if (collected.isEmpty()) {
            ResearchInterventionRetrievalResult.Empty
        } else {
            ResearchInterventionRetrievalResult.Success(collected)
        }
    }

    private fun webQueryTokens(text: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+")
            .findAll(text.lowercase())
            .map { it.value }
            .toSet()
}

/**
 * Builds the durable, non-sensitive audit record for one persisted answer.
 * The answer hash binds the record to the exact visible answer the user sees.
 */
internal fun buildResearchInterventionAuditRecord(
    audit: ResearchInterventionAudit,
    messageId: Long,
    storedAssistantContent: String,
    generationModeKey: String?,
    savedAt: Long,
): ResearchInterventionAuditRecord = ResearchInterventionAuditRecord(
    messageId = messageId,
    conversationId = audit.conversationId,
    answerSha256 = EvidenceFingerprint.canonicalAnswerHash(
        assistantVisibleAnswerForHandoff(
            content = storedAssistantContent,
            reasoningEnabled = true,
            generationModeKey = generationModeKey,
        ),
    ),
    savedAt = savedAt,
    action = audit.action,
    reason = audit.reason,
    wholeAnswerCertified = audit.wholeAnswerCertified,
    claimScopedOnly = audit.claimScopedOnly,
    revisionAttempted = audit.revisionAttempted,
    retrievalAttempted = audit.retrievalAttempted,
    findingSubject = audit.findingSubject,
    draftStage = audit.draftStage,
    claims = audit.claims.map { claim ->
        ResearchInterventionAuditClaimRecord(
            claimId = claim.claimId,
            claimText = claim.claimText,
            claimTextSha256 = claim.claimTextSha256,
            status = claim.status,
            evidence = claim.evidence.map { evidence ->
                ResearchInterventionAuditEvidenceRecord(
                    evidenceIdentity = evidence.evidenceIdentity,
                    title = evidence.title,
                    url = evidence.url,
                    providerDisplayName = evidence.providerDisplayName,
                )
            },
        )
    },
)
