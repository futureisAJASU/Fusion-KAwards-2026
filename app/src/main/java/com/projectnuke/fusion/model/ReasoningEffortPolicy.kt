package com.projectnuke.fusion.model

/**
 * UI projection over the durable reasoningEnabled/reasoningBudgetTokens pair.
 * This type is not persisted and does not represent a second preference.
 */
enum class ReasoningEffortPreset(
    val label: String,
    val budgetTokens: Int,
) {
    LOW("낮음", 512),
    MEDIUM("중간", 1024),
    HIGH("높음", 2048),
    XHIGH("매우 높음", 4096),
}

enum class ReasoningEffortSelection(val label: String) {
    OFF("꺼짐"),
    LOW("낮음"),
    MEDIUM("중간"),
    HIGH("높음"),
    XHIGH("매우 높음"),
    CUSTOM("사용자 설정"),
}

fun ReasoningEffortPreset.toSelection(): ReasoningEffortSelection = when (this) {
    ReasoningEffortPreset.LOW -> ReasoningEffortSelection.LOW
    ReasoningEffortPreset.MEDIUM -> ReasoningEffortSelection.MEDIUM
    ReasoningEffortPreset.HIGH -> ReasoningEffortSelection.HIGH
    ReasoningEffortPreset.XHIGH -> ReasoningEffortSelection.XHIGH
}

fun detectReasoningEffort(
    reasoningEnabled: Boolean,
    reasoningBudgetTokens: Int,
): ReasoningEffortSelection {
    if (!reasoningEnabled) return ReasoningEffortSelection.OFF
    return when (reasoningBudgetTokens) {
        ReasoningEffortPreset.LOW.budgetTokens -> ReasoningEffortSelection.LOW
        ReasoningEffortPreset.MEDIUM.budgetTokens -> ReasoningEffortSelection.MEDIUM
        ReasoningEffortPreset.HIGH.budgetTokens -> ReasoningEffortSelection.HIGH
        ReasoningEffortPreset.XHIGH.budgetTokens -> ReasoningEffortSelection.XHIGH
        else -> ReasoningEffortSelection.CUSTOM
    }
}

fun detectReasoningEffort(values: LocalSettingsEditValues): ReasoningEffortSelection =
    detectReasoningEffort(values.reasoningEnabled, values.settings.reasoningBudgetTokens)

/**
 * Applies only the reasoning truth. Output length, sampling, accelerator,
 * context, MTP, and compact settings are intentionally preserved.
 */
fun applyReasoningEffort(
    values: LocalSettingsEditValues,
    preset: ReasoningEffortPreset,
    supportsReasoning: Boolean = true,
): LocalSettingsEditValues? {
    if (!supportsReasoning) return null
    return values.copy(
        reasoningEnabled = true,
        settings = values.settings.copy(reasoningBudgetTokens = preset.budgetTokens),
    )
}

fun setReasoningEnabled(
    values: LocalSettingsEditValues,
    enabled: Boolean,
): LocalSettingsEditValues = values.copy(reasoningEnabled = enabled)

fun reasoningEffortDisplayLabel(values: LocalSettingsEditValues): String =
    when (val selection = detectReasoningEffort(values)) {
        ReasoningEffortSelection.OFF -> selection.label
        ReasoningEffortSelection.CUSTOM -> "${selection.label} · ${values.settings.reasoningBudgetTokens}"
        else -> selection.label
    }
