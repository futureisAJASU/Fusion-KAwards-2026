package com.projectnuke.fusion.llm

import com.google.ai.edge.litertlm.Conversation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class GenerationTerminationIntent {
    USER_STOP,
    SUPERSEDED,
    CONVERSATION_DELETE,
    OWNER_DISPOSED,
    INTERNAL_CANCEL,
}

/** Structural deletion owns the conversation lifetime. */
internal fun mayPromoteTerminationIntent(
    existing: GenerationTerminationIntent?,
    requested: GenerationTerminationIntent,
): Boolean = existing == null ||
    existing == requested ||
    requested == GenerationTerminationIntent.CONVERSATION_DELETE

data class GenerationRequestKey(
    val conversationId: Long,
    val requestId: String,
)

/**
 * Exact-request native cancellation ownership. The session registry owns one
 * instance of this controller; it is not a second generation-session registry.
 */
class NativeConversationCancellationController {
    private enum class CancelState { NOT_ISSUED, IN_FLIGHT, SUCCEEDED }

    private data class Entry(
        val owner: Any,
        val cancel: () -> Unit,
        val requireNativeStartAdmission: Boolean = false,
        var nativeStarted: Boolean = !requireNativeStartAdmission,
        var cancelPendingBeforeStart: Boolean = false,
        val cancelState: AtomicReference<CancelState> = AtomicReference(CancelState.NOT_ISSUED),
    )

    private val conversations = ConcurrentHashMap<GenerationRequestKey, Entry>()
    private val intents = ConcurrentHashMap<GenerationRequestKey, GenerationTerminationIntent>()
    private val partialOutputs = ConcurrentHashMap<GenerationRequestKey, AtomicReference<String>>()

    /** Test-only barrier for the register-vs-Stop CAS race. */
    internal var onBeforeRegistrationStopCheck: () -> Unit = {}

    /** Test-only barrier after observing an entry and before taking its lifetime lock. */
    internal var onBeforeCancellationLock: () -> Unit = {}

    fun register(key: GenerationRequestKey, conversation: Conversation) {
        conversations[key] = Entry(owner = conversation, cancel = { conversation.cancelProcess() })
        // Route Stop-before-register through the same CAS-protected controller
        // path as an ordinary cancellation. Never call Conversation directly
        // from registration, or registration and Stop can double-cancel.
        onBeforeRegistrationStopCheck()
        if (intents.containsKey(key)) {
            cancelProcess(key)
        }
    }

    /**
     * Unregisters the exact Conversation for this request.
     * Does NOT remove the termination intent - that is owned by the request lifecycle.
     */
    fun unregister(key: GenerationRequestKey, conversation: Conversation) {
        unregisterOwner(key, conversation)
        // Intent is preserved for request-terminal cleanup via clearRequest()
    }

    internal fun registerCancellationHook(
        key: GenerationRequestKey,
        owner: Any,
        requireNativeStartAdmission: Boolean = false,
        cancel: () -> Unit,
    ) {
        conversations[key] = Entry(
            owner = owner,
            cancel = cancel,
            requireNativeStartAdmission = requireNativeStartAdmission,
        )
        onBeforeRegistrationStopCheck()
        if (intents.containsKey(key)) {
            cancelProcess(key)
        }
    }

    /** Serializes native start admission against exact-request retirement. */
    internal fun startNativeIfCurrent(
        key: GenerationRequestKey,
        owner: Any,
        start: () -> Unit,
    ): Boolean {
        val entry = conversations[key] ?: return false
        synchronized(entry) {
            if (conversations[key] !== entry || entry.owner !== owner) return false
            if (intents.containsKey(key) || entry.cancelPendingBeforeStart) return false
            if (entry.nativeStarted) return false
            entry.nativeStarted = true
            try {
                start()
            } catch (failure: Throwable) {
                // Synchronous send admission failed; no async operation was
                // returned to the caller.
                entry.nativeStarted = false
                throw failure
            }
            return true
        }
    }

    internal fun unregisterCancellationHook(key: GenerationRequestKey, owner: Any) {
        unregisterOwner(key, owner)
        // Intent is preserved for request-terminal cleanup via clearRequest()
    }

    private fun unregisterOwner(key: GenerationRequestKey, owner: Any) {
        val entry = conversations[key] ?: return
        if (entry.owner !== owner) return
        // The caller closes its Conversation after this returns. Join any
        // already-admitted native cancellation before releasing that lifetime.
        synchronized(entry) {
            conversations.remove(key, entry)
        }
    }

    fun recordIntent(key: GenerationRequestKey, intent: GenerationTerminationIntent) {
        intents.putIfAbsent(key, intent)
    }

    /** Reserves or explicitly promotes the exact request's intent. */
    fun reserveIntent(key: GenerationRequestKey, intent: GenerationTerminationIntent): Boolean {
        var accepted = false
        val entry = conversations[key]
        val reserve = {
            intents.compute(key) { _, existing ->
                if (mayPromoteTerminationIntent(existing, intent)) {
                    accepted = true
                    intent
                } else {
                    existing
                }
            }
        }
        if (entry == null) reserve() else synchronized(entry) { reserve() }
        return accepted
    }

    fun intentFor(key: GenerationRequestKey): GenerationTerminationIntent? = intents[key]

    /** Returns false when no exact live Conversation exists or native cancel throws. */
    fun cancelProcess(key: GenerationRequestKey): Boolean {
        val entry = conversations[key] ?: return false
        onBeforeCancellationLock()
        synchronized(entry) {
            // Unregister may have retired this observed entry before its lock
            // was acquired. Never invoke a closed or replaced Conversation.
            if (conversations[key] !== entry) return false
            if (entry.requireNativeStartAdmission && !entry.nativeStarted) {
                entry.cancelPendingBeforeStart = true
                return true
            }
            when (entry.cancelState.get()) {
                CancelState.SUCCEEDED -> return true
                // Re-entrant native callbacks cannot issue a second cancel while
                // the first invocation is still in flight.
                CancelState.IN_FLIGHT -> return false
                CancelState.NOT_ISSUED -> entry.cancelState.set(CancelState.IN_FLIGHT)
            }
            return try {
                val succeeded = runNonFatal {
                    entry.cancel()
                    true
                } != null
                entry.cancelState.set(if (succeeded) CancelState.SUCCEEDED else CancelState.NOT_ISSUED)
                succeeded
            } catch (failure: Throwable) {
                // Fatal Errors and coroutine cancellation propagate, but never
                // leave the exact entry permanently stuck IN_FLIGHT.
                entry.cancelState.set(CancelState.NOT_ISSUED)
                throw failure
            }
        }
    }

    /**
     * Clears all request-scoped state for the given key.
     * Must be called exactly once at the request's terminal boundary.
     * Removes both the termination intent and any stale Conversation entry.
     */
    fun clearRequest(key: GenerationRequestKey) {
        conversations.remove(key)
        intents.remove(key)
        partialOutputs.remove(key)
    }

    /**
     * Returns true if USER_STOP has been recorded for this exact request.
     */
    fun hasUserStop(key: GenerationRequestKey): Boolean =
        intents[key] == GenerationTerminationIntent.USER_STOP

    /** Publishes only the exact request's latest streaming output. */
    fun publishPartial(key: GenerationRequestKey, text: String) {
        partialOutputs.computeIfAbsent(key) { AtomicReference("") }.set(text)
    }

    fun partialSnapshot(key: GenerationRequestKey): String? =
        partialOutputs[key]?.get()

    internal fun registeredRequestCount(): Int = conversations.size
    internal fun recordedIntentCount(): Int = intents.size
    internal fun partialOutputCount(): Int = partialOutputs.size
}

class GenerationRequestContext internal constructor(
    val cancellationController: NativeConversationCancellationController,
    val requestKey: GenerationRequestKey,
    internal val backgroundLifetime: BackgroundNativeConversationLifetime? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GenerationRequestContext>
}
