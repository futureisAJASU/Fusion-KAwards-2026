package com.projectnuke.fusion.ai.provider

import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType

object AiProviderPresets {
    val OpenAi = AiProviderConfig(
        id = "openai",
        type = AiProviderType.OPENAI,
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        modelId = "gpt-5.5",
        apiKeySecretId = null
    )

    val NvidiaNim = AiProviderConfig(
        id = "nvidia_nim",
        type = AiProviderType.NVIDIA_NIM,
        displayName = "NVIDIA NIM",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        modelId = "qwen/qwen3-coder-480b-a35b-instruct",
        apiKeySecretId = null
    )

    val CustomOpenAiCompatible = AiProviderConfig(
        id = "custom_openai_compatible",
        type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
        displayName = "Custom OpenAI-Compatible",
        baseUrl = "",
        modelId = "",
        apiKeySecretId = null
    )

    val defaults = listOf(OpenAi, NvidiaNim, CustomOpenAiCompatible)
}
