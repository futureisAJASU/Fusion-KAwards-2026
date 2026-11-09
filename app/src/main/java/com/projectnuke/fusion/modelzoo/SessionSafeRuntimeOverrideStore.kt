package com.projectnuke.fusion.modelzoo

import com.projectnuke.fusion.model.GenerationSettings

/** Request-session memory choice; deliberately never written to user settings. */
internal data class SessionSafeRuntimeOverrideKey(
    val modelIdentity: String?,
    val modelPath: String,
    val visionEnabled: Boolean,
    val requestedSettings: GenerationSettings,
)

internal object SessionSafeRuntimeOverrideStore {
    private data class Accepted(
        val key: SessionSafeRuntimeOverrideKey,
        val safeSettings: GenerationSettings,
        val riskLevel: FusionModelMemoryRiskLevel,
        val lowMemory: Boolean,
    )

    private var accepted: Accepted? = null

    @Synchronized
    fun resolve(
        key: SessionSafeRuntimeOverrideKey,
        currentRiskLevel: FusionModelMemoryRiskLevel,
        lowMemory: Boolean,
    ): GenerationSettings? {
        val saved = accepted ?: return null
        if (saved.key != key || saved.riskLevel != currentRiskLevel || saved.lowMemory != lowMemory) {
            accepted = null
            return null
        }
        return saved.safeSettings
    }

    @Synchronized
    fun accept(
        key: SessionSafeRuntimeOverrideKey,
        safeSettings: GenerationSettings,
        riskLevel: FusionModelMemoryRiskLevel,
        lowMemory: Boolean,
    ) {
        accepted = Accepted(key, safeSettings, riskLevel, lowMemory)
    }

    @Synchronized
    fun forget(key: SessionSafeRuntimeOverrideKey) {
        if (accepted?.key == key) accepted = null
    }

    @Synchronized
    fun clear() {
        accepted = null
    }
}
