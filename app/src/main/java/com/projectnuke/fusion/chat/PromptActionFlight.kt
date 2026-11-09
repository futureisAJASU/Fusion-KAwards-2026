package com.projectnuke.fusion.chat

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single-flight owner for F2 (Next Action) and F3 (Study Session) prompt
 * actions. Rapid taps must not append duplicate prompts: the first action
 * owns the flight and additional taps while active are ignored (and the
 * chips render disabled via [inFlight]). There is no queue; ownership is
 * released in `finally` after success, failure or cancellation.
 *
 * The owner is conversation/New-Chat-scoped: the UI remembers one instance
 * keyed by `(conversationId, newChatEpoch)`, so a conversation switch or a
 * same-id New Chat discards any stale flight instead of leaking it across
 * surfaces. No GlobalScope: callers run [runAction] in their own scope.
 */
internal class PromptActionFlight {
    private val active = AtomicBoolean(false)
    private val _inFlight = MutableStateFlow(false)
    val inFlight: StateFlow<Boolean> = _inFlight.asStateFlow()

    /** Acquires the flight, or returns false when another action owns it. */
    fun tryAcquire(): Boolean {
        if (!active.compareAndSet(false, true)) return false
        _inFlight.value = true
        return true
    }

    /** Releases the flight. Idempotent; safe to call without ownership. */
    fun release() {
        active.set(false)
        _inFlight.value = false
    }

    /**
     * Runs [action] iff this caller owns the flight. Returns false
     * immediately when another action is in flight (no queue, no append).
     */
    suspend fun runAction(action: suspend () -> Unit): Boolean {
        if (!tryAcquire()) return false
        try {
            action()
        } finally {
            release()
        }
        return true
    }
}
