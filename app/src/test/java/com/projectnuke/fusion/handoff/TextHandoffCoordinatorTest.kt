package com.projectnuke.fusion.handoff

import androidx.lifecycle.viewModelScope
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.cancelForTest
import com.projectnuke.fusion.chat.ComposerDraftState
import com.projectnuke.fusion.chat.MainDispatcherIsolationRule
import com.projectnuke.fusion.chat.PendingAttachmentIdentity
import com.projectnuke.fusion.chat.PersistentComposerDraftStore
import com.projectnuke.fusion.chat.SubmissionAdmissionResult
import com.projectnuke.fusion.retrieval.LocalRagBounds
import com.projectnuke.fusion.util.AttachmentImportCoordinator
import com.projectnuke.fusion.util.AttachmentReferenceAuthority
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.util.RemovedAttachmentReleaseResult
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// Hybrid handoff lifecycle through the single production entry
// (TextHandoffCoordinator): one fresh-chat reset, old NEW_CONVERSATION_ID
// pending files cleaned, then either the durable inline composer path (short
// payloads: exact text, no file, no generation) or the managed
// 공유된 텍스트.txt attachment path (long payloads, existing Local RAG
// continuity). Consumption stays with the caller: the coordinator returns a
// terminal outcome exactly once per event.
@OptIn(ExperimentalCoroutinesApi::class)
class TextHandoffCoordinatorTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule()

    private val testDispatcher get() = mainDispatcherRule.dispatcher
    private var viewModel: ChatViewModel? = null
    private var pendingAttachmentsAtTestStart: Set<String> = emptySet()

    @Before
    fun capturePendingAttachments() {
        pendingAttachmentsAtTestStart = AttachmentStorageManager.pendingAttachmentPathsSnapshot()
    }

    @After
    fun tearDown() {
        viewModel?.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        viewModel = null
        (AttachmentStorageManager.pendingAttachmentPathsSnapshot() - pendingAttachmentsAtTestStart)
            .forEach(AttachmentStorageManager::unregisterPendingAttachment)
        assertEquals(
            pendingAttachmentsAtTestStart,
            AttachmentStorageManager.pendingAttachmentPathsSnapshot(),
        )
    }

    private fun realStore(dir: File, attachmentRoot: File? = null): PersistentComposerDraftStore =
        PersistentComposerDraftStore(
            file = File(dir, PersistentComposerDraftStore.FILE_NAME),
            resolveManagedAttachment = { path ->
                File(path).takeIf { candidate ->
                    attachmentRoot != null &&
                        candidate.parentFile?.canonicalPath == attachmentRoot.canonicalPath &&
                        candidate.isFile
                }
            },
            registerPendingAttachment = { it.absolutePath },
        )

    private fun deletePendingFileFor(
        rootCanonical: String,
        deletedPaths: MutableList<String>,
    ): suspend (String?) -> Boolean = { path ->
        path?.let {
            deletedPaths += it
            val file = File(it)
            if (file.parentFile?.canonicalPath == rootCanonical) {
                if (file.exists()) return@let file.delete()
                return@let true
            }
            false
        } ?: false
    }

    /**
     * Production-shaped authority-aware release for old-draft paths: no
     * surviving authority by default, so genuine orphans are released while
     * shared paths fail closed and are preserved.
     */
    private fun releaseRemovedDraftPathFor(
        root: File,
        releasedPaths: MutableList<String>,
        messageAuthority: AttachmentReferenceAuthority = AttachmentReferenceAuthority.Complete(emptySet()),
    ): suspend (String?) -> RemovedAttachmentReleaseResult = { path ->
        path?.let { releasedPaths += it }
        AttachmentStorageManager.releaseRemovedDraftAttachment(
            attachmentRoot = root,
            path = path,
            messageAuthority = messageAuthority,
            projectPaths = emptyList(),
            durableDraftPaths = emptyList(),
        )
    }

    private fun coordinatorFor(
        root: File,
        deletedPaths: MutableList<String>,
        toasts: MutableList<String>,
        releasedOldPaths: MutableList<String> = mutableListOf(),
    ): TextHandoffCoordinator {
        val rootCanonical = root.canonicalPath
        return TextHandoffCoordinator(
            attachmentRoot = root,
            coordinatorFactory = { text ->
                AttachmentImportCoordinator(
                    attachmentRoot = root,
                    inputFactory = { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) },
                    usableSpace = handoffTestUsableSpace,
                )
            },
            deletePendingFile = deletePendingFileFor(rootCanonical, deletedPaths),
            releaseRemovedDraftPath = releaseRemovedDraftPathFor(root, releasedOldPaths),
            notify = { toasts += it },
        )
    }

    /** Coordinator whose managed-file copy always fails (SOURCE_UNAVAILABLE). */
    private fun failingCoordinatorFor(
        root: File,
        deletedPaths: MutableList<String>,
        toasts: MutableList<String>,
    ): TextHandoffCoordinator {
        val rootCanonical = root.canonicalPath
        return TextHandoffCoordinator(
            attachmentRoot = root,
            coordinatorFactory = { _ ->
                AttachmentImportCoordinator(
                    attachmentRoot = root,
                    inputFactory = { null },
                    usableSpace = handoffTestUsableSpace,
                )
            },
            deletePendingFile = deletePendingFileFor(rootCanonical, deletedPaths),
            releaseRemovedDraftPath = releaseRemovedDraftPathFor(root, mutableListOf()),
            notify = { toasts += it },
        )
    }

    private fun shareEvent(id: String, text: String) =
        ExternalTextHandoff(id = id, source = ExternalTextHandoffSource.SHARE_TEXT, text = text)

    private fun managedFiles(root: File): List<File> =
        root.listFiles { f -> f.isFile && f.name.endsWith("_공유된 텍스트.txt") }.orEmpty().toList()

    private fun attachmentFiles(root: File): List<File> =
        root.listFiles { f -> f.isFile }.orEmpty().toList()

    // The FusionApp consumption contract: the event slot is consumed exactly
    // once after the coordinator returns its terminal outcome. Returns the
    // number of processing rounds.
    private suspend fun processHandoffSlot(
        slot: PendingExternalTextHandoff,
        coordinator: TextHandoffCoordinator,
        chatViewModel: ChatViewModel,
        consumed: MutableList<String>,
    ): Int {
        var rounds = 0
        while (rounds < 3) {
            val pending = slot.pending ?: return rounds
            rounds++
            coordinator.handle(pending, chatViewModel)
            slot.consume()?.let { consumed += it.id }
        }
        return rounds
    }

    // ---- inline lifecycle -------------------------------------------------

    @Test
    fun `inline handoff writes the exact text into the fresh composer and creates nothing`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        val storeDir = Files.createTempDirectory("fusion-handoff-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val deleted = mutableListOf<String>()
            val toasts = mutableListOf<String>()
            val source = "HBM4 uses a wider interface.  \n\n이어서 자연스러운 한국어 질문을 붙일 수 있어요.  "
            val epochBefore = vm.newChatEpoch.value

            val outcome = coordinatorFor(attachmentDir, deleted, toasts).handle(
                shareEvent("handoff-inline-1", source),
                vm,
            )

            assertEquals(HandoffImportOutcome.IMPORTED, outcome)
            assertTrue(deleted.isEmpty())
            assertTrue(toasts.isEmpty())
            assertTrue(vm.newChatEpoch.value > epochBefore)
            assertEquals(ChatViewModel.NEW_CONVERSATION_ID, vm.currentConversationId.value)
            // Exact payload in the composer, byte-for-byte, untrimmed.
            assertEquals(source, vm.draft(ChatViewModel.NEW_CONVERSATION_ID).rawInput)
            assertEquals(
                "handoff-inline-1",
                vm.draft(ChatViewModel.NEW_CONVERSATION_ID).lastExternalHandoffId,
            )
            // No attachment chip, no managed file, no ownership, no generation.
            assertTrue(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).pendingAttachments.isEmpty())
            assertNull(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).importOwnership)
            assertNull(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).activeSubmissionToken)
            assertTrue(attachmentFiles(attachmentDir).isEmpty())
            assertNull(vm.state(ChatViewModel.NEW_CONVERSATION_ID).activeRequestId)
            assertFalse(vm.state(ChatViewModel.NEW_CONVERSATION_ID).isGenerating)
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `inline handoff cleans stale new-chat pending files exactly like new chat`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        val storeDir = Files.createTempDirectory("fusion-handoff-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            assertTrue(vm.draftMachine.updateCritical(ChatViewModel.NEW_CONVERSATION_ID) {
                it.copy(
                    rawInput = "옛 초안",
                    pendingAttachments = listOf(
                        PendingAttachmentIdentity("old.txt", "text/plain", "old-pending-path"),
                    ),
                    version = it.version + 1L,
                )
            })
            val deleted = mutableListOf<String>()
            val released = mutableListOf<String>()
            val toasts = mutableListOf<String>()
            val epochBefore = vm.newChatEpoch.value

            val outcome = coordinatorFor(attachmentDir, deleted, toasts, released).handle(
                shareEvent("handoff-inline-2", "새 공유 내용"),
                vm,
            )

            assertEquals(HandoffImportOutcome.IMPORTED, outcome)
            assertTrue(vm.newChatEpoch.value > epochBefore)
            // Old NEW_CONVERSATION_ID pending attachment released through
            // the authority-aware seam exactly like the normal New Chat
            // contract; nothing new exists for inline.
            assertTrue(released.contains("old-pending-path"))
            assertEquals("새 공유 내용", vm.draft(ChatViewModel.NEW_CONVERSATION_ID).rawInput)
            assertTrue(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).pendingAttachments.isEmpty())
            assertTrue(attachmentFiles(attachmentDir).isEmpty())
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `inline durable write failure preserves the old draft and epoch`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        val storeDir = Files.createTempDirectory("fusion-handoff-store").toFile()
        try {
            val oldFile = File(attachmentDir, "old-pending.txt").apply { writeText("old") }
            val store = FailingFirstInlineWriteStore(storeDir, attachmentDir)
            assertTrue(store.write(mapOf(
                ChatViewModel.NEW_CONVERSATION_ID to ComposerDraftState(
                    rawInput = "기존 초안",
                    pendingAttachments = listOf(
                        PendingAttachmentIdentity("old-pending.txt", "text/plain", oldFile.canonicalPath),
                    ),
                    version = 4L,
                ),
            )))
            val vm = ChatViewModel.forTesting(store).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val before = vm.draft(ChatViewModel.NEW_CONVERSATION_ID)
            store.failNextInlineWrite()
            val deleted = mutableListOf<String>()
            val toasts = mutableListOf<String>()
            val epochBefore = vm.newChatEpoch.value

            val outcome = coordinatorFor(attachmentDir, deleted, toasts).handle(
                shareEvent("handoff-inline-fail", "저장이 실패할 인라인 텍스트"),
                vm,
            )

            // Terminal failure: the durable draft write was not imported and
            // the event must not be reported as successful.
            assertEquals(HandoffImportOutcome.FAILED_IMPORT, outcome)
            assertTrue(toasts.isNotEmpty())
            assertTrue(toasts.last().contains("반영하지 못했습니다"))
            // The failed adoption never cleared or replaced the old durable
            // composing state, and therefore never advanced New Chat.
            assertEquals(before, vm.draft(ChatViewModel.NEW_CONVERSATION_ID))
            assertEquals(epochBefore, vm.newChatEpoch.value)
            assertTrue(oldFile.isFile)
            assertTrue(deleted.isEmpty())
            assertEquals(listOf(oldFile.canonicalPath), attachmentFiles(attachmentDir).map { it.canonicalPath })
            assertFalse(vm.state(ChatViewModel.NEW_CONVERSATION_ID).isGenerating)
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `cancellation before inline adoption keeps the event retryable`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        val storeDir = Files.createTempDirectory("fusion-handoff-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(SyncFakeDraftStore()).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val coordinator = coordinatorFor(attachmentDir, mutableListOf(), mutableListOf())

            val worker = launch {
                coordinator.handle(shareEvent("handoff-inline-cancel", "취소 테스트"), vm)
            }
            worker.cancel()
            advanceUntilIdle()
            // Cancellation before the durable adoption commit leaves the
            // event retryable and makes no New Chat transition.
            assertTrue(worker.isCancelled)
            assertEquals(0L, vm.newChatEpoch.value)
            assertEquals("", vm.draft(ChatViewModel.NEW_CONVERSATION_ID).rawInput)

            // Retry with the same event identity: the fixture is healthy again.
            val outcome = coordinator.handle(shareEvent("handoff-inline-cancel", "취소 테스트"), vm)
            assertEquals(HandoffImportOutcome.IMPORTED, outcome)
            assertEquals("취소 테스트", vm.draft(ChatViewModel.NEW_CONVERSATION_ID).rawInput)
            assertTrue(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).pendingAttachments.isEmpty())
            assertTrue(attachmentFiles(attachmentDir).isEmpty())
            assertEquals(1L, vm.newChatEpoch.value)
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // ---- attachment regression --------------------------------------------

    @Test
    fun `long handoff still creates the managed shared text file on a fresh chat`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        val storeDir = Files.createTempDirectory("fusion-handoff-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val deleted = mutableListOf<String>()
            val toasts = mutableListOf<String>()
            val source = "공유된 텍스트 첫 문단을 담기 위한 긴 내용입니다. ".repeat(80)

            val outcome = coordinatorFor(attachmentDir, deleted, toasts).handle(
                shareEvent("handoff-long-1", source),
                vm,
            )

            assertEquals(HandoffImportOutcome.IMPORTED, outcome)
            assertTrue(deleted.isEmpty())
            assertTrue(toasts.isEmpty())

            // Managed text file lives directly under the attachment directory.
            val files = managedFiles(attachmentDir)
            assertEquals(1, files.size)
            assertEquals(source, files[0].readText(Charsets.UTF_8))

            // Registered through the existing pending ownership contract.
            assertTrue(AttachmentStorageManager.isPendingAttachmentRegistered(files[0].canonicalPath))

            // Fresh chat established BEFORE the attachment import: epoch moved,
            // surface is NEW_CONVERSATION_ID, durable draft carries the identity.
            assertTrue(vm.newChatEpoch.value > 0L)
            assertEquals(ChatViewModel.NEW_CONVERSATION_ID, vm.currentConversationId.value)
            val draft = vm.draft(ChatViewModel.NEW_CONVERSATION_ID)
            assertEquals(
                listOf(
                    PendingAttachmentIdentity(
                        name = HandoffAttachmentImporter.DISPLAY_NAME,
                        mimeType = HandoffAttachmentImporter.MIME_TYPE_TEXT_PLAIN,
                        localPath = files[0].canonicalPath,
                    ),
                ),
                draft.pendingAttachments,
            )
            // Composer stays empty, no ownership, no generation.
            assertTrue(draft.rawInput.isEmpty())
            assertNull(draft.importOwnership)
            assertNull(draft.activeSubmissionToken)
            assertNull(vm.state(ChatViewModel.NEW_CONVERSATION_ID).activeRequestId)
            assertFalse(vm.state(ChatViewModel.NEW_CONVERSATION_ID).isGenerating)
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `fresh reset precedes attachment import and stale new-chat pending files are cleaned`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        val storeDir = Files.createTempDirectory("fusion-handoff-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            assertTrue(vm.draftMachine.updateCritical(ChatViewModel.NEW_CONVERSATION_ID) {
                it.copy(
                    rawInput = "옛 초안",
                    pendingAttachments = listOf(
                        PendingAttachmentIdentity("old.txt", "text/plain", "old-pending-path"),
                    ),
                    version = it.version + 1L,
                )
            })
            val deleted = mutableListOf<String>()
            val released = mutableListOf<String>()
            val toasts = mutableListOf<String>()
            val epochBefore = vm.newChatEpoch.value

            val outcome = coordinatorFor(attachmentDir, deleted, toasts, released).handle(
                shareEvent("handoff-long-2", "새 공유 내용".repeat(300)),
                vm,
            )

            assertEquals(HandoffImportOutcome.IMPORTED, outcome)
            assertTrue(vm.newChatEpoch.value > epochBefore)
            // Old NEW_CONVERSATION_ID pending attachment released through the
            // authority-aware seam exactly like the normal New Chat contract;
            // the imported file survives.
            assertTrue(released.contains("old-pending-path"))
            assertTrue(deleted.none { it.endsWith("_공유된 텍스트.txt") })
            assertEquals("", vm.draft(ChatViewModel.NEW_CONVERSATION_ID).rawInput)
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `oversized text is rejected before any fresh reset or file write`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        val storeDir = Files.createTempDirectory("fusion-handoff-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val deleted = mutableListOf<String>()
            val toasts = mutableListOf<String>()
            val oversized = "x".repeat(LocalRagBounds.MAX_DOC_CHARS + 1)
            val epochBefore = vm.newChatEpoch.value

            val outcome = coordinatorFor(attachmentDir, deleted, toasts).handle(
                shareEvent("handoff-too-long", oversized),
                vm,
            )

            assertEquals(HandoffImportOutcome.REJECTED_TOO_LARGE, outcome)
            assertTrue(deleted.isEmpty())
            assertEquals(1, toasts.size)
            assertTrue(toasts.single().contains(LocalRagBounds.MAX_DOC_CHARS.toString()))
            assertEquals(epochBefore, vm.newChatEpoch.value)
            assertEquals(0, attachmentFiles(attachmentDir).size)
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `many-line short-byte payload still uses the managed attachment path`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        val storeDir = Files.createTempDirectory("fusion-handoff-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val deleted = mutableListOf<String>()
            val toasts = mutableListOf<String>()
            // 25 short lines: well under the byte limit, over the line limit.
            val source = List(25) { "x" }.joinToString("\n")

            val outcome = coordinatorFor(attachmentDir, deleted, toasts).handle(
                shareEvent("handoff-lines-1", source),
                vm,
            )

            assertEquals(HandoffImportOutcome.IMPORTED, outcome)
            val files = managedFiles(attachmentDir)
            assertEquals(1, files.size)
            assertEquals(source, files[0].readText(Charsets.UTF_8))
            assertEquals("", vm.draft(ChatViewModel.NEW_CONVERSATION_ID).rawInput)
            assertFalse(vm.state(ChatViewModel.NEW_CONVERSATION_ID).isGenerating)
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `failed durable attachment adoption cleans up the generated pending file`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        val storeDir = Files.createTempDirectory("fusion-handoff-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(FailingAfterFirstWriteStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val deleted = mutableListOf<String>()
            val toasts = mutableListOf<String>()

            val outcome = coordinatorFor(attachmentDir, deleted, toasts).handle(
                shareEvent("handoff-fail", "가져오기 실패할 긴 내용입니다. ".repeat(80)),
                vm,
            )

            assertEquals(HandoffImportOutcome.FAILED_IMPORT, outcome)
            // The generated pending file existed after the copy and was removed
            // on the failed durable adoption; no orphan (.part included) remains.
            assertTrue(deleted.isNotEmpty())
            assertEquals(0, attachmentFiles(attachmentDir).size)
            // Bounded Korean message shown, no crash.
            assertTrue(toasts.isNotEmpty())
            assertTrue(toasts.last().isNotBlank())
            assertFalse(vm.state(ChatViewModel.NEW_CONVERSATION_ID).isGenerating)
            assertNotNull(vm.draft(ChatViewModel.NEW_CONVERSATION_ID))
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `active attachment owner blocks prepared replacement and rolls staged file back`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        val storeDir = Files.createTempDirectory("fusion-handoff-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            assertTrue(vm.draftMachine.updateCritical(ChatViewModel.NEW_CONVERSATION_ID) {
                it.copy(rawInput = "사용자가 입력 중인 초안", version = it.version + 1L)
            })
            val before = vm.draft(ChatViewModel.NEW_CONVERSATION_ID)
            val owner = (vm.tryBeginAttachmentImport(ChatViewModel.NEW_CONVERSATION_ID) as com.projectnuke.fusion.chat.AttachmentImportAdmission.Accepted).owner
            advanceUntilIdle()
            assertEquals(owner, vm.draft(ChatViewModel.NEW_CONVERSATION_ID).importOwnership)
            val deleted = mutableListOf<String>()

            val outcome = coordinatorFor(attachmentDir, deleted, mutableListOf()).handle(
                shareEvent("evt-active-owner", "관리 파일로 전환될 긴 공유 내용입니다. ".repeat(100)),
                vm,
            )

            assertEquals(HandoffImportOutcome.FAILED_IMPORT, outcome)
            assertTrue(deleted.isNotEmpty())
            assertTrue(attachmentFiles(attachmentDir).isEmpty())
            assertEquals(before.rawInput, vm.draft(ChatViewModel.NEW_CONVERSATION_ID).rawInput)
            assertEquals(owner, vm.draft(ChatViewModel.NEW_CONVERSATION_ID).importOwnership)
            assertEquals(0L, vm.newChatEpoch.value)
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // ---- one-shot semantics -------------------------------------------------

    @Test
    fun `identical short payload shared twice with different event ids is processed twice inline`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        val storeDir = Files.createTempDirectory("fusion-handoff-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val coordinator = coordinatorFor(attachmentDir, mutableListOf(), mutableListOf())
            val text = "같은 인라인 내용을 두 번 공유해도 매번 처리됩니다."

            val first = coordinator.handle(shareEvent("evt-inline-a", text), vm)
            val second = coordinator.handle(shareEvent("evt-inline-b", text), vm)

            assertEquals(HandoffImportOutcome.IMPORTED, first)
            assertEquals(HandoffImportOutcome.IMPORTED, second)
            assertEquals(
                text,
                vm.draft(ChatViewModel.NEW_CONVERSATION_ID).rawInput,
            )
            assertTrue(vm.newChatEpoch.value >= 2L)
            assertTrue(attachmentFiles(attachmentDir).isEmpty())
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `identical long payload shared twice keeps only the newest managed file`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        val storeDir = Files.createTempDirectory("fusion-handoff-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val deleted = mutableListOf<String>()
            val coordinator = coordinatorFor(attachmentDir, deleted, mutableListOf())
            val text = "동일한 긴 내용입니다. ".repeat(100)

            val first = coordinator.handle(shareEvent("evt-long-a", text), vm)
            val firstFiles = managedFiles(attachmentDir)
            assertEquals(HandoffImportOutcome.IMPORTED, first)
            assertEquals(1, firstFiles.size)

            val released = mutableListOf<String>()
            val second = TextHandoffCoordinator(
                attachmentRoot = attachmentDir,
                coordinatorFactory = { textValue ->
                    AttachmentImportCoordinator(
                        attachmentRoot = attachmentDir,
                        inputFactory = {
                            ByteArrayInputStream(textValue.toByteArray(Charsets.UTF_8))
                        },
                        usableSpace = handoffTestUsableSpace,
                    )
                },
                deletePendingFile = deletePendingFileFor(attachmentDir.canonicalPath, mutableListOf()),
                releaseRemovedDraftPath = releaseRemovedDraftPathFor(attachmentDir, released),
                notify = {},
            ).handle(shareEvent("evt-long-b", text), vm)

            assertEquals(HandoffImportOutcome.IMPORTED, second)
            // The second fresh reset released the first pending file through
            // the authority-aware seam exactly like the New Chat contract,
            // then imported a fresh managed file.
            assertTrue(released.contains(firstFiles.single().canonicalPath))
            assertTrue(vm.newChatEpoch.value >= 2L)
            val finalFiles = managedFiles(attachmentDir)
            assertEquals(1, finalFiles.size)
            assertTrue(finalFiles.single().canonicalPath != firstFiles.single().canonicalPath)
            assertEquals(text, finalFiles.single().readText(Charsets.UTF_8))
            assertEquals(
                listOf(
                    PendingAttachmentIdentity(
                        name = HandoffAttachmentImporter.DISPLAY_NAME,
                        mimeType = HandoffAttachmentImporter.MIME_TYPE_TEXT_PLAIN,
                        localPath = finalFiles.single().canonicalPath,
                    ),
                ),
                vm.draft(ChatViewModel.NEW_CONVERSATION_ID).pendingAttachments,
            )
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `successful inline event is consumed exactly once`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        val storeDir = Files.createTempDirectory("fusion-handoff-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val coordinator = coordinatorFor(attachmentDir, mutableListOf(), mutableListOf())
            val slot = PendingExternalTextHandoff()
            slot.submit(shareEvent("evt-inline-once", "한 번만 소비되는 인라인 텍스트"))
            val consumed = mutableListOf<String>()

            val rounds = processHandoffSlot(slot, coordinator, vm, consumed)
            assertEquals(1, rounds)
            assertEquals(listOf("evt-inline-once"), consumed)
            assertNull(slot.pending)
            // A recomposition-driven re-run finds nothing and consumes nothing.
            assertEquals(0, processHandoffSlot(slot, coordinator, vm, consumed))
            assertEquals(listOf("evt-inline-once"), consumed)
            assertTrue(attachmentFiles(attachmentDir).isEmpty())
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `successful attachment event is consumed exactly once`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        val storeDir = Files.createTempDirectory("fusion-handoff-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val coordinator = coordinatorFor(attachmentDir, mutableListOf(), mutableListOf())
            val slot = PendingExternalTextHandoff()
            slot.submit(shareEvent("evt-attachment-once", "긴 첨부 문서입니다. ".repeat(120)))
            val consumed = mutableListOf<String>()

            val rounds = processHandoffSlot(slot, coordinator, vm, consumed)
            assertEquals(1, rounds)
            assertEquals(listOf("evt-attachment-once"), consumed)
            assertNull(slot.pending)
            assertEquals(0, processHandoffSlot(slot, coordinator, vm, consumed))
            assertEquals(listOf("evt-attachment-once"), consumed)
            assertEquals(1, managedFiles(attachmentDir).size)
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `durable handoff receipt makes a process-death replay imported without recopy`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        val storeDir = Files.createTempDirectory("fusion-handoff-store").toFile()
        var firstViewModel: ChatViewModel? = null
        try {
            val text = "프로세스 재시작 뒤에도 같은 공유 이벤트는 한 번만 반영됩니다. ".repeat(100)
            val first = ChatViewModel.forTesting(realStore(storeDir, attachmentDir)).also {
                firstViewModel = it
                viewModel = it
            }
            assertTrue(first.draftMachine.awaitHydration())
            val firstCoordinator = coordinatorFor(attachmentDir, mutableListOf(), mutableListOf())
            assertEquals(
                HandoffImportOutcome.IMPORTED,
                firstCoordinator.handle(shareEvent("evt-replay", text), first),
            )
            val adoptedPath = first.draft(ChatViewModel.NEW_CONVERSATION_ID)
                .pendingAttachments.single().localPath
            assertEquals("evt-replay", first.draft(ChatViewModel.NEW_CONVERSATION_ID).lastExternalHandoffId)
            firstViewModel?.viewModelScope?.cancel()

            var copyCount = 0
            val second = ChatViewModel.forTesting(realStore(storeDir, attachmentDir)).also {
                viewModel = it
            }
            assertTrue(second.draftMachine.awaitHydration())
            val replay = TextHandoffCoordinator(
                attachmentRoot = attachmentDir,
                coordinatorFactory = { textValue ->
                    copyCount++
                    AttachmentImportCoordinator(
                        attachmentRoot = attachmentDir,
                        inputFactory = {
                            ByteArrayInputStream(textValue.toByteArray(Charsets.UTF_8))
                        },
                        usableSpace = handoffTestUsableSpace,
                    )
                },
                deletePendingFile = { path ->
                    path?.let { candidate ->
                        val file = File(candidate)
                        if (file.exists()) file.delete() else true
                    } ?: false
                },
                releaseRemovedDraftPath = { path ->
                    AttachmentStorageManager.releaseRemovedDraftAttachment(
                        attachmentRoot = attachmentDir,
                        path = path,
                        messageAuthority = AttachmentReferenceAuthority.Complete(emptySet()),
                        projectPaths = emptyList(),
                        durableDraftPaths = emptySet(),
                    )
                },
                notify = {},
            )

            assertEquals(
                HandoffImportOutcome.IMPORTED,
                replay.handle(shareEvent("evt-replay", text), second),
            )
            assertEquals(0, copyCount)
            assertEquals(0L, second.newChatEpoch.value)
            assertEquals(adoptedPath, second.draft(ChatViewModel.NEW_CONVERSATION_ID)
                .pendingAttachments.single().localPath)
            assertTrue(File(adoptedPath).isFile)
        } finally {
            firstViewModel?.viewModelScope?.cancel()
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // ---- ownership-cleanup regression ---------------------------------------

    @Test
    fun `old history-shared draft source survives handoff replacement`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        val storeDir = Files.createTempDirectory("fusion-handoff-store").toFile()
        // A historical message owns F; the old NEW_CHAT draft references the
        // SAME physical file (pending-registered as a shared copy).
        val sharedFile = File(attachmentDir, "history-shared.txt").apply { writeText("historical") }
        AttachmentStorageManager.registerPendingAttachment(sharedFile)
        try {
            val store = realStore(storeDir, attachmentDir)
            assertTrue(store.write(mapOf(
                ChatViewModel.NEW_CONVERSATION_ID to ComposerDraftState(
                    rawInput = "옛 초안",
                    pendingAttachments = listOf(
                        PendingAttachmentIdentity("history-shared.txt", "text/plain", sharedFile.canonicalPath),
                    ),
                    version = 1L,
                ),
            )))
            val vm = ChatViewModel.forTesting(store).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val deleted = mutableListOf<String>()
            val released = mutableListOf<String>()
            val toasts = mutableListOf<String>()

            val outcome = TextHandoffCoordinator(
                attachmentRoot = attachmentDir,
                coordinatorFactory = { text ->
                    AttachmentImportCoordinator(
                        attachmentRoot = attachmentDir,
                        inputFactory = { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) },
                        usableSpace = handoffTestUsableSpace,
                    )
                },
                deletePendingFile = deletePendingFileFor(attachmentDir.canonicalPath, deleted),
                releaseRemovedDraftPath = releaseRemovedDraftPathFor(
                    attachmentDir,
                    released,
                    messageAuthority = AttachmentReferenceAuthority.Complete(setOf(sharedFile.canonicalPath)),
                ),
                notify = { toasts += it },
            ).handle(shareEvent("evt-old-shared", "새 공유 내용"), vm)

            assertEquals(HandoffImportOutcome.IMPORTED, outcome)
            // The removed old-draft reference settled through the
            // authority-aware release, never the destructive delete.
            assertTrue(released.contains(sharedFile.canonicalPath))
            assertTrue(deleted.none { it == sharedFile.canonicalPath })
            // F preserved and the historical reference stays readable.
            assertTrue(sharedFile.isFile)
            assertEquals("historical", sharedFile.readText(Charsets.UTF_8))
            // The replacement draft no longer references F.
            assertTrue(
                vm.draft(ChatViewModel.NEW_CONVERSATION_ID).pendingAttachments
                    .none { it.localPath == sharedFile.canonicalPath },
            )
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(sharedFile.canonicalPath)
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `failed text copy settles import ownership and releases the registry lease`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        val storeDir = Files.createTempDirectory("fusion-handoff-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val deleted = mutableListOf<String>()
            val toasts = mutableListOf<String>()

            val outcome = failingCoordinatorFor(attachmentDir, deleted, toasts).handle(
                shareEvent("handoff-copy-fail", "복사가 실패할 긴 첨부 텍스트입니다. ".repeat(80)),
                vm,
            )

            assertEquals(HandoffImportOutcome.FAILED_IMPORT, outcome)
            assertTrue(toasts.isNotEmpty())
            assertTrue(toasts.last().contains("가져오지 못했습니다"))
            // The ownership gap under regression: import ownership must be
            // cleared even though the copy already failed.
            assertNull(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).importOwnership)
            assertNull(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).activeSubmissionToken)
            // No generated/orphan managed file, no deletion needed.
            assertTrue(attachmentFiles(attachmentDir).isEmpty())
            assertTrue(deleted.isEmpty())
            assertFalse(vm.state(ChatViewModel.NEW_CONVERSATION_ID).isGenerating)

            // The registry lease must be free: a fresh import can begin.
val nextOwner = (vm.tryBeginAttachmentImport(ChatViewModel.NEW_CONVERSATION_ID) as com.projectnuke.fusion.chat.AttachmentImportAdmission.Accepted).owner
            assertTrue(vm.settleAttachmentImport(ChatViewModel.NEW_CONVERSATION_ID, nextOwner.token))

            // A normal submission can still be admitted (no
            // MANUAL_COMPACTION_LEASE_HELD from a leaked handoff owner).
            val admission = vm.beginSubmissionResult(ChatViewModel.NEW_CONVERSATION_ID)
            assertTrue(admission is SubmissionAdmissionResult.Accepted)
            val accepted = admission as SubmissionAdmissionResult.Accepted
            assertTrue(vm.settleSubmissionOwner(ChatViewModel.NEW_CONVERSATION_ID, accepted.snapshot.token))

            // A subsequent attachment import through the healthy flow succeeds.
            val retry = coordinatorFor(attachmentDir, deleted, mutableListOf()).handle(
                shareEvent("handoff-copy-retry", "복사가 실패할 긴 첨부 텍스트입니다. ".repeat(80)),
                vm,
            )
            assertEquals(HandoffImportOutcome.IMPORTED, retry)
            val files = managedFiles(attachmentDir)
            assertEquals(1, files.size)
            assertEquals(
                listOf(
                    PendingAttachmentIdentity(
                        name = HandoffAttachmentImporter.DISPLAY_NAME,
                        mimeType = HandoffAttachmentImporter.MIME_TYPE_TEXT_PLAIN,
                        localPath = files.single().canonicalPath,
                    ),
                ),
                vm.draft(ChatViewModel.NEW_CONVERSATION_ID).pendingAttachments,
            )
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

@Test
    fun `cancellation during attachment import cleans up and settles the owner`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-handoff-attachments").toFile()
        try {
            val copyBegan = CompletableDeferred<Unit>()
            val copyRelease = CompletableDeferred<Unit>()
            val cancellation = CompletableDeferred<Throwable?>()
            val vm = ChatViewModel.forTesting(SyncFakeDraftStore()).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val deleted = mutableListOf<String>()
            val toasts = mutableListOf<String>()
            val source = "취소되는 긴 첨부 텍스트입니다. ".repeat(80)

            val coordinator = TextHandoffCoordinator(
                attachmentRoot = attachmentDir,
                coordinatorFactory = { text ->
                    AttachmentImportCoordinator(
                        attachmentRoot = attachmentDir,
                        inputFactory = { _ ->
                            GatedTextInputStream(
                                payload = text.toByteArray(Charsets.UTF_8),
                                began = copyBegan,
                                release = copyRelease,
                            )
                        },
                        usableSpace = handoffTestUsableSpace,
                    )
                },
                deletePendingFile = deletePendingFileFor(attachmentDir.canonicalPath, deleted),
                releaseRemovedDraftPath = releaseRemovedDraftPathFor(attachmentDir, mutableListOf()),
                notify = { toasts += it },
            )

            val worker = launch {
                try {
                    coordinator.handle(shareEvent("handoff-cancel-attach", source), vm)
                    cancellation.complete(null)
                } catch (c: CancellationException) {
                    cancellation.complete(c)
                    throw c
                }
            }
            // Wait until the worker is deterministically blocked inside the
            // real-IO attachment copy (first read already blocked on the gate).
            val beginDeadline = System.currentTimeMillis() + 5_000
            while (!copyBegan.isCompleted && !worker.isCompleted && System.currentTimeMillis() < beginDeadline) {
                advanceUntilIdle()
            }
            assertTrue("attachment copy never began within the bounded wait", copyBegan.isCompleted)
            worker.cancel()
            copyRelease.complete(Unit)
            val doneDeadline = System.currentTimeMillis() + 5_000
            while (!cancellation.isCompleted && !worker.isCompleted && System.currentTimeMillis() < doneDeadline) {
                advanceUntilIdle()
            }
            // Cancellation propagated like the FusionApp effect rethrow, and the
            // NonCancellable cleanup settled the owner before rethrowing.
            assertTrue("cancellation was not observed by the copy cleanup", cancellation.isCompleted)
            assertTrue(worker.isCancelled)
            assertTrue(cancellation.await() is CancellationException)
            assertNull(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).importOwnership)
            assertNull(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).activeSubmissionToken)
            assertFalse(vm.state(ChatViewModel.NEW_CONVERSATION_ID).isGenerating)
            // No orphan managed file (.part included): copy and cleanup remove it.
            assertTrue(attachmentFiles(attachmentDir).isEmpty())
            assertTrue(toasts.isEmpty())

            // The registry lease is released after the settled cleanup: a fresh
            // import can begin immediately.
            val nextOwner = (vm.tryBeginAttachmentImport(ChatViewModel.NEW_CONVERSATION_ID) as com.projectnuke.fusion.chat.AttachmentImportAdmission.Accepted).owner
            assertTrue(vm.settleAttachmentImport(ChatViewModel.NEW_CONVERSATION_ID, nextOwner.token))
        } finally {
            attachmentDir.deleteRecursively()
        }
    }

    // ---- durable-store fixtures -------------------------------------------

    /** Allows a deterministic failure at the prepared-handoff adoption write. */
    private class FailingFirstInlineWriteStore(
        dir: File,
        attachmentRoot: File,
    ) : PersistentComposerDraftStore(
        file = File(dir, PersistentComposerDraftStore.FILE_NAME),
        resolveManagedAttachment = { path ->
            File(path).takeIf {
                it.parentFile?.canonicalPath == attachmentRoot.canonicalPath && it.isFile
            }
        },
        registerPendingAttachment = { it.absolutePath },
    ) {
        private var failInlineWrite = false

        fun failNextInlineWrite() {
            failInlineWrite = true
        }

        override suspend fun write(drafts: Map<Long, ComposerDraftState>): Boolean {
            val hasText = drafts[ChatViewModel.NEW_CONVERSATION_ID]?.rawInput?.isNotEmpty() == true
            if (hasText && failInlineWrite) {
                failInlineWrite = false
                return false
            }
            return super.write(drafts)
        }
    }

    /**
     * Fully synchronous store for deterministic cancellation tests: the real
     * store hops to Dispatchers.IO, which would race the test scheduler.
     * Durability assertions in the gated test do not re-load from disk.
     */
    private open class SyncFakeDraftStore : PersistentComposerDraftStore(
        file = File("unused-composer-draft-store.json"),
        resolveManagedAttachment = { null },
        registerPendingAttachment = { it.absolutePath },
    ) {
        override suspend fun write(drafts: Map<Long, ComposerDraftState>): Boolean = true

        override suspend fun write(writeId: Long, drafts: Map<Long, ComposerDraftState>): Boolean = true
    }

/**
     * Blocks the first read on a real IO thread until [release] completes, so
     * the worker is deterministically suspended inside the attachment copy when
     * the test cancels it; the copy's ensureActive checkpoint then observes the
     * cancellation, rethrows it, and the importer's NonCancellable cleanup
     * settles the owner (no generated file, no orphan).
     */
    private class GatedTextInputStream(
        private val payload: ByteArray,
        private val began: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : java.io.InputStream() {
        private var offset = 0
        private var firstReadHandled = false

        override fun read(): Int {
            if (!firstReadHandled) {
                firstReadHandled = true
                began.complete(Unit)
                while (!release.isCompleted) {
                    Thread.sleep(1)
                }
            }
            if (offset >= payload.size) return -1
            return payload[offset++].toInt() and 0xFF
        }
    }

    /** Durable adoption of the imported attachment (the write that contains a
     *  pending attachment on the fresh draft) fails; later writes succeed again
     *  so the failure-safety path can settle the import owner and release its
     *  registry lease. This targets the adoption specifically rather than any
     *  incidental debounced persist. */
    private class FailingAfterFirstWriteStore(dir: File) : PersistentComposerDraftStore(
        file = File(dir, PersistentComposerDraftStore.FILE_NAME),
        resolveManagedAttachment = { null },
        registerPendingAttachment = { it.absolutePath },
    ) {
        private var adoptionSeen = false

        override suspend fun write(drafts: Map<Long, ComposerDraftState>): Boolean {
            val hasPendingAttachment = drafts.values.any { it.pendingAttachments.isNotEmpty() }
            if (hasPendingAttachment && !adoptionSeen) {
                adoptionSeen = true
                return false
            }
            return super.write(drafts)
        }
    }
}
