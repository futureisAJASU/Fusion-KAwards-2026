package com.projectnuke.fusion.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "benchmark_results")
data class BenchmarkResultEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val createdAt: Long,
    val modelName: String,
    val modelPath: String?,
    /** Requested accelerator mode (AUTO/GPU/CPU) chosen by the user. */
    val accelerator: String,
    /**
     * Text backend the candidate ladder actually selected. Renamed from
     * `actualBackend`. Nullable for legacy rows saved before Phase 7.
     */
    val selectedTextBackend: String?,
    /** Vision backend selected for multimodal runs; null for text-only. */
    val selectedVisionBackend: String?,
    /** Sampler backend; UNKNOWN unless a stable LiteRT-LM API reports it. */
    val samplerBackend: String,
    /**
     * Whether the user *requested* MTP. Renamed from `mtpEnabled`; the field
     * captures the user setting, not the resulting runtime applied state.
     */
    val mtpRequested: Boolean,
    /** Effective MTP runtime status (see [com.projectnuke.fusion.llm.MtpRuntimeStatus]). */
    val mtpStatus: String,
    /** CSV of [com.projectnuke.fusion.llm.FallbackReason] codes captured during selection. */
    val fallbackEventCodes: String?,
    /** True when an Engine initialized with the MTP flag applied. */
    val initializedWithMtp: Boolean,
    /** Native first-token latency in seconds, when reported. */
    val nativeTtftSeconds: Double?,
    /** Native prefill tokens/sec, when reported. */
    val nativePrefillTokensPerSecond: Double?,
    /** Native decode tokens/sec, when reported. */
    val nativeDecodeTokensPerSecond: Double?,
    /** Native prefill token count, when reported. */
    val nativePrefillTokenCount: Int?,
    /** Native decode token count, when reported. */
    val nativeDecodeTokenCount: Int?,
    /** Native init time in seconds, when reported. */
    val nativeInitTimeSeconds: Double?,
    val maxTokens: Int,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val reasoningEnabled: Boolean,
    val webSearchEnabled: Boolean,
    val promptLabel: String,
    val promptText: String,
    val modelLoadingMs: Long?,
    val firstTokenLatencyMs: Long?,
    val totalGenerationMs: Long,
    val estimatedOutputTokens: Int,
    val totalTokensPerSecond: Float,
    val decodeTokensPerSecond: Float?,
    val success: Boolean,
    val errorMessage: String?,
    val appVersion: String?,
    val deviceModel: String?,
    val androidVersion: String?
)
