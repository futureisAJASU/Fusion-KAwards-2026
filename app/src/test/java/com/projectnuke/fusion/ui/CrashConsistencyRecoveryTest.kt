package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.MainDispatcherIsolationRule
import com.projectnuke.fusion.chat.PendingAttachmentIdentity
import com.projectnuke.fusion.chat.cancelForTest
import com.projectnuke.fusion.data.ResponseVersionCommitWitnessEntity
import com.projectnuke.fusion.data.SubmissionCommitWitnessEntity
import com.projectnuke.fusion.data.SubmissionCommitWitnessCodec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CrashConsistencyRecoveryTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule { scheduler ->
        UnconfinedTestDispatcher(scheduler)
    }

    @Test
    fun `matching submission witness recovers exact draft fields and unregisters paths`() = runBlocking {
        val vm = ChatViewModel()
        val path = "/managed/committed.txt"
        val token = "s8:commit"
        assertTrue(
            vm.draftMachine.updateCritical(0L) {
                it.copy(
                    rawInput = "captured",
                    pendingAttachments = listOf(PendingAttachmentIdentity("committed.txt", "text/plain", path)),
                    activeSubmissionToken = token,
                )
            }
        )
        val witnessDao = FakeSubmissionWitnessDao(
            SubmissionCommitWitnessEntity(
                token = token,
                draftKey = 0L,
                committedConversationId = 41L,
                userMessageId = 52L,
                capturedRawInput = "captured",
                committedPathsJson = SubmissionCommitWitnessCodec.encodePaths(listOf(path))!!,
                createdAt = 1L,
            )
        )
        val unregistered = mutableListOf<String>()
        try {
            recoverSchema8SubmissionWitnesses(vm, witnessDao) { unregistered += it }
            assertEquals("", vm.draft(0L).rawInput)
            assertTrue(vm.draft(0L).pendingAttachments.isEmpty())
            assertNull(vm.draft(0L).activeSubmissionToken)
            assertTrue(witnessDao.deleted)
            assertEquals(listOf(path), unregistered)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    @Test
    fun `schema8 token without witness clears only ownership and legacy token stays fail safe`() = runBlocking {
        val vm = ChatViewModel()
        try {
            assertTrue(
                vm.draftMachine.updateCritical(0L) {
                    it.copy(
                        rawInput = "keep me",
                        pendingAttachments = listOf(PendingAttachmentIdentity("a", "text/plain", "/managed/a")),
                        activeSubmissionToken = "s8:uncommitted",
                    )
                }
            )
            recoverSchema8SubmissionWitnesses(vm, FakeSubmissionWitnessDao(null)) {}
            assertEquals("keep me", vm.draft(0L).rawInput)
            assertEquals(1, vm.draft(0L).pendingAttachments.size)
            assertNull(vm.draft(0L).activeSubmissionToken)

            assertTrue(vm.draftMachine.updateCritical(0L) { it.copy(activeSubmissionToken = "legacy-uuid") })
            recoverSchema8SubmissionWitnesses(vm, FakeSubmissionWitnessDao(null)) {}
            assertEquals("legacy-uuid", vm.draft(0L).activeSubmissionToken)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    @Test
    fun `superseded witness never erases newer draft and response merge is idempotent`() = runBlocking {
        val vm = ChatViewModel()
        val oldPath = "/managed/old"
        try {
            assertTrue(
                vm.draftMachine.updateCritical(0L) {
                    it.copy(rawInput = "new text", activeSubmissionToken = "s8:new")
                }
            )
            val oldToken = "s8:old"
            val witnessDao = FakeSubmissionWitnessDao(
                SubmissionCommitWitnessEntity(
                    token = oldToken,
                    draftKey = 0L,
                    committedConversationId = 2L,
                    userMessageId = 3L,
                    capturedRawInput = "old text",
                    committedPathsJson = SubmissionCommitWitnessCodec.encodePaths(listOf(oldPath))!!,
                    createdAt = 1L,
                )
            )
            val unregistered = mutableListOf<String>()
            recoverSchema8SubmissionWitnesses(vm, witnessDao) { unregistered += it }
            assertEquals("new text", vm.draft(0L).rawInput)
            assertNull(vm.draft(0L).activeSubmissionToken)
            assertEquals(listOf(oldPath), unregistered)

            val witness = ResponseVersionCommitWitnessEntity(9L, 7L, 8L, 42L, 1L)
            val first = mergeResponseVersionCommitWitness(ResponseVersionState(), witness)
            val second = mergeResponseVersionCommitWitness(first, witness)
            assertEquals(first, second)
            assertEquals(42L, second.groupByMessageId[8L])
            assertEquals(42L, second.groupByMessageId[9L])
            assertEquals(9L, second.activeMessageIdByGroup[42L])
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    private class FakeSubmissionWitnessDao(
        private var witness: SubmissionCommitWitnessEntity?,
    ) : com.projectnuke.fusion.data.SubmissionCommitWitnessDao {
        var deleted = false

        override suspend fun insert(witness: SubmissionCommitWitnessEntity) {
            this.witness = witness
        }

        override suspend fun get(token: String): SubmissionCommitWitnessEntity? =
            witness?.takeIf { it.token == token }

        override suspend fun getBatch(limit: Int): List<SubmissionCommitWitnessEntity> =
            witness?.let(::listOf).orEmpty().take(limit)

        override suspend fun getBatchAfter(
            createdAt: Long,
            token: String,
            limit: Int,
        ): List<SubmissionCommitWitnessEntity> = witness
            ?.takeIf { it.createdAt > createdAt || (it.createdAt == createdAt && it.token > token) }
            ?.let(::listOf).orEmpty().take(limit)

        override suspend fun delete(token: String): Int {
            if (witness?.token == token) {
                witness = null
                deleted = true
                return 1
            }
            return 0
        }
    }
}
