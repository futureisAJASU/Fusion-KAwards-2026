package com.projectnuke.fusion.llm

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job

/** Low-level foreground Conversation ownership; it has no UI or persistence policy. */
internal class NativeConversationRequestLifetime(
    private val conversation: Conversation,
    private val requestContext: GenerationRequestContext?,
    private val callerJob: Job?,
    private val runtimeProfileIdentity: String,
) {
    private enum class State { CREATED, STARTED, TERMINAL, CLOSED }

    private val lock = Any()
    private val nativeConversationId = NativeConversationLifecycleDiagnostics.nextConversationId()
    private val key = requestContext?.requestKey
    private val controller = requestContext?.cancellationController
    private val cancellationSource = AtomicReference<String?>(null)
    private var state = State.CREATED

    val nativeTerminalObserved: Boolean
        get() = synchronized(lock) { state == State.TERMINAL || state == State.CLOSED }

    init {
        emit("NATIVE_CONVERSATION_CREATED")
    }

    fun registerCancellation() {
        val requestKey = key ?: return
        val cancellationController = controller ?: return
        cancellationController.registerCancellationHook(
            key = requestKey,
            owner = conversation,
            requireNativeStartAdmission = true,
        ) {
            val source = cancellationSource.get()
                ?: cancellationController.intentFor(requestKey)?.name
                ?: "UNKNOWN"
            emit("NATIVE_CANCEL_REQUESTED", "source=$source")
            conversation.cancelProcess()
        }
    }

    /** Serializes start with controller retirement, then invokes LiteRT's async-start API. */
    fun beginNative(start: () -> Unit): Boolean {
        if (callerJob?.isActive == false) {
            requestRetirement("OWNER_DISPOSED", requestNativeCancel = false)
            return false
        }
        val begin: () -> Unit = {
            synchronized(lock) {
                check(state == State.CREATED) { "Native Conversation start is not admissible" }
                state = State.STARTED
                emit("NATIVE_GENERATION_BEGIN")
                try {
                    start()
                } catch (failure: Throwable) {
                    state = State.TERMINAL
                    emit("FLOW_TERMINAL", "start_error=${failure.javaClass.simpleName}")
                    throw failure
                }
            }
        }
        return if (key != null && controller != null) {
            controller.startNativeIfCurrent(key, conversation, begin)
        } else {
            begin()
            true
        }
    }

    suspend fun collect(
        startNative: (NativeTerminalCallback<Message>) -> Unit,
        onMessage: (Message) -> Unit,
    ): Boolean = collectNativeCallbackUntilTerminal(
        startNative = startNative,
        beginNative = ::beginNative,
        onMessage = onMessage,
        onTerminal = { failure -> flowTerminal(failure) },
        onConsumerFailure = { requestRetirement("CALLBACK_FAILURE") },
    )

    fun flowTerminal(failure: Throwable? = null) = synchronized(lock) {
        if (state == State.STARTED) {
            state = State.TERMINAL
            val detail = failure?.let { "terminal_error=${it.javaClass.simpleName}" }.orEmpty()
            emit("FLOW_TERMINAL", detail)
        }
    }

    fun requestRetirement(source: String, requestNativeCancel: Boolean = true) {
        cancellationSource.set(source)
        emit("RETIRE_REQUESTED", "source=$source")
        val started = synchronized(lock) { state == State.STARTED }
        if (!started) return
        if (requestNativeCancel) {
            val requestKey = key
            if (controller != null && requestKey != null) {
                controller.cancelProcess(requestKey)
            } else {
                try {
                    conversation.cancelProcess()
                    emit("NATIVE_CANCEL_REQUESTED", "source=$source")
                } catch (failure: Exception) {
                    emit("NATIVE_CANCEL_FAILED", "source=$source error=${failure.javaClass.simpleName}")
                }
            }
        }
    }

    fun closeConversation(close: () -> Unit) {
        synchronized(lock) {
            if (state == State.STARTED) {
                emit("CLOSE_BEFORE_TERMINAL_VIOLATION")
                error("Conversation.close() cannot precede the native terminal callback")
            }
            check(state == State.CREATED || state == State.TERMINAL)
            state = State.CLOSED
        }
        emit("CLOSE_BEGIN")
        try {
            if (key != null && controller != null) {
                controller.unregisterCancellationHook(key, conversation)
            }
            close()
        } finally {
            emit("CLOSE_END")
            emit("RELEASE")
        }
    }

    private fun emit(event: String, extra: String = "") =
        NativeConversationLifecycleDiagnostics.foregroundEvent(
            event = event,
            requestKey = key,
            nativeConversationId = nativeConversationId,
            runtimeProfileIdentity = runtimeProfileIdentity,
            extra = extra,
        )
}
