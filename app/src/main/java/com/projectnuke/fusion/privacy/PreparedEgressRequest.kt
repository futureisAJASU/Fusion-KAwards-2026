package com.projectnuke.fusion.privacy

import com.projectnuke.fusion.ai.model.AiChatRequest
import com.projectnuke.fusion.ai.model.ExternalRequestPatch
import com.projectnuke.fusion.ai.network.ExternalOutputTokenField
import com.projectnuke.fusion.ai.network.ExternalRequestCompatibility
import com.projectnuke.fusion.ai.network.ExternalTemperaturePolicy
import com.projectnuke.fusion.privacy.DataEgressPolicy.sha256Hex

// C5 EXACT egress authority: PreparedEgressRequest.
//
// Consent binds the EXACT canonical payload to be transmitted, and the
// payload descriptor can never be mismatched with the actual transport
// bytes: there is exactly ONE object, constructed FROM the final transport
// payload plus typed privacy provenance in a single factory call. A caller
// cannot describe payload A while passing transport request B, because the
// descriptor and the bytes travel together.
//
// Identity incorporates every transmitted component (never just user text):
// request ID, destination, provider, canonical transport hash, frozen
// user/history identities, private RAG excerpt hashes, attachment-derived
// text hashes, egress scope/version. Secrets are never part of the identity
// (only content hashes).
internal data class PreparedEgressRequest(
    val requestId: String,
    val destination: DataEgressDestination,
    val providerId: String,
    /** The FINAL transport payload whose bytes will leave the device. */
    val exactRequest: AiChatRequest,
    /** Canonical hash of the complete outbound transport identity. */
    val transportHashHex: String,
    val userTextHashHex: String,
    val historyHashHex: String,
    val privateExcerptHashes: List<String> = emptyList(),
    val attachmentTextHashes: List<String> = emptyList(),
    val egressScope: DataEgressScope = DataEgressScope.USER_QUERY_AND_HISTORY,
    val version: String = EGRESS_PAYLOAD_VERSION,
) {
    fun canonicalIdentity(): String = listOf(
        version,
        requestId,
        destination.name,
        providerId,
        transportHashHex,
        userTextHashHex,
        historyHashHex,
        privateExcerptHashes.joinToString(","),
        attachmentTextHashes.joinToString(","),
        egressScope.name,
    ).joinToString("|")

    // Exact payload identity used for consent binding.
    val payloadHashHex: String get() = sha256Hex(canonicalIdentity())

    val includesPrivateMaterial: Boolean
        get() = privateExcerptHashes.isNotEmpty() || attachmentTextHashes.isNotEmpty()

    companion object {
        const val EGRESS_PAYLOAD_VERSION = "exact-egress-v1"
    }
}

/** Typed, deterministic identity of every semantic field in the outbound body. */
internal data class ExternalTransportIdentity(
    val modelId: String,
    val messages: List<TransportMessageIdentity>,
    val tools: List<com.projectnuke.fusion.ai.model.AiToolDefinition>,
    val toolChoice: com.projectnuke.fusion.ai.model.AiToolChoice?,
    val temperature: Double?,
    val outputTokenField: ExternalOutputTokenField?,
    val maxTokens: Int?,
    val stream: Boolean,
    val reasoningEffortValue: String?,
    val reasoningEffortMapping: com.projectnuke.fusion.ai.model.ReasoningEffortMapping,
)

internal data class TransportMessageIdentity(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<com.projectnuke.fusion.ai.model.AiToolCall> = emptyList(),
)

private fun appendCanonicalField(out: StringBuilder, value: String?) {
    if (value == null) out.append("-1:") else out.append(value.length).append(':').append(value)
    out.append(';')
}

private fun ExternalTransportIdentity.canonicalBytes(): String = buildString {
    appendCanonicalField(this, modelId)
    append(messages.size).append(';')
    messages.forEach { message ->
        appendCanonicalField(this, message.role)
        appendCanonicalField(this, message.content)
    }
    // Preserve the pre-tool hash for every legacy plain-chat request. Native
    // protocol fields enter the identity only when they change the HTTP body.
    if (messages.any { it.toolCallId != null || it.toolCalls.isNotEmpty() }) {
        appendCanonicalField(this, "native-tool-messages-v1")
        messages.forEach { message ->
            appendCanonicalField(this, message.toolCallId)
            append(message.toolCalls.size).append(';')
            message.toolCalls.forEach { call ->
                appendCanonicalField(this, call.id)
                appendCanonicalField(this, call.name)
                appendCanonicalField(this, call.arguments)
            }
        }
    }
    if (tools.isNotEmpty()) {
        appendCanonicalField(this, "native-tool-definitions-v1")
        append(tools.size).append(';')
        tools.forEach { tool ->
            appendCanonicalField(this, tool.name)
            appendCanonicalField(this, tool.description)
            appendCanonicalField(this, tool.strict.toString())
            appendCanonicalField(this, tool.parameters.type)
            append(tool.parameters.additionalProperties).append(';')
            append(tool.parameters.required.size).append(';')
            tool.parameters.required.forEach { appendCanonicalField(this, it) }
            append(tool.parameters.properties.size).append(';')
            tool.parameters.properties.toSortedMap().forEach { (key, value) ->
                appendCanonicalField(this, key)
                appendCanonicalField(this, value.type)
                appendCanonicalField(this, value.description)
                appendCanonicalField(this, value.items?.type)
                appendCanonicalField(this, value.items?.description)
            }
        }
        appendCanonicalField(this, toolChoice?.name)
    }
    appendCanonicalField(this, temperature?.toString())
    appendCanonicalField(this, outputTokenField?.name)
    appendCanonicalField(this, maxTokens?.toString())
    appendCanonicalField(this, stream.toString())
    appendCanonicalField(this, reasoningEffortValue)
    // OpenAiCompatibleClient omits reasoning entirely when no value resolves.
    // Do not let the unused mapping distinguish two identical HTTP bodies.
    appendCanonicalField(this, reasoningEffortValue?.let { reasoningEffortMapping.name })
}

/** Hash of the exact typed transport identity; secrets never enter this value. */
internal fun canonicalTransportHash(
    modelId: String,
    request: AiChatRequest,
    patch: ExternalRequestPatch = ExternalRequestPatch(),
    stream: Boolean = false,
    compatibility: ExternalRequestCompatibility = ExternalRequestCompatibility.FALLBACK,
): String = sha256Hex(
    ExternalTransportIdentity(
        modelId = modelId,
        messages = request.messages.map {
            TransportMessageIdentity(it.role.name, it.content, it.toolCallId, it.toolCalls)
        },
        tools = request.tools,
        toolChoice = request.toolChoice,
        temperature = request.temperature.takeIf {
            compatibility.temperaturePolicy == ExternalTemperaturePolicy.EMIT
        },
        outputTokenField = request.maxTokens?.let { compatibility.outputTokenField },
        maxTokens = request.maxTokens,
        stream = stream,
        reasoningEffortValue = patch.customReasoningEffort ?: patch.reasoningEffort?.name?.lowercase(),
        reasoningEffortMapping = patch.reasoningEffortMapping,
    ).canonicalBytes(),
)

/**
 * The single construction authority: builds a [PreparedEgressRequest] from
 * the final transport payload and typed privacy provenance in one place.
 */
internal object PreparedEgressRequestFactory {
    fun prepare(
        requestId: String,
        destination: DataEgressDestination,
        provider: com.projectnuke.fusion.ai.model.AiProviderConfig,
        exactRequest: AiChatRequest,
        resolvedRequestPatch: ExternalRequestPatch = ExternalRequestPatch(),
        requestCompatibility: ExternalRequestCompatibility = ExternalRequestCompatibility.FALLBACK,
        transmittedUserText: String,
        transmittedHistoryText: String,
        privateExcerpts: List<String> = emptyList(),
        attachmentTexts: List<String> = emptyList(),
        scope: DataEgressScope = DataEgressScope.USER_QUERY_AND_HISTORY,
    ): PreparedEgressRequest = PreparedEgressRequest(
        requestId = requestId,
        destination = destination,
        providerId = provider.id,
        exactRequest = exactRequest,
        transportHashHex = canonicalTransportHash(
            provider.modelId,
            exactRequest,
            resolvedRequestPatch,
            compatibility = requestCompatibility,
        ),
        userTextHashHex = sha256Hex(transmittedUserText),
        historyHashHex = sha256Hex(transmittedHistoryText),
        privateExcerptHashes = privateExcerpts.map { sha256Hex(it) },
        attachmentTextHashes = attachmentTexts.map { sha256Hex(it) },
        egressScope = scope,
    )
}

internal class EgressGateChatClient(
    private val delegate: com.projectnuke.fusion.ai.network.ChatClient,
    private val controller: DataEgressController,
) : com.projectnuke.fusion.ai.network.ChatClient {

    override suspend fun chatCompletion(
        config: com.projectnuke.fusion.ai.model.AiProviderConfig,
        request: AiChatRequest,
    ): com.projectnuke.fusion.ai.model.AiChatResponse =
        chatCompletion(config, request, requestId = null)

    override suspend fun chatCompletion(
        config: com.projectnuke.fusion.ai.model.AiProviderConfig,
        request: AiChatRequest,
        requestId: String?,
    ): com.projectnuke.fusion.ai.model.AiChatResponse {
        when (val authorization = controller.authorize(config, request, requestId, null)) {
            is DataEgressController.Authorization.Granted ->
                return delegate.chatCompletion(config, request)
            is DataEgressController.Authorization.Refused -> {
                // 0 outbound calls. Bounded redaction keeps provider failure
                // copy safe for users and for diagnostics.
                val reason = controller.sanitizeTransportRefusal(authorization.reason)
                throw com.projectnuke.fusion.ai.network.AiProviderClientException(
                    reason,
                    egressAuthorizationDenied = true,
                )
            }
        }
    }

    override suspend fun chatCompletion(
        config: com.projectnuke.fusion.ai.model.AiProviderConfig,
        request: AiChatRequest,
        requestId: String?,
        providerRequestSnapshot: com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot?,
    ): com.projectnuke.fusion.ai.model.AiChatResponse {
        when (val authorization = controller.authorize(config, request, requestId, providerRequestSnapshot)) {
            is DataEgressController.Authorization.Granted ->
                return delegate.chatCompletion(config, request, requestId, providerRequestSnapshot)
            is DataEgressController.Authorization.Refused -> {
                val reason = controller.sanitizeTransportRefusal(authorization.reason)
                throw com.projectnuke.fusion.ai.network.AiProviderClientException(
                    reason,
                    egressAuthorizationDenied = true,
                )
            }
        }
    }
}

/**
 * Request-scoped egress authorization tickets. There is no global
 * single-slot expectation: concurrent external operations each hold their
 * own ticket keyed by (request, provider, transport hash), so no operation can
 * overwrite, consume, or impersonate another's authorization.
 *
 * - private payload without valid exact consent: 0 calls (Refused);
 * - valid one-request consent: exactly one approved call (consumed);
 * - payload mutation: refused until the caller re-consents with the exact
 *   mutated payload (same texts + same provider otherwise).
 */
class DataEgressController {
    sealed interface Authorization {
        data object Granted : Authorization
        data class Refused(val reason: String) : Authorization
    }

    private data class Ticket(
        val prepared: PreparedEgressRequest,
        val consent: EgressConsent?,
        val stillAuthorized: () -> Boolean,
    )

    private val lock = Any()
    private val tickets = LinkedHashMap<String, Ticket>()

    /**
     * Arms a request-scoped ticket for one exact outbound payload, and, when
     * private material is included, for one already-validated one-request
     * consent over the same exact payload identity.
     */
    internal fun arm(
        prepared: PreparedEgressRequest,
        consent: EgressConsent?,
        stillAuthorized: () -> Boolean = { true },
    ): Boolean {
        if (prepared.requestId.isBlank()) return false
        if (!runCatching(stillAuthorized).getOrDefault(false)) return false
        if (prepared.includesPrivateMaterial) {
            if (consent == null || !consent.includesPrivate ||
                !DataEgressPolicy.validateConsent(
                    consent = consent,
                    currentRequestId = prepared.requestId,
                    currentProviderId = prepared.providerId,
                    currentPayloadHash = prepared.payloadHashHex,
                )
            ) {
                disarm(prepared.requestId)
                return false
            }
        }
        return synchronized(lock) {
            val key = ticketKey(prepared.requestId, prepared.providerId, prepared.transportHashHex)
            if (!tickets.containsKey(key) && tickets.size >= MAX_TICKETS) {
                return@synchronized false
            }
            tickets[key] = Ticket(prepared, consent, stillAuthorized)
            true
        }
    }

    fun disarm() {
        synchronized(lock) { tickets.clear() }
    }

    /** Cancels only the exact request's pending expectation. */
    internal fun disarm(requestId: String) {
        if (requestId.isBlank()) return
        synchronized(lock) {
            tickets.entries.removeIf { it.value.prepared.requestId == requestId }
        }
    }

    /**
     * Transport-boundary decision. Grants exactly once per armed ticket and
     * consumes it; any mismatch — provider, payload, or missing ticket —
     * refuses and produces 0 calls. A ticket can only authorize its own
     * exact (request, provider, transport hash) identity, never another
     * request's. The request identity is carried through the runner to this
     * final transport boundary; a provider/hash-only lookup is insufficient.
     */
    fun authorize(
        config: com.projectnuke.fusion.ai.model.AiProviderConfig,
        request: AiChatRequest,
        requestId: String?,
        providerRequestSnapshot: com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot? = null,
    ): Authorization {
        if (requestId.isNullOrBlank()) return Authorization.Refused("missing egress request identity")
        val patch = providerRequestSnapshot?.resolvedRequestPatch ?: ExternalRequestPatch()
        val compatibility = providerRequestSnapshot?.requestCompatibility
            ?: ExternalRequestCompatibility.FALLBACK
        val key = ticketKey(
            requestId,
            config.id,
            canonicalTransportHash(
                config.modelId,
                request,
                patch,
                compatibility = compatibility,
            ),
        )
        val ticket = synchronized(lock) { tickets.remove(key) }
            ?: return Authorization.Refused("no armed egress expectation")
        if (!runCatching(ticket.stillAuthorized).getOrDefault(false)) {
            return Authorization.Refused("request authorization revoked")
        }
        if (ticket.prepared.includesPrivateMaterial && ticket.consent == null) {
            return Authorization.Refused("private payload without consent")
        }
        return Authorization.Granted
    }

    internal fun pendingTicketCountForTest(): Int = synchronized(lock) { tickets.size }

    private fun ticketKey(requestId: String, providerId: String, transportHashHex: String): String =
        requestId + "|" + providerId + "|" + transportHashHex

    fun sanitizeTransportRefusal(reason: String): String =
        DataEgressPolicy.sanitizeDiagnostics(reason)

    companion object {
        const val MAX_TICKETS = 8
    }
}
