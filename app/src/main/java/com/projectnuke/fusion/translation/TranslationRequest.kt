package com.projectnuke.fusion.translation

const val TRANSLATION_SOURCE_LANGUAGE_UNSPECIFIED = "unspecified"

/**
 * REQUESTED source-language semantics of a translation request.
 *
 * UNSPECIFIED means "the caller does not provide an authoritative
 * source-language code". It NEVER claims the backend detected the source —
 * detection is a separate, optional RESOLVED result supplied by the producing
 * backend only when it truthfully validates it. Explicit codes are only valid
 * when the active backend advertises them as supported source languages.
 */
sealed interface TranslationSourceLanguage {
    data object Unspecified : TranslationSourceLanguage
    data class Explicit(val rawCode: String) : TranslationSourceLanguage {
        val code: String get() = rawCode.lowercase()
    }

    companion object {
        fun parse(value: String): TranslationSourceLanguage {
            val v = value.trim().lowercase()
            if (v.isEmpty() || v == TRANSLATION_SOURCE_LANGUAGE_UNSPECIFIED || v == "auto") {
                // "auto" is accepted as a legacy input value; it canonicalizes
                // to UNSPECIFIED and never implies detection happened.
                return Unspecified
            }
            return Explicit(v)
        }

        /**
         * Canonical requested-source code for provenance strings:
         * "unspecified" or an explicit code. NOT a resolved language.
         */
        fun codeOf(language: TranslationSourceLanguage): String = when (language) {
            is Unspecified -> TRANSLATION_SOURCE_LANGUAGE_UNSPECIFIED
            is Explicit -> language.code
        }
    }
}

/** Semantic owner of a translation request. Routes are never interchangeable:
 *  evidence presentation and search-snippet presentation are distinct
 *  contexts even though both are presentation only. */
enum class TranslationRoute {
    STANDALONE,
    EVIDENCE_PRESENTATION,
    SEARCH_SNIPPET_PRESENTATION,
}

/**
 * Style seam for the future dedicated model. One conservative default keeps
 * the domain future-compatible; UI exposes no style controls today.
 */
enum class TranslationStyle {
    CONSERVATIVE_DEFAULT,
}

/**
 * Backend-independent translation request.
 *
 * The UI and presentation controllers build requests; the coordinator routes
 * them through the active backend. The current LiteRT adapter may ignore
 * style/context where it does not advertise support; unsupported capability is
 * never fabricated as supported.
 */
data class TranslationRequest(
    val originalText: String,
    val targetLanguage: String,
    val sourceLanguage: TranslationSourceLanguage = TranslationSourceLanguage.Unspecified,
    val style: TranslationStyle = TranslationStyle.CONSERVATIVE_DEFAULT,
    val context: String? = null,
    val route: TranslationRoute = TranslationRoute.STANDALONE,
    val sourceIdentity: String,
    val sourceUrl: String? = null,
) {
    /** Deterministically bounded application/user context, data only. */
    val boundedContext: String?
        get() = context
            ?.take(TranslationBounds.MAX_CONTEXT_CHARS)
            ?.takeIf { it.isNotEmpty() }
}