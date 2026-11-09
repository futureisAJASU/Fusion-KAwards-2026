package com.projectnuke.fusion.ai.usecase

import com.projectnuke.fusion.ai.model.AiChatRequest
import com.projectnuke.fusion.ai.model.AiMessage
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiRole
import com.projectnuke.fusion.ai.network.OpenAiCompatibleClient
import kotlinx.coroutines.CancellationException

class TestAiProviderUseCase(
    private val client: OpenAiCompatibleClient
) {
    suspend operator fun invoke(config: AiProviderConfig): Result<String> {
        return try {
            client.chatCompletion(
                config = config,
                request = AiChatRequest(
                    messages = listOf(AiMessage(AiRole.USER, "ping")),
                    temperature = 0.0,
                    maxTokens = 8
                )
            )
            Result.success("연결 테스트가 완료되었습니다.")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(IllegalStateException(error.message ?: "연결 테스트에 실패했습니다.", error))
        }
    }
}
