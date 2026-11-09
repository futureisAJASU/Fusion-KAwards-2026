package com.projectnuke.fusion.model

import android.content.SharedPreferences

/**
 * Compatibility bounds for persisted local-generation settings. These are
 * validity bounds, not recommendation or preset values.
 */
object LocalGenerationSettingBounds {
    const val TOP_K_MIN = 1
    const val TOP_K_MAX = 100
    const val REASONING_BUDGET_MIN = 1
    const val REASONING_BUDGET_MAX = 8192

    fun clampTopK(value: Int): Int = value.coerceIn(TOP_K_MIN, TOP_K_MAX)

    fun clampReasoningBudget(value: Int): Int =
        value.coerceIn(REASONING_BUDGET_MIN, REASONING_BUDGET_MAX)

    fun parseTopK(text: String, fallback: Int): Int =
        text.toIntOrNull()?.let(::clampTopK) ?: clampTopK(fallback)

    fun parseReasoningBudget(text: String, fallback: Int): Int =
        text.toIntOrNull()?.let(::clampReasoningBudget) ?: clampReasoningBudget(fallback)
}

/**
 * Unified local-generation settings policy consumed by Chat, Benchmark, and A/B.
 *
 * Replaces the duplicated per-screen `load*SettingsFromPrefs` helpers with a
 * single [fromPrefs] entry point so all three surfaces use the same
 * preference keys, legacy migration rules, and KV capacity derivation.
 *
 * Authoritative keys (written by all production code):
 * - [KEY_MAX_TOKENS] -> "generation_max_output_tokens"
 * - [KEY_KV_CACHE] -> "engine_kv_cache_capacity_tokens"
 *
 * Legacy keys (read-only for one-time migration):
 * - "max_tokens"
 * - "kv_cache_capacity_tokens"
 *
 * Migration runs once per key when the authoritative key is absent
 * and the legacy key exists. After migration, the legacy key is NOT
 * updated by policy — only the authoritative key is written.
 */
object LocalGenerationSettingsPolicy {

    // ── Authoritative preference keys ────────────────────────────────────

    const val KEY_MAX_TOKENS = "generation_max_output_tokens"
    const val KEY_KV_CACHE = "engine_kv_cache_capacity_tokens"

    // Legacy keys for one-time compatibility migration only.
    private const val LEGACY_MAX_TOKENS = "max_tokens"
    private const val LEGACY_KV_CACHE = "kv_cache_capacity_tokens"

    // Other settings keys (non-migrated, shared).
    const val KEY_TOP_K = "top_k"
    const val KEY_TOP_P = "top_p"
    const val KEY_TEMPERATURE = "temperature"
    const val KEY_ACCELERATOR = "accelerator"
    const val KEY_REASONING_BUDGET = "reasoning_budget_tokens"
    const val KEY_SPECULATIVE_DECODING = "speculative_decoding_enabled"
    const val KEY_SETTINGS_MIGRATION_STATE = "local_settings_migration_state"

    private const val MIGRATION_FRESH = "fresh"
    private const val MIGRATION_LEGACY = "legacy"

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Reads generation settings from shared preferences with the consolidated
     * output/KV policy and legacy-key migration. Should be the single source
     * for all three surfaces (Chat, Benchmark, A/B).
     */
    fun fromPrefs(
        prefs: SharedPreferences,
        materializeMigration: Boolean = true,
    ): GenerationSettings {
        val hadPersistedSettings = hasPersistedSettings(prefs)
        val migrationState = establishMigrationState(prefs, hadPersistedSettings, materializeMigration)
        val legacyMigration = migrationState == MIGRATION_LEGACY
        val materializeMissingKeys = materializeMigration && legacyMigration
        val maxTokens = readMaxTokens(prefs, materializeMissingKeys)
        val kvCache = readKvCache(prefs, maxTokens, legacyMigration, materializeMissingKeys)
        val accel = readAccelerator(prefs)

        return GenerationSettings(
            maxTokens = maxTokens,
            kvCacheCapacityTokens = kvCache,
            topK = LocalGenerationSettingBounds.clampTopK(prefs.getInt(KEY_TOP_K, 64)),
            topP = LocalGenerationFloatSettings.topPOrDefault(
                prefs.getFloat(KEY_TOP_P, LocalGenerationFloatSettings.DEFAULT_TOP_P),
            ),
            temperature = LocalGenerationFloatSettings.temperatureOrDefault(
                prefs.getFloat(KEY_TEMPERATURE, LocalGenerationFloatSettings.DEFAULT_TEMPERATURE),
            ),
            accelerator = accel,
            reasoningBudgetTokens = LocalGenerationSettingBounds.clampReasoningBudget(
                prefs.getInt(KEY_REASONING_BUDGET, 512)
            ),
            speculativeDecodingEnabled = if (prefs.contains(KEY_SPECULATIVE_DECODING)) {
                prefs.getBoolean(KEY_SPECULATIVE_DECODING, false)
            } else null
        )
    }

    // ── Output / KV policy ─────────────────────────────────────────────

    /**
     * Returns the effective output limit for a migration run on a low-memory
     * device. Benchmarks on 8-GB-class devices should use a reduced output cap
     * to avoid OOM during assessment.
     */
    fun lowMemoryOutputCap(): Int = 1024

    /**
     * Returns the reduced KV cache capacity token count for memory assessment.
     * Delegates to [com.projectnuke.fusion.model.KvCacheCapacityPolicy.lowMemoryBenchmarkCapacity] for the default.
     */
    fun lowMemoryKvCapacity(): Int =
        KvCacheCapacityPolicy.lowMemoryBenchmarkCapacity()

    /**
     * Returns the KV cache capacity based on the requested output limit
     * using the standard heuristic: capacity = max(output_limit * 2, MIN_CAPACITY).
     */
    fun deriveKvCapacityForOutput(outputLimit: Int): Int =
        (outputLimit * 2).coerceIn(KvCacheCapacityPolicy.MIN_CAPACITY, KvCacheCapacityPolicy.MAX_CAPACITY)

    // ── Private helpers ─────────────────────────────────────────────────

    private fun readMaxTokens(prefs: SharedPreferences, materializeMissingKeys: Boolean): Int {
        // If authoritative key exists, use it.
        if (prefs.contains(KEY_MAX_TOKENS)) {
            return prefs.getInt(KEY_MAX_TOKENS, 4000).coerceIn(1, 32000)
        }
        // One-time migration from legacy key.
        val legacy = prefs.getInt(LEGACY_MAX_TOKENS, 4000).coerceIn(1, 32000)
        if (materializeMissingKeys) prefs.edit().putInt(KEY_MAX_TOKENS, legacy).apply()
        return legacy
    }

    private fun readKvCache(
        prefs: SharedPreferences,
        maxTokens: Int,
        legacyMigration: Boolean,
        materializeMissingKeys: Boolean,
    ): Int {
        // If authoritative key exists, use it.
        if (prefs.contains(KEY_KV_CACHE)) {
            return prefs.getInt(KEY_KV_CACHE, 4096).coerceIn(KvCacheCapacityPolicy.MIN_CAPACITY, KvCacheCapacityPolicy.MAX_CAPACITY)
        }
        // One-time migration from legacy key.
        if (prefs.contains(LEGACY_KV_CACHE)) {
            val legacy = prefs.getInt(LEGACY_KV_CACHE, 4096).coerceIn(
                KvCacheCapacityPolicy.MIN_CAPACITY, KvCacheCapacityPolicy.MAX_CAPACITY
            )
            if (materializeMissingKeys) prefs.edit().putInt(KEY_KV_CACHE, legacy).apply()
            return legacy
        }
        if (!legacyMigration) return KvCacheCapacityPolicy.DEFAULT_CAPACITY
        // Derive from output limit using the heuristic: capacity >= 2 * output.
        val derived = deriveKvCapacityForOutput(maxTokens)
        if (materializeMissingKeys) prefs.edit().putInt(KEY_KV_CACHE, derived).apply()
        return derived
    }

    private fun hasPersistedSettings(prefs: SharedPreferences): Boolean = listOf(
        KEY_MAX_TOKENS,
        KEY_KV_CACHE,
        KEY_TOP_K,
        KEY_TOP_P,
        KEY_TEMPERATURE,
        KEY_ACCELERATOR,
        KEY_REASONING_BUDGET,
        KEY_SPECULATIVE_DECODING,
        "reasoning_enabled",
        "max_tokens",
        "kv_cache_capacity_tokens",
    ).any(prefs::contains)

    private fun establishMigrationState(
        prefs: SharedPreferences,
        hadPersistedSettings: Boolean,
        materializeMigration: Boolean,
    ): String {
        prefs.getString(KEY_SETTINGS_MIGRATION_STATE, null)?.let { return it }
        val state = if (hadPersistedSettings) MIGRATION_LEGACY else MIGRATION_FRESH
        if (materializeMigration) prefs.edit().putString(KEY_SETTINGS_MIGRATION_STATE, state).apply()
        return state
    }

    private fun readAccelerator(prefs: SharedPreferences): AcceleratorMode =
        runCatching {
            val raw = prefs.getString(KEY_ACCELERATOR, "GPU") ?: "GPU"
            AcceleratorMode.valueOf(raw)
        }.getOrDefault(AcceleratorMode.GPU)

    // ── Migration check ──────────────────────────────────────────────────

    /**
     * Returns true if migration has completed for both authoritative keys.
     * Useful for tests to verify migration state.
     */
    fun isMigrationComplete(prefs: SharedPreferences): Boolean =
        prefs.contains(KEY_MAX_TOKENS) && prefs.contains(KEY_KV_CACHE)
}
