package com.projectnuke.fusion.chat

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationDeletionFatalFailureTest(
    private val rowInitiallyExists: Boolean,
    private val stage: String,
    private val fatal: Error,
) {
    @Test
    fun `fatal cleanup propagates without reporting recoverable debt and releases ownership`() = runTest {
        val coordinator = ConversationDeletionCoordinator()
        var rowExists = rowInitiallyExists
        var cleanupCalls = 0
        var debtCalls = 0
        val thrown = runCatching {
            coordinator.delete(
                conversationId = 17,
                cancelAndJoin = {},
                exists = { rowExists },
                commitDelete = { rowExists = false },
                settleTarget = { if (stage == "settle") throw fatal },
                cleanupDerivedData = {
                    cleanupCalls++
                    if (stage == "cleanup") throw fatal
                    if (stage.startsWith("debt")) error("ordinary cleanup failure")
                },
                recordCleanupDebt = {
                    debtCalls++
                    throw fatal
                },
                recordCleanupDebtResult = if (stage == "debt-boolean") ({
                    debtCalls++
                    throw fatal
                }) else null,
            )
        }.exceptionOrNull()

        assertSame("a fatal runtime failure is not cleanup-debt evidence", fatal, thrown)
        assertFalse("fatal cleanup must not resurrect the committed or absent row", rowExists)
        assertEquals(if (stage == "settle") 0 else 1, cleanupCalls)
        assertEquals(if (stage.startsWith("debt")) 1 else 0, debtCalls)
        assertEquals(ConversationDeletionResult.ALREADY_ABSENT_CLEAN,
            coordinator.delete(17, {}, { rowExists }, {}, {}, {}, {}))
    }

    companion object {
        // Coroutine stack-trace recovery can copy standard Error instances.
        // These fatal subclasses forbid copying so assertSame checks the actual
        // production propagation rather than the test runtime's recovery copy.
        private class FatalOom : OutOfMemoryError("cleanup OOM"), CopyableThrowable<FatalOom> {
            override fun createCopy(): FatalOom? = null
        }

        private class FatalLinkage : LinkageError("cleanup linkage"), CopyableThrowable<FatalLinkage> {
            override fun createCopy(): FatalLinkage? = null
        }

        @JvmStatic
        @Parameterized.Parameters(name = "exists={0}, stage={1}, fatal={2}")
        fun cases(): List<Array<Any>> = listOf(true, false).flatMap { exists ->
            listOf("settle", "cleanup", "debt-legacy", "debt-boolean").flatMap { stage ->
                listOf(FatalOom(), FatalLinkage()).map { fatal ->
                    arrayOf<Any>(exists, stage, fatal)
                }
            }
        }
    }
}
