package com.projectnuke.fusion.ai.model

enum class AiRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

data class AiMessage(
    val role: AiRole,
    val content: String,
    /** Required by provider-native tool result messages. */
    val toolCallId: String? = null,
    /** Assistant tool-call envelope; empty for ordinary chat messages. */
    val toolCalls: List<AiToolCall> = emptyList(),
)
