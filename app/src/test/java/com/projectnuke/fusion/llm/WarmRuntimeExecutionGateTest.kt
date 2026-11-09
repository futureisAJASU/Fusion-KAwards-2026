package com.projectnuke.fusion.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmRuntimeExecutionGateTest {
    @Test
    fun `profile changed while waiting for runtime lock skips generation`() = runBlocking {
        var loadedProfile = "accepted-safe-profile"
        var generationInvocations = 0
        var replacementInvocations = 0
        assertTrue(loadedProfile == "accepted-safe-profile") // earlier optimistic warm check

        val outcome = executeOnlyWithReusableRuntime(
            withRuntimeLock = { criticalSection ->
                // Models a foreground/profile owner winning the mutex after an
                // earlier optimistic warm check and before this background task.
                loadedProfile = "new-foreground-profile"
                criticalSection()
            },
            isRuntimeReusable = { loadedProfile == "accepted-safe-profile" },
            generate = {
                generationInvocations++
                replacementInvocations++ // engine acquisition would replace this stale profile
                "unexpected"
            },
        )

        assertEquals(WarmRuntimeExecution.NotReusable, outcome)
        assertEquals(0, generationInvocations)
        assertEquals(0, replacementInvocations)
    }

    @Test
    fun `matching loaded profile permits bounded background generation`() = runBlocking {
        var generationInvocations = 0

        val outcome = executeOnlyWithReusableRuntime(
            withRuntimeLock = { criticalSection -> criticalSection() },
            isRuntimeReusable = { true },
            generate = {
                generationInvocations++
                "questions"
            },
        )

        assertEquals(WarmRuntimeExecution.Executed("questions"), outcome)
        assertEquals(1, generationInvocations)
    }
}
