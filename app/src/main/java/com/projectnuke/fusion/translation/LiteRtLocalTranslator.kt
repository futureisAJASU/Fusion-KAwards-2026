package com.projectnuke.fusion.translation

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.RequestedEngineProfile
import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.llm.LlmEngine
import com.projectnuke.fusion.llm.FusionRuntimeLock
import com.projectnuke.fusion.llm.BenchmarkRunningException

// C3 production LocalTranslator: bounded deterministic translation over the
// existing shared local LiteRT runtime.
//
// Contract (frozen by this implementation):
// - reuses the already loaded shared engine where possible; no second runtime;
// - FRESH messages/conversation per translation, no answer-generation
//   KV/history reuse (the translation prompt never contains prior turns);
// - the existing global FusionRuntimeLock serializes with generation, and the
//   translation never runs concurrently with benchmark exclusivity
//   (BenchmarkRunningException surfaces as Unavailable, never a fallback);
// - bounded, deterministic per-turn settings (fixed temperature/topK/topP,
//   fixed seed, and a bounded output cap);
// - no external provider fallback, no chain-of-thought request;
// - engine unavailability returns Unavailable so the caller shows the
//   original unchanged;
// - capability truth: validated KO/EN targets only; no product-level style
//   or structured-context support. style/context on requests are validated
//   (non-default style rejected) but never fabricated as supported.
class LiteRtLocalTranslator(
    private val engineProvider: () -> LlmEngine?,
    private val profileProvider: () -> RequestedEngineProfile?,
) : LocalTranslator {

    override val capabilities: TranslationBackendCapabilities = TranslationBackends.CURRENT

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        if (!TranslationPolicy.validateRequest(request, capabilities)) {
            return TranslationResult.Failure("translation request out of policy bounds")
        }
        val engine = engineProvider() ?: return TranslationResult.Unavailable
        val profile = profileProvider() ?: return TranslationResult.Unavailable
        val boundedInput = request.originalText.take(TranslationBounds.MAX_INPUT_CHARS)
        return try {
            // Shared runtime lock: translation is serialized with every other
            // LiteRT use. This is a FRESH generation call with a fresh bounded
            // message list; no prior KV/history besides the translation prompt.
            val outcome = FusionRuntimeLock.withChatGeneration {
                engine.generate(
                    messages = listOf(
                        ChatMessage(role = "system", content = TranslationPolicy.systemPrompt(request.targetLanguage)),
                        ChatMessage(role = "user", content = TranslationPolicy.userPayload(boundedInput)),
                    ),
                    profile = profile,
                    options = TranslationGenerationSettings.deterministicOptions(),
                )
            }
            when (outcome) {
                is GenerationOutcome.Success -> {
                    val artifact = TranslationPolicy.makeArtifact(
                        request = request.copy(originalText = boundedInput),
                        translatedText = outcome.text,
                        capabilities = capabilities,
                    ) ?: return TranslationResult.Failure("translation output rejected by policy")
                    TranslationResult.Success(artifact)
                }
                else -> TranslationResult.Unavailable
            }
        } catch (_: BenchmarkRunningException) {
            TranslationResult.Unavailable
        } catch (_: kotlinx.coroutines.CancellationException) {
            throw kotlinx.coroutines.CancellationException("translation cancelled")
        } catch (_: Exception) {
            TranslationResult.Unavailable
        }
    }
}

/** Extremely narrow, bounded, deterministic per-turn translation settings. */
object TranslationGenerationSettings {
    const val MAX_OUTPUT_TOKENS = 2_048

    fun deterministicOptions(): ConversationOptions = ConversationOptions(
        maxOutputToken = MAX_OUTPUT_TOKENS,
        temperature = 0.1f,
        topK = 1,
        topP = 0.9f,
        seed = 9_137,
    )
}

// Ownership of translation artifacts inside the process. Original text and
// hashes stay canonical; artifacts are derived presentation only.
class LocalTranslationCoordinator(
    private val translator: LocalTranslator,
) {
    private val gate = TranslationGate()

    /** Active backend capability truth; the UI derives targets from this. */
    val capabilities: TranslationBackendCapabilities
        get() = translator.capabilities

    /**
     * Single-flight translation with bounded deterministic work. An
     * unavailable engine yields Unavailable and the caller shows the original
     * unchanged (TranslationPolicy.fallbackToOriginalWhenUnavailable); the
     * original always remains the canonical evidence text.
     *
     * Backend contract boundary: a backend must return a [TranslationResult].
     * Cancellation stays cancellation; an ordinary operational backend
     * exception settles into a bounded Failure instead of escaping; a
     * LinkageError from an optional/native backend integration that is not
     * present settles into Unavailable. Fatal VM errors propagate.
     */
    suspend fun translate(request: TranslationRequest): TranslationResult {
        if (!TranslationPolicy.validateRequest(request, capabilities)) {
            return TranslationResult.Failure("translation request out of policy bounds")
        }
        if (!gate.tryEnter()) return TranslationResult.Unavailable
        return try {
            enforceBackendResultContract {
                when (val result = translator.translate(request)) {
                    // The URL is public source metadata bound by the caller; the
                    // canonical evidence text is untouched.
                    is TranslationResult.Success ->
                        TranslationResult.Success(result.artifact.copy(sourceUrl = request.sourceUrl ?: result.artifact.sourceUrl))
                    is TranslationResult.Unavailable -> result
                    is TranslationResult.Failure -> result
                }
            }
        } finally {
            gate.release()
        }
    }

    private suspend fun enforceBackendResultContract(
        block: suspend () -> TranslationResult,
    ): TranslationResult = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: LinkageError) {
        TranslationResult.Unavailable
    } catch (_: Exception) {
        TranslationResult.Failure("translation backend failure")
    }

    fun actionLabel(hasTranslation: Boolean): String = TranslationPolicy.actionLabel(hasTranslation)

    // Evidence verification must keep operating on the canonical original.
    fun canonicalEvidenceText(artifact: TranslationArtifact): String =
        TranslationPolicy.canonicalEvidenceText(artifact)
}