package com.projectnuke.fusion.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.rules.ExternalResource
import java.util.concurrent.locks.ReentrantLock

/**
 * Owns the process-global test Main dispatcher for one complete test method.
 *
 * The lock intentionally spans the test body, not just set/reset. This keeps
 * tests that use Dispatchers.Main from racing one another when the Gradle
 * runner reuses a JVM. Teardown drains scheduled work before Main is reset.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherIsolationRule(
    private val dispatcherFactory: (TestCoroutineScheduler) -> TestDispatcher =
        ::StandardTestDispatcher,
) : ExternalResource() {
    private companion object {
        val mainLock = ReentrantLock(true)
    }

    lateinit var dispatcher: TestDispatcher
        private set

    private lateinit var scheduler: TestCoroutineScheduler

    override fun before() {
        mainLock.lock()
        try {
            scheduler = TestCoroutineScheduler()
            dispatcher = dispatcherFactory(scheduler)
            Dispatchers.setMain(dispatcher)
        } catch (failure: Throwable) {
            mainLock.unlock()
            throw failure
        }
    }

    override fun after() {
        try {
            scheduler.advanceUntilIdle()
            check(ManualCompactionOperationRegistry.residualOwnerCountForTest() == 0) {
                "test leaked ManualCompactionOperationRegistry ownership"
            }
            check(ConversationStructuralMutationRegistry.residualOwnerCountForTest() == 0) {
                "test leaked ConversationStructuralMutationRegistry ownership"
            }
        } finally {
            try {
                Dispatchers.resetMain()
            } finally {
                mainLock.unlock()
            }
        }
    }
}

/** Cancel a test-owned ViewModel and drain its Main-owned work. */
internal fun ChatViewModel.cancelForTest(scheduler: TestCoroutineScheduler) {
    val job = scope.coroutineContext[Job] ?: return
    job.cancel()
    scheduler.advanceUntilIdle()
    runTest(StandardTestDispatcher(scheduler)) {
        job.join()
    }
    scheduler.advanceUntilIdle()
    check(job.isCompleted) { "test-owned ChatViewModel scope did not reach terminal completion" }
    check(ManualCompactionOperationRegistry.residualOwnerCountForTest() == 0) {
        "test leaked ManualCompactionOperationRegistry ownership"
    }
    check(ConversationStructuralMutationRegistry.residualOwnerCountForTest() == 0) {
        "test leaked ConversationStructuralMutationRegistry ownership"
    }
}
