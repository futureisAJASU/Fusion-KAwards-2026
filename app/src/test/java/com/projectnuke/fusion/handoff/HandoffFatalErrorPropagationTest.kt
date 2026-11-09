package com.projectnuke.fusion.handoff

import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.ComposerDraftState
import com.projectnuke.fusion.chat.MainDispatcherIsolationRule
import com.projectnuke.fusion.chat.PendingAttachmentIdentity
import com.projectnuke.fusion.chat.PersistentComposerDraftStore
import com.projectnuke.fusion.chat.cancelForTest
import com.projectnuke.fusion.util.AttachmentImportCoordinator
import com.projectnuke.fusion.util.AttachmentReferenceAuthority
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.util.RemovedAttachmentReleaseResult
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * Fatal-error policy for the external handoff stack: structural cancellation
 * rethrows, ordinary recoverable RuntimeException failures keep the existing
 * bounded FAILED_IMPORT/toast semantics, and fatal runtime Errors
 * (LinkageError, VirtualMachineError, ...) propagate — they are never
 * converted into an ordinary failure that also consumes the one-shot event.
 */
class HandoffFatalErrorPropagationTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule()

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

    private fun textCoordinator(
        attachmentDir: File,
        factory: (String) -> AttachmentImportCoordinator,
        deletePendingFile: suspend (String?) -> Boolean,
        releaseRemovedDraftPath: suspend (String?) -> RemovedAttachmentReleaseResult,
        toasts: MutableList<String>,
    ) = TextHandoffCoordinator(
        attachmentRoot = attachmentDir,
        coordinatorFactory = factory,
        deletePendingFile = deletePendingFile,
        releaseRemovedDraftPath = releaseRemovedDraftPath,
        notify = { toasts += it },
    )

    private fun longText() = "치명적 오류를 낼 충분히 긴 공유 텍스트입니다. ".repeat(80)

    // ---- P4.1 text handoff fatal preparation error propagates ---------------

    @Test
    fun `text handoff fatal preparation error propagates instead of failing import`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-fatal-text").toFile()
        val storeDir = Files.createTempDirectory("fusion-fatal-text-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val toasts = mutableListOf<String>()
            val coordinator = textCoordinator(
                attachmentDir = attachmentDir,
                factory = { _ -> throw LinkageError("broken text backend") },
                deletePendingFile = { false },
                releaseRemovedDraftPath = { RemovedAttachmentReleaseResult.ReferenceOnly },
                toasts = toasts,
            )

            val failure = runCatching {
                coordinator.handle(
                    ExternalTextHandoff(
                        id = "evt-fatal-prep",
                        source = ExternalTextHandoffSource.SHARE_TEXT,
                        text = longText(),
                    ),
                    vm,
                )
            }.exceptionOrNull()

            assertTrue("LinkageError must propagate, got: $failure", failure is LinkageError)
            // Not an ordinary FAILED_IMPORT: no bounded toast fired.
            assertTrue(toasts.isEmpty())
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // ---- P4.2 document metadata / admission fatal vs bounded ---------------

    @Test
    fun `document metadata fatal error propagates and ordinary runtime failure stays bounded`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-fatal-meta").toFile()
        val storeDir = Files.createTempDirectory("fusion-fatal-meta-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val uri = "content://x/doc.txt"
            val bytes = "문서 본문".toByteArray(Charsets.UTF_8)
            val toasts = mutableListOf<String>()

            val fatal = DocumentHandoffCoordinator(
                attachmentRoot = attachmentDir,
                coordinatorProvider = {
                    AttachmentImportCoordinator(
                        attachmentRoot = attachmentDir,
                        inputFactory = { ByteArrayInputStream(bytes) },
                        usableSpace = { Long.MAX_VALUE },
                    )
                },
                metadataProvider = { throw LinkageError("broken metadata backend") },
                deletePendingFile = { false },
                releaseRemovedDraftPath = { RemovedAttachmentReleaseResult.ReferenceOnly },
                notify = { toasts += it },
            )
            val failure = runCatching {
                fatal.handle(
                    ExternalDocumentHandoff(
                        id = "evt-fatal-meta",
                        uriStrings = listOf(uri),
                        declaredMimeType = "text/plain",
                        source = ExternalDocumentHandoffSource.SHARE_STREAM,
                    ),
                    vm,
                )
            }.exceptionOrNull()
            assertTrue("LinkageError must propagate, got: $failure", failure is LinkageError)

            // Ordinary RuntimeException from the metadata provider keeps the
            // existing bounded route: import proceeds with the URI-derived
            // fallback identity instead of crashing.
            val bounded = DocumentHandoffCoordinator(
                attachmentRoot = attachmentDir,
                coordinatorProvider = {
                    AttachmentImportCoordinator(
                        attachmentRoot = attachmentDir,
                        inputFactory = { ByteArrayInputStream(bytes) },
                        usableSpace = { Long.MAX_VALUE },
                    )
                },
                metadataProvider = { throw RuntimeException("provider unavailable") },
                deletePendingFile = { false },
                releaseRemovedDraftPath = { RemovedAttachmentReleaseResult.ReferenceOnly },
                notify = { toasts += it },
            )
            val outcome = bounded.handle(
                ExternalDocumentHandoff(
                    id = "evt-runtime-meta",
                    uriStrings = listOf(uri),
                    declaredMimeType = "text/plain",
                    source = ExternalDocumentHandoffSource.SHARE_STREAM,
                ),
                vm,
            )
            assertEquals(HandoffImportOutcome.IMPORTED, outcome)
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `document admission fatal error propagates and ordinary runtime failure rejects boundedly`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-fatal-admission").toFile()
        val storeDir = Files.createTempDirectory("fusion-fatal-admission-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val uri = "content://x/doc.txt"
            val bytes = "문서 본문".toByteArray(Charsets.UTF_8)
            val toasts = mutableListOf<String>()

            fun coordinator(admission: suspend (String, String?, String) -> com.projectnuke.fusion.retrieval.DocumentAdmissionPolicy.DocumentAdmissionResult) =
                DocumentHandoffCoordinator(
                    attachmentRoot = attachmentDir,
                    coordinatorProvider = {
                        AttachmentImportCoordinator(
                            attachmentRoot = attachmentDir,
                            inputFactory = { ByteArrayInputStream(bytes) },
                            usableSpace = handoffTestUsableSpace,
                        )
                    },
                    metadataProvider = { SharedDocumentMetadata("doc.txt", bytes.size.toLong(), "text/plain") },
                    deletePendingFile = { false },
                    releaseRemovedDraftPath = { RemovedAttachmentReleaseResult.ReferenceOnly },
                    notify = { toasts += it },
                    admission = admission,
                )

            val fatal = coordinator { _, _, _ -> throw LinkageError("broken admission backend") }
            val failure = runCatching {
                fatal.handle(
                    ExternalDocumentHandoff(
                        id = "evt-fatal-admission",
                        uriStrings = listOf(uri),
                        declaredMimeType = "text/plain",
                        source = ExternalDocumentHandoffSource.SHARE_STREAM,
                    ),
                    vm,
                )
            }.exceptionOrNull()
            assertTrue("LinkageError must propagate, got: $failure", failure is LinkageError)

            val bounded = coordinator { _, _, _ -> throw RuntimeException("extractor crashed") }
            val outcome = bounded.handle(
                ExternalDocumentHandoff(
                    id = "evt-runtime-admission",
                    uriStrings = listOf(uri),
                    declaredMimeType = "text/plain",
                    source = ExternalDocumentHandoffSource.SHARE_STREAM,
                ),
                vm,
            )
            assertEquals(HandoffImportOutcome.FAILED_IMPORT, outcome)
            assertTrue(toasts.isNotEmpty())
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // ---- P4.3 fatal from the durable adoption region propagates ----------------

    @Test
    fun `document handoff fatal error after committed adoption propagates without rollback`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-fatal-doc-adopt").toFile()
        val storeDir = Files.createTempDirectory("fusion-fatal-doc-adopt-store").toFile()
        val oldFile = File(attachmentDir, "old-pending.txt").apply { writeText("old") }
        try {
            AttachmentStorageManager.registerPendingAttachment(oldFile)
            val store = realStore(storeDir, attachmentDir)
            assertTrue(store.write(mapOf(
                ChatViewModel.NEW_CONVERSATION_ID to ComposerDraftState(
                    rawInput = "옛 초안",
                    pendingAttachments = listOf(
                        PendingAttachmentIdentity("old-pending.txt", "text/plain", oldFile.canonicalPath),
                    ),
                    version = 1L,
                ),
            )))
            val vm = ChatViewModel.forTesting(store).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val uri = "content://x/new.txt"
            val bytes = "새 문서".toByteArray(Charsets.UTF_8)
            val toasts = mutableListOf<String>()

            val coordinator = DocumentHandoffCoordinator(
                attachmentRoot = attachmentDir,
                coordinatorProvider = {
                    AttachmentImportCoordinator(
                        attachmentRoot = attachmentDir,
                        inputFactory = { ByteArrayInputStream(bytes) },
                        usableSpace = handoffTestUsableSpace,
                    )
                },
                metadataProvider = { SharedDocumentMetadata("new.txt", bytes.size.toLong(), "text/plain") },
                deletePendingFile = { false },
                releaseRemovedDraftPath = { throw LinkageError("broken release backend") },
                notify = { toasts += it },
            )

            val failure = runCatching {
                coordinator.handle(
                    ExternalDocumentHandoff(
                        id = "evt-fatal-doc-adopt",
                        uriStrings = listOf(uri),
                        declaredMimeType = "text/plain",
                        source = ExternalDocumentHandoffSource.SHARE_STREAM,
                    ),
                    vm,
                )
            }.exceptionOrNull()

            assertTrue("LinkageError must propagate, got: $failure", failure is LinkageError)
            // The durable adoption committed: its receipt keeps any replay
            // idempotent and no rollback was fabricated.
            assertEquals(
                "evt-fatal-doc-adopt",
                vm.draft(ChatViewModel.NEW_CONVERSATION_ID).lastExternalHandoffId,
            )
            assertTrue(
                vm.draft(ChatViewModel.NEW_CONVERSATION_ID).pendingAttachments
                    .none { it.localPath == oldFile.canonicalPath },
            )
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(oldFile.canonicalPath)
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // ---- P4.4 cleanup fatal error is not silently swallowed --------------------

    @Test
    fun `fatal staging cleanup error after a failed adoption propagates`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-fatal-cleanup").toFile()
        val storeDir = Files.createTempDirectory("fusion-fatal-cleanup-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(FailingAdoptionStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val toasts = mutableListOf<String>()
            val coordinator = textCoordinator(
                attachmentDir = attachmentDir,
                factory = { text ->
                    AttachmentImportCoordinator(
                        attachmentRoot = attachmentDir,
                        inputFactory = { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) },
                        usableSpace = handoffTestUsableSpace,
                    )
                },
                deletePendingFile = { throw LinkageError("broken cleanup backend") },
                releaseRemovedDraftPath = { RemovedAttachmentReleaseResult.ReferenceOnly },
                toasts = toasts,
            )

            val failure = runCatching {
                coordinator.handle(
                    ExternalTextHandoff(
                        id = "evt-fatal-cleanup",
                        source = ExternalTextHandoffSource.SHARE_TEXT,
                        text = longText(),
                    ),
                    vm,
                )
            }.exceptionOrNull()

            assertTrue("LinkageError must propagate, got: $failure", failure is LinkageError)
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `fatal old-draft release error propagates without fabricating adoption rollback`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-fatal-release").toFile()
        val storeDir = Files.createTempDirectory("fusion-fatal-release-store").toFile()
        val oldFile = File(attachmentDir, "old-pending.txt").apply { writeText("old") }
        try {
            AttachmentStorageManager.registerPendingAttachment(oldFile)
            val store = realStore(storeDir, attachmentDir)
            assertTrue(store.write(mapOf(
                ChatViewModel.NEW_CONVERSATION_ID to ComposerDraftState(
                    rawInput = "옛 초안",
                    pendingAttachments = listOf(
                        PendingAttachmentIdentity("old-pending.txt", "text/plain", oldFile.canonicalPath),
                    ),
                    version = 1L,
                ),
            )))
            val vm = ChatViewModel.forTesting(store).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val toasts = mutableListOf<String>()
            val coordinator = textCoordinator(
                attachmentDir = attachmentDir,
                factory = { text ->
                    AttachmentImportCoordinator(
                        attachmentRoot = attachmentDir,
                        inputFactory = { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) },
                        usableSpace = handoffTestUsableSpace,
                    )
                },
                deletePendingFile = { false },
                releaseRemovedDraftPath = { throw LinkageError("broken release backend") },
                toasts = toasts,
            )

            val failure = runCatching {
                coordinator.handle(
                    ExternalTextHandoff(
                        id = "evt-fatal-release",
                        source = ExternalTextHandoffSource.SHARE_TEXT,
                        text = longText(),
                    ),
                    vm,
                )
            }.exceptionOrNull()

            assertTrue("LinkageError must propagate, got: $failure", failure is LinkageError)
            // The adoption already committed durably: its receipt stays and a
            // replay stays idempotent — no fabricated rollback occurred.
            assertEquals(
                "evt-fatal-release",
                vm.draft(ChatViewModel.NEW_CONVERSATION_ID).lastExternalHandoffId,
            )
            assertTrue(
                vm.draft(ChatViewModel.NEW_CONVERSATION_ID).pendingAttachments
                    .none { it.localPath == oldFile.canonicalPath },
            )
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(oldFile.canonicalPath)
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // ---- P3.1 text handoff canonical-path fatal error propagates --------------

    @Test
    fun `text handoff canonical path fatal error propagates instead of failing import`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-fatal-canonical-text").toFile()
        try {
            val toasts = mutableListOf<String>()
            val importer = HandoffAttachmentImporter(
                attachmentRoot = attachmentDir,
                coordinatorFactory = { text ->
                    AttachmentImportCoordinator(
                        attachmentRoot = attachmentDir,
                        inputFactory = { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) },
                        usableSpace = handoffTestUsableSpace,
                    )
                },
                deletePendingFile = { false },
                notify = { toasts += it },
                canonicalPathOf = { throw LinkageError("broken canonical path") },
            )

            val failure = runCatching {
                importer.prepareTextHandoff(
                    ExternalTextHandoff(
                        id = "evt-fatal-canonical-text",
                        source = ExternalTextHandoffSource.SHARE_TEXT,
                        text = longText(),
                    ),
                )
            }.exceptionOrNull()

            assertTrue("LinkageError must propagate, got: $failure", failure is LinkageError)
            // Not an ordinary FAILED path: no bounded toast fired.
            assertTrue(toasts.isEmpty())
        } finally {
            attachmentDir.deleteRecursively()
        }
    }

    // ---- P3.2 document handoff canonical-path fatal error propagates -----------

    @Test
    fun `document handoff canonical path fatal error propagates instead of failing import`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-fatal-canonical-doc").toFile()
        val storeDir = Files.createTempDirectory("fusion-fatal-canonical-doc-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val uri = "content://x/doc.txt"
            val bytes = "문서 본문".toByteArray(Charsets.UTF_8)
            val toasts = mutableListOf<String>()
            val fatal = DocumentHandoffCoordinator(
                attachmentRoot = attachmentDir,
                coordinatorProvider = {
                    AttachmentImportCoordinator(
                        attachmentRoot = attachmentDir,
                        inputFactory = { ByteArrayInputStream(bytes) },
                        usableSpace = handoffTestUsableSpace,
                    )
                },
                metadataProvider = { SharedDocumentMetadata("doc.txt", bytes.size.toLong(), "text/plain") },
                deletePendingFile = { false },
                releaseRemovedDraftPath = { RemovedAttachmentReleaseResult.ReferenceOnly },
                notify = { toasts += it },
                canonicalPathOf = { throw LinkageError("broken canonical path") },
            )

            val failure = runCatching {
                fatal.handle(
                    ExternalDocumentHandoff(
                        id = "evt-fatal-canonical-doc",
                        uriStrings = listOf(uri),
                        declaredMimeType = "text/plain",
                        source = ExternalDocumentHandoffSource.SHARE_STREAM,
                    ),
                    vm,
                )
            }.exceptionOrNull()

            assertTrue("LinkageError must propagate, got: $failure", failure is LinkageError)
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // ---- P3.3 canonical-path ordinary failure / cancellation stays bounded -----

    @Test
    fun `canonical path ordinary failure stays a bounded import failure and cancellation propagates`() =
        runTest {
            val attachmentDir = Files.createTempDirectory("fusion-bounded-canonical").toFile()
            val storeDir = Files.createTempDirectory("fusion-bounded-canonical-store").toFile()
            try {
                val toasts = mutableListOf<String>()
                val boundedImporter = HandoffAttachmentImporter(
                    attachmentRoot = attachmentDir,
                    coordinatorFactory = { text ->
                        AttachmentImportCoordinator(
                            attachmentRoot = attachmentDir,
                            inputFactory = { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) },
                            usableSpace = handoffTestUsableSpace,
                        )
                    },
                    deletePendingFile = { false },
                    notify = { toasts += it },
                    canonicalPathOf = { throw java.io.IOException("fs unavailable") },
                )

                // Ordinary I/O failure: existing bounded failure route (null + toast).
                val bounded = boundedImporter.prepareTextHandoff(
                    ExternalTextHandoff(
                        id = "evt-runtime-canonical-text",
                        source = ExternalTextHandoffSource.SHARE_TEXT,
                        text = longText(),
                    ),
                )
                assertEquals(null, bounded)
                assertTrue(toasts.isNotEmpty())

                // Structural cancellation at the seam: rethrown, never bounded.
                val cancellationImporter = HandoffAttachmentImporter(
                    attachmentRoot = attachmentDir,
                    coordinatorFactory = { text ->
                        AttachmentImportCoordinator(
                            attachmentRoot = attachmentDir,
                            inputFactory = { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) },
                            usableSpace = handoffTestUsableSpace,
                        )
                    },
                    deletePendingFile = { false },
                    notify = {},
                    canonicalPathOf = { throw CancellationException("canonical cancelled") },
                )
                val cancellationFailure = runCatching {
                    cancellationImporter.prepareTextHandoff(
                        ExternalTextHandoff(
                            id = "evt-cancel-canonical-text",
                            source = ExternalTextHandoffSource.SHARE_TEXT,
                            text = longText(),
                        ),
                    )
                }.exceptionOrNull()
                assertTrue(
                    "CancellationException must propagate, got: $cancellationFailure",
                    cancellationFailure is CancellationException,
                )

                // Document side: ordinary canonical failure keeps the bounded
                // FAILED_IMPORT route with its toast.
                val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
                assertTrue(vm.draftMachine.awaitHydration())
                val uri = "content://x/doc.txt"
                val bytes = "문서 본문".toByteArray(Charsets.UTF_8)
                val docToasts = mutableListOf<String>()
                val boundedDoc = DocumentHandoffCoordinator(
                    attachmentRoot = attachmentDir,
                    coordinatorProvider = {
                        AttachmentImportCoordinator(
                            attachmentRoot = attachmentDir,
                            inputFactory = { ByteArrayInputStream(bytes) },
                            usableSpace = handoffTestUsableSpace,
                        )
                    },
                    metadataProvider = { SharedDocumentMetadata("doc.txt", bytes.size.toLong(), "text/plain") },
                    deletePendingFile = { false },
                    releaseRemovedDraftPath = { RemovedAttachmentReleaseResult.ReferenceOnly },
                    notify = { docToasts += it },
                    canonicalPathOf = { throw java.io.IOException("fs unavailable") },
                )
                val outcome = boundedDoc.handle(
                    ExternalDocumentHandoff(
                        id = "evt-runtime-canonical-doc",
                        uriStrings = listOf(uri),
                        declaredMimeType = "text/plain",
                        source = ExternalDocumentHandoffSource.SHARE_STREAM,
                    ),
                    vm,
                )
                assertEquals(HandoffImportOutcome.FAILED_IMPORT, outcome)
                assertTrue(docToasts.isNotEmpty())
            } finally {
                attachmentDir.deleteRecursively()
                storeDir.deleteRecursively()
            }
        }

    // ---- P4.5 cancellation behavior unchanged ---------------------------------

    @Test
    fun `structural cancellation still propagates and keeps the event retryable`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-cancel-meta").toFile()
        val storeDir = Files.createTempDirectory("fusion-cancel-meta-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(realStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val coordinator = DocumentHandoffCoordinator(
                attachmentRoot = attachmentDir,
                coordinatorProvider = {
                    AttachmentImportCoordinator(
                        attachmentRoot = attachmentDir,
                        inputFactory = { ByteArrayInputStream("본문".toByteArray(Charsets.UTF_8)) },
                        usableSpace = handoffTestUsableSpace,
                    )
                },
                metadataProvider = { throw CancellationException("metadata cancelled") },
                deletePendingFile = { false },
                releaseRemovedDraftPath = { RemovedAttachmentReleaseResult.ReferenceOnly },
                notify = {},
            )

            try {
                coordinator.handle(
                    ExternalDocumentHandoff(
                        id = "evt-cancel-meta",
                        uriStrings = listOf("content://x/c.txt"),
                        declaredMimeType = "text/plain",
                        source = ExternalDocumentHandoffSource.SHARE_STREAM,
                    ),
                    vm,
                )
                fail("CancellationException must propagate")
            } catch (expected: CancellationException) {
                // Structural cancellation: rethrown, event retryable.
            }
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // ---- P4.6 ordinary recoverable failure unchanged ---------------------------

    @Test
    fun `ordinary runtime adoption failure still settles as failed import with toast`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-runtime-adopt").toFile()
        val storeDir = Files.createTempDirectory("fusion-runtime-adopt-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(FailingAdoptionStore(storeDir)).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val toasts = mutableListOf<String>()
            val coordinator = textCoordinator(
                attachmentDir = attachmentDir,
                factory = { text ->
                    AttachmentImportCoordinator(
                        attachmentRoot = attachmentDir,
                        inputFactory = { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) },
                        usableSpace = handoffTestUsableSpace,
                    )
                },
                deletePendingFile = { false },
                releaseRemovedDraftPath = { RemovedAttachmentReleaseResult.ReferenceOnly },
                toasts = toasts,
            )

            val outcome = coordinator.handle(
                ExternalTextHandoff(
                    id = "evt-runtime-adopt",
                    source = ExternalTextHandoffSource.SHARE_TEXT,
                    text = longText(),
                ),
                vm,
            )

            assertEquals(HandoffImportOutcome.FAILED_IMPORT, outcome)
            assertTrue(toasts.isNotEmpty())
            assertTrue(toasts.last().contains("반영하지 못했습니다"))
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // ---- fixtures ---------------------------------------------------------------

    /** Durable adoption write fails boundedly (returns false). */
    private class FailingAdoptionStore(dir: File) : PersistentComposerDraftStore(
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
