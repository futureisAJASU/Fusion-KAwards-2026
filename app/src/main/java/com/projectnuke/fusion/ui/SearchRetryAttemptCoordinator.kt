package com.projectnuke.fusion.ui

import com.projectnuke.fusion.llm.isFatalError
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Production owner of ONE explicit Retry Search attempt.
 *
 * The coordinator is the retry owner that the ChatScreen "다시 검색" action
 * calls. It owns the parts of the attempt lifecycle that must never be
 * duplicated or lost:
 *
 *  - claim: at most ONE in-flight attempt per failed message. A second tap
 *    on the same failed message is refused deterministically instead of
 *    racing the first attempt through the generation registry.
 *  - consume: the frozen retry snapshot is consumed exactly once, at the
 *    attempt's start. A frozen snapshot is never replayed.
 *  - restore: a failure BEFORE the attempt started keeps the retry eligible.
 *  - settle: the attempt's ownership is released exactly once, on every
 *    terminal path (success, typed failure, cancellation, preparation
 *    refusal).
 *
 * The stages themselves are supplied by the production caller (ChatScreen):
 * `prepare` performs the frozen scope / source-access validation, `register`
 * starts the generation, and `execute` awaits the registered attempt's
 * terminal state. The coordinator never re-derives a retry from mutable UI
 * state and never owns a request snapshot of its own.
 */
internal class SearchRetryAttemptCoordinator(
    private val recovery: TrustRecoveryCoordinator = TrustRecoveryCoordinator(),
) {
    /** One claimed retry attempt. */
    internal class Attempt internal constructor(
        val failureMessageId: Long,
        val frozen: FrozenSearchRetry,
    ) {
        internal val consumed = AtomicBoolean(false)
        internal val settled = AtomicBoolean(false)
    }

    internal enum class RejectionReason {
        /** No frozen snapshot exists for this failed message. */
        NO_FROZEN_SNAPSHOT,

        /** Another attempt for the same failed message is still in flight. */
        ALREADY_IN_FLIGHT,
    }

    internal sealed interface ClaimOutcome {
        data class Granted(val attempt: Attempt) : ClaimOutcome
        data class Rejected(val reason: RejectionReason) : ClaimOutcome
    }

    internal enum class Stage { PREPARE, REGISTER, EXECUTE }

    internal sealed interface AttemptOutcome {
        data object Completed : AttemptOutcome
        data class Aborted(val stage: Stage) : AttemptOutcome
        data class Failed(val stage: Stage, val cause: Throwable?) : AttemptOutcome
    }

    /** In-flight ownership, one attempt per failed message. */
    private val attempts = ConcurrentHashMap<Long, Attempt>()

    /**
     * Claims exclusive ownership of one failed message's retry. The frozen
     * snapshot is read but NOT consumed here: consumption happens at the
     * attempt's start so a preparation refusal keeps the retry eligible.
     */
    fun claim(failureMessageId: Long): ClaimOutcome {
        var outcome: ClaimOutcome? = null
        attempts.compute(failureMessageId) { _, existing ->
            if (existing != null && !existing.settled.get()) {
                outcome = ClaimOutcome.Rejected(RejectionReason.ALREADY_IN_FLIGHT)
                existing
            } else {
                val frozen = recovery.pendingSearchRetry(failureMessageId)
                if (frozen == null) {
                    outcome = ClaimOutcome.Rejected(RejectionReason.NO_FROZEN_SNAPSHOT)
                    null
                } else {
                    Attempt(failureMessageId, frozen).also {
                        outcome = ClaimOutcome.Granted(it)
                    }
                }
            }
        }
        return checkNotNull(outcome)
    }

    /** Consumes the frozen retry exactly once, at the attempt's start. */
    fun consume(attempt: Attempt): Boolean {
        if (!attempt.consumed.compareAndSet(false, true)) return false
        if (recovery.consumeSearchRetry(attempt.failureMessageId, attempt.frozen)) return true
        attempt.consumed.set(false)
        return false
    }

    /**
     * Restores the retry eligibility after a failure that happened BEFORE
     * the attempt started. A post-start failure never restores: the attempt
     * owned the request and its failure produced its own recovery action.
     */
    fun restore(attempt: Attempt): Boolean {
        if (!attempt.consumed.compareAndSet(true, false)) return false
        return recovery.restoreSearchRetryIfAbsent(attempt.failureMessageId, attempt.frozen)
    }

    /** Releases the attempt's ownership exactly once. */
    fun settle(attempt: Attempt) {
        if (!attempt.settled.compareAndSet(false, true)) return
        attempts.remove(attempt.failureMessageId, attempt)
    }

    /**
     * Runs one claimed attempt through the production stages with exact
     * ownership:
     *
     *  - a preparation failure keeps the retry eligible;
     *  - the frozen retry is consumed exactly once before registration;
     *  - a registration failure restores the retry eligibility;
     *  - a post-registration failure keeps the attempt consumed;
     *  - the ownership is released exactly once on every terminal path.
     */
    suspend fun <P> run(
        attempt: Attempt,
        prepare: suspend () -> P,
        register: suspend (P) -> Unit,
        execute: suspend (P) -> Unit,
    ): AttemptOutcome {
        return try {
            val prepared = try {
                prepare()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (failure.isFatalError()) throw failure
                return AttemptOutcome.Failed(Stage.PREPARE, failure)
            }
            if (!consume(attempt)) {
                return AttemptOutcome.Aborted(Stage.REGISTER)
            }
            try {
                register(prepared)
            } catch (cancelled: CancellationException) {
                restore(attempt)
                throw cancelled
            } catch (failure: Throwable) {
                if (failure.isFatalError()) {
                    restore(attempt)
                    throw failure
                }
                restore(attempt)
                return AttemptOutcome.Failed(Stage.REGISTER, failure)
            }
            try {
                execute(prepared)
                AttemptOutcome.Completed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (failure.isFatalError()) throw failure
                AttemptOutcome.Failed(Stage.EXECUTE, failure)
            }
        } finally {
            settle(attempt)
        }
    }

    /** Read-only ownership seam for host verification. */
    internal fun inFlightAttemptCount(): Int = attempts.size
}

/**
 * A preparation refusal that carries the bounded user-facing reason. The
 * refusal happens BEFORE the attempt owns anything, so the frozen retry
 * stays eligible.
 */
internal class SearchRetryPreparationRefused(
    val userMessage: String,
) : Exception(userMessage)

/** Registration was rejected before the generation registry accepted a session. */
internal class SearchRetryRegistrationRefused : Exception("Search retry registration was refused")

/** The prepared production input of one frozen retry attempt. */
internal data class FrozenSearchRetryPreparation(
    val capture: RegenerationRequestCapture,
    val versions: ResponseVersionState,
    val scopedHistory: List<com.projectnuke.fusion.data.MessageEntity>,
)
