package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.RequestedEngineProfile
import java.security.MessageDigest

/**
 * Deterministic CPU/GPU comparison contract for the next physical gate.
 * This describes identical runtime inputs; it does not run inference or infer
 * GPU correctness from successful Engine construction.
 */
data class FusionCpuGpuDifferentialPlan(
    val modelPath: String,
    val modelIdentity: String?,
    /** SHA-256 identity of the exact prompt; raw prompt text is never logged. */
    val promptSha256: String,
    val promptTemplateIdentity: String,
    val kvCapacityTokens: Int = 2048,
    val maxOutputTokens: Int = 256,
) {
    val conversationOptions: ConversationOptions = ConversationOptions(
        maxOutputToken = maxOutputTokens,
        topK = 1,
        topP = 1f,
        temperature = 0f,
        seed = 0,
    )

    fun profile(accelerator: AcceleratorMode): RequestedEngineProfile = RequestedEngineProfile(
        modelPath = modelPath,
        accelerator = accelerator,
        mtpRequested = false,
        kvCacheCapacityTokens = kvCapacityTokens,
        enableVisionBackend = false,
        modelIdentity = modelIdentity,
    )
}

data class FusionCpuGpuDifferentialObservation(
    val actualBackend: String,
    val nonEmptyOutput: Boolean,
    val outputLength: Int,
    val normalizedOutputSha256: String,
    val benchmarkStats: GenerationBenchmarkStats? = null,
    val runtimeDiagnostics: String? = null,
)

fun observeFusionCpuGpuDifferentialOutput(
    actualBackend: String,
    output: String,
    benchmarkStats: GenerationBenchmarkStats? = null,
    runtimeDiagnostics: String? = null,
): FusionCpuGpuDifferentialObservation {
    val normalized = output.trim().replace(Regex("\\s+"), " ")
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalized.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02X".format(byte) }
    return FusionCpuGpuDifferentialObservation(
        actualBackend = actualBackend,
        nonEmptyOutput = normalized.isNotEmpty(),
        outputLength = normalized.length,
        normalizedOutputSha256 = digest,
        benchmarkStats = benchmarkStats,
        runtimeDiagnostics = runtimeDiagnostics,
    )
}
