package com.projectnuke.fusion.search

/** Answer-generation admission derived only from semantically usable search evidence. */
internal data class SearchAnswerEvidenceAdmission(
    val actualWebSearchUsed: Boolean,
    val structuredContext: String?,
    /** Sources that correspond exactly to records initially supplied to answer generation. */
    val sources: List<WebSearchSource>,
    val evidenceStatus: SearchEvidenceStatus,
    val retrievalUsable: Boolean = false,
    val answerSufficiency: SearchAnswerSufficiencyStatus = SearchAnswerSufficiencyStatus.NOT_EVALUATED,
    /** Full retrieved provenance, retained separately from answer attribution. */
    val retrievedSources: List<WebSearchSource> = emptyList(),
    /** Host quality recalculated for each complete prefix that final fitting may retain. */
    val qualityStatusByModelResultCount: Map<Int, SearchEvidenceStatus> = emptyMap(),
) {
    companion object {
        val NONE = SearchAnswerEvidenceAdmission(
            actualWebSearchUsed = false,
            structuredContext = null,
            sources = emptyList(),
            evidenceStatus = SearchEvidenceStatus.LIMITED,
            retrievalUsable = false,
            answerSufficiency = SearchAnswerSufficiencyStatus.NOT_EVALUATED,
            retrievedSources = emptyList(),
            qualityStatusByModelResultCount = emptyMap(),
        )
    }
}

internal enum class SearchEvidenceStatus { USABLE, LIMITED, ANSWER_INSUFFICIENT }

/**
 * Search evidence may survive recoverable agent failure or timeout. When the
 * admitted records cannot support the requested fact, retrieval provenance is
 * retained for diagnostics but answer context and attribution fail closed.
 */
internal fun SearchStrategyRouteResult?.answerEvidence(): SearchAnswerEvidenceAdmission {
    if (this == null || outcome in setOf(
            SearchRouteOutcome.CANCELLED,
            SearchRouteOutcome.SEARCH_DISABLED,
            SearchRouteOutcome.MANUAL_QUERY_MISSING,
            SearchRouteOutcome.MANUAL_QUERY_INVALID,
            SearchRouteOutcome.PRIVACY_DENIED,
            SearchRouteOutcome.PERMISSION_REVOKED,
            SearchRouteOutcome.OWNERSHIP_LOST,
        )
    ) return SearchAnswerEvidenceAdmission.NONE

    val accepted = response?.takeIf { it.hasSemanticSuccess() }
        ?: return SearchAnswerEvidenceAdmission.NONE
    val uniqueRetrievedResults = accepted.results
        .distinctBy(FusionSearchResult::sourceIdentity)
    val officialScopedResults = SearchAnswerSufficiencyPolicy.restrictToRequestedOfficialEvidence(
        query = accepted.query,
        results = uniqueRetrievedResults,
    )
    val modelResults = SearchAnswerSufficiencyPolicy.selectAnswerEvidence(
        query = accepted.query,
        intent = accepted.intent,
        results = officialScopedResults,
    )
        .filterNot { result -> modelExcerptDropsMeaningChangingContext(result) }
        .take(SearchAnswerEvidenceLimits.MODEL_MAX_RESULTS)
    val admittedQualityResults = modelResults.map { result ->
        result.copy(
            title = sanitizeExternalField(result.title, 160),
            source = sanitizeExternalField(result.source.orEmpty(), 100),
            publishedAt = sanitizeExternalField(result.publishedAt.orEmpty(), 48),
            snippet = sanitizeExternalField(result.snippet.orEmpty(), 320),
        )
    }
    val sourceByIdentity = linkedMapOf<SearchEvidenceIdentity, WebSearchSource>()
    accepted.sources.forEach { source -> sourceByIdentity.putIfAbsent(source.evidenceIdentity(), source) }
    val answerSources = modelResults.map { result ->
        sourceByIdentity[result.evidenceIdentity()] ?: WebSearchSource(
            title = result.title,
            source = result.source,
            url = result.url,
            snippet = result.snippet,
            publishedAt = result.publishedAt,
            providerType = result.providerType,
            providerDisplayName = result.providerDisplayName,
            searchCategory = result.searchCategory,
            queryUsed = result.queryUsed,
            rank = result.rank,
            score = result.score,
            qualityHints = result.qualityHints,
        )
    }.distinctBy { it.evidenceIdentity() }
    val qualityByCount = (1..modelResults.size).associateWith { count ->
        val selectedPromptQuality = FusionWebSearch.evaluateResultQualityForTest(
            results = admittedQualityResults.take(count),
            query = accepted.query,
            intent = accepted.intent,
        )
        val selectedRecordQuality = FusionWebSearch.evaluateResultQualityForTest(
            results = modelResults.take(count),
            query = accepted.query,
            intent = accepted.intent,
        )
        when {
            !selectedPromptQuality.answerSufficient || !selectedRecordQuality.answerSufficient ->
                SearchEvidenceStatus.ANSWER_INSUFFICIENT
            !selectedPromptQuality.retrievalUsable -> SearchEvidenceStatus.LIMITED
            else -> SearchEvidenceStatus.USABLE
        }
    }
    val promptQuality = FusionWebSearch.evaluateResultQualityForTest(
        results = admittedQualityResults,
        query = accepted.query,
        intent = accepted.intent,
    )
    val fullRecordQuality = FusionWebSearch.evaluateResultQualityForTest(
        results = modelResults,
        query = accepted.query,
        intent = accepted.intent,
    )
    // Truncation can remove counter-context (for example, a prelaunch clause)
    // while leaving a misleading status verb in the prompt-facing excerpt.
    // Require both the full selected record and its exact model-facing excerpt
    // to support the requested fact before generation or attribution.
    val fullModelQuality = if (!fullRecordQuality.answerSufficient || !promptQuality.answerSufficient) {
        promptQuality.copy(
            answerSufficiency = SearchAnswerSufficiencyStatus.INSUFFICIENT,
            shouldTryFallback = true,
        )
    } else {
        promptQuality
    }
    val evidenceStatus = when {
        !fullModelQuality.answerSufficient -> SearchEvidenceStatus.ANSWER_INSUFFICIENT
        !fullModelQuality.retrievalUsable -> SearchEvidenceStatus.LIMITED
        else -> SearchEvidenceStatus.USABLE
    }
    return SearchAnswerEvidenceAdmission(
        actualWebSearchUsed = true,
        structuredContext = accepted.copy(results = modelResults, quality = fullModelQuality)
            .toAnswerContext()
            .takeIf { fullModelQuality.answerSufficient },
        sources = answerSources.takeIf { fullModelQuality.answerSufficient }.orEmpty(),
        evidenceStatus = evidenceStatus,
        retrievalUsable = fullModelQuality.retrievalUsable,
        answerSufficiency = fullModelQuality.answerSufficiency,
        retrievedSources = accepted.sources.toList(),
        qualityStatusByModelResultCount = qualityByCount,
    )
}

internal object SearchAnswerEvidenceLimits {
    const val MODEL_MAX_RESULTS = 4
}

private const val MODEL_TITLE_CHARS = 160
private const val MODEL_SNIPPET_CHARS = 320

private fun modelExcerptDropsMeaningChangingContext(result: FusionSearchResult): Boolean {
    val snippet = result.snippet.orEmpty()
    val fullSnippet = sanitizeExternalField(snippet, 10_000)
    val promptSnippet = sanitizeExternalField(snippet, MODEL_SNIPPET_CHARS)
    if (SearchEvidenceClipSafety.dropsMeaningChangingContext(fullSnippet, promptSnippet)) return true
    val fullTitle = sanitizeExternalField(result.title, 10_000)
    val promptTitle = sanitizeExternalField(result.title, MODEL_TITLE_CHARS)
    return SearchEvidenceClipSafety.dropsMeaningChangingContext(fullTitle, promptTitle)
}
