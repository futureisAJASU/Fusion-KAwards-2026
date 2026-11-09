package com.projectnuke.fusion.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private enum class S { OPEN, FINISHING, PUBLISHING, ABORTING, FINISHED, ABORTED }

class TokenCoalescer(
    scope: CoroutineScope,
    private val publishIntervalMs: Long = 30L,
    private val shouldPublish: () -> Boolean = { true },
    private val onPublish: (String) -> Unit = {},
) {
    private val buffer = StringBuilder()
    private val tick = Channel<Unit>(Channel.CONFLATED)
    private val consumerJob: Job
    private val lock = Any()
    private val publicationGate = Mutex()
    private var state = S.OPEN
    private val terminalCompleted = CompletableDeferred<Unit>()
    @Volatile private var finalizedText = ""

    init {
        consumerJob = scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            while (true) {
                if (tick.receiveCatching().isClosed) break
                val snapshot = synchronized(lock) {
                    if (state != S.OPEN) null else buffer.toString()
                } ?: break
                publicationGate.withLock {
                    val publish = synchronized(lock) { state == S.OPEN } && shouldPublish()
                    if (publish) onPublish(snapshot)
                }
                delay(publishIntervalMs)
            }
        }
    }

    fun append(token: String) {
        if (token.isEmpty()) return
        synchronized(lock) {
            if (state != S.OPEN) return
            buffer.append(token)
        }
        tick.trySend(Unit)
    }

    suspend fun finish(): String {
        val primary = synchronized(lock) {
            when (state) {
                S.OPEN -> { state = S.FINISHING; true }
                S.FINISHED, S.ABORTED -> false
                else -> false
            }
        }
        if (!primary) {
            withContext(NonCancellable) { terminalCompleted.await() }
            return finalizedText
        }

        try {
            tick.close()
            consumerJob.join()
            var callbackFailure: Throwable? = null
            publicationGate.withLock {
                val publish = synchronized(lock) {
                    if (state != S.FINISHING) false else {
                        finalizedText = buffer.toString()
                        state = S.PUBLISHING
                        true
                    }
                }
                if (publish) {
                    try {
                        if (shouldPublish()) onPublish(finalizedText)
                    } catch (failure: Throwable) {
                        callbackFailure = failure
                    } finally {
                        synchronized(lock) { if (state == S.PUBLISHING) state = S.FINISHED }
                        terminalCompleted.complete(Unit)
                    }
                }
            }
            if (callbackFailure != null) throw callbackFailure!!
            if (synchronized(lock) { state == S.ABORTING }) {
                withContext(NonCancellable) { terminalCompleted.await() }
            }
            return finalizedText
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            val ownsAbort = synchronized(lock) {
                when (state) {
                    S.FINISHING, S.PUBLISHING -> { state = S.ABORTING; true }
                    else -> false
                }
            }
            if (ownsAbort) abortCleanup()
            else withContext(NonCancellable) { terminalCompleted.await() }
            throw cancelled
        }
    }

    suspend fun abort() {
        val ownsAbort = synchronized(lock) {
            when (state) {
                S.OPEN, S.FINISHING -> { state = S.ABORTING; true }
                else -> false
            }
        }
        if (ownsAbort) abortCleanup()
        else withContext(NonCancellable) { terminalCompleted.await() }
    }

    private suspend fun abortCleanup() = withContext(NonCancellable) {
        tick.close()
        consumerJob.cancelAndJoin()
        publicationGate.withLock { }
        synchronized(lock) {
            finalizedText = buffer.toString()
            state = S.ABORTED
        }
        terminalCompleted.complete(Unit)
    }
}
