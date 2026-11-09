package com.projectnuke.fusion.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import com.projectnuke.fusion.chat.MainDispatcherIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.projectnuke.fusion.data.SubmissionCommitWitnessEntity

@OptIn(ExperimentalCoroutinesApi::class)
class MessageSubmissionCoordinatorTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule()

    @Test
    fun `schema8 user message and witness are one transaction`() = runBlocking {
        val state = SubmissionCommitState(
            conversationId = 0L,
            witnessPayload = SubmissionCommitWitnessPayload(
                token = "s8:token",
                draftKey = 0L,
                capturedRawInput = "hello",
                committedPaths = listOf("/managed/a"),
                createdAt = 10L,
            ),
        )
        val events = mutableListOf<String>()
        var witness: SubmissionCommitWitnessEntity? = null
        val result = commitAndSettleUserSubmission(
            state = state,
            parentJob = Job(),
            runInTransaction = { block ->
                events += "begin"
                block()
                events += "commit"
            },
            createConversation = { events += "conversation"; 44L },
            insertUserMessageReturningId = { id -> events += "message:$id"; 55L },
            insertSubmissionWitness = { value -> witness = value; events += "witness" },
            publishConversation = { events += "publish" },
            reconcileCommittedDraft = { events += "reconcile"; true },
            updateConversationTimestamp = { events += "timestamp" },
        )

        assertEquals(44L, result.conversationId)
        assertEquals(listOf("begin", "conversation", "message:44", "witness", "commit", "publish", "reconcile", "timestamp"), events)
        assertEquals(0L, witness!!.draftKey)
        assertEquals(44L, witness!!.committedConversationId)
        assertEquals(55L, witness!!.userMessageId)
        assertEquals("hello", witness!!.capturedRawInput)
        assertEquals("[\"/managed/a\"]", witness!!.committedPathsJson)
    }

    @Test
    fun `schema8 witness failure rolls back message boundary and never reconciles`() = runBlocking {
        val state = SubmissionCommitState(
            conversationId = 12L,
            witnessPayload = SubmissionCommitWitnessPayload(
                token = "s8:token",
                draftKey = 12L,
                capturedRawInput = "hello",
                committedPaths = emptyList(),
                createdAt = 10L,
            ),
        )
        var reconciled = false
        try {
            commitAndSettleUserSubmission(
                state = state,
                parentJob = Job(),
                runInTransaction = { block -> block() },
                createConversation = { error("must not create") },
                insertUserMessageReturningId = { 55L },
                insertSubmissionWitness = { error("witness failed") },
                publishConversation = { error("must not publish") },
                reconcileCommittedDraft = { reconciled = true; true },
                updateConversationTimestamp = {},
            )
            fail("Expected witness failure")
        } catch (_: IllegalStateException) {
            // Expected: Room would roll the message back with the witness.
        }
        assertFalse(reconciled)
        assertFalse(state.messageInserted)
        assertEquals(12L, state.conversationId)
    }

    @Test
    fun `new conversation commit preserves exact production order`() = runBlocking {
        val events = mutableListOf<String>()
        val state = SubmissionCommitState(conversationId = 0L)

        val result = commitAndSettleUserSubmission(
            state = state,
            parentJob = Job(),
            runInTransaction = { block ->
                events += "transaction-begin"
                block()
                events += "transaction-commit"
            },
            createConversation = {
                events += "conversation-insert"
                42L
            },
            insertUserMessage = { id -> events += "message-insert:$id" },
            assignCreatedConversation = { id -> events += "project-assign:$id" },
            publishConversation = { id -> events += "publish:$id" },
            reconcileCommittedDraft = { events += "reconcile"; true },
            updateConversationTimestamp = { id -> events += "timestamp:$id" },
        )

        assertEquals(
            listOf(
                "transaction-begin",
                "conversation-insert",
                "message-insert:42",
                "project-assign:42",
                "transaction-commit",
                "publish:42",
                "reconcile",
                "timestamp:42",
            ),
            events,
        )
        assertEquals(42L, result.conversationId)
        assertTrue(state.conversationWasCreated)
        assertTrue(state.messageInserted)
        assertNull(result.publicationFailure)
    }

    @Test
    fun `failed atomic transaction restores uncommitted state and never settles composer`() = runBlocking {
        val events = mutableListOf<String>()
        val state = SubmissionCommitState(conversationId = 0L)
        val expected = IllegalStateException("insert failed")

        try {
            commitAndSettleUserSubmission(
                state = state,
                parentJob = Job(),
                runInTransaction = { block ->
                    events += "transaction-begin"
                    block()
                    error("transaction must not commit")
                },
                createConversation = {
                    events += "conversation-insert"
                    7L
                },
                insertUserMessage = {
                    events += "message-insert"
                    throw expected
                },
                publishConversation = { events += "publish" },
                reconcileCommittedDraft = { events += "reconcile"; true },
                updateConversationTimestamp = { events += "timestamp" },
            )
            fail("Expected insert failure")
        } catch (actual: IllegalStateException) {
            assertEquals(expected.message, actual.message)
        }

        assertEquals(
            listOf("transaction-begin", "conversation-insert", "message-insert"),
            events,
        )
        assertEquals(0L, state.conversationId)
        assertFalse(state.conversationWasCreated)
        assertFalse(state.messageInserted)
    }

    @Test
    fun `failed project binding is part of first conversation transaction`() = runBlocking {
        val state = SubmissionCommitState(conversationId = 0L)
        var published = false

        try {
            commitAndSettleUserSubmission(
                state = state,
                parentJob = Job(),
                runInTransaction = { block -> block() },
                createConversation = { 55L },
                insertUserMessage = {},
                assignCreatedConversation = { throw IllegalStateException("binding failed") },
                publishConversation = { published = true },
                reconcileCommittedDraft = { true },
                updateConversationTimestamp = {},
            )
            fail("Expected project binding failure")
        } catch (_: IllegalStateException) {
        }

        assertEquals(0L, state.conversationId)
        assertFalse(state.conversationWasCreated)
        assertFalse(state.messageInserted)
        assertFalse(published)
    }

    @Test
    fun `existing conversation failure preserves its identity and never settles composer`() = runBlocking {
        val state = SubmissionCommitState(conversationId = 9L)
        var settled = false

        try {
            commitAndSettleUserSubmission(
                state = state,
                parentJob = Job(),
                runInTransaction = { block -> block() },
                createConversation = { error("must not create") },
                insertUserMessage = { throw IllegalArgumentException("failed") },
                publishConversation = { error("must not publish") },
                reconcileCommittedDraft = { settled = true; true },
                updateConversationTimestamp = { error("must not update") },
            )
            fail("Expected insert failure")
        } catch (_: IllegalArgumentException) {
        }

        assertEquals(9L, state.conversationId)
        assertFalse(state.conversationWasCreated)
        assertFalse(state.messageInserted)
        assertFalse(settled)
    }

    @Test
    fun `cancellation before commit performs zero side effects`() = runBlocking {
        val parentJob = Job().apply { cancel() }
        var sideEffects = 0

        try {
            commitAndSettleUserSubmission(
                state = SubmissionCommitState(0L),
                parentJob = parentJob,
                runInTransaction = { sideEffects++; it() },
                createConversation = { sideEffects++; 1L },
                insertUserMessage = { sideEffects++ },
                publishConversation = { sideEffects++ },
                reconcileCommittedDraft = { sideEffects++; true },
                updateConversationTimestamp = { sideEffects++ },
            )
            fail("Expected cancellation")
        } catch (_: CancellationException) {
        }

        assertEquals(0, sideEffects)
    }

    @Test
    fun `cancellation after transaction starts still commits and settles exactly once`() = runBlocking {
        val parentJob = Job()
        val events = mutableListOf<String>()
        val state = SubmissionCommitState(0L)

        commitAndSettleUserSubmission(
            state = state,
            parentJob = parentJob,
            runInTransaction = { block -> block() },
            createConversation = {
                events += "conversation-insert"
                parentJob.cancel()
                11L
            },
            insertUserMessage = { events += "message-insert" },
            publishConversation = { events += "publish" },
            reconcileCommittedDraft = { events += "reconcile"; true },
            updateConversationTimestamp = { events += "timestamp" },
        )

        assertEquals(
            listOf("conversation-insert", "message-insert", "publish", "reconcile", "timestamp"),
            events,
        )
        assertTrue(state.messageInserted)
    }

    @Test
    fun `transaction commit failure never reports a message or conversation as committed`() = runBlocking {
        val events = mutableListOf<String>()
        val state = SubmissionCommitState(0L)
        val expected = IllegalStateException("transaction commit failed")

        try {
            commitAndSettleUserSubmission(
                state = state,
                parentJob = Job(),
                runInTransaction = { block ->
                    block()
                    throw expected
                },
                createConversation = { 33L },
                insertUserMessage = { events += "message-insert" },
                publishConversation = { events += "publish" },
                reconcileCommittedDraft = { events += "reconcile"; true },
                updateConversationTimestamp = { events += "timestamp" },
            )
            fail("Expected transaction failure")
        } catch (actual: IllegalStateException) {
            assertEquals(expected.message, actual.message)
        }

        assertFalse(state.messageInserted)
        assertFalse(state.conversationWasCreated)
        assertEquals(0L, state.conversationId)
        assertEquals(listOf("message-insert"), events)
    }

    @Test
    fun `publication failure is reported but committed draft still reconciles`() = runBlocking {
        val publicationFailure = IllegalStateException("navigation failed")
        var reconciled = false
        var reported: Throwable? = null

        val result = commitAndSettleUserSubmission(
            state = SubmissionCommitState(0L),
            parentJob = Job(),
            runInTransaction = { block -> block() },
            createConversation = { 12L },
            insertUserMessage = {},
            publishConversation = { throw publicationFailure },
            reconcileCommittedDraft = { reconciled = true; true },
            updateConversationTimestamp = {},
            onPublicationFailure = { reported = it },
        )

        assertTrue(reconciled)
        assertSame(publicationFailure, result.publicationFailure)
        assertSame(publicationFailure, reported)
    }

    @Test
    fun `timestamp failure is best effort`() = runBlocking {
        val timestampFailure = IllegalStateException("timestamp failed")
        var reported: Throwable? = null

        val result = commitAndSettleUserSubmission(
            state = SubmissionCommitState(3L),
            parentJob = Job(),
            runInTransaction = { block -> block() },
            createConversation = { error("must not create") },
            insertUserMessage = {},
            publishConversation = {},
            reconcileCommittedDraft = { true },
            updateConversationTimestamp = { throw timestampFailure },
            onTimestampFailure = { reported = it },
        )

        assertEquals(3L, result.conversationId)
        assertSame(timestampFailure, reported)
    }

    @Test
    fun `failed reconciliation is surfaced and timestamp settlement still runs`() = runBlocking {
        val result = commitAndSettleUserSubmission(
            state = SubmissionCommitState(3L),
            parentJob = Job(),
            runInTransaction = { block -> block() },
            createConversation = { error("must not create") },
            insertUserMessage = {},
            publishConversation = {},
            reconcileCommittedDraft = { false },
            updateConversationTimestamp = {},
        )

        assertEquals(3L, result.conversationId)
        assertTrue(result.reconciliationFailed)
    }

    @Test
    fun `successful reconciliation is reported as settled`() = runBlocking {
        val result = commitAndSettleUserSubmission(
            state = SubmissionCommitState(3L),
            parentJob = Job(),
            runInTransaction = { block -> block() },
            createConversation = { error("must not create") },
            insertUserMessage = {},
            publishConversation = {},
            reconcileCommittedDraft = { true },
            updateConversationTimestamp = {},
        )

        assertFalse(result.reconciliationFailed)
    }

    @Test
    fun `reconciliation exception is reported as failed without aborting settlement`() = runBlocking {
        val result = commitAndSettleUserSubmission(
            state = SubmissionCommitState(3L),
            parentJob = Job(),
            runInTransaction = { block -> block() },
            createConversation = { error("must not create") },
            insertUserMessage = {},
            publishConversation = {},
            reconcileCommittedDraft = { throw IllegalStateException("draft store unavailable") },
            updateConversationTimestamp = {},
        )

        assertEquals(3L, result.conversationId)
        assertTrue(result.reconciliationFailed)
    }

    @Test
    fun `registry owner remains active during install and clears after success or failure`() = runBlocking {
        val owner = MessageSubmissionOwner("owner", 1L)
        var activeOwner: MessageSubmissionOwner? = owner

        val result = installGenerationRequestAndSettleOwner(
            owner = owner,
            getActiveOwner = { activeOwner },
            setActiveOwner = { activeOwner = it },
        ) {
            assertEquals(owner, activeOwner)
            "installed"
        }

        assertEquals("installed", result)
        assertNull(activeOwner)

        activeOwner = owner
        try {
            installGenerationRequestAndSettleOwner<Unit>(
                owner = owner,
                getActiveOwner = { activeOwner },
                setActiveOwner = { activeOwner = it },
            ) {
                throw IllegalStateException("install failed")
            }
            fail("Expected install failure")
        } catch (_: IllegalStateException) {
        }
        assertNull(activeOwner)
    }

    @Test
    fun `registry installation stays cancellable and stale completion preserves newer owner`() = runBlocking {
        val oldOwner = MessageSubmissionOwner("old", 1L)
        val newerOwner = MessageSubmissionOwner("new", 2L)
        var activeOwner: MessageSubmissionOwner? = oldOwner
        var installStarted = false
        var installCompleted = false

        val job = launch {
            installGenerationRequestAndSettleOwner(
                owner = oldOwner,
                getActiveOwner = { activeOwner },
                setActiveOwner = { activeOwner = it },
            ) {
                installStarted = true
                delay(Long.MAX_VALUE)
                installCompleted = true
            }
        }
        while (!installStarted) yield()
        assertEquals(oldOwner, activeOwner)
        job.cancelAndJoin()
        assertFalse(installCompleted)
        assertNull(activeOwner)

        activeOwner = newerOwner
        installGenerationRequestAndSettleOwner(
            owner = oldOwner,
            getActiveOwner = { activeOwner },
            setActiveOwner = { activeOwner = it },
        ) {
            Unit
        }
        assertEquals(newerOwner, activeOwner)
    }

    @Test
    fun `bounded failures at each pre-token stage settle UI and submission ownership`() = runTest {
        val stages = listOf(
            "before-trust-routing",
            "after-user-commit-before-registry-install",
            "inside-generation-before-search",
            "optional-search-planner",
        )
        stages.forEachIndexed { index, stage ->
            val vm = com.projectnuke.fusion.chat.ChatViewModel()
            val requestId = "request-$index"
            val owner = MessageSubmissionOwner("token-$index", 0L)
            try {
                assertTrue(vm.draftMachine.updateCritical(0L) { it.copy(activeSubmissionToken = owner.token) })
                if (index > 0) {
                    vm.update(0L) {
                        it.copy(isGenerating = true, isStopping = true, activeRequestId = requestId)
                    }
                }

                try {
                    throw IllegalStateException("injected $stage")
                } catch (failure: Throwable) {
                    assertTrue(isBoundedNonFatalRequestFailure(failure))
                    settleFailedRequestBoundary(
                        chatViewModel = vm,
                        owner = owner,
                        conversationId = if (index > 0) 0L else null,
                        requestId = if (index > 0) requestId else null,
                    )
                }

                assertFalse(vm.state(0L).isGenerating)
                assertFalse(vm.state(0L).isStopping)
                assertNull(vm.state(0L).activeRequestId)
                assertNull(vm.draft(0L).activeSubmissionToken)
                assertFalse(vm.registry.hasLogicalRequest(0L))
            } finally {
                vm.scope.cancel()
            }
        }
    }
}
