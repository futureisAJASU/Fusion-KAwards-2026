package com.projectnuke.fusion.verifier

import com.projectnuke.fusion.model.RuntimeCpuPolicyInput
import com.projectnuke.fusion.model.RuntimeCpuThreadPolicy
import com.projectnuke.fusion.model.RuntimePerformancePreference
import com.projectnuke.fusion.audit.VerificationPairRequest
import com.projectnuke.fusion.audit.VerifierBackend
import com.projectnuke.fusion.audit.VerifierBackendAvailability
import com.projectnuke.fusion.audit.VerifierBackendDescriptor
import com.projectnuke.fusion.audit.VerifierBackendResult
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal suspend fun verifyBackendForGeneration(
    backend: VerifierBackend,
    request: VerificationPairRequest,
    expectedGeneration: Long,
    currentGeneration: () -> Long,
): VerifierBackendResult {
    if (currentGeneration() != expectedGeneration) return VerifierBackendResult.Unavailable
    val result = backend.verify(request)
    return if (currentGeneration() == expectedGeneration) result else VerifierBackendResult.Unavailable
}

/** One process-wide lazy runtime. Stable proxies survive background/pressure close and recreate on demand. */
object FusionVerifierRuntimeOwner {
    val descriptor = VerifierBackendDescriptor(
        id = "fusion-me5-koen-manifest",
        version = "v0.9c",
        supportedLanguages = setOf("ko", "en"),
        experimental = true,
        maxPairLength = FusionVerifierModelPolicy.MAX_SEQUENCE_LENGTH,
        availability = VerifierBackendAvailability.AVAILABLE,
    )

    private val creationMutex = Mutex()
    private val stateLock = Any()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanupFailures = mutableListOf<Throwable>()
    private val cleanupOwner = VerifierBackendCleanupOwner(cleanupScope, ::recordCleanupFailure)
    private var generation = 0L
    private var backend: OnnxEvidenceVerifierBackend? = null
    private var backendDir: File? = null
    private var backendPreference: RuntimePerformancePreference? = null
    private val proxies = HashMap<String, VerifierBackend>()
    internal var tokenizerOverrideForTests: XlmRPairTokenizer? = null
    internal var beforeGenerationValidationForTests: (() -> Unit)? = null

    private fun resolveThreadsForCurrentGeneration(
        input: RuntimeCpuPolicyInput,
        verifierDir: File,
        manifest: FusionVerifierModelManifest,
    ): Int {
        val sameRuntime = backend?.takeIf {
            backendDir == verifierDir.absoluteFile.normalize() && it.loadedManifest == manifest
        }
        val previous = sameRuntime?.loadedIntraOpThreads?.let { threads ->
            com.projectnuke.fusion.model.RuntimeCpuThreadResolution(
                threadCount = threads,
                deviceCeiling = RuntimeCpuThreadPolicy.deviceCeiling(input.deviceSignals),
                reason = com.projectnuke.fusion.model.RuntimeCpuResolutionReason.RETAINED_FOR_RUNTIME_GENERATION,
            )
        }
        return RuntimeCpuThreadPolicy.resolve(
            preference = input.preference,
            signals = input.deviceSignals,
            previous = previous,
            previousPreference = backendPreference,
        ).threadCount
    }

    fun proxy(
        verifierDir: File,
        cpuPolicyProvider: () -> RuntimeCpuPolicyInput = {
            RuntimeCpuPolicyInput(
                RuntimePerformancePreference(),
                com.projectnuke.fusion.model.RuntimeCpuDeviceSignals(Runtime.getRuntime().availableProcessors()),
            )
        },
    ): VerifierBackend {
        val canonical = verifierDir.absoluteFile.normalize()
        val key = canonical.path
        return synchronized(stateLock) { proxies.getOrPut(key) { FusionVerifierBackendProxyForPath(canonical, cpuPolicyProvider) } }
    }

    internal suspend fun verify(
        verifierDir: File,
        request: VerificationPairRequest,
        cpuPolicyProvider: () -> RuntimeCpuPolicyInput,
    ): VerifierBackendResult {
        val requestGeneration = synchronized(stateLock) { generation }
        val activeManifest = FusionVerifierModelManifest.parse(File(verifierDir, FusionVerifierModelPolicy.MANIFEST_FILE))
        if (activeManifest == null) {
            closeBackendForDirectory(verifierDir)
            return VerifierBackendResult.Unavailable
        }
        val policyInput = cpuPolicyProvider()
        val requestedThreads = synchronized(stateLock) {
            resolveThreadsForCurrentGeneration(policyInput, verifierDir, activeManifest)
        }
        val current = synchronized(stateLock) {
            backend?.takeIf {
                backendDir == verifierDir.absoluteFile.normalize() &&
                    it.loadedManifest == activeManifest &&
                    it.loadedIntraOpThreads == requestedThreads
            }
        }
        if (current != null) {
            synchronized(stateLock) {
                if (generation == requestGeneration) backendPreference = policyInput.preference.normalized()
            }
            return verifyBackendForGeneration(current, request, requestGeneration) {
                synchronized(stateLock) { generation }
            }
        }
        return try {
            creationMutex.withLock {
                if (synchronized(stateLock) { generation != requestGeneration }) {
                    return@withLock VerifierBackendResult.Unavailable
                }
                val latestInput = cpuPolicyProvider()
                val latestThreads = synchronized(stateLock) {
                    resolveThreadsForCurrentGeneration(latestInput, verifierDir, activeManifest)
                }
                val existing = synchronized(stateLock) {
                    backend?.takeIf {
                        backendDir == verifierDir.absoluteFile.normalize() &&
                            it.loadedManifest == activeManifest &&
                            it.loadedIntraOpThreads == latestThreads
                    }
                }
                if (existing != null) {
                    synchronized(stateLock) {
                        if (generation == requestGeneration) backendPreference = latestInput.preference.normalized()
                    }
                    return@withLock verifyBackendForGeneration(existing, request, requestGeneration) {
                        synchronized(stateLock) { generation }
                    }
                }
                val detached = synchronized(stateLock) {
                    if (backendDir == verifierDir.absoluteFile.normalize()) {
                        backend.also { backend = null; backendDir = null; backendPreference = null }
                    } else null
                }
                dispose(detached)
                beforeGenerationValidationForTests?.invoke()
                if (synchronized(stateLock) { generation != requestGeneration }) {
                    return@withLock VerifierBackendResult.Unavailable
                }
                val tokenizerOverride = synchronized(stateLock) { tokenizerOverrideForTests }
                val created = withContext(Dispatchers.IO) {
                    OnnxEvidenceVerifierBackend(
                        FusionVerifierAssetProvider(verifierDir),
                        intraOpThreads = latestThreads,
                        tokenizerLoader = { file -> tokenizerOverride ?: XlmRPairTokenizer.load(file) },
                        expectedManifest = activeManifest,
                    )
                }
                synchronized(stateLock) {
                    if (generation != requestGeneration) {
                        created.close()
                        return@withLock VerifierBackendResult.Unavailable
                    }
                    val old = backend
                    backend = created
                    backendDir = verifierDir.absoluteFile.normalize()
                    backendPreference = latestInput.preference.normalized()
                    dispose(old)
                }
                verifyBackendForGeneration(created, request, requestGeneration) {
                    synchronized(stateLock) { generation }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            VerifierBackendResult.Unavailable
        }
    }

    /** Nonblocking lifecycle release. Inference leases defer native close safely. */
    fun close() {
        val detached = synchronized(stateLock) {
            generation++
            backend.also { backend = null; backendDir = null; backendPreference = null }
        }
        dispose(detached)
    }

    private fun closeBackendForDirectory(verifierDir: File) {
        val detached = synchronized(stateLock) {
            if (backendDir != verifierDir.absoluteFile.normalize()) return
            backend.also { backend = null; backendDir = null; backendPreference = null; generation++ }
        }
        dispose(detached)
    }

    private fun dispose(value: OnnxEvidenceVerifierBackend?) {
        if (value != null) cleanupOwner.dispose(value::close)
    }

    private fun recordCleanupFailure(failure: Throwable) {
        synchronized(stateLock) {
            if (cleanupFailures.size >= 8) cleanupFailures.removeAt(0)
            cleanupFailures += failure
        }
        Log.e("FusionVerifierRuntime", "Verifier backend cleanup failed", failure)
    }

    internal fun currentBackendForTests(): OnnxEvidenceVerifierBackend? = synchronized(stateLock) { backend }
    internal fun currentTokenizerForTests(): XlmRPairTokenizer? = synchronized(stateLock) { backend?.tokenizerForTests() }
    internal fun cleanupFailuresForTests(): List<Throwable> = synchronized(stateLock) { cleanupFailures.toList() }

    internal fun resetForTests() {
        close()
        synchronized(stateLock) {
            proxies.clear()
            tokenizerOverrideForTests = null
            beforeGenerationValidationForTests = null
            generation = 0
            cleanupFailures.clear()
        }
    }
}

private class FusionVerifierBackendProxyForPath(
    private val verifierDir: File,
    private val cpuPolicyProvider: () -> RuntimeCpuPolicyInput,
) : VerifierBackend {
    override val descriptor get() = FusionVerifierRuntimeOwner.descriptor
    override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult =
        FusionVerifierRuntimeOwner.verify(verifierDir, request, cpuPolicyProvider)
}
