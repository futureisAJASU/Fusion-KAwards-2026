package com.projectnuke.fusion.modelzoo

import com.projectnuke.fusion.chat.HistoricalMemoryPromptPolicy
import com.projectnuke.fusion.chat.FusionHostMarkerPolicy
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.util.splitExplicitReasoning
import com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace

interface FusionPromptAdapter {
    val family: ModelFamily
    val explicitReasoningTags: Set<String> get() = emptySet()
    fun buildMessages(messages: List<ChatMessage>): List<ChatMessage>
    fun supportsFusionReasoningTags(): Boolean
    fun sanitizeOutput(raw: String): String {
        if (explicitReasoningTags.isEmpty()) return visibleMessageContentPreservingWhitespace(raw)
        val (thinking, answer) = splitExplicitReasoning(raw, explicitReasoningTags)
        return if (thinking.isNotBlank()) {
            "<fusion_thinking>$thinking</fusion_thinking>$answer".trim()
        } else {
            answer.trim()
        }
    }
}

data class PreparedMessages(
    val messages: List<ChatMessage>,
    val modelFamily: ModelFamily
)

object GemmaPromptAdapter : FusionPromptAdapter {
    override val family = ModelFamily.GEMMA
    override val explicitReasoningTags: Set<String> = setOf("fusion_thinking")
    override fun buildMessages(messages: List<ChatMessage>): List<ChatMessage> = messages
    override fun supportsFusionReasoningTags(): Boolean = true
}

object QwenPromptAdapter : FusionPromptAdapter {
    override val family = ModelFamily.QWEN
    override val explicitReasoningTags: Set<String> = setOf("think")
    override fun buildMessages(messages: List<ChatMessage>): List<ChatMessage> = shortenSystemMessages(messages)
    override fun supportsFusionReasoningTags(): Boolean = false
}

/**
 * Kanana uses the pinned upstream Jinja template as the sole role-formatting
 * authority. Fusion supplies semantic roles to LiteRT-LM and only trims the
 * returned text; it must not add Qwen/ChatML control tokens itself.
 */
object KananaPromptAdapter : FusionPromptAdapter {
    override val family = ModelFamily.KANANA
    override fun buildMessages(messages: List<ChatMessage>): List<ChatMessage> = messages
    override fun supportsFusionReasoningTags(): Boolean = false
}

object DeepSeekPromptAdapter : FusionPromptAdapter {
    override val family = ModelFamily.DEEPSEEK
    override val explicitReasoningTags: Set<String> = setOf("think", "thinking")
    override fun buildMessages(messages: List<ChatMessage>): List<ChatMessage> {
        return shortenSystemMessages(messages)
    }
    override fun supportsFusionReasoningTags(): Boolean = false
}

internal const val DEEPSEEK_REASONING_INSTRUCTION =
    "추론 출력이 길어질 수 있으므로 최종 답변은 명확하게 구분해 주세요."

object LlamaPromptAdapter : FusionPromptAdapter {
    override val family = ModelFamily.LLAMA
    override fun buildMessages(messages: List<ChatMessage>): List<ChatMessage> = messages.map { message ->
        if (message.role == "system") message.copy(content = message.content.trim()) else message
    }
    override fun supportsFusionReasoningTags(): Boolean = false
}

object MistralPromptAdapter : FusionPromptAdapter {
    override val family = ModelFamily.MISTRAL
    override fun buildMessages(messages: List<ChatMessage>): List<ChatMessage> = messages.map { message ->
        if (message.role == "system") message.copy(content = message.content.trim()) else message
    }
    override fun supportsFusionReasoningTags(): Boolean = false
}

object PhiPromptAdapter : FusionPromptAdapter {
    override val family = ModelFamily.PHI
    override fun buildMessages(messages: List<ChatMessage>): List<ChatMessage> = shortenSystemMessages(messages)
    override fun supportsFusionReasoningTags(): Boolean = false
}

object CustomPromptAdapter : FusionPromptAdapter {
    override val family = ModelFamily.CUSTOM
    override val explicitReasoningTags: Set<String> = setOf("fusion_thinking")
    override fun buildMessages(messages: List<ChatMessage>): List<ChatMessage> = GemmaPromptAdapter.buildMessages(messages)
    override fun supportsFusionReasoningTags(): Boolean = true
}

object KimiPromptAdapter : FusionPromptAdapter {
    override val family = ModelFamily.KIMI
    override fun buildMessages(messages: List<ChatMessage>): List<ChatMessage> = messages
    override fun supportsFusionReasoningTags(): Boolean = false
}

object FusionPromptAdapters {
    fun forFamily(family: ModelFamily): FusionPromptAdapter = when (family) {
        ModelFamily.GEMMA -> GemmaPromptAdapter
        ModelFamily.CUSTOM -> CustomPromptAdapter
        ModelFamily.QWEN -> QwenPromptAdapter
        ModelFamily.KANANA -> KananaPromptAdapter
        ModelFamily.DEEPSEEK -> DeepSeekPromptAdapter
        ModelFamily.LLAMA -> LlamaPromptAdapter
        ModelFamily.MISTRAL -> MistralPromptAdapter
        ModelFamily.PHI -> PhiPromptAdapter
        ModelFamily.KIMI -> KimiPromptAdapter
    }

    /**
     * Prepares messages for the model by extracting internal metadata and stripping
     * all Fusion-internal marker messages. This ensures runtime configuration never
     * alters the semantic model prompt.
     */
    fun prepareMessagesForModel(messages: List<ChatMessage>): PreparedMessages {
        // Extract model family from FUSION_MODEL_FAMILY marker for adapter selection
        val familyMarker = messages.firstOrNull { it.role == "system" && it.content.startsWith("FUSION_MODEL_FAMILY=") }
            ?.content
            ?.substringAfter("=")
            ?.trim()
        val family = runCatching { ModelFamily.valueOf(familyMarker.orEmpty()) }.getOrDefault(ModelFamily.GEMMA)

        // Strip all Fusion-internal marker messages
        val cleanedMessages = messages.filter { msg ->
            !FusionHostMarkerPolicy.isHostOnlyMarker(msg)
        }

        return PreparedMessages(HistoricalMemoryPromptPolicy.prepare(cleanedMessages), family)
    }

    /** Adds family-specific semantic text before the authoritative local fit. */
    fun prepareMessagesForLocalBudget(messages: List<ChatMessage>): List<ChatMessage> {
        val familyMarker = messages.firstOrNull {
            it.role == "system" && it.content.startsWith("FUSION_MODEL_FAMILY=")
        }?.content?.substringAfter("=")?.trim()
        val family = runCatching { ModelFamily.valueOf(familyMarker.orEmpty()) }
            .getOrDefault(ModelFamily.GEMMA)
        if (family != ModelFamily.DEEPSEEK || messages.any {
                it.role == "system" && it.content == DEEPSEEK_REASONING_INSTRUCTION
            }) {
            return messages
        }
        val semanticSystemIndex = messages.indexOfFirst { message ->
            message.role == "system" && !FusionHostMarkerPolicy.isHostOnlyMarker(message)
        }
        val insertionIndex = if (semanticSystemIndex >= 0) semanticSystemIndex else {
            messages.indexOfFirst { it.role == "user" }.takeIf { it >= 0 } ?: messages.size
        }
        return messages.toMutableList().apply {
            add(insertionIndex, ChatMessage("system", DEEPSEEK_REASONING_INSTRUCTION))
        }
    }

    @Deprecated("Use prepareMessagesForModel instead. This reads markers that should be stripped before model prompt construction.", ReplaceWith("forFamily(prepareMessagesForModel(messages).modelFamily)"))
    fun inferFromMessages(messages: List<ChatMessage>): FusionPromptAdapter {
        val marker = messages.firstOrNull { it.role == "system" && it.content.startsWith("FUSION_MODEL_FAMILY=") }
            ?.content
            ?.substringAfter("=")
            ?.trim()
        val family = runCatching { ModelFamily.valueOf(marker.orEmpty()) }.getOrDefault(ModelFamily.GEMMA)
        return forFamily(family)
    }
}

private fun shortenSystemMessages(messages: List<ChatMessage>): List<ChatMessage> {
    return messages.map { message ->
        if (message.role != "system") return@map message
        val short = message.content
            .lines()
            .filterNot { it.contains("<fusion_thinking>") || it.contains("<fusion_answer>") }
            .joinToString("\n")
            .trim()
        message.copy(content = short)
    }
}
