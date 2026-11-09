package com.projectnuke.fusion.ai.network

import com.projectnuke.fusion.ai.model.AiChatCompletionStatus
import com.projectnuke.fusion.ai.secure.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiCompatibleClientCompletionStatusTest {
    private val client = OpenAiCompatibleClient(object : SecretStore {
        override suspend fun getSecret(id: String): String? = null
        override suspend fun putSecret(id: String, value: String): Boolean = true
        override suspend fun deleteSecret(id: String): Boolean = true
    })

    @Test
    fun lengthFinishReason_mapsToOutputLimitReached() {
        val response = client.parseResponse(
            """{"choices":[{"message":{"content":"partial"},"finish_reason":"length"}]}""",
            reasoningEnabled = false,
        )

        assertEquals(AiChatCompletionStatus.OUTPUT_LIMIT_REACHED, response.completionStatus)
    }

    @Test
    fun stopFinishReason_mapsToOrdinaryCompletion() {
        val response = client.parseResponse(
            """{"choices":[{"message":{"content":"done"},"finish_reason":"stop"}]}""",
            reasoningEnabled = false,
        )

        assertEquals(AiChatCompletionStatus.COMPLETE_OR_UNSPECIFIED, response.completionStatus)
    }

    @Test
    fun missingFinishReason_preservesOrdinaryCompletion() {
        val response = client.parseResponse(
            """{"choices":[{"message":{"content":"done"}}]}""",
            reasoningEnabled = false,
        )

        assertEquals(AiChatCompletionStatus.COMPLETE_OR_UNSPECIFIED, response.completionStatus)
    }

    @Test
    fun nullFinishReason_preservesOrdinaryCompletion() {
        val response = client.parseResponse(
            """{"choices":[{"message":{"content":"done"},"finish_reason":null}]}""",
            reasoningEnabled = false,
        )

        assertEquals(AiChatCompletionStatus.COMPLETE_OR_UNSPECIFIED, response.completionStatus)
    }

    @Test
    fun unknownFinishReason_doesNotBecomeOutputLimitReached() {
        val response = client.parseResponse(
            """{"choices":[{"message":{"content":"done"},"finish_reason":"content_filter"}]}""",
            reasoningEnabled = false,
        )

        assertEquals(AiChatCompletionStatus.COMPLETE_OR_UNSPECIFIED, response.completionStatus)
    }

    @Test
    fun reasoningRemainsSeparateWhenLengthOccurs() {
        val response = client.parseResponse(
            """{"choices":[{"message":{"content":"partial","reasoning_content":"private plan"},"finish_reason":"length"}]}""",
            reasoningEnabled = true,
        )

        assertEquals("partial", response.content)
        assertEquals("private plan", response.reasoningContent)
        assertEquals(AiChatCompletionStatus.OUTPUT_LIMIT_REACHED, response.completionStatus)
    }

    @Test
    fun stringAndContentArrayRetainCurrentParsing() {
        val stringResponse = client.parseResponse(
            """{"choices":[{"message":{"content":"hello"},"finish_reason":"stop"}]}""",
            reasoningEnabled = false,
        )
        val arrayResponse = client.parseResponse(
            """{"choices":[{"message":{"content":[{"type":"text","text":"hello "},{"type":"text","text":"world"}]},"finish_reason":"length"}]}""",
            reasoningEnabled = false,
        )

        assertEquals("hello", stringResponse.content)
        assertEquals("hello world", arrayResponse.content)
        assertEquals(AiChatCompletionStatus.OUTPUT_LIMIT_REACHED, arrayResponse.completionStatus)
    }
}
