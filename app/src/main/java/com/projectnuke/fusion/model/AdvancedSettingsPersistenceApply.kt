package com.projectnuke.fusion.model

/** Ordered durable writes performed when the Advanced Settings dialog is applied. */
enum class AdvancedSettingsPersistenceStage {
    PERFORMANCE,
    MODEL_PROFILE,
    MODEL_OVERRIDE_DELETION,
    SEARCH_STRATEGY,
    COMPATIBILITY_SETTINGS,
    WEB_SEARCH_SELECTION,
}

data class AdvancedSettingsPersistenceStep(
    val stage: AdvancedSettingsPersistenceStage,
    val durabilityUncertainOnFailure: () -> Boolean = { false },
    val persist: () -> Boolean,
)

data class AdvancedSettingsPersistenceReport(
    val completedStages: List<AdvancedSettingsPersistenceStage>,
    val failedStage: AdvancedSettingsPersistenceStage?,
    val durabilityUncertain: Boolean = false,
) {
    val succeeded: Boolean get() = failedStage == null
    val partiallyApplied: Boolean get() = completedStages.isNotEmpty() && failedStage != null
    val needsReview: Boolean get() = partiallyApplied || durabilityUncertain
}

/** Executes the real ordered persistence calls and reports exactly what returned success. */
object AdvancedSettingsPersistenceApply {
    fun persist(steps: List<AdvancedSettingsPersistenceStep>): AdvancedSettingsPersistenceReport {
        val completed = mutableListOf<AdvancedSettingsPersistenceStage>()
        for (step in steps) {
            val saved = try {
                step.persist()
            } catch (_: Exception) {
                false
            }
            if (!saved) {
                val uncertain = runCatching(step.durabilityUncertainOnFailure).getOrDefault(true)
                return AdvancedSettingsPersistenceReport(completed.toList(), step.stage, uncertain)
            }
            completed += step.stage
        }
        return AdvancedSettingsPersistenceReport(completed.toList(), null)
    }
}
