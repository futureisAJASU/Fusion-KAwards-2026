package com.projectnuke.fusion.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

object FusionRuntimeLock {
    private val mutex = Mutex()
    private val stateLock = Any()

    private var chatReservationCount: Int = 0
    private var activeChatRequestId: String? = null
    private var benchmarkReservation: BenchmarkReservation? = null

    private class BenchmarkReservation(val runId: String, var isActive: Boolean = false)

    private val unloadCallbacks = CopyOnWriteArraySet<ChatEngineUnloadCallback>()

    fun interface ChatEngineUnloadCallback {
        fun onUnloadRequested()
    }

    sealed interface RuntimeOwnerState {
        data object Idle : RuntimeOwnerState
        data class Chat(val requestId: String) : RuntimeOwnerState
        data class ChatQueued(val count: Int) : RuntimeOwnerState
        data class Benchmark(val runId: String) : RuntimeOwnerState
        data class BenchmarkReserved(val runId: String) : RuntimeOwnerState
    }

    val isChatGenerationRunning: Boolean
        get() = synchronized(stateLock) { chatReservationCount > 0 }

    val isBenchmarkRunning: Boolean
        get() = synchronized(stateLock) { benchmarkReservation != null }

    fun ownerSnapshot(): RuntimeOwnerState = synchronized(stateLock) {
        val bm = benchmarkReservation
        if (bm != null) {
            return@synchronized if (bm.isActive) RuntimeOwnerState.Benchmark(bm.runId)
            else RuntimeOwnerState.BenchmarkReserved(bm.runId)
        }
        val active = activeChatRequestId
        if (active != null) {
            return@synchronized RuntimeOwnerState.Chat(active)
        }
        if (chatReservationCount > 0) {
            return@synchronized RuntimeOwnerState.ChatQueued(chatReservationCount)
        }
        RuntimeOwnerState.Idle
    }

    fun registerChatEngineUnloadCallback(callback: () -> Unit): () -> Unit {
        val wrapper = ChatEngineUnloadCallback { callback() }
        unloadCallbacks += wrapper
        return { unloadCallbacks -= wrapper }
    }

    fun registerChatEngineUnloadCallbackTyped(callback: ChatEngineUnloadCallback): () -> Unit {
        unloadCallbacks += callback
        return { unloadCallbacks -= callback }
    }

    fun requestChatEngineUnloadForBenchmark() {
        unloadCallbacks.forEach { runCatching { it.onUnloadRequested() } }
    }

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> withChatGeneration(
        conversationId: Long? = null,
        lifecycleRequestId: String? = null,
        block: suspend (String) -> T,
    ): T {
        // Atomic foreground admission: the background lease is taken before
        // any background owner can be admitted and is held until this whole
        // admission ends, so preemption and the foreground reservation cannot
        // be separated by a racing background acquire. A started dynamic
        // follow-up receives native cancellation, then holds the runtime mutex
        // until its terminal callback and Conversation close. Never touches
        // USER_STOP semantics.
        BackgroundRuntimeOwner.beginForegroundAdmission()
        try {
            val requestId = "chat-${UUID.randomUUID()}"
            synchronized(stateLock) {
                if (benchmarkReservation != null) throw BenchmarkRunningException()
                chatReservationCount++
            }
            try {
                return mutex.withLock {
                    synchronized(stateLock) {
                        if (benchmarkReservation != null) throw BenchmarkRunningException()
                        activeChatRequestId = requestId
                    }
                    try {
                        block(requestId)
                    } finally {
                        synchronized(stateLock) {
                            if (activeChatRequestId == requestId) {
                                activeChatRequestId = null
                            }
                        }
                    }
                }
            } finally {
                synchronized(stateLock) {
                    chatReservationCount--
                }
                if (conversationId != null) {
                    val terminationIntent = lifecycleRequestId?.let { requestId ->
                        val context = kotlin.coroutines.coroutineContext[GenerationRequestContext]
                        context?.cancellationController?.intentFor(
                            GenerationRequestKey(conversationId, requestId),
                        )
                    }
                    FusionLifecycleLog.event(
                        name = "RUNTIME_LOCK_RELEASED",
                        conversationId = conversationId,
                        requestId = lifecycleRequestId,
                        terminationIntent = terminationIntent,
                        runtimeState = FusionLifecycleLog.runtimeStateName(ownerSnapshot()),
                    )
                }
            }
        } finally {
            BackgroundRuntimeOwner.endForegroundAdmission()
        }
    }

    suspend fun <T> withExclusiveBenchmark(
        onPrepareExclusiveMode: suspend () -> Unit,
        block: suspend () -> T
    ): T {
        // Same atomic foreground rule as chat generation: the benchmark owns
        // the runtime exclusively and must never wait on background work, and
        // the reservation is held through this whole admission.
        BackgroundRuntimeOwner.beginForegroundAdmission()
        try {
            val runId = "benchmark-${UUID.randomUUID()}"
            synchronized(stateLock) {
                if (chatReservationCount > 0) throw ChatGenerationRunningException()
                if (benchmarkReservation != null) throw BenchmarkRunningException()
                benchmarkReservation = BenchmarkReservation(runId)
            }
            try {
                return mutex.withLock {
                    synchronized(stateLock) {
                        if (chatReservationCount > 0) {
                            benchmarkReservation = null
                            throw ChatGenerationRunningException()
                        }
                        if (benchmarkReservation?.runId != runId) {
                            benchmarkReservation = null
                            throw BenchmarkRunningException()
                        }
                        benchmarkReservation!!.isActive = true
                    }
                    try {
                        onPrepareExclusiveMode()
                        block()
                    } finally {
                        synchronized(stateLock) {
                            if (benchmarkReservation?.runId == runId) {
                                benchmarkReservation = null
                            }
                        }
                    }
                }
            } finally {
                synchronized(stateLock) {
                    if (benchmarkReservation?.runId == runId) {
                        benchmarkReservation = null
                    }
                }
            }
        } finally {
            BackgroundRuntimeOwner.endForegroundAdmission()
        }
    }
}

class BenchmarkRunningException : IllegalStateException("Benchmark is running")

class ChatGenerationRunningException : IllegalStateException("Chat generation is running")
