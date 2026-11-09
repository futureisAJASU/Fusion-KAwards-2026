package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.ChatPromptComposition
import com.projectnuke.fusion.retrieval.LocalRagSelection
import com.projectnuke.fusion.search.SearchAnswerEvidenceAdmission
import com.projectnuke.fusion.search.SearchAnswerTemporalGroundingPolicy
import com.projectnuke.fusion.search.SearchEvidenceStatus
import com.projectnuke.fusion.search.SearchStrategyRouteResult
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.search.answerEvidence
import com.projectnuke.fusion.search.appendRetrievedSearchResultsMetadata
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.util.AttachmentRecord

/**
 * The production consumption boundary for admitted web-search evidence.
 *
 * One instance owns every value a single request derives from its search
 * route: the evidence admitted to the final model context, the
 * `actualWebSearchUsed` fact, the persisted web-source metadata, and the
 * research-intervention inputs. The route decision, the generation prompt,
 * the persisted row, and the intervention can therefore never disagree about
 * which evidence was admitted.
 *
 * Only semantically usable evidence from a non-stale route is considered.
 * Result records that fail deterministic answer-sufficiency checks are kept as
 * retrieval provenance, but never reach answer generation or source attribution.
 */
internal class SearchEvidenceConsumption private constructor(
    val admission: SearchAnswerEvidenceAdmission,
    private val requestedQuery: String?,
) {
    private var finalModelSources: List<WebSearchSource> = admission.sources
    private var finalModelEvidenceStatus: SearchEvidenceStatus = admission.evidenceStatus
    /** Whether this request actually used web search. */
    val actualWebSearchUsed: Boolean get() = admission.actualWebSearchUsed
    val retrievalUsable: Boolean get() = admission.retrievalUsable
    val answerSufficiency: com.projectnuke.fusion.search.SearchAnswerSufficiencyStatus get() = admission.answerSufficiency
    /** A deterministic user-visible outcome when search cannot support the requested fact. */
    val deterministicAbstentionAnswer: String?
        get() = if (answerSufficiency == com.projectnuke.fusion.search.SearchAnswerSufficiencyStatus.INSUFFICIENT) {
            "검색 결과만으로는 요청한 사실을 확인하지 못했어요. 정확하지 않은 정보를 추측해 답하지 않겠습니다."
        } else {
            null
        }
    /** True only while the exact model-facing evidence can answer the requested current fact. */
    val usableForTrustSearch: Boolean
        get() = actualWebSearchUsed &&
            answerSufficiency != com.projectnuke.fusion.search.SearchAnswerSufficiencyStatus.INSUFFICIENT &&
            finalModelEvidenceStatus != SearchEvidenceStatus.ANSWER_INSUFFICIENT

    /** The structured search context handed to the final model prompt. */
    val structuredContext: String? get() = admission.structuredContext

    /** Sources corresponding to complete result records retained in the final prompt. */
    val sources: List<WebSearchSource> get() = finalModelSources
    /** Full search retrieval metadata, distinct from answer-attributed sources. */
    val retrievedSources: List<WebSearchSource> get() = admission.retrievedSources
    val evidenceStatus: SearchEvidenceStatus get() = finalModelEvidenceStatus

    /** Aligns persistence, inspector, share, and intervention sources to final local fitting. */
    fun retainFinalModelEvidence(resultCount: Int?) {
        if (resultCount == null) return
        finalModelSources = admission.sources.take(resultCount.coerceAtLeast(0))
        finalModelEvidenceStatus = admission.qualityStatusByModelResultCount[resultCount]
            ?: if (resultCount == 0) SearchEvidenceStatus.LIMITED else admission.evidenceStatus
    }

    /** The exact user content the final model prompt receives. */
    fun finalUserContent(body: String, attachments: List<AttachmentRecord>): String =
        ChatPromptComposition.finalUserContent(
            body = body,
            attachments = attachments,
            webSearchEnabled = actualWebSearchUsed,
            webSearchResult = structuredContext,
            evidenceStatus = evidenceStatus.name,
        )

    /** The exact system prompt the final model prompt receives. */
    fun systemPrompt(
        reasoningEnabled: Boolean,
        promptLabInstruction: String? = null,
        reasoningBudgetTokens: Int = 512,
        interactionMode: ChatInteractionMode = ChatInteractionMode.NORMAL,
    ): String = buildFusionSystemPrompt(
        reasoningEnabled = reasoningEnabled,
        webSearchEnabled = actualWebSearchUsed,
        // External evidence has one low-authority owner in the final user message.
        webContext = null,
        promptLabInstruction = promptLabInstruction,
        reasoningBudgetTokens = reasoningBudgetTokens,
        interactionMode = interactionMode,
    )

    /** The exact persisted assistant content, including web-source metadata. */
    fun persistedAssistantContent(
        answer: String,
        trustRoutingDecision: TrustRoutingDecision,
        ragSelections: List<LocalRagSelection>? = null,
        contextReceipt: AnswerContextReceipt? = null,
    ): String {
        val attributed = composePersistedAssistantContent(
            answer = SearchAnswerTemporalGroundingPolicy.sanitize(
                answer = answer,
                admittedSources = finalModelSources,
                requestedQuery = requestedQuery,
            ),
            trustRoutingDecision = trustRoutingDecision,
            webSources = sources,
            localRagSelections = ragSelections,
            contextReceipt = contextReceipt,
        )
        return appendRetrievedSearchResultsMetadata(attributed, retrievedSources)
    }

    /** Failed search answers retain retrieved provenance without attributing it as support. */
    fun persistedSearchFailureContent(
        answer: String,
        trustRoutingDecision: TrustRoutingDecision,
        contextReceipt: AnswerContextReceipt? = null,
    ): String = persistedAssistantContent(
        answer = answer,
        trustRoutingDecision = trustRoutingDecision,
        contextReceipt = contextReceipt,
    )

    /** The exact web sources the research intervention receives. */
    fun interventionWebSources(): List<WebSearchSource> = sources

    companion object {
        /** Builds the consumption boundary from one search route result. */
        fun from(
            route: SearchStrategyRouteResult?,
            requestedQuery: String? = null,
        ): SearchEvidenceConsumption =
            SearchEvidenceConsumption(route.answerEvidence(), requestedQuery)
    }
}
