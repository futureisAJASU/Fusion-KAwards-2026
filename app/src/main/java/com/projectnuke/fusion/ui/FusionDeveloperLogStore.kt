package com.projectnuke.fusion.ui

import android.content.Context
import com.projectnuke.fusion.llm.FailureMemoryDurability

data class FusionDeveloperLogEvent(
    val timestamp: Long,
    val category: String,
    val message: String,
    val technicalSummary: String? = null
)

object FusionDeveloperLogStore {
    fun load(context: Context): List<FusionDeveloperLogEvent> {
        return DeveloperLogStore.load(context).map { event ->
            FusionDeveloperLogEvent(
                timestamp = event.timestamp,
                category = event.category,
                message = event.message,
                technicalSummary = event.technicalSummary
            )
        }
    }

    fun record(context: Context, category: String, message: String, technicalSummary: String? = null) {
        DeveloperLogStore.record(context, category, message, technicalSummary)
    }

    fun clear(context: Context) {
        DeveloperLogStore.clear(context)
    }

    /**
     * Records the current failure memory durability state to the developer log.
     * Called when durability transitions to a degraded state so the app can
     * surface this in diagnostics without claiming restart durability.
     */
    fun recordDurabilityState(context: Context, durability: FailureMemoryDurability) {
        val message = when (durability) {
            is FailureMemoryDurability.NotAttempted -> "Failure memory: no durability operations attempted"
            is FailureMemoryDurability.Durable -> "Failure memory: durable (persists across restarts)"
            is FailureMemoryDurability.InMemoryOnly -> "Failure memory: in-memory only (cause: ${durability.cause.message})"
        }
        val technicalSummary = when (durability) {
            is FailureMemoryDurability.InMemoryOnly -> durability.cause.toString()
            else -> null
        }
        record(context, "memory", message, technicalSummary)
    }
}
