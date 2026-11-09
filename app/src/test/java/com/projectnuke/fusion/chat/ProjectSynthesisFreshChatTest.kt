package com.projectnuke.fusion.chat

import androidx.lifecycle.viewModelScope
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectSynthesisFreshChatTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule()

    private var viewModel: ChatViewModel? = null

    @After
    fun tearDown() {
        viewModel?.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        viewModel = null
    }

    @Test
    fun `seeded fresh chat replaces the draft and binds the exact project`() = runTest {
        val vm = ChatViewModel().also { viewModel = it }
        vm.selectConversation(42L)
        assertTrue(vm.draftMachine.updateCritical(42L) { it.copy(rawInput = "other conversation draft") })
        assertTrue(vm.draftMachine.updateCritical(0L) {
            it.copy(
                rawInput = "stale new-chat draft",
                pendingAttachments = listOf(
                    PendingAttachmentIdentity("a.txt", "text/plain", "/managed/a.txt"),
                ),
            )
        })
        val epochBefore = vm.newChatEpoch.value

        val result = vm.openFreshProjectChatSeeded(7L, "synthesis prompt")

        assertTrue(result.success)
        assertEquals(ChatViewModel.NEW_CONVERSATION_ID, vm.currentConversationId.value)
        assertEquals(epochBefore + 1L, result.epoch)
        assertEquals(result.epoch, vm.newChatEpoch.value)
        val seeded = vm.draft(0L)
        assertEquals("synthesis prompt", seeded.rawInput)
        assertTrue(seeded.pendingAttachments.isEmpty())
        assertNull(seeded.activeSubmissionToken)
        assertNull(seeded.importOwnership)
        assertEquals(7L, vm.pendingProjectNewChatId.value)
        assertEquals("other conversation draft", vm.draft(42L).rawInput)
    }

    @Test
    fun `seeded fresh chat returns old pending attachment paths for cleanup`() = runTest {
        val vm = ChatViewModel().also { viewModel = it }
        assertTrue(vm.draftMachine.updateCritical(0L) {
            it.copy(
                rawInput = "stale",
                pendingAttachments = listOf(
                    PendingAttachmentIdentity("a.txt", "text/plain", "/managed/a.txt"),
                    PendingAttachmentIdentity("b.txt", "text/plain", "/managed/b.txt"),
                ),
            )
        })

        val result = vm.openFreshProjectChatSeeded(7L, "synthesis prompt")

        assertTrue(result.success)
        assertEquals(listOf("/managed/a.txt", "/managed/b.txt"), result.pendingAttachmentPaths)
        assertTrue(vm.draft(0L).pendingAttachments.isEmpty())
    }

    @Test
    fun `seeded fresh chat waits for readable hydration before mutating`() = runTest {
        val loadGate = CompletableDeferred<Unit>()
        val oldDraft = ComposerDraftState(
            rawInput = "old",
            pendingAttachments = listOf(
                PendingAttachmentIdentity("old.pdf", "application/pdf", "/managed/old.pdf"),
            ),
            version = 8L,
            activeSubmissionToken = "old-submission",
            importOwnership = ComposerImportOwnership("old-import", ComposerImportStatus.COPYING),
            lastExternalHandoffId = "old-handoff",
        )
        val store = GatedRestoreStore(loadGate, oldDraft)
        val vm = ChatViewModel.forTesting(store).also { viewModel = it }
        vm.selectConversation(42L)
        vm.setPendingProjectNewChat(99L)
        val epochBefore = vm.newChatEpoch.value

        val operation = async { vm.openFreshProjectChatSeeded(7L, "synthesis prompt") }
        testScheduler.runCurrent()

        assertFalse(operation.isCompleted)
        assertEquals(0, store.writeCalls)
        assertTrue(vm.composerDrafts.value.isEmpty())
        assertEquals(42L, vm.currentConversationId.value)
        assertEquals(99L, vm.pendingProjectNewChatId.value)
        assertEquals(epochBefore, vm.newChatEpoch.value)

        loadGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertTrue(operation.await().success)
        assertEquals(
            ComposerDraftState(rawInput = "synthesis prompt", version = oldDraft.version + 1L),
            vm.draft(0L),
        )
        assertEquals(7L, vm.pendingProjectNewChatId.value)
        assertEquals(0L, vm.currentConversationId.value)
        assertEquals(epochBefore + 1L, vm.newChatEpoch.value)
    }

    @Test
    fun `failed seeded replacement restores the prior draft and publishes nothing`() = runTest {
        val dir = Files.createTempDirectory("fusion-synthesis-failure").toFile()
        try {
            val oldDraft = ComposerDraftState(
                rawInput = "durable draft",
                pendingAttachments = listOf(
                    PendingAttachmentIdentity("old.txt", "text/plain", "/managed/old.txt"),
                ),
                version = 4L,
                activeSubmissionToken = "old-submission",
                importOwnership = ComposerImportOwnership("old-import", ComposerImportStatus.COPYING),
            )
            val vm = ChatViewModel.forTesting(AlwaysFailingStore(dir, oldDraft)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            vm.selectConversation(42L)
            vm.setPendingProjectNewChat(99L)
            // The registry holds a single submission lease per conversation;
            // a second acquire while it is held is rejected by construction.
            assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(0L, "old-submission"))
            val epochBefore = vm.newChatEpoch.value

            val result = vm.openFreshProjectChatSeeded(7L, "synthesis prompt")

            assertFalse(result.success)
            assertEquals(FreshNewChatFailure.DRAFT_CLEAR_FAILED, result.failure)
            assertEquals(oldDraft, vm.draft(0L))
            assertEquals(42L, vm.currentConversationId.value)
            assertEquals(epochBefore, vm.newChatEpoch.value)
            assertEquals(99L, vm.pendingProjectNewChatId.value)
            // The held ownership was not released prematurely: a fresh probe
            // is still rejected while the old lease is held.
            assertFalse(ManualCompactionOperationRegistry.tryAcquireSubmission(0L, "probe"))
            ManualCompactionOperationRegistry.releaseSubmission(0L, "old-submission")
            assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(0L, "probe"))
            ManualCompactionOperationRegistry.releaseSubmission(0L, "probe")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `seeded write failure preserves the exact hydrated ownership state`() = runTest {
        val loadGate = CompletableDeferred<Unit>()
        val oldDraft = ComposerDraftState(
            rawInput = "old",
            pendingAttachments = listOf(
                PendingAttachmentIdentity("old.pdf", "application/pdf", "/managed/old.pdf"),
            ),
            version = 8L,
            activeSubmissionToken = "old-submission",
            importOwnership = ComposerImportOwnership("old-import", ComposerImportStatus.COPYING),
            lastExternalHandoffId = "old-handoff",
        )
        val store = GatedRestoreStore(loadGate, oldDraft, writeResult = false)
        val vm = ChatViewModel.forTesting(store).also { viewModel = it }
        vm.selectConversation(42L)
        vm.setPendingProjectNewChat(99L)
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(0L, "old-submission"))
        val epochBefore = vm.newChatEpoch.value

        val operation = async { vm.openFreshProjectChatSeeded(7L, "synthesis prompt") }
        testScheduler.runCurrent()
        assertFalse(operation.isCompleted)
        assertEquals(0, store.writeCalls)

        loadGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        val result = operation.await()
        assertFalse(result.success)
        assertEquals(FreshNewChatFailure.DRAFT_CLEAR_FAILED, result.failure)
        assertEquals(oldDraft, vm.draft(0L))
        assertEquals(42L, vm.currentConversationId.value)
        assertEquals(99L, vm.pendingProjectNewChatId.value)
        assertEquals(epochBefore, vm.newChatEpoch.value)
        assertFalse(ManualCompactionOperationRegistry.tryAcquireSubmission(0L, "probe"))
        ManualCompactionOperationRegistry.releaseSubmission(0L, "old-submission")
    }

    @Test
    fun `generation settlement failure mutates nothing`() = runTest {
        val vm = ChatViewModel().also { viewModel = it }
        val requestId = "request-synth"
        val hold = CompletableDeferred<Unit>()
        val generationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            vm.update(0L) {
                it.copy(isGenerating = true, activeRequestId = requestId, generationStatus = "생성 중")
            }
            vm.registry.start(generationScope, snapshot(0L, requestId)) { hold.await() }
            assertTrue(vm.registry.hasLogicalRequest(0L))
            assertTrue(vm.draftMachine.updateCritical(0L) { it.copy(rawInput = "untouched draft") })
            vm.selectConversation(42L)
            vm.registry.onBeforeIntentReservation = { throw IllegalStateException("settlement boom") }
            val epochBefore = vm.newChatEpoch.value

            val result = vm.openFreshProjectChatSeeded(7L, "synthesis prompt")

            assertFalse(result.success)
            assertEquals(FreshNewChatFailure.CANCELLATION_FAILED, result.failure)
            assertEquals("untouched draft", vm.draft(0L).rawInput)
            assertEquals(42L, vm.currentConversationId.value)
            assertEquals(epochBefore, vm.newChatEpoch.value)
            assertNull(vm.pendingProjectNewChatId.value)
        } finally {
            viewModel?.registry?.onBeforeIntentReservation = {}
            hold.complete(Unit)
            generationScope.cancel()
            generationScope.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun `invalid project id is rejected before any mutation`() = runTest {
        val vm = ChatViewModel().also { viewModel = it }
        assertTrue(vm.draftMachine.updateCritical(0L) { it.copy(rawInput = "keep me") })
        vm.setPendingProjectNewChat(99L)

        listOf(0L, -5L).forEach { badId ->
            val result = vm.openFreshProjectChatSeeded(badId, "synthesis prompt")
            assertFalse(result.success)
            assertEquals(FreshNewChatFailure.INVALID_SEED_PROJECT, result.failure)
        }

        assertEquals("keep me", vm.draft(0L).rawInput)
        assertEquals(0L, vm.newChatEpoch.value)
        assertEquals(99L, vm.pendingProjectNewChatId.value)
        assertEquals(ChatViewModel.NEW_CONVERSATION_ID, vm.currentConversationId.value)
    }

    @Test
    fun `blank or oversized seed prompt is rejected before any mutation`() = runTest {
        val vm = ChatViewModel().also { viewModel = it }
        assertTrue(vm.draftMachine.updateCritical(0L) { it.copy(rawInput = "keep me") })

        listOf(
            "",
            "   ",
            "x".repeat(ProjectSynthesisPromptPolicy.MAX_SYNTHESIS_PROMPT_CHARS + 1),
        ).forEach { badPrompt ->
            val result = vm.openFreshProjectChatSeeded(7L, badPrompt)
            assertFalse(result.success)
            assertEquals(FreshNewChatFailure.INVALID_SEED_PROMPT, result.failure)
        }

        assertEquals("keep me", vm.draft(0L).rawInput)
        assertEquals(0L, vm.newChatEpoch.value)
        assertNull(vm.pendingProjectNewChatId.value)
        assertEquals(ChatViewModel.NEW_CONVERSATION_ID, vm.currentConversationId.value)
    }

    @Test
    fun `committed seeded replacement releases old submission ownership`() = runTest {
        val vm = ChatViewModel().also { viewModel = it }
        assertTrue(vm.draftMachine.updateCritical(0L) {
            it.copy(rawInput = "old", activeSubmissionToken = "old-submission")
        })
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(0L, "old-submission"))
        assertFalse(ManualCompactionOperationRegistry.tryAcquireSubmission(0L, "probe"))

        val result = vm.openFreshProjectChatSeeded(7L, "synthesis prompt")

        assertTrue(result.success)
        assertNull(vm.draft(0L).activeSubmissionToken)
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(0L, "probe"))
        ManualCompactionOperationRegistry.releaseSubmission(0L, "probe")
    }

    @Test
    fun `committed seeded replacement releases old import ownership`() = runTest {
        val vm = ChatViewModel().also { viewModel = it }
        assertTrue(vm.draftMachine.updateCritical(0L) {
            it.copy(
                rawInput = "old",
                importOwnership = ComposerImportOwnership("old-import", ComposerImportStatus.COPYING),
            )
        })
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(0L, "old-import"))
        assertFalse(ManualCompactionOperationRegistry.tryAcquireSubmission(0L, "probe"))

        val result = vm.openFreshProjectChatSeeded(7L, "synthesis prompt")

        assertTrue(result.success)
        assertNull(vm.draft(0L).importOwnership)
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(0L, "probe"))
        ManualCompactionOperationRegistry.releaseSubmission(0L, "probe")
    }

    private class AlwaysFailingStore(
        dir: File,
        private val restored: ComposerDraftState,
    ) : PersistentComposerDraftStore(
        file = File(dir, PersistentComposerDraftStore.FILE_NAME),
        resolveManagedAttachment = { null },
        registerPendingAttachment = { it.absolutePath },
    ) {
        override suspend fun loadResult(): DraftStoreLoad = DraftStoreLoad.Valid(
            mapOf(0L to restored),
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
