package com.projectnuke.fusion.ui

import com.projectnuke.fusion.translation.LocalTranslationCoordinator
import com.projectnuke.fusion.translation.TranslationRequest
import com.projectnuke.fusion.translation.TranslationRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Presentation-only owner for the standalone translation screen.
 *
 * Single-flight, local-only: every request goes through the existing
 * [LocalTranslationCoordinator] backed by the active local backend. There is
 * no external translation route or background queue. Results are derived
 * presentation only; the composer draft is never written here. The UI request
 * epoch is kept separate from the stable provenance identity.
 */
class StandaloneTranslationController(
    private val scope: CoroutineScope,
    private val coordinator: LocalTranslationCoordinator,
) {
    private val mutableState = MutableStateFlow(StandaloneTranslationState())
    val state = mutableState.asStateFlow()

    val capabilities: com.projectnuke.fusion.translation.TranslationBackendCapabilities
        get() = coordinator.capabilities

    private var activeJob: Job? = null

    /** Opens a fresh session seeded with a COPY of the caller's draft text. */
    fun open(sourceText: String) {
        cancelActive()
        mutableState.value = StandaloneTranslationState(
            sourceText = sourceText,
            targetLanguage = StandaloneTranslationPolicy.defaultTargetLanguageFor(sourceText),
            requestId = state.value.requestId + 1L,
        )
    }

    fun setSource(text: String) {
        val previous = state.value
        update { StandaloneTranslationPolicy.onSourceChanged(it, text) }
        if (state.value.requestId != previous.requestId) cancelActive()
    }

    fun setTargetLanguage(code: String) {
        val previous = state.value
        update { StandaloneTranslationPolicy.onTargetChanged(it, code) }
        if (state.value.requestId != previous.requestId) cancelActive()
    }

    /** Explicit user action for callers that intentionally own a composer. */
    fun composerReplacementText(): String? =
        StandaloneTranslationPolicy.composerReplacementText(state.value, capabilities)

    fun translate() {
        val current = state.value
        if (!StandaloneTranslationPolicy.canStart(current, capabilities)) return
        val started = StandaloneTranslationPolicy.beginTranslation(current, capabilities)
        if (started == current) return
        val runRequestId = started.requestId
        mutableState.value = started
        val request = TranslationRequest(
            originalText = current.sourceText,
            targetLanguage = current.targetLanguage,
            sourceLanguage = current.sourceLanguage,
            route = TranslationRoute.STANDALONE,
            sourceIdentity = StandaloneTranslationPolicy.provenanceIdentityFor(
                sourceText = current.sourceText,
                targetLanguage = current.targetLanguage,
                sourceLanguage = current.sourceLanguage,
            ),
        )
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                val result = coordinator.translate(request)
                update {
                    StandaloneTranslationPolicy.onTranslationResult(it, result, runRequestId, capabilities)
                }
            } catch (_: CancellationException) {
                // Request superseded: state already advanced past this request.
            } catch (_: Exception) {
                // Defense-in-depth: the coordinator already enforces the
                // result-based backend contract, but if it is ever violated
                // the surface must still settle out of TRANSLATING instead of
                // staying permanently busy. The request-epoch guard inside
                // onTranslationError keeps a stale failure from overwriting a
                // newer request. Fatal VM errors are NOT caught here.
                update { StandaloneTranslationPolicy.onTranslationError(it, runRequestId) }
            }
        }
    }

    fun swap() {
        update { StandaloneTranslationPolicy.swap(it, capabilities) }
        cancelActive()
    }

    fun clear() {
        update { StandaloneTranslationPolicy.clear(it) }
        cancelActive()
    }

    fun close() {
        cancelActive()
        mutableState.value = StandaloneTranslationState(requestId = state.value.requestId + 1L)
    }

    private fun cancelActive() {
        activeJob?.cancel()
        activeJob = null
    }

    private inline fun update(transform: (StandaloneTranslationState) -> StandaloneTranslationState) {
        mutableState.value = transform(mutableState.value)
    }
}
