package com.projectnuke.fusion.model

/**
 * Hybrid settings precedence for automatic model routing (A -> B).
 *
 * Field ownership is explicit and exhaustive for [GenerationSettings] plus
 * the snapshot-level runtime fields carried alongside it:
 *
 * REQUEST_SEMANTIC (preserved from the original frozen request):
 * - temperature, topP, topK: sampling intent, model-independent.
 * - maxTokens: frozen request output budget; B's stored sampling default
 *   must not overwrite explicit request intent.
 * - reasoningBudgetTokens: reasoning effort intent.
 * - reasoningEnabled intent: preserved, gated by final-model capability
 *   (see ROUTING_DERIVED).
 *
 * FINAL_MODEL_PROFILE (re-resolved for final model B):
 * - kvCacheCapacityTokens: KV/memory validity depends on B.
 * - speculativeDecodingEnabled: per-model runtime tuning.
 * - contextMode, compactPolicy: drive B's KV/memory planning.
 *
 * ROUTING_DERIVED (computed for B at route time):
 * - accelerator: routing decision's backend choice for B.
 * - reasoningEnabled final value: request intent AND B capability.
 *
 * No field is left unclassified; adding a field to [GenerationSettings]
 * without updating this policy is a compile-review obligation (all fields
 * are copied explicitly, never via blind whole-object reuse).
 */
internal enum class RoutedSettingOwnership {
    REQUEST_SEMANTIC,
    FINAL_MODEL_PROFILE,
    ROUTING_DERIVED,
}

internal data class RoutedRuntimeSettings(
    val settings: GenerationSettings,
    val reasoningEnabled: Boolean,
    val contextMode: LocalContextMode,
    val compactPolicy: LocalCompactPolicy,
)

internal object AutoRoutedSettingsPolicy {
    fun resolve(
        requestSettings: GenerationSettings,
        requestReasoningEnabled: Boolean,
        finalModelProfile: EffectiveLocalModelProfile,
        decisionAccelerator: AcceleratorMode,
        finalReasoningCapable: Boolean,
    ): RoutedRuntimeSettings {
        return RoutedRuntimeSettings(
            settings = GenerationSettings(
                maxTokens = requestSettings.maxTokens,
                kvCacheCapacityTokens = finalModelProfile.generationSettings.kvCacheCapacityTokens,
                topK = requestSettings.topK,
                topP = requestSettings.topP,
                temperature = requestSettings.temperature,
                accelerator = decisionAccelerator,
                reasoningBudgetTokens = requestSettings.reasoningBudgetTokens,
                speculativeDecodingEnabled =
                    finalModelProfile.generationSettings.speculativeDecodingEnabled,
            ),
            reasoningEnabled = requestReasoningEnabled && finalReasoningCapable,
            contextMode = finalModelProfile.contextMode,
            compactPolicy = finalModelProfile.compactPolicy,
        )
    }
}
