package com.projectnuke.fusion.ai.network

import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.ExternalDiscoveredModel
import com.projectnuke.fusion.ai.model.ExternalModelDiscoveryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** The non-secret editor identity used to invalidate discovery work. */
data class DiscoveryEditorFingerprint(
    val providerId: String,
    val modelsEndpoint: String,
    val authMode: AiProviderAuthMode,
    val authHeaderIdentity: String?,
    val credentialRevision: Long,
) {
    companion object {
        fun from(
            providerId: String,
            baseUrl: String,
            authMode: AiProviderAuthMode,
            authHeaderName: String?,
            credentialRevision: Long,
        ): DiscoveryEditorFingerprint = DiscoveryEditorFingerprint(
            providerId = providerId,
            modelsEndpoint = runCatching {
                OpenAiCompatibleEndpointResolver.resolve(baseUrl).models
            }.getOrElse { baseUrl.trim() },
            authMode = authMode,
            authHeaderIdentity = authHeaderName?.trim()?.takeIf { it.isNotBlank() },
            credentialRevision = credentialRevision,
        )
    }
}

data class DiscoveryCoordinatorState(
    val loading: Boolean = false,
    val result: ExternalModelDiscoveryResult? = null,
    val models: List<ExternalDiscoveredModel> = emptyList(),
    val editorFingerprint: DiscoveryEditorFingerprint? = null,
)

interface ExternalModelDiscoverySource {
    suspend fun discover(
        config: AiProviderConfig,
        discoveryIdentity: ExternalDiscoveryIdentity,
        ephemeralApiKey: String? = null,
    ): ExternalModelDiscoveryResult
}

/**
 * Production owner for discovery state, cache access, request cancellation,
 * and stale-result fencing. UI code observes [state] and never settles an
 * operation directly.
 */
class DiscoveryCoordinator(
    private val scope: CoroutineScope,
    private val source: ExternalModelDiscoverySource,
    private val cache: ExternalModelDiscoveryCache = ExternalModelDiscoveryCache(),
) {
    private val mutableState = MutableStateFlow(DiscoveryCoordinatorState())
    val state: StateFlow<DiscoveryCoordinatorState> = mutableState.asStateFlow()

    private var activeJob: Job? = null
    private var activeIdentity: ExternalDiscoveryIdentity? = null
    private var editorFingerprint: DiscoveryEditorFingerprint? = null
    private val ownershipFence = DiscoveryOwnershipFence()

    fun updateEditorFingerprint(fingerprint: DiscoveryEditorFingerprint) {
        if (editorFingerprint == fingerprint) return
        editorFingerprint = fingerprint
        activeJob?.cancel()
        activeJob = null
        activeIdentity = null
        ownershipFence.clear()
        mutableState.value = DiscoveryCoordinatorState(editorFingerprint = fingerprint)
    }

    fun invalidate() {
        activeJob?.cancel()
        activeJob = null
        activeIdentity = null
        ownershipFence.clear()
        mutableState.value = DiscoveryCoordinatorState(editorFingerprint = editorFingerprint)
    }

    fun discover(
        config: AiProviderConfig,
        credentialRevision: Long,
        ephemeralApiKey: String? = null,
        forceRefresh: Boolean = false,
    ): Job {
        val fingerprint = DiscoveryEditorFingerprint.from(
            providerId = config.id,
            baseUrl = config.baseUrl,
            authMode = config.authMode,
            authHeaderName = config.authHeaderName,
            credentialRevision = credentialRevision,
        )
        updateEditorFingerprint(fingerprint)
        activeJob?.cancel()

        val identity = ExternalDiscoveryIdentity(
            providerId = fingerprint.providerId,
            endpointIdentity = fingerprint.modelsEndpoint,
            authMode = fingerprint.authMode,
            authHeaderIdentity = fingerprint.authHeaderIdentity,
            credentialRevision = fingerprint.credentialRevision,
            operationId = UUID.randomUUID().toString(),
        )
        activeIdentity = identity
        ownershipFence.claim(identity)
        mutableState.value = DiscoveryCoordinatorState(
            loading = true,
            editorFingerprint = fingerprint,
        )

        val job = scope.launch {
            try {
                if (!forceRefresh) {
                    val cached = cache.get(identity)
                    if (cached != null) {
                        publish(identity, ExternalModelDiscoveryResult.Success(cached, System.currentTimeMillis()), storeInCache = false)
                        return@launch
                    }
                }
                val result = source.discover(config, identity, ephemeralApiKey)
                publish(identity, result, storeInCache = true)
            } catch (_: CancellationException) {
                // Cancellation is an invalidation/supersession, never a user error.
            } catch (error: Exception) {
                publish(identity, ExternalModelDiscoveryResult.NetworkError(error.message ?: "Discovery failed"), storeInCache = false)
            } finally {
                // The old coroutine must not settle the newer operation.
                if (owns(identity)) {
                    mutableState.value = mutableState.value.copy(loading = false)
                    activeJob = null
                }
            }
        }
        activeJob = job
        return job
    }

    fun close() {
        activeJob?.cancel()
        activeJob = null
        activeIdentity = null
        ownershipFence.clear()
    }

    private fun publish(
        identity: ExternalDiscoveryIdentity,
        result: ExternalModelDiscoveryResult,
        storeInCache: Boolean,
    ) {
        if (!ownershipFence.publishResult(identity, result) || !owns(identity)) return
        if (storeInCache && result is ExternalModelDiscoveryResult.Success) {
            cache.put(identity, result)
        }
        mutableState.value = DiscoveryCoordinatorState(
            loading = true,
            result = result,
            models = (result as? ExternalModelDiscoveryResult.Success)?.models.orEmpty(),
            editorFingerprint = editorFingerprint,
        )
    }

    private fun owns(identity: ExternalDiscoveryIdentity): Boolean =
        activeIdentity == identity && ownershipFence.canPublishFor(identity)
}
