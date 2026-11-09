package com.projectnuke.fusion.privacy

import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

internal data class ResearchInterventionSearchQuery(
    val claimId: String,
    val query: String,
)

/**
 * Exact, request-scoped authorization for bounded claim-derived retrieval.
 *
 * It is intentionally not derived from a global "web search enabled"
 * preference. The caller must mint it from an explicit decision for this exact
 * request, draft fingerprint, claim id and query string. The one-shot claim
 * latch means one intervention can never execute two retrieval attempts.
 *
 * Queries containing registered private excerpts or local file names are
 * rejected before the permit exists, so a claim-derived query cannot leak
 * private local content.
 */
internal class ResearchInterventionSearchPermit private constructor(
    val conversationId: Long,
    val requestId: String,
    val draftFingerprint: String,
    queries: List<ResearchInterventionSearchQuery>,
    forbiddenPrivateExcerpts: List<String>,
    forbiddenFileNames: List<String>,
) {
    val queries: List<ResearchInterventionSearchQuery> =
        Collections.unmodifiableList(queries.map { it.copy() })
    private val forbiddenPrivateExcerpts = Collections.unmodifiableList(forbiddenPrivateExcerpts.toList())
    private val forbiddenFileNames = Collections.unmodifiableList(forbiddenFileNames.toList())

    private val executionClaimed = AtomicBoolean(false)

    internal fun claimExecution(
        expectedConversationId: Long,
        expectedRequestId: String,
        expectedDraftFingerprint: String,
    ): Boolean =
        conversationId == expectedConversationId &&
            requestId == expectedRequestId &&
            draftFingerprint == expectedDraftFingerprint &&
            executionClaimed.compareAndSet(false, true)

    internal fun authorizes(claimId: String, query: String): Boolean =
        executionClaimed.get() &&
            claims(claimId, query) &&
            !DataEgressPolicy.webQueryContainsPrivate(
                webQuery = query,
                excerpts = forbiddenPrivateExcerpts + DataEgressPrivateTextLedger.currentTexts(),
                fileNames = forbiddenFileNames,
            )

    internal fun wasClaimedFor(
        expectedConversationId: Long,
        expectedRequestId: String,
        expectedDraftFingerprint: String,
    ): Boolean =
        executionClaimed.get() &&
            conversationId == expectedConversationId &&
            requestId == expectedRequestId &&
            draftFingerprint == expectedDraftFingerprint

    private fun claims(claimId: String, query: String): Boolean =
        queries.any { it.claimId == claimId && it.query.contentEquals(query) }

    companion object {
        const val MAX_QUERIES = 3
        const val MAX_QUERY_CHARS = 240
        const val MAX_CLAIM_ID_CHARS = 64

        fun grant(
            conversationId: Long,
            requestId: String,
            draftFingerprint: String,
            queries: List<ResearchInterventionSearchQuery>,
            explicitConsent: Boolean,
            forbiddenPrivateExcerpts: List<String> = emptyList(),
            forbiddenFileNames: List<String> = emptyList(),
        ): ResearchInterventionSearchPermit? {
            if (!explicitConsent) return null
            if (conversationId <= 0L || requestId.isBlank() || requestId.length > 128) return null
            if (!draftFingerprint.matches(Regex("[0-9a-f]{64}"))) return null
            if (queries.isEmpty() || queries.size > MAX_QUERIES) return null
            if (queries.any {
                    it.claimId.isBlank() || it.claimId.length > MAX_CLAIM_ID_CHARS ||
                        it.query.isBlank() || it.query.length > MAX_QUERY_CHARS
                }
            ) return null
            if (queries.map { it.claimId }.distinct().size != queries.size) return null
            if (queries.any { query ->
                    DataEgressPolicy.webQueryContainsPrivate(
                        webQuery = query.query,
                        excerpts = forbiddenPrivateExcerpts,
                        fileNames = forbiddenFileNames,
                    )
                }
            ) return null
            // De-duplicate exact query strings; the one-shot permit has no
            // reason to authorize the same outbound query twice.
            if (queries.map { it.query.lowercase() }.distinct().size != queries.size) return null
            return ResearchInterventionSearchPermit(
                conversationId = conversationId,
                requestId = requestId,
                draftFingerprint = draftFingerprint,
                queries = queries,
                forbiddenPrivateExcerpts = forbiddenPrivateExcerpts,
                forbiddenFileNames = forbiddenFileNames,
            )
        }

        internal fun sha256Fingerprint(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
