package com.projectnuke.fusion.search

import com.projectnuke.fusion.privacy.DataEgressPolicy
import com.projectnuke.fusion.privacy.DataEgressPrivateTextLedger
import java.util.Collections
import java.util.Locale

/** Immutable privacy and authorization facts captured for one generation request. */
class AgenticSearchPrivacySnapshot(
    val requestId: String,
    val typedUserQuery: String,
    forbiddenPrivateExcerpts: List<String> = emptyList(),
    privateFileNames: List<String> = emptyList(),
    approvedLegacyQueries: List<String> = emptyList(),
    val explicitManualQuery: String? = null,
    val hasPrivateHistory: Boolean = false,
    val hasAttachments: Boolean = false,
    val hasLocalRagExcerpts: Boolean = false,
    val hasProjectContext: Boolean = false,
    val hasSelectionContext: Boolean = false,
    approvedPriorUserTurns: List<String> = emptyList(),
    private val requestStillAuthorized: () -> Boolean = { false },
) {
    val forbiddenPrivateExcerpts: List<String> = Collections.unmodifiableList(forbiddenPrivateExcerpts.toList())
    val privateFileNames: List<String> = Collections.unmodifiableList(privateFileNames.toList())
    val approvedLegacyQueries: List<String> = Collections.unmodifiableList(approvedLegacyQueries.toList())

    internal fun isStillAuthorized(): Boolean = requestStillAuthorized()
    internal fun hasPrivateTaint(): Boolean = hasPrivateHistory || hasAttachments || hasLocalRagExcerpts || hasProjectContext || hasSelectionContext

    /**
     * Bounded, privacy-approved prior user turns of this conversation. Pure
     * public planning context: it widens model context only and never
     * contributes authority for an outbound query term.
     */
    val approvedPriorUserTurns: List<String> = Collections.unmodifiableList(approvedPriorUserTurns.toList())
}

/**
 * Authorization for claim-like pre-answer planning. The exact typed query is
 * the authority anchor. New words are accepted only from:
 *  - a previously approved deterministic legacy plan,
 *  - a deterministic, inspectable trusted public entity table, or
 *  - a deterministic Korean/English public terminology table.
 *
 * Private and request-local context never contributes authority. Model output
 * itself is never a source of new terms: only the typed query's own public
 * surfaces widen the permitted additions, so a model can never authorize its
 * own expansion, and no confidence signal is consulted anywhere in this file.
 */
class RequestScopedAgenticSearchQueryAuthorizer(
    private val snapshot: AgenticSearchPrivacySnapshot,
) : AgenticSearchQueryAuthorizer {
    override fun authorize(originalUserQuery: String, proposedQuery: String): AgenticQueryAuthorization {
        if (snapshot.requestId.isBlank() || snapshot.typedUserQuery.isBlank()) {
            return AgenticQueryAuthorization.Denied("invalid_snapshot")
        }
        if (!snapshot.isStillAuthorized()) return AgenticQueryAuthorization.Denied("authorization_revoked")
        val explicitManualQuery = snapshot.explicitManualQuery?.takeIf {
            originalUserQuery == it && proposedQuery == it
        }
        if (explicitManualQuery == null && originalUserQuery != snapshot.typedUserQuery) {
            return AgenticQueryAuthorization.Denied("request_query_mismatch")
        }
        if (proposedQuery.isBlank() || proposedQuery.length > SearchHarnessProtocol.MAX_QUERY_CHARS ||
            proposedQuery.any(Char::isISOControl)
        ) return AgenticQueryAuthorization.Denied("invalid_query")

        val privateTexts = snapshot.forbiddenPrivateExcerpts + DataEgressPrivateTextLedger.currentTexts()
        if (DataEgressPolicy.webQueryContainsPrivate(proposedQuery, privateTexts, snapshot.privateFileNames)) {
            return AgenticQueryAuthorization.Denied(
                reasonCode = "private_or_sensitive_text",
                materialKind = AgenticQueryMaterialKind.PRIVATE_OR_TAINTED,
            )
        }
        if (explicitManualQuery != null) {
            return AgenticQueryAuthorization.Authorized(
                exactQuery = explicitManualQuery,
                materialKind = AgenticQueryMaterialKind.EXPLICIT_TYPED,
            )
        }

        val typedTokens = queryTokens(snapshot.typedUserQuery)
        val candidateTokens = queryTokens(proposedQuery)
        if (typedTokens.isEmpty() || candidateTokens.isEmpty()) return AgenticQueryAuthorization.Denied("query_without_terms")
        val addedTokens = candidateTokens - typedTokens
        val normalizedOriginal = normalizeTypedQuery(snapshot.typedUserQuery)
        val normalizedCandidate = normalizeTypedQuery(proposedQuery)
        val deterministicLegacyQueries = snapshot.approvedLegacyQueries.toSet() + RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = snapshot.typedUserQuery),
        ).let { plan -> setOf(plan.primaryQuery) + plan.alternateQueries }
        if (proposedQuery in deterministicLegacyQueries) {
            return AgenticQueryAuthorization.Authorized(
                exactQuery = proposedQuery,
                materialKind = AgenticQueryMaterialKind.DETERMINISTIC_NORMALIZATION,
            )
        }
        if (addedTokens.isEmpty()) {
            return AgenticQueryAuthorization.Authorized(
                exactQuery = proposedQuery,
                materialKind = if (normalizedCandidate == normalizedOriginal) {
                    AgenticQueryMaterialKind.EXPLICIT_TYPED
                } else {
                    AgenticQueryMaterialKind.DETERMINISTIC_NORMALIZATION
                },
            )
        }

        val permittedAdditions = trustedPublicAdditionTerms(snapshot.typedUserQuery, snapshot.hasPrivateTaint())
        if (addedTokens.all(permittedAdditions::contains) && candidateTokens.any(typedTokens::contains)) {
            return AgenticQueryAuthorization.Authorized(
                exactQuery = proposedQuery,
                materialKind = AgenticQueryMaterialKind.TRUSTED_PUBLIC_ENRICHMENT,
            )
        }
        return AgenticQueryAuthorization.Denied(
            reasonCode = "unverified_query_expansion",
            materialKind = AgenticQueryMaterialKind.UNTRUSTED_MODEL_INFERRED,
        )
    }

    /**
     * Every public term this request may add, derived only from the typed
     * request's own public surfaces. Sources:
     *  - the stock-ticker lexicon (issuer-published identifier and exchange),
     *  - the Korean/English public terminology groups, and
     *  - a small stock-query vocabulary, which is dropped when any private
     *    taint is present.
     */
    private fun trustedPublicAdditionTerms(typedQuery: String, privateTainted: Boolean): Set<String> {
        val permitted = LinkedHashSet<String>()
        if (!TrustedPublicEntityLexicon.isAmbiguousEntityRequest(typedQuery)) {
            permitted += TrustedPublicEntityLexicon.termsFor(typedQuery)
            permitted += trustedPublicStockEnrichmentTerms(typedQuery)
        }
        permitted += TrustedPublicTerminology.termsFor(typedQuery)
        if (!privateTainted && hasStockIntentSignal(typedQuery)) {
            permitted += SafeStockSearchModifiers
        }
        return permitted
    }

    private fun queryTokens(text: String): Set<String> = publicQueryTokens(text)

    private fun normalizeTypedQuery(text: String): String = text.trim()
        .lowercase(Locale.ROOT)
        .replace(WhitespacePattern, " ")

    companion object {
        private val WhitespacePattern = Regex("\\s+")
        private val SafeStockSearchModifiers = setOf(
            "current", "now", "today", "latest", "price", "stock", "share", "close", "market", "cap", "index", "level",
            "현재", "현재가", "지금", "오늘", "최신", "주가", "주식", "시세", "종가", "시가총액", "지수", "수준",
        )
    }
}
