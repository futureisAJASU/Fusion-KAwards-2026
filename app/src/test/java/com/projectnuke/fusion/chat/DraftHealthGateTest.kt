package com.projectnuke.fusion.chat

import com.projectnuke.fusion.ui.CommittedDraftReconciliationDebt
import com.projectnuke.fusion.ui.CommittedDraftReconciliationDebtStore
import com.projectnuke.fusion.ui.CommittedDraftDebtLoad
import com.projectnuke.fusion.ui.DraftReconciliationOwner
import com.projectnuke.fusion.ui.DraftReconciliationResult
import java.io.File
import java.nio.file.Files
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DraftHealthGateTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule()

    private val testDispatcher get() = mainDispatcherRule.dispatcher
    private var vm: ChatViewModel? = null

    @After
    fun tearDown() {
        vm?.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        vm = null
    }

    private fun corruptStore(file: File): PersistentComposerDraftStore =
        PersistentComposerDraftStore(file, { null }, { it.absolutePath })

    @Test
    fun `CORRUPT draft health blocks debt consumption without owner or release calls`() = runTest {
        val dir = Files.createTempDirectory("fusion-health-corrupt").toFile()
        val draftFile = File(dir, "composer_drafts_v2.json").apply { writeText("not-json") }
        val debtFile = File(dir, "debt.json")
        val store = corruptStore(draftFile)
        val vm = ChatViewModel.forTesting(store).also { this@DraftHealthGateTest.vm = it }
        assertTrue(vm.draftMachine.awaitHydration())
        assertEquals(DraftHydrationHealth.UNREADABLE_CORRUPT, vm.draftHealthNow())
        assertFalse(shouldProcessReconciliationDebts(vm.draftHealthNow()))

        CommittedDraftReconciliationDebtStore.record(
            debtFile,
            CommittedDraftReconciliationDebt(9L, "token-T", "hello", setOf("/managed/a"), 0, 0L),
        )
        val beforeBytes = debtFile.readBytes()
        var ownerCalls = 0
        var releases = 0
        // Mirror the FusionApp startup gate: unreadable health skips retry entirely.
        if (shouldProcessReconciliationDebts(vm.draftHealthNow())) {
            CommittedDraftReconciliationDebtStore.retry(
                owner = DraftReconciliationOwner { ownerCalls++; DraftReconciliationResult(true, true) },
                unregisterPath = { releases++ },
                file = debtFile,
            )
        }
        assertEquals(0, ownerCalls)
        assertEquals(0, releases)
        assertTrue(beforeBytes.contentEquals(debtFile.readBytes()))
        val load = CommittedDraftReconciliationDebtStore.loadResult(debtFile)
        assertTrue(load is CommittedDraftDebtLoad.Valid)
        assertEquals(1, (load as CommittedDraftDebtLoad.Valid).entries.size)

        // Defense in depth: direct machine reconcile also refuses from empty view.
        assertFalse(vm.draftMachine.reconcileCommittedSubmission(9L, "token-T", "hello", setOf("/managed/a")))
        assertTrue(beforeBytes.contentEquals(debtFile.readBytes()))
        dir.deleteRecursively()
    }

    @Test
    fun `OVERSIZED draft health blocks debt consumption`() = runTest {
        val dir = Files.createTempDirectory("fusion-health-oversized").toFile()
        val draftFile = File(dir, "composer_drafts_v2.json").apply {
            writeText("x".repeat(PersistentComposerDraftStore.MAX_FILE_BYTES + 1))
        }
        val debtFile = File(dir, "debt.json")
        val store = corruptStore(draftFile)
        val vm = ChatViewModel.forTesting(store).also { this@DraftHealthGateTest.vm = it }
        assertTrue(vm.draftMachine.awaitHydration())
        assertEquals(DraftHydrationHealth.UNREADABLE_OVERSIZED, vm.draftHealthNow())
        assertFalse(shouldProcessReconciliationDebts(vm.draftHealthNow()))
        CommittedDraftReconciliationDebtStore.record(
            debtFile,
            CommittedDraftReconciliationDebt(9L, "token-T", "hello", emptySet(), 0, 0L),
        )
        val beforeBytes = debtFile.readBytes()
        assertFalse(vm.draftMachine.reconcileCommittedSubmission(9L, "token-T", "hello", emptySet()))
        assertTrue(beforeBytes.contentEquals(debtFile.readBytes()))
        dir.deleteRecursively()
    }

    @Test
    fun `unreadable draft health returns typed Send rejection`() = runTest {
        val dir = Files.createTempDirectory("fusion-health-send").toFile()
        val draftFile = File(dir, "composer_drafts_v2.json").apply { writeText("not-json") }
        val vm = ChatViewModel.forTesting(corruptStore(draftFile)).also { this@DraftHealthGateTest.vm = it }
        assertTrue(vm.draftMachine.awaitHydration())
        val result = vm.beginSubmissionResult(1L)
        assertTrue(result is SubmissionAdmissionResult.Rejected)
        assertEquals(
            SubmissionRejectionReason.DRAFT_STORE_UNREADABLE,
            (result as SubmissionAdmissionResult.Rejected).reason,
        )
        dir.deleteRecursively()
    }

    @Test
    fun `missing draft file allows normal Send`() = runTest {
        val dir = Files.createTempDirectory("fusion-health-missing").toFile()
        val draftFile = File(dir, "composer_drafts_v2.json")
        assertFalse(draftFile.exists())
        val vm = ChatViewModel.forTesting(corruptStore(draftFile)).also { this@DraftHealthGateTest.vm = it }
        assertTrue(vm.draftMachine.awaitHydration())
        assertEquals(DraftHydrationHealth.MISSING, vm.draftHealthNow())
        assertTrue(shouldProcessReconciliationDebts(vm.draftHealthNow()))
        vm.updateDraftText(1L, "hello new chat")
        testDispatcher.scheduler.advanceUntilIdle()
        val result = vm.beginSubmissionResult(1L)
        assertTrue("missing store must admit Send, was $result", result is SubmissionAdmissionResult.Accepted)
        val snapshot = (result as SubmissionAdmissionResult.Accepted).snapshot
        vm.settleSubmissionOwner(1L, snapshot.token)
        dir.deleteRecursively()
    }

    @Test
    fun `unexpected load failure becomes UNREADABLE_IO, never MISSING`() = runTest {
        val dir = Files.createTempDirectory("fusion-health-io").toFile()
        val draftFile = File(dir, "composer_drafts_v2.json")
        val failing = object : PersistentComposerDraftStore(
            draftFile,
            { null },
            { it.absolutePath },
        ) {
            override suspend fun loadResult(): DraftStoreLoad =
                throw java.io.IOException("injected disk failure")
        }
        val vm = ChatViewModel.forTesting(failing).also { this@DraftHealthGateTest.vm = it }
        assertTrue(vm.draftMachine.awaitHydration())
        // Unknown ownership must not masquerade as legitimate empty state.
        assertEquals(DraftHydrationHealth.UNREADABLE_IO, vm.draftHealthNow())
        assertFalse(shouldProcessReconciliationDebts(vm.draftHealthNow()))
        // In-memory view is empty, but Send is blocked with a typed rejection.
        assertTrue(vm.draft(1L).rawInput.isEmpty())
        val result = vm.beginSubmissionResult(1L)
        assertTrue(result is SubmissionAdmissionResult.Rejected)
        assertEquals(
            SubmissionRejectionReason.DRAFT_STORE_UNREADABLE,
            (result as SubmissionAdmissionResult.Rejected).reason,
        )
        // Direct reconciliation also refuses from the unknown-ownership view.
        assertFalse(vm.draftMachine.reconcileCommittedSubmission(1L, "token-T", "hello", emptySet()))
        dir.deleteRecursively()
    }

    @Test
    fun `corrupt startup plus ordinary edit and debounce attempt never supersedes unreadable ownership`() = runTest {
        val dir = Files.createTempDirectory("fusion-health-debounce").toFile()
        val draftFile = File(dir, "composer_drafts_v2.json").apply { writeText("not-json") }
        val originalBytes = draftFile.readBytes()
        val store = corruptStore(draftFile)
        val vm = ChatViewModel.forTesting(store).also { this@DraftHealthGateTest.vm = it }
        assertTrue(vm.draftMachine.awaitHydration())
        assertEquals(DraftHydrationHealth.UNREADABLE_CORRUPT, vm.draftHealthNow())

        // Ordinary user edit: in-memory mutation succeeds, then the debounced
        // persistence attempt runs against an unreadable authoritative source.
        vm.draftMachine.updateText(1L) { it.copy(rawInput = "typed while unreadable") }
        // The debounce (300ms on IO) plus the PersistDebounced command must
        // both complete without touching durable evidence.
        advanceUntilIdle()
        Thread.sleep(700)
        advanceUntilIdle()

        assertEquals(DraftHydrationHealth.UNREADABLE_CORRUPT, vm.draftHealthNow())
        // The original authoritative artifact is byte-identical: no silent
        // quarantine-and-replace from an in-memory view of unknown ownership.
        assertTrue(originalBytes.contentEquals(draftFile.readBytes()))
        assertFalse(File(dir, "composer_drafts_v2.json.corrupt.bak").exists())
        // Send stays blocked while ownership is unreadable.
        val result = vm.beginSubmissionResult(1L)
        assertTrue(result is SubmissionAdmissionResult.Rejected)
        assertEquals(
            SubmissionRejectionReason.DRAFT_STORE_UNREADABLE,
            (result as SubmissionAdmissionResult.Rejected).reason,
        )
        dir.deleteRecursively()
    }

    @Test
    fun `critical write on corrupt startup must not certify ownership valid`() = runTest {
        val dir = Files.createTempDirectory("fusion-health-critical").toFile()
        val draftFile = File(dir, "composer_drafts_v2.json").apply { writeText("not-json") }
        val originalBytes = draftFile.readBytes()
        val store = corruptStore(draftFile)
        val vm = ChatViewModel.forTesting(store).also { this@DraftHealthGateTest.vm = it }
        assertTrue(vm.draftMachine.awaitHydration())
        assertEquals(DraftHydrationHealth.UNREADABLE_CORRUPT, vm.draftHealthNow())

        // Writable bytes are not proof of known ownership: the critical
        // durable write is refused before it can supersede the unreadable
        // authoritative artifact.
        assertFalse(vm.draftMachine.updateCritical(1L) { it.copy(rawInput = "edited after crash") })
        assertEquals(DraftHydrationHealth.UNREADABLE_CORRUPT, vm.draftHealthNow())
        assertTrue(originalBytes.contentEquals(draftFile.readBytes()))
        assertFalse(File(dir, "composer_drafts_v2.json.corrupt.bak").exists())
        val result = vm.beginSubmissionResult(1L)
        assertTrue(result is SubmissionAdmissionResult.Rejected)
        assertEquals(
            SubmissionRejectionReason.DRAFT_STORE_UNREADABLE,
            (result as SubmissionAdmissionResult.Rejected).reason,
        )
        dir.deleteRecursively()
    }

    @Test
    fun `oversized startup keeps ownership unreadable across persistence attempts`() = runTest {
        val dir = Files.createTempDirectory("fusion-health-oversized").toFile()
        val draftFile = File(dir, "composer_drafts_v2.json")
            .apply { writeText("x".repeat(PersistentComposerDraftStore.MAX_FILE_BYTES + 1)) }
        val originalBytes = draftFile.readBytes()
        val store = corruptStore(draftFile)
        val vm = ChatViewModel.forTesting(store).also { this@DraftHealthGateTest.vm = it }
        assertTrue(vm.draftMachine.awaitHydration())
        assertEquals(DraftHydrationHealth.UNREADABLE_OVERSIZED, vm.draftHealthNow())

        assertFalse(vm.draftMachine.updateCritical(1L) { it.copy(rawInput = "edited") })
        assertEquals(DraftHydrationHealth.UNREADABLE_OVERSIZED, vm.draftHealthNow())
        assertTrue(originalBytes.contentEquals(draftFile.readBytes()))
        val result = vm.beginSubmissionResult(1L)
        assertTrue(result is SubmissionAdmissionResult.Rejected)
        assertEquals(
            SubmissionRejectionReason.DRAFT_STORE_UNREADABLE,
            (result as SubmissionAdmissionResult.Rejected).reason,
        )
        dir.deleteRecursively()
    }

    @Test
    fun `io-failed startup keeps ownership unreadable across persistence attempts`() = runTest {
        val dir = Files.createTempDirectory("fusion-health-io").toFile()
        val draftFile = File(dir, "composer_drafts_v2.json")
        val unreadable = object : PersistentComposerDraftStore(
            draftFile,
            { null },
            { it.absolutePath },
        ) {
            override suspend fun loadResult(): DraftStoreLoad = throw java.io.IOException("injected load failure")
        }
        val vm = ChatViewModel.forTesting(unreadable).also { this@DraftHealthGateTest.vm = it }
        assertTrue(vm.draftMachine.awaitHydration())
        assertEquals(DraftHydrationHealth.UNREADABLE_IO, vm.draftHealthNow())

        assertFalse(vm.draftMachine.updateCritical(1L) { it.copy(rawInput = "edited") })
        assertEquals(DraftHydrationHealth.UNREADABLE_IO, vm.draftHealthNow())
        // No durable artifact was created to impersonate known ownership.
        assertFalse(draftFile.exists())
        val result = vm.beginSubmissionResult(1L)
        assertTrue(result is SubmissionAdmissionResult.Rejected)
        assertEquals(
            SubmissionRejectionReason.DRAFT_STORE_UNREADABLE,
            (result as SubmissionAdmissionResult.Rejected).reason,
        )
        dir.deleteRecursively()
    }

    @Test
    fun `missing and valid stores retain normal persistence and send behavior`() = runTest {
        // MISSING: a fresh store persists normally and becomes VALID.
        val missingDir = Files.createTempDirectory("fusion-health-missing").toFile()
        val missingFile = File(missingDir, "composer_drafts_v2.json")
        val missingVm = ChatViewModel.forTesting(corruptStore(missingFile)).also { this@DraftHealthGateTest.vm = it }
        assertTrue(missingVm.draftMachine.awaitHydration())
        assertEquals(DraftHydrationHealth.MISSING, missingVm.draftHealthNow())
        assertTrue(missingVm.draftMachine.updateCritical(1L) { it.copy(rawInput = "normal") })
        assertEquals(DraftHydrationHealth.VALID, missingVm.draftHealthNow())
        assertTrue(missingFile.isFile)
        val admitted = missingVm.beginSubmissionResult(1L)
        assertTrue(admitted is SubmissionAdmissionResult.Accepted)
        missingVm.settleSubmissionOwner(1L, (admitted as SubmissionAdmissionResult.Accepted).snapshot.token)
        missingDir.deleteRecursively()
        vm = null

        // VALID: a readable store keeps persisting normally.
        val validDir = Files.createTempDirectory("fusion-health-valid").toFile()
        val validFile = File(validDir, "composer_drafts_v2.json")
        val validVm = ChatViewModel.forTesting(corruptStore(validFile)).also { this@DraftHealthGateTest.vm = it }
        assertTrue(validVm.draftMachine.awaitHydration())
        assertTrue(validVm.draftMachine.updateCritical(1L) { it.copy(rawInput = "first") })
        assertTrue(validVm.draftMachine.updateCritical(1L) { it.copy(rawInput = "second") })
        assertEquals(DraftHydrationHealth.VALID, validVm.draftHealthNow())
        assertEquals("second", validVm.draft(1L).rawInput)
        validDir.deleteRecursively()
    }

    @Test
    fun `failed quarantine rewrite keeps health unreadable and Send blocked`() = runTest {
        val dir = Files.createTempDirectory("fusion-health-recover-fail").toFile()
        val draftFile = File(dir, "composer_drafts_v2.json").apply { writeText("not-json") }
        val store = corruptStore(draftFile)
        store.quarantineHook = { _, _ -> com.projectnuke.fusion.util.DebtQuarantineOutcome.Failed }
        val vm = ChatViewModel.forTesting(store).also { this@DraftHealthGateTest.vm = it }
        assertTrue(vm.draftMachine.awaitHydration())
        assertEquals(DraftHydrationHealth.UNREADABLE_CORRUPT, vm.draftHealthNow())

        // Durable write fails closed; the machine rolls back and health stays put.
        assertFalse(vm.draftMachine.updateCritical(1L) { it.copy(rawInput = "lost edit") })
        assertEquals(DraftHydrationHealth.UNREADABLE_CORRUPT, vm.draftHealthNow())
        val result = vm.beginSubmissionResult(1L)
        assertTrue(result is SubmissionAdmissionResult.Rejected)
        assertEquals(
            SubmissionRejectionReason.DRAFT_STORE_UNREADABLE,
            (result as SubmissionAdmissionResult.Rejected).reason,
        )
        // Corrupt evidence is preserved byte-identical.
        assertEquals("not-json", draftFile.readText())
        dir.deleteRecursively()
    }

    @Test
    fun `explicit recovery makes corrupt state valid without sending`() = runTest {
        val dir = Files.createTempDirectory("fusion-health-explicit-recovery").toFile()
        val draftFile = File(dir, "composer_drafts_v2.json").apply { writeText("not-json") }
        val vm = ChatViewModel.forTesting(corruptStore(draftFile)).also { this@DraftHealthGateTest.vm = it }
        assertTrue(vm.draftMachine.awaitHydration())
        vm.draftMachine.updateText(4L) { it.copy(rawInput = "current input") }

        assertEquals(DraftRecoveryResult.Recovered, vm.recoverUnreadableDraftState())
        assertEquals(DraftHydrationHealth.VALID, vm.draftHealthNow())
        assertEquals("current input", vm.draft(4L).rawInput)
        assertEquals("not-json", File(dir, draftFile.name + ".corrupt.bak").readText())
        // Recovery writes only composer state; it neither creates an owner nor sends.
        assertEquals(null, vm.draft(4L).activeSubmissionToken)
        dir.deleteRecursively()
    }

    @Test
    fun `recovery rejects live submission or import ownership`() = runTest {
        val dir = Files.createTempDirectory("fusion-health-recovery-owner").toFile()
        val draftFile = File(dir, "composer_drafts_v2.json").apply { writeText("not-json") }
        val vm = ChatViewModel.forTesting(corruptStore(draftFile)).also { this@DraftHealthGateTest.vm = it }
        assertTrue(vm.draftMachine.awaitHydration())
        vm.draftMachine.updateText(4L) { it.copy(activeSubmissionToken = "live-submission") }
        assertEquals(DraftRecoveryResult.Failed, vm.recoverUnreadableDraftState())
        assertEquals(DraftHydrationHealth.UNREADABLE_CORRUPT, vm.draftHealthNow())
        assertEquals("not-json", draftFile.readText())

        vm.draftMachine.updateText(4L) {
            it.copy(
                activeSubmissionToken = null,
                importOwnership = ComposerImportOwnership("live-import", ComposerImportStatus.COPYING),
            )
        }
        assertEquals(DraftRecoveryResult.Failed, vm.recoverUnreadableDraftState())
        assertEquals("not-json", draftFile.readText())
        dir.deleteRecursively()
    }

    @Test
    fun `valid and missing recovery requests do not rewrite state`() = runTest {
        val missingDir = Files.createTempDirectory("fusion-health-recovery-missing").toFile()
        val missingFile = File(missingDir, "composer_drafts_v2.json")
        val missingVm = ChatViewModel.forTesting(corruptStore(missingFile)).also { this@DraftHealthGateTest.vm = it }
        assertTrue(missingVm.draftMachine.awaitHydration())
        assertEquals(DraftRecoveryResult.NotNeeded, missingVm.recoverUnreadableDraftState())
        assertFalse(missingFile.exists())
        missingDir.deleteRecursively()
        vm = null

        val validDir = Files.createTempDirectory("fusion-health-recovery-valid").toFile()
        val validFile = File(validDir, "composer_drafts_v2.json")
        val validVm = ChatViewModel.forTesting(corruptStore(validFile)).also { this@DraftHealthGateTest.vm = it }
        assertTrue(validVm.draftMachine.awaitHydration())
        assertTrue(validVm.draftMachine.updateCritical(4L) { it.copy(rawInput = "valid") })
        val before = validFile.readBytes()
        assertEquals(DraftRecoveryResult.NotNeeded, validVm.recoverUnreadableDraftState())
        assertTrue(before.contentEquals(validFile.readBytes()))
        validDir.deleteRecursively()
    }
}
