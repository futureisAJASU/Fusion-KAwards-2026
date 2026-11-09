package com.projectnuke.fusion.handoff

import androidx.lifecycle.viewModelScope
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.cancelForTest
import com.projectnuke.fusion.chat.ComposerDraftState
import com.projectnuke.fusion.chat.MainDispatcherIsolationRule
import com.projectnuke.fusion.chat.PendingAttachmentIdentity
import com.projectnuke.fusion.chat.PersistentComposerDraftStore
import com.projectnuke.fusion.retrieval.LocalRagInputPolicy
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// v2 document handoff lifecycle: fresh chat before import, managed copies,
// durable identities, bounded multi-doc, no orphans, no generation.
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentHandoffCoordinatorTest {
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
        contents: Map<String, ByteArray>,
        metadata: Map<String, SharedDocumentMetadata?>,
        deleted: MutableList<String>,
        toasts: MutableList<String>,
        releasedOldPaths: MutableList<String> = mutableListOf(),
    ): DocumentHandoffCoordinator {
        val rootCanonical = root.canonicalPath
        return DocumentHandoffCoordinator(
            attachmentRoot = root,
            coordinatorProvider = {
                AttachmentImportCoordinator(
                    attachmentRoot = root,
                    inputFactory = { source ->
                        contents[source]?.let { ByteArrayInputStream(it) }
                    },
                    usableSpace = handoffTestUsableSpace,
                )
            },
            metadataProvider = { uri -> metadata[uri] ?: SharedDocumentMetadata(null, null, null) },
            deletePendingFile = { path ->
                path?.let {
                    deleted += it
                    val file = File(it)
                    if (file.parentFile?.canonicalPath == rootCanonical) {
                        if (file.exists()) file.delete() else true
                    } else false
                } ?: false
            },
            releaseRemovedDraftPath = releaseRemovedDraftPathFor(root, releasedOldPaths),
            notify = { toasts += it },
        )
    }

    private fun docEvent(id: String, uris: List<String>, mime: String? = "text/plain") =
        ExternalDocumentHandoff(
            id = id,
            uriStrings = uris,
            declaredMimeType = mime,
            source = if (uris.size > 1) ExternalDocumentHandoffSource.SHARE_MULTIPLE
            else ExternalDocumentHandoffSource.SHARE_STREAM,
        )

    private fun pdfCoordinatorFor(
        root: File,
        contents: Map<String, ByteArray>,
        metadata: Map<String, SharedDocumentMetadata?>,
        deleted: MutableList<String>,
        toasts: MutableList<String>,
        admissionByDisplayName: Map<String, com.projectnuke.fusion.retrieval.DocumentAdmissionPolicy.DocumentAdmissionResult>,
    ): DocumentHandoffCoordinator {
        val rootCanonical = root.canonicalPath
        return DocumentHandoffCoordinator(
            attachmentRoot = root,
            coordinatorProvider = {
                AttachmentImportCoordinator(
                    attachmentRoot = root,
                    inputFactory = { source ->
                        contents[source]?.let { ByteArrayInputStream(it) }
                    },
                    usableSpace = handoffTestUsableSpace,
                )
            },
            metadataProvider = { uri -> metadata[uri] ?: SharedDocumentMetadata(null, null, null) },
            deletePendingFile = { path ->
                path?.let {
                    deleted += it
                    val file = File(it)
                    if (file.parentFile?.canonicalPath == rootCanonical) {
                        if (file.exists()) file.delete() else true
                    } else false
                } ?: false
            },
            releaseRemovedDraftPath = releaseRemovedDraftPathFor(root, mutableListOf()),
            notify = { toasts += it },
            admission = { displayName, mimeType, localPath ->
                // Managed disk names are UUID-prefixed; the DISPLAY name is
                // the stable identity for the admission map.
                admissionByDisplayName[displayName]
                    ?: com.projectnuke.fusion.retrieval.DocumentAdmissionPolicy.admit(
                        path = localPath,
                        mimeType = mimeType,
                        fileName = displayName,
                        pdfAdmission = null,
                        strictTextLimit = true,
                    )
            },
        )
    }

    private fun attachmentFiles(root: File): List<File> =
        root.listFiles { f -> f.isFile }.orEmpty().toList()

    @Test
    fun `single stream accepted into fresh chat composer stays empty no generation`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-doc-handoff").toFile()
        val storeDir = Files.createTempDirectory("fusion-doc-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val deleted = mutableListOf<String>()
            val toasts = mutableListOf<String>()
            val uri = "content://x/notes.txt"
            val bytes = "문서 본문입니다.".toByteArray(Charsets.UTF_8)
            val epochBefore = vm.newChatEpoch.value

            val outcome = coordinatorFor(
                attachmentDir,
                mapOf(uri to bytes),
                mapOf(uri to SharedDocumentMetadata("notes.txt", bytes.size.toLong(), "text/plain")),
                deleted, toasts,
            ).handle(docEvent("doc-1", listOf(uri)), vm)

            assertEquals(HandoffImportOutcome.IMPORTED, outcome)
            assertTrue(vm.newChatEpoch.value > epochBefore)
            assertEquals(ChatViewModel.NEW_CONVERSATION_ID, vm.currentConversationId.value)
            // Fresh chat happened before import: composer empty, managed file adopted.
            assertEquals("", vm.draft(ChatViewModel.NEW_CONVERSATION_ID).rawInput)
            val draft = vm.draft(ChatViewModel.NEW_CONVERSATION_ID)
            assertEquals(1, draft.pendingAttachments.size)
            val identity = draft.pendingAttachments.single()
            assertTrue(File(identity.localPath).parentFile?.canonicalPath == attachmentDir.canonicalPath)
            assertTrue(File(identity.localPath).isFile)
            assertEquals(bytes.toString(Charsets.UTF_8), File(identity.localPath).readText(Charsets.UTF_8))
            // Durable: registered pending, no generation state.
            assertTrue(AttachmentStorageManager.isPendingAttachmentRegistered(File(identity.localPath).canonicalPath))
            assertNull(vm.state(ChatViewModel.NEW_CONVERSATION_ID).activeRequestId)
            assertFalse(vm.state(ChatViewModel.NEW_CONVERSATION_ID).isGenerating)
            assertNull(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).importOwnership)
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `multiple documents accepted up to five excess bounded with notice`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-doc-handoff").toFile()
        val storeDir = Files.createTempDirectory("fusion-doc-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val deleted = mutableListOf<String>()
            val toasts = mutableListOf<String>()
            val uris = (1..7).map { "content://x/doc$it.txt" }
            val contents = uris.associateWith { "내용 $it".toByteArray(Charsets.UTF_8) }
            val metas = uris.associateWith { uri ->
                SharedDocumentMetadata(
                    uri.substringAfterLast('/'),
                    contents[uri]!!.size.toLong(),
                    "text/plain",
                )
            }

            val outcome = coordinatorFor(attachmentDir, contents, metas, deleted, toasts)
                .handle(docEvent("doc-multi", uris), vm)

            assertEquals(HandoffImportOutcome.PARTIALLY_IMPORTED, outcome)
            // Maximum accepted is 5, deterministic first-5 order.
            assertEquals(5, vm.draft(ChatViewModel.NEW_CONVERSATION_ID).pendingAttachments.size)
            assertEquals(5, attachmentFiles(attachmentDir).size)
            // One bounded Korean notice for skipped excess.
            assertTrue(toasts.isNotEmpty())
            assertTrue(toasts.last().contains("한 번에 최대 5개"))
            assertTrue(toasts.last().contains("2개는 처리하지 않았어요"))
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `restored twenty document event with five URIs adopts five and reports partial`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-doc-handoff-restored-subset").toFile()
        val storeDir = Files.createTempDirectory("fusion-doc-store-restored-subset").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val uris = listOf("content://x/a.txt", "content://x/b.txt", "content://x/c.txt", "content://x/d.txt", "content://x/e.txt")
            val contents = uris.associateWith { "body:$it".toByteArray(Charsets.UTF_8) }
            val metadata = uris.associateWith { uri ->
                SharedDocumentMetadata(uri.substringAfterLast('/'), contents.getValue(uri).size.toLong(), "text/plain")
            }
            val notices = mutableListOf<String>()
            val handoff = docEvent("doc-restored-subset", uris).copy(totalDistinctUriCount = 20)

            val outcome = coordinatorFor(attachmentDir, contents, metadata, mutableListOf(), notices).handle(handoff, vm)

            assertEquals(HandoffImportOutcome.PARTIALLY_IMPORTED, outcome)
            assertEquals(5, vm.draft(ChatViewModel.NEW_CONVERSATION_ID).pendingAttachments.size)
            assertTrue("notice must report the sender's original total", notices.any { it.contains("20") })
            assertEquals("PARTIALLY_IMPORTED", outcome.receiptValue())
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `unsupported mime rejected and never labeled readable`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-doc-handoff").toFile()
        val storeDir = Files.createTempDirectory("fusion-doc-store").toFile()
        try {
            val oldFile = File(attachmentDir, "old.txt").apply { writeText("old") }
            val store = realStore(storeDir, attachmentDir)
            assertTrue(store.write(mapOf(
                ChatViewModel.NEW_CONVERSATION_ID to ComposerDraftState(
                    rawInput = "기존 문서 초안",
                    pendingAttachments = listOf(
                        PendingAttachmentIdentity("old.txt", "text/plain", oldFile.canonicalPath),
                    ),
                    version = 2L,
                ),
            )))
            val vm = ChatViewModel.forTesting(store).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val before = vm.draft(ChatViewModel.NEW_CONVERSATION_ID)
            val epochBefore = vm.newChatEpoch.value
            val deleted = mutableListOf<String>()
            val toasts = mutableListOf<String>()
            val uri = "content://x/photo.png"
            val outcome = coordinatorFor(
                attachmentDir,
                mapOf(uri to ByteArray(10)),
                mapOf(uri to SharedDocumentMetadata("photo.png", 10L, "image/png")),
                deleted, toasts,
            ).handle(docEvent("doc-bad", listOf(uri), "image/png"), vm)

            assertEquals(HandoffImportOutcome.FAILED_IMPORT, outcome)
            assertEquals(before, vm.draft(ChatViewModel.NEW_CONVERSATION_ID))
            assertEquals(epochBefore, vm.newChatEpoch.value)
            assertEquals(listOf(oldFile.canonicalPath), attachmentFiles(attachmentDir).map { it.canonicalPath })
            assertTrue(deleted.isEmpty())
            assertTrue(toasts.any { it.contains("문서 1개를 새 채팅에 저장하지 못했어요") && it.contains("지원하지 않는 문서 1개") })
            // Unsupported docs are never labeled local-readable.
            assertFalse(LocalRagInputPolicy.isSupported("image/png", "photo.png"))
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `one bad uri cannot create orphan valid docs remain`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-doc-handoff").toFile()
        val storeDir = Files.createTempDirectory("fusion-doc-store").toFile()
        val oldFile = File(attachmentDir, "old-pending.txt").apply { writeText("old") }
        // Production registration: a durable draft's pending attachment
        // owns a process pending-copy claim on the file.
        AttachmentStorageManager.registerPendingAttachment(oldFile)
        try {
            val store = realStore(storeDir, attachmentDir)
            assertTrue(store.write(mapOf(
                ChatViewModel.NEW_CONVERSATION_ID to ComposerDraftState(
                    rawInput = "기존 초안",
                    pendingAttachments = listOf(
                        PendingAttachmentIdentity("old-pending.txt", "text/plain", oldFile.canonicalPath),
                    ),
                    version = 1L,
                ),
            )))
            val vm = ChatViewModel.forTesting(store).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val deleted = mutableListOf<String>()
            val released = mutableListOf<String>()
            val toasts = mutableListOf<String>()
            val good = "content://x/good.txt"
            val bad = "content://x/missing.txt"
            val goodBytes = "좋은 문서".toByteArray(Charsets.UTF_8)

            val outcome = coordinatorFor(
                attachmentDir,
                mapOf(good to goodBytes),
                mapOf(
                    good to SharedDocumentMetadata("good.txt", goodBytes.size.toLong(), "text/plain"),
                    bad to SharedDocumentMetadata("missing.txt", 10L, "text/plain"),
                ),
                deleted, toasts, released,
            ).handle(docEvent("doc-partial", listOf(good, bad)), vm)

            assertEquals(HandoffImportOutcome.PARTIALLY_IMPORTED, outcome)
            // Valid doc remains; bad one truthfully counted, no orphan.
            assertEquals(1, vm.draft(ChatViewModel.NEW_CONVERSATION_ID).pendingAttachments.size)
            assertEquals(1, attachmentFiles(attachmentDir).size)
            assertTrue(released.contains(oldFile.canonicalPath))
            assertFalse(oldFile.exists())
            assertEquals("", vm.draft(ChatViewModel.NEW_CONVERSATION_ID).rawInput)
            assertTrue(toasts.any { it.contains("가져오지 못한") })
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(oldFile.canonicalPath)
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `identical uri list different ids treated as separate events`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-doc-handoff").toFile()
        val storeDir = Files.createTempDirectory("fusion-doc-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val uri = "content://x/same.txt"
            val bytes = "같은 내용".toByteArray(Charsets.UTF_8)
            val first = coordinatorFor(
                attachmentDir,
                mapOf(uri to bytes),
                mapOf(uri to SharedDocumentMetadata("same.txt", bytes.size.toLong(), "text/plain")),
                mutableListOf(), mutableListOf(),
            ).handle(docEvent("evt-a", listOf(uri)), vm)
            val firstPath = vm.draft(ChatViewModel.NEW_CONVERSATION_ID).pendingAttachments.single().localPath
            val second = coordinatorFor(
                attachmentDir,
                mapOf(uri to bytes),
                mapOf(uri to SharedDocumentMetadata("same.txt", bytes.size.toLong(), "text/plain")),
                mutableListOf(), mutableListOf(),
            ).handle(docEvent("evt-b", listOf(uri)), vm)
            assertEquals(HandoffImportOutcome.IMPORTED, first)
            assertEquals(HandoffImportOutcome.IMPORTED, second)
            assertTrue(vm.newChatEpoch.value >= 2L)
            // Second fresh reset replaced the first pending file (no accumulation).
            assertEquals(1, attachmentFiles(attachmentDir).size)
            assertTrue(firstPath != vm.draft(ChatViewModel.NEW_CONVERSATION_ID).pendingAttachments.single().localPath)
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `vanished uri permission fails boundedly with no orphan and settled owner`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-doc-handoff").toFile()
        val storeDir = Files.createTempDirectory("fusion-doc-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val deleted = mutableListOf<String>()
            val toasts = mutableListOf<String>()
            val uri = "content://x/gone.txt"
            // No contents entry: inputFactory returns null (permission gone).
            val outcome = coordinatorFor(
                attachmentDir, emptyMap(),
                mapOf(uri to SharedDocumentMetadata("gone.txt", 10L, "text/plain")),
                deleted, toasts,
            ).handle(docEvent("doc-gone", listOf(uri)), vm)

            assertEquals(HandoffImportOutcome.FAILED_IMPORT, outcome)
            assertTrue(attachmentFiles(attachmentDir).isEmpty())
            assertNull(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).importOwnership)
            assertTrue(toasts.isNotEmpty())
            // Lease released: next import can begin.
            val next = (vm.tryBeginAttachmentImport(ChatViewModel.NEW_CONVERSATION_ID) as com.projectnuke.fusion.chat.AttachmentImportAdmission.Accepted).owner
            assertTrue(vm.settleAttachmentImport(ChatViewModel.NEW_CONVERSATION_ID, next.token))
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `cancellation settles ownership with no orphan`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-doc-handoff").toFile()
        val storeDir = Files.createTempDirectory("fusion-doc-store").toFile()
        try {
            val copyBegan = CompletableDeferred<Unit>()
            val copyRelease = CompletableDeferred<Unit>()
            val oldFile = File(attachmentDir, "old-pending.txt").apply { writeText("old") }
            val store = realStore(storeDir, attachmentDir)
            assertTrue(store.write(mapOf(
                ChatViewModel.NEW_CONVERSATION_ID to ComposerDraftState(
                    rawInput = "기존 초안",
                    pendingAttachments = listOf(
                        PendingAttachmentIdentity("old-pending.txt", "text/plain", oldFile.canonicalPath),
                    ),
                    version = 1L,
                ),
            )))
            val vm = ChatViewModel.forTesting(store).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val before = vm.draft(ChatViewModel.NEW_CONVERSATION_ID)
            val epochBefore = vm.newChatEpoch.value
            val uri = "content://x/big.txt"
            val payload = "취소 문서 내용 ".repeat(200).toByteArray(Charsets.UTF_8)
            val coordinator = DocumentHandoffCoordinator(
                attachmentRoot = attachmentDir,
                coordinatorProvider = {
                    AttachmentImportCoordinator(
                        attachmentRoot = attachmentDir,
                        inputFactory = {
                            GatedStream(payload, copyBegan, copyRelease)
                        },
                        usableSpace = handoffTestUsableSpace,
                    )
                },
                metadataProvider = { SharedDocumentMetadata("big.txt", payload.size.toLong(), "text/plain") },
                deletePendingFile = { path ->
                    path?.let {
                        val f = File(it)
                        if (f.parentFile?.canonicalPath == attachmentDir.canonicalPath) {
                            if (f.exists()) f.delete() else true
                        } else false
                    } ?: false
                },
                releaseRemovedDraftPath = releaseRemovedDraftPathFor(attachmentDir, mutableListOf()),
                notify = {},
            )
            val worker = launch {
                try {
                    coordinator.handle(docEvent("doc-cancel", listOf(uri)), vm)
                } catch (c: CancellationException) {
                    throw c
                }
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (!copyBegan.isCompleted && !worker.isCompleted && System.currentTimeMillis() < deadline) {
                advanceUntilIdle()
            }
            assertTrue(copyBegan.isCompleted)
            worker.cancel()
            copyRelease.complete(Unit)
            val done = System.currentTimeMillis() + 5_000
            while (!worker.isCompleted && System.currentTimeMillis() < done) {
                advanceUntilIdle()
            }
            assertTrue(worker.isCancelled)
            assertEquals(before, vm.draft(ChatViewModel.NEW_CONVERSATION_ID))
            assertEquals(epochBefore, vm.newChatEpoch.value)
            assertTrue(oldFile.isFile)
            assertNull(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).importOwnership)
            assertEquals(listOf(oldFile.canonicalPath), attachmentFiles(attachmentDir).map { it.canonicalPath })
            val next = (vm.tryBeginAttachmentImport(ChatViewModel.NEW_CONVERSATION_ID) as com.projectnuke.fusion.chat.AttachmentImportAdmission.Accepted).owner
            assertTrue(vm.settleAttachmentImport(ChatViewModel.NEW_CONVERSATION_ID, next.token))
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `old history-shared draft source survives document handoff replacement`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-doc-handoff-shared").toFile()
        val storeDir = Files.createTempDirectory("fusion-doc-store-shared").toFile()
        // A historical message owns F; the old NEW_CHAT draft references the
        // SAME physical file (pending-registered as a shared copy).
        val sharedFile = File(attachmentDir, "history-shared.txt").apply { writeText("historical") }
        AttachmentStorageManager.registerPendingAttachment(sharedFile)
        try {
            val store = realStore(storeDir, attachmentDir)
            assertTrue(store.write(mapOf(
                ChatViewModel.NEW_CONVERSATION_ID to ComposerDraftState(
                    rawInput = "기존 초안",
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
            val uri = "content://x/new.txt"
            val bytes = "새 문서".toByteArray(Charsets.UTF_8)

            val outcome = DocumentHandoffCoordinator(
                attachmentRoot = attachmentDir,
                coordinatorProvider = {
                    AttachmentImportCoordinator(
                        attachmentRoot = attachmentDir,
                        inputFactory = { source ->
                            if (source == uri) ByteArrayInputStream(bytes) else null
                        },
                        usableSpace = handoffTestUsableSpace,
                    )
                },
                metadataProvider = { SharedDocumentMetadata("new.txt", bytes.size.toLong(), "text/plain") },
                deletePendingFile = { path ->
                    path?.let {
                        deleted += it
                        val f = File(it)
                        if (f.parentFile?.canonicalPath == attachmentDir.canonicalPath) {
                            if (f.exists()) f.delete() else true
                        } else false
                    } ?: false
                },
                releaseRemovedDraftPath = releaseRemovedDraftPathFor(
                    attachmentDir,
                    released,
                    messageAuthority = AttachmentReferenceAuthority.Complete(setOf(sharedFile.canonicalPath)),
                ),
                notify = { toasts += it },
            ).handle(docEvent("evt-doc-old-shared", listOf(uri)), vm)

            assertEquals(HandoffImportOutcome.IMPORTED, outcome)
            // The removed old-draft reference settled through the
            // authority-aware release, never the destructive staging delete.
            assertTrue(released.contains(sharedFile.canonicalPath))
            assertTrue(deleted.none { it == sharedFile.canonicalPath })
            assertTrue(sharedFile.isFile)
            assertEquals("historical", sharedFile.readText(Charsets.UTF_8))
            // The new document was adopted alongside the preserved source.
            val draftPaths = vm.draft(ChatViewModel.NEW_CONVERSATION_ID).pendingAttachments.map { it.localPath }
            assertTrue(draftPaths.none { it == sharedFile.canonicalPath })
            assertEquals(1, draftPaths.size)
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(sharedFile.canonicalPath)
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    private class GatedStream(
        private val payload: ByteArray,
        private val began: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : java.io.InputStream() {
        private var offset = 0
        private var handled = false
        override fun read(): Int {
            if (!handled) {
                handled = true
                began.complete(Unit)
                while (!release.isCompleted) Thread.sleep(1)
            }
            if (offset >= payload.size) return -1
            return payload[offset++].toInt() and 0xFF
        }
    }

    // ---------------- PDF document handoff (Document Intelligence) --------

    @Test
    fun `mixed share imports readable pdf with text and rejects scanned pdf truthfully`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-doc-handoff-pdf").toFile()
        val storeDir = Files.createTempDirectory("fusion-doc-store-pdf").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val toasts = mutableListOf<String>()
            val deleted = mutableListOf<String>()
            val pdfUri = "content://x/paper.pdf"
            val scannedUri = "content://x/scanned.pdf"
            val mdUri = "content://x/notes.md"
            val bytes = "pdf-data".toByteArray(Charsets.UTF_8)
            // Resolve admission by the canonical managed path that the copy
            // wrote (root/displayName).
            val admission = mapOf(
                "paper.pdf" to com.projectnuke.fusion.retrieval.DocumentAdmissionPolicy.DocumentAdmissionResult.Accepted,
                "scanned.pdf" to com.projectnuke.fusion.retrieval.DocumentAdmissionPolicy.DocumentAdmissionResult.Rejected(
                    com.projectnuke.fusion.retrieval.DocumentExtractionStatus.IMAGE_ONLY_NO_OCR,
                ),
            )
            val outcome = pdfCoordinatorFor(
                attachmentDir,
                mapOf(pdfUri to bytes, scannedUri to bytes, mdUri to bytes),
                mapOf(
                    pdfUri to SharedDocumentMetadata("paper.pdf", bytes.size.toLong(), "application/pdf"),
                    scannedUri to SharedDocumentMetadata("scanned.pdf", bytes.size.toLong(), "application/pdf"),
                    mdUri to SharedDocumentMetadata("notes.md", bytes.size.toLong(), "text/markdown"),
                ),
                deleted, toasts, admission,
            ).handle(
                docEvent("evt-mixed", listOf(pdfUri, scannedUri, mdUri), mime = "application/pdf"),
                vm,
            )
            assertEquals(HandoffImportOutcome.PARTIALLY_IMPORTED, outcome)
            val names = vm.draft(ChatViewModel.NEW_CONVERSATION_ID).pendingAttachments.map { it.name }.sorted()
            assertEquals(listOf("notes.md", "paper.pdf"), names)
            // The unusable scanned PDF left no orphan.
            assertEquals(2, attachmentFiles(attachmentDir).size)
            assertTrue(attachmentFiles(attachmentDir).none { it.name.contains("scanned") })
            assertTrue(toasts.any { it.contains("가져오지 못한") })
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `partial chat outcome survives process recreation and replay without recopy`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-doc-partial-replay").toFile()
        val storeDir = Files.createTempDirectory("fusion-doc-partial-store").toFile()
        try {
            val uri = "content://x/accepted.txt"
            val missingUri = "content://x/missing.txt"
            val payload = "읽을 수 있는 문서".toByteArray(Charsets.UTF_8)
            val first = ChatViewModel.forTesting(realStore(storeDir, attachmentDir)).also {
                viewModel = it
            }
            assertTrue(first.draftMachine.awaitHydration())
            val firstNotices = mutableListOf<String>()
            val handoff = docEvent("evt-partial-process-death", listOf(uri, missingUri))
            val result = coordinatorFor(
                attachmentDir,
                mapOf(uri to payload),
                mapOf(
                    uri to SharedDocumentMetadata("accepted.txt", payload.size.toLong(), "text/plain"),
                    missingUri to SharedDocumentMetadata("missing.txt", 12L, "text/plain"),
                ),
                mutableListOf(),
                firstNotices,
            ).handle(handoff, first)

            assertEquals(HandoffImportOutcome.PARTIALLY_IMPORTED, result)
            val storedPaths = first.draft(ChatViewModel.NEW_CONVERSATION_ID)
                .pendingAttachments.map { it.localPath }
            val storedNotice = first.draft(ChatViewModel.NEW_CONVERSATION_ID)
                .lastExternalHandoffPartialNotice
            assertEquals(1, storedPaths.size)
            assertTrue(storedNotice.orEmpty().contains("가져오지 못한 문서 1개"))

            // Simulate process death after the durable composer commit but
            // before the separate Room receipt settles. The draft JSON is the
            // adoption commit and must retain its truthful partial outcome.
            first.viewModelScope.cancel()
            val second = ChatViewModel.forTesting(realStore(storeDir, attachmentDir)).also {
                viewModel = it
            }
            assertTrue(second.draftMachine.awaitHydration())
            val replayNotices = mutableListOf<String>()
            var sourceOpened = false
            val replayCoordinator = DocumentHandoffCoordinator(
                attachmentRoot = attachmentDir,
                coordinatorProvider = {
                    AttachmentImportCoordinator(
                        attachmentRoot = attachmentDir,
                        inputFactory = { sourceOpened = true; null },
                        usableSpace = handoffTestUsableSpace,
                    )
                },
                metadataProvider = { SharedDocumentMetadata("missing.txt", 12L, "text/plain") },
                deletePendingFile = { false },
                releaseRemovedDraftPath = { RemovedAttachmentReleaseResult.ReferenceOnly },
                notify = replayNotices::add,
            )
            assertEquals(HandoffImportOutcome.PARTIALLY_IMPORTED, replayCoordinator.handle(handoff, second))
            assertFalse(sourceOpened)
            assertEquals(storedPaths, second.draft(ChatViewModel.NEW_CONVERSATION_ID)
                .pendingAttachments.map { it.localPath })
            assertEquals(1, attachmentFiles(attachmentDir).size)
            assertEquals(storedNotice, replayNotices.single())
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `single scanned pdf share fails entirely without orphan`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-doc-handoff-pdf2").toFile()
        val storeDir = Files.createTempDirectory("fusion-doc-store-pdf2").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val toasts = mutableListOf<String>()
            val deleted = mutableListOf<String>()
            val scannedUri = "content://x/scanned.pdf"
            val bytes = "pdf-data".toByteArray(Charsets.UTF_8)
            val admission = mapOf(
                "scanned.pdf" to com.projectnuke.fusion.retrieval.DocumentAdmissionPolicy.DocumentAdmissionResult.Rejected(
                    com.projectnuke.fusion.retrieval.DocumentExtractionStatus.IMAGE_ONLY_NO_OCR,
                ),
            )
            val outcome = pdfCoordinatorFor(
                attachmentDir,
                mapOf(scannedUri to bytes),
                mapOf(scannedUri to SharedDocumentMetadata("scanned.pdf", bytes.size.toLong(), "application/pdf")),
                deleted, toasts, admission,
            ).handle(docEvent("evt-scan", listOf(scannedUri), mime = "application/pdf"), vm)
            assertEquals(HandoffImportOutcome.FAILED_IMPORT, outcome)
            assertTrue(attachmentFiles(attachmentDir).isEmpty())
            assertNull(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).importOwnership)
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }
}
