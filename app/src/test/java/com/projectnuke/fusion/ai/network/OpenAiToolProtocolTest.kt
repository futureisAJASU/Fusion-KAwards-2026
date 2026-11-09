package com.projectnuke.fusion.ai.network

import com.projectnuke.fusion.ai.model.AgenticWebSearchTool
import com.projectnuke.fusion.ai.model.AiChatRequest
import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.AiMessage
import com.projectnuke.fusion.ai.model.AiRole
import com.projectnuke.fusion.ai.model.AiToolCall
import com.projectnuke.fusion.ai.model.AiToolChoice
import com.projectnuke.fusion.ai.secure.SecretStore
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class OpenAiToolProtocolTest {
    private val client = OpenAiCompatibleClient(NoSecrets())
    private val provider = AiProviderConfig(
        id = "openai",
        type = AiProviderType.OPENAI,
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        modelId = "gpt-5.5",
        apiKeySecretId = null,
        authMode = AiProviderAuthMode.NONE,
    )

    @Test
    fun nativeToolRequestSerializesDefinitionAndToolChoice() {
        val request = AiChatRequest(
            messages = listOf(AiMessage(AiRole.USER, "삼성전자 주가")),
            tools = listOf(AgenticWebSearchTool.definition()),
            toolChoice = AiToolChoice.AUTO,
        )
        val json = client.buildRequestJson(provider, request)
        val tool = json.getJSONArray("tools").getJSONObject(0)
        val function = tool.getJSONObject("function")
        val params = function.getJSONObject("parameters")

        assertEquals("auto", json.getString("tool_choice"))
        assertEquals("web_search", function.getString("name"))
        assertTrue(function.getBoolean("strict"))
        assertFalse(params.getBoolean("additionalProperties"))
        assertEquals("queries", params.getJSONArray("required").getString(0))
        assertEquals("array", params.getJSONObject("properties").getJSONObject("queries").getString("type"))
        assertEquals("string", params.getJSONObject("properties").getJSONObject("queries").getJSONObject("items").getString("type"))
    }

    @Test
    fun assistantCallsAndToolResultsSerializeWithExactIds() {
        val call = AiToolCall("call-123", "web_search", """{"queries":["public query"]}""")
        val request = AiChatRequest(
            messages = listOf(
                AiMessage(AiRole.ASSISTANT, "", toolCalls = listOf(call)),
                AiMessage(AiRole.TOOL, "untrusted result", toolCallId = "call-123"),
            ),
            tools = listOf(AgenticWebSearchTool.definition()),
        )
        val messages = client.buildRequestJson(provider, request).getJSONArray("messages")
        val assistant = messages.getJSONObject(0)
        val tool = messages.getJSONObject(1)

        assertEquals("assistant", assistant.getString("role"))
        assertEquals("call-123", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("tool", tool.getString("role"))
        assertEquals("call-123", tool.getString("tool_call_id"))
    }

    @Test
    fun nativeResponseParsesCallIdArgumentsAndFinishReason() {
        val response = client.parseResponse(
            """{"id":"resp-1","choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"web_search","arguments":"{\"queries\":[\"public query\"]}"}}]}}]}""",
            reasoningEnabled = false,
        )

        assertEquals("tool_calls", response.finishReason)
        assertEquals(1, response.toolCalls.size)
        assertEquals("call-1", response.toolCalls.single().id)
        assertEquals("web_search", response.toolCalls.single().name)
        assertEquals("""{"queries":["public query"]}""", response.toolCalls.single().arguments)
        assertEquals("", response.content)
    }

    @Test
    fun parserRejectsMissingCallIdsUnsupportedTypesAndExcessiveCalls() {
        assertThrows(AiProviderClientException::class.java) {
            client.parseResponse(
                """{"choices":[{"message":{"tool_calls":[{"type":"function","function":{"name":"web_search","arguments":"{}"}}]}}]}""",
                false,
            )
        }
        assertThrows(AiProviderClientException::class.java) {
            client.parseResponse(
                """{"choices":[{"message":{"tool_calls":[{"id":"x","type":"custom","function":{"name":"web_search","arguments":"{}"}}]}}]}""",
                false,
            )
        }
        val excessive = JSONArray().apply {
            repeat(3) { index -> put("""{"id":"$index","type":"function","function":{"name":"web_search","arguments":"{}"}}""") }
        }
        assertThrows(AiProviderClientException::class.java) {
            client.parseResponse("""{"choices":[{"message":{"tool_calls":$excessive}}]}""", false)
        }
    }

    @Test
    fun emptyAndPlainResponsesRemainCompatibleAndDoNotClaimToolUse() {
        val plain = client.buildRequestJson(provider, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi"))))
        assertFalse(plain.has("tools"))
        assertFalse(plain.has("tool_choice"))
        val response = client.parseResponse(
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"plain reply"}}]}""",
            false,
        )
        assertEquals("plain reply", response.content)
        assertTrue(response.toolCalls.isEmpty())
        assertNull(response.reasoningContent)
    }

    @Test
    fun unsupportedToolProtocolIsClassifiedOnceWithoutBroadProviderAssumption() {
        assertTrue(client.isNativeToolProtocolRejection(400, "Unknown parameter: tools is not supported"))
        assertFalse(client.isNativeToolProtocolRejection(500, "tools not supported"))
        assertFalse(client.isNativeToolProtocolRejection(400, "invalid model"))
    }

    @Test
    fun invalidOrExcessiveToolEnvelopesAreRejectedBeforeOpeningTransport() {
        var opened = 0
        val guarded = OpenAiCompatibleClient(NoSecrets(), connectionFactory = {
            opened++
            error("invalid tool request must be rejected before transport")
        })
        val unsupported = AiChatRequest(
            messages = listOf(AiMessage(AiRole.USER, "query")),
            tools = listOf(AgenticWebSearchTool.definition().copy(name = "shell")),
        )
        assertThrows(AiProviderClientException::class.java) {
            runBlocking { guarded.chatCompletion(provider, unsupported) }
        }
        val oversizedResult = AiChatRequest(
            messages = listOf(AiMessage(AiRole.TOOL, "x".repeat(8_193), toolCallId = "call-1")),
            tools = listOf(AgenticWebSearchTool.definition()),
        )
        assertThrows(AiProviderClientException::class.java) {
            runBlocking { guarded.chatCompletion(provider, oversizedResult) }
        }
        assertEquals(0, opened)
    }

    private class NoSecrets : SecretStore {
        override suspend fun putSecret(id: String, value: String): Boolean = true
        override suspend fun getSecret(id: String): String? = null
        override suspend fun deleteSecret(id: String): Boolean = true
    }
}
