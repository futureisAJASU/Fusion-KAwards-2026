package com.projectnuke.fusion.privacy

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

// C5 privacy / data-egress routing.
//
// Information-source routing and data-egress permission are separate
// decisions. The Trust Router chooses what capability is useful; privacy
// policy decides what data may leave the device. Attachment bytes and local
// RAG excerpts are private local content by default.

data class RequestPrivacyContext(
    val hasAttachments: Boolean = false,
    val attachmentCount: Int = 0,
    val hasLocalRagExcerpts: Boolean = false,
    val localExcerptChars: Int = 0,
    val fileNames: List<String> = emptyList(),
    val userText: String = "",
)

enum class DataEgressScope {
    NONE,
    USER_QUERY_ONLY,
    USER_QUERY_AND_HISTORY,
}

enum class DataEgressDestination {
    WEB_SEARCH,
    EXTERNAL_AI,
    CLOUD_ESCALATION,
}

data class EgressConsent(
    val consentId: String,
    val requestId: String,
    val providerId: String?,
    val payloadHashHex: String,
    val includesPrivate: Boolean,
    val scope: DataEgressScope,
)

/** Exact owner identity for one user-triggered post-answer verification run. */
data class PostAnswerVerificationSearchIdentity(
    val conversationId: Long,
    val assistantMessageId: Long,
    val answerSha256: String,
    /** The message id identifies the selected response variant in this schema. */
    val responseVersionId: Long,
)

data class AuthorizedTargetedQuery(
    val claimId: String,
    val query: String,
)

/**
 * Immutable user authorization for a bounded set of assistant-derived queries.
 * Its private execution latch makes the frozen authorization one-shot; query
 * membership is checked again immediately before each outbound search.
 */
class PostAnswerVerificationSearchAuthorization private constructor(
    val operationId: String,
    val identity: PostAnswerVerificationSearchIdentity,
    queries: List<AuthorizedTargetedQuery>,
) {
    val queries: List<AuthorizedTargetedQuery> = Collections.unmodifiableList(queries.map { it.copy() })
    val webSearchAuthorized: Boolean = true
    private val executionClaimed = AtomicBoolean(false)

    internal fun claimExecution(
        expectedOperationId: String,
        expectedIdentity: PostAnswerVerificationSearchIdentity,
    ): Boolean =
        webSearchAuthorized &&
            operationId == expectedOperationId &&
            identity == expectedIdentity &&
            executionClaimed.compareAndSet(false, true)

    internal fun matches(
        expectedOperationId: String,
        expectedIdentity: PostAnswerVerificationSearchIdentity,
    ): Boolean =
        webSearchAuthorized && operationId == expectedOperationId && identity == expectedIdentity

    internal fun wasClaimedFor(
        expectedOperationId: String,
        expectedIdentity: PostAnswerVerificationSearchIdentity,
    ): Boolean = executionClaimed.get() && matches(expectedOperationId, expectedIdentity)

    companion object {
        internal const val MAX_QUERIES = 3
        internal const val MAX_QUERY_CHARS = 240

        /** Called only after the user chooses the explicit web-search action. */
        internal fun afterExplicitConfirmation(
            operationId: String,
            identity: PostAnswerVerificationSearchIdentity,
            queries: List<AuthorizedTargetedQuery>,
        ): PostAnswerVerificationSearchAuthorization? {
            if (operationId.isBlank() || operationId.length > 128) return null
            if (identity.conversationId <= 0L || identity.assistantMessageId <= 0L ||
                identity.responseVersionId <= 0L || !identity.answerSha256.matches(Regex("[0-9a-f]{64}"))
            ) return null
            if (queries.isEmpty() || queries.size > MAX_QUERIES || queries.any {
                    it.claimId.isBlank() || it.claimId.length > 64 ||
                        it.query.isBlank() || it.query.length > MAX_QUERY_CHARS
                }
            ) return null
            if (queries.map { it.claimId }.distinct().size != queries.size) return null
            return PostAnswerVerificationSearchAuthorization(operationId, identity, queries)
        }
    }
}

// Frozen egress authorization for one permitted external action. A consent
// for one payload must not authorize a later mutated payload.
data class DataEgressSnapshot(
    val requestId: String,
    val destination: DataEgressDestination,
    val providerId: String?,
    val egressScope: DataEgressScope,
    val payloadHashHex: String,
    val includesPrivate: Boolean,
    val consentId: String,
)

object DataEgressBounds {
    const val MAX_SNAPSHOT_TEXT_CHARS = 8_000
    const val MAX_CONSENTS_PER_REQUEST = 1
    const val MAX_FILE_NAMES_PER_SNAPSHOT = 8
}

class DataEgressGate {
    private val held = java.util.concurrent.atomic.AtomicBoolean(false)
    fun tryEnter(): Boolean = held.compareAndSet(false, true)
    fun release() { held.set(false) }
}

// Bounded process-visible registry of private attachment/RAG text so the
// production logging path can redact those exact excerpts if they ever
// appear in diagnostics. Host tests can register fakes directly.
object DataEgressPrivateTextLedger {
    private const val MAX_REGISTERED_TEXTS = 64
    private const val MAX_TOTAL_CHARS = 65_536

    private val lock = Any()
    private val texts = LinkedHashSet<String>()
    private var hasPrivateMaterial = false
    private var generation = 0L

    internal data class Snapshot(val texts: List<String>, val hasPrivateMaterial: Boolean, val generation: Long)

    internal fun snapshot(): Snapshot = synchronized(lock) { Snapshot(texts.toList(), hasPrivateMaterial, generation) }

    fun register(candidates: Collection<String>) {
        synchronized(lock) {
            if (candidates.any { it.isNotBlank() }) {
                // Even rejected or oversized entries carry private provenance.
                hasPrivateMaterial = true
                generation++
            }
            var totalChars = texts.sumOf { it.length }
            for (candidate in candidates) {
                if (candidate.isBlank()) continue
                if (texts.size >= MAX_REGISTERED_TEXTS) break
                if (candidate.length > MAX_TOTAL_CHARS ||
                    totalChars > MAX_TOTAL_CHARS - candidate.length
                ) continue
                if (texts.add(candidate)) totalChars += candidate.length
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            texts.clear()
            hasPrivateMaterial = false
            generation++
        }
    }

    fun currentTexts(): List<String> = synchronized(lock) { texts.toList() }
}

object DataEgressPolicy {
    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun payloadHash(userText: String, historyFingerprint: String = ""): String =
        sha256Hex(userText + "\n" + historyFingerprint)

    // Web search may use the explicitly typed user question, but must never
    // silently append document excerpts, attachment text, RAG chunks, or
    // private file names. Enforced at the transport boundary: a query that is
    // not one of the planner-approved user-query strings never reaches a
    // provider request.
    fun buildWebQuery(userQuery: String, @Suppress("UNUSED_PARAMETER") context: RequestPrivacyContext): String =
        userQuery

    fun isApprovedWebQuery(query: String, approvedQueries: Collection<String>): Boolean {
        if (query.isBlank()) return false
        if (approvedQueries.isEmpty()) return false
        return approvedQueries.any { it.contentEquals(query) }
    }

    /**
     * Dedicated post-answer egress contract. Assistant-derived queries are
     * accepted only with the one-shot authorization for this exact operation,
     * answer identity, claim id and query string. Typed user-query behavior is
     * intentionally unchanged.
     */
    fun isAuthorizedPostAnswerVerificationQuery(
        authorization: PostAnswerVerificationSearchAuthorization?,
        operationId: String,
        identity: PostAnswerVerificationSearchIdentity,
        claimId: String,
        query: String,
    ): Boolean {
        if (authorization == null || !authorization.wasClaimedFor(operationId, identity)) return false
        return authorization.queries.any { approved ->
            approved.claimId == claimId && approved.query.contentEquals(query)
        }
    }

    fun webQueryContainsPrivate(webQuery: String, excerpts: List<String>, fileNames: List<String>): Boolean {
        if (webQuery.isBlank()) return true
        val normalizedQuery = normalizePrivateTextForMatch(webQuery)
        val containsKnownText = excerpts.any { excerpt ->
            excerpt.isNotBlank() && excerpt.length >= 3 &&
                normalizedQuery.contains(normalizePrivateTextForMatch(excerpt))
        } || fileNames.any { fileName ->
            fileName.isNotBlank() && normalizedQuery.contains(normalizePrivateTextForMatch(fileName))
        }
        if (containsKnownText) return true
        // Detect compatibility and formatting obfuscation without modifying the
        // exact query that a caller may later authorize for transport.
        return SensitiveWebQueryPatterns.any {
            it.containsMatchIn(webQuery) || it.containsMatchIn(normalizedQuery)
        }
    }

    private fun normalizePrivateTextForMatch(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .filterNot { Character.getType(it) == Character.FORMAT.toInt() }
            .lowercase(Locale.ROOT)
            .replace(PrivateTextWhitespace, " ")
            .trim()

    private val PrivateTextWhitespace = Regex("\\s+")

    private val SensitiveWebQueryPatterns = listOf(
        Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE),
        Regex("(?<!\\d)(?:01[016789][ -]?)\\d{3,4}[ -]?\\d{4}(?!\\d)"),
        Regex("(?<!\\d)\\d{6}[- ]?[1-4]\\d{6}(?!\\d)"),
        Regex("(?i)(?:[A-Z]:\\\\|/)(?:[^\\s<>:\"|?*]+[\\\\/])*[^\\s<>:\"|?*]+"),
    )

    // External AI + private context requires explicit one-request consent.
    // Before valid consent the transport must carry nothing (0 calls).
    fun externalAiAllowed(context: RequestPrivacyContext, consent: EgressConsent?, currentPayloadHash: String, currentRequestId: String, currentProviderId: String?): Boolean {
        if (!context.hasAttachments && !context.hasLocalRagExcerpts) {
            // Ordinary text still needs no private consent, but cloud calls
            // themselves remain explicit user actions (never automatic).
            return consent != null && validateConsent(consent, currentRequestId, currentProviderId, currentPayloadHash)
        }
        if (consent == null) return false
        if (!consent.includesPrivate) return false
        return validateConsent(consent, currentRequestId, currentProviderId, currentPayloadHash)
    }

    // CLOUD_SUGGEST + private content stays blocked in C5 (conservative).
    // No well-tested per-request private-data egress path is claimed here.
    fun cloudEscalationAllowed(context: RequestPrivacyContext): Boolean =
        !context.hasAttachments && !context.hasLocalRagExcerpts

    fun makeConsent(
        consentId: String,
        requestId: String,
        providerId: String?,
        payloadHashHex: String,
        includesPrivate: Boolean,
        scope: DataEgressScope,
    ): EgressConsent = EgressConsent(consentId, requestId, providerId, payloadHashHex, includesPrivate, scope)

    // Payload or provider change invalidates consent; no blanket consent
    // survives into an unrelated request.
    fun validateConsent(
        consent: EgressConsent,
        currentRequestId: String,
        currentProviderId: String?,
        currentPayloadHash: String,
    ): Boolean {
        if (consent.requestId != currentRequestId) return false
        if (consent.providerId != currentProviderId) return false
        if (!consent.payloadHashHex.equals(currentPayloadHash, ignoreCase = true)) return false
        return true
    }

    fun snapshotFor(
        requestId: String,
        destination: DataEgressDestination,
        providerId: String?,
        scope: DataEgressScope,
        payloadHashHex: String,
        includesPrivate: Boolean,
        consentId: String,
    ): DataEgressSnapshot = DataEgressSnapshot(
        requestId = requestId,
        destination = destination,
        providerId = providerId,
        egressScope = scope,
        payloadHashHex = payloadHashHex,
        includesPrivate = includesPrivate,
        consentId = consentId,
    )

    // Diagnostics never log secrets or full private content. Hash/identity
    // metadata may be used where appropriate.
    fun sanitizeDiagnostics(raw: String, secrets: List<String> = emptyList(), privateTexts: List<String> = emptyList()): String {
        var out = raw
        for (secret in secrets) {
            if (secret.isNotBlank()) out = out.replace(secret, "[redacted-secret]")
        }
        out = out.replace(Regex("(?i)authorization\\s*:\\s*Bearer\\s+\\S+"), "Authorization: [redacted-secret]")
        out = out.replace(Regex("(?i)api[_-]?key\\s*[:=]\\s*\\S+"), "api_key=[redacted-secret]")
        for (private in privateTexts) {
            if (private.length >= 8) out = out.replace(private, "[redacted-private]")
        }
        return out
    }

    fun diagnosticsLeaksSecrets(sanitized: String, secrets: List<String>): Boolean =
        secrets.any { it.isNotBlank() && sanitized.contains(it) }

    fun diagnosticsLeaksPrivate(sanitized: String, privateTexts: List<String>): Boolean =
        privateTexts.any { it.length >= 8 && sanitized.contains(it) }

    // Trust route, egress policy, retrieval, translation, evidence, and
    // answer generation are distinct concepts that must not collapse.
    fun trustRouteSeparateFromEgress(): Boolean = true

    fun userFacingNotice(context: RequestPrivacyContext): String =
        if (context.hasAttachments || context.hasLocalRagExcerpts) {
            "로컬 첨부 내용은 기기에 그대로 둬요. 외부 전송이 필요하면 별도로 확인해요."
        } else {
            ""
        }
}
