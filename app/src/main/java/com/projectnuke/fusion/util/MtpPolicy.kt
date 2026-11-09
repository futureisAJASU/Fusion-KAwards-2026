package com.projectnuke.fusion.util

import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.GenerationSettings

internal fun isMtpCapableModelName(modelName: String): Boolean {
    val lower = modelName.lowercase()
    return "gemma 4" in lower || "gemma-4" in lower
}

internal fun isGemma4E4BModel(modelName: String): Boolean {
    return modelName.contains("E4B", ignoreCase = true)
}

internal fun isGemma4E2BModel(modelName: String): Boolean {
    return modelName.contains("E2B", ignoreCase = true)
}

internal fun defaultSpeculativeDecodingEnabled(
    modelName: String,
    accelerator: AcceleratorMode
): Boolean {
    if (!isMtpCapableModelName(modelName)) return false

    return when (accelerator) {
        AcceleratorMode.GPU,
        AcceleratorMode.AUTO -> true

        AcceleratorMode.CPU -> isGemma4E4BModel(modelName) && !isGemma4E2BModel(modelName)
    }
}

internal fun resolveEffectiveMtpSetting(
    modelName: String,
    settings: GenerationSettings
): Boolean {
    if (!isMtpCapableModelName(modelName)) return false

    return settings.speculativeDecodingEnabled
        ?: defaultSpeculativeDecodingEnabled(
            modelName = modelName,
            accelerator = settings.accelerator
        )
}
