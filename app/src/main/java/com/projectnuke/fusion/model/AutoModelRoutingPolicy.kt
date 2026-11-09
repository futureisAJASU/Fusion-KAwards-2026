package com.projectnuke.fusion.model

/** All candidates are admitted local artifacts, never remote provider descriptors. */
internal data class AutoModelCandidate(
    val id: String, val path: String, val name: String, val bytes: Long,
    val runnable: Boolean, val memoryAllowed: Boolean, val vision: Boolean,
    val measuredRate: Double? = null, val measuredAt: Long = 0,
    val reasoning: Boolean = false,
)
internal data class AutoModelDecision(val candidate: AutoModelCandidate, val accelerator: AcceleratorMode, val reason: String)

internal object AutoModelRoutingPolicy {
    const val BENCHMARK_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    fun choose(candidates: List<AutoModelCandidate>, visionRequired: Boolean,
        lowMemory: Boolean, signals: RuntimeCpuDeviceSignals, preference: RuntimePerformancePreference,
        now: Long, reasoningRequired: Boolean = false): AutoModelDecision? {
        if (lowMemory) return null
        val eligible = candidates.take(128).filter {
            it.runnable && it.memoryAllowed && it.bytes > 0 && (!visionRequired || it.vision) &&
                (!reasoningRequired || it.reasoning)
        }
        val constrained = signals.powerSaveMode || signals.isLowRamDevice ||
            signals.thermalStatus >= RuntimeCpuDeviceSignals.THERMAL_MODERATE ||
            (preference.mode == RuntimePerformanceMode.MANUAL && preference.manualLevel < 34)
        fun rate(c: AutoModelCandidate): Double? = c.measuredRate?.takeIf {
            it.isFinite() && it > 0 && c.measuredAt > 0 && now - c.measuredAt in 0..BENCHMARK_MAX_AGE_MS
        }
        // Compare measured speeds only when every eligible candidate has a current measurement.
        val measured = !constrained && eligible.isNotEmpty() && eligible.all { rate(it) != null }
        val selected = eligible.sortedWith(compareBy<AutoModelCandidate> {
            if (measured) -rate(it)!! else it.bytes.toDouble()
        }.thenBy { it.id }.thenBy { it.path }).firstOrNull() ?: return null
        return AutoModelDecision(selected, if (constrained) AcceleratorMode.CPU else AcceleratorMode.AUTO,
            when { constrained -> "전력·메모리 상태 반영"; measured -> "최근 기기 측정 반영"; else -> "로컬 우선 · 메모리 여유 고려" })
    }
    fun maySuggestExternal(privateContent: Boolean, capableProviderAvailable: Boolean) =
        !privateContent && capableProviderAvailable
}
