package com.projectnuke.fusion.ai

import com.projectnuke.fusion.ai.model.AiMessage
import com.projectnuke.fusion.ai.model.AiRole
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.util.sanitizeAssistantHistoryTurns
import java.util.Locale

internal fun buildExternalAiMessages(
    history: List<ChatMessage>,
    userInput: String,
    stripAttachments: (String) -> String,
): List<AiMessage> {
    return buildExternalAiMessages(history, userInput, emptySet(), stripAttachments)
}

internal fun buildExternalAiMessages(
    history: List<ChatMessage>,
    userInput: String,
    assistantReasoningTags: Set<String>,
    stripAttachments: (String) -> String,
): List<AiMessage> {
    val strippedHistory = history.map { message ->
        message.copy(content = stripAttachments(message.content))
    }
    val sanitizedHistory = sanitizeAssistantHistoryTurns(strippedHistory, assistantReasoningTags)
    return buildList {
        sanitizedHistory.forEach { message ->
            val role = message.role.toAiRoleOrNull() ?: return@forEach
            val content = message.content.trim()
            if (content.isNotBlank()) {
                add(AiMessage(role = role, content = content))
            }
        }
        if (userInput.isNotBlank()) {
            add(AiMessage(role = AiRole.USER, content = userInput))
        }
    }
}

internal fun buildExternalAiMessages(
    messages: List<ChatMessage>,
    stripAttachments: (String) -> String,
): List<AiMessage> {
    return buildExternalAiMessages(messages, emptySet(), stripAttachments)
}

internal fun buildExternalAiMessages(
    messages: List<ChatMessage>,
    assistantReasoningTags: Set<String>,
    stripAttachments: (String) -> String,
): List<AiMessage> {
    val strippedMessages = messages.map { message ->
        message.copy(content = stripAttachments(message.content))
    }
    val sanitizedMessages = sanitizeAssistantHistoryTurns(strippedMessages, assistantReasoningTags)
    return buildList {
        sanitizedMessages.forEach { message ->
            val role = message.role.toAiRoleOrNull() ?: return@forEach
            val content = message.content.trim()
            if (content.isNotBlank()) {
                add(AiMessage(role = role, content = content))
            }
        }
    }
}

private fun String.toAiRoleOrNull(): AiRole? {
    return when (lowercase(Locale.US)) {
        "system" -> AiRole.SYSTEM
        "user" -> AiRole.USER
        "assistant" -> AiRole.ASSISTANT
        else -> null
    }
}
