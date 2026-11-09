package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.RequestedEngineProfile

interface LlmEngine {
    suspend fun generate(
        messages: List<ChatMessage>,
        profile: RequestedEngineProfile,
        options: ConversationOptions
    ): GenerationOutcome

    suspend fun generateStreaming(
        messages: List<ChatMessage>,
        profile: RequestedEngineProfile,
        options: ConversationOptions,
        onToken: (String) -> Unit
    ): GenerationOutcome {
        val output = generate(
            messages = messages,
            profile = profile,
            options = options
        )
        if (output is GenerationOutcome.Success) {
            onToken(output.text)
        }
        return output
    }

    suspend fun generateMultimodalStreaming(
        messages: List<ChatMessage>,
        profile: RequestedEngineProfile,
        options: ConversationOptions,
        imagePaths: List<String>,
        onToken: (String) -> Unit
    ): GenerationOutcome {
        val outcome = generate(
            messages = messages,
            profile = profile,
            options = options
        )
        if (outcome is GenerationOutcome.Success) {
            onToken(outcome.text)
        }
        return outcome
    }

    fun unload()
}
