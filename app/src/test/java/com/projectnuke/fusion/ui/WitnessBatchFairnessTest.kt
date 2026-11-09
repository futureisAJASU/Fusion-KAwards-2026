package com.projectnuke.fusion.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.chat.MainDispatcherIsolationRule
import com.projectnuke.fusion.chat.PendingAttachmentIdentity
import com.projectnuke.fusion.chat.cancelForTest
import com.projectnuke.fusion.data.ChatDao
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.MessageContentHit
import com.projectnuke.fusion.data.MessageContentPageRow
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.MessageIdentity
import com.projectnuke.fusion.data.ResponseVersionCommitWitnessDao
import com.projectnuke.fusion.data.ResponseVersionCommitWitnessEntity
import com.projectnuke.fusion.data.SubmissionCommitWitnessCodec
import com.projectnuke.fusion.data.SubmissionCommitWitnessDao
import com.projectnuke.fusion.data.SubmissionCommitWitnessEntity
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.test.PlatformSharedPreferences
import com.projectnuke.fusion.util.SharedPreferencesDurability
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * P17-04 witness recovery batch fairness.
 *
 * The production recovery loads exactly one fixed first page per invocation
 * (`witnessDao.getBatch(CRASH_RECOVERY_BATCH_SIZE)` with `ORDER BY createdAt
 * ASC, token ASC LIMIT 256`). If the oldest 256 rows stay permanently
 * unresolvable, every invocation retrieves those same 256 rows and a newer
 * valid witness starves indefinitely.
 *
 * Corrupt payloads below are pure data (never mock-programmed failures): a
 * `committedPathsJson` that [SubmissionCommitWitnessCodec.decodePaths]
 * rejects is preserved forever by the recovery, exactly like a version-skewed
 * or storage-damaged row in production. The fake DAOs below honor the same
 * `(createdAt, token)` ordering and `LIMIT` semantics as the Room queries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WitnessBatchFairnessTest {
    private enum class P19InvalidWitness {
        MISSING_INSERTED,
        FOREIGN_INSERTED,
        WRONG_INSERTED_ROLE,
        MISSING_TARGET,
        FOREIGN_TARGET,
        WRONG_TARGET_ROLE,
    }

    private enum class P19Workload { HEALTHY, DAMAGED, MIXED }

    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule { scheduler ->
        UnconfinedTestDispatcher(scheduler)
    }

    // P17-04-A: exactly 256 permanently damaged oldest witnesses plus one
    // newer valid witness. The valid witness must still be reached.
    @Test
    fun `P17-04-A valid witness beyond 256 damaged oldest is recovered`() = runBlocking {
        val vm = ChatViewModel()
        try {
            val rows = mutableListOf<SubmissionCommitWitnessEntity>()
            repeat(256) { index ->
                rows += SubmissionCommitWitnessEntity(
                    token = "s8:damaged-%03d".format(index),
                    draftKey = 9000L + index,
                    committedConversationId = 41L,
                    userMessageId = 1000L + index,
                    capturedRawInput = "old",
                    committedPathsJson = "corrupt{{{not-json-$index",
                    createdAt = 1L + index,
                )
            }
            val validToken = "s8:valid-newest"
            val validPath = "/managed/valid-newest.txt"
            val draftKey = 7777L
            assertTrue(
                vm.draftMachine.updateCritical(draftKey) {
                    it.copy(
                        rawInput = "captured-newest",
                        pendingAttachments = listOf(
                            PendingAttachmentIdentity("valid-newest.txt", "text/plain", validPath)
                        ),
                        activeSubmissionToken = validToken,
                    )
                }
            )
            rows += SubmissionCommitWitnessEntity(
                token = validToken,
                draftKey = draftKey,
                committedConversationId = 41L,
                userMessageId = 9999L,
                capturedRawInput = "captured-newest",
                committedPathsJson = SubmissionCommitWitnessCodec.encodePaths(listOf(validPath))!!,
                createdAt = 10_000L,
            )
            val dao = PagedSubmissionDao(rows)
            val unregistered = mutableListOf<String>()
            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }

            assertEquals(
                "newer valid witness must be recovered despite 256 damaged oldest rows, visited=${dao.visitedTokens.size}",
                listOf(validPath),
                unregistered,
            )
            assertTrue(
                "valid witness row must be deleted, deleted=${dao.deletedTokens}",
                dao.deletedTokens == listOf(validToken),
            )
            assertEquals(256, dao.remaining)
            assertEquals("", vm.draft(draftKey).rawInput)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P17-04-B: more than one page of mixed damaged / healthy witnesses; every
    // healthy entry beyond the first page must eventually be processed.
    @Test
    fun `P17-04-B healthy witnesses beyond the first page all converge`() = runBlocking {
        val vm = ChatViewModel()
        try {
            val rows = mutableListOf<SubmissionCommitWitnessEntity>()
            repeat(256) { index ->
                rows += SubmissionCommitWitnessEntity(
                    token = "s8:damaged-b-%03d".format(index),
                    draftKey = 9100L + index,
                    committedConversationId = 41L,
                    userMessageId = 2000L + index,
                    capturedRawInput = "old",
                    committedPathsJson = "[\"dup\",\"dup\"]",
                    createdAt = 1L + index,
                )
            }
            val validPaths = mutableListOf<String>()
            repeat(44) { index ->
                val token = "s8:healthy-b-%03d".format(index)
                val path = "/managed/healthy-b-$index.txt"
                validPaths += path
                rows += SubmissionCommitWitnessEntity(
                    token = token,
                    draftKey = 9500L + index,
                    committedConversationId = 41L,
                    userMessageId = 3000L + index,
                    capturedRawInput = "committed-$index",
                    committedPathsJson = SubmissionCommitWitnessCodec.encodePaths(listOf(path))!!,
                    createdAt = 20_000L + index,
                )
            }
            val dao = PagedSubmissionDao(rows)
            val unregistered = mutableListOf<String>()
            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }

            assertEquals(
                "all 44 healthy witnesses beyond the first page must converge, got ${unregistered.size}",
                validPaths.sorted(),
                unregistered.sorted(),
            )
            assertEquals(44, dao.deletedTokens.size)
            assertEquals(256, dao.remaining)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P17-04-C: repeated invocations while the oldest rows stay damaged must
    // still make progress beyond the first page.
    @Test
    fun `P17-04-C repeated invocations progress beyond damaged prefix`() = runBlocking {
        val vm = ChatViewModel()
        try {
            val rows = mutableListOf<SubmissionCommitWitnessEntity>()
            repeat(256) { index ->
                rows += SubmissionCommitWitnessEntity(
                    token = "s8:damaged-c-%03d".format(index),
                    draftKey = 9200L + index,
                    committedConversationId = 41L,
                    userMessageId = 4000L + index,
                    capturedRawInput = "old",
                    committedPathsJson = "!!!",
                    createdAt = 1L + index,
                )
            }
            val validToken = "s8:valid-c"
            val validPath = "/managed/valid-c.txt"
            rows += SubmissionCommitWitnessEntity(
                token = validToken,
                draftKey = 9600L,
                committedConversationId = 41L,
                userMessageId = 4999L,
                capturedRawInput = "committed-c",
                committedPathsJson = SubmissionCommitWitnessCodec.encodePaths(listOf(validPath))!!,
                createdAt = 30_000L,
            )
            val dao = PagedSubmissionDao(rows)
            val unregistered = mutableListOf<String>()
            repeat(3) {
                recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            }
            assertEquals(
                "three invocations must still reach the valid witness exactly once, got $unregistered",
                listOf(validPath),
                unregistered,
            )
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P17-04-D: restart boundary. A fresh DAO over the same durable rows (no
    // process-local cursor is carried) must retain batch progress semantics.
    @Test
    fun `P17-04-D restart retains progress beyond damaged prefix`() = runBlocking {
        val vm = ChatViewModel()
        try {
            val backing = mutableListOf<SubmissionCommitWitnessEntity>()
            repeat(256) { index ->
                backing += SubmissionCommitWitnessEntity(
                    token = "s8:damaged-d-%03d".format(index),
                    draftKey = 9300L + index,
                    committedConversationId = 41L,
                    userMessageId = 5000L + index,
                    capturedRawInput = "old",
                    committedPathsJson = "???",
                    createdAt = 1L + index,
                )
            }
            val validToken = "s8:valid-d"
            val validPath = "/managed/valid-d.txt"
            backing += SubmissionCommitWitnessEntity(
                token = validToken,
                draftKey = 9700L,
                committedConversationId = 41L,
                userMessageId = 5999L,
                capturedRawInput = "committed-d",
                committedPathsJson = SubmissionCommitWitnessCodec.encodePaths(listOf(validPath))!!,
                createdAt = 40_000L,
            )
            val unregistered = mutableListOf<String>()
            // First process lifetime converges the valid witness in one pass.
            recoverSchema8SubmissionWitnesses(vm, PagedSubmissionDao(backing)) { unregistered += it }
            assertEquals(listOf(validPath), unregistered)
            // Simulated restart: a new DAO over the same durable backing rows.
            // The resolved witness is gone; the damaged prefix is revisited
            // without double cleanup or errors.
            val restartedDao = PagedSubmissionDao(backing)
            recoverSchema8SubmissionWitnesses(vm, restartedDao) { unregistered += it }

            assertEquals(
                "restart re-scan must be a clean no-op, got $unregistered",
                listOf(validPath),
                unregistered,
            )
            assertTrue(restartedDao.deletedTokens.isEmpty())
            assertEquals(256, restartedDao.remaining)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P17-04-E: tied timestamps with different tokens must traverse stably and
    // completely (300 rows, one createdAt).
    @Test
    fun `P17-04-E tied timestamps traverse deterministically without loss`() = runBlocking {
        val vm = ChatViewModel()
        try {
            val rows = mutableListOf<SubmissionCommitWitnessEntity>()
            val expected = mutableListOf<String>()
            repeat(300) { index ->
                val token = "s8:tied-%03d".format(index)
                val path = "/managed/tied-$index.txt"
                expected += path
                rows += SubmissionCommitWitnessEntity(
                    token = token,
                    draftKey = 9800L + index,
                    committedConversationId = 41L,
                    userMessageId = 6000L + index,
                    capturedRawInput = "committed-$index",
                    committedPathsJson = SubmissionCommitWitnessCodec.encodePaths(listOf(path))!!,
                    createdAt = 777L,
                )
            }
            // Deterministic shuffle of insertion order; traversal order must
            // still be the stable (createdAt, token) order.
            rows.shuffle(java.util.Random(17L))
            val dao = PagedSubmissionDao(rows)
            val unregistered = mutableListOf<String>()
            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }

            assertEquals(300, unregistered.size)
            assertEquals(expected.sorted(), unregistered.sorted())
            assertEquals(0, dao.remaining)
            // Stable order: page visits must be token-sorted within the tie.
            val visited = dao.visitedTokens
            assertEquals(visited.sorted(), visited)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P17-04-F: witnesses inserted concurrently between batches must not
    // starve, duplicate destructive cleanup, or regress the traversal.
    @Test
    fun `P17-04-F concurrent insertions do not starve or duplicate`() = runBlocking {
        val vm = ChatViewModel()
        try {
            val backing = mutableListOf<SubmissionCommitWitnessEntity>()
            repeat(255) { index ->
                backing += SubmissionCommitWitnessEntity(
                    token = "s8:damaged-f-%03d".format(index),
                    draftKey = 9400L + index,
                    committedConversationId = 41L,
                    userMessageId = 7000L + index,
                    capturedRawInput = "old",
                    committedPathsJson = "{bad json",
                    createdAt = 1L + index,
                )
            }
            // Row 256 of the first page is healthy-but-superseded: its
            // unregister callback fires mid-scan and inserts a newer witness.
            val firstPath = "/managed/first-f.txt"
            backing += SubmissionCommitWitnessEntity(
                token = "s8:first-f",
                draftKey = 9400L + 255,
                committedConversationId = 41L,
                userMessageId = 7255L,
                capturedRawInput = "first",
                committedPathsJson = SubmissionCommitWitnessCodec.encodePaths(listOf(firstPath))!!,
                createdAt = 256L,
            )
            val dao = PagedSubmissionDao(backing)
            val unregistered = mutableListOf<String>()
            var inserted = false
            recoverSchema8SubmissionWitnesses(vm, dao) { path ->
                unregistered += path
                if (!inserted) {
                    inserted = true
                    dao.insertNow(
                        SubmissionCommitWitnessEntity(
                            token = "s8:inserted-during-recovery",
                            draftKey = 9900L,
                            committedConversationId = 41L,
                            userMessageId = 7999L,
                            capturedRawInput = "late",
                            committedPathsJson = SubmissionCommitWitnessCodec.encodePaths(
                                listOf("/managed/late.txt")
                            )!!,
                            createdAt = 50_000L,
                        )
                    )
                }
            }
            assertTrue(
                "a witness inserted during recovery must still be reached, got $unregistered",
                unregistered.contains("/managed/late.txt"),
            )
            assertEquals(1, unregistered.count { it == "/managed/late.txt" })
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P17-04-G (control): a transient paginated-read failure preserves every
    // witness and the next invocation resumes correctly.
    @Test
    fun `P17-04-G transient page read failure preserves position safely`() = runBlocking {
        val vm = ChatViewModel()
        try {
            val validToken = "s8:valid-g"
            val validPath = "/managed/valid-g.txt"
            val dao = PagedSubmissionDao(
                mutableListOf(
                    SubmissionCommitWitnessEntity(
                        token = validToken,
                        draftKey = 9950L,
                        committedConversationId = 41L,
                        userMessageId = 8999L,
                        capturedRawInput = "committed-g",
                        committedPathsJson = SubmissionCommitWitnessCodec.encodePaths(listOf(validPath))!!,
                        createdAt = 60_000L,
                    )
                )
            )
            val unregistered = mutableListOf<String>()
            dao.failNextGetBatch = RuntimeException("page read unavailable")
            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            assertTrue("failed page must process nothing, got $unregistered", unregistered.isEmpty())
            assertEquals(1, dao.remaining)

            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            assertEquals(listOf(validPath), unregistered)
            assertEquals(0, dao.remaining)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P17-04-H: after the scan reaches the end, earlier unresolved witnesses
    // are revisited on the next pass (no permanent abandonment) while resolved
    // ones are never processed twice.
    @Test
    fun `P17-04-H exhausted scan revisits failed entries without double cleanup`() = runBlocking {
        val vm = ChatViewModel()
        try {
            val failOnceToken = "s8:flaky-h"
            val failOncePath = "/managed/flaky-h.txt"
            val okToken = "s8:ok-h"
            val okPath = "/managed/ok-h.txt"
            val dao = PagedSubmissionDao(
                mutableListOf(
                    SubmissionCommitWitnessEntity(
                        token = failOnceToken,
                        draftKey = 9960L,
                        committedConversationId = 41L,
                        userMessageId = 9001L,
                        capturedRawInput = "flaky",
                        committedPathsJson = SubmissionCommitWitnessCodec.encodePaths(listOf(failOncePath))!!,
                        createdAt = 70_000L,
                    ),
                    SubmissionCommitWitnessEntity(
                        token = okToken,
                        draftKey = 9961L,
                        committedConversationId = 41L,
                        userMessageId = 9002L,
                        capturedRawInput = "ok",
                        committedPathsJson = SubmissionCommitWitnessCodec.encodePaths(listOf(okPath))!!,
                        createdAt = 70_001L,
                    ),
                )
            )
            val unregistered = mutableListOf<String>()
            var attempts = 0
            // Both drafts hold their tokens so the reconcile path (not the
            // superseded path) is exercised for each witness.
            assertTrue(
                vm.draftMachine.updateCritical(9960L) {
                    it.copy(
                        rawInput = "flaky",
                        pendingAttachments = listOf(
                            PendingAttachmentIdentity("flaky-h.txt", "text/plain", failOncePath)
                        ),
                        activeSubmissionToken = failOnceToken,
                    )
                }
            )
            assertTrue(
                vm.draftMachine.updateCritical(9961L) {
                    it.copy(
                        rawInput = "ok",
                        pendingAttachments = listOf(
                            PendingAttachmentIdentity("ok-h.txt", "text/plain", okPath)
                        ),
                        activeSubmissionToken = okToken,
                    )
                }
            )
            suspend fun attempt() {
                attempts++
                recoverSchema8SubmissionWitnesses(
                    vm,
                    dao,
                    reconcileCommitted = { witness, committed ->
                        if (witness.token == failOnceToken && attempts == 1) {
                            throw RuntimeException("transient reconcile fault")
                        }
                        vm.reconcileCommittedSubmission(
                            snapshot = com.projectnuke.fusion.chat.ComposerSubmissionSnapshot(
                                token = witness.token,
                                conversationId = witness.draftKey,
                                rawInput = witness.capturedRawInput,
                                pendingAttachments = emptyList(),
                                version = 0L,
                            ),
                            committedPaths = committed,
                        )
                    },
                ) { unregistered += it }
            }
            attempt()
            assertEquals(
                "first pass recovers only the healthy witness, got $unregistered",
                listOf(okPath),
                unregistered,
            )
            assertEquals(1, dao.remaining)

            attempt()
            assertEquals(
                "second pass reconsiders the earlier failed witness exactly once, got $unregistered",
                listOf(okPath, failOncePath),
                unregistered,
            )
            assertEquals(0, dao.remaining)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P17-04-RV-A: the same fixed-first-batch shape in response-version
    // recovery. The oldest 256 witnesses hit a persistent per-row fault; a
    // newer valid witness must still be reached.
    @Test
    fun `P17-04-RV-A valid response witness beyond stuck oldest page is recovered`() {
        val context = FakePrefsContext(PlatformSharedPreferences())
        val chatDao = TrackingChatDao()
        val rows = mutableListOf<ResponseVersionCommitWitnessEntity>()
        val stuckIds = mutableSetOf<Long>()
        repeat(256) { index ->
            val messageId = 100_000L + index
            stuckIds += messageId
            val conversationId = 5000L + index
            chatDao.conversations[conversationId] = ConversationEntity(
                id = conversationId, title = "c$conversationId", createdAt = 1L, updatedAt = 1L
            )
            val targetId = 200_000L + index
            daoMessages(chatDao, messageId, conversationId, targetId)
            rows += ResponseVersionCommitWitnessEntity(
                messageId = messageId,
                conversationId = conversationId,
                targetAssistantMessageId = targetId,
                groupId = 7000L + index,
                createdAt = 1L + index,
            )
        }
        val validConversationId = 99991L
        chatDao.conversations[validConversationId] = ConversationEntity(
            id = validConversationId, title = "valid", createdAt = 1L, updatedAt = 1L
        )
        val validMessageId = 99992L
        val validTargetId = 99993L
        daoMessages(chatDao, validMessageId, validConversationId, validTargetId)
        rows += ResponseVersionCommitWitnessEntity(
            messageId = validMessageId,
            conversationId = validConversationId,
            targetAssistantMessageId = validTargetId,
            groupId = 4242L,
            createdAt = 80_000L,
        )
        val dao = PagedResponseDao(rows, chatDao.messages)
        dao.deleteFailures += stuckIds
        runBlocking {
            recoverResponseVersionCommitWitnesses(context, chatDao, dao)
        }
        val repaired = loadResponseVersionState(context, validConversationId)
        assertEquals(
            "newer valid response witness must be repaired despite 256 stuck oldest rows",
            validMessageId,
            repaired.activeMessageIdByGroup[4242L],
        )
        assertTrue(dao.deletedMessageIds.contains(validMessageId))
        assertTrue(dao.deletedMessageIds.none { it in stuckIds })
    }

    // P17-04-RV-E: tied timestamps across more than one page traverse fully.
    @Test
    fun `P17-04-RV-E tied response timestamps traverse without loss`() {
        val context = FakePrefsContext(PlatformSharedPreferences())
        val chatDao = TrackingChatDao()
        val rows = mutableListOf<ResponseVersionCommitWitnessEntity>()
        repeat(300) { index ->
            val conversationId = 6000L + index
            val messageId = 300_000L + index
            val targetId = 400_000L + index
            chatDao.conversations[conversationId] = ConversationEntity(
                id = conversationId, title = "c$conversationId", createdAt = 1L, updatedAt = 1L
            )
            daoMessages(chatDao, messageId, conversationId, targetId)
            rows += ResponseVersionCommitWitnessEntity(
                messageId = messageId,
                conversationId = conversationId,
                targetAssistantMessageId = targetId,
                groupId = 8000L + index,
                createdAt = 555L,
            )
        }
        rows.shuffle(java.util.Random(23L))
        val dao = PagedResponseDao(rows, chatDao.messages)
        runBlocking {
            recoverResponseVersionCommitWitnesses(context, chatDao, dao)
        }
        assertEquals(300, dao.deletedMessageIds.size)
        assertEquals(0, dao.remaining)
    }

    // P18-02: a response-version witness can outlive its own SP save (a
    // deleteWitness failure, or a crash in the save->delete window) and then
    // be superseded by a NEWER committed active selection. Recovery must not
    // let that stale witness regress the durable active pointer back to its
    // own older message; the structural membership mapping is still repaired.
    @Test
    fun `P18-02 stale witness does not regress a newer durable active selection`() {
        val context = FakePrefsContext(PlatformSharedPreferences())
        val chatDao = TrackingChatDao()
        val conversationId = 99001L
        val groupId = 99002L
        val targetId = 99003L
        val staleMessageId = 99004L // older commit whose witness was left behind
        val newerMessageId = 99005L // newer commit already active in durable state
        chatDao.conversations[conversationId] = ConversationEntity(
            id = conversationId, title = "c", createdAt = 1L, updatedAt = 1L,
        )
        chatDao.messages[targetId] = MessageEntity(
            id = targetId, conversationId = conversationId, role = "assistant",
            content = "t", createdAt = 50L,
        )
        chatDao.messages[staleMessageId] = MessageEntity(
            id = staleMessageId, conversationId = conversationId, role = "assistant",
            content = "old", createdAt = 100L,
        )
        chatDao.messages[newerMessageId] = MessageEntity(
            id = newerMessageId, conversationId = conversationId, role = "assistant",
            content = "new", createdAt = 200L,
        )
        // Durable state already reflects the newer commit as the active
        // selection for the group (stale witness's SP save was superseded).
        val durableState = ResponseVersionState(
            groupByMessageId = mapOf(
                targetId to groupId,
                staleMessageId to groupId,
                newerMessageId to groupId,
            ),
            activeMessageIdByGroup = mapOf(groupId to newerMessageId),
        )
        assertTrue(saveResponseVersionState(context, conversationId, durableState))

        val staleWitness = ResponseVersionCommitWitnessEntity(
            messageId = staleMessageId,
            conversationId = conversationId,
            targetAssistantMessageId = targetId,
            groupId = groupId,
            createdAt = 100L,
        )
        val dao = PagedResponseDao(listOf(staleWitness), chatDao.messages)
        runBlocking {
            recoverResponseVersionCommitWitnesses(context, chatDao, dao)
        }
        val repaired = loadResponseVersionState(context, conversationId)
        assertEquals(
            "a stale witness must not regress a newer durable active selection",
            newerMessageId,
            repaired.activeMessageIdByGroup[groupId],
        )
        // The structural membership mapping for the stale message is still
        // repaired so both versions stay grouped under one id.
        assertEquals(groupId, repaired.groupByMessageId[staleMessageId])
        // The stale witness is consumed; it will never be re-applied.
        assertTrue(dao.deletedMessageIds.contains(staleMessageId))
        assertEquals(0, dao.remaining)
    }

    // P18-02 control: a genuine crash-before-save witness (no newer durable
    // active selection) MUST still repair the active pointer. An absent or
    // older active selection is exactly the case the witness exists for.
    @Test
    fun `P18-02 crash-before-save witness still repairs the active pointer`() {
        val context = FakePrefsContext(PlatformSharedPreferences())
        val chatDao = TrackingChatDao()
        val conversationId = 99101L
        val groupId = 99102L
        val targetId = 99103L
        val committedMessageId = 99104L
        chatDao.conversations[conversationId] = ConversationEntity(
            id = conversationId, title = "c", createdAt = 1L, updatedAt = 1L,
        )
        chatDao.messages[targetId] = MessageEntity(
            id = targetId, conversationId = conversationId, role = "assistant",
            content = "t", createdAt = 50L,
        )
        chatDao.messages[committedMessageId] = MessageEntity(
            id = committedMessageId, conversationId = conversationId, role = "assistant",
            content = "a", createdAt = 100L,
        )
        // Durable state has the group but no active selection for it yet:
        // the process died before the SP save that would set it.
        val durableState = ResponseVersionState(
            groupByMessageId = mapOf(targetId to groupId),
            activeMessageIdByGroup = emptyMap(),
        )
        assertTrue(saveResponseVersionState(context, conversationId, durableState))

        val witness = ResponseVersionCommitWitnessEntity(
            messageId = committedMessageId,
            conversationId = conversationId,
            targetAssistantMessageId = targetId,
            groupId = groupId,
            createdAt = 100L,
        )
        val dao = PagedResponseDao(listOf(witness), chatDao.messages)
        runBlocking {
            recoverResponseVersionCommitWitnesses(context, chatDao, dao)
        }
        val repaired = loadResponseVersionState(context, conversationId)
        assertEquals(
            "a crash-before-save witness must repair the active pointer",
            committedMessageId,
            repaired.activeMessageIdByGroup[groupId],
        )
        assertTrue(dao.deletedMessageIds.contains(committedMessageId))
    }

    // P19-02-B: a Room/SP gap and rollback deletion failure leave an older
    // response witness behind. Cloud escalation then commits the implicit
    // target group, which stale recovery must not overwrite.
    @Test
    fun `P19-02-B stale response witness cannot overwrite cloud regrouping`() = runBlocking {
        val prefs = PlatformSharedPreferences()
        val context = FakePrefsContext(prefs)
        val chatDao = TrackingChatDao()
        val conversationId = 99301L
        val userMessageId = 99302L
        val targetMessageId = 99303L
        val failedResponseId = 99304L
        val cloudResponseId = 99305L
        chatDao.conversations[conversationId] = ConversationEntity(
            id = conversationId, title = "c", createdAt = 1L, updatedAt = 1L,
        )
        val userMessage = MessageEntity(
            id = userMessageId, conversationId = conversationId, role = "user",
            content = "query", createdAt = 50L,
        )
        val targetMessage = MessageEntity(
            id = targetMessageId, conversationId = conversationId, role = "assistant",
            content = "A", createdAt = 100L,
        )
        chatDao.messages[userMessageId] = userMessage
        chatDao.messages[targetMessageId] = targetMessage
        val witnessDao = PagedResponseDao(mutableListOf(), chatDao.messages)

        // ChatScreen's regeneration plan assigns the originating user ID.
        // Model an SP commit failure followed by the production rollback
        // delete also failing, so the committed Room row and witness survive.
        prefs.enqueueCommitResults(false, true)
        var persistenceFailed = false
        try {
            persistAssistantVersion(
                conversationId = conversationId,
                loadPreviousState = { loadResponseVersionState(context, conversationId) },
                buildCommitPlan = { ResponseVersionCommitPlan(targetMessageId, userMessageId) },
                insertMessage = {
                    chatDao.messages[failedResponseId] = MessageEntity(
                        id = failedResponseId, conversationId = conversationId, role = "assistant",
                        content = "B", createdAt = 200L,
                    )
                    failedResponseId
                },
                insertResponseWitness = { plan, messageId ->
                    witnessDao.insert(
                        ResponseVersionCommitWitnessEntity(
                            messageId, conversationId, plan.targetAssistantMessageId,
                            plan.groupId, 200L,
                        )
                    )
                },
                runRoomTransaction = { block -> block() },
                buildUpdatedState = { previous, plan, messageId ->
                    previous.copy(
                        groupByMessageId = previous.groupByMessageId +
                            (plan.targetAssistantMessageId to plan.groupId) + (messageId to plan.groupId),
                        activeMessageIdByGroup = previous.activeMessageIdByGroup +
                            (plan.groupId to messageId),
                    )
                },
                saveState = { saveResponseVersionStateDurably(context, conversationId, it) },
                restoreState = { saveResponseVersionStateDurably(context, conversationId, it) },
                deleteMessage = { error("simulated Room rollback delete failure for $it") },
                deleteWitness = { false },
                updateConversationTimestamp = {},
            )
        } catch (_: IllegalStateException) {
            persistenceFailed = true
        }
        assertTrue(persistenceFailed)
        assertTrue(chatDao.messages.containsKey(failedResponseId))
        assertEquals(1, witnessDao.remaining)
        assertTrue(loadResponseVersionState(context, conversationId).groupByMessageId.isEmpty())

        val trustDecision = TrustRoutingDecision(
            TrustRoute.CLOUD_SUGGEST,
            TrustRoutingReason.HIGH_COMPLEXITY_OPTIONAL_CLOUD,
            emptySet(),
        )
        val providerSnapshot = ExternalProviderRequestSnapshot(
            id = "p19-provider",
            type = AiProviderType.OPENAI,
            baseUrl = "https://api.example.com",
            modelId = "m",
            temperature = 0.7,
            maxTokens = 256,
            authMode = AiProviderAuthMode.BEARER_API_KEY,
            authHeaderName = null,
            reasoningContentEnabled = false,
            apiKeySecretId = "p19-secret",
            displayName = "P19",
        )
        val request = buildCloudEscalationSnapshot(
            conversationId,
            "p19-request",
            providerSnapshot.id,
            providerSnapshot,
            GenerationSettings(),
            false,
            "",
            listOf(ChatMessage("user", "query")),
            listOf(userMessageId),
            "query",
            "query",
            300L,
            trustDecision,
        )
        val pending = PendingCloudEscalation(
            conversationId = conversationId,
            targetAssistantMessageId = targetMessageId,
            userMessageId = userMessageId,
            versionGroupId = -targetMessageId,
            providerId = providerSnapshot.id,
            providerDisplayName = providerSnapshot.displayName,
            escalatedRequest = request,
            fullMessageEntities = listOf(userMessage, targetMessage),
            capturedResponseVersionState = ResponseVersionState(),
        )
        val cloudCoordinator = CloudEscalationCoordinator({ error("execution is not part of persistence") })
        val cloudPersisted = cloudCoordinator.persistEscalatedResponse(
            pending = pending,
            escalatedContent = "C",
            loadLive = {
                CloudEscalationLiveState(
                    conversationExists = true,
                    activeMessages = listOf(userMessage, targetMessage),
                    versions = loadResponseVersionState(context, conversationId),
                )
            },
            loadPreviousState = { loadResponseVersionState(context, conversationId) },
            insertMessage = { content ->
                chatDao.messages[cloudResponseId] = MessageEntity(
                    id = cloudResponseId, conversationId = conversationId, role = "assistant",
                    content = content, createdAt = 300L,
                )
                cloudResponseId
            },
            insertResponseWitness = { plan, messageId ->
                witnessDao.insert(
                    ResponseVersionCommitWitnessEntity(
                        messageId, conversationId, plan.targetAssistantMessageId,
                        plan.groupId, 300L,
                    )
                )
            },
            runRoomTransaction = { block -> block() },
            saveState = { saveResponseVersionStateDurably(context, conversationId, it) },
            restoreState = { saveResponseVersionStateDurably(context, conversationId, it) },
            deleteMessage = { chatDao.messages.remove(it) },
            deleteWitness = { witnessDao.delete(it) > 0 },
            updateConversationTimestamp = {},
        )
        assertEquals(-targetMessageId, cloudPersisted?.state?.groupByMessageId?.get(targetMessageId))
        assertEquals(-targetMessageId, loadResponseVersionState(context, conversationId).groupByMessageId[targetMessageId])

        recoverResponseVersionCommitWitnesses(context, chatDao, witnessDao)

        val recovered = loadResponseVersionState(context, conversationId)
        assertEquals(
            "stale Room evidence must preserve the later cloud escalation's target group",
            -targetMessageId,
            recovered.groupByMessageId[targetMessageId],
        )
        assertEquals(-targetMessageId, recovered.groupByMessageId[cloudResponseId])
        assertEquals(
            "the failed response remains a version of the target's current group",
            -targetMessageId,
            recovered.groupByMessageId[failedResponseId],
        )
        assertEquals(cloudResponseId, recovered.activeMessageIdByGroup[-targetMessageId])
        val timeline = buildChatTimeline(chatDao.messages.values.sortedBy { it.id }, recovered)
        val targetGroup = timeline.single {
            it is ChatTimelineItem.AssistantVersions && it.versions.any { version -> version.id == targetMessageId }
        } as ChatTimelineItem.AssistantVersions
        assertEquals(listOf(targetMessageId, failedResponseId, cloudResponseId), targetGroup.versions.map { it.id })
        assertEquals(cloudResponseId, targetGroup.activeMessage.id)
        assertEquals(
            listOf(userMessageId, cloudResponseId),
            activeTimelineMessages(chatDao.messages.values.sortedBy { it.id }, recovered).map { it.id },
        )
    }

    // P19-01-A: an old assistant commit witness can survive its state save;
    // a later user navigation to the older response must remain authoritative
    // after a process restart replays that witness.
    @Test
    fun `P19-01-A explicit backward navigation survives stale witness replay`() = runBlocking {
        val prefs = PlatformSharedPreferences()
        val context = FakePrefsContext(prefs)
        val chatDao = TrackingChatDao()
        val conversationId = 99201L
        val groupId = 99202L
        val originalMessageId = 99203L
        val committedMessageId = 99204L
        chatDao.conversations[conversationId] = ConversationEntity(
            id = conversationId, title = "c", createdAt = 1L, updatedAt = 1L,
        )
        chatDao.messages[originalMessageId] = MessageEntity(
            id = originalMessageId, conversationId = conversationId, role = "assistant",
            content = "A", createdAt = 100L,
        )
        val witnessDao = PagedResponseDao(mutableListOf(), chatDao.messages)

        val persisted = persistAssistantVersion(
            conversationId = conversationId,
            loadPreviousState = { loadResponseVersionState(context, conversationId) },
            buildCommitPlan = { ResponseVersionCommitPlan(originalMessageId, groupId) },
            insertMessage = {
                chatDao.messages[committedMessageId] = MessageEntity(
                    id = committedMessageId, conversationId = conversationId, role = "assistant",
                    content = "B", createdAt = 200L,
                )
                committedMessageId
            },
            insertResponseWitness = { plan, messageId ->
                witnessDao.insert(
                    ResponseVersionCommitWitnessEntity(
                        messageId = messageId,
                        conversationId = conversationId,
                        targetAssistantMessageId = plan.targetAssistantMessageId,
                        groupId = plan.groupId,
                        createdAt = 200L,
                    )
                )
            },
            runRoomTransaction = { block -> block() },
            buildUpdatedState = { previous, plan, messageId ->
                previous.copy(
                    groupByMessageId = previous.groupByMessageId +
                        (plan.targetAssistantMessageId to plan.groupId) + (messageId to plan.groupId),
                    activeMessageIdByGroup = previous.activeMessageIdByGroup +
                        (plan.groupId to messageId),
                )
            },
            saveState = { saveResponseVersionStateDurably(context, conversationId, it) },
            restoreState = { saveResponseVersionStateDurably(context, conversationId, it) },
            deleteMessage = { chatDao.messages.remove(it) },
            deleteWitness = { false }, // Model the already-supported post-save cleanup failure.
            updateConversationTimestamp = {},
        )
        assertEquals(committedMessageId, persisted.messageId)
        assertEquals(committedMessageId, loadResponseVersionState(context, conversationId).activeMessageIdByGroup[groupId])
        assertEquals(1, witnessDao.remaining)

        val selected = moveResponseVersionDurably(
            context = context,
            conversationId = conversationId,
            groupId = groupId,
            expectedOrderedMessageIds = listOf(originalMessageId, committedMessageId),
            direction = ResponseVersionDirection.PREVIOUS,
            validateConversationLive = { chatDao.getConversationById(conversationId) != null },
        )
        assertEquals(originalMessageId, selected?.activeMessageIdByGroup?.get(groupId))

        // A recreated Context models a restarted owner reading the same durable preferences.
        val commitsBeforeRecovery = prefs.commitCalls.get()
        val restartedContext = FakePrefsContext(prefs)
        recoverResponseVersionCommitWitnesses(restartedContext, chatDao, witnessDao)

        assertEquals(
            "recovery must preserve the latest committed user selection even when it points backward",
            originalMessageId,
            loadResponseVersionState(restartedContext, conversationId).activeMessageIdByGroup[groupId],
        )
        assertEquals(0, witnessDao.remaining)
        assertEquals(groupId, loadResponseVersionState(restartedContext, conversationId).groupByMessageId[committedMessageId])
        assertEquals("replay of an already committed member must not rewrite response state", commitsBeforeRecovery, prefs.commitCalls.get())
    }

    @Test
    fun `P19-01-B repeated forward and backward navigation survives an older witness`() = runBlocking {
        val prefs = PlatformSharedPreferences()
        val context = FakePrefsContext(prefs)
        val chatDao = TrackingChatDao()
        val conversationId = 99401L
        val groupId = 99402L
        val a = 99403L
        val b = 99404L
        val c = 99405L
        chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)
        listOf(a, b, c).forEach { id ->
            chatDao.messages[id] = MessageEntity(id, conversationId, "assistant", "v$id", 100L)
        }
        val state = ResponseVersionState(
            groupByMessageId = mapOf(a to groupId, b to groupId, c to groupId),
            activeMessageIdByGroup = mapOf(groupId to c),
        )
        saveResponseVersionStateDurably(context, conversationId, state)
        val witnessDao = PagedResponseDao(
            mutableListOf(ResponseVersionCommitWitnessEntity(c, conversationId, a, groupId, 7L)),
            chatDao.messages,
        )

        suspend fun move(direction: ResponseVersionDirection): ResponseVersionState? =
            moveResponseVersionDurably(
                context, conversationId, groupId, listOf(a, b, c), direction,
            ) { chatDao.getConversationById(conversationId) != null }

        assertEquals(b, move(ResponseVersionDirection.PREVIOUS)?.activeMessageIdByGroup?.get(groupId))
        assertEquals(a, move(ResponseVersionDirection.PREVIOUS)?.activeMessageIdByGroup?.get(groupId))
        assertEquals(b, move(ResponseVersionDirection.NEXT)?.activeMessageIdByGroup?.get(groupId))
        recoverResponseVersionCommitWitnesses(FakePrefsContext(prefs), chatDao, witnessDao)

        assertEquals(b, loadResponseVersionState(context, conversationId).activeMessageIdByGroup[groupId])
    }

    @Test
    fun `P19-01-C multiple stale witnesses preserve backward selection in either replay order`() = runBlocking {
        listOf(false, true).forEachIndexed { orderIndex, reverse ->
            val prefs = PlatformSharedPreferences()
            val context = FakePrefsContext(prefs)
            val chatDao = TrackingChatDao()
            val conversationId = 99500L + orderIndex
            val groupId = 99600L + orderIndex
            val a = 99700L + orderIndex * 10
            val b = a + 1
            val c = a + 2
            chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)
            listOf(a, b, c).forEach { id ->
                chatDao.messages[id] = MessageEntity(id, conversationId, "assistant", "v$id", id)
            }
            saveResponseVersionStateDurably(
                context,
                conversationId,
                ResponseVersionState(
                    groupByMessageId = mapOf(a to groupId, b to groupId, c to groupId),
                    activeMessageIdByGroup = mapOf(groupId to a),
                ),
            )
            val bCreatedAt = if (reverse) 200L else 100L
            val cCreatedAt = if (reverse) 100L else 200L
            val witnesses = mutableListOf(
                ResponseVersionCommitWitnessEntity(b, conversationId, a, groupId, bCreatedAt),
                ResponseVersionCommitWitnessEntity(c, conversationId, a, groupId, cCreatedAt),
            )
            val witnessDao = PagedResponseDao(witnesses, chatDao.messages)

            recoverResponseVersionCommitWitnesses(FakePrefsContext(prefs), chatDao, witnessDao)

            assertEquals(a, loadResponseVersionState(context, conversationId).activeMessageIdByGroup[groupId])
            assertEquals(0, witnessDao.remaining)
        }
    }

    @Test
    fun `P19-01-D crash before first state save repairs target mapping and active version`() = runBlocking {
        val prefs = PlatformSharedPreferences()
        val context = FakePrefsContext(prefs)
        val chatDao = TrackingChatDao()
        val conversationId = 99801L
        val groupId = 99802L
        val targetId = 99803L
        val insertedId = 99804L
        chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)
        chatDao.messages[targetId] = MessageEntity(targetId, conversationId, "assistant", "A", 1L)
        chatDao.messages[insertedId] = MessageEntity(insertedId, conversationId, "assistant", "B", 2L)
        val witnessDao = PagedResponseDao(
            mutableListOf(ResponseVersionCommitWitnessEntity(insertedId, conversationId, targetId, groupId, 2L)),
            chatDao.messages,
        )

        assertTrue(loadResponseVersionStateResult(context, conversationId) is ResponseVersionLoad.Missing)
        recoverResponseVersionCommitWitnesses(context, chatDao, witnessDao)
        val repaired = loadResponseVersionState(context, conversationId)
        assertEquals(groupId, repaired.groupByMessageId[targetId])
        assertEquals(groupId, repaired.groupByMessageId[insertedId])
        assertEquals(insertedId, repaired.activeMessageIdByGroup[groupId])
    }

    @Test
    fun `P19-01-F failed navigation commit is not treated as durable user intent`() = runBlocking {
        val prefs = PlatformSharedPreferences()
        val context = FakePrefsContext(prefs)
        val chatDao = TrackingChatDao()
        val conversationId = 99901L
        val groupId = 99902L
        val a = 99903L
        val b = 99904L
        chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)
        chatDao.messages[a] = MessageEntity(a, conversationId, "assistant", "A", 1L)
        chatDao.messages[b] = MessageEntity(b, conversationId, "assistant", "B", 2L)
        saveResponseVersionStateDurably(
            context,
            conversationId,
            ResponseVersionState(
                groupByMessageId = mapOf(a to groupId, b to groupId),
                activeMessageIdByGroup = mapOf(groupId to b),
            ),
        )
        val witnessDao = PagedResponseDao(
            mutableListOf(ResponseVersionCommitWitnessEntity(b, conversationId, a, groupId, 2L)),
            chatDao.messages,
        )
        // The navigation's commit fails; the following commit restores the
        // previous durable snapshot in PlatformSharedPreferences.
        prefs.enqueueCommitResults(false, true)

        val moved = moveResponseVersionDurably(
            context,
            conversationId,
            groupId,
            listOf(a, b),
            ResponseVersionDirection.PREVIOUS,
        ) { chatDao.getConversationById(conversationId) != null }
        assertEquals(null, moved)
        assertEquals(b, loadResponseVersionState(FakePrefsContext(prefs), conversationId).activeMessageIdByGroup[groupId])

        recoverResponseVersionCommitWitnesses(FakePrefsContext(prefs), chatDao, witnessDao)
        assertEquals(b, loadResponseVersionState(context, conversationId).activeMessageIdByGroup[groupId])
    }

    @Test
    fun `P19-01-G deleted conversation cannot be recreated by a surviving witness`() = runBlocking {
        val prefs = PlatformSharedPreferences()
        val context = FakePrefsContext(prefs)
        val chatDao = TrackingChatDao() // absent conversation models deletion winning the structural lease
        val conversationId = 100001L
        val targetId = 100002L
        val insertedId = 100003L
        val messages = mapOf(
            targetId to MessageEntity(targetId, conversationId, "assistant", "A", 1L),
            insertedId to MessageEntity(insertedId, conversationId, "assistant", "B", 2L),
        )
        val witnessDao = PagedResponseDao(
            mutableListOf(ResponseVersionCommitWitnessEntity(insertedId, conversationId, targetId, 55L, 2L)),
            messages,
        )

        recoverResponseVersionCommitWitnesses(context, chatDao, witnessDao)

        assertTrue(loadResponseVersionStateResult(context, conversationId) is ResponseVersionLoad.Missing)
        assertEquals(0, witnessDao.remaining)
        assertEquals(2, messages.size)
    }

    @Test
    fun `P19-03 equal and backward wall clock values do not order response recovery`() = runBlocking {
        listOf(100L to 100L, 300L to 100L).forEachIndexed { index, times ->
            val prefs = PlatformSharedPreferences()
            val context = FakePrefsContext(prefs)
            val chatDao = TrackingChatDao()
            val conversationId = 101000L + index
            val groupId = 102000L + index
            val a = 103000L + index * 10
            val b = a + 1
            chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)
            chatDao.messages[a] = MessageEntity(a, conversationId, "assistant", "A", times.first)
            chatDao.messages[b] = MessageEntity(b, conversationId, "assistant", "B", times.second)
            saveResponseVersionStateDurably(
                context,
                conversationId,
                ResponseVersionState(
                    groupByMessageId = mapOf(a to groupId, b to groupId),
                    activeMessageIdByGroup = mapOf(groupId to a),
                ),
            )
            val witnessDao = PagedResponseDao(
                mutableListOf(ResponseVersionCommitWitnessEntity(b, conversationId, a, groupId, times.second)),
                chatDao.messages,
            )

            recoverResponseVersionCommitWitnesses(FakePrefsContext(prefs), chatDao, witnessDao)

            assertEquals(a, loadResponseVersionState(context, conversationId).activeMessageIdByGroup[groupId])
        }
    }

    @Test
    fun `P19-02-D conflicting witness groups converge independent of replay order`() = runBlocking {
        listOf(false, true).forEachIndexed { orderIndex, reverse ->
            val prefs = PlatformSharedPreferences()
            val context = FakePrefsContext(prefs)
            val chatDao = TrackingChatDao()
            val conversationId = 104000L + orderIndex
            val userGroupId = 105000L + orderIndex
            val targetId = 106000L + orderIndex * 10
            val b = targetId + 1
            val c = targetId + 2
            val laterGroupId = -targetId
            chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)
            listOf(targetId, b, c).forEachIndexed { index, id ->
                chatDao.messages[id] = MessageEntity(id, conversationId, "assistant", "v$id", index.toLong())
            }
            val bAt = if (reverse) 200L else 100L
            val cAt = if (reverse) 100L else 200L
            val witnessDao = PagedResponseDao(
                mutableListOf(
                    ResponseVersionCommitWitnessEntity(b, conversationId, targetId, userGroupId, bAt),
                    ResponseVersionCommitWitnessEntity(c, conversationId, targetId, laterGroupId, cAt),
                ),
                chatDao.messages,
            )

            recoverResponseVersionCommitWitnesses(context, chatDao, witnessDao)

            val recovered = loadResponseVersionState(context, conversationId)
            assertEquals(laterGroupId, recovered.groupByMessageId[targetId])
            assertEquals(laterGroupId, recovered.groupByMessageId[b])
            assertEquals(laterGroupId, recovered.groupByMessageId[c])
            assertEquals(c, recovered.activeMessageIdByGroup[laterGroupId])
        }
    }

    @Test
    fun `P19-02-E corrupt and unknown response state preserves valid witnesses`() = runBlocking {
        listOf(false, true).forEachIndexed { index, unknown ->
            val prefs = PlatformSharedPreferences()
            val context = FakePrefsContext(prefs)
            val conversationId = 105000L + index
            val targetId = 106000L + index * 10
            val messageId = targetId + 1
            val groupId = targetId + 2
            val chatDao = TrackingChatDao()
            chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)
            chatDao.messages[targetId] = MessageEntity(targetId, conversationId, "assistant", "A", 1L)
            chatDao.messages[messageId] = MessageEntity(messageId, conversationId, "assistant", "B", 2L)
            prefs.edit().putString(conversationId.toString(), if (unknown) "{}" else "{bad").commit()
            if (unknown) SharedPreferencesDurability.markUnknown(prefs, conversationId.toString())
            val commitsBefore = prefs.commitCalls.get()
            val witnessDao = PagedResponseDao(
                mutableListOf(ResponseVersionCommitWitnessEntity(messageId, conversationId, targetId, groupId, 2L)),
                chatDao.messages,
            )

            recoverResponseVersionCommitWitnesses(context, chatDao, witnessDao)

            assertTrue(loadResponseVersionStateResult(context, conversationId) is ResponseVersionLoad.Corrupt)
            assertEquals("valid witness remains retryable when state is unreadable", 1, witnessDao.remaining)
            assertEquals("recovery must not rewrite unknown or corrupt state", commitsBefore, prefs.commitCalls.get())
        }
    }

    @Test
    fun `P19-02-F invalid response witnesses are discarded without creating state`() = runBlocking {
        enumValues<P19InvalidWitness>().forEachIndexed { index, invalid ->
            val prefs = PlatformSharedPreferences()
            val context = FakePrefsContext(prefs)
            val chatDao = TrackingChatDao()
            val conversationId = 107000L + index
            val targetId = 108000L + index * 10
            val messageId = targetId + 1
            chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)
            val insertedConversation = if (invalid == P19InvalidWitness.FOREIGN_INSERTED) conversationId + 500 else conversationId
            val insertedRole = if (invalid == P19InvalidWitness.WRONG_INSERTED_ROLE) "user" else "assistant"
            if (invalid != P19InvalidWitness.MISSING_INSERTED) {
                chatDao.messages[messageId] = MessageEntity(messageId, insertedConversation, insertedRole, "B", 2L)
            }
            if (invalid != P19InvalidWitness.MISSING_TARGET) {
                val targetConversation = if (invalid == P19InvalidWitness.FOREIGN_TARGET) conversationId + 600 else conversationId
                val targetRole = if (invalid == P19InvalidWitness.WRONG_TARGET_ROLE) "user" else "assistant"
                chatDao.messages[targetId] = MessageEntity(targetId, targetConversation, targetRole, "A", 1L)
            }
            val witnessDao = PagedResponseDao(
                mutableListOf(ResponseVersionCommitWitnessEntity(messageId, conversationId, targetId, targetId + 2, 2L)),
                chatDao.messages,
            )

            recoverResponseVersionCommitWitnesses(context, chatDao, witnessDao)

            assertEquals("invalid witness $invalid should be removed", 0, witnessDao.remaining)
            assertTrue("invalid witness $invalid must not create response state", loadResponseVersionStateResult(context, conversationId) is ResponseVersionLoad.Missing)
            assertEquals(0, prefs.commitCalls.get())
        }
    }

    @Test
    fun `P19-04 recovery workload measures bounded chunk work at supported sizes`() = runBlocking {
        data class Sample(
            val elapsedMs: Long,
            val rows: Int,
            val batchQueries: Int,
            val messageLookups: Int,
            val preferenceReads: Int,
            val preferenceBytesRead: Long,
            val preferenceBytesWritten: Long,
            val preferenceCommits: Int,
            val remaining: Int,
            val repeatedStartupMs: Long,
            val repeatedStartupRows: Int,
            val repeatedStartupQueries: Int,
            val repeatedStartupPreferenceReads: Int,
        )

        suspend fun runSample(count: Int, workload: P19Workload): Sample {
            val prefs = PlatformSharedPreferences()
            val context = FakePrefsContext(prefs)
            val chatDao = TrackingChatDao()
            val conversationId = 110000L + count + workload.ordinal * 100000L
            val targetId = 200000L + count + workload.ordinal * 100000L
            val groupId = targetId + 900000L
            chatDao.conversations[conversationId] = ConversationEntity(conversationId, "bench", 1L, 1L)
            val validCount = when (workload) {
                P19Workload.HEALTHY -> count
                P19Workload.DAMAGED -> count
                P19Workload.MIXED -> count / 2
            }
            val groups: MutableMap<Long, Long> = mutableMapOf()
            if (validCount > 0) groups[targetId] = groupId
            val witnesses = ArrayList<ResponseVersionCommitWitnessEntity>(count)
            repeat(count) { index ->
                val messageId = targetId + index + 1
                chatDao.messages[messageId] = MessageEntity(messageId, conversationId, "assistant", "v", index.toLong())
                val valid = index < validCount
                if (valid && workload == P19Workload.HEALTHY) groups[messageId] = groupId
                val witnessTarget = if (valid) targetId else targetId + 100000000L + index
                witnesses += ResponseVersionCommitWitnessEntity(
                    messageId, conversationId, witnessTarget, groupId, index.toLong() + 1L,
                )
            }
            if (validCount > 0) {
                chatDao.messages[targetId] = MessageEntity(targetId, conversationId, "assistant", "A", 0L)
            }
            if (workload == P19Workload.DAMAGED) {
                // Room witnesses and messages are valid, but the corresponding
                // SharedPreferences value is permanently unreadable. Recovery
                // must keep retry evidence and must not promote empty state.
                prefs.edit().putString(conversationId.toString(), "{bad").commit()
            } else {
                saveResponseVersionStateDurably(
                    context,
                    conversationId,
                    ResponseVersionState(
                        groupByMessageId = groups,
                        activeMessageIdByGroup = if (validCount == 0) {
                            emptyMap()
                        } else {
                            mapOf(groupId to targetId)
                        },
                    ),
                )
            }
            val witnessDao = PagedResponseDao(witnesses, chatDao.messages)
            prefs.getStringCalls.set(0)
            prefs.getStringBytesRead.set(0)
            prefs.putStringBytesWritten.set(0)
            prefs.commitCalls.set(0)

            val start = System.nanoTime()
            recoverResponseVersionCommitWitnesses(context, chatDao, witnessDao)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            val reads = prefs.getStringCalls.get()
            val firstBytesRead = prefs.getStringBytesRead.get()
            val firstBatchQueries = witnessDao.batchQueries
            val firstRows = witnessDao.rowsReturned
            val firstLookups = witnessDao.messageLookups
            val firstCommits = prefs.commitCalls.get()
            val firstBytesWritten = prefs.putStringBytesWritten.get()
            witnessDao.batchQueries = 0
            witnessDao.rowsReturned = 0
            witnessDao.messageLookups = 0
            witnessDao.deleteCalls = 0
            prefs.getStringCalls.set(0)
            prefs.getStringBytesRead.set(0)
            val repeatStart = System.nanoTime()
            recoverResponseVersionCommitWitnesses(context, chatDao, witnessDao)
            val repeatedStartupMs = (System.nanoTime() - repeatStart) / 1_000_000L
            val repeatedRows = witnessDao.rowsReturned
            return Sample(
                elapsedMs = elapsedMs,
                rows = firstRows,
                batchQueries = firstBatchQueries,
                messageLookups = firstLookups,
                preferenceReads = reads,
                preferenceBytesRead = firstBytesRead,
                preferenceBytesWritten = firstBytesWritten,
                preferenceCommits = firstCommits,
                remaining = witnessDao.remaining,
                repeatedStartupMs = repeatedStartupMs,
                repeatedStartupRows = repeatedRows,
                repeatedStartupQueries = witnessDao.batchQueries,
                repeatedStartupPreferenceReads = prefs.getStringCalls.get(),
            )
        }

        enumValues<P19Workload>().forEach { workload ->
            val sizes = if (workload == P19Workload.HEALTHY) {
                // A target plus 16,384 response rows exceeds the state's
                // documented 16,384-entry map limit by one.
                listOf(256, 1024, 4096, 16383)
            } else {
                listOf(256, 1024, 4096, 16384)
            }
            sizes.forEach { count ->
                val samples = List(3) { runSample(count, workload) }
                val sortedTimes = samples.map { it.elapsedMs }.sorted()
                val expectedRemaining = if (workload == P19Workload.DAMAGED) count else 0
                val expectedCommits = if (workload == P19Workload.MIXED) {
                    (count / 2 + 64 - 1) / 64
                } else {
                    0
                }
                val expectedRepeatRows = expectedRemaining
                val expectedRepeatQueries = if (expectedRepeatRows == 0) {
                    1
                } else {
                    (expectedRepeatRows + CRASH_RECOVERY_BATCH_SIZE - 1) / CRASH_RECOVERY_BATCH_SIZE +
                        if (expectedRepeatRows % CRASH_RECOVERY_BATCH_SIZE == 0) 1 else 0
                }
                samples.forEach { sample ->
                    assertEquals(count, sample.rows)
                    assertEquals(count, sample.messageLookups / 2)
                    assertEquals(expectedRemaining, sample.remaining)
                    assertEquals("bounded keyset reads", (count + CRASH_RECOVERY_BATCH_SIZE - 1) / CRASH_RECOVERY_BATCH_SIZE + if (count % CRASH_RECOVERY_BATCH_SIZE == 0) 1 else 0, sample.batchQueries)
                    assertEquals(expectedCommits, sample.preferenceCommits)
                    if (expectedCommits == 0) assertEquals(0L, sample.preferenceBytesWritten)
                    else assertTrue(sample.preferenceBytesWritten > 0L)
                    assertEquals(expectedRepeatRows, sample.repeatedStartupRows)
                    assertEquals(expectedRepeatQueries, sample.repeatedStartupQueries)
                    assertEquals(if (workload == P19Workload.DAMAGED) sample.preferenceReads else 0, sample.repeatedStartupPreferenceReads)
                }
                val representative = samples.last()
                val median = sortedTimes[sortedTimes.size / 2]
                val repeatedSorted = samples.map { it.repeatedStartupMs }.sorted()
                System.out.println(
                    "P19-BENCH workload=$workload witnesses=$count medianMs=$median maxMs=${sortedTimes.last()} " +
                        "rows=${representative.rows} batchQueries=${representative.batchQueries} " +
                        "messageLookups=${representative.messageLookups} preferenceReads=${representative.preferenceReads} " +
                        "preferenceBytesRead=${representative.preferenceBytesRead} " +
                        "preferenceBytesWritten=${representative.preferenceBytesWritten} " +
                        "preferenceCommits=${representative.preferenceCommits} perWitnessMedianUs=${median * 1000.0 / count} " +
                        "repeatStartupRows=${representative.repeatedStartupRows} " +
                        "repeatStartupQueries=${representative.repeatedStartupQueries} " +
                        "repeatStartupMedianMs=${repeatedSorted[repeatedSorted.size / 2]} repeatStartupMaxMs=${repeatedSorted.last()}",
                )
            }
        }

        data class SubmissionSample(
            val elapsedMs: Long,
            val rows: Int,
            val batchQueries: Int,
            val deleted: Int,
            val payloadBytes: Long,
            val remaining: Int,
            val repeatedStartupMs: Long,
            val repeatedRows: Int,
            val repeatedQueries: Int,
        )

        suspend fun runSubmissionSample(count: Int, workload: P19Workload): SubmissionSample {
            val vm = ChatViewModel()
            try {
                val validCount = when (workload) {
                    P19Workload.HEALTHY -> count
                    P19Workload.DAMAGED -> 0
                    P19Workload.MIXED -> count / 2
                }
                val witnesses = (0 until count).map { index ->
                    val paths = SubmissionCommitWitnessCodec.encodePaths(listOf("/p19/bench-$workload-$index"))!!
                    SubmissionCommitWitnessEntity(
                        token = "s8:p19-$workload-$count-$index",
                        draftKey = 120000L,
                        committedConversationId = 120001L,
                        userMessageId = index + 1L,
                        capturedRawInput = "bench",
                        committedPathsJson = if (index < validCount) paths else "{bad",
                        createdAt = index + 1L,
                    )
                }
                val dao = PagedSubmissionDao(witnesses.toMutableList())
                suspend fun recover() = recoverSchema8SubmissionWitnesses(
                    chatViewModel = vm,
                    witnessDao = dao,
                    reconcileCommitted = { _, _ -> true },
                    unregisterPendingAttachment = {},
                )

                val payloadBytes = witnesses.sumOf { it.committedPathsJson.toByteArray(Charsets.UTF_8).size.toLong() }
                val start = System.nanoTime()
                recover()
                val elapsedMs = (System.nanoTime() - start) / 1_000_000L
                val rows = dao.rowsReturned
                val queries = dao.batchQueries
                val deleted = dao.deleteCalls
                val remaining = dao.remaining
                dao.visitedTokens.clear()
                dao.batchQueries = 0
                dao.rowsReturned = 0
                dao.deleteCalls = 0
                val repeatStart = System.nanoTime()
                recover()
                val repeatedStartupMs = (System.nanoTime() - repeatStart) / 1_000_000L
                return SubmissionSample(
                    elapsedMs = elapsedMs,
                    rows = rows,
                    batchQueries = queries,
                    deleted = deleted,
                    payloadBytes = payloadBytes,
                    remaining = remaining,
                    repeatedStartupMs = repeatedStartupMs,
                    repeatedRows = dao.rowsReturned,
                    repeatedQueries = dao.batchQueries,
                )
            } finally {
                vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
            }
        }

        enumValues<P19Workload>().forEach { workload ->
            listOf(256, 1024, 4096, 16384).forEach { count ->
                val samples = List(3) { runSubmissionSample(count, workload) }
                val sortedTimes = samples.map { it.elapsedMs }.sorted()
                val expectedDeleted = when (workload) {
                    P19Workload.HEALTHY -> count
                    P19Workload.DAMAGED -> 0
                    P19Workload.MIXED -> count / 2
                }
                val expectedRemaining = count - expectedDeleted
                val expectedRepeatQueries = if (expectedRemaining == 0) {
                    1
                } else {
                    (expectedRemaining + CRASH_RECOVERY_BATCH_SIZE - 1) / CRASH_RECOVERY_BATCH_SIZE +
                        if (expectedRemaining % CRASH_RECOVERY_BATCH_SIZE == 0) 1 else 0
                }
                samples.forEach { sample ->
                    assertEquals(count, sample.rows)
                    assertEquals(
                        (count + CRASH_RECOVERY_BATCH_SIZE - 1) / CRASH_RECOVERY_BATCH_SIZE +
                            if (count % CRASH_RECOVERY_BATCH_SIZE == 0) 1 else 0,
                        sample.batchQueries,
                    )
                    assertEquals(expectedDeleted, sample.deleted)
                    assertEquals(expectedRemaining, sample.remaining)
                    assertEquals(expectedRemaining, sample.repeatedRows)
                    assertEquals(expectedRepeatQueries, sample.repeatedQueries)
                }
                val repeatedTimes = samples.map { it.repeatedStartupMs }.sorted()
                val representative = samples.last()
                System.out.println(
                    "P19-BENCH subsystem=SUBMISSION workload=$workload witnesses=$count " +
                        "medianMs=${sortedTimes[sortedTimes.size / 2]} maxMs=${sortedTimes.last()} " +
                        "rows=${representative.rows} batchQueries=${representative.batchQueries} " +
                        "deleted=${representative.deleted} payloadBytes=${representative.payloadBytes} " +
                        "remaining=${representative.remaining} warningRowsPerPass=${representative.remaining} " +
                        "repeatStartupRows=${representative.repeatedRows} repeatStartupQueries=${representative.repeatedQueries} " +
                        "repeatStartupMedianMs=${repeatedTimes[repeatedTimes.size / 2]} repeatStartupMaxMs=${repeatedTimes.last()} " +
                        "perWitnessMedianUs=${sortedTimes[sortedTimes.size / 2] * 1000.0 / count}",
                )
            }
        }

        val repairPrefs = PlatformSharedPreferences()
        val repairContext = FakePrefsContext(repairPrefs)
        val repairChatDao = TrackingChatDao()
        val repairConversationId = 980001L
        val repairTargetId = 980002L
        val repairGroupId = 980003L
        repairChatDao.conversations[repairConversationId] = ConversationEntity(repairConversationId, "repair", 1L, 1L)
        repairChatDao.messages[repairTargetId] = MessageEntity(repairTargetId, repairConversationId, "assistant", "A", 0L)
        val repairWitnesses = (1..256).map { index ->
            val messageId = repairTargetId + index
            repairChatDao.messages[messageId] = MessageEntity(messageId, repairConversationId, "assistant", "v", index.toLong())
            ResponseVersionCommitWitnessEntity(messageId, repairConversationId, repairTargetId, repairGroupId, index.toLong())
        }.toMutableList()
        val repairWitnessDao = PagedResponseDao(repairWitnesses, repairChatDao.messages)
        val repairStart = System.nanoTime()
        recoverResponseVersionCommitWitnesses(repairContext, repairChatDao, repairWitnessDao)
        val repairElapsedMs = (System.nanoTime() - repairStart) / 1_000_000L
        assertEquals(0, repairWitnessDao.remaining)
        assertEquals("one state write per 64-row repair chunk", 4, repairPrefs.commitCalls.get())
        System.out.println(
            "P19-BENCH workload=FIRST_SAVE_REPAIR witnesses=256 medianMs=$repairElapsedMs maxMs=$repairElapsedMs " +
                "rows=${repairWitnessDao.rowsReturned} batchQueries=${repairWitnessDao.batchQueries} " +
                "messageLookups=${repairWitnessDao.messageLookups} preferenceReads=${repairPrefs.getStringCalls.get()} " +
                "preferenceBytesRead=${repairPrefs.getStringBytesRead.get()} " +
                "preferenceBytesWritten=${repairPrefs.putStringBytesWritten.get()} preferenceCommits=${repairPrefs.commitCalls.get()}",
        )

        // The state codec caps each group map at 16,384 members. One target
        // plus 16,384 distinct versions exceeds that supported ceiling, so
        // the recovery must preserve the witnesses instead of rewriting an
        // unreadable oversized state.
        val prefs = PlatformSharedPreferences()
        val context = FakePrefsContext(prefs)
        val chatDao = TrackingChatDao()
        val conversationId = 990001L
        val targetId = 990002L
        val groupId = 990003L
        chatDao.conversations[conversationId] = ConversationEntity(conversationId, "limit", 1L, 1L)
        chatDao.messages[targetId] = MessageEntity(targetId, conversationId, "assistant", "A", 0L)
        val witnessId = targetId + 1
        val witnesses = mutableListOf(
            ResponseVersionCommitWitnessEntity(witnessId, conversationId, targetId, groupId, 1L),
        )
        (1..16384).forEach { index ->
            val messageId = targetId + index
            chatDao.messages[messageId] = MessageEntity(messageId, conversationId, "assistant", "v", index.toLong())
        }
        saveResponseVersionStateDurably(
            context,
            conversationId,
            ResponseVersionState(
                groupByMessageId = (1..16384).associate { index ->
                    (targetId + index) to groupId
                },
                activeMessageIdByGroup = mapOf(groupId to targetId),
            ),
        )
        assertTrue(loadResponseVersionStateResult(context, conversationId) is ResponseVersionLoad.Valid)
        val witnessDao = PagedResponseDao(witnesses, chatDao.messages)
        recoverResponseVersionCommitWitnesses(context, chatDao, witnessDao)
        assertEquals("repair that would exceed the state map limit preserves its witness", 1, witnessDao.remaining)
        System.out.println("P19-BENCH workload=HEALTHY witnesses=16384 sameConversation=UNSUPPORTED reason=target-plus-witnesses-exceeds-16384-map-entry-limit")
    }

    private fun daoMessages(
        chatDao: TrackingChatDao,
        messageId: Long,
        conversationId: Long,
        targetId: Long,
    ) {
        chatDao.messages[messageId] = MessageEntity(
            id = messageId, conversationId = conversationId, role = "assistant",
            content = "a$messageId", createdAt = 1L,
        )
        chatDao.messages[targetId] = MessageEntity(
            id = targetId, conversationId = conversationId, role = "assistant",
            content = "b$targetId", createdAt = 2L,
        )
    }

    // Limit-honoring fake mirroring the Room
    // `ORDER BY createdAt ASC, token ASC LIMIT :limit` semantics. The backing
    // list is shared (never copied) so a re-created DAO over the same list
    // faithfully models a process restart over one durable table.
    private class PagedSubmissionDao(
        private val rows: MutableList<SubmissionCommitWitnessEntity>,
    ) : SubmissionCommitWitnessDao {
        val visitedTokens = mutableListOf<String>()
        val deletedTokens = mutableListOf<String>()
        var failNextGetBatch: Throwable? = null
        var batchQueries: Int = 0
        var rowsReturned: Int = 0
        var deleteCalls: Int = 0

        val remaining: Int get() = rows.size

        private fun ordered(): List<SubmissionCommitWitnessEntity> =
            rows.sortedWith(compareBy({ it.createdAt }, { it.token }))

        override suspend fun insert(witness: SubmissionCommitWitnessEntity) {
            insertNow(witness)
        }

        fun insertNow(witness: SubmissionCommitWitnessEntity) {
            rows.removeAll { it.token == witness.token }
            rows += witness
        }

        override suspend fun get(token: String): SubmissionCommitWitnessEntity? =
            rows.firstOrNull { it.token == token }

        override suspend fun getBatch(limit: Int): List<SubmissionCommitWitnessEntity> {
            batchQueries++
            failNextGetBatch?.let { failure ->
                failNextGetBatch = null
                throw failure
            }
            return ordered().take(limit).also { page ->
                rowsReturned += page.size
                visitedTokens += page.map { it.token }
            }
        }

        override suspend fun getBatchAfter(
            createdAt: Long,
            token: String,
            limit: Int,
        ): List<SubmissionCommitWitnessEntity> {
            batchQueries++
            failNextGetBatch?.let { failure ->
                failNextGetBatch = null
                throw failure
            }
            return ordered()
                .dropWhile { it.createdAt < createdAt || (it.createdAt == createdAt && it.token <= token) }
                .take(limit)
                .also { page ->
                    rowsReturned += page.size
                    visitedTokens += page.map { it.token }
                }
        }

        override suspend fun delete(token: String): Int {
            deleteCalls++
            return if (rows.removeAll { it.token == token }) {
                deletedTokens += token
                1
            } else {
                0
            }
        }
    }

    private class PagedResponseDao(
        backing: List<ResponseVersionCommitWitnessEntity>,
        private val messages: Map<Long, MessageEntity>,
    ) : ResponseVersionCommitWitnessDao {
        private val rows = backing.toMutableList()
        val deletedMessageIds = mutableListOf<Long>()
        val deleteFailures = mutableSetOf<Long>()
        var failNextGetBatch: Throwable? = null
        var batchQueries: Int = 0
        var rowsReturned: Int = 0
        var messageLookups: Int = 0
        var deleteCalls: Int = 0

        val remaining: Int get() = rows.size

        private fun ordered(): List<ResponseVersionCommitWitnessEntity> =
            rows.sortedWith(compareBy({ it.createdAt }, { it.messageId }))

        override suspend fun insert(witness: ResponseVersionCommitWitnessEntity) {
            rows.removeAll { it.messageId == witness.messageId }
            rows += witness
        }

        override suspend fun getBatch(limit: Int): List<ResponseVersionCommitWitnessEntity> {
            batchQueries++
            failNextGetBatch?.let { failure ->
                failNextGetBatch = null
                throw failure
            }
            return ordered().take(limit).also { rowsReturned += it.size }
        }

        override suspend fun getBatchAfter(
            createdAt: Long,
            messageId: Long,
            limit: Int,
        ): List<ResponseVersionCommitWitnessEntity> {
            batchQueries++
            failNextGetBatch?.let { failure ->
                failNextGetBatch = null
                throw failure
            }
            return ordered()
                .dropWhile { it.createdAt < createdAt || (it.createdAt == createdAt && it.messageId <= messageId) }
                .take(limit)
                .also { rowsReturned += it.size }
        }

        override suspend fun getByMessageId(messageId: Long): ResponseVersionCommitWitnessEntity? =
            rows.firstOrNull { it.messageId == messageId }

        override suspend fun delete(messageId: Long): Int {
            deleteCalls++
            deleteFailures.firstOrNull { it == messageId }?.let {
                throw RuntimeException("persistent witness delete fault for $messageId")
            }
            return if (rows.removeAll { it.messageId == messageId }) {
                deletedMessageIds += messageId
                1
            } else {
                0
            }
        }

        override suspend fun getMessageById(messageId: Long): MessageEntity? {
            messageLookups++
            return messages[messageId]
        }
    }

    private class FakePrefsContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
    }

    private class TrackingChatDao : ChatDao {
        val conversationLookups = mutableListOf<Long>()
        val conversations = mutableMapOf<Long, ConversationEntity>()
        val messages = mutableMapOf<Long, MessageEntity>()

        override suspend fun getConversationById(conversationId: Long): ConversationEntity? {
            conversationLookups += conversationId
            return conversations[conversationId]
        }

        override suspend fun getMessageIdentityById(messageId: Long): MessageIdentity? = null
        override suspend fun getMessageById(messageId: Long): MessageEntity? = null
        override suspend fun insertConversation(conversation: ConversationEntity): Long = 0L
        override suspend fun insertMessage(message: MessageEntity): Long = 0L
        override fun observeConversations(): Flow<List<ConversationEntity>> = emptyFlow()
        override fun observeConversationsLimited(limit: Int): Flow<List<ConversationEntity>> = emptyFlow()
        override fun observeArchivedConversations(): Flow<List<ConversationEntity>> = emptyFlow()
        override fun observeArchivedConversationsLimited(limit: Int): Flow<List<ConversationEntity>> =
            emptyFlow()

        override fun observeConversationIdsMatchingMessages(query: String): Flow<List<Long>> =
            emptyFlow()

        override fun observeConversationIdsMatchingMessagesLimited(
            query: String,
            limit: Int,
        ): Flow<List<Long>> = emptyFlow()

        override fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> = emptyFlow()
        override fun observeMessagesLatestLimited(
            conversationId: Long,
            limit: Int,
        ): Flow<List<MessageEntity>> = emptyFlow()

        override fun observeMessageCountForConversation(conversationId: Long): Flow<Int> = emptyFlow()
        override fun observeMessageIdentitiesForConversation(conversationId: Long): Flow<List<MessageIdentity>> =
            emptyFlow()

        override suspend fun getMessagesForConversation(conversationId: Long): List<MessageEntity> =
            emptyList()

        override suspend fun getMessageIdentitiesForConversation(conversationId: Long): List<MessageIdentity> =
            emptyList()

        override suspend fun getMessagesLatestLimited(
            conversationId: Long,
            limit: Int,
        ): List<MessageEntity> = emptyList()

        override suspend fun getAllMessageContents(): List<String> = emptyList()
        override suspend fun getMessageContentPage(
            afterId: Long,
            limit: Int,
        ): List<MessageContentPageRow> = emptyList()

        override suspend fun getLatestConversation(): ConversationEntity? = null
        override suspend fun getConversationCount(): Int = 0
        override suspend fun getMessageCount(): Int = 0
        override suspend fun getArchivedConversationCount(): Int = 0
        override suspend fun getMessageCountForConversation(conversationId: Long): Int = 0
        override suspend fun updateConversationTime(conversationId: Long, updatedAt: Long) = Unit
        override suspend fun updateMessageContent(messageId: Long, content: String) = Unit
        override suspend fun updateConversationPinned(conversationId: Long, isPinned: Boolean) = Unit
        override suspend fun updateConversationTitle(conversationId: Long, title: String) = Unit
        override suspend fun archiveConversation(conversationId: Long) = Unit
        override suspend fun setConversationArchived(conversationId: Long, archived: Boolean) = Unit
        override suspend fun deleteConversation(conversationId: Long) = Unit
        override suspend fun deleteMessagesForConversation(conversationId: Long) = Unit
        override suspend fun deleteMessageById(messageId: Long) = Unit
        override suspend fun getMessageContentHitsLike(
            query: String,
            limit: Int,
            offset: Int,
        ): List<MessageContentHit> = emptyList()
    }
}
