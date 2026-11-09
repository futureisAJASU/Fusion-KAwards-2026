package com.projectnuke.fusion.retrieval

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Tokenizer output for one bounded batch. Long ids per E5/XLMRoberta contract. */
data class KoEnE5TokenBatch(
    val inputIds: Array<LongArray>,
    val attentionMask: Array<LongArray>,
)

/**
 * Tokenizer contract for the XLMRoberta-fast SentencePiece tokenizer
 * (max 512, truncation + right padding). Production implementations read the
 * pinned `tokenizer.json`; S1 ships fail-closed when it is absent.
 */
interface KoEnE5Tokenizer {
    /** Returns null when tokenization cannot be performed. */
    fun encode(texts: List<String>): KoEnE5TokenBatch?
}

/** Default tokenizer: always unavailable (asset not provisioned). */
object MissingKoEnE5Tokenizer : KoEnE5Tokenizer {
    override fun encode(texts: List<String>): KoEnE5TokenBatch? = null
}

/**
 * ONNX backend for [KoEnE5ModelPolicy.MODEL_ID].
 *
 * Contract (verified against upstream model card + HF file list):
 * - inputs: `input_ids: int64[batch, seq]`, `attention_mask: int64[batch, seq]`,
 *   plus `token_type_ids: int64[batch, seq]` (all zeros; the exported INT8
 *   graph requires it — verified by feeding the real model)
 * - output: `last_hidden_state: float32[batch, seq, 384]` (first output used)
 * - pooling: attention-masked mean, then L2 normalize (cosine space)
 * - prefixes: `query: ` for claims, `passage: ` for segments (applied by
 *   [EmbeddingSemanticCandidateMatcher], never persisted)
 *
 * Lifecycle: exactly one [OrtSession] with its exact [OrtSession.SessionOptions]
 * retained until the session closes, single-thread CPU, idempotent close,
 * bounded batches, no text/embedding retention. Every failure maps to
 * [EmbeddingResult.Failure] so Evidence falls back to deterministic rules.
 * This is an encoder runtime only, never an autoregressive LLM runtime.
 */
class KoEnE5OnnxEmbeddingEngine(
    private val assets: KoEnE5AssetProvider,
    private val tokenizerProvider: KoEnE5TokenizerProvider = FileKoEnE5TokenizerProvider(),
    private val threads: Int = 1,
    private val inferenceBatchSize: Int = FusionEmbeddingLimits.MAX_INFERENCE_BATCH,
) : FusionEmbeddingEngine {

    private val loadMutex = Mutex()
    private val nativeLifetime = KoEnE5NativeLifetime<OrtSession, OrtSession.SessionOptions>()
    @Volatile
    private var loadedTokenizer: KoEnE5Tokenizer? = null
    private var loadLatencyMs: Long? = null
    private var lastBatchSize: Int = 0
    private var lastLatencyMs: Long = 0L
    private var lastFailure: EmbeddingFailureReason? = null

    override val isClosed: Boolean get() = nativeLifetime.isClosed

    override val diagnostics: EmbeddingDiagnostics
        get() = EmbeddingDiagnostics(
            lastBatchSize = lastBatchSize,
            lastLatencyMs = lastLatencyMs,
            loadLatencyMs = loadLatencyMs,
            isLoaded = nativeLifetime.hasLiveResources,
            lastFailureReason = lastFailure,
        )

    override suspend fun embed(texts: List<String>): EmbeddingResult {
        coroutineContext.ensureActive()
        if (isClosed) return EmbeddingResult.Failure(EmbeddingFailureReason.CLOSED, "engine closed")
        if (texts.isEmpty() || texts.size > FusionEmbeddingLimits.MAX_TEXTS_PER_CALL) {
            return EmbeddingResult.Failure(EmbeddingFailureReason.INVALID_INPUT, "batch out of bounds")
        }
        for (text in texts) {
            if (text.length > FusionEmbeddingLimits.MAX_TEXT_CHARS) {
                return EmbeddingResult.Failure(EmbeddingFailureReason.INVALID_INPUT, "text too long")
            }
        }
        val abiFailure = unsupportedAbiReason()
        if (abiFailure != null) return abiFailure

        return try {
            withContext(Dispatchers.IO.limitedParallelism(1)) {
                coroutineContext.ensureActive()
                val started = System.nanoTime()
                val load = ensureLoaded()
                if (load != null) return@withContext load
                val tokenizer = loadedTokenizer
                    ?: return@withContext EmbeddingResult.Failure(
                        EmbeddingFailureReason.TOKENIZER_FAILURE,
                        "tokenizer unavailable",
                    ).also { lastFailure = EmbeddingFailureReason.TOKENIZER_FAILURE }
                val batch = tokenizer.encode(texts)
                    ?: return@withContext EmbeddingResult.Failure(
                        EmbeddingFailureReason.TOKENIZER_FAILURE,
                        "tokenizer unavailable",
                    ).also { lastFailure = EmbeddingFailureReason.TOKENIZER_FAILURE }
                val vectors = nativeLifetime.withInference { active -> runBatched(active, batch) }
                    ?: return@withContext EmbeddingResult.Failure(
                        EmbeddingFailureReason.CLOSED,
                        "engine closed",
                    )
                lastBatchSize = texts.size
                lastLatencyMs = (System.nanoTime() - started) / 1_000_000L
                lastFailure = null
                EmbeddingResult.Success(vectors, KoEnE5ModelPolicy.EMBEDDING_DIM)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            lastFailure = EmbeddingFailureReason.OUT_OF_MEMORY
            EmbeddingResult.Failure(EmbeddingFailureReason.OUT_OF_MEMORY, "embedding OOM")
        } catch (e: Exception) {
            lastFailure = EmbeddingFailureReason.INFERENCE_FAILURE
            EmbeddingResult.Failure(
                EmbeddingFailureReason.INFERENCE_FAILURE,
                (e::class.simpleName ?: "INFERENCE_FAILURE").take(80),
            )
        }
    }

    private suspend fun ensureLoaded(): EmbeddingResult.Failure? {
        // Fast path without lock; creation serialized below.
        if (nativeLifetime.hasLiveResources) return null
        loadMutex.withLock {
            coroutineContext.ensureActive()
            if (nativeLifetime.isClosed) {
                return EmbeddingResult.Failure(EmbeddingFailureReason.CLOSED, "engine closed")
            }
            if (nativeLifetime.hasLiveResources) return null
            val modelState = assets.modelAsset()
            if (modelState is KoEnE5AssetState.Missing) {
                lastFailure = EmbeddingFailureReason.ASSET_MISSING
                return EmbeddingResult.Failure(EmbeddingFailureReason.ASSET_MISSING, modelState.reason.take(120))
            }
            val tokenizerState = assets.tokenizerAsset()
            if (tokenizerState is KoEnE5AssetState.Missing) {
                // Report early so JVM unit tests never touch native code.
                lastFailure = EmbeddingFailureReason.TOKENIZER_FAILURE
                return EmbeddingResult.Failure(EmbeddingFailureReason.TOKENIZER_FAILURE, tokenizerState.reason.take(120))
            }
            val tokenizer = tokenizerProvider.tokenizerFor(
                (tokenizerState as KoEnE5AssetState.Available).file,
            ) ?: run {
                lastFailure = EmbeddingFailureReason.TOKENIZER_FAILURE
                return EmbeddingResult.Failure(EmbeddingFailureReason.TOKENIZER_FAILURE, "tokenizer load failed")
            }
            return try {
                val started = System.nanoTime()
                val file = (modelState as KoEnE5AssetState.Available).file
                // File-path session: the verified app-private model file is
                // memory-mapped by ONNX Runtime instead of duplicating ~38 MB
                // through a managed-heap ByteArray. No byte[] fallback is kept:
                // createSession(String, SessionOptions) is standard ORT Android
                // API and the provisioner guarantees a verified file exists.
                val modelPath = file.absolutePath
                val environment = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions()
                val optionsClosed = AtomicBoolean(false)
                fun closeOptionsOnce() {
                    if (optionsClosed.compareAndSet(false, true)) options.close()
                }
                var resources: KoEnE5NativeResourceBundle<OrtSession, OrtSession.SessionOptions>? = null
                var published = false
                try {
                    options.setIntraOpNumThreads(threads.coerceIn(1, 4))
                    options.setInterOpNumThreads(1)
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    options.addCPU(true)
                    resources = createKoEnE5NativeResourceBundle(
                        sessionOptions = options,
                        createSession = { environment.createSession(modelPath, options) },
                        closeSession = { it.close() },
                        closeSessionOptions = { closeOptionsOnce() },
                    )
                    if (!nativeLifetime.publish(resources)) {
                        return EmbeddingResult.Failure(EmbeddingFailureReason.CLOSED, "engine closed")
                    }
                    published = true
                    loadedTokenizer = tokenizer
                    loadLatencyMs = (System.nanoTime() - started) / 1_000_000L
                    lastFailure = null
                    null
                } finally {
                    if (!published) {
                        resources?.close()
                        if (resources == null) closeOptionsOnce()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OutOfMemoryError) {
                lastFailure = EmbeddingFailureReason.OUT_OF_MEMORY
                EmbeddingResult.Failure(EmbeddingFailureReason.OUT_OF_MEMORY, "model load OOM")
            } catch (e: Exception) {
                lastFailure = EmbeddingFailureReason.MODEL_LOAD_FAILURE
                EmbeddingResult.Failure(
                    EmbeddingFailureReason.MODEL_LOAD_FAILURE,
                    (e::class.simpleName ?: "LOAD_FAILED").take(80),
                )
            }
        }
    }

    private suspend fun runBatched(
        active: OrtSession,
        batch: KoEnE5TokenBatch,
    ): List<FloatArray> {
        val out = ArrayList<FloatArray>(batch.inputIds.size)
        var cursor = 0
        val step = inferenceBatchSize.coerceIn(1, FusionEmbeddingLimits.MAX_INFERENCE_BATCH)
        while (cursor < batch.inputIds.size) {
            coroutineContext.ensureActive()
            val end = minOf(cursor + step, batch.inputIds.size)
            out += runOnce(
                active,
                batch.inputIds.sliceArray(cursor until end),
                batch.attentionMask.sliceArray(cursor until end),
            )
            cursor = end
        }
        return out
    }

    private fun runOnce(
        active: OrtSession,
        ids: Array<LongArray>,
        mask: Array<LongArray>,
    ): List<FloatArray> {
        val inputNames = runCatching { active.inputNames }.getOrNull().orEmpty()
        return withSequentialCloseableResources(
            acquire1 = {
                OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), ids as Any)
            },
            acquire2 = {
                OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), mask as Any)
            },
            acquire3 = {
                OnnxTensor.createTensor(
                    OrtEnvironment.getEnvironment(),
                    Array(ids.size) { LongArray(ids[0].size) } as Any,
                )
            },
        ) { idsTensor, maskTensor, typeTensor ->
            val inputs = linkedMapOf<String, OnnxTensor>()
            fun put(name: String, tensor: OnnxTensor) {
                inputs[name] = tensor
            }
            when {
                "input_ids" in inputNames && "attention_mask" in inputNames -> {
                    put("input_ids", idsTensor)
                    put("attention_mask", maskTensor)
                    inputNames.firstOrNull { it != "input_ids" && it != "attention_mask" }
                        ?.let { put(it, typeTensor) }
                }
                inputNames.size >= 3 -> {
                    put(inputNames.elementAt(0), idsTensor)
                    put(inputNames.elementAt(1), maskTensor)
                    put(inputNames.elementAt(2), typeTensor)
                }
                inputNames.size == 2 -> {
                    put(inputNames.elementAt(0), idsTensor)
                    put(inputNames.elementAt(1), maskTensor)
                }
                inputNames.size == 1 -> {
                    put(inputNames.first(), idsTensor)
                }
                else -> {
                    put("input_ids", idsTensor)
                    put("attention_mask", maskTensor)
                }
            }
            active.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                val hidden = result[0].value as Array<Array<FloatArray>>
                if (hidden.size != ids.size) throw IllegalStateException("batch mismatch")
                hidden.mapIndexed { index, seq ->
                    SemanticSimilarity.normalize(SemanticSimilarity.meanPool(seq, mask[index]))
                }
            }
        }
    }

    private fun unsupportedAbiReason(): EmbeddingResult.Failure? {
        val abis = runCatching {
            Class.forName("android.os.Build")
                .getDeclaredField("SUPPORTED_ABIS")
                .get(null) as? Array<*>
        }.getOrNull() ?: return null // JVM unit tests: no Android ABI gate.
        val supported = abis.filterIsInstance<String>().map { it.lowercase() }
        if (supported.any { it == "arm64-v8a" || it == "x86_64" }) return null
        lastFailure = EmbeddingFailureReason.UNSUPPORTED_ABI
        return EmbeddingResult.Failure(EmbeddingFailureReason.UNSUPPORTED_ABI, "ABI unsupported")
    }

    /** Idempotent: safe to call multiple times, from any thread. */
    override fun close() {
        nativeLifetime.close()
        loadedTokenizer = null
    }
}
