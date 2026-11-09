package com.projectnuke.fusion.llm

import android.content.Context
import android.util.Log

/**
 * Stable process owner for a runtime wrapper. Unload clears only the wrapper's
 * native state; [acquire] never replaces an already published wrapper.
 */
internal class StableRuntimeOwner<T : Any> {
    private val lock = Any()
    private var instance: T? = null

    fun acquire(factory: () -> T): T = synchronized(lock) {
        instance ?: factory().also { instance = it }
    }

    fun unload(action: (T) -> Unit): Boolean {
        synchronized(lock) {
            val current = instance ?: return false
            action(current)
            return true
        }
    }

    internal fun currentIdentity(): T? = synchronized(lock) { instance }
}

object FusionRuntimeManager {
    private val owner = StableRuntimeOwner<LiteRtLlmEngine>()

    /**
     * Returns the one stable process wrapper. UI code may retain this identity:
     * unload never orphans it and the next generation reloads its native engine.
     */
    fun sharedEngine(context: Context): LiteRtLlmEngine =
        owner.acquire {
            LiteRtLlmEngine(
                context.applicationContext,
                failureMemoryStorage = PrefsMtpFailureMemoryStorage(context.applicationContext)
            )
        }

    fun unloadSharedEngineIfIdle(reason: String) {
        if (FusionRuntimeLock.isChatGenerationRunning || FusionRuntimeLock.isBenchmarkRunning) return
        unloadStableWrapper("idle:$reason", idle = true)
    }

    suspend fun unloadSharedEngineWhenRuntimeIdle(reason: String) {
        if (FusionRuntimeLock.isChatGenerationRunning || FusionRuntimeLock.isBenchmarkRunning) return
        FusionRuntimeLock.withLock {
            if (FusionRuntimeLock.isChatGenerationRunning || FusionRuntimeLock.isBenchmarkRunning) {
                return@withLock
            }
            unloadStableWrapper("runtime-idle:$reason", idle = true)
        }
    }

    /**
     * Call only while the caller owns [FusionRuntimeLock]'s exclusive runtime
     * section. This keeps benchmark model switches serialized with generation.
     */
    fun unloadSharedEngineAfterExclusive(reason: String) {
        unloadStableWrapper("exclusive:$reason", idle = false)
    }

    /**
     * Call only from the active chat owner while it holds the runtime lock.
     */
    fun unloadSharedEngineForActiveOwner(reason: String) {
        unloadStableWrapper("active-owner:$reason", idle = false)
    }

    /**
     * Logs `IDLE_UNLOAD_COMPLETE` only after the shared engine's native
     * release actually returns for an idle unload; a skipped unload (runtime
     * active, nothing loaded, or failure) never reports completion.
     */
    private fun unloadStableWrapper(reason: String, idle: Boolean) {
        val unloaded = runCatching {
            owner.unload { it.unload() }
        }.onFailure {
            Log.e("FusionEngine", "Failed to unload shared runtime: $reason", it)
        }.getOrDefault(false)
        if (idle && unloaded) {
            Log.i("FusionMemory", "IDLE_UNLOAD_COMPLETE reason=$reason")
        }
    }
}
