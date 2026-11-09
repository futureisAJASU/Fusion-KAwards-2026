package com.projectnuke.fusion.model

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlin.math.roundToInt

enum class RuntimePerformanceMode { AUTO, MANUAL }

data class RuntimePerformancePreference(
    val mode: RuntimePerformanceMode = RuntimePerformanceMode.AUTO,
    val manualLevel: Int = DEFAULT_MANUAL_LEVEL,
) {
    fun normalized(): RuntimePerformancePreference = copy(manualLevel = manualLevel.coerceIn(0, 100))

    companion object {
        const val DEFAULT_MANUAL_LEVEL = 50
    }
}

data class RuntimeCpuDeviceSignals(
    val availableProcessors: Int,
    val isLowRamDevice: Boolean = false,
    val powerSaveMode: Boolean = false,
    val thermalStatus: Int = THERMAL_UNKNOWN,
) {
    companion object {
        const val THERMAL_UNKNOWN = -1
        const val THERMAL_NONE = 0
        const val THERMAL_MODERATE = 2

        fun observe(context: Context): RuntimeCpuDeviceSignals {
            val app = context.applicationContext
            val activity = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                power?.currentThermalStatus ?: THERMAL_UNKNOWN
            } else {
                THERMAL_UNKNOWN
            }
            return RuntimeCpuDeviceSignals(
                availableProcessors = Runtime.getRuntime().availableProcessors(),
                isLowRamDevice = activity?.isLowRamDevice == true,
                powerSaveMode = power?.isPowerSaveMode == true,
                thermalStatus = thermal,
            )
        }
    }
}

enum class RuntimeCpuResolutionReason {
    AUTO_NORMAL,
    AUTO_LOW_CORE,
    AUTO_LOW_RAM,
    POWER_SAVER,
    THERMAL_CONSTRAINT,
    MANUAL,
    RETAINED_FOR_RUNTIME_GENERATION,
}

data class RuntimeCpuThreadResolution(
    val threadCount: Int,
    val deviceCeiling: Int,
    val reason: RuntimeCpuResolutionReason,
)

data class RuntimeCpuPolicyInput(
    val preference: RuntimePerformancePreference,
    val deviceSignals: RuntimeCpuDeviceSignals,
)

/** Pure, bounded CPU budget policy shared by LiteRT-LM and ONNX Runtime. */
object RuntimeCpuThreadPolicy {
    const val MAX_SUPPORTED_CPU_THREADS = 8

    fun deviceCeiling(signals: RuntimeCpuDeviceSignals): Int =
        signals.availableProcessors.coerceAtLeast(1).coerceAtMost(MAX_SUPPORTED_CPU_THREADS)

    fun resolve(
        preference: RuntimePerformancePreference,
        signals: RuntimeCpuDeviceSignals,
        previous: RuntimeCpuThreadResolution? = null,
        previousPreference: RuntimePerformancePreference? = null,
    ): RuntimeCpuThreadResolution {
        val normalized = preference.normalized()
        val ceiling = deviceCeiling(signals)
        if (normalized.mode == RuntimePerformanceMode.MANUAL) {
            return RuntimeCpuThreadResolution(
                threadCount = manualThreadCount(normalized.manualLevel, ceiling),
                deviceCeiling = ceiling,
                reason = RuntimeCpuResolutionReason.MANUAL,
            )
        }

        val constrainedReason = when {
            signals.powerSaveMode -> RuntimeCpuResolutionReason.POWER_SAVER
            signals.isLowRamDevice -> RuntimeCpuResolutionReason.AUTO_LOW_RAM
            signals.thermalStatus >= RuntimeCpuDeviceSignals.THERMAL_MODERATE ->
                RuntimeCpuResolutionReason.THERMAL_CONSTRAINT
            else -> null
        }
        val unconstrainedTarget = when {
            constrainedReason != null -> 1
            signals.availableProcessors.coerceAtLeast(1) < 6 -> 1
            signals.availableProcessors >= 16 -> 4
            signals.availableProcessors >= 12 -> 3
            else -> 2
        }.coerceAtMost(ceiling)

        val sameAutoPreference = previous != null && previousPreference?.normalized() == normalized
        if (sameAutoPreference) {
            val retained = previous!!.threadCount.coerceIn(1, ceiling)
            return if (constrainedReason != null) {
                RuntimeCpuThreadResolution(
                    threadCount = minOf(retained, unconstrainedTarget),
                    deviceCeiling = ceiling,
                    reason = constrainedReason,
                )
            } else {
                // Do not churn native runtimes as thermal status oscillates. A
                // lower budget remains in force until this runtime is released.
                RuntimeCpuThreadResolution(
                    threadCount = retained,
                    deviceCeiling = ceiling,
                    reason = RuntimeCpuResolutionReason.RETAINED_FOR_RUNTIME_GENERATION,
                )
            }
        }

        val reason = constrainedReason ?: if (signals.availableProcessors.coerceAtLeast(1) < 6) {
            RuntimeCpuResolutionReason.AUTO_LOW_CORE
        } else if (signals.isLowRamDevice) {
            RuntimeCpuResolutionReason.AUTO_LOW_RAM
        } else {
            RuntimeCpuResolutionReason.AUTO_NORMAL
        }
        return RuntimeCpuThreadResolution(unconstrainedTarget, ceiling, reason)
    }

    fun manualThreadCount(level: Int, deviceCeiling: Int): Int {
        val ceiling = deviceCeiling.coerceIn(1, MAX_SUPPORTED_CPU_THREADS)
        if (ceiling == 1) return 1
        val fraction = level.coerceIn(0, 100) / 100.0
        return (1 + (fraction * (ceiling - 1)).roundToInt()).coerceIn(1, ceiling)
    }

    /** Encodes the nearest discrete slider stop into the normalized persisted level. */
    fun snapManualLevel(level: Int, deviceCeiling: Int): Int {
        val ceiling = deviceCeiling.coerceIn(1, MAX_SUPPORTED_CPU_THREADS)
        if (ceiling == 1) return 0
        val selectedThread = manualThreadCount(level, ceiling)
        return (((selectedThread - 1).toDouble() / (ceiling - 1)) * 100).roundToInt().coerceIn(0, 100)
    }

    fun threadCountLabel(threadCount: Int): String {
        val count = threadCount.coerceAtLeast(1)
        return "$count ${if (count == 1) "thread" else "threads"}"
    }
}
