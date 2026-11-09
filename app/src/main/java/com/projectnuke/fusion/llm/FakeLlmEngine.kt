package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.RequestedEngineProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class FakeLlmEngine : LlmEngine {
    override suspend fun generate(
        messages: List<ChatMessage>,
        profile: RequestedEngineProfile,
        options: ConversationOptions
    ): GenerationOutcome {
        try {
            delay(500)
        } catch (e: CancellationException) {
            throw e
        }

        val lastUserMessage = messages
            .lastOrNull { it.role == "user" }
            ?.content
            ?: ""

        val text = buildString {
            appendLine("FakeLlmEngine 테스트 응답입니다.")
            appendLine()
            appendLine("입력: $lastUserMessage")
            appendLine("모델 경로: ${profile.modelPath}")
            appendLine("설정: max=${profile.kvCacheCapacityTokens}, topK=${options.topK}, topP=${options.topP}, temp=${options.temperature}, acc=${profile.accelerator}")
        }
        return GenerationOutcome.Success(text = text, actualBackend = "CPU")
    }

    override suspend fun generateStreaming(
        messages: List<ChatMessage>,
        profile: RequestedEngineProfile,
        options: ConversationOptions,
        onToken: (String) -> Unit
    ): GenerationOutcome {
        try {
            val outcome = generate(messages, profile, options)
            if (outcome is GenerationOutcome.Success) {
                outcome.text.split(" ").forEach { token ->
                    onToken("$token ")
                    delay(35)
                }
            }
            return outcome
        } catch (e: CancellationException) {
            throw e
        }
    }

    override suspend fun generateMultimodalStreaming(
        messages: List<ChatMessage>,
        profile: RequestedEngineProfile,
        options: ConversationOptions,
        imagePaths: List<String>,
        onToken: (String) -> Unit
    ): GenerationOutcome {
        try {
            val text = buildString {
                appendLine("FakeLlmEngine image test response.")
                appendLine("Images: ${imagePaths.joinToString()}")
                appendLine("Model: ${profile.modelPath}")
            }
            text.split(" ").forEach { token ->
                onToken("$token ")
                delay(35)
            }
            return GenerationOutcome.Success(text = text, actualBackend = "CPU")
        } catch (e: CancellationException) {
            throw e
        }
    }

    override fun unload() {}
}
