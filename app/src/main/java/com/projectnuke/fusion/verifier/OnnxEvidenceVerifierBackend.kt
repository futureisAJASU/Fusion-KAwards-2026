package com.projectnuke.fusion.verifier

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.projectnuke.fusion.audit.AuditBackendFailureReason
import com.projectnuke.fusion.audit.AuditVerificationLabel
import com.projectnuke.fusion.audit.VerificationPairRequest
import com.projectnuke.fusion.audit.VerifierBackendAvailability
import com.projectnuke.fusion.audit.VerifierBackendDescriptor
import com.projectnuke.fusion.audit.VerifierBackendResult
import com.projectnuke.fusion.audit.VerifierBackend
import com.projectnuke.fusion.audit.VerifierConfidenceScore
import com.projectnuke.fusion.retrieval.KoEnE5NativeLifetime
import com.projectnuke.fusion.retrieval.KoEnE5NativeResourceBundle
import com.projectnuke.fusion.retrieval.createKoEnE5NativeResourceBundle
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Supplemental semantic classifier. Rule Evidence remains the durable authority. */
class OnnxEvidenceVerifierBackend internal constructor(
    private val assets: FusionVerifierAssetProvider,
    private val intraOpThreads: Int = FusionVerifierExecutionPolicy.INTRA_OP_THREADS,
    private val tokenizerLoader: (java.io.File) -> XlmRPairTokenizer = XlmRPairTokenizer::load,
    private val verifyAssets: ((() -> Unit) -> FusionVerifierAssetResult)? = null,
    private val expectedManifest: FusionVerifierModelManifest? = null,
) : VerifierBackend, AutoCloseable {
    override val descriptor = VerifierBackendDescriptor(
        id = "fusion-me5-koen-onnx",
        version = "v0.9c",
        supportedLanguages = setOf("ko", "en"),
        experimental = true,
        maxPairLength = FusionVerifierModelPolicy.MAX_SEQUENCE_LENGTH,
        availability = VerifierBackendAvailability.AVAILABLE,
    )

    private val loadMutex = Mutex()
    private val native = KoEnE5NativeLifetime<OrtSession, OrtSession.SessionOptions>()
    @Volatile private var tokenizer: XlmRPairTokenizer? = null
    @Volatile var loadedManifest: FusionVerifierModelManifest? = null
        private set
    internal val loadedIntraOpThreads: Int get() = intraOpThreads
    @Volatile private var closed = false
    @Volatile var diagnostics = VerifierRuntimeDiagnostics()
        private set

    override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult {
        coroutineContext.ensureActive()
        val totalStarted = System.nanoTime()
        if (request.claimText.isBlank() || request.evidenceText.isBlank()) {
            return VerifierBackendResult.Failure(AuditBackendFailureReason.INVALID_OUTPUT)
        }
        return try {
            withContext(Dispatchers.IO.limitedParallelism(1)) {
                coroutineContext.ensureActive()
                when (val load = ensureLoaded()) {
                    LoadResult.Unavailable -> return@withContext VerifierBackendResult.Unavailable
                    is LoadResult.Failure -> return@withContext VerifierBackendResult.Failure(load.reason)
                    LoadResult.Invalid -> return@withContext VerifierBackendResult.Failure(AuditBackendFailureReason.INVALID_OUTPUT)
                    LoadResult.Ready -> Unit
                }
                coroutineContext.ensureActive()
                val tokenStarted = System.nanoTime()
                val encoded = tokenizer?.encode(request.evidenceText, request.claimText)
                    ?: return@withContext VerifierBackendResult.Failure(AuditBackendFailureReason.INVALID_OUTPUT)
                val tokenMs = elapsedMs(tokenStarted)
                coroutineContext.ensureActive()
                val inferenceStarted = System.nanoTime()
                val logits = native.withInference { session -> infer(session, encoded) }
                    ?: return@withContext VerifierBackendResult.Unavailable
                val inferenceMs = elapsedMs(inferenceStarted)
                val probabilities = stableSoftmax(logits)
                    ?: return@withContext VerifierBackendResult.Failure(AuditBackendFailureReason.INVALID_OUTPUT)
                val index = probabilities.indices.maxByOrNull { probabilities[it] }
                    ?: return@withContext VerifierBackendResult.Failure(AuditBackendFailureReason.INVALID_OUTPUT)
                val label = AuditVerificationLabel.entries.getOrNull(index)
                    ?: return@withContext VerifierBackendResult.Failure(AuditBackendFailureReason.INVALID_OUTPUT)
                diagnostics = diagnostics.copy(
                    tokenizationMs = tokenMs,
                    inferenceMs = inferenceMs,
                    totalVerificationMs = elapsedMs(totalStarted),
                    firstVerificationMs = diagnostics.firstVerificationMs ?: elapsedMs(totalStarted),
                )
                VerifierBackendResult.Verified(label, FusionVerifierModelPolicy.LABELS.mapIndexed { i, name ->
                    VerifierConfidenceScore(name, probabilities[i].toDouble())
                })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            VerifierBackendResult.Failure(AuditBackendFailureReason.EXECUTION_FAILED)
        }
    }

    private suspend fun ensureLoaded(): LoadResult {
        val activeContext = coroutineContext
        if (closed) return LoadResult.Unavailable
        if (native.hasLiveResources && tokenizer != null) return LoadResult.Ready
        return loadMutex.withLock {
            coroutineContext.ensureActive()
            if (closed) return@withLock LoadResult.Unavailable
            if (native.hasLiveResources && tokenizer != null) return@withLock LoadResult.Ready
            val assetStarted = System.nanoTime()
            val assetResult = try {
                verifyAssets?.invoke { activeContext.ensureActive() }
                    ?: assets.verify { activeContext.ensureActive() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                FusionVerifierAssetResult.Missing("ASSET_IO_FAILURE")
            }
            val verified = when (val result = assetResult) {
                is FusionVerifierAssetResult.Ready -> result.assets
                is FusionVerifierAssetResult.Missing -> return@withLock LoadResult.Unavailable
            }
            if (expectedManifest != null && verified.manifest != expectedManifest) {
                return@withLock LoadResult.Unavailable
            }
            diagnostics = diagnostics.copy(assetVerificationMs = elapsedMs(assetStarted))
            try {
                val tokenizerStarted = System.nanoTime()
                val loadedTokenizer = tokenizerLoader(verified.tokenizer)
                diagnostics = diagnostics.copy(tokenizerLoadMs = elapsedMs(tokenizerStarted))
                coroutineContext.ensureActive()
                val environment = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions()
                val optionsClosed = AtomicBoolean(false)
                fun closeOptions() { if (optionsClosed.compareAndSet(false, true)) options.close() }
                var bundle: KoEnE5NativeResourceBundle<OrtSession, OrtSession.SessionOptions>? = null
                var published = false
                try {
                    options.setIntraOpNumThreads(intraOpThreads.coerceAtLeast(1))
                    options.setInterOpNumThreads(FusionVerifierExecutionPolicy.INTER_OP_THREADS)
                    options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    options.addCPU(true)
                    coroutineContext.ensureActive()
                    val sessionStarted = System.nanoTime()
                    bundle = createKoEnE5NativeResourceBundle(
                        sessionOptions = options,
                        createSession = { environment.createSession(verified.model.absolutePath, options) },
                        closeSession = { it.close() },
                        closeSessionOptions = { closeOptions() },
                    )
                    diagnostics = diagnostics.copy(sessionCreateMs = elapsedMs(sessionStarted))
                    val names = bundle.session.inputNames
                    if (names != setOf("input_ids", "attention_mask") || bundle.session.outputNames != setOf("logits")) {
                        return@withLock LoadResult.Invalid
                    }
                    if (!native.publish(bundle)) return@withLock LoadResult.Unavailable
                    tokenizer = loadedTokenizer
                    loadedManifest = verified.manifest
                    published = true
                    LoadResult.Ready
                } finally {
                    if (!published) {
                        bundle?.close()
                        if (bundle == null) closeOptions()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LoadResult.Failure(AuditBackendFailureReason.EXECUTION_FAILED)
            }
        }
    }

    private fun infer(session: OrtSession, encoded: XlmRPairEncoding): FloatArray {
        val idsTensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), arrayOf(encoded.inputIds) as Any)
        try {
            val maskTensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), arrayOf(encoded.attentionMask) as Any)
            try {
                session.run(linkedMapOf("input_ids" to idsTensor, "attention_mask" to maskTensor)).use { result ->
                    val output = result[0].value as? Array<*> ?: error("output rank")
                    if (output.size != 1) error("output batch")
                    val row = output[0] as? FloatArray ?: error("output dtype")
                    if (row.size != 3 || row.any { !it.isFinite() }) error("invalid logits")
                    return row.copyOf()
                }
            } finally { maskTensor.close() }
        } finally { idsTensor.close() }
    }

    internal suspend fun inferForBenchmark(encoded: XlmRPairEncoding): FloatArray? {
        return when (ensureLoaded()) {
            LoadResult.Unavailable, LoadResult.Invalid, is LoadResult.Failure -> null
            LoadResult.Ready -> native.withInference { session -> infer(session, encoded) }
        }
    }

    internal fun tokenizerForTests(): XlmRPairTokenizer? = tokenizer

    override fun close() {
        closed = true
        tokenizer = null
        loadedManifest = null
        native.close()
    }

    private sealed interface LoadResult {
        data object Ready : LoadResult
        data object Unavailable : LoadResult
        data object Invalid : LoadResult
        data class Failure(val reason: AuditBackendFailureReason) : LoadResult
    }

    companion object {
        private fun elapsedMs(started: Long): Double = (System.nanoTime() - started) / 1_000_000.0

        fun stableSoftmax(logits: FloatArray): DoubleArray? {
            if (logits.size != 3 || logits.any { !it.isFinite() }) return null
            val max = logits.maxOrNull()?.toDouble() ?: return null
            val values = DoubleArray(3) { kotlin.math.exp(logits[it].toDouble() - max) }
            val sum = values.sum()
            if (!sum.isFinite() || sum <= 0.0) return null
            val probs = DoubleArray(3) { values[it] / sum }
            if (probs.any { !it.isFinite() || it !in 0.0..1.0 } || kotlin.math.abs(probs.sum() - 1.0) > 1e-9) return null
            return probs
        }
    }
}

data class VerifierRuntimeDiagnostics(
    val assetVerificationMs: Double? = null,
    val tokenizerLoadMs: Double? = null,
    val sessionCreateMs: Double? = null,
    val tokenizationMs: Double? = null,
    val inferenceMs: Double? = null,
    val totalVerificationMs: Double? = null,
    val firstVerificationMs: Double? = null,
)
