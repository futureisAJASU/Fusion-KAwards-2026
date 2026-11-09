package com.projectnuke.fusion.ai.data

import android.content.Context
import android.util.Log
import com.projectnuke.fusion.util.DebtQuarantine
import com.projectnuke.fusion.util.DebtQuarantineOutcome
import com.projectnuke.fusion.util.BoundedUtf8FileRead
import com.projectnuke.fusion.util.readUtf8FileBounded
import com.projectnuke.fusion.util.writeTextAtomically
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable record of an obsolete secret that could not be deleted.
 * The retry re-attempts the exact deletion through the secret store.
 *
 * Strict contract: any malformed required entry makes the WHOLE file CORRUPT.
 */
internal data class ObsoleteSecretCleanupDebt(
    val secretId: String,
    val attempts: Int,
    val lastAttemptAt: Long,
)

internal data class ObsoleteSecretCleanupResult(
    val success: Boolean,
)

internal sealed interface ObsoleteSecretDebtLoad {
    data object Missing : ObsoleteSecretDebtLoad
    data class Valid(val entries: List<ObsoleteSecretCleanupDebt>) : ObsoleteSecretDebtLoad
    data object Corrupt : ObsoleteSecretDebtLoad
    data object Oversized : ObsoleteSecretDebtLoad
}

internal data class ObsoleteSecretRetryResult(
    val cleaned: Int,
    val persistenceOk: Boolean,
    val load: ObsoleteSecretDebtLoad,
    val pendingAfter: Int,
)

internal object ObsoleteSecretCleanupDebtStore {
    internal const val MAX_ENTRIES = 32
    internal const val MAX_SECRET_ID_CHARS = 128
    internal const val MAX_ATTEMPTS = 100
    internal const val MAX_FILE_BYTES = 64 * 1024
    private const val FILE_NAME = "obsolete_secret_cleanup_debt.json"
    internal const val QUARANTINE_SUFFIX = ".corrupt.bak"
    private val lock = Any()

    internal var quarantineHook: ((File) -> DebtQuarantineOutcome)? = null
    /** Test-only failure seam for callers that must prove lost debt durability. */
    internal var recordHook: ((File, String) -> Boolean)? = null
    /** Test-only failure seam for the final retry persistence boundary. */
    internal var retryPersistenceHook: ((File, List<ObsoleteSecretCleanupDebt>) -> Boolean)? = null

    fun record(context: Context, secretId: String): Boolean =
        record(File(context.filesDir, FILE_NAME), secretId)

    fun record(file: File, secretId: String): Boolean = synchronized(lock) {
        recordHook?.let { return@synchronized it(file, secretId) }
        if (secretId.length !in 1..MAX_SECRET_ID_CHARS) return false
        when (val current = loadResultLocked(file)) {
            is ObsoleteSecretDebtLoad.Corrupt,
            is ObsoleteSecretDebtLoad.Oversized -> {
                val outcome = quarantineHook?.invoke(file) ?: quarantineLocked(
                    file,
                    allowCopyFallback = current !is ObsoleteSecretDebtLoad.Oversized,
                )
                if (outcome is DebtQuarantineOutcome.Failed) return false
                persistLocked(
                    file,
                    listOf(
                        ObsoleteSecretCleanupDebt(
                            secretId = secretId,
                            attempts = 0,
                            lastAttemptAt = System.currentTimeMillis(),
                        ),
                    ),
                )
            }
            is ObsoleteSecretDebtLoad.Missing,
            is ObsoleteSecretDebtLoad.Valid -> {
                val current = loadLocked(file)
                val exists = current.any { it.secretId == secretId }
                if (!exists && current.size >= MAX_ENTRIES) return false
                val combined = deduplicate(current.filterNot { it.secretId == secretId } + ObsoleteSecretCleanupDebt(
                    secretId = secretId,
                    attempts = 0,
                    lastAttemptAt = System.currentTimeMillis(),
                ))
                if (combined.size > MAX_ENTRIES) return false
                persistLocked(file, combined)
            }
        }
    }

    internal fun isValidDebt(debt: ObsoleteSecretCleanupDebt): Boolean {
        if (debt.secretId.length !in 1..MAX_SECRET_ID_CHARS) return false
        if (debt.attempts < 0 || debt.attempts > MAX_ATTEMPTS) return false
        return true
    }

    fun remove(context: Context, secretId: String): Boolean =
        remove(File(context.filesDir, FILE_NAME), secretId)

    fun remove(file: File, secretId: String): Boolean = synchronized(lock) {
        when (loadResultLocked(file)) {
            is ObsoleteSecretDebtLoad.Corrupt,
            is ObsoleteSecretDebtLoad.Oversized -> false
            is ObsoleteSecretDebtLoad.Missing,
            is ObsoleteSecretDebtLoad.Valid ->
                persistLocked(file, loadLocked(file).filterNot { it.secretId == secretId })
        }
    }

    fun loadResult(file: File): ObsoleteSecretDebtLoad = synchronized(lock) {
        loadResultLocked(file)
    }

    suspend fun retry(
        secretStore: com.projectnuke.fusion.ai.secure.SecretStore,
        file: File,
        limit: Int = MAX_ENTRIES,
    ): ObsoleteSecretRetryResult = withContext(Dispatchers.IO) {
        val load = synchronized(lock) { loadResultLocked(file) }
        when (load) {
            is ObsoleteSecretDebtLoad.Missing ->
                return@withContext ObsoleteSecretRetryResult(0, true, load, 0)
            is ObsoleteSecretDebtLoad.Corrupt,
            is ObsoleteSecretDebtLoad.Oversized -> {
                Log.w("FusionRecovery", "ObsoleteSecret debt unreadable (${load::class.simpleName}); preserving file")
                return@withContext ObsoleteSecretRetryResult(0, false, load, -1)
            }
            is ObsoleteSecretDebtLoad.Valid -> Unit
        }
        val entries = (load as ObsoleteSecretDebtLoad.Valid).entries
        val boundedLimit = limit.coerceIn(0, MAX_ENTRIES)
        val pending = entries.filter { it.attempts < MAX_ATTEMPTS }.take(boundedLimit)
        val succeeded = mutableListOf<ObsoleteSecretCleanupDebt>()
        val failed = mutableListOf<ObsoleteSecretCleanupDebt>()
        var cleaned = 0
        pending.forEach { entry ->
            val result = try {
                val success = secretStore.deleteSecret(entry.secretId)
                ObsoleteSecretCleanupResult(success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Error) {
                throw e
            } catch (_: Exception) {
                ObsoleteSecretCleanupResult(success = false)
            }
            if (result.success) {
                cleaned++
                succeeded += entry
            } else {
                failed += entry.copy(attempts = entry.attempts + 1, lastAttemptAt = System.currentTimeMillis())
            }
        }
        var finalPendingCount = pending.size
        val persisted: Boolean = synchronized(lock) {
            val currentDurableResult = loadResultLocked(file)
            when (currentDurableResult) {
                is ObsoleteSecretDebtLoad.Corrupt,
                is ObsoleteSecretDebtLoad.Oversized -> {
                    Log.w("FusionRecovery", "ObsoleteSecret current durable unreadable (${currentDurableResult::class.simpleName}); not overwriting with retry state")
                    return@synchronized false
                }
                else -> Unit
            }
            val currentDurable = when (currentDurableResult) {
                is ObsoleteSecretDebtLoad.Valid -> currentDurableResult.entries
                else -> emptyList()
            }
            val currentByKey = currentDurable.associateBy { it.secretId }.toMutableMap()

            // Apply success removals
            succeeded.forEach { entry ->
                val id = entry.secretId
                val current = currentByKey[id]
                if (current == null) {
                    // Concurrent remove preserved (already absent)
                } else if (current == entry) {
                    currentByKey.remove(id)
                } else {
                    // Concurrent update/replacement preserved
                    currentByKey[id] = current
                }
            }

            // Apply failure replacements / additions
            failed.forEach { updated ->
                val id = updated.secretId
                val baseline = pending.first { it.secretId == id }
                val current = currentByKey[id]
                if (current == null) {
                    // Concurrent remove preserved: do NOT resurrect
                } else if (current == baseline) {
                    currentByKey[id] = updated
                } else {
                    // Concurrent different entry preserved
                    currentByKey[id] = current
                }
            }

            val finalList = currentByKey.values.toList()
            if (finalList.size > MAX_ENTRIES) {
                Log.w("FusionRecovery", "ObsoleteSecret retry merged result above cap; failing closed without persistence")
                return@synchronized false
            }
            finalPendingCount = finalList.count { it.attempts < MAX_ATTEMPTS }
            retryPersistenceHook?.invoke(file, finalList) ?: persistLocked(file, finalList)
        }
        if (!persisted) {
            Log.w("FusionRecovery", "ObsoleteSecret retry cleaned=$cleaned but persistence failed")
        }
        ObsoleteSecretRetryResult(cleaned, persisted, load, if (persisted) finalPendingCount else pending.size)
    }

    fun getPendingCount(file: File): Int = synchronized(lock) {
        loadLocked(file).count { it.attempts < MAX_ATTEMPTS }
    }

    internal fun deduplicate(debts: List<ObsoleteSecretCleanupDebt>): List<ObsoleteSecretCleanupDebt> {
        val byKey = LinkedHashMap<String, ObsoleteSecretCleanupDebt>(debts.size)
        debts.forEach { debt -> byKey[debt.secretId] = debt }
        return byKey.values.toList()
    }

    private fun loadResultLocked(file: File): ObsoleteSecretDebtLoad {
        if (!file.isFile) return ObsoleteSecretDebtLoad.Missing
        val length = runCatching { file.length() }.getOrNull() ?: return ObsoleteSecretDebtLoad.Corrupt
        if (length > MAX_FILE_BYTES) return ObsoleteSecretDebtLoad.Oversized
        return when (val read = readUtf8FileBounded(file, MAX_FILE_BYTES)) {
            is BoundedUtf8FileRead.Success -> parseStrict(read.text)
            is BoundedUtf8FileRead.Oversized -> ObsoleteSecretDebtLoad.Oversized
            is BoundedUtf8FileRead.Failed -> ObsoleteSecretDebtLoad.Corrupt
        }
    }

    internal fun parseStrict(raw: String): ObsoleteSecretDebtLoad {
        val array = try {
            JSONArray(raw)
        } catch (e: Error) {
            throw e
        } catch (_: Exception) {
            return ObsoleteSecretDebtLoad.Corrupt
        }
        if (array.length() > MAX_ENTRIES) return ObsoleteSecretDebtLoad.Corrupt
        // The writer replaces same-identity entries, so duplicate secret IDs
        // prove an out-of-contract artifact rather than recoverable state.
        val seenIds = HashSet<String>(array.length())
        val entries = ArrayList<ObsoleteSecretCleanupDebt>(array.length())
        for (index in 0 until array.length()) {
            val item = try {
                array.optJSONObject(index) ?: return ObsoleteSecretDebtLoad.Corrupt
            } catch (_: Exception) {
                return ObsoleteSecretDebtLoad.Corrupt
            }
            if (!item.has("secretId")) return ObsoleteSecretDebtLoad.Corrupt
            val idRaw = try { item.get("secretId") } catch (_: Exception) { return ObsoleteSecretDebtLoad.Corrupt }
            if (idRaw !is String) return ObsoleteSecretDebtLoad.Corrupt
            if (idRaw.length !in 1..MAX_SECRET_ID_CHARS) return ObsoleteSecretDebtLoad.Corrupt
            val attempts = if (item.has("attempts")) {
                val aRaw = try { item.get("attempts") } catch (_: Exception) { return ObsoleteSecretDebtLoad.Corrupt }
                val a = com.projectnuke.fusion.util.exactIntOrNull(aRaw) ?: return ObsoleteSecretDebtLoad.Corrupt
                if (a < 0 || a > MAX_ATTEMPTS) return ObsoleteSecretDebtLoad.Corrupt
                a
            } else 0
            val lastAttemptAt = if (item.has("lastAttemptAt")) {
                val lRaw = try { item.get("lastAttemptAt") } catch (_: Exception) { return ObsoleteSecretDebtLoad.Corrupt }
                val parsed = com.projectnuke.fusion.util.exactLongOrNull(lRaw) ?: return ObsoleteSecretDebtLoad.Corrupt
                if (parsed < 0L) return ObsoleteSecretDebtLoad.Corrupt
                parsed
            } else 0L
            if (!seenIds.add(idRaw)) return ObsoleteSecretDebtLoad.Corrupt
            entries += ObsoleteSecretCleanupDebt(idRaw, attempts, lastAttemptAt)
        }
        return ObsoleteSecretDebtLoad.Valid(entries)
    }

    private fun loadLocked(file: File): List<ObsoleteSecretCleanupDebt> =
        when (val result = loadResultLocked(file)) {
            is ObsoleteSecretDebtLoad.Valid -> result.entries
            is ObsoleteSecretDebtLoad.Missing,
            is ObsoleteSecretDebtLoad.Corrupt,
            is ObsoleteSecretDebtLoad.Oversized -> emptyList()
        }

    private fun quarantineLocked(file: File, allowCopyFallback: Boolean = true): DebtQuarantineOutcome {
        val outcome = DebtQuarantine.quarantine(file, QUARANTINE_SUFFIX, allowCopyFallback)
        when (outcome) {
            is DebtQuarantineOutcome.Secured ->
                Log.w("FusionRecovery", "Quarantined unreadable obsolete-secret debt to ${outcome.quarantine.name}")
            is DebtQuarantineOutcome.SourceMissing -> Unit
            is DebtQuarantineOutcome.Failed ->
                Log.w("FusionRecovery", "Quarantine FAILED for obsolete-secret debt; preserving original")
        }
        return outcome
    }

    private fun persistLocked(file: File, debts: List<ObsoleteSecretCleanupDebt>): Boolean {
        if (debts.size > MAX_ENTRIES) return false
        debts.forEach { if (!isValidDebt(it)) return false }
        val array = JSONArray()
        debts.forEach { debt ->
            array.put(
                JSONObject()
                    .put("secretId", debt.secretId)
                    .put("attempts", debt.attempts)
                    .put("lastAttemptAt", debt.lastAttemptAt),
            )
        }
        val encoded = array.toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) return false
        return runCatching { writeTextAtomically(file, encoded) }.isSuccess
    }
}
