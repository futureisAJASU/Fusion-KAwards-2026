package com.projectnuke.fusion.llm

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

/** Callback contract shared by foreground and background native Conversation owners. */
internal interface NativeTerminalCallback<T> {
    fun onMessage(message: T)
    fun onDone()
    fun onError(failure: Throwable)
}

internal object NativeConversationLifecycleDiagnostics {
    private const val TAG = "FusionNativeLifetime"
    private val nextId = AtomicLong()

    fun nextConversationId(): Long = nextId.incrementAndGet()

    fun foregroundEvent(
        event: String,
        requestKey: GenerationRequestKey?,
        nativeConversationId: Long,
        runtimeProfileIdentity: String,
        extra: String = "",
    ) {
        val requestId = requestKey?.requestId ?: "unscoped"
        val conversationId = requestKey?.conversationId ?: -1L
        val suffix = if (extra.isBlank()) "" else " $extra"
        Log.i(
            TAG,
            "$event owner_kind=FOREGROUND request_id=$requestId conversation_id=$conversationId " +
                "native_conversation_id=$nativeConversationId runtime_profile_identity=$runtimeProfileIdentity$suffix",
        )
    }
}

/**
 * Collects a callback-backed native send through its terminal callback.
 * Caller cancellation cannot cancel this collector; callers must request native
 * cancellation separately and close the Conversation only after this returns.
 */
internal suspend fun <T> collectNativeCallbackUntilTerminal(
    startNative: (NativeTerminalCallback<T>) -> Unit,
    beginNative: ((() -> Unit) -> Boolean) = { start -> start(); true },
    onStarted: () -> Unit = {},
    onMessage: (T) -> Unit,
    onTerminal: (Throwable?) -> Unit,
    onConsumerFailure: (Throwable) -> Unit,
): Boolean = withContext(NonCancellable) {
    val channel = Channel<T>(Channel.UNLIMITED)
    val terminalDelivered = AtomicBoolean(false)
    var consumerFailure: Throwable? = null
    var streamFailure: Throwable? = null

    fun finish(failure: Throwable?) {
        if (terminalDelivered.compareAndSet(false, true)) {
            onTerminal(failure)
            channel.close(failure)
        }
    }

    val callback = object : NativeTerminalCallback<T> {
        override fun onMessage(message: T) {
            channel.trySend(message)
        }

        override fun onDone() = finish(null)

        override fun onError(failure: Throwable) = finish(failure)
    }

    var started = false
    try {
        started = beginNative {
            onStarted()
            try {
                startNative(callback)
            } catch (failure: Throwable) {
                // The pinned LiteRT API throws synchronously when an async send
                // cannot be admitted; it has not returned an active callback
                // operation in that case. Match the background lifetime seam.
                finish(failure)
                throw failure
            }
        }
    } catch (failure: Throwable) {
        streamFailure = failure
    }

    if (!started) {
        channel.cancel()
        streamFailure?.let { throw it }
        return@withContext false
    }

    try {
        for (message in channel) {
            if (consumerFailure == null) {
                try {
                    onMessage(message)
                } catch (failure: Throwable) {
                    consumerFailure = failure
                    try {
                        onConsumerFailure(failure)
                    } catch (cancelFailure: Throwable) {
                        failure.addSuppressed(cancelFailure)
                    }
                }
            }
        }
    } catch (failure: Throwable) {
        streamFailure = failure
    } finally {
        channel.cancel()
    }

    consumerFailure?.let { throw it }
    streamFailure?.let { throw it }
    check(terminalDelivered.get()) { "Native callback collector ended before terminal callback" }
    true
}
