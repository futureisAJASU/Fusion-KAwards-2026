package com.projectnuke.fusion.llm

import kotlinx.coroutines.Job
import com.projectnuke.fusion.chat.DynamicFollowUpOwnershipLease

/**
 * Opportunistic/background runtime ownership for dynamic follow-up inference.
 * A started native Conversation is retired through its ticket lifetime and
 * stays under the shared runtime lock until LiteRT reports a terminal
 * callback. Foreground admission requests native cancellation and waits for
 * that terminal boundary; it never cancels the started collector job.
 *
 * Foreground reservations are counted under [stateLock] and block new
 * background tickets. Background work is single-flight, never cold-loads a
 * model, and cannot publish after its ownership lease has been invalidated.
 */
internal class BackgroundPreemptedException :
    kotlinx.coroutines.CancellationException("background runtime owner preempted by foreground admission")

internal data class BackgroundTicket(
    val key: GenerationRequestKey,
    val job: Job,
    val lifetime: BackgroundNativeConversationLifetime,
    val ownershipLease: DynamicFollowUpOwnershipLease?,
    var ownershipRegistration: AutoCloseable? = null,
)

internal object BackgroundRuntimeOwner {
    private val stateLock = Any()
    private var activeTicket: BackgroundTicket? = null
    private var nextSequence = 0L

    /**
     * Outstanding foreground admission leases. Incremented atomically by
     * [beginForegroundAdmission] and decremented by [endForegroundAdmission]
     * (typically from a finally). While positive, [tryAcquire] admits no
     * background owner, so foreground chat/benchmark admission always wins
     * over background enhancement work, including queued foreground callers.
     */
    private var foregroundReservationCount = 0

    /** Background-only native-stop controller. Same-module tests may observe it; production background paths must never record intents on it. */
    internal val controller = NativeConversationCancellationController()

    /** Coroutine context element installing this ticket's cancellation key. */
    fun requestContextFor(ticket: BackgroundTicket): GenerationRequestContext =
        GenerationRequestContext(controller, ticket.key, ticket.lifetime)

    /**
     * Single-flight admission. Returns null while a foreground reservation
     * is pending or another background owner is still active; the caller
     * must skip its work (foreground always wins, background never queues
     * behind or ahead of it).
     */
    fun tryAcquire(
        job: Job,
        conversationId: Long,
        ownershipLease: DynamicFollowUpOwnershipLease? = null,
        runtimeProfileIdentity: String = "unknown",
        nativeTimeoutMs: Long = 0L,
    ): BackgroundTicket? {
        val ticket = synchronized(stateLock) {
            if (!job.isActive || foregroundReservationCount > 0) return null
            val active = activeTicket
            if (active != null && (active.job.isActive || active.lifetime.requiresExclusiveRuntime)) return null
            val sequence = ++nextSequence
            BackgroundTicket(
                key = GenerationRequestKey(conversationId, "followup-$sequence"),
                job = job,
                lifetime = BackgroundNativeConversationLifetime(
                    ticketId = "followup-$sequence",
                    conversationId = conversationId,
                    ownershipGeneration = ownershipLease?.generation ?: 0L,
                    runtimeProfileIdentity = runtimeProfileIdentity,
                    timeoutMs = nativeTimeoutMs,
                ),
                ownershipLease = ownershipLease,
            ).also { activeTicket = it }
        }
        ticket.ownershipRegistration = ownershipLease?.observeRetirement { source -> retire(ticket, source) }
        return ticket
    }

    /**
     * Releases exactly [ticket]. Only the identity-matched ticket clears the
     * active-owner slot, so a late finally can never clear a newer owner —
     * but every ticket always clears its own request-scoped controller
     * state. Without the unconditional clear, a superseded ticket whose
     * release runs after the next [tryAcquire] overwrote the slot would
     * leak its native registration/intent/partial entries (the mismatch
     * early-return skipped [NativeConversationCancellationController.clearRequest]).
     * Keys are unique per ticket, so clearing a stale key cannot disturb
     * the newer owner.
     */
    fun release(ticket: BackgroundTicket) {
        ticket.lifetime.backgroundRelease()
        synchronized(stateLock) {
            if (activeTicket === ticket) activeTicket = null
        }
        ticket.ownershipRegistration?.close()
        ticket.ownershipRegistration = null
        controller.clearRequest(ticket.key)
    }

    fun hasActiveOwner(): Boolean = synchronized(stateLock) {
        activeTicket?.let { it.job.isActive || it.lifetime.requiresExclusiveRuntime } == true
    }

    fun isForegroundPending(): Boolean = synchronized(stateLock) {
        foregroundReservationCount > 0
    }

    /** Test-visible lease deep count for host synchronization. */
    internal fun foregroundReservationCount(): Int = synchronized(stateLock) {
        foregroundReservationCount
    }

    /**
     * Foreground admission hook. Atomically marks a foreground reservation
     * pending — immediately blocking any new [tryAcquire] — and snapshots
     * the active background owner for cancellation. Cancellation is
     * performed outside the lock: [stateLock] is never held across native
     * cancelProcess() or Job.cancel(). Recoverable stop failures are best effort: even
     * if native cancellation cannot be initiated, the job cancellation below
     * still unwinds the background coroutine and releases the runtime
     * mutex. A fatal failure still cancels the job and releases this admission's
     * reservation before propagating. Every successful call must be paired
     * with [endForegroundAdmission] in a finally.
     */
    fun beginForegroundAdmission() {
        val ticket = synchronized(stateLock) {
            foregroundReservationCount++
            activeTicket
        } ?: return
        try {
            ticket.ownershipLease?.invalidate(BackgroundTerminationSource.FOREGROUND_PREEMPTION)
            val retirement = ticket.lifetime.requestRetirement(
                BackgroundTerminationSource.FOREGROUND_PREEMPTION,
            )
            if (retirement == BackgroundRetirementAction.CANCEL_JOB) {
                try {
                    // Compatibility for test hooks and legacy registrations.
                    controller.cancelProcess(ticket.key)
                } catch (_: Exception) {
                    // The Conversation is pre-native, so Job.cancel is safe.
                } finally {
                    ticket.job.cancel(BackgroundPreemptedException())
                }
            }
        } catch (failure: Throwable) {
            // The caller has not acquired a successful admission to release.
            endForegroundAdmission()
            throw failure
        }
    }

    private fun retire(ticket: BackgroundTicket, source: BackgroundTerminationSource) {
        val retirement = ticket.lifetime.requestRetirement(source)
        if (retirement == BackgroundRetirementAction.CANCEL_JOB) {
            ticket.job.cancel(BackgroundPreemptedException())
        }
    }

    /**
     * Releases exactly one foreground reservation taken by
     * [beginForegroundAdmission]. Background admission reopens only when the
     * count returns to zero; the non-negative guard keeps an accidental
     * over-release from permanently unblocking background admission.
     */
    fun endForegroundAdmission() {
        synchronized(stateLock) {
            if (foregroundReservationCount > 0) foregroundReservationCount--
        }
    }
}
