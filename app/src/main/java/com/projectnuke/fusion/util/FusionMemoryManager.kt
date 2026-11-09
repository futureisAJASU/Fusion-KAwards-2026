package com.projectnuke.fusion.util

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.projectnuke.fusion.llm.FusionRuntimeLock
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

data class FusionMemorySnapshot(
    val totalMem: Long,
    val availMem: Long,
    val lowMemory: Boolean,
    val threshold: Long
)

data class FusionRuntimeMemoryInfo(
    val modelPath: String? = null,
    val modelFileSizeBytes: Long? = null,
    val actualBackend: String? = null,
    val kvCapacityTokens: Int? = null,
)

object FusionMemoryManager {
    private const val LowRamDeviceBytes = 8L * 1024L * 1024L * 1024L
    private const val LowMemoryBenchmarkFloorBytes = 1536L * 1024L * 1024L
    private const val IdleRuntimePressureFloorBytes = 1536L * 1024L * 1024L
    private const val GpuRuntimeReserveFloorBytes = 256L * 1024L * 1024L
    private const val CpuRuntimeReserveFloorBytes = 128L * 1024L * 1024L
    private val lightweightCacheClearers = CopyOnWriteArrayList<() -> Unit>()
    private val idleEngineUnloaders = CopyOnWriteArrayList<() -> Unit>()

    fun getMemorySnapshot(context: Context): FusionMemorySnapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        return FusionMemorySnapshot(
            totalMem = memoryInfo.totalMem,
            availMem = memoryInfo.availMem,
            lowMemory = memoryInfo.lowMemory,
            threshold = memoryInfo.threshold
        )
    }

    fun isLowRamDevice(context: Context): Boolean {
        val snapshot = getMemorySnapshot(context)
        return snapshot.totalMem in 1..LowRamDeviceBytes
    }

    fun recommendedBenchmarkMaxTokens(context: Context, userMaxTokens: Int): Int {
        val snapshot = getMemorySnapshot(context)
        return recommendedBenchmarkMaxTokens(snapshot, userMaxTokens)
    }

    internal fun recommendedBenchmarkMaxTokens(
        snapshot: FusionMemorySnapshot,
        userMaxTokens: Int,
    ): Int {
        val tightFloor = snapshot.threshold.coerceAtLeast(LowMemoryBenchmarkFloorBytes) * 2
        return when {
            snapshot.lowMemory || snapshot.availMem <= 0L || snapshot.availMem < tightFloor -> userMaxTokens.coerceAtMost(1024)
            snapshot.totalMem in 1..LowRamDeviceBytes -> userMaxTokens.coerceAtMost(2048)
            else -> userMaxTokens
        }
    }

    fun shouldBlockBenchmark(context: Context): Boolean {
        val snapshot = getMemorySnapshot(context)
        return shouldBlockBenchmark(snapshot)
    }

    internal fun shouldBlockBenchmark(snapshot: FusionMemorySnapshot): Boolean {
        val safeFloor = snapshot.threshold.coerceAtLeast(LowMemoryBenchmarkFloorBytes)
        return snapshot.lowMemory || snapshot.availMem <= 0L || snapshot.availMem < safeFloor
    }

    fun logMemorySnapshot(tag: String, context: Context, reason: String) {
        val snapshot = getMemorySnapshot(context)
        Log.i(
            tag,
            "reason=$reason totalMem=${snapshot.totalMem} availMem=${snapshot.availMem} lowMemory=${snapshot.lowMemory} threshold=${snapshot.threshold}"
        )
    }

    fun logRuntimeSnapshot(
        tag: String,
        context: Context,
        reason: String,
        runtimeInfo: FusionRuntimeMemoryInfo = FusionRuntimeMemoryInfo(),
    ) {
        val snapshot = getMemorySnapshot(context)
        val runtimeState = FusionRuntimeLock.ownerSnapshot()
        val modelBasename = runtimeInfo.modelPath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it).name }
            ?: "none"
        Log.i(
            tag,
            "reason=$reason totalMem=${snapshot.totalMem} availMem=${snapshot.availMem} " +
                "lowMemory=${snapshot.lowMemory} threshold=${snapshot.threshold} " +
                "runtimeState=${runtimeState::class.simpleName} " +
                "runtimeActive=${runtimeState !is FusionRuntimeLock.RuntimeOwnerState.Idle} " +
                "actualBackend=${runtimeInfo.actualBackend ?: "unknown"} " +
                "model=$modelBasename kvCapacityTokens=${runtimeInfo.kvCapacityTokens ?: 0}",
        )
    }

    /**
     * Central pressure policy for the shared idle LiteRT wrapper.
     *
     * The policy is intentionally limited to 8GB-class devices. It reuses the
     * existing 1.5GB/threshold pressure convention and adds the loaded model
     * footprint plus a backend reserve when that is known. Healthy memory keeps
     * runtime reuse; high pressure asks only idle owners to release.
     */
    internal fun shouldReleaseIdleRuntime(
        snapshot: FusionMemorySnapshot,
        runtimeInfo: FusionRuntimeMemoryInfo = FusionRuntimeMemoryInfo(),
    ): Boolean {
        if (snapshot.totalMem <= 0L || snapshot.totalMem > LowRamDeviceBytes) return false
        if (snapshot.lowMemory || snapshot.availMem <= 0L) return true

        val highPressureFloor = maxOf(
            IdleRuntimePressureFloorBytes,
            snapshot.threshold * 2L,
        )
        if (snapshot.availMem < highPressureFloor) return true

        val backendReserve = when (runtimeInfo.actualBackend?.uppercase()) {
            "GPU" -> maxOf(GpuRuntimeReserveFloorBytes, snapshot.threshold)
            "CPU" -> maxOf(CpuRuntimeReserveFloorBytes, snapshot.threshold / 2L)
            else -> snapshot.threshold
        }
        val modelSize = runtimeInfo.modelFileSizeBytes?.takeIf { it > 0L } ?: return false
        return snapshot.availMem < modelSize + backendReserve
    }

    /** Requests a release only when the shared runtime is idle and pressure is real. */
    fun requestPressureAwareIdleEngineUnload(
        context: Context,
        runtimeInfo: FusionRuntimeMemoryInfo = FusionRuntimeMemoryInfo(),
        reason: String,
    ): Boolean {
        logRuntimeSnapshot("FusionMemory", context, "POST_GENERATION_MEMORY:$reason", runtimeInfo)
        if (FusionRuntimeLock.isChatGenerationRunning || FusionRuntimeLock.isBenchmarkRunning) {
            Log.i("FusionMemory", "Idle runtime unload skipped while runtime is active reason=$reason")
            return false
        }
        val snapshot = getMemorySnapshot(context)
        if (!shouldReleaseIdleRuntime(snapshot, runtimeInfo)) return false

        Log.i("FusionMemory", "IDLE_UNLOAD_REQUESTED reason=$reason")
        unloadIdleEngines()
        return true
    }

    fun registerLightweightCacheClearer(clearer: () -> Unit): () -> Unit {
        lightweightCacheClearers.add(clearer)
        return { lightweightCacheClearers.remove(clearer) }
    }

    fun registerIdleEngineUnloader(unloader: () -> Unit): () -> Unit {
        idleEngineUnloaders.add(unloader)
        return { idleEngineUnloaders.remove(unloader) }
    }

    fun clearLightweightCaches() {
        lightweightCacheClearers.forEach { clearer ->
            runCatching { clearer() }
                .onFailure { Log.w("FusionMemory", "Failed to clear lightweight cache", it) }
        }
    }

    fun unloadIdleEngines() {
        if (FusionRuntimeLock.isChatGenerationRunning || FusionRuntimeLock.isBenchmarkRunning) {
            Log.i("FusionMemory", "Idle runtime unload skipped while runtime is active")
            return
        }
        idleEngineUnloaders.forEach { unloader ->
            runCatching { unloader() }
                .onFailure { Log.w("FusionMemory", "Failed to unload idle engine", it) }
        }
    }
}
