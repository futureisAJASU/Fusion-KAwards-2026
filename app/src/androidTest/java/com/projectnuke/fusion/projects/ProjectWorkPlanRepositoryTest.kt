package com.projectnuke.fusion.projects

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectConversationEntity
import com.projectnuke.fusion.data.ProjectTaskEntity
import com.projectnuke.fusion.tasks.ProjectTaskRepository
import com.projectnuke.fusion.tasks.ProjectTaskState

@RunWith(AndroidJUnit4::class)
class ProjectWorkPlanRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ProjectWorkPlanRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ProjectWorkPlanRepository(database, clock = { 100L })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `plan and ordered step identities persist through edits and reordering`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Project", createdAt = 1, updatedAt = 1))
        val result = repository.create(10, proposal(), planId = "plan", stepIds = listOf("one", "two"), now = 2)
        assertEquals(ProjectWorkPlanCreateResult.Created("plan"), result)

        assertTrue(repository.updatePlanText("plan", 10, "New title", "Goal", "Output", "Constraint"))
        assertTrue(repository.updateStepText("plan", "two", "Second edited", "Review source dates"))
        assertTrue(repository.reorder("plan", 10, listOf("two", "one")))
        val loaded = repository.load("plan", 10)!!
        assertEquals("New title", loaded.plan.title)
        assertEquals(listOf("two", "one"), loaded.steps.map { it.id })
        assertEquals("Second edited", loaded.steps.first().title)
        assertEquals(ProjectWorkPlanStepState.READY.name, loaded.steps.first().state)
        assertEquals(ProjectWorkPlanStepState.PENDING.name, loaded.steps.last().state)
    }

    @Test
    fun `execution requires one current owner and stale retry callbacks cannot settle a newer attempt`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Project", createdAt = 1, updatedAt = 1))
        repository.create(10, proposal(), planId = "plan", stepIds = listOf("one", "two"), now = 2)
        val first = repository.startStep("plan", "one", 10)!!
        assertNull(repository.startStep("plan", "one", 10))
        assertEquals(ProjectWorkPlanAttemptResult.APPLIED, repository.finishAttempt(first, "MESSAGE", "41"))
        val retry = repository.startStep("plan", "one", 10)!!
        assertEquals(ProjectWorkPlanAttemptResult.STALE, repository.finishAttempt(first, "MESSAGE", "42"))
        assertEquals(ProjectWorkPlanAttemptResult.APPLIED, repository.finishAttempt(retry, "MESSAGE", "42"))
        val loaded = repository.load("plan", 10)!!
        assertEquals(ProjectWorkPlanStepState.REVIEW.name, loaded.steps.first().state)
        assertEquals("42", loaded.steps.first().resultId)
        assertTrue(loaded.steps.first().previousResultRefs.contains("41"))
    }

    @Test
    fun `composer preparation stays in flight until the user message is durably sent`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Project", createdAt = 1, updatedAt = 1))
        val askProposal = ProjectWorkPlanProposal("Plan", "Goal", "", "", listOf(
            ProjectWorkPlanProposalStep(ProjectWorkPlanStepType.ASK_WITH_PROJECT_CONTEXT, "Ask", "Review the answer"),
        ))
        repository.create(10, askProposal, planId = "send-plan", stepIds = listOf("ask-step"), now = 2)
        val attempt = repository.startStep("send-plan", "ask-step", 10)!!
        assertEquals(ProjectWorkPlanStepState.RUNNING.name, repository.load("send-plan", 10)!!.steps.single().state)
        assertFalse(repository.completeReviewedStep("send-plan", "ask-step", 10))

        val conversationId = database.chatDao().insertConversation(
            ConversationEntity(title = "Question", createdAt = 3, updatedAt = 3),
        )
        database.projectDao().assignConversation(ProjectConversationEntity(conversationId, 10, 3))
        val messageId = database.chatDao().insertMessage(
            MessageEntity(conversationId = conversationId, role = "user", content = "Step request", createdAt = 4),
        )
        val answerId = database.chatDao().insertMessage(
            MessageEntity(conversationId = conversationId, role = "assistant", content = "Reviewed answer", createdAt = 5),
        )

        assertEquals(ProjectWorkPlanAttemptResult.APPLIED,
            repository.recordAssistantResult(attempt, conversationId, messageId, answerId))
        val step = repository.load("send-plan", 10)!!.steps.single()
        assertEquals(ProjectWorkPlanStepState.REVIEW.name, step.state)
        assertEquals("MESSAGE", step.resultKind)
        assertEquals(answerId.toString(), step.resultId)
    }

    @Test
    fun `process recovery releases in flight work without claiming completion`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Project", createdAt = 1, updatedAt = 1))
        repository.create(10, proposal(), planId = "plan", stepIds = listOf("one", "two"), now = 2)
        val attempt = repository.startStep("plan", "one", 10)!!
        assertEquals("Plan", attempt.planTitle)
        assertEquals("Goal", attempt.goal)
        assertEquals("Output", attempt.desiredOutput)
        assertEquals("Constraint", attempt.constraintText)
        assertEquals(false, repository.updatePlanText("plan", 10, "Changed", "Goal", "Output", "Constraint"))

        val recovered = repository.load("plan", 10, recover = true)!!

        assertEquals(ProjectWorkPlanStepState.READY.name, recovered.steps.first().state)
        assertTrue(recovered.steps.first().interrupted)
        assertEquals(ProjectWorkPlanStatus.ACTIVE.name, recovered.plan.status)
    }

    @Test
    fun `linked task can satisfy the task oriented step and deleted task keeps missing reference`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Project", createdAt = 1, updatedAt = 1))
        val taskProposal = ProjectWorkPlanProposal(
            title = "Plan", goal = "Goal", desiredOutput = "", constraintText = "",
            steps = listOf(ProjectWorkPlanProposalStep(ProjectWorkPlanStepType.CREATE_TASK, "Create task", "Confirm an actionable task")),
        )
        repository.create(10, taskProposal, planId = "plan", stepIds = listOf("task-step"), now = 2)
        val attempt = repository.startStep("plan", "task-step", 10)!!
        val taskId = database.projectTaskDao().insert(
            ProjectTaskEntity(projectId = 10, title = "Review", note = "", createdAt = 3, updatedAt = 3),
        )
        assertTrue(repository.linkConfirmedTask(attempt, taskId))
        assertTrue(repository.taskCompleted(taskId))
        assertEquals(ProjectWorkPlanStatus.COMPLETED.name, repository.load("plan", 10)!!.plan.status)
        database.projectTaskDao().delete(taskId, 10)
        val step = repository.load("plan", 10)!!.steps.single()
        assertNull(step.linkedTaskId)
        assertEquals(taskId.toString(), step.resultId)
    }

    @Test
    fun `explicit task conversion links atomically and Task completion settles the step`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Project", createdAt = 1, updatedAt = 1))
        val oneStep = ProjectWorkPlanProposal("Plan", "Goal", "", "", listOf(
            ProjectWorkPlanProposalStep(ProjectWorkPlanStepType.MANUAL, "Review sources", "Check two sources"),
        ))
        repository.create(10, oneStep, planId = "task-plan", stepIds = listOf("task-step"), now = 2)
        val attempt = repository.startStep("task-plan", "task-step", 10)!!

        val result = repository.createConfirmedTask(attempt, "Review sources", "Check two sources")

        assertTrue(result is ProjectWorkPlanTaskCreateResult.Created)
        val taskId = (result as ProjectWorkPlanTaskCreateResult.Created).taskId
        val step = repository.load("task-plan", 10)!!.steps.single()
        assertEquals(taskId, step.linkedTaskId)
        assertEquals("TASK", step.resultKind)
        assertEquals(ProjectWorkPlanStepState.REVIEW.name, step.state)
        // Confirmation-owner disposal may race this completed transaction. Exact-attempt
        // cleanup must not undo the durable Task or move the step out of REVIEW.
        assertFalse(repository.cancelAttempt(attempt))
        assertEquals(taskId, repository.load("task-plan", 10)!!.steps.single().linkedTaskId)
        assertEquals(ProjectWorkPlanStepState.REVIEW.name, repository.load("task-plan", 10)!!.steps.single().state)
        assertEquals(false, repository.completeReviewedStep("task-plan", "task-step", 10))
        assertTrue(ProjectTaskRepository(database).setState(10, taskId, ProjectTaskState.DONE))
        assertEquals(ProjectWorkPlanStepState.COMPLETED.name, repository.load("task-plan", 10)!!.steps.single().state)
        assertEquals(ProjectWorkPlanStatus.COMPLETED.name, repository.load("task-plan", 10)!!.plan.status)
    }

    @Test
    fun `deleted linked task stays missing and a retry retains its former provenance`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Project", createdAt = 1, updatedAt = 1))
        val taskOnly = ProjectWorkPlanProposal("Plan", "Goal", "", "", listOf(
            ProjectWorkPlanProposalStep(ProjectWorkPlanStepType.CREATE_TASK, "Review", "Review source material"),
        ))
        repository.create(10, taskOnly, planId = "plan", stepIds = listOf("step"), now = 2)
        val first = repository.startStep("plan", "step", 10)!!
        val firstResult = repository.createConfirmedTask(first, "Review", "Initial attempt")
            as ProjectWorkPlanTaskCreateResult.Created
        database.projectTaskDao().delete(firstResult.taskId, 10)
        val missing = repository.load("plan", 10)!!.steps.single()
        assertNull(missing.linkedTaskId)
        assertEquals(firstResult.taskId.toString(), missing.resultId)

        val retry = repository.startStep("plan", "step", 10)!!
        val retryResult = repository.createConfirmedTask(retry, "Review again", "Retry")
        assertTrue(retryResult is ProjectWorkPlanTaskCreateResult.Created)
        val updated = repository.load("plan", 10)!!.steps.single()
        assertTrue(updated.previousResultRefs.contains(firstResult.taskId.toString()))
    }

    @Test
    fun `confirmed decision identity is linked for user review`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Project", createdAt = 1, updatedAt = 1))
        val oneStep = ProjectWorkPlanProposal("Plan", "Goal", "", "", listOf(
            ProjectWorkPlanProposalStep(ProjectWorkPlanStepType.PROPOSE_DECISION, "Select option", "Record why"),
        ))
        repository.create(10, oneStep, planId = "decision-plan", stepIds = listOf("decision-step"), now = 2)
        val attempt = repository.startStep("decision-plan", "decision-step", 10)!!

        val result = repository.createConfirmedDecision(attempt, "Choose option A", "Evidence supports A")

        assertTrue(result is ProjectWorkPlanDecisionCreateResult.Created)
        val decisionId = (result as ProjectWorkPlanDecisionCreateResult.Created).decisionId
        val step = repository.load("decision-plan", 10)!!.steps.single()
        assertEquals("DECISION", step.resultKind)
        assertEquals(decisionId, step.resultId)
        assertEquals(ProjectWorkPlanStepState.REVIEW.name, step.state)
        assertEquals("Choose option A", database.projectDecisionDao().get(decisionId, 10)?.title)
        // Disposal cleanup is harmless after the confirmation transaction settled.
        assertFalse(repository.cancelAttempt(attempt))
        assertEquals(decisionId.toString(), repository.load("decision-plan", 10)!!.steps.single().resultId)
        assertEquals(ProjectWorkPlanStepState.REVIEW.name, repository.load("decision-plan", 10)!!.steps.single().state)
    }

    @Test
    fun `dismissed local confirmation is retryable and stale duplicate cleanup cannot release retry`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Project", createdAt = 1, updatedAt = 1))
        val taskPlan = ProjectWorkPlanProposal("Plan", "Goal", "", "", listOf(
            ProjectWorkPlanProposalStep(ProjectWorkPlanStepType.CREATE_TASK, "Review", "Create a task"),
        ))
        repository.create(10, taskPlan, planId = "confirmation-plan", stepIds = listOf("task-step"), now = 2)
        val dismissed = repository.startStep("confirmation-plan", "task-step", 10)!!
        assertEquals(ProjectWorkPlanStepState.RUNNING.name, repository.load("confirmation-plan", 10)!!.steps.single().state)

        assertTrue(repository.cancelAttempt(dismissed))
        assertFalse(repository.cancelAttempt(dismissed))
        val retry = repository.startStep("confirmation-plan", "task-step", 10)!!

        assertFalse(repository.cancelAttempt(dismissed))
        val current = repository.load("confirmation-plan", 10)!!.steps.single()
        assertEquals(ProjectWorkPlanStepState.RUNNING.name, current.state)
        assertEquals(retry.attemptId, current.activeAttemptId)
        assertTrue(dismissed.attemptId != retry.attemptId)
    }

    @Test
    fun `only one active plan per project may be created or resumed`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Project", createdAt = 1, updatedAt = 1))
        assertTrue(repository.create(10, proposal(), planId = "first", stepIds = listOf("one", "two"), now = 2) is ProjectWorkPlanCreateResult.Created)
        assertEquals(ProjectWorkPlanCreateResult.ActivePlanAlreadyExists,
            repository.create(10, proposal(), planId = "second", stepIds = listOf("three", "four"), now = 3))
        assertTrue(repository.pause("first", 10))
        assertTrue(repository.create(10, proposal(), planId = "second", stepIds = listOf("three", "four"), now = 4) is ProjectWorkPlanCreateResult.Created)
        assertEquals(false, repository.resume("first", 10))
    }

    @Test
    fun `specialized selection receipts settle only the exact active attempt`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Project", createdAt = 1, updatedAt = 1))
        val reviewProposal = ProjectWorkPlanProposal("Plan", "Goal", "", "", listOf(
            ProjectWorkPlanProposalStep(ProjectWorkPlanStepType.REVIEW_KNOWLEDGE, "Review", "Confirm two sources"),
        ))
        repository.create(10, reviewProposal, planId = "review-plan", stepIds = listOf("review-step"), now = 2)
        val attempt = repository.startStep("review-plan", "review-step", 10)!!
        val entries = listOf(
            ProjectWorkPlanResultDetails.SourceEntry(
                com.projectnuke.fusion.chat.SelectionSourceIdentity(
                    com.projectnuke.fusion.chat.SelectionSourceKind.PROJECT,
                    projectId = 10L,
                ),
                "Project",
            ),
            ProjectWorkPlanResultDetails.SourceEntry(
                com.projectnuke.fusion.chat.SelectionSourceIdentity(
                    com.projectnuke.fusion.chat.SelectionSourceKind.SAVED_KNOWLEDGE,
                    savedKnowledgeId = "note-1",
                ),
                "Note",
            ),
        )
        // Saved knowledge note-1 does not exist: deleted sources fail closed.
        val missingReceipt = ProjectWorkPlanResultReceipt(
            planId = "review-plan",
            stepId = "review-step",
            attemptId = attempt.attemptId,
            projectId = 10L,
            stepType = ProjectWorkPlanStepType.REVIEW_KNOWLEDGE,
            resultKind = ProjectWorkPlanResultReceipt.KIND_SELECTION,
            resultId = ProjectWorkPlanResultDetails.selectionResultId(entries.map { it.identity })!!,
        )
        assertEquals(ProjectWorkPlanAttemptResult.STALE, repository.settleSelectionResult(missingReceipt, entries))
        assertEquals(ProjectWorkPlanStepState.RUNNING.name, repository.load("review-plan", 10)!!.steps.single().state)

        database.savedKnowledgeDao().insert(
            com.projectnuke.fusion.data.SavedKnowledgeEntity(
                id = "note-1",
                scope = "GLOBAL",
                projectId = null,
                title = "Note",
                savedText = "body",
                provenanceType = "ASSISTANT_ANSWER",
                sourceConversationId = null,
                sourceMessageId = null,
                createdAt = 3,
                updatedAt = 3,
            ),
        )
        assertEquals(ProjectWorkPlanAttemptResult.APPLIED, repository.settleSelectionResult(missingReceipt, entries))

        val step = repository.load("review-plan", 10)!!.steps.single()
        assertEquals(ProjectWorkPlanStepState.REVIEW.name, step.state)
        assertEquals("SELECTION", step.resultKind)
        assertEquals(missingReceipt.resultId, step.resultId)
        // The durable row carries identities, never the process-local UUID.
        val stored = repository.loadStepResultSources("review-plan", "review-step")
        assertEquals(entries.map { it.identity.stableKey() }, stored.map { it.identity.stableKey() })
        assertEquals(listOf("Project", "Note"), stored.map { it.title })
        // A duplicate settlement of the already-settled attempt fails closed.
        assertEquals(ProjectWorkPlanAttemptResult.STALE, repository.settleSelectionResult(missingReceipt, entries))
    }

    @Test
    fun `deleted selected source cannot settle on retry`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Project", createdAt = 1, updatedAt = 1))
        val reviewProposal = ProjectWorkPlanProposal("Plan", "Goal", "", "", listOf(
            ProjectWorkPlanProposalStep(ProjectWorkPlanStepType.REVIEW_KNOWLEDGE, "Review", "Confirm source"),
        ))
        repository.create(10, reviewProposal, planId = "review-plan", stepIds = listOf("review-step"), now = 2)
        val conversationId = database.chatDao().insertConversation(
            ConversationEntity(title = "Source", createdAt = 3, updatedAt = 3),
        )
        val entries = listOf(
            ProjectWorkPlanResultDetails.SourceEntry(
                com.projectnuke.fusion.chat.SelectionSourceIdentity(
                    com.projectnuke.fusion.chat.SelectionSourceKind.CONVERSATION,
                    conversationId = conversationId,
                ),
                "Source",
            ),
        )
        val receiptFor = { attemptId: String ->
            ProjectWorkPlanResultReceipt(
                planId = "review-plan",
                stepId = "review-step",
                attemptId = attemptId,
                projectId = 10L,
                stepType = ProjectWorkPlanStepType.REVIEW_KNOWLEDGE,
                resultKind = ProjectWorkPlanResultReceipt.KIND_SELECTION,
                resultId = ProjectWorkPlanResultDetails.selectionResultId(entries.map { it.identity })!!,
            )
        }
        val first = repository.startStep("review-plan", "review-step", 10)!!
        assertEquals(ProjectWorkPlanAttemptResult.APPLIED, repository.settleSelectionResult(receiptFor(first.attemptId), entries))

        database.chatDao().deleteConversation(conversationId)
        val retry = repository.startStep("review-plan", "review-step", 10)!!
        assertEquals(ProjectWorkPlanAttemptResult.STALE, repository.settleSelectionResult(receiptFor(retry.attemptId), entries))
        assertEquals(ProjectWorkPlanStepState.RUNNING.name, repository.load("review-plan", 10)!!.steps.single().state)
    }

    @Test
    fun `an unrelated later assistant response cannot settle the earlier attempt`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Project", createdAt = 1, updatedAt = 1))
        val askProposal = ProjectWorkPlanProposal("Plan", "Goal", "", "", listOf(
            ProjectWorkPlanProposalStep(ProjectWorkPlanStepType.ASK_WITH_PROJECT_CONTEXT, "Ask", "Answer with context"),
        ))
        repository.create(10, askProposal, planId = "ask-plan", stepIds = listOf("ask-step"), now = 2)
        val attempt = repository.startStep("ask-plan", "ask-step", 10)!!

        val conversationId = database.chatDao().insertConversation(
            ConversationEntity(title = "Question", createdAt = 3, updatedAt = 3),
        )
        database.projectDao().assignConversation(ProjectConversationEntity(conversationId, 10, 3))
        // Work Plan user message A, generation cancelled/failed with no
        // answer; later normal user message B answered by its assistant.
        val messageA = database.chatDao().insertMessage(
            MessageEntity(conversationId = conversationId, role = "user", content = "Step request", createdAt = 4),
        )
        val messageB = database.chatDao().insertMessage(
            MessageEntity(conversationId = conversationId, role = "user", content = "Normal follow-up", createdAt = 5),
        )
        val answerB = database.chatDao().insertMessage(
            MessageEntity(conversationId = conversationId, role = "assistant", content = "Normal answer", createdAt = 6),
        )
        assertEquals(
            ProjectWorkPlanAttemptResult.STALE,
            repository.recordAssistantResult(attempt, conversationId, messageA, answerB),
        )
        assertEquals(ProjectWorkPlanStepState.RUNNING.name, repository.load("ask-plan", 10)!!.steps.single().state)

        // Issue 1: bind the original request R1 to user A before testing
        // unrelated B / regeneration scenarios, so the witness comparison
        // is exact rather than defaulting to STALE from a missing row.
        repository.noteWorkPlanGenerationAttempt(attempt, conversationId, messageA, requestId = "request-A")

        // A regenerated-style insertion grouped to another origin also fails: B
        // (message 12) is linked to another request and cannot settle A's R1.
        database.responseVersionCommitWitnessDao().insert(
            com.projectnuke.fusion.data.ResponseVersionCommitWitnessEntity(
                messageId = answerB,
                conversationId = conversationId,
                targetAssistantMessageId = answerB,
                groupId = messageB,
                createdAt = 7,
            ),
        )
        assertEquals(
            ProjectWorkPlanAttemptResult.STALE,
            repository.recordAssistantResult(attempt, conversationId, messageA, answerB),
        )
        assertEquals(ProjectWorkPlanStepState.RUNNING.name, repository.load("ask-plan", 10)!!.steps.single().state)

        // The direct answer to the committed message still settles: a
        // fresh conversation where the assistant immediately follows A.
        val ownedConversationId = database.chatDao().insertConversation(
            ConversationEntity(title = "Owned", createdAt = 8, updatedAt = 8),
        )
        database.projectDao().assignConversation(ProjectConversationEntity(ownedConversationId, 10, 8))
        val ownedUser = database.chatDao().insertMessage(
            MessageEntity(conversationId = ownedConversationId, role = "user", content = "Step request", createdAt = 9),
        )
        val ownedAnswer = database.chatDao().insertMessage(
            MessageEntity(conversationId = ownedConversationId, role = "assistant", content = "Owned answer", createdAt = 10),
        )
        // Direct answer to the committed message in the original conversation.
        // The version group proves origin (nearest preceding user would be B),
        // so settlement can still attribute it to A.
        val answerA = database.chatDao().insertMessage(
            MessageEntity(conversationId = conversationId, role = "assistant", content = "Direct answer", createdAt = 11),
        )
        database.responseVersionCommitWitnessDao().insert(
            com.projectnuke.fusion.data.ResponseVersionCommitWitnessEntity(
                messageId = answerA,
                conversationId = conversationId,
                targetAssistantMessageId = answerA,
                groupId = messageA,
                createdAt = 12,
            ),
        )
        // Issue 1: bind the original work-plan attempt to its request.
        repository.noteWorkPlanGenerationAttempt(attempt, conversationId, messageA, requestId = "request-A")
        // Note the assistant produced by the bound request; settlement
        // requires the exact R1 → assistant link.
        repository.noteWorkPlanAssistantPersisted(attempt.attemptId, "request-A", answerA)

        // A regeneration under another request for the same user cannot
        // settle the original R1-bound attempt.
        val retryConversation = database.chatDao().insertConversation(
            ConversationEntity(title = "Retry", createdAt = 13, updatedAt = 13),
        )
        database.projectDao().assignConversation(ProjectConversationEntity(retryConversation, 10, 11))
        val retryUser = database.chatDao().insertMessage(
            MessageEntity(conversationId = retryConversation, role = "user", content = "Retry", createdAt = 14),
        )
        val retryAnswer = database.chatDao().insertMessage(
            MessageEntity(conversationId = retryConversation, role = "assistant", content = "Retry answer", createdAt = 15),
        )
        database.responseVersionCommitWitnessDao().delete(answerB)
        // R2 request created; no link to R1's witness.
        repository.noteWorkPlanGenerationAttempt(attempt, retryConversation, retryUser, requestId = "request-R2")
        repository.noteWorkPlanAssistantPersisted(attempt.attemptId, "request-R2", retryAnswer)
        // The receipt claims R1 but names answerB while the witness links
        // answerA to R1; settlement fails.
        val receiptR1 = ProjectWorkPlanResultReceipt(
            planId = attempt.planId,
            stepId = attempt.stepId,
            attemptId = attempt.attemptId,
            projectId = attempt.projectId,
            stepType = attempt.type,
            resultKind = ProjectWorkPlanResultReceipt.KIND_MESSAGE,
            resultId = answerB.toString(),
            conversationId = conversationId,
            userMessageId = messageA,
            requestId = "request-A",
            submissionToken = "token-A",
        )
        // A forged requestId with the correct user/assistant never settles
        // because the witness still links to R2, not the forged value.
        assertEquals(
            ProjectWorkPlanAttemptResult.STALE,
            repository.settleSpecializedResult(receiptR1),
        )
        assertEquals(ProjectWorkPlanStepState.RUNNING.name, repository.load("ask-plan", 10)!!.steps.single().state)

        // The direct R1-bound assistant A1 (recorded for the original bound
        // request) settles when presented with the correct receipt.
        repository.noteWorkPlanAssistantPersisted(attempt.attemptId, "request-A", answerA)
        assertEquals(
            ProjectWorkPlanAttemptResult.APPLIED,
            repository.settleSpecializedResult(
                receiptR1.copy(resultId = answerA.toString(), requestId = "request-A"),
            ),
        )
    }

    @Test
    fun `receipts with a wrong step type attempt or project never settle`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Project", createdAt = 1, updatedAt = 1))
        val compareProposal = ProjectWorkPlanProposal("Plan", "Goal", "", "", listOf(
            ProjectWorkPlanProposalStep(ProjectWorkPlanStepType.COMPARE_SOURCES, "Compare", "Compare two sources"),
        ))
        repository.create(10, compareProposal, planId = "compare-plan", stepIds = listOf("compare-step"), now = 2)
        val attempt = repository.startStep("compare-plan", "compare-step", 10)!!
        // COMPARE_SOURCES settles through MESSAGE only; SELECTION and
        // EXTRACTION receipts are rejected as incompatible without
        // mutating the step, as is the generic path for those kinds.
        val selectionReceipt = ProjectWorkPlanResultReceipt(
            planId = "compare-plan",
            stepId = "compare-step",
            attemptId = attempt.attemptId,
            projectId = 10L,
            stepType = ProjectWorkPlanStepType.COMPARE_SOURCES,
            resultKind = ProjectWorkPlanResultReceipt.KIND_SELECTION,
            resultId = "s:${"c".repeat(64)}",
        )
        assertEquals(
            ProjectWorkPlanAttemptResult.INVALID,
            repository.settleSpecializedResult(selectionReceipt),
        )
        val extractionReceipt = selectionReceipt.copy(
            resultKind = ProjectWorkPlanResultReceipt.KIND_EXTRACTION,
            resultId = "x:${"c".repeat(64)}#KEY_NUMBERS",
        )
        assertEquals(
            ProjectWorkPlanAttemptResult.INVALID,
            repository.settleSpecializedResult(extractionReceipt),
        )
        val selectionEntries = listOf(
            ProjectWorkPlanResultDetails.SourceEntry(
                com.projectnuke.fusion.chat.SelectionSourceIdentity(
                    com.projectnuke.fusion.chat.SelectionSourceKind.PROJECT,
                    projectId = 10L,
                ),
                "Project",
            ),
        )
        val selectionId = ProjectWorkPlanResultDetails.selectionResultId(selectionEntries.map { it.identity })!!
        assertEquals(
            ProjectWorkPlanAttemptResult.INVALID,
            repository.settleSelectionResult(
                selectionReceipt.copy(stepType = ProjectWorkPlanStepType.WRITE_DRAFT, resultId = selectionId),
                selectionEntries,
            ),
        )

        val conversationId = database.chatDao().insertConversation(
            ConversationEntity(title = "Compare", createdAt = 3, updatedAt = 3),
        )
        database.projectDao().assignConversation(ProjectConversationEntity(conversationId, 10, 3))
        val messageId = database.chatDao().insertMessage(
            MessageEntity(conversationId = conversationId, role = "user", content = "Compare", createdAt = 4),
        )
        val answerId = database.chatDao().insertMessage(
            MessageEntity(conversationId = conversationId, role = "assistant", content = "Compared", createdAt = 5),
        )
        val messageReceiptFor = { transform: (ProjectWorkPlanResultReceipt) -> ProjectWorkPlanResultReceipt ->
            transform(
                ProjectWorkPlanResultReceipt(
                    planId = "compare-plan",
                    stepId = "compare-step",
                    attemptId = attempt.attemptId,
                    projectId = 10L,
                    stepType = ProjectWorkPlanStepType.COMPARE_SOURCES,
                    resultKind = ProjectWorkPlanResultReceipt.KIND_MESSAGE,
                    resultId = answerId.toString(),
                    conversationId = conversationId,
                    userMessageId = messageId,
                ),
            )
        }
        assertEquals(
            ProjectWorkPlanAttemptResult.STALE,
            repository.settleSpecializedResult(messageReceiptFor { it.copy(projectId = 11L) }),
        )
        assertEquals(
            ProjectWorkPlanAttemptResult.STALE,
            repository.settleSpecializedResult(messageReceiptFor { it.copy(planId = "other-plan") }),
        )
        assertEquals(
            ProjectWorkPlanAttemptResult.STALE,
            repository.settleSpecializedResult(messageReceiptFor { it.copy(attemptId = "stale-attempt") }),
        )
        assertEquals(
            ProjectWorkPlanAttemptResult.INVALID,
            repository.settleSpecializedResult(
                messageReceiptFor {
                    it.copy(resultKind = ProjectWorkPlanResultReceipt.KIND_SELECTION, resultId = " ")
                },
            ),
        )
        // Every rejection left the running attempt untouched.
        val step = repository.load("compare-plan", 10)!!.steps.single()
        assertEquals(ProjectWorkPlanStepState.RUNNING.name, step.state)
        assertNull(step.resultId)
    }

    @Test
    fun `archived plans and foreign conversation message receipts never settle`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Project", createdAt = 1, updatedAt = 1))
        val askProposal = ProjectWorkPlanProposal("Plan", "Goal", "", "", listOf(
            ProjectWorkPlanProposalStep(ProjectWorkPlanStepType.ASK_WITH_PROJECT_CONTEXT, "Ask", "Answer with context"),
        ))
        repository.create(10, askProposal, planId = "ask-plan", stepIds = listOf("ask-step"), now = 2)
        val attempt = repository.startStep("ask-plan", "ask-step", 10)!!

        // A conversation that is not assigned to this Project cannot settle.
        val conversationId = database.chatDao().insertConversation(
            ConversationEntity(title = "Foreign", createdAt = 3, updatedAt = 3),
        )
        val messageId = database.chatDao().insertMessage(
            MessageEntity(conversationId = conversationId, role = "user", content = "Step request", createdAt = 4),
        )
        val answerId = database.chatDao().insertMessage(
            MessageEntity(conversationId = conversationId, role = "assistant", content = "Answer", createdAt = 5),
        )
        assertEquals(
            ProjectWorkPlanAttemptResult.STALE,
            repository.recordAssistantResult(attempt, conversationId, messageId, answerId),
        )
        assertEquals(ProjectWorkPlanStepState.RUNNING.name, repository.load("ask-plan", 10)!!.steps.single().state)

        // After cancel/pause/archive no receipt can settle the plan.
        assertTrue(repository.cancelAttempt(attempt))
        assertTrue(repository.pause("ask-plan", 10))
        assertTrue(repository.archive("ask-plan", 10))
        assertEquals(
            ProjectWorkPlanAttemptResult.STALE,
            repository.recordAssistantResult(attempt, conversationId, messageId, answerId),
        )
    }

    private fun proposal() = ProjectWorkPlanProposal(
        title = "Plan",
        goal = "Goal",
        desiredOutput = "Output",
        constraintText = "Constraint",
        steps = listOf(
            ProjectWorkPlanProposalStep(ProjectWorkPlanStepType.MANUAL, "First", "Do first"),
            ProjectWorkPlanProposalStep(ProjectWorkPlanStepType.MANUAL, "Second", "Do second"),
        ),
    )

    @Test
    fun `repeated observational reads preserve live RUNNING attempt and receipt applies once`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Project", createdAt = 1, updatedAt = 1))
        repository.create(10, proposal(), planId = "plan", stepIds = listOf("one", "two"), now = 2)
        val attempt = repository.startStep("plan", "one", 10)!!
        repeat(3) {
            val reread = repository.load("plan", 10)!!
            assertEquals(ProjectWorkPlanStepState.RUNNING.name, reread.steps.first().state)
            assertEquals(attempt.attemptId, reread.steps.first().activeAttemptId)
        }
        val rereadActive = repository.activePlan(10)!!
        assertEquals(ProjectWorkPlanStepState.RUNNING.name, rereadActive.steps.first().state)
        assertEquals(attempt.attemptId, rereadActive.steps.first().activeAttemptId)
        assertEquals(ProjectWorkPlanAttemptResult.APPLIED, repository.finishAttempt(attempt, "MESSAGE", "41"))
        assertEquals(ProjectWorkPlanStepState.REVIEW.name, repository.load("plan", 10)!!.steps.first().state)
    }

    @Test
    fun `archived plans do not occupy bounded query before active plans are selected`() = runBlocking {
        database.projectDao().insertProject(ProjectEntity(id = 10, name = "Project", createdAt = 1, updatedAt = 1))
        // Saturate the old post-filter limit with archived rows, then add one valid plan.
        repeat(64) { index ->
            val id = "archived-$index"
            repository.create(
                10,
                ProjectWorkPlanProposal("Plan $index", "Goal", "", "", listOf(
                    ProjectWorkPlanProposalStep(ProjectWorkPlanStepType.MANUAL, "Step", "Do"),
                )),
                planId = id, stepIds = listOf("$id-step"), now = 2L,
            )
            database.projectWorkPlanDao().getPlanById(id)?.let {
                // Archive requires a terminal-adjacent status; pause then archive.
                repository.pause(id, 10)
                repository.archive(id, 10)
            }
        }
        repository.create(10, proposal(), planId = "live-plan", stepIds = listOf("live-one", "live-two"), now = 3)
        val visible = repository.plans(10, includeArchived = false)
        assertTrue(visible.any { it.id == "live-plan" })
    }
}
