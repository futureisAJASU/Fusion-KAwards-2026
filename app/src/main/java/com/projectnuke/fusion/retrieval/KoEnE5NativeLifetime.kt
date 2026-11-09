package com.projectnuke.fusion.retrieval

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The native resources which must be released as one unit. The session's
 * exact SessionOptions instance is held here so their lifetime cannot drift
 * apart after publication.
 */
internal class KoEnE5NativeResourceBundle<S : Any, O : Any>(
    val session: S,
    val sessionOptions: O,
    private val closeSession: (S) -> Unit,
    private val closeSessionOptions: (O) -> Unit,
) : AutoCloseable {

    private val closeLock = Any()
    private var closed = false

    /** Idempotent and deliberately ordered: session first, options second. */
    override fun close() {
        synchronized(closeLock) {
            if (closed) return
            closed = true
        }
        var failure: Throwable? = null
        try {
            closeSession(session)
        } catch (error: Throwable) {
            failure = error
        }
        try {
            closeSessionOptions(sessionOptions)
        } catch (error: Throwable) {
            val primary = failure
            if (primary == null) failure = error
            else if (primary !== error) primary.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}

/**
 * Acquires closeable resources in order and releases only acquired resources
 * in deterministic reverse order. The primary failure keeps precedence while
 * every cleanup failure remains observable as a suppressed exception.
 */
internal fun <A : AutoCloseable, B : AutoCloseable, C : AutoCloseable, R>
    withSequentialCloseableResources(
        acquire1: () -> A,
        acquire2: () -> B,
        acquire3: () -> C,
        block: (A, B, C) -> R,
    ): R {
    var first: A? = null
    var second: B? = null
    var third: C? = null
    val outcome = try {
        first = acquire1()
        second = acquire2()
        third = acquire3()
        Result.success(block(checkNotNull(first), checkNotNull(second), checkNotNull(third)))
    } catch (failure: Throwable) {
        Result.failure(failure)
    }
    var cleanupFailure: Throwable? = null
    fun close(resource: AutoCloseable?) {
        if (resource == null) return
        try {
            resource.close()
        } catch (failure: Throwable) {
            val firstFailure = cleanupFailure
            if (firstFailure == null) cleanupFailure = failure
            else if (firstFailure !== failure) firstFailure.addSuppressed(failure)
        }
    }
    close(third)
    close(second)
    close(first)
    val primary = outcome.exceptionOrNull()
    if (primary != null) {
        cleanupFailure?.let { if (primary !== it) primary.addSuppressed(it) }
        throw primary
    }
    cleanupFailure?.let { throw it }
    return outcome.getOrThrow()

}

/**
 * Creates a resource bundle while retaining cleanup ownership on every
 * failure path. This small seam is also usable by host tests without loading
 * ONNX Runtime native classes.
 */
internal fun <S : Any, O : Any> createKoEnE5NativeResourceBundle(
    sessionOptions: O,
    createSession: () -> S,
    closeSession: (S) -> Unit,
    closeSessionOptions: (O) -> Unit,
): KoEnE5NativeResourceBundle<S, O> {
    val session: S
    try {
        session = createSession()
    } catch (failure: Throwable) {
        try {
            closeSessionOptions(sessionOptions)
        } catch (cleanup: Throwable) {
            if (failure !== cleanup) failure.addSuppressed(cleanup)
        }
        throw failure
    }
    return try {
        KoEnE5NativeResourceBundle<S, O>(session, sessionOptions, closeSession, closeSessionOptions)
    } catch (failure: Throwable) {
        try {
            closeSession(session)
        } catch (cleanup: Throwable) {
            if (failure !== cleanup) failure.addSuppressed(cleanup)
        }
        try {
            closeSessionOptions(sessionOptions)
        } catch (cleanup: Throwable) {
            if (failure !== cleanup) failure.addSuppressed(cleanup)
        }
        throw failure
    }
}

/**
 * Owns one live native resource bundle and one active inference lease.
 *
 * [close] never waits for [inferenceMutex]. It marks the lifetime closed under
 * a short JVM monitor, immediately rejects new leases, and releases the
 * detached bundle after the final lease exits. The suspend gate is only used
 * by inference callers, so main-thread lifecycle callbacks never block on it.
 */
internal class KoEnE5NativeLifetime<S : Any, O : Any> {

    private val stateLock = Any()
    private val inferenceMutex = Mutex()
    private var resources: KoEnE5NativeResourceBundle<S, O>? = null
    private var activeLease: Lease<S, O>? = null
    private var closeRequested = false
    private var closed = false

    val isClosed: Boolean
        get() = synchronized(stateLock) { closed }

    val hasLiveResources: Boolean
        get() = synchronized(stateLock) { resources != null && !closed }

    /** Publishes only a complete pair; rejected candidates are closed here. */
    fun publish(candidate: KoEnE5NativeResourceBundle<S, O>): Boolean {
        val accepted = synchronized(stateLock) {
            if (closed || closeRequested || resources != null) {
                false
            } else {
                resources = candidate
                true
            }
        }
        if (!accepted) candidate.close()
        return accepted
    }

    /**
     * Runs one cancellable, serialized native inference. A null result means
     * that the engine was closed or never published a complete resource pair.
     */
    suspend fun <T> withInference(block: suspend (S) -> T): T? =
        inferenceMutex.withLock {
            val lease = synchronized(stateLock) {
                val current = resources
                if (closed || closeRequested || current == null || activeLease != null) {
                    null
                } else {
                    Lease(current).also { activeLease = it }
                }
            } ?: return@withLock null

            val outcome = try {
                Result.success(block(lease.resources.session))
            } catch (primary: Throwable) {
                Result.failure(primary)
            }
            try {
                release(lease)
            } catch (cleanup: Throwable) {
                val primary = outcome.exceptionOrNull()
                if (primary == null) {
                    throw cleanup
                }
                if (primary !== cleanup) {
                    primary.addSuppressed(cleanup)
                }
            }
            outcome.getOrThrow()
        }

    /** Non-blocking, idempotent release request. */
    fun close() {
        val detached = synchronized(stateLock) {
            if (closeRequested) {
                null
            } else {
                closeRequested = true
                closed = true
                if (activeLease == null) resources.also { resources = null } else null
            }
        }
        detached?.close()
    }

    private fun release(lease: Lease<S, O>) {
        val detached = synchronized(stateLock) {
            if (activeLease !== lease) return
            activeLease = null
            if (closeRequested) resources.also { resources = null } else null
        }
        detached?.close()
    }

    private class Lease<S : Any, O : Any>(
        val resources: KoEnE5NativeResourceBundle<S, O>,
    )
}
