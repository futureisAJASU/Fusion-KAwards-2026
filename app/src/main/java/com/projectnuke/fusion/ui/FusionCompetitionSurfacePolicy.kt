package com.projectnuke.fusion.ui

import android.content.Context
import android.content.SharedPreferences
import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.util.ManagedModelPathPolicy
import java.io.File

/**
 * C10 competition-surface truth policy.
 *
 * Pure, host-testable UI-visibility/readiness rules. These helpers decide
 * WHAT normal users may see; they never change trust routing, evidence,
 * privacy, RAG, translation, or send-admission behavior.
 */

/** Authoritative local-model readiness truth.
 *
 * Presentation must NEVER equate `selectedManagedPath != null` with
 * `runnable model exists`. The actual runnable file may be missing,
 * corrupt, or the wrong extension, while a null path can coexist with
 * a downloaded built-in model.
 */
data class LocalModelReadinessTruth(
    val selectedDisplayName: String,
    val selectedManagedPath: String?,
    val actualManagedRunnablePath: String?,
    val selectedBuiltInAvailable: Boolean,
    val isRunnable: Boolean,
    val finalRunnablePath: String?,
)

fun resolveLocalModelReadinessTruth(
    selectedDisplayName: String,
    selectedManagedPath: String?,
    actualManagedRunnablePath: String?,
    selectedBuiltInAvailable: Boolean,
): LocalModelReadinessTruth {
    val runnable = actualManagedRunnablePath != null || selectedBuiltInAvailable
    val finalPath = actualManagedRunnablePath
        ?: if (selectedBuiltInAvailable) "built-in:$selectedDisplayName" else null
    return LocalModelReadinessTruth(
        selectedDisplayName = selectedDisplayName,
        selectedManagedPath = selectedManagedPath,
        actualManagedRunnablePath = actualManagedRunnablePath,
        selectedBuiltInAvailable = selectedBuiltInAvailable,
        isRunnable = runnable,
        finalRunnablePath = finalPath,
    )
}

/** The single production resolver used by both Chat and Settings. Built-in
 * identity comes from the model catalog; no surface owns a second filename
 * map. Every call reads the current backing files, so a refresh cannot retain
 * a stale availability result.
 */
fun resolveLocalModelReadinessTruth(
    context: Context,
    prefs: SharedPreferences,
    selectedDisplayName: String = prefs.getString(LocalModelSelectedPreference, DefaultLocalModelDisplayName) ?: DefaultLocalModelDisplayName,
    selectedManagedPath: String? = prefs.getString(LocalModelSelectedPathPreference, null),
): LocalModelReadinessTruth {
    val managedRunnable = ManagedModelPathPolicy.resolveRunnableModel(context, selectedManagedPath)?.absolutePath
    val builtInAvailable = managedRunnable == null &&
        resolveRunnableSelectedLocalModelPath(context, selectedDisplayName, selectedManagedPath) != null
    return resolveLocalModelReadinessTruth(
        selectedDisplayName = selectedDisplayName,
        selectedManagedPath = selectedManagedPath,
        actualManagedRunnablePath = managedRunnable,
        selectedBuiltInAvailable = builtInAvailable,
    )
}

/** Actual executable file shared by Chat and standalone Translation. No display identity is executable. */
fun resolveRunnableSelectedLocalModelPath(
    context: Context,
    selectedDisplayName: String,
    selectedManagedPath: String?,
): String? {
    ManagedModelPathPolicy.resolveRunnableModel(context, selectedManagedPath)?.let { return it.absolutePath }
    val builtIn = FusionModelCatalog.builtIn.firstOrNull {
        it.displayName == selectedDisplayName && it.canRunLocalRuntime && !it.fileName.isNullOrBlank()
    } ?: return null
    val candidate = File(ManagedModelPathPolicy.getModelDirectory(context), builtIn.fileName!!)
    return ManagedModelPathPolicy.resolveRunnableModel(context, candidate.absolutePath)?.absolutePath
}

const val LocalModelSelectedPreference = "selected_model"
const val LocalModelSelectedPathPreference = "selected_model_path"

/** Selection evidence is durable even while its file is temporarily unavailable. */
fun readSelectedModelPathEvidence(prefs: SharedPreferences): String? =
    prefs.getString(LocalModelSelectedPathPreference, null)
val DefaultLocalModelDisplayName: String
    get() = FusionModelCatalog.builtIn.firstOrNull { it.canRunLocalRuntime }?.displayName ?: "모델 선택 필요"

// -- App version truth --------------------------------------------------
// The single visible version authority is the installed package/build
// metadata already represented by AppInfoSummary (versionName/versionCode).
// No hardcoded marketing literal may appear in App Info.
fun resolveAppInfoTitle(versionName: String): String {
    val normalized = versionName.trim().ifBlank { "unknown" }
    return "Fusion $normalized"
}

// -- Local-model readiness presentation ------------------------------------
// The visible selected-model display name (e.g. "Gemma 4 E2B-it") is only a
// preference default; it does not imply a runnable model is installed.
// Presentation must distinguish the display name from actual readiness.
// Send-admission/trust-routing semantics are intentionally unchanged here.
const val LocalModelSelectionRequiredLabel = "모델 선택 필요"

fun resolveLocalModelChipLabel(displayName: String, hasRunnableModel: Boolean): String =
    if (hasRunnableModel) displayName else LocalModelSelectionRequiredLabel
