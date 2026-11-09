package com.projectnuke.fusion.ui

import android.content.Context
import android.util.Log
import com.projectnuke.fusion.chat.AutoCompactMemoryStore
import com.projectnuke.fusion.data.ChatDao
import com.projectnuke.fusion.data.ProjectDao
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.util.DebtQuarantine
import com.projectnuke.fusion.util.DebtQuarantineOutcome
import com.projectnuke.fusion.util.BoundedUtf8FileRead
import com.projectnuke.fusion.util.readUtf8FileBounded
import com.projectnuke.fusion.util.FusionResponseRatings
import com.projectnuke.fusion.util.isPhysicalCleanupSettled
import com.projectnuke.fusion.util.writeTextAtomically
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class ConversationCleanupDebt(
    val conversationId: Long,
    val messageIds: Set<Long>,
    val pendingPaths: Set<String>,
    val attempts: Int,
    val lastAttemptAt: Long,
)

internal sealed interface ConversationCleanupLoad {
    data object Missing : ConversationCleanupLoad
    data class Valid(val entries: List<ConversationCleanupDebt>) : ConversationCleanupLoad
    data object Corrupt : ConversationCleanupLoad
    data object Oversized : ConversationCleanupLoad
}

internal data class ConversationCleanupRetryResult(
    val attempted: Int,
    val persistenceOk: Boolean,
    val load: ConversationCleanupLoad,
    val pendingAfter: Int,
)

internal object ConversationCleanupDebtStore {
    internal const val MAX_ENTRIES = 64
    internal const val MAX_MESSAGE_IDS = 512
    internal const val MAX_PATHS = 64
    internal const val MAX_PATH_CHARS = 4_096
    internal const val MAX_ATTEMPTS = 100
    private const val RETRY_BASE_DELAY_MS = 5_000L
    internal const val MAX_FILE_BYTES = 512 * 1024
    private const val FILE_NAME = "conversation_cleanup_debt.json"
    internal const val QUARANTINE_SUFFIX = ".corrupt.bak"
    private val lock = Any()

    internal var quarantineHook: ((File) -> DebtQuarantineOutcome)? = null
    /** Test-only failure seam for the final retry persistence boundary. */
    internal var retryPersistenceHook: ((File, List<ConversationCleanupDebt>) -> Boolean)? = null

    fun record(context: Context, debt: ConversationCleanupDebt): Boolean =
        record(File(context.filesDir, FILE_NAME), debt)

    fun record(file: File, debt: ConversationCleanupDebt): Boolean = synchronized(lock) {
        if (!isValidDebt(debt)) return false
        when (val current = loadResultLocked(file)) {
            is ConversationCleanupLoad.Corrupt,
            is ConversationCleanupLoad.Oversized -> {
                val outcome = quarantineHook?.invoke(file) ?: quarantineLocked(
                    file,
                    allowCopyFallback = current !is ConversationCleanupLoad.Oversized,
                )
                if (outcome is DebtQuarantineOutcome.Failed) return false
                persistLocked(file, listOf(debt))
            }
            is ConversationCleanupLoad.Missing,
            is ConversationCleanupLoad.Valid -> {
                val current = loadLocked(file)
                val exists = current.any { it.conversationId == debt.conversationId }
                if (!exists && current.size >= MAX_ENTRIES) return false
                val combined = deduplicate(current.filterNot { it.conversationId == debt.conversationId } + debt)
                if (combined.size > MAX_ENTRIES) return false
                persistLocked(file, combined)
            }
        }
    }

    internal fun isValidDebt(debt: ConversationCleanupDebt): Boolean {
        if (debt.conversationId <= 0L) return false
        if (debt.messageIds.size > MAX_MESSAGE_IDS) return false
        if (debt.messageIds.any { it <= 0L }) return false
        if (debt.pendingPaths.size > MAX_PATHS) return false
        if (debt.pendingPaths.any { it.length !in 1..MAX_PATH_CHARS }) return false
        if (debt.attempts < 0 || debt.attempts > MAX_ATTEMPTS) return false
        return true
    }

    fun remove(context: Context, conversationId: Long): Boolean =
        remove(File(context.filesDir, FILE_NAME), conversationId)

    fun remove(file: File, conversationId: Long): Boolean = synchronized(lock) {
        when (loadResultLocked(file)) {
            is ConversationCleanupLoad.Corrupt,
            is ConversationCleanupLoad.Oversized -> false
            is ConversationCleanupLoad.Missing,
            is ConversationCleanupLoad.Valid ->
                persistLocked(file, loadLocked(file).filterNot { it.conversationId == conversationId })
        }
    }

    fun load(file: File): List<ConversationCleanupDebt> = synchronized(lock) {
        loadLocked(file)
    }

    fun loadResult(file: File): ConversationCleanupLoad = synchronized(lock) {
        loadResultLocked(file)
    }

    suspend fun retry(
        context: Context,
        dao: ChatDao,
        projectDao: ProjectDao? = null,
        limit: Int = MAX_ENTRIES,
    ): ConversationCleanupRetryResult =
        withContext(Dispatchers.IO) {
            val file = File(context.filesDir, FILE_NAME)
            val load = synchronized(lock) { loadResultLocked(file) }
            when (load) {
                is ConversationCleanupLoad.Missing ->
                    return@withContext ConversationCleanupRetryResult(0, true, load, 0)
                is ConversationCleanupLoad.Corrupt,
                is ConversationCleanupLoad.Oversized -> {
                    Log.w("FusionRecovery", "ConversationCleanup debt unreadable (${load::class.simpleName}); preserving file")
                    return@withContext ConversationCleanupRetryResult(0, false, load, -1)
                }
                is ConversationCleanupLoad.Valid -> Unit
            }
            val entries = (load as ConversationCleanupLoad.Valid).entries
            val now = System.currentTimeMillis()
            val boundedLimit = limit.coerceIn(0, MAX_ENTRIES)
            val pending = entries.filter {
                it.attempts < MAX_ATTEMPTS &&
                    (it.lastAttemptAt <= 0L || now - it.lastAttemptAt >= retryDelayMs(it.attempts))
            }.take(boundedLimit)
            val succeeded = mutableListOf<ConversationCleanupDebt>()
            val failed = mutableListOf<ConversationCleanupDebt>()
            pending.forEach { entry ->
                var remainingMessageIds = entry.messageIds
                var remainingPaths = entry.pendingPaths
                val componentsOk = try {
                    val stateOk = deleteResponseVersionState(context, entry.conversationId)
                    val summaryOk = deleteConversationSummary(context, entry.conversationId)
                    val memoryOk = deleteConversationOnlyMemoryCandidates(context, entry.conversationId)
                    val autoCompactOk = AutoCompactMemoryStore(context).deleteDurably(entry.conversationId)
                    val studyOk = com.projectnuke.fusion.chat.StudyProgressStore(java.io.File(context.filesDir, "study_progress")).delete(entry.conversationId)
                    val researchAuditsOk = ResearchInterventionAuditStore.from(context).removeConversation(entry.conversationId)
                    val ratingsOk = FusionResponseRatings.deleteForMessages(context, remainingMessageIds)
                    // Recorded draft paths must never be destructively
                    // retried: a path that now has a surviving durable
                    // authority (project row, another draft/message) is
                    // preserved by the authority-aware release. Debt stays
                    // truthful — only settled outcomes drop the path.
                    remainingPaths = AttachmentStorageManager
                        .releaseRemovedDraftAttachments(context, dao, projectDao, remainingPaths)
                        .filterValues { !it.isPhysicalCleanupSettled() }
                        .keys.toSet()
                    if (!ratingsOk) {
                        remainingMessageIds = entry.messageIds
                    } else {
                        remainingMessageIds = emptySet()
                    }
                    AttachmentStorageManager.cleanupUnreferencedAttachments(context, dao, projectDao)
                    stateOk && summaryOk && memoryOk && autoCompactOk && studyOk && researchAuditsOk && ratingsOk && remainingPaths.isEmpty()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Error) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (componentsOk) {
                    succeeded += entry
                } else {
                    failed += entry.copy(
                        messageIds = remainingMessageIds,
                        pendingPaths = remainingPaths,
                        attempts = entry.attempts + 1,
                        lastAttemptAt = System.currentTimeMillis(),
                    )
                }
            }
            val (persisted, finalPendingCount) = synchronized(lock) {
                mergeAndPersistRetryLocked(file, pending, succeeded, failed)
            }
            if (!persisted) {
                Log.w("FusionRecovery", "ConversationCleanup retry attempted=${pending.size} but persistence failed")
            }
            ConversationCleanupRetryResult(
                attempted = pending.size,
                persistenceOk = persisted,
                load = load,
                pendingAfter = if (persisted) finalPendingCount else pending.size,
            )
        }

    /**
     * Testable file-based retry without Android Context. [handler] returns true when the
     * debt's components were fully cleaned (debt may be dropped), false to re-queue with
     * incremented attempts. Cancellation is rethrown without consuming attempts.
     */
    internal suspend fun retryWithHandler(
        file: File,
        limit: Int = MAX_ENTRIES,
        handler: suspend (ConversationCleanupDebt) -> Boolean,
    ): ConversationCleanupRetryResult = withContext(Dispatchers.IO) {
        val load = synchronized(lock) { loadResultLocked(file) }
        when (load) {
            is ConversationCleanupLoad.Missing ->
                return@withContext ConversationCleanupRetryResult(0, true, load, 0)
            is ConversationCleanupLoad.Corrupt,
            is ConversationCleanupLoad.Oversized ->
                return@withContext ConversationCleanupRetryResult(0, false, load, -1)
            is ConversationCleanupLoad.Valid -> Unit
        }
        val entries = (load as ConversationCleanupLoad.Valid).entries
        val boundedLimit = limit.coerceIn(0, MAX_ENTRIES)
        val pending = entries.filter { it.attempts < MAX_ATTEMPTS }.take(boundedLimit)
        val succeeded = mutableListOf<ConversationCleanupDebt>()
        val failed = mutableListOf<ConversationCleanupDebt>()
        pending.forEach { entry ->
            val ok = try {
                handler(entry)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Error) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (ok) {
                succeeded += entry
            } else {
                failed += entry.copy(attempts = entry.attempts + 1, lastAttemptAt = System.currentTimeMillis())
            }
        }
        val (persisted, finalPendingCount) = synchronized(lock) {
            mergeAndPersistRetryLocked(file, pending, succeeded, failed)
        }
        ConversationCleanupRetryResult(
            attempted = pending.size,
            persistenceOk = persisted,
            load = load,
            pendingAfter = if (persisted) finalPendingCount else pending.size,
        )
    }

    private fun mergeAndPersistRetryLocked(
        file: File,
        pending: List<ConversationCleanupDebt>,
        succeeded: List<ConversationCleanupDebt>,
        failed: List<ConversationCleanupDebt>,
    ): Pair<Boolean, Int> {
        val currentDurableResult = loadResultLocked(file)
        when (currentDurableResult) {
            is ConversationCleanupLoad.Corrupt,
            is ConversationCleanupLoad.Oversized -> {
                Log.w("FusionRecovery", "ConversationCleanup current durable unreadable (${currentDurableResult::class.simpleName}); not overwriting with retry state")
                return false to pending.size
            }
            else -> Unit
        }
        val currentDurable = when (currentDurableResult) {
            is ConversationCleanupLoad.Valid -> currentDurableResult.entries
            else -> emptyList()
        }
        val currentByKey = currentDurable.associateBy { it.conversationId }.toMutableMap()

        // Apply success removals
        succeeded.forEach { entry ->
            val id = entry.conversationId
            val current = currentByKey[id]
            if (current == null) {
                // Concurrent removal preserved
            } else if (current == entry) {
                currentByKey.remove(id)
            } else {
                currentByKey[id] = current
            }
        }

        // Apply failure replacements
        failed.forEach { updated ->
            val id = updated.conversationId
            val baseline = pending.first { it.conversationId == id }
            val current = currentByKey[id]
            if (current == null) {
                // Concurrent removal preserved: do NOT resurrect
            } else if (current == baseline) {
                currentByKey[id] = updated
            } else {
                currentByKey[id] = current
            }
        }

        val finalList = currentByKey.values.toList()
        if (finalList.size > MAX_ENTRIES) {
            Log.w("FusionRecovery", "ConversationCleanup retry merged result above cap; failing closed without persistence")
            return false to pending.size
        }
        val finalPendingCount = finalList.count { it.attempts < MAX_ATTEMPTS }
        val persisted = retryPersistenceHook?.invoke(file, finalList) ?: persistLocked(file, finalList)
        return persisted to finalPendingCount
    }

    internal fun deduplicate(debts: List<ConversationCleanupDebt>): List<ConversationCleanupDebt> {
        val byKey = LinkedHashMap<Long, ConversationCleanupDebt>(debts.size)
        debts.forEach { debt -> byKey[debt.conversationId] = debt }
        return byKey.values.toList()
    }



    private fun retryDelayMs(attempts: Int): Long =
        RETRY_BASE_DELAY_MS * (1L shl attempts.coerceIn(0, 10))

    private fun loadResultLocked(file: File): ConversationCleanupLoad {
        if (!file.isFile) return ConversationCleanupLoad.Missing
        val length = runCatching { file.length() }.getOrNull() ?: return ConversationCleanupLoad.Corrupt
        if (length > MAX_FILE_BYTES) return ConversationCleanupLoad.Oversized
        return when (val read = readUtf8FileBounded(file, MAX_FILE_BYTES)) {
            is BoundedUtf8FileRead.Success -> parseStrict(read.text)
            is BoundedUtf8FileRead.Oversized -> ConversationCleanupLoad.Oversized
            is BoundedUtf8FileRead.Failed -> ConversationCleanupLoad.Corrupt
        }
    }

    internal fun parseStrict(raw: String): ConversationCleanupLoad {
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return ConversationCleanupLoad.Corrupt
        }
        if (array.length() > MAX_ENTRIES) return ConversationCleanupLoad.Corrupt
        // The writer replaces same-identity entries, so duplicate conversation
        // IDs prove an out-of-contract artifact rather than recoverable state.
        val seenIds = HashSet<Long>(array.length())
        val entries = ArrayList<ConversationCleanupDebt>(array.length())
        for (index in 0 until array.length()) {
            val item = try {
                array.optJSONObject(index) ?: return ConversationCleanupLoad.Corrupt
            } catch (_: Exception) {
                return ConversationCleanupLoad.Corrupt
            }
            if (!item.has("id")) return ConversationCleanupLoad.Corrupt
            val idRaw = try { item.get("id") } catch (_: Exception) { return ConversationCleanupLoad.Corrupt }
            val id = com.projectnuke.fusion.util.exactLongOrNull(idRaw) ?: return ConversationCleanupLoad.Corrupt
            if (id <= 0L) return ConversationCleanupLoad.Corrupt
            if (!item.has("messages")) return ConversationCleanupLoad.Corrupt
            val messagesRaw = try { item.get("messages") } catch (_: Exception) { return ConversationCleanupLoad.Corrupt }
            if (messagesRaw !is JSONArray) return ConversationCleanupLoad.Corrupt
            if (messagesRaw.length() > MAX_MESSAGE_IDS) return ConversationCleanupLoad.Corrupt
            val messages = HashSet<Long>(messagesRaw.length())
            for (m in 0 until messagesRaw.length()) {
                val v = try { messagesRaw.get(m) } catch (_: Exception) { return ConversationCleanupLoad.Corrupt }
                val lv = com.projectnuke.fusion.util.exactLongOrNull(v) ?: return ConversationCleanupLoad.Corrupt
                if (lv <= 0L) return ConversationCleanupLoad.Corrupt
                // Message IDs persist a Set; a duplicate member is out-of-contract.
                if (!messages.add(lv)) return ConversationCleanupLoad.Corrupt
            }
            if (!item.has("paths")) return ConversationCleanupLoad.Corrupt
            val pathsRaw = try { item.get("paths") } catch (_: Exception) { return ConversationCleanupLoad.Corrupt }
            if (pathsRaw !is JSONArray) return ConversationCleanupLoad.Corrupt
            if (pathsRaw.length() > MAX_PATHS) return ConversationCleanupLoad.Corrupt
            val paths = HashSet<String>(pathsRaw.length())
            for (p in 0 until pathsRaw.length()) {
                val v = try { pathsRaw.get(p) } catch (_: Exception) { return ConversationCleanupLoad.Corrupt }
                if (v !is String) return ConversationCleanupLoad.Corrupt
                if (v.length !in 1..MAX_PATH_CHARS) return ConversationCleanupLoad.Corrupt
                // Pending paths persist a Set; a duplicate member is out-of-contract.
                if (!paths.add(v)) return ConversationCleanupLoad.Corrupt
            }
            val attempts = if (item.has("attempts")) {
                val aRaw = try { item.get("attempts") } catch (_: Exception) { return ConversationCleanupLoad.Corrupt }
                val a = com.projectnuke.fusion.util.exactIntOrNull(aRaw) ?: return ConversationCleanupLoad.Corrupt
                if (a < 0 || a > MAX_ATTEMPTS) return ConversationCleanupLoad.Corrupt
                a
            } else 0
            val lastAttemptAt = if (item.has("lastAttemptAt")) {
                val lRaw = try { item.get("lastAttemptAt") } catch (_: Exception) { return ConversationCleanupLoad.Corrupt }
                val parsed = com.projectnuke.fusion.util.exactLongOrNull(lRaw) ?: return ConversationCleanupLoad.Corrupt
                if (parsed < 0L) return ConversationCleanupLoad.Corrupt
                parsed
            } else 0L
            if (!seenIds.add(id)) return ConversationCleanupLoad.Corrupt
            entries += ConversationCleanupDebt(id, messages, paths, attempts, lastAttemptAt)
        }
        return ConversationCleanupLoad.Valid(entries)
    }

    private fun loadLocked(file: File): List<ConversationCleanupDebt> =
        when (val result = loadResultLocked(file)) {
            is ConversationCleanupLoad.Valid -> result.entries
            is ConversationCleanupLoad.Missing,
            is ConversationCleanupLoad.Corrupt,
            is ConversationCleanupLoad.Oversized -> emptyList()
        }

    private fun loadLocked(context: Context): List<ConversationCleanupDebt> =
        loadLocked(File(context.filesDir, FILE_NAME))

    private fun quarantineLocked(file: File, allowCopyFallback: Boolean = true): DebtQuarantineOutcome {
        val outcome = DebtQuarantine.quarantine(file, QUARANTINE_SUFFIX, allowCopyFallback)
        when (outcome) {
            is DebtQuarantineOutcome.Secured ->
                Log.w("FusionRecovery", "Quarantined unreadable conversation-cleanup debt to ${outcome.quarantine.name}")
            is DebtQuarantineOutcome.SourceMissing -> Unit
            is DebtQuarantineOutcome.Failed ->
                Log.w("FusionRecovery", "Quarantine FAILED for conversation-cleanup debt; preserving original")
        }
        return outcome
    }

    private fun persistLocked(file: File, debts: List<ConversationCleanupDebt>): Boolean {
        if (debts.size > MAX_ENTRIES) return false
        debts.forEach { if (!isValidDebt(it)) return false }
        val array = JSONArray()
        debts.forEach { debt ->
            array.put(JSONObject().put("id", debt.conversationId).put("attempts", debt.attempts).put("lastAttemptAt", debt.lastAttemptAt)
                .put("messages", JSONArray(debt.messageIds.toList()))
                .put("paths", JSONArray(debt.pendingPaths.toList())))
        }
        val encoded = array.toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) return false
        return runCatching { writeTextAtomically(file, encoded) }.isSuccess
    }

    private fun persistLocked(context: Context, debts: List<ConversationCleanupDebt>): Boolean =
        persistLocked(File(context.filesDir, FILE_NAME), debts)
}
