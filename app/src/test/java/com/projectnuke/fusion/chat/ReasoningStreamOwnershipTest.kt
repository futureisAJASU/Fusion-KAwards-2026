package com.projectnuke.fusion.chat

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReasoningStreamOwnershipTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule { scheduler ->
        UnconfinedTestDispatcher(scheduler)
    }

    private val viewModels = mutableListOf<ChatViewModel>()

    @After
    fun tearDown() {
        viewModels.asReversed().forEach { it.cancelForTest(mainDispatcherRule.dispatcher.scheduler) }
        viewModels.clear()
    }

    @Test
    fun `live reasoning belongs to exact request id`() {
        val vm = ChatViewModel().also { viewModels += it }
        val conv = 201L
        vm.update(conv) {
            ConversationGenerationState(isGenerating = true, activeRequestId = "req-1", streamingText = "", streamingReasoningTags = setOf("think"))
        }
        vm.updateRequestState(conv, "req-1", requireActiveSession = false) {
            it.copy(streamingText = "<think>partial</think>", streamingReasoningTags = setOf("think"))
        }
        val state = vm.state(conv)
        assertEquals("<think>partial</think>", state.streamingText)
        assertEquals(setOf("think"), state.streamingReasoningTags)
    }

    @Test
    fun `stale request reasoning cannot settle under new response`() {
        val vm = ChatViewModel().also { viewModels += it }
        val conv = 202L
        vm.update(conv) {
            ConversationGenerationState(isGenerating = true, activeRequestId = "req-2", streamingText = "", streamingReasoningTags = setOf("think"))
        }
        vm.updateRequestState(conv, "req-1", requireActiveSession = false) {
            it.copy(streamingText = "<think>stale</think>", streamingReasoningTags = setOf("think"))
        }
        val state = vm.state(conv)
        assertEquals("", state.streamingText)
        assertEquals(setOf("think"), state.streamingReasoningTags)
    }

    @Test
    fun `cancelled reasoning generation cannot publish late`() {
        val vm = ChatViewModel().also { viewModels += it }
        val conv = 203L
        vm.update(conv) {
            ConversationGenerationState(isGenerating = true, activeRequestId = "req-3", streamingText = "live", streamingReasoningTags = setOf("think"))
        }
        val result = vm.finishRequestState(conv, "req-3")
        assertEquals(RequestStateFinishResult.APPLIED, result)
        val finished = vm.state(conv)
        assertNull(finished.streamingText)
        assertTrue(finished.streamingReasoningTags.isEmpty())
        vm.updateRequestState(conv, "req-3", requireActiveSession = false) {
            it.copy(streamingText = "<think>late</think>")
        }
        assertNull(vm.state(conv).streamingText)
    }

    @Test
    fun `finished clears transient reasoning tags`() {
        val state = ConversationGenerationState(
            isGenerating = true,
            activeRequestId = "r",
            streamingText = "t",
            streamingReasoningTags = setOf("fusion_thinking"),
        ).finished()
        assertNull(state.streamingText)
        assertTrue(state.streamingReasoningTags.isEmpty())
    }
}
