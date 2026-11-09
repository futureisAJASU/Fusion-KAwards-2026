package com.projectnuke.fusion.ai.network

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertThrows

class OpenAiCompatibleEndpointResolverTest {

    @Test
    fun `resolve v1 root produces correct endpoints`() {
        val result = OpenAiCompatibleEndpointResolver.resolve("https://host/v1")
        assertEquals("https://host/v1", result.apiRoot)
        assertEquals("https://host/v1/chat/completions", result.chatCompletions)
        assertEquals("https://host/v1/models", result.models)
    }

    @Test
    fun `resolve v1 with trailing slash produces correct endpoints`() {
        val result = OpenAiCompatibleEndpointResolver.resolve("https://host/v1/")
        assertEquals("https://host/v1", result.apiRoot)
        assertEquals("https://host/v1/chat/completions", result.chatCompletions)
        assertEquals("https://host/v1/models", result.models)
    }

    @Test
    fun `resolve chat completions endpoint derives correct api root`() {
        val result = OpenAiCompatibleEndpointResolver.resolve("https://host/v1/chat/completions")
        assertEquals("https://host/v1", result.apiRoot)
        assertEquals("https://host/v1/chat/completions", result.chatCompletions)
        assertEquals("https://host/v1/models", result.models)
    }

    @Test
    fun `resolve chat completions with trailing slash derives correct api root`() {
        val result = OpenAiCompatibleEndpointResolver.resolve("https://host/v1/chat/completions/")
        assertEquals("https://host/v1", result.apiRoot)
        assertEquals("https://host/v1/chat/completions", result.chatCompletions)
        assertEquals("https://host/v1/models", result.models)
    }

    @Test
    fun `normalizeForChat produces chat endpoint for root url`() {
        assertEquals("https://host/v1/chat/completions", OpenAiCompatibleEndpointResolver.normalizeForChat("https://host/v1"))
    }

    @Test
    fun `normalizeForChat produces same endpoint when input is already chat`() {
        assertEquals("https://host/v1/chat/completions", OpenAiCompatibleEndpointResolver.normalizeForChat("https://host/v1/chat/completions"))
    }

    @Test
    fun `resolveModelsEndpoint produces models endpoint`() {
        assertEquals("https://host/v1/models", OpenAiCompatibleEndpointResolver.resolveModelsEndpoint("https://host/v1"))
    }

    @Test
    fun `resolve preserves port when non-standard`() {
        val result = OpenAiCompatibleEndpointResolver.resolve("https://host:8443/v1")
        assertEquals("https://host:8443/v1", result.apiRoot)
        assertEquals("https://host:8443/v1/chat/completions", result.chatCompletions)
        assertEquals("https://host:8443/v1/models", result.models)
    }

    @Test
    fun `resolve handles pure domain with no path`() {
        val result = OpenAiCompatibleEndpointResolver.resolve("https://api.example.com")
        assertEquals("https://api.example.com", result.apiRoot)
        assertEquals("https://api.example.com/chat/completions", result.chatCompletions)
        assertEquals("https://api.example.com/models", result.models)
    }

    @Test
    fun `query and fragment are rejected before sibling endpoints are built`() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiCompatibleEndpointResolver.resolve("https://host/v1?x=1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiCompatibleEndpointResolver.resolve("https://host/v1#fragment")
        }
    }

    @Test
    fun `malformed endpoint is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiCompatibleEndpointResolver.resolve("not a uri")
        }
    }
}
