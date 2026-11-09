package com.projectnuke.fusion.llm

import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

internal enum class BackgroundTerminationSource {
    OWNERSHIP_STALE,
    NEW_CHAT,
    CONVERSATION_SWITCH,
    SURFACE_INACTIVE,
    FOREGROUND_PREEMPTION,
    TIMEOUT,
    APP_SHUTDOWN,
    OWNER_DISPOSED,
    OUTPUT_LIMIT,
    CALLBACK_FAILURE,
    NORMAL_COMPLETION,
    GENERATION_FAILURE,
}

internal enum class BackgroundRetirementAction {
    CANCEL_JOB,
    WAIT_FOR_NATIVE_TERMINAL,
}

internal enum class NativeConversationLifetimeState {
    RESERVED,
    WAITING_FOR_RUNTIME,
    NATIVE_CONVERSATION_CREATED,
    NATIVE_STARTED,
    RETIRE_REQUESTED,
    NATIVE_TERMINAL,
    CLOSED,
}

/**
 * Dynamic follow-up's native Conversation lease. UI ownership can retire the
 * result independently; only explicit foreground, timeout, or output-limit
 * retirement asks LiteRT to cancel. Every started stream is drained to its
 * callback terminal event before the owner closes the Conversation.
 */
internal class BackgroundNativeConversationLifetime(
    private val ticketId: String,
    private val conversationId: Long,
    private val ownershipGeneration: Long,
    private val runtimeProfileIdentity: String,
    private val timeoutMs: Long,
    private val eventSink: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val lock = Any()
    private val nativeConversationId = nextNativeConversationId.incrementAndGet()
    private var state = NativeConversationLifetimeState.RESERVED
    private var cancelNative: (() -> Unit)? = null
    private var nativeStarted = false
    private var flowTerminal = false
    private var closeStarted = false
    private var closeEnded = false
    private var nativeCancelRequested = false
    private var startNanos = 0L
    private var outputChars = 0
    private val retirementSources = linkedSetOf<BackgroundTerminationSource>()

    val currentState: NativeConversationLifetimeState
        get() = synchronized(lock) { state }

    val requiresExclusiveRuntime: Boolean
        get() = synchronized(lock) {
            state != NativeConversationLifetimeState.RESERVED &&
                state != NativeConversationLifetimeState.CLOSED
        }

    val hasNativeUseInFlight: Boolean
        get() = synchronized(lock) { nativeStarted && !flowTerminal && !closeStarted }

    val isRetired: Boolean
        get() = synchronized(lock) { retirementSources.isNotEmpty() }

    fun wasRetiredFor(source: BackgroundTerminationSource): Boolean = synchronized(lock) {
        source in retirementSources
    }

    fun primaryTerminationSource(): BackgroundTerminationSource? = synchronized(lock) {
        when {
            BackgroundTerminationSource.FOREGROUND_PREEMPTION in retirementSources ->
                BackgroundTerminationSource.FOREGROUND_PREEMPTION
            BackgroundTerminationSource.TIMEOUT in retirementSources -> BackgroundTerminationSource.TIMEOUT
            BackgroundTerminationSource.OUTPUT_LIMIT in retirementSources -> BackgroundTerminationSource.OUTPUT_LIMIT
            else -> retirementSources.firstOrNull()
        }
    }

    fun markWaitingForRuntime() = synchronized(lock) {
        if (state == NativeConversationLifetimeState.RESERVED) {
            state = NativeConversationLifetimeState.WAITING_FOR_RUNTIME
        }
    }

    /** Called immediately after createConversation(), before any async send. */
    fun nativeConversationCreated(cancel: () -> Unit) = synchronized(lock) {
        check(!closeStarted) { "Conversation cannot be created after close begins" }
        check(this.cancelNative == null) { "Ticket may own only one native Conversation" }
        this.cancelNative = cancel
        if (state != NativeConversationLifetimeState.RETIRE_REQUESTED) {
            state = NativeConversationLifetimeState.NATIVE_CONVERSATION_CREATED
        }
        emitLocked("NATIVE_CONVERSATION_CREATED")
    }

    /**
     * Starts the native callback API under the same gate used by retirement.
     * Holding the short-lived gate through LiteRT's async-start call ensures a
     * foreground preemption cannot cancel before send starts and then miss it.
     */
    fun beginNativeGeneration(callerJobActive: () -> Boolean, start: () -> Unit): Boolean =
        synchronized(lock) {
            if (state == NativeConversationLifetimeState.RETIRE_REQUESTED ||
                state == NativeConversationLifetimeState.CLOSED || flowTerminal
            ) {
                return@synchronized false
            }
            check(state == NativeConversationLifetimeState.NATIVE_CONVERSATION_CREATED) {
                "Native generation requires a created Conversation"
            }
            if (!callerJobActive()) {
                requestRetirementLocked(BackgroundTerminationSource.OWNER_DISPOSED)
                return@synchronized false
            }
            check(!nativeStarted) { "Ticket cannot start multiple native generations" }
            nativeStarted = true
            state = NativeConversationLifetimeState.NATIVE_STARTED
            startNanos = System.nanoTime()
            emitLocked("NATIVE_GENERATION_BEGIN")
            try {
                start()
            } catch (failure: Throwable) {
                markFlowTerminalLocked()
                throw failure
            }
            true
        }

    /** Requests retirement. Started native work is never joined by Job.cancel(). */
    fun requestRetirement(source: BackgroundTerminationSource): BackgroundRetirementAction =
        synchronized(lock) {
            if (source != BackgroundTerminationSource.NORMAL_COMPLETION) {
                requestRetirementLocked(source)
            }
            if (nativeStarted && !flowTerminal) {
                if (source.requestsNativeCancellation() && !nativeCancelRequested) {
                    nativeCancelRequested = true
                    emitLocked("NATIVE_CANCEL_REQUESTED", "source=${source.name}")
                    try {
                        cancelNative?.invoke()
                    } catch (failure: Exception) {
                        emitLocked("NATIVE_CANCEL_FAILED", "source=${source.name} error=${failure.javaClass.simpleName}")
                    }
                }
                BackgroundRetirementAction.WAIT_FOR_NATIVE_TERMINAL
            } else {
                BackgroundRetirementAction.CANCEL_JOB
            }
        }

    /** Called only by LiteRT MessageCallback.onDone/onError. */
    fun flowTerminal() = synchronized(lock) {
        check(nativeStarted) { "Flow cannot terminate before native generation starts" }
        markFlowTerminalLocked()
    }

    fun recordOutputChars(value: Int) = synchronized(lock) {
        outputChars = value.coerceAtLeast(outputChars)
    }

    /** Logs shape-only parse metrics for output-budget tuning, never text. */
    fun recordParseResult(rawChars: Int, parsedCount: Int, supportedCount: Int, supportedChars: Int) =
        synchronized(lock) {
            emitLocked(
                "DYNAMIC_FOLLOWUP_PARSE_RESULT",
                "raw_chars=${rawChars.coerceAtLeast(0)} parsed_count=${parsedCount.coerceAtLeast(0)} " +
                    "supported_count=${supportedCount.coerceAtLeast(0)} " +
                    "supported_chars=${supportedChars.coerceAtLeast(0)} " +
                    "parse_success=${parsedCount > 0} supported_success=${supportedCount > 0}",
            )
        }

    /** Called inside Conversation.use's body, immediately before use closes it. */
    fun conversationCloseBegin() = synchronized(lock) {
        check(!closeStarted) { "Conversation close already began" }
        check(!nativeStarted || flowTerminal) {
            "Conversation.close() cannot precede the native Flow terminal callback"
        }
        closeStarted = true
        state = NativeConversationLifetimeState.CLOSED
        emitLocked("CONVERSATION_CLOSE_BEGIN")
    }

    /** Performs the actual close only after the native terminal barrier passes. */
    fun closeConversation(close: () -> Unit) {
        conversationCloseBegin()
        try {
            close()
        } finally {
            conversationCloseEnd()
        }
    }

    /** Called after Conversation.use has returned or thrown from close(). */
    fun conversationCloseEnd() = synchronized(lock) {
        check(closeStarted) { "Conversation close end without close begin" }
        if (!closeEnded) {
            closeEnded = true
            emitLocked("CONVERSATION_CLOSE_END")
        }
    }

    /** Called from BackgroundRuntimeOwner.release() after engine.generate returns. */
    fun backgroundRelease() = synchronized(lock) {
        if (!nativeStarted && !closeStarted) {
            state = NativeConversationLifetimeState.CLOSED
        }
        check(state == NativeConversationLifetimeState.CLOSED) {
            "Background ticket released before native Conversation retirement completed"
        }
        emitLocked("BACKGROUND_RELEASE", "source=${primaryTerminationSourceLocked()?.name ?: "NORMAL_COMPLETION"}")
    }

    /**
     * Uses LiteRT's callback API instead of its Flow adapter. In 0.14.0 the
     * Flow adapter's awaitClose has no native cancellation handler; this
     * collector keeps callback ownership alive until onDone/onError.
     */
    suspend fun collectNativeMessages(
        conversation: Conversation,
        message: String,
        callerJob: Job?,
        onMessage: (Message) -> Unit,
    ): Boolean = coroutineScope {
        var timeoutJob: Job? = null
        try {
            collectNativeCallbackUntilTerminal(
                startNative = { callback ->
                    conversation.sendMessageAsync(message, object : MessageCallback {
                        override fun onMessage(message: Message) = callback.onMessage(message)
                        override fun onDone() = callback.onDone()
                        override fun onError(throwable: Throwable) = callback.onError(throwable)
                    })
                },
                beginNative = { start ->
                    beginNativeGeneration(
                        callerJobActive = { callerJob?.isActive != false },
                        start = start,
                    )
                },
                onStarted = {
                    if (timeoutMs > 0L) {
                        emit("TIMEOUT_ARMED", "timeout_ms=$timeoutMs")
                        timeoutJob = launch {
                            delay(timeoutMs)
                            emit("TIMEOUT_FIRED", "timeout_ms=$timeoutMs")
                            requestRetirement(BackgroundTerminationSource.TIMEOUT)
                        }
                    }
                },
                onMessage = onMessage,
                onTerminal = { flowTerminal() },
                onConsumerFailure = {
                    requestRetirement(BackgroundTerminationSource.CALLBACK_FAILURE)
                },
            )
        } finally {
            timeoutJob?.cancelAndJoin()
        }
    }

    private fun requestRetirementLocked(source: BackgroundTerminationSource) {
        if (source !in retirementSources) {
            retirementSources += source
            if (state != NativeConversationLifetimeState.CLOSED && !flowTerminal) {
                state = NativeConversationLifetimeState.RETIRE_REQUESTED
            }
            emitLocked("RETIRE_REQUESTED", "source=${source.name}")
        }
    }

    private fun markFlowTerminalLocked() {
        if (!flowTerminal) {
            flowTerminal = true
            if (state != NativeConversationLifetimeState.CLOSED) {
                state = NativeConversationLifetimeState.NATIVE_TERMINAL
            }
            val elapsedMs = if (startNanos == 0L) 0L else (System.nanoTime() - startNanos) / 1_000_000L
            emitLocked("FLOW_TERMINAL", "elapsed_ms=$elapsedMs output_chars=$outputChars")
        }
    }

    private fun primaryTerminationSourceLocked(): BackgroundTerminationSource? = when {
        BackgroundTerminationSource.FOREGROUND_PREEMPTION in retirementSources ->
            BackgroundTerminationSource.FOREGROUND_PREEMPTION
        BackgroundTerminationSource.TIMEOUT in retirementSources -> BackgroundTerminationSource.TIMEOUT
        BackgroundTerminationSource.OUTPUT_LIMIT in retirementSources -> BackgroundTerminationSource.OUTPUT_LIMIT
        else -> retirementSources.firstOrNull()
    }

    private fun emitLocked(event: String, extra: String = "") {
        val suffix = if (extra.isBlank()) "" else " $extra"
        eventSink(
            "$event owner_kind=DYNAMIC_FOLLOWUP ticket_id=$ticketId conversation_id=$conversationId native_conversation_id=$nativeConversationId " +
                "ownership_generation=$ownershipGeneration runtime_profile_identity=$runtimeProfileIdentity$suffix",
        )
    }

    private fun emit(event: String, extra: String = "") = synchronized(lock) {
        emitLocked(event, extra)
    }

    private fun BackgroundTerminationSource.requestsNativeCancellation(): Boolean = when (this) {
        BackgroundTerminationSource.FOREGROUND_PREEMPTION,
        BackgroundTerminationSource.TIMEOUT,
        BackgroundTerminationSource.OUTPUT_LIMIT,
        BackgroundTerminationSource.CALLBACK_FAILURE,
        -> true
        else -> false
    }

    companion object {
        private const val TAG = "FusionFollowUpNative"
        private val nextNativeConversationId = AtomicLong()
    }
}
