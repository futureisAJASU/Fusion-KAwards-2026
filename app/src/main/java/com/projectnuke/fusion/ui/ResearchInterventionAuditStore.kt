package com.projectnuke.fusion.ui

import android.content.Context
import android.content.SharedPreferences
import com.projectnuke.fusion.audit.ResearchInterventionAuditCodec
import com.projectnuke.fusion.audit.ResearchInterventionAuditRecord
import com.projectnuke.fusion.util.SharedPreferencesDurability
import com.projectnuke.fusion.util.SharedPreferencesScopedLock
import com.projectnuke.fusion.util.restoreKeys
import com.projectnuke.fusion.util.snapshotKeys

/**
 * Durable store for Research intervention audit summaries.
 *
 * The store is intentionally separate from the Room message row so enabling
 * the intervention never requires a schema migration. Entries are bound to the
 * exact persisted message id and answer hash, so a response-version switch or
 * a rewritten answer can never display a stale verdict.
 */
internal class ResearchInterventionAuditStore(
    private val prefs: SharedPreferences,
) {
    fun save(record: ResearchInterventionAuditRecord): Boolean {
        if (record.messageId <= 0L || record.conversationId <= 0L) return false
        val key = keyFor(record.messageId)
        return SharedPreferencesScopedLock.withKeyLock(prefs, LockKey) {
            val encoded = ResearchInterventionAuditCodec.encode(record)
            val pruned = pruneKeys(key)
            val snapshot = prefs.snapshotKeys(listOf(key) + pruned)
            val editor = prefs.edit().putString(key, encoded)
            pruned.forEach { editor.remove(it) }
            if (editor.commit()) {
                SharedPreferencesDurability.clearUnknown(prefs, key)
                true
            } else {
                prefs.restoreKeys(snapshot)
                SharedPreferencesDurability.markUnknown(prefs, key)
                false
            }
        }
    }

    fun load(messageId: Long, answerSha256: String): ResearchInterventionAuditRecord? {
        if (messageId <= 0L || answerSha256.isBlank()) return null
        val key = keyFor(messageId)
        if (SharedPreferencesDurability.isUnknown(prefs, key)) return null
        val encoded = prefs.getString(key, null) ?: return null
        val record = ResearchInterventionAuditCodec.decode(encoded) ?: return null
        if (record.messageId != messageId) return null
        if (record.answerSha256 != answerSha256) return null
        return record
    }

    fun loadForConversation(conversationId: Long): List<ResearchInterventionAuditRecord> {
        if (conversationId <= 0L) return emptyList()
        return SharedPreferencesScopedLock.withKeyLock(prefs, LockKey) {
            prefs.all.keys
                .filter { it.startsWith(KeyPrefix) }
                .mapNotNull { key ->
                    if (SharedPreferencesDurability.isUnknown(prefs, key)) null
                    else prefs.getString(key, null)
                }
                .mapNotNull(ResearchInterventionAuditCodec::decode)
                .filter { it.conversationId == conversationId }
                .sortedByDescending { it.savedAt }
        }
    }

    /** Remove bounded SharedPreferences entries whose assistant row no longer exists. */
    fun removeOrphansForConversation(conversationId: Long, liveMessageIds: Set<Long>): Boolean {
        if (conversationId <= 0L) return false
        return SharedPreferencesScopedLock.withKeyLock(prefs, LockKey) {
            val orphanKeys = prefs.all.keys
                .filter { it.startsWith(KeyPrefix) }
                .filter { key ->
                    val record = prefs.getString(key, null)?.let(ResearchInterventionAuditCodec::decode)
                    record?.conversationId == conversationId && record.messageId !in liveMessageIds
                }
            if (orphanKeys.isEmpty()) return@withKeyLock true
            val snapshot = prefs.snapshotKeys(orphanKeys)
            val editor = prefs.edit()
            orphanKeys.forEach { editor.remove(it) }
            if (editor.commit()) {
                true
            } else {
                prefs.restoreKeys(snapshot)
                orphanKeys.forEach { SharedPreferencesDurability.markUnknown(prefs, it) }
                false
            }
        }
    }

    fun remove(messageId: Long): Boolean {
        if (messageId <= 0L) return false
        val key = keyFor(messageId)
        return SharedPreferencesScopedLock.withKeyLock(prefs, LockKey) {
            val snapshot = prefs.snapshotKeys(listOf(key))
            if (prefs.edit().remove(key).commit()) {
                SharedPreferencesDurability.clearUnknown(prefs, key)
                true
            } else {
                prefs.restoreKeys(snapshot)
                SharedPreferencesDurability.markUnknown(prefs, key)
                false
            }
        }
    }

    /** Remove every audit for a conversation after its durable message rows are deleted. */
    fun removeConversation(conversationId: Long): Boolean =
        removeOrphansForConversation(conversationId, emptySet())

    private fun pruneKeys(updatingKey: String): List<String> {
        val entries = prefs.all.keys
            .filter { it.startsWith(KeyPrefix) }
            .mapNotNull { key ->
                val record = prefs.getString(key, null)?.let(ResearchInterventionAuditCodec::decode)
                if (record == null) key to Long.MAX_VALUE else key to record.savedAt
            }
            .sortedBy { it.second }
        val projectedSize = entries.size + if (entries.any { it.first == updatingKey }) 0 else 1
        val excess = maxOf(0, projectedSize - MAX_RECORDS)
        return entries.asSequence()
            .filterNot { it.first == updatingKey }
            .take(excess)
            .map { it.first }
            .toList()
    }

    companion object {
        private const val PreferencesName = "fusion_research_intervention_audit"
        private const val KeyPrefix = "audit:"
        private const val LockKey = "research_intervention_audit"
        private const val MAX_RECORDS = 200

        fun from(context: Context): ResearchInterventionAuditStore =
            ResearchInterventionAuditStore(
                context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE),
            )

        internal fun keyFor(messageId: Long): String = KeyPrefix + messageId
    }
}
