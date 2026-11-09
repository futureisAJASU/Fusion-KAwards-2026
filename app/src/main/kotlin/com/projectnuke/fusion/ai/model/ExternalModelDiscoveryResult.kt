package com.projectnuke.fusion.ai.model

sealed interface ExternalModelDiscoveryResult {
    data class Success(
        val models: List<ExternalDiscoveredModel>,
        val fetchedAt: Long,
    ) : ExternalModelDiscoveryResult

    data object Unsupported : ExternalModelDiscoveryResult
    data object AuthenticationFailed : ExternalModelDiscoveryResult
    data object RateLimited : ExternalModelDiscoveryResult
    data class NetworkError(val message: String) : ExternalModelDiscoveryResult
    data object InvalidResponse : ExternalModelDiscoveryResult
    data object Empty : ExternalModelDiscoveryResult
}
