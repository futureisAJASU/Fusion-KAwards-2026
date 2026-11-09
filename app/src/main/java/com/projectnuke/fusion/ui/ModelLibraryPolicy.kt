package com.projectnuke.fusion.ui

import android.content.Context
import android.content.SharedPreferences
import com.projectnuke.fusion.model.LocalModelSettingsProfileStore
import com.projectnuke.fusion.model.applyRecommendedSimpleReasoning
import com.projectnuke.fusion.model.resolveLocalSettingsEditState
import com.projectnuke.fusion.model.writeSimpleModelOverride
import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.modelzoo.FusionModelSpec
import com.projectnuke.fusion.modelzoo.ModelAvailability
import java.io.File

internal fun isCatalogModelAvailable(context: Context, spec: FusionModelSpec): Boolean {
    if (spec.externallyReferenced && spec.localPath.isNullOrBlank()) return false
    spec.localPath?.let { return File(it).exists() && spec.canRunLocalRuntime }
    spec.fileName?.let { return File(getModelDirectory(context), it).exists() && spec.canRunLocalRuntime }
    return spec.availability == ModelAvailability.READY
}

internal fun applyDeviceAwareRecommendedSettings(
    context: Context,
    prefs: SharedPreferences,
    spec: FusionModelSpec
): Boolean {
    val editState = resolveLocalSettingsEditState(context, prefs, spec.id, spec.localPath)
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
    val memoryInfo = android.app.ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
    val stableCap = com.projectnuke.fusion.model.stableRecommendedOutputCap(
        spec = spec,
        totalRamBytes = memoryInfo.totalMem,
        isLowRamDevice = activityManager?.isLowRamDevice == true,
    )
    val recommendedLength = com.projectnuke.fusion.model.recommendedSimpleLengthForCap(stableCap)
    return writeSimpleModelOverride(
        store = LocalModelSettingsProfileStore(prefs),
        modelId = spec.id,
        current = editState.buffer.model,
    ) { values ->
        val styled = com.projectnuke.fusion.model.applySimpleStyle(
            values,
            com.projectnuke.fusion.model.SimpleResponseStyle.BALANCED,
            spec.family,
        )
        val withLength = com.projectnuke.fusion.model.applySimpleLength(styled, recommendedLength)
        applyRecommendedSimpleReasoning(
            values = withLength,
            supportsReasoning = FusionModelCatalog.reasoningCapabilityAvailable(spec),
            recommendedReasoningEnabled = spec.recommendedReasoningEnabled,
        )
    }
}
