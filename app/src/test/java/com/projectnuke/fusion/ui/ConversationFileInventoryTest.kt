package com.projectnuke.fusion.ui

import androidx.lifecycle.viewModelScope
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.MainDispatcherIsolationRule
import com.projectnuke.fusion.chat.cancelForTest
import com.projectnuke.fusion.chat.PendingAttachmentIdentity
import com.projectnuke.fusion.chat.PersistentComposerDraftStore
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// Conversation file inventory: trusted local files for the active
// conversation from message contents plus current draft attachments.
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationFileInventoryTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule()

    private val testDispatcher get() = mainDispatcherRule.dispatcher
    private var viewModel: ChatViewModel? = null

    @After
    fun tearDown() {
        viewModel?.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        viewModel = null
    }

    private fun managedFile(root: File, name: String): File =
        File(root, name).apply { writeText("내용", Charsets.UTF_8) }

    private fun envelope(path: String, name: String = "doc.txt", mime: String = "text/plain"): String =
        AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(AttachmentRecord(name, mime, path)),
            "질문 본문",
        )

    @Test
    fun `historical trusted file listed as available`() {
        val root = Files.createTempDirectory("fusion-inventory").toFile()
        try {
            val file = managedFile(root, "notes.txt")
            val inventory = ConversationFileInventory.collect(
                historyContents = listOf(envelope(file.canonicalPath, "notes.txt")),
                draftAttachments = emptyList(),
                attachmentRoot = root,
            )
            assertEquals(1, inventory.size)
            val entry = inventory.single()
            assertEquals("notes.txt", entry.name)
            assertEquals("text/plain", entry.mimeType)
            assertEquals(file.canonicalPath, entry.localPath)
            assertEquals(InventoryAvailability.AVAILABLE, entry.availability)
            assertFalse(entry.inDraft)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `current draft file listed with draft flag`() {
        val root = Files.createTempDirectory("fusion-inventory").toFile()
        try {
            val file = managedFile(root, "draft.md")
            val inventory = ConversationFileInventory.collect(
                historyContents = emptyList(),
                draftAttachments = listOf(AttachmentRecord("draft.md", "text/plain", file.canonicalPath)),
                attachmentRoot = root,
            )
            assertEquals(1, inventory.size)
            assertTrue(inventory.single().inDraft)
            assertEquals(InventoryAvailability.AVAILABLE, inventory.single().availability)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `duplicate path appears once with draft flag merged`() {
        val root = Files.createTempDirectory("fusion-inventory").toFile()
        try {
            val file = managedFile(root, "dup.txt")
            val inventory = ConversationFileInventory.collect(
                historyContents = listOf(
                    envelope(file.canonicalPath),
                    envelope(file.canonicalPath),
                    "일반 메시지",
                ),
                draftAttachments = listOf(AttachmentRecord("dup.txt", "text/plain", file.canonicalPath)),
                attachmentRoot = root,
            )
            assertEquals(1, inventory.size)
            assertTrue(inventory.single().inDraft)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unavailable file represented truthfully`() {
        val root = Files.createTempDirectory("fusion-inventory").toFile()
        try {
            val missing = File(root, "gone.txt").canonicalPath
            val inventory = ConversationFileInventory.collect(
                historyContents = listOf(envelope(missing, "gone.txt")),
                draftAttachments = emptyList(),
                attachmentRoot = root,
            )
            assertEquals(1, inventory.size)
            assertEquals(InventoryAvailability.UNAVAILABLE, inventory.single().availability)
            assertEquals("gone.txt", inventory.single().name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `suspicious path excluded entirely`() {
        val root = Files.createTempDirectory("fusion-inventory").toFile()
        val outside = File.createTempFile("outside", ".txt")
        try {
            outside.writeText("외부", Charsets.UTF_8)
            val inventory = ConversationFileInventory.collect(
                historyContents = listOf(envelope(outside.canonicalPath, "outside.txt")),
                draftAttachments = listOf(
                    AttachmentRecord("evil.txt", "text/plain", "/etc/hostname"),
                ),
                attachmentRoot = root,
            )
            assertTrue(inventory.isEmpty())
        } finally {
            outside.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun `reattach decision matrix`() {
        val available = InventoryFile("a.txt", "text/plain", "/root/a.txt", InventoryAvailability.AVAILABLE, false)
        val unavailable = available.copy(availability = InventoryAvailability.UNAVAILABLE)
        assertEquals(ReattachDecision.OK, InventoryReattachPolicy.decide(available, emptySet()))
        assertEquals(
            ReattachDecision.ALREADY_ATTACHED,
            InventoryReattachPolicy.decide(available, setOf("/root/a.txt")),
        )
        assertEquals(
            ReattachDecision.LIMIT_REACHED,
            InventoryReattachPolicy.decide(
                available,
                setOf("/root/1", "/root/2", "/root/3", "/root/4", "/root/5"),
            ),
        )
        assertEquals(
            ReattachDecision.BLOCKED_EXTERNAL,
            InventoryReattachPolicy.decide(available, emptySet(), externalAttachmentsBlocked = true),
        )
        assertEquals(ReattachDecision.UNAVAILABLE_FILE, InventoryReattachPolicy.decide(unavailable, emptySet()))
        assertEquals(5, InventoryReattachPolicy.MAX_COMPOSER_ATTACHMENTS)
    }

    @Test
    fun `reattach adoption uses the managed canonical file with no duplicate`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-inventory-adopt").toFile()
        val storeDir = Files.createTempDirectory("fusion-inventory-store").toFile()
        try {
            val managed = File(attachmentDir, "abc_shared.txt").apply {
                writeText("공유 문서", Charsets.UTF_8)
            }
            val vm = ChatViewModel.forTesting(
                PersistentComposerDraftStore(
                    file = File(storeDir, PersistentComposerDraftStore.FILE_NAME),
                    resolveManagedAttachment = { null },
                    registerPendingAttachment = { it.absolutePath },
                ),
            ).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            // Same adoption the inventory "다시 첨부" action performs: the
            // existing managed file joins the composer, nothing is copied.
            val owner = (vm.tryBeginAttachmentImport(ChatViewModel.NEW_CONVERSATION_ID) as com.projectnuke.fusion.chat.AttachmentImportAdmission.Accepted).owner
            assertTrue(vm.beginAttachmentCopy(ChatViewModel.NEW_CONVERSATION_ID, owner.token))
            assertTrue(
                vm.completeAttachmentImport(
                    conversationId = ChatViewModel.NEW_CONVERSATION_ID,
                    token = owner.token,
                    attachments = listOf(
                        PendingAttachmentIdentity("shared.txt", "text/plain", managed.canonicalPath),
                    ),
                )
            )
            val draft = vm.draft(ChatViewModel.NEW_CONVERSATION_ID)
            assertEquals(1, draft.pendingAttachments.size)
            assertEquals(managed.canonicalPath, draft.pendingAttachments.single().localPath)
            // No physical duplicate created.
            assertEquals(1, attachmentDir.listFiles { f -> f.isFile }.orEmpty().size)
            assertNull(draft.importOwnership)
            assertFalse(vm.state(ChatViewModel.NEW_CONVERSATION_ID).isGenerating)
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // ---- inventory reattach ownership (performInventoryReattach) --------------------------

    private fun reattachViewModel(storeDir: File): ChatViewModel =
        ChatViewModel.forTesting(
            PersistentComposerDraftStore(
                file = File(storeDir, PersistentComposerDraftStore.FILE_NAME),
                resolveManagedAttachment = { null },
                registerPendingAttachment = { it.absolutePath },
            ),
        ).also { viewModel = it }

    private fun reattachEntry(managed: File): InventoryFile =
        InventoryFile(
            name = "shared.txt",
            mimeType = "text/plain",
            localPath = managed.canonicalPath,
            availability = InventoryAvailability.AVAILABLE,
            inDraft = false,
        )

    @Test
    fun `reattach success keeps the adopted shared file`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-reattach-ok").toFile()
        val storeDir = Files.createTempDirectory("fusion-reattach-ok-store").toFile()
        try {
            val managed = File(attachmentDir, "shared.txt").apply {
                writeText("공유 문서", Charsets.UTF_8)
            }
            val vm = reattachViewModel(storeDir)
            assertTrue(vm.draftMachine.awaitHydration())
            val outcome = performInventoryReattach(
                conversationId = 401L,
                file = reattachEntry(managed),
                draftPaths = emptySet(),
                externalAttachmentsBlocked = false,
                chatViewModel = vm,
            )
            assertEquals(InventoryReattachOutcome.ATTACHED, outcome)
            val draft = vm.draft(401L)
            assertEquals(listOf(managed.canonicalPath), draft.pendingAttachments.map { it.localPath })
            assertNull(draft.importOwnership)
            // The shared managed file is adopted by reference, never deleted.
            assertTrue(File(managed.canonicalPath).exists())
            // The registry lease is released: a subsequent import may proceed.
            val next = (vm.tryBeginAttachmentImport(401L) as com.projectnuke.fusion.chat.AttachmentImportAdmission.Accepted).owner
            assertTrue(vm.settleAttachmentImport(401L, next.token))
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `reattach while busy leaves the live owner untouched`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-reattach-busy").toFile()
        val storeDir = Files.createTempDirectory("fusion-reattach-busy-store").toFile()
        try {
            val managed = File(attachmentDir, "shared.txt").apply {
                writeText("공유 문서", Charsets.UTF_8)
            }
            val vm = reattachViewModel(storeDir)
            assertTrue(vm.draftMachine.awaitHydration())
            val live = (vm.tryBeginAttachmentImport(402L) as com.projectnuke.fusion.chat.AttachmentImportAdmission.Accepted).owner
            testDispatcher.scheduler.advanceUntilIdle()
            val outcome = performInventoryReattach(
                conversationId = 402L,
                file = reattachEntry(managed),
                draftPaths = emptySet(),
                externalAttachmentsBlocked = false,
                chatViewModel = vm,
            )
            assertEquals(InventoryReattachOutcome.BUSY, outcome)
            testDispatcher.scheduler.advanceUntilIdle()
            // Nothing was adopted and the live owner still holds the lease.
            assertTrue(vm.draft(402L).pendingAttachments.isEmpty())
            assertEquals(live.token, vm.draft(402L).importOwnership?.token)
            assertTrue(vm.settleAttachmentImport(402L, live.token))
            assertNull(vm.draft(402L).importOwnership)
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `cancelled reattach settles the owner and releases the lease`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-reattach-cancel").toFile()
        val storeDir = Files.createTempDirectory("fusion-reattach-cancel-store").toFile()
        try {
            val managed = File(attachmentDir, "shared.txt").apply {
                writeText("공유 문서", Charsets.UTF_8)
            }
            val vm = reattachViewModel(storeDir)
            assertTrue(vm.draftMachine.awaitHydration())
            // Undispatched start runs the helper synchronously until its first
            // suspension: the import owner is acquired before cancellation can
            // land, so the NonCancellable settle path is exercised
            // deterministically.
            val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                performInventoryReattach(
                    conversationId = 403L,
                    file = reattachEntry(managed),
                    draftPaths = emptySet(),
                    externalAttachmentsBlocked = false,
                    chatViewModel = vm,
                )
            }
            job.cancel()
            testDispatcher.scheduler.advanceUntilIdle()
            job.join()
            assertTrue(job.isCancelled)
            // The owner is settled, the lease is released, nothing adopted,
            // and the shared managed file was never deleted.
            testDispatcher.scheduler.advanceUntilIdle()
            assertNull(vm.draft(403L).importOwnership)
            assertTrue(vm.draft(403L).pendingAttachments.isEmpty())
            assertTrue(File(managed.canonicalPath).exists())
            val next = (vm.tryBeginAttachmentImport(403L) as com.projectnuke.fusion.chat.AttachmentImportAdmission.Accepted).owner
            assertTrue(vm.settleAttachmentImport(403L, next.token))
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `reattach gates stay silent-safe without adopting`() = runTest {
        val attachmentDir = Files.createTempDirectory("fusion-reattach-gates").toFile()
        val storeDir = Files.createTempDirectory("fusion-reattach-gates-store").toFile()
        try {
            val managed = File(attachmentDir, "shared.txt").apply {
                writeText("공유 문서", Charsets.UTF_8)
            }
            val vm = reattachViewModel(storeDir)
            assertTrue(vm.draftMachine.awaitHydration())
            val entry = reattachEntry(managed)
            assertEquals(
                InventoryReattachOutcome.ALREADY_ATTACHED,
                performInventoryReattach(404L, entry, setOf(managed.canonicalPath), false, vm),
            )
            assertEquals(
                InventoryReattachOutcome.BLOCKED_EXTERNAL,
                performInventoryReattach(404L, entry, emptySet(), true, vm),
            )
            assertEquals(
                InventoryReattachOutcome.UNAVAILABLE_FILE,
                performInventoryReattach(
                    404L,
                    entry.copy(availability = InventoryAvailability.UNAVAILABLE),
                    emptySet(),
                    false,
                    vm,
                ),
            )
            // Gated paths acquire no ownership and adopt nothing.
            testDispatcher.scheduler.advanceUntilIdle()
            assertNull(vm.draft(404L).importOwnership)
            assertTrue(vm.draft(404L).pendingAttachments.isEmpty())
            assertTrue(File(managed.canonicalPath).exists())
        } finally {
            attachmentDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }
}
