package com.projectnuke.fusion.chat

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelLifecycleAndDraftTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule { scheduler ->
        UnconfinedTestDispatcher(scheduler)
    }

    @Test
    fun `configuration recreation retains active Activity scoped registry session`() = runTest {
        val activityViewModel = ChatViewModel()
        val release = CompletableDeferred<Unit>()
        val session = activityViewModel.registry.start(
            scope = activityViewModel.scope,
            snapshot = snapshot(41L, "request-41"),
        ) {
            release.await()
        }

        val recreatedCompositionOwner = activityViewModel
        assertTrue(recreatedCompositionOwner.registry.isActive(41L, "request-41"))
        assertFalse(session.job.isCancelled)

        release.complete(Unit)
        session.job.join()
    }

@Test
    fun `current conversation and serializable drafts survive process recreation`() {
        val handle = SavedStateHandle()
        val original = ChatViewModel(handle)
        original.selectConversation(72L)
        original.updateDraftText(72L, "persisted draft")

        val restored = ChatViewModel(
            SavedStateHandle(
                mapOf(
                    "current_conversation_id" to handle.get<Long>("current_conversation_id"),
                )
            )
        )

        assertEquals(72L, restored.currentConversationId.value)
        assertTrue(restored.draft(72L).rawInput.isEmpty())
        assertTrue(restored.draft(72L).pendingAttachments.isEmpty())
        assertNull("process recreation must not restore invalid import ownership", restored.draft(72L).importOwnership)
        assertNull("process recreation must not restore invalid submission ownership", restored.draft(72L).activeSubmissionToken)
    }

    @Test
    fun `restored conversational id that no longer exists falls back to New Chat`() = runTest {
        val restored = ChatViewModel(
            SavedStateHandle(mapOf("current_conversation_id" to 404L)),
        )
        assertEquals(404L, restored.currentConversationId.value)

        restored.revalidateRestoredConversation { id -> id == 404L && false }

        assertEquals(
            "a deleted restored conversation must never stay the authoritative surface",
            ChatViewModel.NEW_CONVERSATION_ID,
            restored.currentConversationId.value,
        )
    }

    @Test
    fun `restored conversational id that still exists is retained`() = runTest {
        val restored = ChatViewModel(
            SavedStateHandle(mapOf("current_conversation_id" to 42L)),
        )

        restored.revalidateRestoredConversation { id -> id == 42L }

        assertEquals(42L, restored.currentConversationId.value)
    }

    @Test
    fun `New Chat id is never revalidated away`() = runTest {
        val vm = ChatViewModel(SavedStateHandle(mapOf("current_conversation_id" to 0L)))

        vm.revalidateRestoredConversation { false }

        assertEquals(ChatViewModel.NEW_CONVERSATION_ID, vm.currentConversationId.value)
    }

    @Test
    fun `stale restored revalidation never overwrites a newer selection`() = runTest {
        val restored = ChatViewModel(
            SavedStateHandle(mapOf("current_conversation_id" to 404L)),
        )
        val existenceGate = CompletableDeferred<Boolean>()
        var existsChecked = false
        val settlement = launch {
            restored.revalidateRestoredConversation {
                existsChecked = true
                // Suspend the durable existence check until the test has
                // selected a different conversation while we are suspended.
                existenceGate.await()
            }
        }
        runCurrent()
        // The settlement body has run up to its first suspension.
        assertTrue(existsChecked)

        // Authoritative user/other path selects B while the stale check for A
        // is still suspended.
        restored.selectConversation(42L)

        // The stale check for 404 resumes with "does not exist" -> its New
        // Chat settlement must be dropped, never overwriting B.
        existenceGate.complete(false)
        settlement.join()

        assertEquals(42L, restored.currentConversationId.value)
    }

    @Test
    fun `stale settlement still applies when the current id is unchanged`() = runTest {
        val restored = ChatViewModel(
            SavedStateHandle(mapOf("current_conversation_id" to 404L)),
        )

        restored.revalidateRestoredConversation { id -> id == 404L && false }

        assertEquals(ChatViewModel.NEW_CONVERSATION_ID, restored.currentConversationId.value)
    }

    @Test
    fun `pending project new chat survives owner recreation and is consumed once`() {
        val handle = SavedStateHandle()
        val original = ChatViewModel(handle)
        original.setPendingProjectNewChat(91L)

        val recreated = ChatViewModel(
            SavedStateHandle(
                mapOf("pending_project_new_chat_id" to handle.get<Long>("pending_project_new_chat_id")),
            ),
        )

        assertEquals(91L, recreated.pendingProjectNewChatId.value)
        assertTrue(recreated.consumePendingProjectNewChat(91L))
        assertFalse(recreated.consumePendingProjectNewChat(91L))
        assertNull(recreated.pendingProjectNewChatId.value)
    }

    @Test
    fun `selecting an existing conversation clears pending project new chat`() {
        val vm = ChatViewModel()
        vm.setPendingProjectNewChat(12L)

        vm.selectConversation(44L)

        assertNull(vm.pendingProjectNewChatId.value)
    }

    @Test
    fun `A and B retain independent text and attachment trays`() = runTest {
        val vm = ChatViewModel()
        vm.updateDraftText(1L, "draft A")
        vm.updateDraftText(2L, "draft B")
        addAttachment(vm, 1L, PendingAttachmentIdentity("a", "text/plain", "/managed/a"))
        addAttachment(vm, 2L, PendingAttachmentIdentity("b", "image/png", "/managed/b"))

        assertEquals("draft A", vm.draft(1L).rawInput)
        assertEquals(listOf("/managed/a"), vm.draft(1L).pendingAttachments.map { it.localPath })
        assertEquals("draft B", vm.draft(2L).rawInput)
        assertEquals(listOf("/managed/b"), vm.draft(2L).pendingAttachments.map { it.localPath })
    }

    @Test
    fun `stale settlement for A cannot clear visible B`() = runTest {
        val vm = ChatViewModel()
        vm.updateDraftText(1L, "send A")
        addAttachment(vm, 1L, PendingAttachmentIdentity("a", "text/plain", "/managed/a"))
        val submissionA = requireNotNull(vm.beginSubmission(1L))
        assertTrue(submissionA.token.startsWith("s8:"))

        vm.updateDraftText(2L, "keep B")
        addAttachment(vm, 2L, PendingAttachmentIdentity("b", "text/plain", "/managed/b"))
        vm.selectConversation(2L)

        assertTrue(vm.reconcileCommittedSubmission(submissionA, listOf("/managed/a")))
        assertEquals(2L, vm.currentConversationId.value)
        assertEquals("keep B", vm.draft(2L).rawInput)
        assertEquals(listOf("/managed/b"), vm.draft(2L).pendingAttachments.map { it.localPath })
        ManualCompactionOperationRegistry.releaseSubmission(1L, submissionA.token)
    }

    @Test
    fun `new chat draft is isolated from persisted conversations`() = runTest {
        val vm = ChatViewModel()
        vm.updateDraftText(ChatViewModel.NEW_CONVERSATION_ID, "new chat")
        vm.updateDraftText(9L, "persisted chat")

        assertEquals("new chat", vm.draft(ChatViewModel.NEW_CONVERSATION_ID).rawInput)
        assertEquals("persisted chat", vm.draft(9L).rawInput)
        assertTrue(vm.clearDraft(9L))
        assertEquals("new chat", vm.draft(ChatViewModel.NEW_CONVERSATION_ID).rawInput)
    }

    @Test
    fun `stale import owner cannot mutate a replacement draft owner`() = runTest {
        val vm = ChatViewModel()
        val old = vm.beginAttachmentImport(1L)
        val replacement = vm.beginAttachmentImport(1L)

        assertFalse(
            vm.completeAttachmentImport(
                1L,
                old.token,
                listOf(PendingAttachmentIdentity("stale", "text/plain", "/managed/stale")),
            )
        )
        assertEquals(replacement.token, vm.draft(1L).importOwnership?.token)
        assertTrue(vm.draft(1L).pendingAttachments.isEmpty())
    }

    @Test
    fun `terminal request removal preserves replacement identity guard`() {
        val vm = ChatViewModel()
        vm.update(1L) { it.copy(activeRequestId = "A", isGenerating = true) }
        vm.finishRequestState(1L, "A")
        // State is not removed but finished (preserves lastRuntimeDiagnostics)
        val state = vm.state(1L)
        assertNull(state.activeRequestId)
        assertFalse(state.isGenerating)

        vm.update(1L) { it.copy(activeRequestId = "B", isGenerating = true) }
        vm.finishRequestState(1L, "A")
        assertEquals("B", vm.state(1L).activeRequestId)
    }

    @Test
    fun `critical removePendingAttachment returns false on failed persistence`() = runTest {
        val vm = ChatViewModel()
        vm.updateDraftText(1L, "test")
        addAttachment(vm, 1L, PendingAttachmentIdentity("a", "text/plain", "/managed/a"))
        val attachmentPath = vm.draft(1L).pendingAttachments.first().localPath

        assertTrue(vm.removePendingAttachment(1L, attachmentPath))
        assertTrue(vm.draft(1L).pendingAttachments.isEmpty())
        assertFalse(vm.removePendingAttachment(1L, attachmentPath))
    }

    @Test
    fun `critical beginSubmission returns null on in-flight submission`() = runTest {
        val vm = ChatViewModel()
        vm.updateDraftText(1L, "test")
        val first = vm.beginSubmission(1L)
        assertTrue(first != null)
        val second = vm.beginSubmission(1L)
        assertNull(second)
        assertTrue(vm.settleSubmissionOwner(1L, requireNotNull(first).token))
    }

    @Test
    fun `clearDraft removes in-memory state`() = runTest {
        val vm = ChatViewModel()
        vm.updateDraftText(1L, "test")
        assertTrue(vm.clearDraft(1L))
        assertEquals("", vm.draft(1L).rawInput)
    }

    private suspend fun addAttachment(
        vm: ChatViewModel,
        conversationId: Long,
        attachment: PendingAttachmentIdentity,
    ) {
        val owner = vm.beginAttachmentImport(conversationId)
        assertTrue(vm.beginAttachmentCopy(conversationId, owner.token))
        assertTrue(vm.completeAttachmentImport(conversationId, owner.token, listOf(attachment)))
    }

    private fun snapshot(conversationId: Long, requestId: String): GenerationRequestSnapshot =
        GenerationRequestSnapshot(
            requestId = requestId,
            conversationId = conversationId,
            generationModeKey = "TEST",
            selectedModelId = null,
            selectedModelPath = null,
            settings = com.projectnuke.fusion.model.GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "test",
            rawUserText = "test",
            createdAt = 0L,
        )
}
