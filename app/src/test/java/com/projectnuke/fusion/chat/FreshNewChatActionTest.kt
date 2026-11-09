package com.projectnuke.fusion.chat

import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.projectnuke.fusion.llm.FusionRuntimeLock
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FreshNewChatActionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule()

    private var viewModel: ChatViewModel? = null

    @After
    fun tearDown() {
        viewModel?.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        viewModel = null
    }

    @Test
    fun `existing conversation opens fresh zero without touching its draft`() = runTest {
        val vm = ChatViewModel().also { viewModel = it }
        vm.selectConversation(42L)
        assertTrue(vm.draftMachine.updateCritical(42L) { it.copy(rawInput = "old conversation draft") })
        assertTrue(vm.draftMachine.updateCritical(0L) { it.copy(rawInput = "stale new-chat draft") })

        val result = vm.openFreshNewChat()

        assertTrue(result.success)
        assertEquals(ChatViewModel.NEW_CONVERSATION_ID, vm.currentConversationId.value)
        assertEquals(1L, result.epoch)
        assertEquals("", vm.draft(0L).rawInput)
        assertEquals("old conversation draft", vm.draft(42L).rawInput)
    }

    @Test
    fun `same zero new chat clears text and pending attachments`() = runTest {
        val vm = ChatViewModel().also { viewModel = it }
        assertTrue(vm.draftMachine.updateCritical(0L) {
            it.copy(
                rawInput = "hello but not sent",
                pendingAttachments = listOf(PendingAttachmentIdentity("a.txt", "text/plain", "/managed/a.txt")),
            )
        })

        val result = vm.openFreshNewChat()

        assertTrue(result.success)
        assertEquals(listOf("/managed/a.txt"), result.pendingAttachmentPaths)
        assertEquals("", vm.draft(0L).rawInput)
        assertTrue(vm.draft(0L).pendingAttachments.isEmpty())
        assertEquals(0L, vm.currentConversationId.value)
    }

    @Test
    fun `consecutive zero new chat actions are monotonic and each resets the draft`() = runTest {
        val vm = ChatViewModel().also { viewModel = it }

        val first = vm.openFreshNewChat()
        assertTrue(first.success)
        assertTrue(vm.draftMachine.updateCritical(0L) { it.copy(rawInput = "typed after first reset") })

        val second = vm.openFreshNewChat()

        assertTrue(second.success)
        assertTrue(second.epoch > first.epoch)
        assertEquals(second.epoch, vm.newChatEpoch.value)
        assertEquals("", vm.draft(0L).rawInput)
    }

    @Test
    fun `process restored selected scope blocks until explicitly cleared`() = runTest {
        val handle = SavedStateHandle(
            mapOf(
                "current_conversation_id" to 42L,
                "new_chat_epoch" to 9L,
                "selection_scope_conversation_id" to 42L,
            ),
        )
        val vm = ChatViewModel(savedStateHandle = handle).also { viewModel = it }

        assertNull(vm.selectionContextFor(42L, 9L))
        assertTrue(vm.selectionScopeUnavailableFor(42L))
        assertFalse(vm.clearUnavailableSelectionScope(41L))
        assertTrue(vm.clearUnavailableSelectionScope(42L))
        assertFalse(vm.selectionScopeUnavailableFor(42L))
        assertNull(handle.get<Long>("selection_scope_conversation_id"))
    }

    @Test
    fun `selected scope promotes with created chat and clears on switching away`() = runTest {
        val vm = ChatViewModel().also { viewModel = it }
        val opened = vm.openFreshNewChat()
        val snapshot = (SelectionContextSnapshot.create(
            listOf(
                SelectionSourceSnapshot(
                    SelectionSourceIdentity(SelectionSourceKind.MEMORY, memoryId = "saved-1"),
                    "선택한 메모",
                    "선택한 발췌",
                ),
            ),
            createdAt = 1L,
        ) as SelectionSnapshotResult.Valid).snapshot

        assertTrue(vm.installSelectionContext(snapshot, opened.epoch))
        assertEquals(0L, vm.selectionScopeConversationId.value)
        assertTrue(vm.promoteSelectionContext(snapshot.id, 42L, opened.epoch))
        vm.selectConversation(42L)
        assertSame(snapshot, vm.selectionContextFor(42L, opened.epoch))
        assertEquals(42L, vm.selectionScopeConversationId.value)

        vm.selectConversation(43L)
        assertNull(vm.selectionContextBinding.value)
        assertNull(vm.selectionScopeConversationId.value)
    }

    @Test
    fun `failed durable new-chat clear does not change surface or report success`() = runTest {
        val dir = Files.createTempDirectory("fusion-new-chat-failure").toFile()
        try {
            val vm = ChatViewModel.forTesting(FailingClearStore(dir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            vm.setPendingProjectNewChat(17L)

            val result = vm.openFreshNewChat()

            assertFalse(result.success)
            assertEquals(FreshNewChatFailure.DRAFT_CLEAR_FAILED, result.failure)
            assertEquals(ChatViewModel.NEW_CONVERSATION_ID, vm.currentConversationId.value)
            assertEquals(0L, vm.newChatEpoch.value)
            assertEquals("durable draft", vm.draft(0L).rawInput)
            assertEquals(17L, vm.pendingProjectNewChatId.value)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `normal fresh chat waits for hydration before issuing clear`() = runTest {
        val loadGate = CompletableDeferred<Unit>()
        val oldDraft = ComposerDraftState(
            rawInput = "old draft",
            pendingAttachments = listOf(
                PendingAttachmentIdentity("old.pdf", "application/pdf", "/managed/old.pdf"),
            ),
            version = 4L,
        )
        val store = GatedRestoreStore(loadGate, oldDraft)
        val vm = ChatViewModel.forTesting(store).also { viewModel = it }
        vm.selectConversation(42L)
        vm.setPendingProjectNewChat(17L)
        val epochBefore = vm.newChatEpoch.value

        val operation = async { vm.openFreshNewChat() }
        testScheduler.runCurrent()

        assertFalse(operation.isCompleted)
        assertEquals(0, store.writeCalls)
        assertTrue(vm.composerDrafts.value.isEmpty())
        assertEquals(42L, vm.currentConversationId.value)
        assertEquals(17L, vm.pendingProjectNewChatId.value)
        assertEquals(epochBefore, vm.newChatEpoch.value)

        loadGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        val result = operation.await()
        assertTrue(result.success)
        assertEquals(listOf("/managed/old.pdf"), result.pendingAttachmentPaths)
        assertEquals("", vm.draft(0L).rawInput)
        assertTrue(vm.draft(0L).pendingAttachments.isEmpty())
        assertEquals(0L, vm.currentConversationId.value)
        assertNull(vm.pendingProjectNewChatId.value)
        assertEquals(epochBefore + 1L, vm.newChatEpoch.value)
    }

    @Test
    fun `unreadable hydration fails every fresh-chat variant closed`() = runTest {
        val vm = ChatViewModel.forTesting(UnreadableDraftStore()).also { viewModel = it }
        vm.selectConversation(42L)
        vm.setPendingProjectNewChat(99L)
        val epochBefore = vm.newChatEpoch.value
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(0L, "unreadable-old"))

        val normal = async { vm.openFreshNewChat() }
        testScheduler.runCurrent()
        val normalResult = normal.await()

        assertFalse(normalResult.success)
        assertEquals(FreshNewChatFailure.DRAFT_UNAVAILABLE, normalResult.failure)
        assertEquals(42L, vm.currentConversationId.value)
        assertEquals(99L, vm.pendingProjectNewChatId.value)
        assertEquals(epochBefore, vm.newChatEpoch.value)
        assertTrue(vm.composerDrafts.value.isEmpty())
        assertFalse(ManualCompactionOperationRegistry.tryAcquireSubmission(0L, "probe"))
        ManualCompactionOperationRegistry.releaseSubmission(0L, "unreadable-old")

        val seeded = vm.openFreshProjectChatSeeded(7L, "synthesis prompt")
        assertFalse(seeded.success)
        assertEquals(FreshNewChatFailure.DRAFT_UNAVAILABLE, seeded.failure)
        assertEquals(42L, vm.currentConversationId.value)
        assertEquals(99L, vm.pendingProjectNewChatId.value)
        assertEquals(epochBefore, vm.newChatEpoch.value)
    }

    @Test
    fun `ordinary project chat publishes its binding only after a successful clear`() = runTest {
        val loadGate = CompletableDeferred<Unit>()
        val store = GatedRestoreStore(
            loadGate = loadGate,
            restoredDraft = ComposerDraftState(rawInput = "old", version = 3L),
        )
        val vm = ChatViewModel.forTesting(store).also { viewModel = it }
        vm.setPendingProjectNewChat(1L)

        val operation = async { vm.openFreshNewChat(projectIdAfterCommit = 2L) }
        testScheduler.runCurrent()
        assertFalse(operation.isCompleted)
        assertEquals(1L, vm.pendingProjectNewChatId.value)
        assertEquals(0, store.writeCalls)

        loadGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertTrue(operation.await().success)
        assertEquals(2L, vm.pendingProjectNewChatId.value)
    }

    @Test
    fun `ordinary project chat failure preserves the prior project binding`() = runTest {
        val dir = Files.createTempDirectory("fusion-project-chat-failure").toFile()
        try {
            val vm = ChatViewModel.forTesting(
                FailingClearStore(dir),
            ).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            vm.setPendingProjectNewChat(1L)

            val result = vm.openFreshNewChat(projectIdAfterCommit = 2L)

            assertFalse(result.success)
            assertEquals(FreshNewChatFailure.DRAFT_CLEAR_FAILED, result.failure)
            assertEquals(1L, vm.pendingProjectNewChatId.value)
            assertEquals("durable draft", vm.draft(0L).rawInput)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `active generation settles before fresh chat and next send is admitted`() = runTest {
        val vm = ChatViewModel().also { viewModel = it }
        val requestId = "request-a"
        val hold = CompletableDeferred<Unit>()
        val generationScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            vm.update(0L) {
                it.copy(isGenerating = true, activeRequestId = requestId, generationStatus = "생성 중")
            }
            vm.registry.start(generationScope, snapshot(0L, requestId)) { hold.await() }
            assertTrue(vm.registry.hasLogicalRequest(0L))

            val result = vm.openFreshNewChat()

            assertTrue(result.success)
            assertFalse(vm.state(0L).isGenerating)
            assertFalse(vm.state(0L).isStopping)
            assertNull(vm.state(0L).activeRequestId)
            assertFalse(vm.registry.hasLogicalRequest(0L))
            assertEquals(FusionRuntimeLock.RuntimeOwnerState.Idle, FusionRuntimeLock.ownerSnapshot())

            assertTrue(vm.draftMachine.updateCritical(0L) { it.copy(rawInput = "request-b") })
            val next = vm.beginSubmissionResult(0L)
            assertTrue(next is SubmissionAdmissionResult.Accepted)
            vm.settleSubmissionOwner(0L, (next as SubmissionAdmissionResult.Accepted).snapshot.token)
        } finally {
            hold.complete(Unit)
            generationScope.cancel()
            generationScope.coroutineContext[Job]?.join()
        }
    }

    private class FailingClearStore(dir: File) : PersistentComposerDraftStore(
        file = File(dir, PersistentComposerDraftStore.FILE_NAME),
        resolveManagedAttachment = { null },
        registerPendingAttachment = { it.absolutePath },
    ) {
        override suspend fun loadResult(): DraftStoreLoad = DraftStoreLoad.Valid(
            mapOf(0L to ComposerDraftState(rawInput = "durable draft", version = 1L)),
        )

        override suspend fun write(drafts: Map<Long, ComposerDraftState>): Boolean = false
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
