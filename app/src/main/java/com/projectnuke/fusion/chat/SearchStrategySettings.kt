package com.projectnuke.fusion.chat

import android.content.Context
import android.content.SharedPreferences

/**
 * Backend search strategy. Selecting a strategy never grants web-search
 * permission; [GenerationRequestSnapshot.webSearchPolicy] remains the sole
 * request authority for whether search may run.
 */
enum class SearchStrategy {
    AGENT_HARNESS,
    LEGACY_RULE_BASED,
    MANUAL_QUERY,
}

/** Exact request-only query supplied by a future manual-search UI. */
@JvmInline
value class ManualSearchQuery private constructor(val value: String) {
    companion object {
        const val MAX_CHARS = 256

        fun parse(raw: String?): ManualSearchQueryValidation = when {
            raw == null -> ManualSearchQueryValidation.Missing
            raw.trim().isEmpty() -> ManualSearchQueryValidation.Invalid(ManualSearchQueryIssue.EMPTY)
            raw.trim().length > MAX_CHARS -> ManualSearchQueryValidation.Invalid(ManualSearchQueryIssue.TOO_LONG)
            raw.any(Char::isISOControl) -> ManualSearchQueryValidation.Invalid(ManualSearchQueryIssue.CONTROL_CHARACTERS)
            raw.trim().none(Char::isLetterOrDigit) -> ManualSearchQueryValidation.Invalid(ManualSearchQueryIssue.NO_SEARCH_TERMS)
            else -> ManualSearchQueryValidation.Valid(ManualSearchQuery(raw.trim()))
        }
    }
}

enum class ManualSearchQueryIssue {
    EMPTY,
    TOO_LONG,
    CONTROL_CHARACTERS,
    NO_SEARCH_TERMS,
}

sealed interface ManualSearchQueryValidation {
    data class Valid(val query: ManualSearchQuery) : ManualSearchQueryValidation
    data class Invalid(val issue: ManualSearchQueryIssue) : ManualSearchQueryValidation
    data object Missing : ManualSearchQueryValidation
}

/** Persisted UI-facing preference value. */
data class SearchStrategyPreference(
    val strategy: SearchStrategy = SearchStrategy.AGENT_HARNESS,
    val allowLegacyFallback: Boolean = true,
    /** True when unknown/corrupt persisted state was replaced by a safe mode. */
    val recoveredFromCorruption: Boolean = false,
)

/** Immutable search settings and manual-query validation captured at request creation. */
data class SearchStrategySnapshot(
    val strategy: SearchStrategy,
    val allowLegacyFallback: Boolean,
    val manualQuery: ManualSearchQueryValidation,
) {
    companion object {
        val DEFAULT = SearchStrategySnapshot(
            strategy = SearchStrategy.AGENT_HARNESS,
            allowLegacyFallback = true,
            manualQuery = ManualSearchQueryValidation.Missing,
        )
    }
}

/**
 * A frozen one-shot manual query is consumed only after the exact transport
 * boundary was entered, and only if the composer still contains that same
 * query revision. A later edit therefore survives completion of an older
 * request.
 */
internal object ManualSearchQueryConsumptionPolicy {
    fun shouldConsume(
        strategy: SearchStrategy,
        query: ManualSearchQueryValidation,
        frozenInputRevision: Long?,
        currentInputRevision: Long,
        currentInput: String,
        exactTransportAttempts: Int,
    ): Boolean {
        val frozenQuery = (query as? ManualSearchQueryValidation.Valid)?.query?.value ?: return false
        return strategy == SearchStrategy.MANUAL_QUERY &&
            frozenInputRevision != null &&
            frozenInputRevision == currentInputRevision &&
            currentInput == frozenQuery &&
            exactTransportAttempts > 0
    }
}

/**
 * Stable future Advanced Settings contract. Read once when a generation is
 * captured; callers retain the returned [SearchStrategySnapshot] for the
 * whole request. Manual query text is never stored in global preferences.
 */
interface SearchStrategySettings {
    fun read(): SearchStrategyPreference
    fun update(strategy: SearchStrategy, allowLegacyFallback: Boolean = true): Boolean
    fun captureRequestSnapshot(manualQueryOverride: String? = null): SearchStrategySnapshot
}

/** Storage seam keeps preference decoding deterministic in host-side tests. */
internal interface SearchStrategyPreferenceAccess {
    fun getString(key: String): String?
    fun write(values: Map<String, String>): Boolean
}

/**
 * Versioned SharedPreferences implementation. Missing keys are a fresh-install
 * state and select AGENT_HARNESS. Unknown versions, modes, or malformed values
 * fail safely to the existing LEGACY_RULE_BASED path.
 */
class SearchStrategySettingsStore internal constructor(
    private val preferences: SearchStrategyPreferenceAccess,
) : SearchStrategySettings {
    constructor(context: Context) : this(
        SharedPreferenceAccess(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        ),
    )

    override fun read(): SearchStrategyPreference {
        val version = preferences.getString(KEY_VERSION)
        val rawStrategy = preferences.getString(KEY_STRATEGY)
        val rawFallback = preferences.getString(KEY_FALLBACK)
        if (version == null && rawStrategy == null && rawFallback == null) {
            return SearchStrategyPreference()
        }
        if (version != SCHEMA_VERSION.toString()) return corruptedPreference()
        val strategy = rawStrategy?.let { raw ->
            SearchStrategy.entries.firstOrNull { it.name == raw }
        } ?: return corruptedPreference()
        val fallback = when (rawFallback) {
            "true" -> true
            "false" -> false
            else -> return corruptedPreference()
        }
        return SearchStrategyPreference(strategy, fallback)
    }

    override fun update(strategy: SearchStrategy, allowLegacyFallback: Boolean): Boolean =
        runCatching {
            preferences.write(
                mapOf(
                    KEY_VERSION to SCHEMA_VERSION.toString(),
                    KEY_STRATEGY to strategy.name,
                    KEY_FALLBACK to allowLegacyFallback.toString(),
                ),
            )
        }.getOrDefault(false)

    override fun captureRequestSnapshot(manualQueryOverride: String?): SearchStrategySnapshot {
        val selected = read()
        val query = if (selected.strategy == SearchStrategy.MANUAL_QUERY) {
            ManualSearchQuery.parse(manualQueryOverride)
        } else {
            ManualSearchQueryValidation.Missing
        }
        return SearchStrategySnapshot(
            strategy = selected.strategy,
            allowLegacyFallback = selected.allowLegacyFallback,
            manualQuery = query,
        )
    }

    private fun corruptedPreference() = SearchStrategyPreference(
        strategy = SearchStrategy.LEGACY_RULE_BASED,
        allowLegacyFallback = true,
        recoveredFromCorruption = true,
    )

    private class SharedPreferenceAccess(private val delegate: SharedPreferences) : SearchStrategyPreferenceAccess {
        override fun getString(key: String): String? = runCatching { delegate.getString(key, null) }.getOrNull()

        override fun write(values: Map<String, String>): Boolean = delegate.edit().let { editor ->
            values.forEach { (key, value) -> editor.putString(key, value) }
            editor.commit()
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val PREFERENCES_NAME = "fusion_search_strategy_v1"
        const val KEY_VERSION = "schema_version"
        const val KEY_STRATEGY = "strategy"
        const val KEY_FALLBACK = "allow_legacy_fallback"
    }
}
