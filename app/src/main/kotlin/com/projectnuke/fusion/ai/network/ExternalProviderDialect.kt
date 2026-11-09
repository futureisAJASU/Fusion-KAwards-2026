package com.projectnuke.fusion.ai.network

import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.ExternalModelVariant
import com.projectnuke.fusion.ai.model.ExternalRequestPatch
import com.projectnuke.fusion.ai.model.ExternalCapabilitySource

enum class ExternalOutputTokenField {
    MAX_TOKENS,
    MAX_COMPLETION_TOKENS,
}

enum class ExternalTemperaturePolicy {
    EMIT,
    OMIT,
}

data class ExternalRequestCompatibility(
    val outputTokenField: ExternalOutputTokenField = ExternalOutputTokenField.MAX_TOKENS,
    val temperaturePolicy: ExternalTemperaturePolicy = ExternalTemperaturePolicy.EMIT,
) {
    companion object {
        val FALLBACK = ExternalRequestCompatibility()
    }
}

enum class ExternalProviderDialect {
    GENERIC_OPENAI_COMPATIBLE,
    OPENAI,
    NVIDIA_NIM,
}

object ExternalProviderDialectResolver {

    fun resolveDialect(providerType: AiProviderType): ExternalProviderDialect {
        return when (providerType) {
            AiProviderType.OPENAI -> ExternalProviderDialect.OPENAI
            AiProviderType.NVIDIA_NIM -> ExternalProviderDialect.NVIDIA_NIM
            AiProviderType.CUSTOM_OPENAI_COMPATIBLE -> ExternalProviderDialect.GENERIC_OPENAI_COMPATIBLE
        }
    }

    /**
     * Resolves a trusted variant into a provider-native request patch.
     * Only writes request fields when the dialect + registry explicitly
     * authorizes it.
     */
    fun resolveRequestPatch(
        dialect: ExternalProviderDialect,
        variant: ExternalModelVariant,
    ): ExternalRequestPatch {
        // Conservative v1: generic dialect ignores unsupported fields.
        // No provider-native reasoning fields are emitted unless explicitly
        // authorized by the registry for the specific dialect.
        return when (dialect) {
            ExternalProviderDialect.GENERIC_OPENAI_COMPATIBLE ->
                if (variant.source == ExternalCapabilitySource.USER_DEFINED) variant.requestPatch else ExternalRequestPatch()
            ExternalProviderDialect.OPENAI,
            ExternalProviderDialect.NVIDIA_NIM -> variant.requestPatch
        }
    }

    fun resolveRequestCompatibility(
        dialect: ExternalProviderDialect,
        compatibility: ExternalRequestCompatibility,
    ): ExternalRequestCompatibility = compatibility
}
