package com.projectnuke.fusion.ui

import android.content.Context
import android.content.SharedPreferences
import com.projectnuke.fusion.model.EffectiveLocalModelProfile
import com.projectnuke.fusion.model.resolveEffectiveLocalModelProfile
import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.modelzoo.FusionModelSpec
import java.util.Locale

/**
 * READ-ONLY shared diagnostics snapshot for local-model presentation.
 *
 * Combines the authoritative production truths in one value so Health Check
 * and Status Dashboard can never disagree or rebuild their own:
 *  - selected-model identity (display name, stored path, catalog spec),
 *  - authoritative local-model readiness ([resolveLocalModelReadinessTruth]),
 *  - the effective local-model profile actual generation would use
 *    ([resolveEffectiveLocalModelProfile]).
 *
 * This object creates NO new source of truth and owns no generation state.
 * Web-search enablement is not part of the local-model profile, so it is
 * carried through from its existing global/user preference unchanged.
 *
 * [hasExplicitStoredModelName] is retained only as persistence metadata: it
 * records whether an explicit `selected_model` preference key was persisted. It
 * must NEVER gate readiness presentation, because production Chat falls back to
 * [DefaultLocalModelDisplayName] when the key is absent, making that fallback an
 * effective selection. Runtime-ready truth is reported exclusively via
 * [readiness] and [selectedSpec].
 */
data class FusionRuntimeDiagnosticsSnapshot(
    val selectedModelName: String,
    val selectedModelPath: String?,
    val hasExplicitStoredModelName: Boolean,
    val selectedSpec: FusionModelSpec?,
    val readiness: LocalModelReadinessTruth,
    val effectiveProfile: EffectiveLocalModelProfile,
    val webSearchEnabled: Boolean,
)

fun resolveFusionRuntimeDiagnosticsSnapshot(
    context: Context,
    prefs: SharedPreferences,
): FusionRuntimeDiagnosticsSnapshot {
    val storedName = prefs.getString(LocalModelSelectedPreference, null)
    val selectedModelName = storedName?.takeIf { it.isNotBlank() } ?: DefaultLocalModelDisplayName
    val selectedModelPath = prefs.getString(LocalModelSelectedPathPreference, null)
    return FusionRuntimeDiagnosticsSnapshot(
        selectedModelName = selectedModelName,
        selectedModelPath = selectedModelPath,
        hasExplicitStoredModelName = !storedName.isNullOrBlank(),
        selectedSpec = resolveDiagnosticsSelectedModelSpec(context, selectedModelName, selectedModelPath),
        readiness = resolveLocalModelReadinessTruth(context, prefs, selectedModelName, selectedModelPath),
        effectiveProfile = resolveEffectiveLocalModelProfile(context, prefs, selectedModelName, selectedModelPath),
        webSearchEnabled = prefs.getBoolean(DiagnosticsWebSearchPreference, false),
    )
}

private const val DiagnosticsWebSearchPreference = "web_search_enabled"

/** Installed-package version truth shared by every diagnostics surface.
 *  The single visible version authority is the installed package/build
 *  metadata; no surface keeps a second copy of this read. */
data class FusionDiagnosticsAppInfo(
    val versionName: String,
    val versionCode: String,
)

fun resolveFusionDiagnosticsAppInfo(context: Context): FusionDiagnosticsAppInfo {
    val info = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    }.getOrNull()
    val versionName = info?.versionName ?: "현재 버전 정보를 확인할 수 없습니다."
    val versionCode = if (info != null) {
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString()
        else @Suppress("DEPRECATION") info.versionCode.toString()
    } else {
        "-"
    }
    return FusionDiagnosticsAppInfo(versionName = versionName, versionCode = versionCode)
}

/** Selected catalog spec lookup shared by diagnostics surfaces. */
internal fun resolveDiagnosticsSelectedModelSpec(
    context: Context,
    selectedModelName: String,
    selectedModelPath: String?,
): FusionModelSpec? {
    if (selectedModelName.isBlank() && selectedModelPath.isNullOrBlank()) return null
    return FusionModelCatalog.all(context).firstOrNull {
        it.displayName == selectedModelName || (!selectedModelPath.isNullOrBlank() && it.localPath == selectedModelPath)
    } ?: FusionModelCatalog.findByNameOrId(context, selectedModelName)
}

/**
 * Canonical effective-settings lines shared by Health Check and Status
 * Dashboard. Pure presentation over an already-resolved
 * [EffectiveLocalModelProfile]; it never re-reads preferences and can never
 * present a stored/global value as effective when a per-model override wins.
 */
object FusionRuntimeDiagnosticsFormat {

    fun effectiveLocalModelSettingLines(profile: EffectiveLocalModelProfile): List<String> = listOf(
        "가속기: ${profile.generationSettings.accelerator.name}",
        "MTP: ${onOff(profile.generationSettings.speculativeDecodingEnabled == true)}",
        "maxTokens: ${profile.generationSettings.maxTokens}",
        "temperature: ${formatFloat(profile.generationSettings.temperature)}",
        "topK: ${profile.generationSettings.topK}",
        "topP: ${formatFloat(profile.generationSettings.topP)}",
        "추론: ${onOff(profile.reasoningEnabled)}",
        "추론 예산: ${profile.generationSettings.reasoningBudgetTokens}",
        "KV 캐시: ${profile.effectiveKvCapacityTokens} tokens",
    )

    fun effectiveGenerationSummaryLine(
        profile: EffectiveLocalModelProfile,
        webSearchEnabled: Boolean,
    ): String =
        "최대 출력 ${profile.generationSettings.maxTokens} · " +
            "온도 ${formatFloat(profile.generationSettings.temperature)} · " +
            "Top-K ${profile.generationSettings.topK} · " +
            "Top-P ${formatFloat(profile.generationSettings.topP)} · " +
            "MTP=${onOff(profile.generationSettings.speculativeDecodingEnabled == true)} · " +
            "추론=${onOff(profile.reasoningEnabled)} · " +
            "웹 검색=${onOff(webSearchEnabled)}"

    fun onOff(value: Boolean): String = if (value) "켜짐" else "꺼짐"

    fun formatFloat(value: Float): String = String.format(Locale.US, "%.2f", value)
}
