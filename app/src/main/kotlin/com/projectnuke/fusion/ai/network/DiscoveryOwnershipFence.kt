package com.projectnuke.fusion.ai.network

import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import java.util.concurrent.atomic.AtomicReference

/**
 * Ownership fence: only the currently-owned discovery request may publish.
 */
class DiscoveryOwnershipFence {

    private val ownedIdentity = AtomicReference<ExternalDiscoveryIdentity?>(null)
    private val ownedResult = AtomicReference<com.projectnuke.fusion.ai.model.ExternalModelDiscoveryResult?>(null)

    fun claim(identity: ExternalDiscoveryIdentity): Boolean {
        ownedIdentity.set(identity)
        ownedResult.set(null)
        return true
    }

    fun publishResult(
        identity: ExternalDiscoveryIdentity,
        result: com.projectnuke.fusion.ai.model.ExternalModelDiscoveryResult,
    ): Boolean {
        val current = ownedIdentity.get()
        if (current == null || !current.matches(identity)) return false
        ownedResult.set(result)
        return true
    }

    fun canPublishFor(identity: ExternalDiscoveryIdentity): Boolean {
        val current = ownedIdentity.get()
        return current != null && current.matches(identity)
    }

    fun getOwnedResult(): com.projectnuke.fusion.ai.model.ExternalModelDiscoveryResult? {
        val current = ownedIdentity.get()
        return if (current != null) ownedResult.get() else null
    }

    fun clear() {
        ownedIdentity.set(null)
        ownedResult.set(null)
    }

    private fun ExternalDiscoveryIdentity.matches(other: ExternalDiscoveryIdentity): Boolean {
        return this.providerId == other.providerId &&
            this.endpointIdentity == other.endpointIdentity &&
            this.authMode == other.authMode &&
            this.authHeaderIdentity == other.authHeaderIdentity &&
            this.credentialRevision == other.credentialRevision &&
            this.operationId == other.operationId
    }
}
