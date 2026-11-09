package com.projectnuke.fusion.ai.network

data class OpenAiChatCompletionRequestDto(
    val model: String,
    val messages: List<OpenAiMessageDto>,
    val temperature: Double?,
    val max_tokens: Int?,
    val stream: Boolean = false
)

data class OpenAiMessageDto(
    val role: String,
    val content: String
)

data class OpenAiChatCompletionResponseDto(
    val id: String?,
    val model: String?,
    val choices: List<OpenAiChoiceDto>
)

data class OpenAiChoiceDto(
    val message: OpenAiMessageDto?
)
