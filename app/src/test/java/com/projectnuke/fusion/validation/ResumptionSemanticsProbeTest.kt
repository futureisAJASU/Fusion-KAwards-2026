package com.projectnuke.fusion.validation

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.junit.Test

/**
 * P12 evidence probe: records the ACTUAL kotlinx-coroutines resumption behavior
 * this codebase depends on. Results are appended to work/p12/probe-results.txt.
 *
 * Probes answer: when a coroutine's job is cancelled while it is suspended
 * inside a (NonCancellable) dispatcher-hopting block, what happens at the exit
 * of that block on the caller side?
 *  - Does plain code AFTER the block (assignment, return) still execute?
 *  - Or does a CancellationException surface on the caller continuation?
 */
class ResumptionSemanticsProbeTest {

            private val outFile = File("work/p12/probe-results.txt")

    private fun record(line: String) {
        outFile.parentFile?.mkdirs()
        outFile.appendText(line + "\n")
    }

    /**
     * Probe 1: coordinator shape - dispatch to IO, commit, get cancelled mid-commit,
     * all under withContext(NonCancellable). Asks: does code AFTER the block
     * (assignment + return value) still run, or is it cancelled?
     */
    @Test
    fun probe1NonCancellableExitAfterMidCommitCancel() {
        repeat(20) { attempt ->
            val committed = CountDownLatch(1)
            val cancelIssued = CountDownLatch(1)
            val assigned = AtomicReference<String?>()
            val threw = AtomicBoolean(false)
            val codeAfterBlock = AtomicBoolean(false)
            runBlocking(Dispatchers.Default) {
                val job = launch(Dispatchers.Default) {
                    try {
                        val mutex = Mutex()
                        val v = mutex.withLock {
                            withContext(NonCancellable) {
                                withContext(Dispatchers.IO) {
                                    committed.countDown()
                                    cancelIssued.await(5, TimeUnit.SECONDS)
                                    "commit-value"
                                }
                            }
                        }
                        assigned.set(v)
                        codeAfterBlock.set(true)
                    } catch (c: CancellationException) {
                        threw.set(true)
                        throw c
                    }
                }
                committed.await(5, TimeUnit.SECONDS)
                job.cancel()
                cancelIssued.countDown()
                job.join()
            }
            record(
                "probe1 assigned=${assigned.get()} threw=$threw " +
                    "codeAfterBlock=${codeAfterBlock.get()}",
            )
        }
    }

    /**
     * Probe 2: plain cancellable dispatcher hop with NO NonCancellable. Cancel
     * mid-commit. Asks: is the post-block assignment suppressed (CancellationException)
     * when NonCancellable is absent?
     */
    @Test
    fun probe2PlainWithContextAfterMidCommitCancel() {
        repeat(20) { attempt ->
            val committed = CountDownLatch(1)
            val cancelIssued = CountDownLatch(1)
            val assigned = AtomicReference<String?>()
            val threw = AtomicBoolean(false)
            runBlocking(Dispatchers.Default) {
                val job = launch(Dispatchers.Default) {
                    try {
                        val v = withContext(Dispatchers.IO) {
                            committed.countDown()
                            cancelIssued.await(5, TimeUnit.SECONDS)
                            "commit-value"
                        }
                        assigned.set(v)
                    } catch (c: CancellationException) {
                        threw.set(true)
                        throw c
                    }
                }
                committed.await(5, TimeUnit.SECONDS)
                job.cancel()
                cancelIssued.countDown()
                job.join()
            }
            record(
                "probe2 assigned=${assigned.get()} threw=$threw",
            )
        }
    }

    /**
     * Probe 3: ordinary (non-cancellation) exception thrown after the commit
     * block, with the job cancelled during the hop. Asks: does the `catch (Exception)`
     * branch still run, or does cancellation swallow it?
     */
    @Test
    fun probe3OrdinaryExceptionCatchAfterCancel() {
        repeat(20) { attempt ->
            val committed = CountDownLatch(1)
            val cancelIssued = CountDownLatch(1)
            val catchRan = AtomicBoolean(false)
            val codeAfterCatch = AtomicBoolean(false)
                                    val thread = Thread {
                runBlocking(Dispatchers.Default) {
                    val job = launch(Dispatchers.Default) {
                        try {
                            withContext(Dispatchers.IO) {
                                committed.countDown()
                                cancelIssued.await(5, TimeUnit.SECONDS)
                            }
                            throw IllegalStateException("post-commit ordinary failure")
                        } catch (c: CancellationException) {
                            throw c
                        } catch (_: Exception) {
                            catchRan.set(true)
                        }
                        codeAfterCatch.set(true)
                    }
                    committed.await(5, TimeUnit.SECONDS)
                    job.cancel()
                    cancelIssued.countDown()
                    job.join()
                }
            }
            thread.join(15_000)
            record(
                "probe3 catchRan=$catchRan codeAfterCatch=$codeAfterCatch",
            )
        }
    }
}
