package com.projectnuke.fusion.util

import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.modelzoo.LiteRtLmCapabilities
import com.projectnuke.fusion.modelzoo.LiteRtLmPackageValidator
import java.io.File

/**
 * Production MTP policy based on validated package capabilities.
 * Replaces display-name heuristics with typed capability checks.
 */
internal object MtpPolicyProduction {

    /**
     * Determines if MTP should be enabled for a given model and settings.
     *
     * Rules:
     * - Explicit user OFF always wins
     * - Explicit ON on a package without drafter produces UNSUPPORTED (handled by caller)
     * - Automatic enablement requires validated drafter capability
     * - Renamed packages retain the same behavior (capability-based, not name-based)
     * - Arbitrary Gemma-like names cannot enable MTP
     */
    fun resolveEffectiveMtpSetting(
        modelPath: String,
        settings: GenerationSettings
    ): Boolean {
        // Explicit user preference always wins
        if (settings.speculativeDecodingEnabled == false) return false
        if (settings.speculativeDecodingEnabled == true) return true

        // Automatic: check validated package capabilities
        val capabilities = LiteRtLmPackageValidator.capabilities(File(modelPath))
        if (!capabilities.hasDrafter) return false

        // Drafter exists, apply accelerator-based defaults
        return when (settings.accelerator) {
            AcceleratorMode.GPU, AcceleratorMode.AUTO -> true
            AcceleratorMode.CPU -> false // Conservative default for CPU
        }
    }

    /**
     * Checks if the model at [modelPath] supports MTP based on validated capabilities.
     */
    fun isMtpSupported(modelPath: String): Boolean {
        val capabilities = LiteRtLmPackageValidator.capabilities(File(modelPath))
        return capabilities.hasDrafter
    }

    /**
     * Gets the default MTP setting for a model based on its validated capabilities and accelerator.
     * This is used when the user hasn't explicitly set a preference.
     */
    fun getDefaultMtpSetting(modelPath: String, accelerator: AcceleratorMode): Boolean {
        val capabilities = LiteRtLmPackageValidator.capabilities(File(modelPath))
        if (!capabilities.hasDrafter) return false

        return when (accelerator) {
            AcceleratorMode.GPU, AcceleratorMode.AUTO -> true
            AcceleratorMode.CPU -> false
        }
    }
}