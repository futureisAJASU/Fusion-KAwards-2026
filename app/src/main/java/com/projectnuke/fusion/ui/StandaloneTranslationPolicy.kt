package com.projectnuke.fusion.ui

import com.projectnuke.fusion.translation.TranslationArtifact
import com.projectnuke.fusion.translation.TranslationBackendCapabilities
import com.projectnuke.fusion.translation.TranslationBounds
import com.projectnuke.fusion.translation.TranslationPolicy
import com.projectnuke.fusion.translation.TranslationRequest
import com.projectnuke.fusion.translation.TranslationResult
import com.projectnuke.fusion.translation.TranslationRoute
import com.projectnuke.fusion.translation.TranslationSourceLanguage
import com.projectnuke.fusion.translation.TRANSLATION_SOURCE_LANGUAGE_UNSPECIFIED

// C3 standalone local translation surface state and policy.
//
// Pure presentation state. The original composer draft, chat history and
// canonical evidence text are never written by this policy: the only exit
// toward a draft is an explicit user action ("입력창에 넣기") surfaced by the
// caller through StandaloneTranslationPolicy.composerReplacementText.
//
// The only translation route is the existing local coordinator; there is no
// external provider route anywhere in this policy. Target-language choices are
// DERIVED from the active backend capability truth, never hard-coded. Every
// policy function that needs capability truth takes it explicitly.

enum class StandaloneTranslationPhase {
    IDLE,
    TRANSLATING,
    SUCCESS,
    UNAVAILABLE,
    FAILURE,
}

data class StandaloneTargetLanguage(
    val code: String,
    val label: String,
)

/**
 * Immutable surface state. [requestId] is the UI REQUEST EPOCH: it advances
 * whenever the source, target or session changes so late in-flight
 * completions can never land on a mismatched request. The epoch is NEVER part
 * of the stable translation provenance identity (see
 * StandaloneTranslationPolicy.provenanceIdentityFor), matching the
 * architecture spec.
 */
data class StandaloneTranslationState(
    val sourceText: String = "",
    val targetLanguage: String = "ko",
    val sourceLanguage: TranslationSourceLanguage = TranslationSourceLanguage.Unspecified,
    val phase: StandaloneTranslationPhase = StandaloneTranslationPhase.IDLE,
    val artifact: TranslationArtifact? = null,
    val requestId: Long = 0L,
) {
    val busy: Boolean
        get() = phase == StandaloneTranslationPhase.TRANSLATING
}

object StandaloneTranslationPolicy {
    const val STANDALONE_SOURCE_OWNER = "standalone-surface"
    const val DEFAULT_TARGET_LANGUAGE = "ko"

    /** Target choices derive from the ACTIVE backend capability truth. */
    fun targetLanguages(capabilities: TranslationBackendCapabilities): List<StandaloneTargetLanguage> =
        TranslationLanguageLabels.orderedCodes()
            .filter { capabilities.supportsTarget(it) }
            .map { code -> StandaloneTargetLanguage(code = code, label = TranslationLanguageLabels.labelOf(code)) }

    fun isSupportedTarget(code: String, capabilities: TranslationBackendCapabilities): Boolean =
        capabilities.supportsTarget(code)

    /** NON-AUTHORITATIVE hint used only to pick a comfortable DEFAULT TARGET.
     *  Never stored or displayed as "detected source language". */
    fun hasHangul(text: String): Boolean = text.any { it in '\uac00'..'\ud7a3' }

    fun defaultTargetLanguageFor(sourceText: String): String =
        if (hasHangul(sourceText)) "en" else "ko"

    /**
     * Stable TRANSLATION PROVENANCE identity for the standalone surface.
     *
     * Same content + same semantic request (source language, target, route)
     * always yields the same identity. The UI request epoch is deliberately
     * excluded; stale completions are rejected by the epoch guards in
     * onSourceChanged/onTargetChanged/onTranslationResult instead.
     */
    fun provenanceIdentityFor(
        sourceText: String,
        targetLanguage: String,
        sourceLanguage: TranslationSourceLanguage,
        route: TranslationRoute = TranslationRoute.STANDALONE,
    ): String = TranslationPolicy.provenanceIdentityFor(
        TranslationRequest(
            originalText = sourceText,
            targetLanguage = targetLanguage,
            sourceLanguage = sourceLanguage,
            route = route,
            sourceIdentity = STANDALONE_SOURCE_OWNER,
        ),
    )

    fun canStart(state: StandaloneTranslationState, capabilities: TranslationBackendCapabilities): Boolean {
        if (state.busy) return false
        return canStartWith(state.sourceText, state.targetLanguage, capabilities)
    }

    fun canStartWith(
        sourceText: String,
        targetLanguage: String,
        capabilities: TranslationBackendCapabilities,
    ): Boolean = TranslationPolicy.validateRequest(sourceText, targetLanguage, capabilities)

    /** Korean helper shown when translation cannot start and the reason is
     *  actionable. Blank input is self-evident, so no message is needed. */
    fun translateDisabledReason(
        state: StandaloneTranslationState,
        capabilities: TranslationBackendCapabilities,
    ): String? {
        if (state.busy) return null
        if (state.sourceText.isBlank()) return null
        if (!isSupportedTarget(state.targetLanguage, capabilities)) return null
        if (state.sourceText.length > TranslationBounds.MAX_INPUT_CHARS) {
            return "번역은 ${TranslationBounds.MAX_INPUT_CHARS}자까지 지원해요. 내용을 줄여 주세요."
        }
        return null
    }

    /** A result is only ever presented while it still matches the current
     *  source AND the active backend capability truth. */
    fun isResultValid(state: StandaloneTranslationState, capabilities: TranslationBackendCapabilities): Boolean {
        val artifact = state.artifact ?: return false
        return artifact.originalText == state.sourceText &&
            TranslationPolicy.validateArtifact(artifact, capabilities)
    }

    fun displayedTranslatedText(
        state: StandaloneTranslationState,
        capabilities: TranslationBackendCapabilities,
    ): String? = state.artifact?.translatedText?.takeIf { isResultValid(state, capabilities) }

    fun canSwap(state: StandaloneTranslationState, capabilities: TranslationBackendCapabilities): Boolean =
        displayedTranslatedText(state, capabilities) != null

    fun canCopy(state: StandaloneTranslationState, capabilities: TranslationBackendCapabilities): Boolean =
        displayedTranslatedText(state, capabilities) != null

    /** Editing the source always invalidates the presented result and any
     *  in-flight request (UI epoch advances). */
    fun onSourceChanged(state: StandaloneTranslationState, newSourceText: String): StandaloneTranslationState {
        if (state.sourceText == newSourceText) return state
        return state.copy(
            sourceText = newSourceText,
            phase = StandaloneTranslationPhase.IDLE,
            artifact = null,
            requestId = state.requestId + 1L,
        )
    }

    /** Changing the target invalidates the previous result; the source is kept. */
    fun onTargetChanged(state: StandaloneTranslationState, newTargetLanguage: String): StandaloneTranslationState {
        if (state.targetLanguage == newTargetLanguage) return state
        return state.copy(
            targetLanguage = newTargetLanguage,
            phase = StandaloneTranslationPhase.IDLE,
            artifact = null,
            requestId = state.requestId + 1L,
        )
    }

    /** Enters TRANSLATING with a fresh request id. No-op when the request
     *  would be invalid or a translation is already running. */
    fun beginTranslation(
        state: StandaloneTranslationState,
        capabilities: TranslationBackendCapabilities,
    ): StandaloneTranslationState {
        if (!canStart(state, capabilities)) return state
        return state.copy(
            phase = StandaloneTranslationPhase.TRANSLATING,
            artifact = null,
            requestId = state.requestId + 1L,
        )
    }

    /**
     * Guards the completion against request-id mismatches: a result belonging
     * to an older request (source edited, target changed, session reset) is
     * discarded. The source text is never touched by any outcome; only the
     * phase/artifact change.
     */
    fun onTranslationResult(
        state: StandaloneTranslationState,
        result: TranslationResult,
        expectedRequestId: Long,
        capabilities: TranslationBackendCapabilities,
    ): StandaloneTranslationState {
        if (state.requestId != expectedRequestId) return state
        return when (result) {
            is TranslationResult.Success -> {
                val artifact = result.artifact
                val valid = TranslationPolicy.validateArtifact(artifact, capabilities) &&
                    artifact.originalText == state.sourceText &&
                    artifact.targetLanguage == state.targetLanguage &&
                    artifact.requestedSourceLanguage == TranslationSourceLanguage.codeOf(state.sourceLanguage) &&
                    !TranslationPolicy.containsHostMetadata(artifact.translatedText)
                if (valid) {
                    state.copy(phase = StandaloneTranslationPhase.SUCCESS, artifact = artifact)
                } else {
                    state.copy(phase = StandaloneTranslationPhase.FAILURE, artifact = null)
                }
            }
            is TranslationResult.Unavailable ->
                state.copy(phase = StandaloneTranslationPhase.UNAVAILABLE, artifact = null)
            is TranslationResult.Failure ->
                state.copy(phase = StandaloneTranslationPhase.FAILURE, artifact = null)
        }
    }

    /**
     * Settles an unexpected in-flight failure for the given request epoch.
     * The surface must never stay busy (TRANSLATING) because an operational
     * exception escaped the coordinator. A stale request can never overwrite
     * a newer state; the source text is never mutated by failure.
     */
    fun onTranslationError(
        state: StandaloneTranslationState,
        expectedRequestId: Long,
    ): StandaloneTranslationState =
        if (state.requestId != expectedRequestId) {
            state
        } else {
            state.copy(phase = StandaloneTranslationPhase.FAILURE, artifact = null)
        }

    /**
     * Swap source and result without assuming a binary KO/EN world.
     *
     * New target derives from the previous source language ONLY when that
     * language is actually known: a genuinely RESOLVED language first, then a
     * caller-asserted explicit requested language. When the previous source
     * was UNSPECIFIED and unresolved, a safe supported default target is
     * chosen; the hint heuristic is used only when the active backend
     * supports the hinted target, otherwise the backend's first supported
     * target is used. No source language is ever fabricated.
     */
    fun swapTargetLanguage(
        resolvedSourceLanguage: String?,
        requestedSourceLanguage: String,
        newSourceText: String,
        capabilities: TranslationBackendCapabilities,
    ): String {
        val known = resolvedSourceLanguage
            ?: requestedSourceLanguage.takeIf { it != TRANSLATION_SOURCE_LANGUAGE_UNSPECIFIED }
        if (known != null) {
            return if (capabilities.supportsTarget(known)) {
                known
            } else {
                val hint = defaultTargetLanguageFor(newSourceText)
                if (capabilities.supportsTarget(hint)) hint else capabilities.supportedTargetLanguages.firstOrNull() ?: hint
            }
        }
        val hint = defaultTargetLanguageFor(newSourceText)
        return if (capabilities.supportsTarget(hint)) {
            hint
        } else {
            capabilities.supportedTargetLanguages.firstOrNull() ?: hint
        }
    }

    fun swap(
        state: StandaloneTranslationState,
        capabilities: TranslationBackendCapabilities,
    ): StandaloneTranslationState {
        val translated = displayedTranslatedText(state, capabilities) ?: return state
        val artifact = state.artifact!!
        return state.copy(
            sourceText = translated,
            targetLanguage = swapTargetLanguage(
                resolvedSourceLanguage = artifact.resolvedSourceLanguage,
                requestedSourceLanguage = artifact.requestedSourceLanguage,
                newSourceText = translated,
                capabilities = capabilities,
            ),
            phase = StandaloneTranslationPhase.IDLE,
            artifact = null,
            requestId = state.requestId + 1L,
        )
    }

    /** Reset the surface: empty source, no result. The chosen target is kept. */
    fun clear(state: StandaloneTranslationState): StandaloneTranslationState =
        state.copy(
            sourceText = "",
            phase = StandaloneTranslationPhase.IDLE,
            artifact = null,
            requestId = state.requestId + 1L,
        )

    /** Full session reset used when the surface closes. */
    fun reset(state: StandaloneTranslationState): StandaloneTranslationState =
        StandaloneTranslationState(
            targetLanguage = state.targetLanguage,
        )

    /** Calm product status copy. Unavailability keeps the source intact. */
    fun statusMessage(state: StandaloneTranslationState): String? = when (state.phase) {
        StandaloneTranslationPhase.TRANSLATING -> "번역 중…"
        StandaloneTranslationPhase.UNAVAILABLE ->
            "현재 로컬 모델을 사용할 수 없어 번역하지 못했어요."
        StandaloneTranslationPhase.FAILURE ->
            "번역하지 못했어요. 내용을 확인한 뒤 다시 시도해 주세요."
        StandaloneTranslationPhase.IDLE,
        StandaloneTranslationPhase.SUCCESS,
        -> null
    }

    /** The ONLY sanctioned path into the composer draft from this surface.
     *  Returns the validated translation; the caller performs the explicit
     *  user-requested replacement through the existing draft seam. */
    fun composerReplacementText(
        state: StandaloneTranslationState,
        capabilities: TranslationBackendCapabilities,
    ): String? = displayedTranslatedText(state, capabilities)
}

/** Central language label metadata. Add a label here when a backend
 *  truthfully advertises a new language; never scatter per-language ifs. */
object TranslationLanguageLabels {
    private val LABELS: Map<String, String> = mapOf(
        "ko" to "한국어",
        "en" to "English",
        "ja" to "日本語",
    )

    private val ORDER: List<String> = listOf("ko", "en", "ja")

    fun labelOf(code: String): String = LABELS[code.lowercase()] ?: code.uppercase()

    fun orderedCodes(): List<String> = ORDER
}