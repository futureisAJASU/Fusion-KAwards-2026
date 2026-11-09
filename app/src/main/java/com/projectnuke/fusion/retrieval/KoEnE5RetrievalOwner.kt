package com.projectnuke.fusion.retrieval

import android.content.res.AssetManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single production owner for the S1 Ko/En semantic backbone.
 *
 * Lazy and releasable: [proxy] construction performs zero IO (no copy,
 * hashing, fsync, tokenizer parsing, or ORT load) so it is safe to call
 * during Compose construction. Heavy work happens on [Dispatchers.IO] only
 * when semantic ranking is actually requested, exactly once per live engine
 * (single-flight via [creationMutex]).
 *
 * ```text
 * stable proxy (cheap, remembered by UI)
 *   -> rank() -> ensure engine lazily on Dispatchers.IO
 *             -> provision verified assets if necessary
 *             -> run ranking (or unavailable -> rule-only fallback)
 *
 * owner.close() -> current engine/session released
 *               -> proxy remains usable
 *               -> next rank() lazily provisions/recreates a fresh engine
 * ```
 *
 * Release policy (wired by the app, never timers): the owning screen disposes
 * (ChatScreen onDispose), the app backgrounds (MainActivity.onStop), and
 * meaningful memory-pressure callbacks all call [close]. The engine's native
 * lifetime lease keeps an in-flight rank safe while close immediately fences
 * new inference; the following rank recreates cleanly.
 */
object KoEnE5RetrievalOwner {

    private val creationMutex = Mutex()
    private val stateLock = Any()
    private var engine: FusionEmbeddingEngine? = null
    private var matcher: EmbeddingSemanticCandidateMatcher? = null
    private var generation: Long = 0

    internal var engineFactory: (KoEnE5AssetProvider) -> FusionEmbeddingEngine =
        { provider -> KoEnE5OnnxEmbeddingEngine(provider) }

    internal var provision: (AssetManager?, File) -> KoEnE5AssetProvisioner.ProvisionResult =
        { assets, dir ->
            if (assets == null) {
                KoEnE5AssetProvisioner.ProvisionResult.Failed("ASSETS_UNAVAILABLE")
            } else {
                KoEnE5AssetProvisioner.provision(assets, dir)
            }
        }

    /**
     * Cheap stable proxy. Performs NO file copy, hashing, fsync, tokenizer
     * parsing, or ORT load — safe during Compose/main-thread construction.
     */
    fun proxy(assets: AssetManager?, privateDir: File): SemanticCandidateMatcher =
        OwnerBackedSemanticMatcher(assets, privateDir)

    /** Stable lazy proxy for symmetric feature tasks; shares the exact E5 engine/session above. */
    fun symmetricProxy(assets: AssetManager?, privateDir: File): SymmetricEmbeddingSource =
        OwnerBackedSymmetricEmbeddingSource(assets, privateDir)

    internal suspend fun embedSymmetric(
        assets: AssetManager?,
        privateDir: File,
        texts: List<String>,
    ): EmbeddingResult {
        if (texts.isEmpty() || texts.size > FusionEmbeddingLimits.MAX_TEXTS_PER_CALL ||
            texts.any { it.isBlank() || it.length > FusionEmbeddingLimits.MAX_TEXT_CHARS }
        ) return EmbeddingResult.Failure(EmbeddingFailureReason.INVALID_INPUT, "invalid symmetric batch")
        synchronized(stateLock) { engine }?.let { current ->
            return current.embed(texts.map(E5InputContract::queryBounded))
        }
        return try {
            creationMutex.withLock {
                val current = synchronized(stateLock) { engine }
                if (current != null) return@withLock current.embed(texts.map(E5InputContract::queryBounded))
                val capturedGeneration = synchronized(stateLock) { generation }
                val created = try {
                    withContext(Dispatchers.IO) {
                        val provisioned = when (val result = provision(assets, privateDir)) {
                            is KoEnE5AssetProvisioner.ProvisionResult.Ready -> result.files
                            is KoEnE5AssetProvisioner.ProvisionResult.Failed -> null
                        } ?: return@withContext null
                        try {
                            engineFactory(VerifiedFileKoEnE5AssetProvider(provisioned.modelFile, provisioned.tokenizerFile))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (factoryFailed: Exception) {
                            throw EngineFactoryFailed(factoryFailed)
                        }
                    }
                } catch (factoryFailed: EngineFactoryFailed) {
                    return@withLock EmbeddingResult.Failure(EmbeddingFailureReason.MODEL_LOAD_FAILURE, "engine creation failed")
                }
                val createdEngine = created
                    ?: return@withLock EmbeddingResult.Failure(EmbeddingFailureReason.ASSET_MISSING, "assets unavailable")
                val installed = synchronized(stateLock) {
                    if (generation == capturedGeneration) {
                        engine = createdEngine
                        matcher = EmbeddingSemanticCandidateMatcher(createdEngine)
                        true
                    } else false
                }
                if (!installed) {
                    closeEngineBestEffort(createdEngine)
                    return@withLock EmbeddingResult.Failure(EmbeddingFailureReason.CLOSED, "owner closed during creation")
                }
                createdEngine.embed(texts.map(E5InputContract::queryBounded))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            EmbeddingResult.Failure(EmbeddingFailureReason.INFERENCE_FAILURE, "symmetric inference failed")
        }
    }

    internal suspend fun rank(
        assets: AssetManager?,
        privateDir: File,
        claim: String,
        segments: List<SemanticSegment>,
        limit: Int,
    ): SemanticMatchResult {
        synchronized(stateLock) { matcher }?.let { return it.rank(claim, segments, limit) }
        return try {
            creationMutex.withLock {
                synchronized(stateLock) { matcher }?.let {
                    return it.rank(claim, segments, limit)
                }
                // Capture the generation at creation start. If close() races,
                // generation will be incremented and we'll discard the new engine.
                val capturedGeneration = synchronized(stateLock) { generation }
                // C17: provision failure (null) stays ASSET_MISSING. A
                // recoverable factory exception maps to MODEL_LOAD_FAILURE,
                // never ASSET_MISSING. CancellationException and fatal Error
                // propagate with identity through both boundaries below.
                val created: FusionEmbeddingEngine? = try {
                    withContext(Dispatchers.IO) {
                        val provisioned = when (val result = provision(assets, privateDir)) {
                            is KoEnE5AssetProvisioner.ProvisionResult.Ready -> result.files
                            is KoEnE5AssetProvisioner.ProvisionResult.Failed -> return@withContext null
                        }
                        try {
                            engineFactory(
                                VerifiedFileKoEnE5AssetProvider(
                                    provisioned.modelFile,
                                    provisioned.tokenizerFile,
                                ),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (factoryFailed: Exception) {
                            throw EngineFactoryFailed(factoryFailed)
                        }
                    }
                } catch (factoryFailed: EngineFactoryFailed) {
                    return SemanticMatchResult(emptyList(), EmbeddingFailureReason.MODEL_LOAD_FAILURE)
                }
                val createdEngine = created
                    ?: return SemanticMatchResult(emptyList(), EmbeddingFailureReason.ASSET_MISSING)
                val shared = EmbeddingSemanticCandidateMatcher(createdEngine)
                val installed = synchronized(stateLock) {
                    // Generation fence: if close() raced, discard the new engine.
                    if (generation == capturedGeneration) {
                        engine = createdEngine
                        matcher = shared
                        true
                    } else {
                        false
                    }
                }
                if (!installed) {
                    closeEngineBestEffort(createdEngine)
                    return@withLock SemanticMatchResult(emptyList(), EmbeddingFailureReason.CLOSED)
                }
                shared.rank(claim, segments, limit)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SemanticMatchResult(emptyList(), EmbeddingFailureReason.INFERENCE_FAILURE)
        }
    }

    /**
     * Idempotent release of the current engine (and therefore its native
     * session). Proxies stay usable: the next rank() recreates lazily.
     * Non-suspending; safe from the main thread and trim callbacks.
     * Increments generation to fence against in-flight engine creation.
     */
    fun close() {
        val detached = synchronized(stateLock) {
            generation++
            engine.also {
                engine = null
                matcher = null
            }
        }
        closeEngineBestEffort(detached)
    }

    /** Test-only: restores production seams after injection. */
    internal fun resetForTests() {
        val detached = synchronized(stateLock) {
            generation = 0
            val previous = engine
            engine = null
            matcher = null
            engineFactory = { provider -> KoEnE5OnnxEmbeddingEngine(provider) }
            provision = { assets, dir ->
                if (assets == null) {
                    KoEnE5AssetProvisioner.ProvisionResult.Failed("ASSETS_UNAVAILABLE")
                } else {
                    KoEnE5AssetProvisioner.provision(assets, dir)
                }
            }
            previous
        }
        closeEngineBestEffort(detached)
    }

    private fun closeEngineBestEffort(engine: FusionEmbeddingEngine?) {
        try {
            engine?.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Ordinary close failures must not retain the owner reference;
            // cancellation and fatal Error remain visible to the caller.
        }
    }
}

/** Internal carrier distinguishing recoverable engine-factory failure (C17). */
internal class EngineFactoryFailed(cause: Exception) : RuntimeException(cause)

/**
 * Cheap stable semantic proxy. Construction stores two references and
 * performs no IO; every rank() resolves the owner's current engine (creating
 * it lazily on first use, recreating after close), so a remembered proxy
 * never pins a closed engine.
 */
class OwnerBackedSemanticMatcher(
    private val assets: AssetManager?,
    private val privateDir: File,
) : SemanticCandidateMatcher {
    override suspend fun rank(
        claim: String,
        segments: List<SemanticSegment>,
        limit: Int,
    ): SemanticMatchResult =
        KoEnE5RetrievalOwner.rank(assets, privateDir, claim, segments, limit)
}

class OwnerBackedSymmetricEmbeddingSource(
    private val assets: AssetManager?,
    private val privateDir: File,
) : SymmetricEmbeddingSource {
    override suspend fun embedSources(texts: List<String>): EmbeddingResult =
        KoEnE5RetrievalOwner.embedSymmetric(assets, privateDir, texts)
}
