package com.projectnuke.fusion.modelzoo

import java.io.File

/**
 * Narrow K0.5 compatibility policy for the pinned Kanana-2 1.3B target.
 *
 * Production currently receives only the resolved managed path, so the file
 * identity below is explicitly a fallback evidence policy: it does not prove
 * model revision or architecture. The authoritative provenance branch is
 * retained for callers that already have a [FusionModelSpec].
 */
object KananaPromptTemplateCompatibility {
    const val TARGET_REPOSITORY = "kakaocorp/kanana-2-1.3b-instruct"
    const val TARGET_REVISION = "bf4786aa2a1908adce942d53976270132732f720"
    const val EXPECTED_PACKAGE_BYTES = 1_318_321_456L
    const val ACTUAL_MANAGED_K0_FILENAME =
        "fa06696a-cbba-4183-a0bd-991a8c7865d9_kanana-2-1.3b-k0.litertlm"

    private val targetFileName = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_kanana-2-1\\.3b-k0\\.litertlm$",
        RegexOption.IGNORE_CASE,
    )

    private fun hasAuthoritativeSpecIdentity(spec: FusionModelSpec): Boolean =
        spec.id == TARGET_REPOSITORY ||
            spec.huggingFaceModelId == TARGET_REPOSITORY

    fun isK0FallbackFileIdentity(fileName: String, sizeBytes: Long): Boolean =
        sizeBytes == EXPECTED_PACKAGE_BYTES && targetFileName.matches(fileName)

    /** Import/catalog seam: accepts the pinned source basename before UUID packaging. */
    fun isK0ImportFileIdentity(fileName: String, sizeBytes: Long): Boolean =
        sizeBytes == EXPECTED_PACKAGE_BYTES &&
            (fileName.equals("kanana-2-1.3b-k0.litertlm", ignoreCase = true) || targetFileName.matches(fileName))

    // --- Paired diagnostic artifact identities (run 32494833311) ---
    //
    // Bounded diagnostic identities used ONLY to restore equal app-side template
    // treatment for the paired experiment. Size is evidence, not cryptographic
    // provenance. Each identity requires exact canonical basename AND exact
    // corresponding size; cross-pairing (control size with diagnostic basename
    // or vice versa) is rejected.
    const val PAIRED_CONTROL_BASENAME = "kanana-k0-control-dynamic.litertlm"
    const val PAIRED_CONTROL_SIZE = 1_317_271_136L
    const val PAIRED_DIAGNOSTIC_BASENAME = "kanana-k0-diagnostic-weight-only.litertlm"
    const val PAIRED_DIAGNOSTIC_SIZE = 1_317_698_560L

    private val pairedControlFileName = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_${Regex.escape(PAIRED_CONTROL_BASENAME)}$",
        RegexOption.IGNORE_CASE,
    )

    private val pairedDiagnosticFileName = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_${Regex.escape(PAIRED_DIAGNOSTIC_BASENAME)}$",
        RegexOption.IGNORE_CASE,
    )

    fun isPairedControlIdentity(fileName: String, sizeBytes: Long): Boolean =
        sizeBytes == PAIRED_CONTROL_SIZE &&
            (fileName.equals(PAIRED_CONTROL_BASENAME, ignoreCase = true) || pairedControlFileName.matches(fileName))

    fun isPairedDiagnosticIdentity(fileName: String, sizeBytes: Long): Boolean =
        sizeBytes == PAIRED_DIAGNOSTIC_SIZE &&
            (fileName.equals(PAIRED_DIAGNOSTIC_BASENAME, ignoreCase = true) || pairedDiagnosticFileName.matches(fileName))

    enum class OverrideReason {
        AUTHORITATIVE_TARGET,
        HISTORICAL_K0_FALLBACK,
        PAIRED_DYNAMIC_CONTROL,
        PAIRED_WEIGHT_ONLY_DIAGNOSTIC,
        NONE
    }

    fun targetReason(
        spec: FusionModelSpec? = null,
        modelIdentity: String? = null,
        modelPath: String? = null,
    ): OverrideReason {
        if (spec != null && hasAuthoritativeSpecIdentity(spec)) return OverrideReason.AUTHORITATIVE_TARGET
        if (modelIdentity == TARGET_REPOSITORY) return OverrideReason.AUTHORITATIVE_TARGET

        val file = modelPath?.let(::File) ?: return OverrideReason.NONE
        if (!file.isFile) return OverrideReason.NONE

        return overrideReasonForFileIdentity(file.name, file.length())
    }

    /**
     * Pure file-identity to override-reason mapping, separated from filesystem
     * access so paired/historical branches can be proven without a real [File].
     */
    fun overrideReasonForFileIdentity(fileName: String, sizeBytes: Long): OverrideReason =
        when {
            isK0FallbackFileIdentity(fileName, sizeBytes) -> OverrideReason.HISTORICAL_K0_FALLBACK
            isPairedControlIdentity(fileName, sizeBytes) -> OverrideReason.PAIRED_DYNAMIC_CONTROL
            isPairedDiagnosticIdentity(fileName, sizeBytes) -> OverrideReason.PAIRED_WEIGHT_ONLY_DIAGNOSTIC
            else -> OverrideReason.NONE
        }

    fun isTarget(
        spec: FusionModelSpec? = null,
        modelIdentity: String? = null,
        modelPath: String? = null,
    ): Boolean = targetReason(spec, modelIdentity, modelPath) != OverrideReason.NONE
}
