package com.projectnuke.fusion.ai.model

enum class ExternalReasoningEffort {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX,
}

enum class ReasoningEffortMapping {
    FLAT_REASONING_EFFORT,
    NESTED_REASONING_EFFORT,
}

enum class ExternalCapabilitySource {
    FUSION_VERIFIED,
    PROVIDER_ADVERTISED,
    USER_DEFINED,
}

data class ExternalRequestPatch(
    val reasoningEffort: ExternalReasoningEffort? = null,
    val customReasoningEffort: String? = null,
    val reasoningEffortMapping: ReasoningEffortMapping = ReasoningEffortMapping.FLAT_REASONING_EFFORT,
) {
    init {
        require(reasoningEffort == null || customReasoningEffort == null)
        require(customReasoningEffort == null || Regex("[A-Za-z0-9_-]{1,32}").matches(customReasoningEffort))
    }
}

data class UserExternalVariant(
    val id: String,
    val providerId: String,
    val modelId: String,
    val label: String,
    val patch: UserVariantPatch,
)

sealed interface UserVariantPatch {
    data class ReasoningEffort(
        val value: String,
        val mapping: ReasoningEffortMapping,
    ) : UserVariantPatch
}

data class ExternalModelVariant(
    val id: String,
    val label: String,
    val requestPatch: ExternalRequestPatch,
    val source: ExternalCapabilitySource = ExternalCapabilitySource.FUSION_VERIFIED,
    val verifiedAt: String? = null,
    val providerNote: String? = null,
)

typealias ResolvedExternalModelVariant = ExternalModelVariant
