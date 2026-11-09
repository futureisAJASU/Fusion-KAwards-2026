package com.projectnuke.fusion.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class DeveloperLogEvent(
    val timestamp: Long,
    val category: String,
    val message: String,
    val technicalSummary: String? = null
)

object DeveloperLogStore {
    private const val PrefsName = "fusion_developer_log"
    private const val KeyEvents = "events"
    private const val MaxEvents = 50
    private const val DurableLogCapBytes = 64L * 1024L
    private val lock = Any()

    /**
     * Pure, host-testable redaction used by the production logging path.
     * Secrets and registered private attachment/RAG text never reach the
     * durable developer log; hash/identity metadata keeps its shape.
     */
    fun sanitizeForRecord(
        message: String,
        technicalSummary: String?,
    ): Pair<String, String?> {
        val privateTexts = com.projectnuke.fusion.privacy.DataEgressPrivateTextLedger.currentTexts()
        return com.projectnuke.fusion.privacy.DataEgressPolicy.sanitizeDiagnostics(
            message,
            privateTexts = privateTexts,
        ) to (technicalSummary?.let {
            com.projectnuke.fusion.privacy.DataEgressPolicy.sanitizeDiagnostics(
                it,
                privateTexts = privateTexts,
            )
        })
    }

    fun record(
        context: Context,
        category: String,
        message: String,
        technicalSummary: String? = null
    ) {
        synchronized(lock) {
            val (redactedMessage, redactedTechnical) = sanitizeForRecord(message, technicalSummary)
            val cleanMessage = redactedMessage.take(180)
            val cleanTechnical = redactedTechnical?.take(240)
            val updated = listOf(
                DeveloperLogEvent(
                    timestamp = System.currentTimeMillis(),
                    category = category.take(40),
                    message = cleanMessage,
                    technicalSummary = cleanTechnical
                )
            ) + load(context)
            save(context, updated.take(MaxEvents))
        }
    }

    fun load(context: Context): List<DeveloperLogEvent> {
        val prefs = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        val raw = prefs.getString(KeyEvents, null)
        if (raw == null) return emptyList()
        // Durable load cap: oversized payload treated as corrupt diagnostics.
        val rawBytes = raw.toByteArray(Charsets.UTF_8)
        if (rawBytes.size.toLong() > DurableLogCapBytes) {
            prefs.edit().remove(KeyEvents).apply()
            return emptyList()
        }
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: run {
            prefs.edit().remove(KeyEvents).apply()
            return emptyList()
        }
        val survivors = mutableListOf<DeveloperLogEvent>()
        for (index in 0 until minOf(arr.length(), MaxEvents)) {
            val obj = arr.optJSONObject(index) ?: continue
            val timestampRaw = obj.optLong("timestamp", -1)
            val timestamp = if (timestampRaw < 0) 0L else timestampRaw
            val categoryRaw = obj.optString("category", "").take(40)
            val messageInput = obj.optString("message", "")
            val technicalInput = obj.optString("technicalSummary", "")
                .takeIf { it.isNotBlank() }
            val (sanitizedMessage, sanitizedTechnical) = sanitizeForRecord(
                message = messageInput,
                technicalSummary = technicalInput,
            )
            survivors.add(
                DeveloperLogEvent(
                    timestamp = timestamp,
                    category = categoryRaw,
                    message = sanitizedMessage.take(180),
                    technicalSummary = sanitizedTechnical?.take(240),
                ),
            )
        }
        return survivors
    }

    fun clear(context: Context) {
        synchronized(lock) {
            context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE).edit().remove(KeyEvents).apply()
        }
    }

    private fun save(context: Context, events: List<DeveloperLogEvent>) {
        val arr = JSONArray()
        events.forEach { event ->
            arr.put(
                JSONObject()
                    .put("timestamp", event.timestamp)
                    .put("category", event.category)
                    .put("message", event.message)
                    .put("technicalSummary", event.technicalSummary)
            )
        }
        context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(KeyEvents, arr.toString())
            .apply()
    }
}
