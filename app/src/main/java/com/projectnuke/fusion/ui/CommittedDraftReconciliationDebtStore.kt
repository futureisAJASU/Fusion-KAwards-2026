package com.projectnuke.fusion.ui

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

internal data class CommittedDraftReconciliationDebt(
    val draftKey: Long,
    val token: String,
    val capturedRawInput: String,
    val committedPaths: Set<String>,
    val attempts: Int,
    val lastAttemptAt: Long,
)

internal data class DraftReconciliationResult(
    val success: Boolean,
    val releasePaths: Boolean,
)

internal fun interface DraftReconciliationOwner {
    suspend fun reconcile(debt: CommittedDraftReconciliationDebt): DraftReconciliationResult
}

/**
 * Typed bounded load result. A corrupt/oversized file must never be read as
 * "zero debts": ambiguous no-debt ownership decisions are fail-safe and the
 * bad evidence is preserved/quarantined instead of being silently replaced.
 *
 * Strict contract: any malformed required entry makes the WHOLE file CORRUPT.
 * No best-effort dropping of bad entries; no silent truncation of arrays.
 */
internal sealed interface CommittedDraftDebtLoad {
    data object Missing : CommittedDraftDebtLoad
    data class Valid(val entries: List<CommittedDraftReconciliationDebt>) : CommittedDraftDebtLoad
    data object Corrupt : CommittedDraftDebtLoad
    data object Oversized : CommittedDraftDebtLoad
}

/**
 * Truthful durable-retry outcome. [reconciled] counts in-memory successful
 * reconciliations; [persistenceOk] reports whether the resulting debt file was
 * durably persisted. Success with persistenceOk=false must not be reported as
 * durable recovery success.
 */
internal data class CommittedDraftRetryResult(
    val reconciled: Int,
    val persistenceOk: Boolean,
    val load: CommittedDraftDebtLoad,
    val pendingAfter: Int,
)

/**
 * Durable record of a composer reconciliation that could not settle when the message was
 * committed. The retry re-attempts the exact reconciliation through the draft store: it only
 * clears the committed draft when the captured submission token still owns the draft, and only
 * clears the captured input when the draft still matches the committed text, so a newer
 * submission or a user edit after commit is never wiped.
 *
 * Crash-consistency note (two tiers, fail-safe):
 * ComposerDraftState.activeSubmissionToken is persisted BEFORE the Room user-message
 * transaction commits. For schema-8 submissions the ambiguity is resolved by
 * an atomic commit witness: [com.projectnuke.fusion.data.SubmissionCommitWitnessEntity]
 * is written in the SAME Room transaction as the user message (see
 * commitAndSettleUserSubmission), so startup recovery can distinguish
 * "died before commit" (no witness: the restored token is safe to settle)
 * from "died after commit" (witness present: reconcile idempotently from the
 * witness, then delete it). A restored schema-8 token with no matching debt
 * and no witness MUST still be settled only through that witness check, never
 * by assuming the message is absent.
 * Legacy (non-schema-8) tokens have no witness: a restored legacy token with
 * no matching debt stays ambiguous and MUST NOT be automatically reclaimed.
 * Availability loss is preferable to duplicating an already-committed user
 * message. This file-debt tier persists only legacy/non-witness reconciliation
 * retries; schema-8 retries are owned by the Room witness.
 */
internal object CommittedDraftReconciliationDebtStore {
    internal const val MAX_ENTRIES = 64
    internal const val MAX_PATHS = 64
    internal const val MAX_PATH_CHARS = 4_096
    internal const val MAX_TOKEN_CHARS = 128
    internal const val MAX_RAW_INPUT_CHARS = 32_768
    internal const val MAX_ATTEMPTS = 100
    internal const val MAX_FILE_BYTES = 512 * 1024
    private const val FILE_NAME = "committed_draft_reconciliation_debt.json"
    internal const val QUARANTINE_SUFFIX = ".corrupt.bak"
    private val lock = Any()

    /** Test seam: when non-null, record() uses this quarantine outcome instead of real I/O. */
    internal var quarantineHook: ((File) -> DebtQuarantineOutcome)? = null
    /** Test-only failure seam for the final retry persistence boundary. */
    internal var retryPersistenceHook: ((File, List<CommittedDraftReconciliationDebt>) -> Boolean)? = null

    fun record(context: Context, debt: CommittedDraftReconciliationDebt): Boolean =
        record(File(context.filesDir, FILE_NAME), debt)

    fun record(file: File, debt: CommittedDraftReconciliationDebt): Boolean = synchronized(lock) {
        // Exact writer contract: out-of-contract objects are rejected BEFORE
        // replacing known-good state. Never truncate identity or user content.
        if (!isValidDebt(debt)) return false
        when (val current = loadResultLocked(file)) {
            is CommittedDraftDebtLoad.Corrupt,
            is CommittedDraftDebtLoad.Oversized -> {
                val outcome = quarantineHook?.invoke(file) ?: quarantineLocked(
                    file,
                    allowCopyFallback = current !is CommittedDraftDebtLoad.Oversized,
                )
                if (outcome is DebtQuarantineOutcome.Failed) return false
                persistLocked(file, listOf(debt))
            }
            is CommittedDraftDebtLoad.Missing,
            is CommittedDraftDebtLoad.Valid -> {
                val current = (loadResultLocked(file) as? CommittedDraftDebtLoad.Valid)?.entries
                    ?: (loadLocked(file))
                val exists = current.any { it.draftKey == debt.draftKey && it.token == debt.token }
                if (!exists && current.size >= MAX_ENTRIES) {
                    // Full store: a NEW identity must fail, never evict pending evidence.
                    return false
                }
                val combined = deduplicate(current.filterNot {
                    it.draftKey == debt.draftKey && it.token == debt.token
                } + debt)
                if (combined.size > MAX_ENTRIES) return false
                persistLocked(file, combined)
            }
        }
    }

    internal fun isValidDebt(debt: CommittedDraftReconciliationDebt): Boolean {
        if (debt.draftKey < 0L) return false
        if (debt.token.length !in 1..MAX_TOKEN_CHARS) return false
        if (debt.capturedRawInput.length > MAX_RAW_INPUT_CHARS) return false
        if (debt.committedPaths.size > MAX_PATHS) return false
        if (debt.committedPaths.any { it.length !in 1..MAX_PATH_CHARS }) return false
        if (debt.attempts < 0 || debt.attempts > MAX_ATTEMPTS) return false
        return true
    }

    fun remove(context: Context, draftKey: Long, token: String): Boolean =
        remove(File(context.filesDir, FILE_NAME), draftKey, token)

    fun remove(file: File, draftKey: Long, token: String): Boolean = synchronized(lock) {
        when (loadResultLocked(file)) {
            // Fail closed: do not claim removal and do not destroy unreadable evidence.
            is CommittedDraftDebtLoad.Corrupt,
            is CommittedDraftDebtLoad.Oversized -> false
            is CommittedDraftDebtLoad.Missing,
            is CommittedDraftDebtLoad.Valid -> {
                persistLocked(file, loadLocked(file).filterNot {
                    it.draftKey == draftKey && it.token == token
                })
            }
        }
    }

    /** Snapshot used by the single startup draft-recovery owner after hydration. */
    fun load(file: File): List<CommittedDraftReconciliationDebt> = synchronized(lock) {
        loadLocked(file)
    }

    fun loadResult(file: File): CommittedDraftDebtLoad = synchronized(lock) {
        loadResultLocked(file)
    }

    /**
     * Processes every currently actionable bounded entry in one startup pass.
     * Terminal (MAX_ATTEMPTS) entries never consume active retry work and are
     * preserved once (deduplicated) without displacing pending entries.
     * Cancellation is rethrown without consuming attempts or persisting failure.
     */
    suspend fun retry(
        owner: DraftReconciliationOwner,
        unregisterPath: (String) -> Unit,
        file: File,
        limit: Int = MAX_ENTRIES,
    ): CommittedDraftRetryResult = withContext(Dispatchers.IO) {
        val load = synchronized(lock) { loadResultLocked(file) }
        when (load) {
            is CommittedDraftDebtLoad.Missing ->
                return@withContext CommittedDraftRetryResult(0, true, load, 0)
            is CommittedDraftDebtLoad.Corrupt,
            is CommittedDraftDebtLoad.Oversized -> {
                Log.w("FusionRecovery", "CommittedDraft debt unreadable (${load::class.simpleName}); preserving file, no retry")
                return@withContext CommittedDraftRetryResult(0, false, load, -1)
            }
            is CommittedDraftDebtLoad.Valid -> Unit
        }
        val entries = (load as CommittedDraftDebtLoad.Valid).entries
        val boundedLimit = limit.coerceIn(0, MAX_ENTRIES)
        val pending = entries.filter { it.attempts < MAX_ATTEMPTS }.take(boundedLimit)
        val succeeded = mutableListOf<CommittedDraftReconciliationDebt>()
        val failed = mutableListOf<CommittedDraftReconciliationDebt>()
        var reconciled = 0
        pending.forEach { entry ->
            val result = try {
                owner.reconcile(entry)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Error) {
                throw e
            } catch (_: Exception) {
                DraftReconciliationResult(success = false, releasePaths = false)
            }
            if (result.success) {
                if (result.releasePaths) entry.committedPaths.forEach(unregisterPath)
                reconciled++
                succeeded += entry
            } else {
                failed += entry.copy(attempts = entry.attempts + 1, lastAttemptAt = System.currentTimeMillis())
            }
        }
        var finalPendingCount = pending.size
        val persisted: Boolean = synchronized(lock) {
            val currentDurableResult = loadResultLocked(file)
            when (currentDurableResult) {
                is CommittedDraftDebtLoad.Corrupt,
                is CommittedDraftDebtLoad.Oversized -> {
                    Log.w("FusionRecovery", "CommittedDraft current durable unreadable (${currentDurableResult::class.simpleName}); not overwriting with retry state")
                    return@synchronized false
                }
                else -> Unit
            }
            val currentDurable = when (currentDurableResult) {
                is CommittedDraftDebtLoad.Valid -> currentDurableResult.entries
                else -> emptyList()
            }
            val currentByKey = currentDurable.associateBy { it.draftKey to it.token }.toMutableMap()

            // Apply success removals
            succeeded.forEach { entry ->
                val identity = entry.draftKey to entry.token
                val current = currentByKey[identity]
                if (current == null) {
                    // Concurrent removal preserved: already absent
                } else if (current == entry) {
                    currentByKey.remove(identity)
                } else {
                    // Concurrent different entry preserved
                    currentByKey[identity] = current
                }
            }

            // Apply failure replacements (only when current equals baseline)
            failed.forEach { updated ->
                val identity = updated.draftKey to updated.token
                val baseline = pending.first { it.draftKey == updated.draftKey && it.token == updated.token }
                val current = currentByKey[identity]
                if (current == null) {
                    // Concurrent removal preserved: do NOT resurrect
                } else if (current == baseline) {
                    currentByKey[identity] = updated
                } else {
                    // Concurrent different entry preserved
                    currentByKey[identity] = current
                }
            }

            val finalList = currentByKey.values.toList()
            if (finalList.size > MAX_ENTRIES) {
                Log.w("FusionRecovery", "CommittedDraft retry merged result above cap; failing closed without persistence")
                return@synchronized false
            }
            finalPendingCount = finalList.count { it.attempts < MAX_ATTEMPTS }
            retryPersistenceHook?.invoke(file, finalList) ?: persistLocked(file, finalList)
        }
        if (!persisted) {
            Log.w("FusionRecovery", "CommittedDraft retry reconciled=$reconciled but debt persistence failed; reporting durability failure")
        }
        CommittedDraftRetryResult(
            reconciled = reconciled,
            persistenceOk = persisted,
            load = load,
            pendingAfter = if (persisted) finalPendingCount else pending.size,
        )
    }

    internal fun deduplicate(debts: List<CommittedDraftReconciliationDebt>): List<CommittedDraftReconciliationDebt> {
        val byKey = LinkedHashMap<Pair<Long, String>, CommittedDraftReconciliationDebt>(debts.size)
        debts.forEach { debt -> byKey[debt.draftKey to debt.token] = debt }
        return byKey.values.toList()
    }

    private fun loadResultLocked(file: File): CommittedDraftDebtLoad {
        if (!file.isFile) return CommittedDraftDebtLoad.Missing
        val length = runCatching { file.length() }.getOrNull() ?: return CommittedDraftDebtLoad.Corrupt
        if (length > MAX_FILE_BYTES) return CommittedDraftDebtLoad.Oversized
        return when (val read = readUtf8FileBounded(file, MAX_FILE_BYTES)) {
            is BoundedUtf8FileRead.Success -> parseStrict(read.text)
            is BoundedUtf8FileRead.Oversized -> CommittedDraftDebtLoad.Oversized
            is BoundedUtf8FileRead.Failed -> CommittedDraftDebtLoad.Corrupt
        }
    }

    internal fun parseStrict(raw: String): CommittedDraftDebtLoad {
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return CommittedDraftDebtLoad.Corrupt
        }
        // Do not silently truncate: out-of-contract counts are CORRUPT.
        if (array.length() > MAX_ENTRIES) return CommittedDraftDebtLoad.Corrupt
        // The writer never emits duplicate identities, so duplicates prove
        // an out-of-contract artifact rather than recoverable state.
        val seenIdentities = HashSet<Pair<Long, String>>(array.length())
        val entries = ArrayList<CommittedDraftReconciliationDebt>(array.length())
        for (index in 0 until array.length()) {
            val item = try {
                array.optJSONObject(index) ?: return CommittedDraftDebtLoad.Corrupt
            } catch (_: Exception) {
                return CommittedDraftDebtLoad.Corrupt
            }
            // Required: id (exact integral Number >= 0).
            if (!item.has("id")) return CommittedDraftDebtLoad.Corrupt
            val idRaw = try { item.get("id") } catch (_: Exception) { return CommittedDraftDebtLoad.Corrupt }
            val id = com.projectnuke.fusion.util.exactLongOrNull(idRaw) ?: return CommittedDraftDebtLoad.Corrupt
            if (id < 0L) return CommittedDraftDebtLoad.Corrupt
            // Required: token (String 1..MAX).
            if (!item.has("token")) return CommittedDraftDebtLoad.Corrupt
            val tokenRaw = try { item.get("token") } catch (_: Exception) { return CommittedDraftDebtLoad.Corrupt }
            if (tokenRaw !is String) return CommittedDraftDebtLoad.Corrupt
            if (tokenRaw.length !in 1..MAX_TOKEN_CHARS) return CommittedDraftDebtLoad.Corrupt
            // Required: rawInput (String <= MAX). Missing or wrong type is CORRUPT.
            if (!item.has("rawInput")) return CommittedDraftDebtLoad.Corrupt
            val rawInputRaw = try { item.get("rawInput") } catch (_: Exception) { return CommittedDraftDebtLoad.Corrupt }
            if (rawInputRaw !is String) return CommittedDraftDebtLoad.Corrupt
            if (rawInputRaw.length > MAX_RAW_INPUT_CHARS) return CommittedDraftDebtLoad.Corrupt
            // Required: paths (JSONArray <= MAX). Missing or wrong type is CORRUPT.
            if (!item.has("paths")) return CommittedDraftDebtLoad.Corrupt
            val pathsRaw = try { item.get("paths") } catch (_: Exception) { return CommittedDraftDebtLoad.Corrupt }
            if (pathsRaw !is JSONArray) return CommittedDraftDebtLoad.Corrupt
            if (pathsRaw.length() > MAX_PATHS) return CommittedDraftDebtLoad.Corrupt
            val paths = HashSet<String>(pathsRaw.length())
            for (p in 0 until pathsRaw.length()) {
                val v = try { pathsRaw.get(p) } catch (_: Exception) { return CommittedDraftDebtLoad.Corrupt }
                if (v !is String) return CommittedDraftDebtLoad.Corrupt
                if (v.length !in 1..MAX_PATH_CHARS) return CommittedDraftDebtLoad.Corrupt
                // Committed paths persist a Set; a duplicate member is out-of-contract.
                if (!paths.add(v)) return CommittedDraftDebtLoad.Corrupt
            }
            // Optional-with-default: attempts (exact integral 0..MAX if present).
            val attempts = if (item.has("attempts")) {
                val aRaw = try { item.get("attempts") } catch (_: Exception) { return CommittedDraftDebtLoad.Corrupt }
                val a = com.projectnuke.fusion.util.exactIntOrNull(aRaw) ?: return CommittedDraftDebtLoad.Corrupt
                if (a < 0 || a > MAX_ATTEMPTS) return CommittedDraftDebtLoad.Corrupt
                a
            } else 0
            val lastAttemptAt = if (item.has("lastAttemptAt")) {
                val lRaw = try { item.get("lastAttemptAt") } catch (_: Exception) { return CommittedDraftDebtLoad.Corrupt }
                val parsed = com.projectnuke.fusion.util.exactLongOrNull(lRaw) ?: return CommittedDraftDebtLoad.Corrupt
                // Fusion-written timestamps are never negative; future values are
                // allowed because the wall clock may have moved.
                if (parsed < 0L) return CommittedDraftDebtLoad.Corrupt
                parsed
            } else 0L
            if (!seenIdentities.add(id to tokenRaw)) return CommittedDraftDebtLoad.Corrupt
            entries += CommittedDraftReconciliationDebt(id, tokenRaw, rawInputRaw, paths, attempts, lastAttemptAt)
        }
        return CommittedDraftDebtLoad.Valid(entries)
    }

    private fun loadLocked(file: File): List<CommittedDraftReconciliationDebt> =
        when (val result = loadResultLocked(file)) {
            is CommittedDraftDebtLoad.Valid -> result.entries
            is CommittedDraftDebtLoad.Missing,
            is CommittedDraftDebtLoad.Corrupt,
            is CommittedDraftDebtLoad.Oversized -> emptyList()
        }

    private fun quarantineLocked(file: File, allowCopyFallback: Boolean = true): DebtQuarantineOutcome {
        val outcome = DebtQuarantine.quarantine(file, QUARANTINE_SUFFIX, allowCopyFallback)
        when (outcome) {
            is DebtQuarantineOutcome.Secured ->
                Log.w("FusionRecovery", "Quarantined unreadable committed-draft debt to ${outcome.quarantine.name}")
            is DebtQuarantineOutcome.SourceMissing -> Unit
            is DebtQuarantineOutcome.Failed ->
                Log.w("FusionRecovery", "Quarantine FAILED for committed-draft debt; preserving original")
        }
        return outcome
    }

    private fun persistLocked(file: File, debts: List<CommittedDraftReconciliationDebt>): Boolean {
        // Exact persist: validate every field, never truncate. Above-cap lists fail closed.
        if (debts.size > MAX_ENTRIES) return false
        debts.forEach { if (!isValidDebt(it)) return false }
        val array = JSONArray()
        debts.forEach { debt ->
            array.put(
                JSONObject()
                    .put("id", debt.draftKey)
                    .put("token", debt.token)
                    .put("rawInput", debt.capturedRawInput)
                    .put("attempts", debt.attempts)
                    .put("lastAttemptAt", debt.lastAttemptAt)
                    .put("paths", JSONArray(debt.committedPaths.toList())),
            )
        }
        val encoded = array.toString()
        // Never persist a representation the loader would reject as too large.
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) return false
        return runCatching { writeTextAtomically(file, encoded) }.isSuccess
    }
}
