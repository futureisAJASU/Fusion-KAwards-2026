package com.projectnuke.fusion.ai

import com.projectnuke.fusion.ai.model.AiChatCompletionStatus
import com.projectnuke.fusion.ai.model.AiChatRequest
import com.projectnuke.fusion.ai.model.AiChatResponse
import com.projectnuke.fusion.ai.model.AiMessage
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.AiRole
import com.projectnuke.fusion.ai.network.ChatClient
import com.projectnuke.fusion.ai.network.OpenAiCompatibleClient
import com.projectnuke.fusion.ai.secure.SecretStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAiChatRunnerTruncationTest {
    private val provider = AiProviderConfig(
        id = "provider",
        type = AiProviderType.OPENAI,
        displayName = "Provider",
        baseUrl = "https://example.com",
        modelId = "model",
        apiKeySecretId = "secret",
    )

    private val source = object : ExternalAiProviderSource {
        override suspend fun getSelectedRunnableProvider(): AiProviderConfig = provider
        override suspend fun getRunnableProviderById(id: String): AiProviderConfig? = provider.takeIf { it.id == id }
    }

    private fun runner(response: AiChatResponse) = ExternalAiChatRunner(
        source,
        object : ChatClient {
            override suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest): AiChatResponse = response
        },
    )

    @Test
    fun nonBlankNormalResponse_isSuccess() = runBlocking {
        val result = runner(AiChatResponse("id", "model", "complete")).generateFromMessages(
            listOf(AiMessage(AiRole.USER, "hello")),
        )

        assertTrue(result is ExternalAiChatResult.Success)
        assertEquals("complete", (result as ExternalAiChatResult.Success).content)
    }

    @Test
    fun blankNormalResponse_isEmpty() = runBlocking {
        val result = runner(AiChatResponse("id", "model", "  ")).generateFromMessages(
            listOf(AiMessage(AiRole.USER, "hello")),
        )

        assertTrue(result is ExternalAiChatResult.Empty)
    }

    @Test
    fun nonBlankOutputLimitResponse_isTruncatedWithSanitizedPartial() = runBlocking {
        val result = runner(
            AiChatResponse(
                id = "id",
                model = "model",
                content = "  partial answer  ",
                completionStatus = AiChatCompletionStatus.OUTPUT_LIMIT_REACHED,
            )
        ).generateFromMessages(listOf(AiMessage(AiRole.USER, "hello")))

        assertTrue(result is ExternalAiChatResult.Truncated)
        assertEquals("partial answer", (result as ExternalAiChatResult.Truncated).content)
    }

    @Test
    fun blankOutputLimitResponse_isTruncatedWithoutFabricatedText() = runBlocking {
        val result = runner(
            AiChatResponse(
                id = "id",
                model = "model",
                content = " \n\t ",
                completionStatus = AiChatCompletionStatus.OUTPUT_LIMIT_REACHED,
            )
        ).generateFromMessages(listOf(AiMessage(AiRole.USER, "hello")))

        assertTrue(result is ExternalAiChatResult.Truncated)
        assertTrue((result as ExternalAiChatResult.Truncated).content.isBlank())
    }

    @Test
    fun outputLimitReasoningRemainsSeparate() = runBlocking {
        val result = runner(
            AiChatResponse(
                id = "id",
                model = "model",
                content = "partial",
                reasoningContent = "private plan",
                completionStatus = AiChatCompletionStatus.OUTPUT_LIMIT_REACHED,
            )
        ).generateFromMessages(listOf(AiMessage(AiRole.USER, "hello"))) as ExternalAiChatResult.Truncated

        assertEquals("partial", result.content)
        assertEquals("private plan", result.reasoningContent)
    }

    @Test
    fun `129 part JSON truncation remains truncated through parser and runner`() = runBlocking {
        val parts = (1..129).joinToString(",") { index ->
            """{"type":"text","text":"part$index"}"""
        }
        val parsed = OpenAiCompatibleClient(object : SecretStore {
            override suspend fun getSecret(id: String): String? = null
            override suspend fun putSecret(id: String, value: String): Boolean = true
            override suspend fun deleteSecret(id: String): Boolean = true
        }).parseResponse(
            """{"choices":[{"message":{"content":[$parts]},"finish_reason":"stop"}]}""",
            reasoningEnabled = false,
        )

        val result = runner(parsed).generateFromMessages(listOf(AiMessage(AiRole.USER, "hello")))

        assertTrue(parsed.truncated)
        assertTrue(result is ExternalAiChatResult.Truncated)
        assertEquals((1..128).joinToString("") { "part$it" }, (result as ExternalAiChatResult.Truncated).content)
    }
}
