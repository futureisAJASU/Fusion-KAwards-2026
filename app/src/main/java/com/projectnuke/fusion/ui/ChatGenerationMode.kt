package com.projectnuke.fusion.ui

internal enum class ChatGenerationMode {
    LOCAL_MODEL,
    EXTERNAL_AI_API
}

internal fun presentedChatReasoningEnabled(
    mode: ChatGenerationMode,
    externalPreference: Boolean,
    effectiveLocalReasoning: Boolean,
): Boolean = if (mode == ChatGenerationMode.LOCAL_MODEL) effectiveLocalReasoning else externalPreference
