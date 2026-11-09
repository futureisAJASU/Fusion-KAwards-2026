package com.projectnuke.fusion.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Single serialized owner of every draft mutation and of hydration.
 *
 * Hydration is a command in the same channel as every mutation, so hydration can never
 * deadlock against a mutation and no lock is ever held while awaiting hydration. Every
 * mutation is applied by the single consumer coroutine in queue order, which makes all
 * hydration, text updates, attachment import transitions, submission ownership
 * transitions, committed reconciliation, draft clears and persistence totally ordered.
 *
 * Durable mutations that arrive before hydration are applied in memory, deferred and
 * written once after hydration merges the restored state. A failed write completes every
 * deferred reply with `false` and rolls the in-memory map back to the exact state that
 * existed before the first deferred mutation.
 */
internal enum class DraftHydrationHealth {
    MISSING,
    VALID,
    UNREADABLE_CORRUPT,
    UNREADABLE_OVERSIZED,
    UNREADABLE_IO,
}

/**
 * Startup gate: reconciliation debts may only be processed when durable draft
 * ownership is readable (or legitimately missing). An unreadable store must
 * never be treated as proof that drafts are absent. This gate also guards new
 * durable Send admission.
 */
internal fun shouldProcessReconciliationDebts(health: DraftHydrationHealth): Boolean = when (health) {
    DraftHydrationHealth.MISSING,
    DraftHydrationHealth.VALID -> true
    DraftHydrationHealth.UNREADABLE_CORRUPT,
    DraftHydrationHealth.UNREADABLE_OVERSIZED,
    DraftHydrationHealth.UNREADABLE_IO -> false
}

internal sealed interface DraftRecoveryResult {
    data object Recovered : DraftRecoveryResult
    data object NotNeeded : DraftRecoveryResult
    data object Failed : DraftRecoveryResult
}

internal class ChatDraftStateMachine(
    private val store: PersistentComposerDraftStore?,
    private val scope: CoroutineScope,
) {
    private val commands = Channel<DraftCommand>(Channel.UNLIMITED)
    private val _drafts = MutableStateFlow(emptyMap<Long, ComposerDraftState>())
    val drafts: StateFlow<Map<Long, ComposerDraftState>> = _drafts.asStateFlow()
    private val _hydrationHealth = MutableStateFlow(DraftHydrationHealth.MISSING)
    val hydrationHealth: StateFlow<DraftHydrationHealth> = _hydrationHealth.asStateFlow()
    private val hydrated = CompletableDeferred<Unit>()
    private val ownerJob: Job
    private var debounceJob: Job? = null
    private var hydrationApplied = false
    private val tombstoned = mutableSetOf<Long>()
    private val preHydrationBaseline = mutableMapOf<Long, ComposerDraftState>()
    private val deferredDurable = mutableListOf<Pair<CompletableDeferred<Boolean>, Map<Long, ComposerDraftState>>>()
    private val activeCommands = Collections.synchronizedList(mutableListOf<DraftCommand>())

    init {
        ownerJob = scope.launch {
            // An unexpected load failure means durable ownership is UNKNOWN.
            // It must never become MISSING (authoritative empty ownership).
            var restored: Map<Long, ComposerDraftState> = emptyMap()
            var startHealth = DraftHydrationHealth.MISSING
            try {
                when (val load = store?.loadResult() ?: DraftStoreLoad.Missing) {
                    is DraftStoreLoad.Missing -> Unit
                    is DraftStoreLoad.Valid -> {
                        restored = load.drafts
                        startHealth = DraftHydrationHealth.VALID
                    }
                    is DraftStoreLoad.Corrupt -> startHealth = DraftHydrationHealth.UNREADABLE_CORRUPT
                    is DraftStoreLoad.Oversized -> startHealth = DraftHydrationHealth.UNREADABLE_OVERSIZED
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                restored = emptyMap()
                startHealth = DraftHydrationHealth.UNREADABLE_IO
            }
            commands.send(DraftCommand.Hydrate(restored, startHealth))
            for (command in commands) {
                process(command)
            }
        }
        ownerJob.invokeOnCompletion { cause ->
            debounceJob?.cancel()
            val unavailable = ChatDraftStateMachineUnavailableException(cause)
            if (!hydrated.isCompleted) hydrated.completeExceptionally(unavailable)

            // Closing first makes every later trySend fail. The drain settles commands
            // that were accepted before owner completion but can no longer be consumed.
            commands.close(unavailable)
            while (true) {
                val command = commands.tryReceive().getOrNull() ?: break
                settleUnavailable(command)
            }
            val inFlight = synchronized(activeCommands) {
                activeCommands.toList().also { activeCommands.clear() }
            }
            inFlight.forEach(::settleUnavailable)
            deferredDurable.forEach { (reply, _) -> reply.complete(false) }
            deferredDurable.clear()
        }
    }

    fun updateText(conversationId: Long, transform: (ComposerDraftState) -> ComposerDraftState) {
        val sent = commands.trySend(
            DraftCommand.Mutate(
                conversationId = conversationId,
                durable = false,
                transform = transform,
                reply = null,
            )
        ).isSuccess
        if (sent) scheduleDebouncedPersist(DRAFT_PERSIST_DEBOUNCE_MS)
    }

    suspend fun updateImmediate(conversationId: Long, transform: (ComposerDraftState) -> ComposerDraftState): Boolean {
        val reply = CompletableDeferred<Boolean>()
        if (!commands.trySend(
            DraftCommand.Mutate(
                conversationId = conversationId,
                durable = false,
                transform = transform,
                reply = reply,
            )
        ).isSuccess) return false
        scheduleDebouncedPersist(0L)
        return reply.await()
    }

    fun queueImmediate(conversationId: Long, transform: (ComposerDraftState) -> ComposerDraftState) {
        val sent = commands.trySend(
            DraftCommand.Mutate(
                conversationId = conversationId,
                durable = false,
                transform = transform,
                reply = null,
            )
        ).isSuccess
        if (sent) scheduleDebouncedPersist(0L)
    }

    suspend fun updateCritical(
        conversationId: Long,
        transform: (ComposerDraftState) -> ComposerDraftState?,
    ): Boolean {
        val reply = CompletableDeferred<Boolean>()
        if (!commands.trySend(
            DraftCommand.Mutate(
                conversationId = conversationId,
                durable = true,
                transform = transform,
                reply = reply,
            )
        ).isSuccess) return false
        return reply.await()
    }

    suspend fun clearDraft(conversationId: Long): Boolean =
        clearDraftCapturing(conversationId).cleared

    /**
     * Durably clears [conversationId] and returns the exact removed draft.
     *
     * The removed state is captured by the single serialized consumer, so a
     * submission admitted between the caller's pre-command snapshot and the
     * actual clear (P16-04B) is still reported authoritatively: callers must
     * release exactly [ClearCapture.removed]'s leases and never a stale
     * pre-command snapshot.
     */
    suspend fun clearDraftCapturing(conversationId: Long): ClearCapture {
        val reply = CompletableDeferred<ClearCapture>()
        if (!commands.trySend(DraftCommand.ClearCapturing(conversationId, reply)).isSuccess) {
            return ClearCapture(cleared = false, removed = null)
        }
        return reply.await()
    }

    /**
     * Durably replaces [draftKey] with fresh defaults carrying
     * [initialRawInput]: one authoritative persisted transition with the
     * existing durable/rollback semantics (exact prior in-memory draft is
     * restored on persistence failure). No old pending attachment, submission,
     * import or handoff ownership survives; the version progresses exactly
     * like any other committed mutation.
     *
     * The caller MUST already hold a product ownership path that awaited
     * readable hydration. This remains a queued state-machine command by
     * design; it must not grow a second hydration wait here.
     */
    suspend fun replaceWithFreshDraftAfterHydration(
        draftKey: Long,
        initialRawInput: String = "",
        onCommittedRemoved: ((ComposerDraftState) -> Unit)? = null,
    ): Boolean = updateCritical(draftKey) { current ->
        // The serialized consumer runs this transform exactly once per
        // accepted command; reporting the consumed state lets the caller
        // release precisely the ownership the commit removed (P16-04) without
        // a stale pre-command snapshot. On a rejected/rolled-back write the
        // caller ignores the report because the result is false.
        onCommittedRemoved?.invoke(current)
        current.copy(
            rawInput = initialRawInput,
            pendingAttachments = emptyList(),
            importOwnership = null,
            activeSubmissionToken = null,
            lastExternalHandoffId = null,
            lastExternalHandoffPartialNotice = null,
            version = current.version + 1L,
        )
    }

    suspend fun reconcileCommittedSubmission(
        draftKey: Long,
        token: String,
        capturedRawInput: String,
        committedPaths: Set<String>,
    ): Boolean {
        if (!awaitHydration()) return false
        val reply = CompletableDeferred<Boolean>()
        if (!commands.trySend(
            DraftCommand.ReconcileCommitted(
                draftKey = draftKey,
                token = token,
                capturedRawInput = capturedRawInput,
                committedPaths = committedPaths,
                reply = reply,
            )
        ).isSuccess) return false
        return reply.await()
    }

    /** Returns false when the owning state-machine job is no longer available. */
    internal suspend fun awaitHydration(): Boolean = awaitBarrier(hydrated)

    fun draftHealthNow(): DraftHydrationHealth = _hydrationHealth.value

    /** Explicit, user-confirmed recovery through this state machine's owner. */
    suspend fun recoverUnreadableDurableState(): DraftRecoveryResult {
        if (!awaitHydration()) return DraftRecoveryResult.Failed
        val reply = CompletableDeferred<DraftRecoveryResult>()
        if (!commands.trySend(DraftCommand.Recover(reply)).isSuccess) return DraftRecoveryResult.Failed
        return reply.await()
    }

    fun importOwnership(conversationId: Long): ComposerImportOwnership? =
        drafts.value[conversationId]?.importOwnership

    private suspend fun process(command: DraftCommand) {
        val reply = command.replyOrNull()
        if (reply != null) activeCommands += command
        try {
            when (command) {
                is DraftCommand.Hydrate -> hydrate(command)
                is DraftCommand.Mutate -> mutate(command)
                is DraftCommand.Clear -> clear(command)
                is DraftCommand.ClearCapturing -> clearCapturing(command)
                is DraftCommand.ReconcileCommitted -> reconcileCommitted(command)
                is DraftCommand.Recover -> recover(command)
                is DraftCommand.PersistDebounced -> persistDebounced(command)
            }
        } catch (failure: Throwable) {
            // Settle the accepted reply BEFORE the finally-removal below: once this
            // owner is terminating, the command must not disappear between
            // activeCommands and the completion drain with its reply incomplete.
            if (reply != null && !reply.isCompleted) settleUnavailable(command)
            throw failure
        } finally {
            if (reply != null) {
                synchronized(activeCommands) {
                    val index = activeCommands.indexOfFirst { active -> active === command }
                    if (index >= 0) activeCommands.removeAt(index)
                }
            }
        }
    }

    private suspend fun hydrate(command: DraftCommand.Hydrate) {
        _hydrationHealth.value = command.health
        val tombstoneSnapshot = tombstoned.toSet()
        val merged = LinkedHashMap(_drafts.value)
        command.restored.forEach { (id, restored) ->
            if (id in tombstoneSnapshot) return@forEach
            val current = merged[id]
            if (current == null) {
                merged[id] = restored
            } else {
                merged[id] = mergeFields(preHydrationBaseline[id] ?: ComposerDraftState(), current, restored)
            }
        }
        tombstoned.clear()
        _drafts.value = merged
        hydrationApplied = true
        if (deferredDurable.isNotEmpty()) {
            // The authoritative pre-command state for the deferred durable
            // commands is the map that existed before the FIRST deferred
            // mutation, re-merged with the hydrated durable state. Computing it
            // once lets the rejected write and a thrown write restore exactly
            // the same state instead of an arbitrary newer map.
            val rollback = deferredRollback(command.restored, tombstoneSnapshot)
            try {
                val ok = durableWrite()
                if (!ok) _drafts.value = rollback
                deferredDurable.forEach { (reply, _) -> reply.complete(ok) }
                deferredDurable.clear()
            } catch (failure: Throwable) {
                // A thrown flush never durably accepted the deferred commands,
                // so no phantom durable mutation may remain in the public draft
                // view and every accepted deferred reply must still settle.
                // The failure itself keeps escaping to the owner.
                _drafts.value = rollback
                deferredDurable.forEach { (reply, _) -> reply.complete(false) }
                deferredDurable.clear()
                throw failure
            }
        }
        preHydrationBaseline.clear()
        hydrated.complete(Unit)
    }

    /**
     * Exact in-memory state a first deferred durable mutation is rolled back to:
     * the map as it existed before that mutation, re-merged with the
     * authoritative durable state read during hydration.
     */
    private fun deferredRollback(
        restored: Map<Long, ComposerDraftState>,
        tombstoneSnapshot: Set<Long>,
    ): Map<Long, ComposerDraftState> {
        val rollback = LinkedHashMap(deferredDurable.first().second)
        restored.forEach { (id, restoredDraft) ->
            if (id in tombstoneSnapshot) return@forEach
            val current = rollback[id]
            if (current == null) {
                rollback[id] = restoredDraft
            } else {
                rollback[id] = mergeFields(
                    preHydrationBaseline[id] ?: ComposerDraftState(),
                    current,
                    restoredDraft,
                )
            }
        }
        return rollback
    }

    private fun mergeFields(
        baseline: ComposerDraftState,
        current: ComposerDraftState,
        restored: ComposerDraftState,
    ): ComposerDraftState = ComposerDraftState(
        rawInput = if (current.rawInput != baseline.rawInput) current.rawInput else restored.rawInput,
        pendingAttachments = if (current.pendingAttachments != baseline.pendingAttachments) {
            current.pendingAttachments
        } else {
            restored.pendingAttachments
        },
        version = if (current.version != baseline.version) current.version else restored.version,
        importOwnership = if (current.importOwnership != baseline.importOwnership) {
            current.importOwnership
        } else {
            restored.importOwnership
        },
        activeSubmissionToken = if (current.activeSubmissionToken != baseline.activeSubmissionToken) {
            current.activeSubmissionToken
        } else {
            restored.activeSubmissionToken
        },
        lastExternalHandoffId = if (current.lastExternalHandoffId != baseline.lastExternalHandoffId) {
            current.lastExternalHandoffId
        } else {
            restored.lastExternalHandoffId
        },
        lastExternalHandoffPartialNotice = when {
            current.lastExternalHandoffId != baseline.lastExternalHandoffId ->
                current.lastExternalHandoffPartialNotice
            current.lastExternalHandoffPartialNotice != baseline.lastExternalHandoffPartialNotice ->
                current.lastExternalHandoffPartialNotice
            else -> restored.lastExternalHandoffPartialNotice
        },
    )

    private suspend fun mutate(command: DraftCommand.Mutate) {
        val conversationId = command.conversationId
        if (!hydrationApplied && conversationId in tombstoned) {
            command.reply?.complete(false)
            return
        }
        val before = _drafts.value
        if (!hydrationApplied && conversationId !in preHydrationBaseline) {
            preHydrationBaseline[conversationId] = before[conversationId] ?: ComposerDraftState()
        }
        val existing = before[conversationId] ?: ComposerDraftState()
        val result = command.transform(existing)
        if (result == null || result == existing) {
            command.reply?.complete(false)
            return
        }
        _drafts.value = before + (conversationId to result)
        if (!command.durable) {
            command.reply?.complete(true)
            return
        }
        if (!hydrationApplied) {
            deferredDurable += command.reply!! to before
            return
        }
        try {
            if (durableWrite()) {
                command.reply?.complete(true)
            } else {
                _drafts.value = before
                command.reply?.complete(false)
            }
        } catch (failure: Throwable) {
            // A thrown durable write leaves the durable artifact at the exact
            // pre-command representation, so a mutation which was never durably
            // committed must never stay published in memory. The rollback
            // belongs to the mutation that changed _drafts, so a newer command
            // can never be overwritten. The original throwable still escapes:
            // fatal failures must keep terminating the owner.
            _drafts.value = before
            throw failure
        }
    }

    private suspend fun clear(command: DraftCommand.Clear) {
        val before = _drafts.value
        if (!hydrationApplied && command.conversationId !in preHydrationBaseline) {
            preHydrationBaseline[command.conversationId] =
                before[command.conversationId] ?: ComposerDraftState()
        }
        val updated = if (before.containsKey(command.conversationId)) before - command.conversationId else before
        _drafts.value = updated
        if (!hydrationApplied) {
            tombstoned += command.conversationId
            deferredDurable += command.reply!! to before
            return
        }
        try {
            if (durableWrite()) {
                command.reply?.complete(true)
            } else {
                _drafts.value = before
                command.reply?.complete(false)
            }
        } catch (failure: Throwable) {
            // Without a durable acknowledgement the clear was never committed,
            // so the exact previous map (attachments, ownership tokens, version
            // and handoff receipt included) must reappear unchanged.
            _drafts.value = before
            throw failure
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun clearCapturing(command: DraftCommand.ClearCapturing) {
        val before = _drafts.value
        if (!hydrationApplied && command.conversationId !in preHydrationBaseline) {
            preHydrationBaseline[command.conversationId] =
                before[command.conversationId] ?: ComposerDraftState()
        }
        val removed = before[command.conversationId]
        val updated = if (before.containsKey(command.conversationId)) before - command.conversationId else before
        _drafts.value = updated
        if (!hydrationApplied) {
            tombstoned += command.conversationId
            val chained = CompletableDeferred<Boolean>()
            deferredDurable += chained to before
            chained.invokeOnCompletion {
                val ok = try {
                    chained.getCompleted()
                } catch (_: Throwable) {
                    false
                }
                command.reply.complete(ClearCapture(cleared = ok, removed = if (ok) removed else null))
            }
            return
        }
        try {
            if (durableWrite()) {
                command.reply.complete(ClearCapture(cleared = true, removed = removed))
            } else {
                _drafts.value = before
                command.reply.complete(ClearCapture(cleared = false, removed = null))
            }
        } catch (failure: Throwable) {
            // Without a durable acknowledgement the clear was never committed,
            // so the exact previous map (attachments, ownership tokens, version
            // and handoff receipt included) must reappear unchanged.
            _drafts.value = before
            throw failure
        }
    }

    private suspend fun persistDebounced(command: DraftCommand.PersistDebounced) {
        if (!hydrationApplied) return
        durableWrite()
    }

    private suspend fun reconcileCommitted(command: DraftCommand.ReconcileCommitted) {
        // Fail-safe: unreadable durable ownership must not be treated as proof
        // that the draft is absent. Never consume debt from an empty view.
        if (!shouldProcessReconciliationDebts(_hydrationHealth.value)) {
            command.reply.complete(false)
            return
        }
        val before = _drafts.value
        val current = before[command.draftKey]
        if (current == null || current.activeSubmissionToken != command.token) {
            command.reply.complete(true)
            return
        }
        val updated = current.copy(
            rawInput = if (current.rawInput == command.capturedRawInput) "" else current.rawInput,
            pendingAttachments = current.pendingAttachments.filterNot {
                it.localPath in command.committedPaths
            },
            activeSubmissionToken = null,
            version = current.version + 1L,
        )
        _drafts.value = before + (command.draftKey to updated)
        try {
            if (durableWrite()) {
                command.reply.complete(true)
            } else {
                _drafts.value = before
                command.reply.complete(false)
            }
        } catch (failure: Throwable) {
            // Reconciliation is a durable consumption of an already committed
            // turn's debt. If persistence throws, the consumed rawInput,
            // committed attachment references and active submission token were
            // never durably released and must stay exactly as they were.
            _drafts.value = before
            throw failure
        }
    }

    private suspend fun recover(command: DraftCommand.Recover) {
        when (_hydrationHealth.value) {
            DraftHydrationHealth.MISSING,
            DraftHydrationHealth.VALID -> {
                command.reply.complete(DraftRecoveryResult.NotNeeded)
                return
            }
            // I/O availability is not proof of corruption; never perform a
            // destructive recovery action for it.
            DraftHydrationHealth.UNREADABLE_IO -> {
                command.reply.complete(DraftRecoveryResult.Failed)
                return
            }
            DraftHydrationHealth.UNREADABLE_CORRUPT,
            DraftHydrationHealth.UNREADABLE_OVERSIZED -> Unit
        }
        val current = _drafts.value
        if (current.values.any { it.activeSubmissionToken != null || it.importOwnership != null }) {
            command.reply.complete(DraftRecoveryResult.Failed)
            return
        }
        when (store?.recoverUnreadable(current) ?: DraftStoreRecoveryResult.Failed) {
            DraftStoreRecoveryResult.Recovered -> {
                _hydrationHealth.value = DraftHydrationHealth.VALID
                command.reply.complete(DraftRecoveryResult.Recovered)
            }
            DraftStoreRecoveryResult.NotNeeded -> command.reply.complete(DraftRecoveryResult.NotNeeded)
            DraftStoreRecoveryResult.Failed -> command.reply.complete(DraftRecoveryResult.Failed)
        }
    }

    private suspend fun durableWrite(): Boolean {
        val store = store ?: return true
        // Ownership gate: while durable ownership is unreadable, the in-memory
        // view is NOT authoritative. Automatic persistence (debounced or
        // critical) must never quarantine-and-replace the unreadable
        // authoritative artifact as if ownership were known, because a
        // previously persisted activeSubmissionToken may represent an already
        // committed logical user turn. Only a real re-hydration that reads a
        // valid durable representation or the explicit confirmed-recovery
        // command may restore VALID.
        when (_hydrationHealth.value) {
            DraftHydrationHealth.MISSING, DraftHydrationHealth.VALID -> Unit
            DraftHydrationHealth.UNREADABLE_CORRUPT,
            DraftHydrationHealth.UNREADABLE_OVERSIZED,
            DraftHydrationHealth.UNREADABLE_IO -> return false
        }
        val ok = store.write(_drafts.value)
        // The machine just persisted an exact representation of its
        // authoritative current state, so durable ownership is readable again.
        // A failed write leaves health (and the unreadable artifact) untouched.
        if (ok) _hydrationHealth.value = DraftHydrationHealth.VALID
        return ok
    }

    private fun scheduleDebouncedPersist(debounceMs: Long) {
        val store = store ?: return
        debounceJob?.cancel()
        debounceJob = scope.launch(Dispatchers.IO) {
            hydrated.await()
            if (debounceMs > 0L) delay(debounceMs)
            commands.trySend(DraftCommand.PersistDebounced(store))
        }
    }

    private suspend fun awaitBarrier(barrier: CompletableDeferred<Unit>): Boolean = try {
        barrier.await()
        true
    } catch (_: ChatDraftStateMachineUnavailableException) {
        false
    }

    private fun settleUnavailable(command: DraftCommand) {
        when (command) {
            is DraftCommand.Mutate -> command.reply?.complete(false)
            is DraftCommand.Clear -> command.reply?.complete(false)
            is DraftCommand.ClearCapturing ->
                command.reply.complete(ClearCapture(cleared = false, removed = null))
            is DraftCommand.ReconcileCommitted -> command.reply.complete(false)
            is DraftCommand.Recover -> command.reply.complete(DraftRecoveryResult.Failed)
            is DraftCommand.Hydrate,
            is DraftCommand.PersistDebounced -> Unit
        }
    }

    private fun DraftCommand.replyOrNull(): CompletableDeferred<*>? = when (this) {
        is DraftCommand.Mutate -> reply
        is DraftCommand.Clear -> reply
        is DraftCommand.ClearCapturing -> reply
        is DraftCommand.ReconcileCommitted -> reply
        is DraftCommand.Recover -> reply
        is DraftCommand.Hydrate,
        is DraftCommand.PersistDebounced -> null
    }

    companion object {
        const val DRAFT_PERSIST_DEBOUNCE_MS = 300L
    }
}

internal sealed interface DraftCommand {
    data class Hydrate(
        val restored: Map<Long, ComposerDraftState>,
        val health: DraftHydrationHealth,
    ) : DraftCommand

    data class Mutate(
        val conversationId: Long,
        val durable: Boolean,
        val transform: (ComposerDraftState) -> ComposerDraftState?,
        val reply: CompletableDeferred<Boolean>?,
    ) : DraftCommand

    data class Clear(
        val conversationId: Long,
        val reply: CompletableDeferred<Boolean>?,
    ) : DraftCommand

    data class ClearCapturing(
        val conversationId: Long,
        val reply: CompletableDeferred<ClearCapture>,
    ) : DraftCommand

    data class ReconcileCommitted(
        val draftKey: Long,
        val token: String,
        val capturedRawInput: String,
        val committedPaths: Set<String>,
        val reply: CompletableDeferred<Boolean>,
    ) : DraftCommand

    data class Recover(
        val reply: CompletableDeferred<DraftRecoveryResult>,
    ) : DraftCommand

    data class PersistDebounced(val store: PersistentComposerDraftStore) : DraftCommand
}

internal class ChatDraftStateMachineUnavailableException(
    cause: Throwable? = null,
) : IllegalStateException("Chat draft state-machine owner is unavailable", cause)

/** Authoritative result of a capturing draft clear: the exact removed draft, if any. */
internal data class ClearCapture(
    val cleared: Boolean,
    val removed: ComposerDraftState?,
)
