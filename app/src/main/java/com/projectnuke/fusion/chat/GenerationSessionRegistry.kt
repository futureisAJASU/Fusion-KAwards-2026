package com.projectnuke.fusion.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.projectnuke.fusion.llm.GenerationRequestContext
import com.projectnuke.fusion.llm.GenerationRequestKey
import com.projectnuke.fusion.llm.GenerationTerminationIntent
import com.projectnuke.fusion.llm.NativeConversationCancellationController
import com.projectnuke.fusion.llm.FusionRuntimeLock
import com.projectnuke.fusion.llm.FusionLifecycleLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GenerationSession(
    val conversationId: Long,
    val requestId: String,
    val job: Job,
)

class GenerationSessionRegistry(
    internal val userStopSettlementTimeoutMs: Long = 5_000L,
    private val onResearchDeadline: (GenerationRequestSnapshot) -> Unit = {},
) {
    private val sessions = ConcurrentHashMap<Long, GenerationSession>()
    private val latestTokens = ConcurrentHashMap<Long, PendingStart>()
    private val conversationLocks = ConcurrentHashMap<Long, ConversationLockEntry>()
    private val ownershipStripes = Array(16) { Any() }
    private val deletionOwners = ConcurrentHashMap<Long, String>()
    private enum class ResponsePersistenceAdmission {
        NORMAL,
        USER_STOP_CLAIMED,
        USER_STOP_COMMITTING,
    }
    private val responsePersistenceAdmissions =
        ConcurrentHashMap<GenerationRequestKey, ResponsePersistenceAdmission>()
    private val terminalListeners = CopyOnWriteArraySet<(GenerationRequestKey) -> Unit>()
    /** Native Conversation ownership remains subordinate to this registry. */
    val cancellationController = NativeConversationCancellationController()

    /** Runs after the exact registry token has reached terminal state. */
    internal fun registerTerminalListener(listener: (GenerationRequestKey) -> Unit): () -> Unit {
        terminalListeners += listener
        return { terminalListeners -= listener }
    }

    internal class PendingStart(
        val conversationId: Long,
        val requestId: String
    ) {
        private val state = AtomicReference(State.PENDING)
        val completed = CompletableDeferred<Unit>()
        val publishedSession = AtomicReference<GenerationSession?>()

        @Volatile
        var predecessor: PendingStart? = null

        /**
         * True when this start's token was claimed while a deletion owned the
         * conversation. The refusal survives the deletion's release so a start
         * that began during a deletion can never install afterwards.
         */
        @Volatile
        var claimedDuringDeletion: Boolean = false

        enum class State { PENDING, STARTING, INSTALLED, CANCELLED }

        fun cancel(reason: String): Boolean {
            while (true) {
                val current = state.get()
                if (current == State.INSTALLED || current == State.CANCELLED) return false
                if (state.compareAndSet(current, State.CANCELLED)) return true
            }
        }

        fun tryBeginStarting(): Boolean =
            state.compareAndSet(State.PENDING, State.STARTING)

        fun tryInstall(): Boolean =
            state.compareAndSet(State.STARTING, State.INSTALLED)

        fun cancellationException(): CancellationException =
            CancellationException("Request $requestId was cancelled before start")

        fun state(): State = state.get()
    }

    internal var onBeforeInstall: suspend () -> Unit = {}

    /** Test-only synchronization seam for the observation/CAS race. */
    internal var onBeforeSettlementCancel: suspend () -> Unit = {}

    /** Test-only seam between observing a head and reserving its intent. */
    internal var onBeforeIntentReservation: suspend () -> Unit = {}

    /** Test-only seam after an exact intent is reserved and before settlement. */
    internal var onAfterIntentReservation: suspend () -> Unit = {}

    /** Test-only event after a pending token is atomically cancelled. */
    internal var onAfterSettlementCancel: suspend () -> Unit = {}

    /** Test-only seam after a request token claims the conversation head. */
    internal var onAfterTokenClaim: suspend () -> Unit = {}

    private class ConversationLockEntry(
        val mutex: Mutex = Mutex(),
        val users: AtomicInteger = AtomicInteger(0),
    )

    /**
     * Exact per-conversation suspendable serialization. Refcounted so the map
     * never leaks conversation IDs: an entry is removed only when its exact
     * owner refcount reaches zero, with identity checking so an old owner can
     * never remove a newer replacement entry (anti-ABA).
     */
    private suspend fun <T> withConversationLock(
        conversationId: Long,
        block: suspend () -> T,
    ): T {
        val entry = conversationLocks.compute(conversationId) { _, existing ->
            (existing ?: ConversationLockEntry()).also { it.users.incrementAndGet() }
        }!!
        try {
            return entry.mutex.withLock { block() }
        } finally {
            conversationLocks.computeIfPresent(conversationId) { _, current ->
                if (current !== entry) {
                    current
                } else if (entry.users.decrementAndGet() == 0) {
                    null
                } else {
                    current
                }
            }
        }
    }

    internal fun conversationLockRegistrySizeForTests(): Int = conversationLocks.size

    private fun ownershipLockFor(conversationId: Long): Any =
        ownershipStripes[(conversationId and 15L).toInt()]

    /**
     * Claims exclusive deferred ownership of a conversation for an in-flight
     * deletion. While owned, [start] refuses to install a new session for the
     * conversation so a generation can never outlive a deletion that already
     * began cancelling it. Must be paired with [releaseDeletionOwnership].
     */
    fun claimDeletionOwnership(conversationId: Long, reason: String): Boolean =
        synchronized(ownershipLockFor(conversationId)) {
            deletionOwners.putIfAbsent(conversationId, reason) == null
        }

    fun releaseDeletionOwnership(conversationId: Long, reason: String) {
        synchronized(ownershipLockFor(conversationId)) {
            deletionOwners.remove(conversationId, reason)
        }
    }

    internal fun deletionOwner(conversationId: Long): String? =
        deletionOwners[conversationId]

    suspend fun start(
        scope: CoroutineScope,
        snapshot: GenerationRequestSnapshot,
        block: suspend (GenerationSession) -> Unit
    ): GenerationSession {
        val conversationId = snapshot.conversationId
        val requestId = snapshot.requestId

        val token = PendingStart(conversationId, requestId)

        try {
            synchronized(ownershipLockFor(conversationId)) {
                latestTokens.compute(conversationId) { _, previous ->
                    token.predecessor = previous
                    previous?.cancel("superseded-by-$requestId")
                    token.claimedDuringDeletion = deletionOwners.containsKey(conversationId)
                    token
                }
            }
            onAfterTokenClaim()

            return withConversationLock(conversationId) {
                if (token.claimedDuringDeletion) {
                    throw CancellationException(
                        "Conversation $conversationId was being deleted when request $requestId began"
                    )
                }
                deletionOwners[conversationId]?.let { ownerReason ->
                    throw CancellationException(
                        "Conversation $conversationId is being deleted ($ownerReason)"
                    )
                }

                if (!token.tryBeginStarting()) {
                    throw token.cancellationException()
                }

                val previous = sessions[conversationId]
                if (previous != null && !previous.job.isCompleted) {
                    requestNativeRetirement(
                        GenerationRequestKey(conversationId, previous.requestId),
                        GenerationTerminationIntent.SUPERSEDED,
                        "superseded-by-$requestId",
                    )
                    previous.job.cancel(CancellationException("superseded-by-${requestId}"))
                    previous.job.join()
                }

                if (!scope.coroutineContext[Job]!!.isActive) {
                    throw CancellationException(
                        "Scope cancelled before session could start for conversation $conversationId"
                    )
                }

                lateinit var gs: GenerationSession

                val requestKey = GenerationRequestKey(conversationId, requestId)
                val coroutineJob = scope.launch(start = CoroutineStart.LAZY) {
                    withContext(GenerationRequestContext(cancellationController, requestKey)) {
                        if (!ResearchRunPolicy.execute(snapshot.interactionMode) { block(gs) }) onResearchDeadline(snapshot)
                    }
                }

                gs = GenerationSession(
                    conversationId = conversationId,
                    requestId = requestId,
                    job = coroutineJob,
                )

                token.publishedSession.set(gs)
                var owner = sessions.putIfAbsent(conversationId, gs)
                if (owner != null && owner.job.isCompleted) {
                    sessions.remove(conversationId, owner)
                    owner = sessions.putIfAbsent(conversationId, gs)
                }
                if (owner != null) {
                    sessions.remove(conversationId, gs)
                    if (!coroutineJob.isCompleted) {
                        coroutineJob.cancel(CancellationException(
                            "Request $requestId lost the conversation slot to ${owner.requestId}"
                        ))
                        coroutineJob.join()
                    }
                    throw CancellationException(
                        "Request $requestId superseded by ${owner.requestId} before install"
                    )
                }

                onBeforeInstall()

                if (!token.tryInstall()) {
                    sessions.remove(conversationId, gs)
                    if (!coroutineJob.isCompleted) {
                        coroutineJob.cancel(CancellationException(
                            "Request $requestId was cancelled before install"
                        ))
                        coroutineJob.join()
                    }
                    throw token.cancellationException()
                }

                gs.job.invokeOnCompletion {
                    val terminalIntent = cancellationController.intentFor(requestKey)
                    synchronized(ownershipLockFor(conversationId)) {
                        latestTokens.remove(conversationId, token)
                        sessions.remove(conversationId, gs)
                        responsePersistenceAdmissions.remove(requestKey)
                    }
                    cancellationController.clearRequest(requestKey)
                    FusionLifecycleLog.event(
                        name = "REGISTRY_TOKEN_TERMINAL",
                        conversationId = conversationId,
                        requestId = requestId,
                        terminationIntent = terminalIntent,
                        requestActive = false,
                        registryActive = hasLogicalRequest(conversationId),
                        runtimeState = FusionLifecycleLog.runtimeStateName(FusionRuntimeLock.ownerSnapshot()),
                    )
                    terminalListeners.forEach { listener ->
                        runCatching { listener(requestKey) }
                    }
                }

                if (!gs.job.start()) {
                    sessions.remove(conversationId, gs)
                    synchronized(ownershipLockFor(conversationId)) {
                        latestTokens.remove(conversationId, token)
                    }
                    throw CancellationException(
                        "Scope cancelled before session could start for conversation $conversationId"
                    )
                }

                gs
            }
 } catch (e: CancellationException) {
    withContext(NonCancellable) {
      if (token.state() != PendingStart.State.INSTALLED) {
        token.publishedSession.get()?.let { session ->
          sessions.remove(conversationId, session)
          if (!session.job.isCompleted) {
            session.job.cancel(CancellationException("start-cancelled"))
            session.job.join()
          }
        }
        val settled = mutableListOf<PendingStart>()
        settlePredecessorChain(token, "start-cancelled", settled)
        settled.forEach { it.completed.await() }
      }
    }
    throw e
  } finally {
            if (token.state() != PendingStart.State.INSTALLED) {
                val requestKey = GenerationRequestKey(conversationId, token.requestId)
                synchronized(ownershipLockFor(conversationId)) {
                    latestTokens.remove(conversationId, token)
                    responsePersistenceAdmissions.remove(requestKey)
                }
                cancellationController.clearRequest(requestKey)
                token.publishedSession.get()?.let { session ->
                    sessions.remove(conversationId, session)
                    if (!session.job.isCompleted) {
                        session.job.cancel(CancellationException("start-aborted"))
                    }
                }
            }
            token.completed.complete(Unit)
        }
    }

    private fun GenerationSession.isBusy(): Boolean =
        job.isActive && !job.isCancelled

    fun activeSession(conversationId: Long): GenerationSession? =
        sessions[conversationId]?.takeIf { it.isBusy() }

    fun isActive(conversationId: Long, requestId: String): Boolean =
        sessions[conversationId]?.let {
            it.requestId == requestId && it.isBusy()
        } == true

    /**
     * Exact terminal USER_STOP ownership. This deliberately remains true for
     * an installed request whose Job was cancelled by the bounded fallback;
     * [isActive] is therefore not part of this predicate.
     */
    internal fun canAdoptUserStopPartial(
        conversationId: Long,
        requestId: String,
    ): Boolean {
        synchronized(ownershipLockFor(conversationId)) {
            return canAdoptUserStopPartialLocked(conversationId, requestId)
        }
    }

    private fun canAdoptUserStopPartialLocked(
        conversationId: Long,
        requestId: String,
    ): Boolean {
        val key = GenerationRequestKey(conversationId, requestId)
        val token = latestTokens[conversationId] ?: return false
        val terminal = when (token.state()) {
            PendingStart.State.CANCELLED -> token.completed.isCompleted
            PendingStart.State.INSTALLED -> token.publishedSession.get()?.job?.isCompleted == true
            else -> false
        }
        return token.requestId == requestId &&
            !terminal &&
            deletionOwners[conversationId] == null &&
            cancellationController.intentFor(key) == GenerationTerminationIntent.USER_STOP &&
            !responsePersistenceAdmissions.containsKey(key)
    }

    /** Claims the exact response slot once for USER_STOP partial persistence. */
    internal fun claimUserStopPartial(
        conversationId: Long,
        requestId: String,
    ): Boolean {
        synchronized(ownershipLockFor(conversationId)) {
            if (!canAdoptUserStopPartialLocked(conversationId, requestId)) return false
            return responsePersistenceAdmissions.putIfAbsent(
                GenerationRequestKey(conversationId, requestId),
                ResponsePersistenceAdmission.USER_STOP_CLAIMED,
            ) == null
        }
    }

    /**
     * Linearizes the exact request's one assistant-row commit with generation
     * replacement and conversation deletion. A successful admission is the
     * durable-commit boundary: cancellation after this point may not undo the
     * established non-cancellable Room/version transaction. A request that
     * lost ownership before this point is rejected, and each request can
     * claim at most one assistant response slot.
     */
    internal fun admitAssistantResponsePersistence(key: GenerationRequestKey): Boolean =
        synchronized(ownershipLockFor(key.conversationId)) {
            when (responsePersistenceAdmissions[key]) {
                ResponsePersistenceAdmission.USER_STOP_CLAIMED -> {
                    if (!canCommitUserStopPartialLocked(key)) return@synchronized false
                    responsePersistenceAdmissions[key] = ResponsePersistenceAdmission.USER_STOP_COMMITTING
                    true
                }
                ResponsePersistenceAdmission.NORMAL,
                ResponsePersistenceAdmission.USER_STOP_COMMITTING,
                -> false
                null -> {
                    if (deletionOwners.containsKey(key.conversationId)) return@synchronized false
                    val token = latestTokens[key.conversationId] ?: return@synchronized false
                    val session = sessions[key.conversationId] ?: return@synchronized false
                    if (token.requestId != key.requestId ||
                        token.state() != PendingStart.State.INSTALLED ||
                        session.requestId != key.requestId ||
                        !session.job.isActive ||
                        cancellationController.intentFor(key) != null
                    ) return@synchronized false
                    responsePersistenceAdmissions[key] = ResponsePersistenceAdmission.NORMAL
                    if (!session.job.isActive ||
                        deletionOwners.containsKey(key.conversationId) ||
                        cancellationController.intentFor(key) != null
                    ) {
                        responsePersistenceAdmissions.remove(key, ResponsePersistenceAdmission.NORMAL)
                        false
                    } else {
                        true
                    }
                }
            }
        }

    private fun canCommitUserStopPartialLocked(key: GenerationRequestKey): Boolean {
        val token = latestTokens[key.conversationId] ?: return false
        return token.requestId == key.requestId &&
            token.state() == PendingStart.State.INSTALLED &&
            sessions[key.conversationId]?.let { session ->
                session.requestId == key.requestId && session.job.isCancelled && !session.job.isCompleted
            } == true &&
            deletionOwners[key.conversationId] == null &&
            cancellationController.intentFor(key) == GenerationTerminationIntent.USER_STOP
    }

    fun cancel(conversationId: Long, reason: String): Boolean {
        return synchronized(ownershipLockFor(conversationId)) {
            val session = sessions[conversationId] ?: return@synchronized false
            if (!session.isBusy()) return@synchronized false
            requestNativeRetirement(
                GenerationRequestKey(conversationId, session.requestId),
                GenerationTerminationIntent.INTERNAL_CANCEL,
                reason,
            )
            session.job.cancel(CancellationException(reason))
            true
        }
    }

    suspend fun cancelAndJoin(conversationId: Long, reason: String): Boolean {
        return cancelAndJoin(conversationId, GenerationTerminationIntent.INTERNAL_CANCEL, reason)
    }

    suspend fun cancelAndJoin(
        conversationId: Long,
        intent: GenerationTerminationIntent,
        reason: String = intent.name,
    ): Boolean {
        val observedHead = synchronized(ownershipLockFor(conversationId)) {
            latestTokens[conversationId]
        }
        if (observedHead == null) {
            return withConversationLock(conversationId) {
                val session = sessions[conversationId]
                if (session != null && !session.job.isCompleted) {
                    requestNativeRetirement(
                        GenerationRequestKey(conversationId, session.requestId),
                        intent,
                        reason,
                    )
                    session.job.cancel(CancellationException(reason))
                    session.job.join()
                    true
                } else false
            }
        }
        onBeforeIntentReservation()
        val head = reserveIntentIfCurrent(conversationId, observedHead.requestId, intent) ?: return false

        val settled = mutableListOf<PendingStart>()

        settlePendingStart(head, reason, settled, intent)

        settlePredecessorChain(head, reason, settled, intent)

        settled.forEach { it.completed.await() }

        return true
    }

    /**
     * Structural deletion settlement barrier. Deletion ownership must already
     * be held by the caller. The barrier promotes the current exact request to
     * CONVERSATION_DELETE, asks the native controller to stop when registered,
     * and joins the complete token/predecessor lifecycle before returning.
     */
    suspend fun settleForDeletion(
        conversationId: Long,
        reason: String = "conversation-delete",
    ): Boolean {
        require(deletionOwner(conversationId) != null) {
            "Deletion settlement requires ownership for conversation $conversationId"
        }
        while (true) {
            val observedHead = synchronized(ownershipLockFor(conversationId)) {
                latestTokens[conversationId]
            }
            if (observedHead == null) {
                val session = sessions[conversationId]
                if (session == null || session.job.isCompleted) return true
                requestNativeRetirement(
                    GenerationRequestKey(conversationId, session.requestId),
                    GenerationTerminationIntent.CONVERSATION_DELETE,
                    reason,
                )
                session.job.cancel(CancellationException(reason))
                session.job.join()
                continue
            }

            val head = reserveIntentIfCurrent(
                conversationId,
                observedHead.requestId,
                GenerationTerminationIntent.CONVERSATION_DELETE,
            ) ?: continue
            onAfterIntentReservation()
            cancellationController.cancelProcess(
                GenerationRequestKey(conversationId, head.requestId)
            )

            val settled = mutableListOf<PendingStart>()
            settlePendingStart(head, reason, settled, GenerationTerminationIntent.CONVERSATION_DELETE)
            settlePredecessorChain(head, reason, settled, GenerationTerminationIntent.CONVERSATION_DELETE)
            settled.forEach { it.completed.await() }
            return true
        }
    }

    suspend fun cancelAndJoin(
        conversationId: Long,
        requestId: String,
        reason: String
    ): Boolean {
        return cancelAndJoin(conversationId, requestId, GenerationTerminationIntent.INTERNAL_CANCEL, reason)
    }

    suspend fun cancelAndJoin(
        conversationId: Long,
        requestId: String,
        intent: GenerationTerminationIntent,
        reason: String = intent.name,
    ): Boolean {
        val observedHead = synchronized(ownershipLockFor(conversationId)) {
            latestTokens[conversationId]
        } ?: return false
        if (observedHead.requestId != requestId) return false
        onBeforeIntentReservation()
        val head = reserveIntentIfCurrent(conversationId, requestId, intent) ?: return false
        onAfterIntentReservation()

        val settled = mutableListOf<PendingStart>()

        if (head.state() == PendingStart.State.CANCELLED && head.completed.isCompleted) return false
        settlePendingStart(head, reason, settled)

        settlePredecessorChain(head, reason, settled)

        settled.forEach { it.completed.await() }

        return true
    }

/**
     * Records a typed USER_STOP, asks the exact native Conversation to stop,
     * then waits for the generation coroutine to return a Cancelled outcome.
     * Job cancellation is only the bounded fallback when native cancellation
     * cannot be initiated or cannot settle.
     */
    suspend fun stopAndJoin(conversationId: Long, requestId: String): Boolean {
        var reservedHead: PendingStart? = null
        try {
            val observedHead = synchronized(ownershipLockFor(conversationId)) {
                latestTokens[conversationId]
            } ?: return false
            if (observedHead.requestId != requestId) return false
            onBeforeIntentReservation()
            val head = reserveIntentIfCurrent(
                conversationId,
                requestId,
                GenerationTerminationIntent.USER_STOP,
            ) ?: return false
            reservedHead = head
            val key = GenerationRequestKey(conversationId, requestId)
            val nativeCancellationStarted = cancellationController.cancelProcess(key)
            FusionLifecycleLog.event(
                name = if (nativeCancellationStarted) "NATIVE_STOP_STARTED" else "NATIVE_STOP_FALLBACK",
                conversationId = conversationId,
                requestId = requestId,
                terminationIntent = GenerationTerminationIntent.USER_STOP,
                requestActive = true,
                registryActive = hasLogicalRequest(conversationId),
                runtimeState = FusionLifecycleLog.runtimeStateName(FusionRuntimeLock.ownerSnapshot()),
            )
            if (!nativeCancellationStarted) {
                return settleReservedRequest(head, "user-stop-job-fallback")
            }

            val session = head.publishedSession.get()
            if (head.state() != PendingStart.State.INSTALLED || session == null) {
                FusionLifecycleLog.event(
                    name = "NATIVE_STOP_FALLBACK",
                    conversationId = conversationId,
                    requestId = requestId,
                    reason = "START_NOT_INSTALLED",
                    terminationIntent = GenerationTerminationIntent.USER_STOP,
                    requestActive = true,
                    registryActive = hasLogicalRequest(conversationId),
                    runtimeState = FusionLifecycleLog.runtimeStateName(FusionRuntimeLock.ownerSnapshot()),
                )
                return settleReservedRequest(head, "user-stop-start-fallback")
            }

            if (withTimeoutOrNull(userStopSettlementTimeoutMs) { session.job.join(); true } == true) {
                FusionLifecycleLog.event(
                    name = "SESSION_JOB_SETTLED",
                    conversationId = conversationId,
                    requestId = requestId,
                    terminationIntent = GenerationTerminationIntent.USER_STOP,
                    requestActive = false,
                    registryActive = hasLogicalRequest(conversationId),
                    runtimeState = FusionLifecycleLog.runtimeStateName(FusionRuntimeLock.ownerSnapshot()),
                )
                return true
            }

            FusionLifecycleLog.event(
                name = "NATIVE_STOP_FALLBACK",
                conversationId = conversationId,
                requestId = requestId,
                reason = "SETTLEMENT_TIMEOUT",
                terminationIntent = GenerationTerminationIntent.USER_STOP,
                requestActive = true,
                registryActive = hasLogicalRequest(conversationId),
                runtimeState = FusionLifecycleLog.runtimeStateName(FusionRuntimeLock.ownerSnapshot()),
            )
            return settleReservedRequest(head, "user-stop-timeout-fallback")
        } catch (cancelled: CancellationException) {
            // Stop's caller may be disposed while it awaits native settlement.
            // The request lifetime still owns terminal cleanup and fallback.
            withContext(NonCancellable) {
                reservedHead?.let { settleReservedRequest(it, "user-stop-caller-cancelled") }
                    ?: cancelAndJoin(
                        conversationId,
                        requestId,
                        GenerationTerminationIntent.USER_STOP,
                        "user-stop-caller-cancelled",
                    )
            }
            throw cancelled
        }
    }

    /** Settles the exact token whose USER_STOP was already reserved by stopAndJoin. */
    private suspend fun settleReservedRequest(head: PendingStart, reason: String): Boolean {
        val settled = mutableListOf<PendingStart>()
        settlePendingStart(head, reason, settled, GenerationTerminationIntent.USER_STOP)
        settlePredecessorChain(head, reason, settled, GenerationTerminationIntent.USER_STOP)
        settled.forEach { it.completed.await() }
        return true
    }

    /**
     * Atomically reserves an intent against the current lifecycle token. The
     * caller may block or join only after this short ownership decision.
     */
    private fun reserveIntentIfCurrent(
        conversationId: Long,
        requestId: String,
        intent: GenerationTerminationIntent,
    ): PendingStart? = synchronized(ownershipLockFor(conversationId)) {
        val token = latestTokens[conversationId] ?: return@synchronized null
        if (token.requestId != requestId || token.isTerminal()) return@synchronized null
        val key = GenerationRequestKey(conversationId, requestId)
        if (!cancellationController.reserveIntent(key, intent)) return@synchronized null
        token
    }

    /** Native cancel is requested before any installed session Job is cancelled. */
    private fun requestNativeRetirement(
        key: GenerationRequestKey,
        requestedIntent: GenerationTerminationIntent,
        reason: String,
    ): Boolean {
        val accepted = cancellationController.reserveIntent(key, requestedIntent)
        val effectiveIntent = cancellationController.intentFor(key)
        if (!accepted && effectiveIntent == null) return false
        FusionLifecycleLog.event(
            name = "RETIRE_REQUESTED",
            conversationId = key.conversationId,
            requestId = key.requestId,
            reason = reason,
            terminationIntent = effectiveIntent ?: requestedIntent,
            requestActive = true,
            registryActive = hasLogicalRequest(key.conversationId),
            runtimeState = FusionLifecycleLog.runtimeStateName(FusionRuntimeLock.ownerSnapshot()),
        )
        return cancellationController.cancelProcess(key)
    }

    private fun PendingStart.isTerminal(): Boolean = when (state()) {
        // A cancelled token may still be awaiting the predecessor/session
        // settlement owned by the first caller. Repeated exact cancellation
        // must be allowed to join that same terminal lifecycle.
        PendingStart.State.CANCELLED -> completed.isCompleted
        PendingStart.State.INSTALLED -> publishedSession.get()?.job?.isCompleted == true
        PendingStart.State.PENDING,
        PendingStart.State.STARTING -> false
    }

    private suspend fun settlePredecessorChain(
        from: PendingStart,
        reason: String,
        out: MutableList<PendingStart>,
        terminationIntent: GenerationTerminationIntent = GenerationTerminationIntent.SUPERSEDED,
    ) {
        var pred = from.predecessor
        while (pred != null) {
            settlePendingStart(pred, reason, out, terminationIntent)
            pred = pred.predecessor
        }
    }

    /**
     * Settles one lifecycle token without trusting a stale state observation.
     * If cancellation loses the PENDING/STARTING CAS, the loop re-reads the
     * token and joins the session that won installation instead.
     */
    private suspend fun settlePendingStart(
        token: PendingStart,
        reason: String,
        out: MutableList<PendingStart>,
        terminationIntent: GenerationTerminationIntent = GenerationTerminationIntent.INTERNAL_CANCEL,
    ) {
        while (true) {
            when (token.state()) {
                PendingStart.State.PENDING,
                PendingStart.State.STARTING -> {
                    onBeforeSettlementCancel()
                    if (token.cancel(reason)) {
                        onAfterSettlementCancel()
                        addSettlement(out, token)
                        return
                    }
                }
                PendingStart.State.CANCELLED -> {
                    addSettlement(out, token)
                    return
                }
                PendingStart.State.INSTALLED -> {
                    val session = token.publishedSession.get()
                    if (session == null) continue
                    val requestKey = GenerationRequestKey(token.conversationId, token.requestId)
                    if (!session.job.isCompleted) {
                        requestNativeRetirement(requestKey, terminationIntent, reason)
                        session.job.cancel(CancellationException(reason))
                    }
                    session.job.join()
                    val terminalIntent = cancellationController.intentFor(requestKey)
                    FusionLifecycleLog.event(
                        name = "SESSION_JOB_SETTLED",
                        conversationId = token.conversationId,
                        requestId = token.requestId,
                        terminationIntent = terminalIntent,
                        requestActive = false,
                        registryActive = hasLogicalRequest(token.conversationId),
                        runtimeState = FusionLifecycleLog.runtimeStateName(FusionRuntimeLock.ownerSnapshot()),
                    )
                    addSettlement(out, token)
                    return
                }
            }
        }
    }

    private fun addSettlement(out: MutableList<PendingStart>, token: PendingStart) {
        if (out.none { it === token } && !token.completed.isCompleted) out.add(token)
    }

    fun hasActiveSession(conversationId: Long): Boolean =
        sessions[conversationId]?.isBusy() == true

    fun activeConversationIds(): Set<Long> =
        sessions.filterValues { it.isBusy() }.keys.toSet()

    /** Includes a pending/starting exact token, not just an already-running Job. */
    internal fun hasLogicalRequest(conversationId: Long): Boolean {
        val token = synchronized(ownershipLockFor(conversationId)) { latestTokens[conversationId] }
        return token != null && !token.isTerminal()
    }

    internal fun isPending(conversationId: Long, requestId: String): Boolean {
        val token = latestTokens[conversationId] ?: return false
        return token.requestId == requestId && token.state() == PendingStart.State.PENDING
    }

    internal fun isStarting(conversationId: Long, requestId: String): Boolean {
        val token = latestTokens[conversationId] ?: return false
        return token.requestId == requestId && token.state() == PendingStart.State.STARTING
    }

    internal fun hasSessionPublished(conversationId: Long, requestId: String): Boolean {
        val token = latestTokens[conversationId] ?: return false
        if (token.requestId != requestId) return false
        return token.publishedSession.get() != null
    }

    internal fun latestToken(conversationId: Long): PendingStart? =
        latestTokens[conversationId]

    internal fun registeredConversationCount(): Int =
        cancellationController.registeredRequestCount()

    internal fun recordedIntentCount(): Int =
        cancellationController.recordedIntentCount()

    internal fun partialOutputCount(): Int =
        cancellationController.partialOutputCount()

    internal fun terminalAdoptionClaimCount(): Int =
        responsePersistenceAdmissions.size
}
