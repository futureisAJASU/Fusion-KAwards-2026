package com.projectnuke.fusion.ai.network

import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.ExternalModelDiscoveryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryOwnershipFenceTest {

    @Test
    fun `claim sets identity`() {
        val fence = DiscoveryOwnershipFence()
        val identity = ExternalDiscoveryIdentity(
            providerId = "p1",
            endpointIdentity = "https://host/v1",
            authMode = AiProviderAuthMode.BEARER_API_KEY,
            authHeaderIdentity = null,
            credentialRevision = 1L,
            operationId = "op1",
        )
        assertTrue(fence.claim(identity))
        assertTrue(fence.canPublishFor(identity))
    }

    @Test
    fun `publish rejects mismatched identity`() {
        val fence = DiscoveryOwnershipFence()
        val identity = ExternalDiscoveryIdentity(
            providerId = "p1",
            endpointIdentity = "https://host/v1",
            authMode = AiProviderAuthMode.BEARER_API_KEY,
            authHeaderIdentity = null,
            credentialRevision = 1L,
            operationId = "op1",
        )
        fence.claim(identity)
        val other = identity.copy(operationId = "op2")
        assertFalse(fence.canPublishFor(other))
        assertFalse(fence.publishResult(other, ExternalModelDiscoveryResult.Success(emptyList(), 0L)))
    }

    @Test
    fun `publish succeeds with matching identity`() {
        val fence = DiscoveryOwnershipFence()
        val identity = ExternalDiscoveryIdentity(
            providerId = "p1",
            endpointIdentity = "https://host/v1",
            authMode = AiProviderAuthMode.BEARER_API_KEY,
            authHeaderIdentity = null,
            credentialRevision = 1L,
            operationId = "op1",
        )
        fence.claim(identity)
        val result = ExternalModelDiscoveryResult.Success(
            models = emptyList(), fetchedAt = 100L
        )
        assertTrue(fence.publishResult(identity, result))
        assertEquals(result, fence.getOwnedResult())
    }

    @Test
    fun `clear removes identity`() {
        val fence = DiscoveryOwnershipFence()
        val identity = ExternalDiscoveryIdentity(
            providerId = "p1", endpointIdentity = "e", authMode = AiProviderAuthMode.BEARER_API_KEY,
            authHeaderIdentity = null, credentialRevision = 1L, operationId = "op1"
        )
        fence.claim(identity)
        fence.clear()
        assertFalse(fence.canPublishFor(identity))
    }

    @Test
    fun `old result cannot publish after credentialRevision change`() {
        val fence = DiscoveryOwnershipFence()
        val identity1 = ExternalDiscoveryIdentity(
            providerId = "p1", endpointIdentity = "https://host/v1",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 1L, operationId = "op1",
        )
        fence.claim(identity1)
        // After key edit, credentialRevision changes
        val identity2 = identity1.copy(credentialRevision = 2L)
        // Old operationId identity cannot publish new credential revision
        assertFalse(fence.publishResult(identity2, ExternalModelDiscoveryResult.AuthenticationFailed))
    }
}
