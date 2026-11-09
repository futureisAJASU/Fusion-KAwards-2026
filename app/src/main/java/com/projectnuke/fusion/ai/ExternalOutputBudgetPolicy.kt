package com.projectnuke.fusion.ai

import com.projectnuke.fusion.ai.model.AiProviderConfig

internal fun effectiveExternalMaxOutputTokens(provider: AiProviderConfig?): Int =
    provider?.maxTokens ?: ExternalAiChatRunner.COMPATIBILITY_MAX_OUTPUT_TOKENS

internal fun effectiveExternalMaxOutputTokens(
    providerId: String?,
    providers: List<AiProviderConfig>,
): Int = effectiveExternalMaxOutputTokens(providerId?.let { id -> providers.firstOrNull { it.id == id } })
