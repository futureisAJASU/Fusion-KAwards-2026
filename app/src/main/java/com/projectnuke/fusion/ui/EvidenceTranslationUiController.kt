package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.translation.LocalTranslationCoordinator
import com.projectnuke.fusion.translation.TranslationArtifact
import com.projectnuke.fusion.translation.TranslationBounds
import com.projectnuke.fusion.translation.TranslationPolicy
import com.projectnuke.fusion.translation.TranslationRequest
import com.projectnuke.fusion.translation.TranslationResult
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class EvidenceTranslationPresentationState {
    ORIGINAL,
    TRANSLATING,
    TRANSLATED,
    UNAVAILABLE,
}

data class EvidenceTranslationIdentity(
    val messageId: Long,
    val answerSha256: String,
    val sourceId: String,
    val originalSnippetSha256: String,
    val targetLanguage: String,
    val sourceUrl: String,
) {
    val requestSourceIdentity: String
        get() = listOf(
            "evidence",
            messageId,
            answerSha256,
            sourceId,
            originalSnippetSha256,
            targetLanguage,
            TranslationPolicy.sha256Hex(sourceUrl),
        ).joinToString(":")

    companion object {
        fun of(answer: EvidenceAnswerIdentity, source: EvidenceSource, targetLanguage: String = "ko") =
            EvidenceTranslationIdentity(
                messageId = answer.messageId,
                answerSha256 = answer.answerSha256,
                sourceId = source.id,
                originalSnippetSha256 = TranslationPolicy.sha256Hex(source.snippet),
                targetLanguage = targetLanguage,
                sourceUrl = source.url,
            )
    }
}

data class EvidenceTranslationPresentation(
    val state: EvidenceTranslationPresentationState = EvidenceTranslationPresentationState.ORIGINAL,
    val showingOriginal: Boolean = true,
    val artifact: TranslationArtifact? = null,
)

data class EvidenceTranslationUiState(
    val session: EvidenceAnswerIdentity? = null,
    val presentations: Map<EvidenceTranslationIdentity, EvidenceTranslationPresentation> = emptyMap(),
)

data class EvidenceTranslationPresentationContext(
    val answer: EvidenceAnswerIdentity,
    val controller: EvidenceTranslationUiController,
    val state: EvidenceTranslationUiState,
)

internal fun isEvidenceTranslationEligibleSnippet(
    snippet: String,
    targetLanguage: String = "ko",
    capabilities: com.projectnuke.fusion.translation.TranslationBackendCapabilities =
        com.projectnuke.fusion.translation.TranslationBackends.CURRENT,
): Boolean =
    snippet.isNotBlank() &&
        snippet.none { it in '\uac00'..'\ud7a3' } &&
        TranslationPolicy.validateRequest(snippet, targetLanguage, capabilities)

/**
 * Presentation-only owner for EvidenceSource translation.
 *
 * It never writes EvidenceReport/EvidenceSource state. Every result is bound
 * to the complete answer/source identity and is checked again after the
 * coordinator suspension before it can enter the bounded in-memory cache.
 */
class EvidenceTranslationUiController(
    private val scope: CoroutineScope,
    private val coordinator: LocalTranslationCoordinator,
    private val targetLanguage: String = "ko",
) {
    private val mutableState = MutableStateFlow(EvidenceTranslationUiState())
    val state = mutableState.asStateFlow()

    private val cache = LinkedHashMap<EvidenceTranslationIdentity, TranslationArtifact>(
        16,
        0.75f,
        true,
    )
    private var cachedArtifactBytes = 0
    private var activeJob: Job? = null
    private var activeKey: EvidenceTranslationIdentity? = null
    private var epoch = 0L

    fun openSession(answer: EvidenceAnswerIdentity) {
        if (mutableState.value.session == answer) return
        invalidate()
        mutableState.value = EvidenceTranslationUiState(session = answer)
    }

    fun toggle(answer: EvidenceAnswerIdentity, source: EvidenceSource) {
        if (mutableState.value.session != answer) return
        val key = EvidenceTranslationIdentity.of(answer, source, targetLanguage)
        if (!isEvidenceTranslationEligibleSnippet(source.snippet, targetLanguage, coordinator.capabilities)) return

        val current = mutableState.value.presentations[key]
        if (current?.artifact != null) {
            cache[key]
            mutableState.value = mutableState.value.copy(
                presentations = mutableState.value.presentations + (
                    key to current.copy(showingOriginal = !current.showingOriginal)
                ),
            )
            return
        }
        if (current?.state == EvidenceTranslationPresentationState.TRANSLATING) return
        if (activeJob?.isActive == true) {
            setPresentation(key, EvidenceTranslationPresentation(EvidenceTranslationPresentationState.UNAVAILABLE))
            return
        }
        startTranslation(answer, source, key)
    }

    fun cancel(answer: EvidenceAnswerIdentity, source: EvidenceSource) {
        val key = EvidenceTranslationIdentity.of(answer, source, targetLanguage)
        if (activeKey != key) return
        epoch++
        activeJob?.cancel()
        activeJob = null
        activeKey = null
        setPresentation(key, EvidenceTranslationPresentation())
    }

    fun dismiss() {
        invalidate()
        mutableState.value = EvidenceTranslationUiState()
    }

    fun presentation(answer: EvidenceAnswerIdentity, source: EvidenceSource): EvidenceTranslationPresentation =
        mutableState.value.presentations[EvidenceTranslationIdentity.of(answer, source, targetLanguage)]
            ?: EvidenceTranslationPresentation()

    internal val cachedArtifactCount: Int
        get() = cache.size

    internal val cachedArtifactBytesForTest: Int
        get() = cachedArtifactBytes

    private fun startTranslation(
        answer: EvidenceAnswerIdentity,
        source: EvidenceSource,
        key: EvidenceTranslationIdentity,
    ) {
        val run = ++epoch
        activeKey = key
        setPresentation(key, EvidenceTranslationPresentation(EvidenceTranslationPresentationState.TRANSLATING))
        activeJob = scope.launch {
            try {
                val result = coordinator.translate(
                    TranslationRequest(
                        originalText = source.snippet,
                        targetLanguage = targetLanguage,
                        sourceLanguage = com.projectnuke.fusion.translation.TranslationSourceLanguage.Unspecified,
                        route = com.projectnuke.fusion.translation.TranslationRoute.EVIDENCE_PRESENTATION,
                        sourceIdentity = key.requestSourceIdentity,
                        sourceUrl = key.sourceUrl,
                    ),
                )
                if (!owns(run, answer, key)) return@launch
                val artifact = (result as? TranslationResult.Success)?.artifact
                if (artifact != null && accepts(key, source.snippet, artifact) && cacheArtifact(key, artifact)) {
                    setPresentation(
                        key,
                        EvidenceTranslationPresentation(
                            state = EvidenceTranslationPresentationState.TRANSLATED,
                            showingOriginal = false,
                            artifact = artifact,
                        ),
                    )
                } else {
                    setPresentation(
                        key,
                        EvidenceTranslationPresentation(EvidenceTranslationPresentationState.UNAVAILABLE),
                    )
                }
            } catch (_: CancellationException) {
                if (owns(run, answer, key)) {
                    setPresentation(key, EvidenceTranslationPresentation())
                }
            } catch (_: Exception) {
                // Defense-in-depth: the coordinator already enforces the
                // result-based backend contract, but if it is ever violated the
                // surface must still settle out of TRANSLATING instead of
                // staying permanently busy with no active job able to finish
                // it. The ownership re-check keeps an exception from a
                // superseded run from overwriting a newer request. Fatal VM
                // errors are NOT caught here.
                if (owns(run, answer, key)) {
                    setPresentation(
                        key,
                        EvidenceTranslationPresentation(EvidenceTranslationPresentationState.UNAVAILABLE),
                    )
                }
            } finally {
                if (activeKey == key && epoch == run) {
                    activeKey = null
                    activeJob = null
                }
            }
        }
    }

    private fun owns(run: Long, answer: EvidenceAnswerIdentity, key: EvidenceTranslationIdentity): Boolean =
        epoch == run &&
            activeKey == key &&
            mutableState.value.session == answer &&
            mutableState.value.presentations[key]?.state == EvidenceTranslationPresentationState.TRANSLATING

    private fun accepts(
        key: EvidenceTranslationIdentity,
        currentOriginalSnippet: String,
        artifact: TranslationArtifact,
    ): Boolean =
        TranslationPolicy.validateArtifact(artifact, coordinator.capabilities) &&
            TranslationPolicy.preservesOriginal(artifact, currentOriginalSnippet) &&
            artifact.sourceIdentity == key.requestSourceIdentity &&
            artifact.sourceUrl == key.sourceUrl &&
            artifact.targetLanguage == key.targetLanguage &&
            !TranslationPolicy.containsHostMetadata(artifact.translatedText)

    private fun cacheArtifact(key: EvidenceTranslationIdentity, artifact: TranslationArtifact): Boolean {
        val bytes = approximateArtifactBytes(artifact)
        if (bytes > TranslationBounds.MAX_CACHED_ARTIFACT_BYTES) return false
        cache.remove(key)?.let { cachedArtifactBytes -= approximateArtifactBytes(it) }
        while (cachedArtifactBytes + bytes > TranslationBounds.MAX_CACHED_ARTIFACT_BYTES && cache.isNotEmpty()) {
            val eldest = cache.entries.iterator().next()
            cachedArtifactBytes -= approximateArtifactBytes(eldest.value)
            cache.remove(eldest.key)
            mutableState.value = mutableState.value.copy(
                presentations = mutableState.value.presentations - eldest.key,
            )
        }
        cache[key] = artifact
        cachedArtifactBytes += bytes
        return true
    }

    private fun setPresentation(key: EvidenceTranslationIdentity, presentation: EvidenceTranslationPresentation) {
        val presentations = (mutableState.value.presentations + (key to presentation)).let { entries ->
            if (entries.size <= MAX_PRESENTATION_ENTRIES) entries else entries
                .toList()
                .drop(entries.size - MAX_PRESENTATION_ENTRIES)
                .toMap()
        }
        mutableState.value = mutableState.value.copy(presentations = presentations)
    }

    private fun invalidate() {
        epoch++
        activeJob?.cancel()
        activeJob = null
        activeKey = null
        cache.clear()
        cachedArtifactBytes = 0
    }

    private fun approximateArtifactBytes(artifact: TranslationArtifact): Int =
        listOf(
            artifact.originalText,
            artifact.originalHashHex,
            artifact.targetLanguage,
            artifact.translatedText,
            artifact.implementationVersion,
            artifact.sourceIdentity,
            artifact.sourceUrl.orEmpty(),
        ).sumOf { it.length * 2 }

    private companion object {
        const val MAX_PRESENTATION_ENTRIES = 64
    }
}
