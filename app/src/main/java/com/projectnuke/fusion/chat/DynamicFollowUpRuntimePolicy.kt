package com.projectnuke.fusion.chat

import android.content.Context
import android.content.pm.ApplicationInfo
import com.projectnuke.fusion.llm.BackgroundTerminationSource
import java.util.concurrent.atomic.AtomicLong

/**
 * Internal release-containment switch for the optional native enhancement.
 * Keep false during development and qualification; set true only if the RC
 * decision disables dynamic native follow-up inference. Deterministic next
 * actions do not consult this policy.
 */
internal object DynamicFollowUpRuntimePolicy {
    const val DISABLE_NATIVE_FOR_RC_CONTAINMENT = false
    const val QUALIFICATION_PREFERENCES = "dynamic_followup_qualification"
    const val QUALIFICATION_TIMEOUT_KEY = "native_timeout_ms"

    @Volatile
    internal var testDisableNative: Boolean? = null

    fun isNativeGenerationEnabled(): Boolean =
        !(testDisableNative ?: DISABLE_NATIVE_FOR_RC_CONTAINMENT)

    /** Debug-only physical qualification override; release builds always use the production value. */
    fun nativeTimeoutMs(context: Context): Long {
        val isDebugBuild = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val override = if (isDebugBuild) {
            context.getSharedPreferences(QUALIFICATION_PREFERENCES, Context.MODE_PRIVATE)
                .takeIf { it.contains(QUALIFICATION_TIMEOUT_KEY) }
                ?.getLong(QUALIFICATION_TIMEOUT_KEY, DynamicFollowUpPolicy.TIMEOUT_MS)
        } else {
            null
        }
        return resolveTimeoutMs(
            productionTimeoutMs = DynamicFollowUpPolicy.TIMEOUT_MS,
            qualificationOverrideMs = override,
            isDebugBuild = isDebugBuild,
        )
    }

    internal fun resolveTimeoutMs(
        productionTimeoutMs: Long,
        qualificationOverrideMs: Long?,
        isDebugBuild: Boolean,
    ): Long = if (isDebugBuild && qualificationOverrideMs != null) {
        qualificationOverrideMs.coerceIn(1L, productionTimeoutMs)
    } else {
        productionTimeoutMs
    }

    suspend fun <T> runNativeGenerationIfEnabled(block: suspend () -> T): T? =
        if (isNativeGenerationEnabled()) block() else null
}

/**
 * Per-owner publication lease. Disposing or changing the UI owner retires
 * publication and notifies the native lifetime without cancelling a started
 * Conversation.
 */
internal class DynamicFollowUpOwnershipLease {
    val generation: Long = nextGeneration.incrementAndGet()

    private val lock = Any()
    private val listeners = linkedSetOf<(BackgroundTerminationSource) -> Unit>()
    private var retirementSource: BackgroundTerminationSource? = null

    val isCurrent: Boolean
        get() = synchronized(lock) { retirementSource == null }

    fun invalidate(source: BackgroundTerminationSource) {
        val pending = synchronized(lock) {
            if (retirementSource != null) return
            retirementSource = source
            listeners.toList().also { listeners.clear() }
        }
        pending.forEach { it(source) }
    }

    fun observeRetirement(listener: (BackgroundTerminationSource) -> Unit): AutoCloseable {
        val alreadyRetired: BackgroundTerminationSource?
        synchronized(lock) {
            alreadyRetired = retirementSource
            if (alreadyRetired == null) listeners += listener
        }
        alreadyRetired?.let(listener)
        return AutoCloseable { synchronized(lock) { listeners -= listener } }
    }

    companion object {
        private val nextGeneration = AtomicLong()
    }
}
