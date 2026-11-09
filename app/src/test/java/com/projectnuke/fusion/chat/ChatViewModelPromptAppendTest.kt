package com.projectnuke.fusion.chat

import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelPromptAppendTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule { scheduler ->
        UnconfinedTestDispatcher(scheduler)
    }

    private val viewModels = mutableListOf<ChatViewModel>()

    @After
    fun tearDown() {
        // Cancel every owned scope BEFORE Main is reset: the draft machine's
        // debounced persist would otherwise resume on a torn-down Main and
        // poison unrelated tests with uncaught exceptions.
        viewModels.forEach { it.cancelForTest(mainDispatcherRule.dispatcher.scheduler) }
        viewModels.clear()
    }

    private fun tracked(vm: ChatViewModel): ChatViewModel {
        viewModels += vm
        return vm
    }

    private class FailingDraftStore : PersistentComposerDraftStore(
        file = File("prompt-append-test-drafts"),
        resolveManagedAttachment = { null },
        registerPendingAttachment = { null },
    ) {
        override suspend fun loadResult(): DraftStoreLoad = DraftStoreLoad.Missing
        override suspend fun write(drafts: Map<Long, ComposerDraftState>): Boolean = false
    }

    @Test
    fun `durable append success persists old draft plus prompt exactly`() = runTest {
        val vm = tracked(ChatViewModel())
        assertTrue(vm.replaceDraftTextDurably(71L, "old draft"))

        val prompt = StudySessionPromptPolicy.initialQuestionPrompt
        assertTrue(vm.appendPromptDurably(71L, prompt, ::appendPromptToDraft))
        assertEquals(
            appendPromptToDraft("old draft", prompt),
            vm.draft(71L).rawInput,
        )
    }

    @Test
    fun `durable append on blank composer becomes the prompt`() = runTest {
        val vm = tracked(ChatViewModel())
        val prompt = StudySessionPromptPolicy.gradingPrompt

        assertTrue(vm.appendPromptDurably(72L, prompt, ::appendPromptToDraft))
        assertEquals(prompt, vm.draft(72L).rawInput)
    }

    @Test
    fun `durable append persistence failure returns false and rolls back exactly`() = runTest {
        val vm = tracked(ChatViewModel.forTesting(FailingDraftStore()))
        val before = vm.draft(73L)

        assertFalse(
            vm.appendPromptDurably(
                73L,
                StudySessionPromptPolicy.initialQuestionPrompt,
                ::appendPromptToDraft,
            ),
        )
        assertEquals(before, vm.draft(73L))
    }

    @Test
    fun `durable append failure preserves a pre-existing draft exactly`() = runTest {
        val vm = tracked(ChatViewModel.forTesting(FailingDraftStore()))
        vm.updateDraftText(74L, "keep me")
        assertEquals("keep me", vm.draft(74L).rawInput)
        val before = vm.draft(74L)

        assertFalse(
            vm.appendPromptDurably(
                74L,
                StudySessionPromptPolicy.initialQuestionPrompt,
                ::appendPromptToDraft,
            ),
        )
        assertEquals(before, vm.draft(74L))
    }

    @Test
    fun `active submission rejects durable append with no draft change`() = runTest {
        val vm = tracked(ChatViewModel())
        assertTrue(vm.replaceDraftTextDurably(75L, "user text"))

        val submission = requireNotNull(vm.beginSubmission(75L))
        // The submission itself owns the draft now; snapshot after the
        // legitimate ownership change and prove the append adds nothing.
        val before = vm.draft(75L)
        assertFalse(
            vm.appendPromptDurably(
                75L,
                StudySessionPromptPolicy.initialQuestionPrompt,
                ::appendPromptToDraft,
            ),
        )
        assertEquals(before, vm.draft(75L))
        assertTrue(vm.settleSubmissionOwner(75L, submission.token))

        // After settlement the same append succeeds.
        assertTrue(
            vm.appendPromptDurably(
                75L,
                StudySessionPromptPolicy.initialQuestionPrompt,
                ::appendPromptToDraft,
            ),
        )
    }

    @Test
    fun `active import rejects durable append with no draft change`() = runTest {
        val vm = tracked(ChatViewModel())
        assertTrue(vm.replaceDraftTextDurably(76L, "user text"))

        val owner = vm.beginAttachmentImport(76L)
        // Suspend round-trip first: the channel is FIFO, so the queued
        // import is applied before this copy; the snapshot below then
        // deterministically carries import ownership.
        assertTrue(vm.beginAttachmentCopy(76L, owner.token))
        // The import itself owns the draft now; snapshot after the
        // legitimate ownership change and prove the append adds nothing.
        val before = vm.draft(76L)
        assertTrue(before.importOwnership?.token == owner.token)
        assertFalse(
            vm.appendPromptDurably(
                76L,
                StudySessionPromptPolicy.initialQuestionPrompt,
                ::appendPromptToDraft,
            ),
        )
        assertEquals(before, vm.draft(76L))
        assertTrue(vm.settleAttachmentImport(76L, owner.token))
    }

    @Test
    fun `durable replacement preserves pending attachments and never submits`() = runTest {
        val vm = tracked(ChatViewModel())
        val attachment = PendingAttachmentIdentity("notes.txt", "text/plain", "/tmp/notes.txt")
        assertTrue(vm.draftMachine.updateCritical(77L) { it.copy(rawInput = "original", pendingAttachments = listOf(attachment)) })

        assertTrue(vm.replaceDraftTextDurably(77L, "translated result"))

        assertEquals("translated result", vm.draft(77L).rawInput)
        assertEquals(listOf(attachment), vm.draft(77L).pendingAttachments)
        assertEquals(null, vm.draft(77L).activeSubmissionToken)
    }

    @Test
    fun `durable replacement rejects active submission and attachment import`() = runTest {
        val vm = tracked(ChatViewModel())
        assertTrue(vm.replaceDraftTextDurably(78L, "original"))

        val submission = requireNotNull(vm.beginSubmission(78L))
        assertFalse(vm.replaceDraftTextDurably(78L, "must not replace submission"))
        assertTrue(vm.settleSubmissionOwner(78L, submission.token))

        val owner = vm.beginAttachmentImport(78L)
        assertTrue(vm.beginAttachmentCopy(78L, owner.token))
        assertFalse(vm.replaceDraftTextDurably(78L, "must not replace import"))
        assertTrue(vm.settleAttachmentImport(78L, owner.token))
    }
}
