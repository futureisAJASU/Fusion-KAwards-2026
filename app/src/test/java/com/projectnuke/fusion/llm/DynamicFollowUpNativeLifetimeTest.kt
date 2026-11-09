package com.projectnuke.fusion.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DynamicFollowUpNativeLifetimeTest {
    private fun lifetime(events: MutableList<String> = mutableListOf()) =
        BackgroundNativeConversationLifetime(
            ticketId = "followup-1",
            conversationId = 8L,
            ownershipGeneration = 4L,
            runtimeProfileIdentity = "gemma4:GPU:kv4096:mtp=false",
            timeoutMs = 20_000L,
            eventSink = events::add,
        )

    private fun attachAndStart(lifetime: BackgroundNativeConversationLifetime) {
        lifetime.markWaitingForRuntime()
        lifetime.nativeConversationCreated { }
        assertTrue(lifetime.beginNativeGeneration(callerJobActive = { true }) { })
    }

    @Test
    fun `normal completion reaches callback terminal before Conversation close`() {
        val events = mutableListOf<String>()
        val lifetime = lifetime(events)
        attachAndStart(lifetime)

        lifetime.recordOutputChars(91)
        lifetime.flowTerminal()
        lifetime.conversationCloseBegin()
        lifetime.conversationCloseEnd()
        lifetime.backgroundRelease()

        assertEventOrder(events, "NATIVE_GENERATION_BEGIN", "FLOW_TERMINAL", "CONVERSATION_CLOSE_BEGIN", "CONVERSATION_CLOSE_END", "BACKGROUND_RELEASE")
        assertEquals(NativeConversationLifetimeState.CLOSED, lifetime.currentState)
    }

    @Test
    fun `guarded close does not invoke native close before terminal callback`() {
        val lifetime = lifetime()
        attachAndStart(lifetime)
        var closeCalls = 0

        try {
            lifetime.closeConversation { closeCalls++ }
            fail("native Conversation.close must wait for callback terminal")
        } catch (_: IllegalStateException) {
            // The close action must remain uncalled while native work is active.
        }
        assertEquals(0, closeCalls)

        lifetime.flowTerminal()
        lifetime.closeConversation { closeCalls++ }
        lifetime.backgroundRelease()
        assertEquals(1, closeCalls)
    }

    @Test
    fun `ownership retirement forbids publication without cancelling native work`() {
        val events = mutableListOf<String>()
        var cancelCalls = 0
        val lifetime = lifetime(events)
        lifetime.markWaitingForRuntime()
        lifetime.nativeConversationCreated { cancelCalls++ }
        assertTrue(lifetime.beginNativeGeneration(callerJobActive = { true }) { })

        assertEquals(
            BackgroundRetirementAction.WAIT_FOR_NATIVE_TERMINAL,
            lifetime.requestRetirement(BackgroundTerminationSource.CONVERSATION_SWITCH),
        )
        assertEquals(0, cancelCalls)
        assertTrue(lifetime.wasRetiredFor(BackgroundTerminationSource.CONVERSATION_SWITCH))
        assertCloseRejectedBeforeTerminal(lifetime)

        lifetime.flowTerminal()
        lifetime.conversationCloseBegin()
        lifetime.conversationCloseEnd()
        lifetime.backgroundRelease()
        assertEventOrder(events, "RETIRE_REQUESTED", "FLOW_TERMINAL", "CONVERSATION_CLOSE_BEGIN")
    }

    @Test
    fun `foreground preemption requests native cancellation and waits for terminal`() {
        val events = mutableListOf<String>()
        var cancelCalls = 0
        val lifetime = lifetime(events)
        lifetime.markWaitingForRuntime()
        lifetime.nativeConversationCreated { cancelCalls++ }
        assertTrue(lifetime.beginNativeGeneration(callerJobActive = { true }) { })

        assertEquals(
            BackgroundRetirementAction.WAIT_FOR_NATIVE_TERMINAL,
            lifetime.requestRetirement(BackgroundTerminationSource.FOREGROUND_PREEMPTION),
        )
        assertEquals(
            BackgroundRetirementAction.WAIT_FOR_NATIVE_TERMINAL,
            lifetime.requestRetirement(BackgroundTerminationSource.FOREGROUND_PREEMPTION),
        )
        assertEquals("cancelProcess must be requested once", 1, cancelCalls)
        assertCloseRejectedBeforeTerminal(lifetime)

        lifetime.flowTerminal()
        lifetime.conversationCloseBegin()
        lifetime.conversationCloseEnd()
        lifetime.backgroundRelease()
        assertEventOrder(events, "NATIVE_CANCEL_REQUESTED", "FLOW_TERMINAL", "CONVERSATION_CLOSE_BEGIN")
    }

    @Test
    fun `timeout requests native cancellation but does not cancel the collector`() {
        val events = mutableListOf<String>()
        var cancelCalls = 0
        val lifetime = lifetime(events)
        lifetime.markWaitingForRuntime()
        lifetime.nativeConversationCreated { cancelCalls++ }
        assertTrue(lifetime.beginNativeGeneration(callerJobActive = { true }) { })

        assertEquals(
            BackgroundRetirementAction.WAIT_FOR_NATIVE_TERMINAL,
            lifetime.requestRetirement(BackgroundTerminationSource.TIMEOUT),
        )
        assertTrue(lifetime.wasRetiredFor(BackgroundTerminationSource.TIMEOUT))
        assertEquals(1, cancelCalls)
        assertCloseRejectedBeforeTerminal(lifetime)

        lifetime.flowTerminal()
        lifetime.conversationCloseBegin()
        lifetime.conversationCloseEnd()
        lifetime.backgroundRelease()
        assertEventOrder(events, "NATIVE_CANCEL_REQUESTED", "FLOW_TERMINAL", "CONVERSATION_CLOSE_BEGIN")
    }

    @Test
    fun `retirement before native start refuses send and permits close`() {
        val events = mutableListOf<String>()
        var sendCalls = 0
        var cancelCalls = 0
        val lifetime = lifetime(events)
        lifetime.markWaitingForRuntime()
        lifetime.nativeConversationCreated { cancelCalls++ }

        assertEquals(
            BackgroundRetirementAction.CANCEL_JOB,
            lifetime.requestRetirement(BackgroundTerminationSource.NEW_CHAT),
        )
        assertFalse(lifetime.beginNativeGeneration(callerJobActive = { true }) { sendCalls++ })
        assertEquals(0, sendCalls)
        assertEquals(0, cancelCalls)
        lifetime.conversationCloseBegin()
        lifetime.conversationCloseEnd()
        lifetime.backgroundRelease()
        assertFalse(events.any { it.startsWith("NATIVE_GENERATION_BEGIN") })
    }

    @Test
    fun `cancelled caller cannot start native work`() {
        val events = mutableListOf<String>()
        var sendCalls = 0
        val lifetime = lifetime(events)
        lifetime.markWaitingForRuntime()
        lifetime.nativeConversationCreated { }

        assertFalse(lifetime.beginNativeGeneration(callerJobActive = { false }) { sendCalls++ })
        assertEquals(0, sendCalls)
        lifetime.conversationCloseBegin()
        lifetime.conversationCloseEnd()
        lifetime.backgroundRelease()
        assertTrue(events.any { it.startsWith("RETIRE_REQUESTED") && it.contains("OWNER_DISPOSED") })
    }

    private fun assertCloseRejectedBeforeTerminal(lifetime: BackgroundNativeConversationLifetime) {
        try {
            lifetime.conversationCloseBegin()
            fail("close must wait for the native terminal callback")
        } catch (_: IllegalStateException) {
            // The lifetime gate enforces the native terminal boundary.
        }
    }

    private fun assertEventOrder(events: List<String>, vararg fragments: String) {
        var previousIndex = -1
        fragments.forEach { fragment ->
            val index = events.indexOfFirst { fragment in it }
            assertTrue("missing lifecycle event fragment: $fragment in $events", index >= 0)
            assertTrue("event order violation for $fragment in $events", index > previousIndex)
            previousIndex = index
        }
    }
}
