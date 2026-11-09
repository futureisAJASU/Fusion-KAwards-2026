package com.projectnuke.fusion.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectWorkPlanReceiptPolicyTest {
    private val planState = ProjectWorkPlanReceiptPlanState(
        projectId = 9L,
        status = ProjectWorkPlanStatus.ACTIVE.name,
        archivedAt = null,
    )
    private val stepState = ProjectWorkPlanReceiptStepState(
        planId = "plan-1",
        type = ProjectWorkPlanStepType.RESEARCH.name,
        state = ProjectWorkPlanStepState.RUNNING.name,
        activeAttemptId = "attempt-3",
        attemptCount = 1,
        linkedTaskId = null,
    )

    @Test
    fun `the exact active message receipt for the owning attempt is accepted`() {
        val verdict = ProjectWorkPlanReceiptPolicy.validate(messageReceipt(), planState, stepState)
        assertEquals(ProjectWorkPlanReceiptVerdict.Accept, verdict)
    }

    @Test
    fun `selection and extraction receipts are accepted without message authority`() {
        val selectionVerdict = ProjectWorkPlanReceiptPolicy.validate(
            messageReceipt().copy(
                stepType = ProjectWorkPlanStepType.REVIEW_KNOWLEDGE,
                resultKind = ProjectWorkPlanResultReceipt.KIND_SELECTION,
                resultId = "s:${"a".repeat(64)}",
                conversationId = null,
                userMessageId = null,
            ),
            planState,
            stepState.copy(type = ProjectWorkPlanStepType.REVIEW_KNOWLEDGE.name),
        )
        assertEquals(ProjectWorkPlanReceiptVerdict.Accept, selectionVerdict)
        val extractionVerdict = ProjectWorkPlanReceiptPolicy.validate(
            messageReceipt().copy(
                stepType = ProjectWorkPlanStepType.STRUCTURED_EXTRACT,
                resultKind = ProjectWorkPlanResultReceipt.KIND_EXTRACTION,
                resultId = "x:${"b".repeat(64)}#KEY_NUMBERS",
                conversationId = null,
                userMessageId = null,
            ),
            planState,
            stepState.copy(type = ProjectWorkPlanStepType.STRUCTURED_EXTRACT.name),
        )
        assertEquals(ProjectWorkPlanReceiptVerdict.Accept, extractionVerdict)
    }

    @Test
    fun `malformed receipts fail closed regardless of durable state`() {
        val receipt = messageReceipt()
        listOf(
            receipt.copy(planId = " "),
            receipt.copy(stepId = ""),
            receipt.copy(attemptId = ""),
            receipt.copy(projectId = 0L),
            receipt.copy(resultKind = "TASK"),
            receipt.copy(resultKind = "SELECTION", resultId = " "),
            receipt.copy(resultId = "x".repeat(ProjectWorkPlanResultReceipt.MAX_RESULT_ID_CHARS + 1)),
            receipt.copy(conversationId = null),
            receipt.copy(userMessageId = null),
            receipt.copy(conversationId = 0L),
            receipt.copy(resultId = "11", userMessageId = 11L),
            receipt.copy(resultId = "not-a-number"),
        ).forEach { invalid ->
            assertEquals(
                ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.MALFORMED),
                ProjectWorkPlanReceiptPolicy.validate(invalid, planState, stepState),
            )
        }
    }

    @Test
    fun `another plan or project can never settle the receipt`() {
        val receipt = messageReceipt()
        assertEquals(
            ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_PLAN),
            ProjectWorkPlanReceiptPolicy.validate(receipt, null, stepState),
        )
        assertEquals(
            ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_PLAN),
            ProjectWorkPlanReceiptPolicy.validate(receipt, planState.copy(projectId = 10L), stepState),
        )
        assertEquals(
            ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_PLAN),
            ProjectWorkPlanReceiptPolicy.validate(receipt.copy(projectId = 10L), planState, stepState),
        )
    }

    @Test
    fun `archived and non-active plans reject settlement`() {
        val receipt = messageReceipt()
        assertEquals(
            ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.PLAN_ARCHIVED),
            ProjectWorkPlanReceiptPolicy.validate(receipt, planState.copy(archivedAt = 5L), stepState),
        )
        ProjectWorkPlanStatus.entries.filter { it != ProjectWorkPlanStatus.ACTIVE }.forEach { status ->
            assertEquals(
                ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.PLAN_NOT_ACTIVE),
                ProjectWorkPlanReceiptPolicy.validate(receipt, planState.copy(status = status.name), stepState),
            )
        }
    }

    @Test
    fun `wrong step type and linked task steps reject settlement`() {
        val receipt = messageReceipt()
        assertEquals(
            ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_STEP_TYPE),
            ProjectWorkPlanReceiptPolicy.validate(receipt, planState, stepState.copy(type = ProjectWorkPlanStepType.WRITE_DRAFT.name)),
        )
        assertEquals(
            ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_STEP_TYPE),
            ProjectWorkPlanReceiptPolicy.validate(receipt.copy(stepType = ProjectWorkPlanStepType.WRITE_DRAFT), planState, stepState),
        )
        assertEquals(
            ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_STEP_TYPE),
            ProjectWorkPlanReceiptPolicy.validate(receipt, planState, stepState.copy(type = null)),
        )
        assertEquals(
            ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_STEP),
            ProjectWorkPlanReceiptPolicy.validate(receipt, planState, stepState.copy(linkedTaskId = 77L)),
        )
        assertEquals(
            ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_STEP),
            ProjectWorkPlanReceiptPolicy.validate(receipt, planState, null),
        )
        assertEquals(
            ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_STEP),
            ProjectWorkPlanReceiptPolicy.validate(receipt, planState, stepState.copy(planId = "plan-2")),
        )
    }

    @Test
    fun `stale attempts cancelled steps and exhausted retries never settle`() {
        val receipt = messageReceipt()
        assertEquals(
            ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_ATTEMPT),
            ProjectWorkPlanReceiptPolicy.validate(receipt, planState, stepState.copy(activeAttemptId = "attempt-9")),
        )
        assertEquals(
            ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_ATTEMPT),
            ProjectWorkPlanReceiptPolicy.validate(receipt, planState, stepState.copy(activeAttemptId = null)),
        )
        listOf(
            ProjectWorkPlanStepState.READY,
            ProjectWorkPlanStepState.REVIEW,
            ProjectWorkPlanStepState.COMPLETED,
            ProjectWorkPlanStepState.SKIPPED,
            ProjectWorkPlanStepState.FAILED,
            ProjectWorkPlanStepState.PENDING,
        ).forEach { state ->
            assertEquals(
                ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.ATTEMPT_NOT_ACTIVE),
                ProjectWorkPlanReceiptPolicy.validate(receipt, planState, stepState.copy(state = state.name)),
            )
        }
        assertEquals(
            ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.ATTEMPTS_EXHAUSTED),
            ProjectWorkPlanReceiptPolicy.validate(
                receipt,
                planState,
                stepState.copy(attemptCount = ProjectWorkPlanRepository.MAX_STEP_ATTEMPTS),
            ),
        )
    }

    @Test
    fun `rejections map to stale invalid and attempt limit results`() {
        assertEquals(
            ProjectWorkPlanAttemptResult.INVALID,
            ProjectWorkPlanReceiptPolicy.attemptResultFor(ProjectWorkPlanReceiptRejection.MALFORMED),
        )
        assertEquals(
            ProjectWorkPlanAttemptResult.INVALID,
            ProjectWorkPlanReceiptPolicy.attemptResultFor(ProjectWorkPlanReceiptRejection.WRONG_STEP_TYPE),
        )
        assertEquals(
            ProjectWorkPlanAttemptResult.INVALID,
            ProjectWorkPlanReceiptPolicy.attemptResultFor(ProjectWorkPlanReceiptRejection.WRONG_RESULT_KIND),
        )
        assertEquals(
            ProjectWorkPlanAttemptResult.ATTEMPT_LIMIT,
            ProjectWorkPlanReceiptPolicy.attemptResultFor(ProjectWorkPlanReceiptRejection.ATTEMPTS_EXHAUSTED),
        )
        listOf(
            ProjectWorkPlanReceiptRejection.WRONG_PLAN,
            ProjectWorkPlanReceiptRejection.WRONG_STEP,
            ProjectWorkPlanReceiptRejection.WRONG_ATTEMPT,
            ProjectWorkPlanReceiptRejection.ATTEMPT_NOT_ACTIVE,
            ProjectWorkPlanReceiptRejection.PLAN_NOT_ACTIVE,
            ProjectWorkPlanReceiptRejection.PLAN_ARCHIVED,
            ProjectWorkPlanReceiptRejection.MISSING_MESSAGE_AUTHORITY,
        ).forEach { rejection ->
            assertEquals(
                ProjectWorkPlanAttemptResult.STALE,
                ProjectWorkPlanReceiptPolicy.attemptResultFor(rejection),
            )
        }
    }

    @Test
    fun `the first step in a running plan is receivable end to end`() {
        // Guards the shape -> ownership -> fencing ordering: a fully valid
        // receipt for the single running attempt is never rejected after
        // every durable check passes.
        val verdict = ProjectWorkPlanReceiptPolicy.validate(messageReceipt(), planState, stepState)
        assertTrue(verdict is ProjectWorkPlanReceiptVerdict.Accept)
    }

    private fun messageReceipt() = ProjectWorkPlanResultReceipt(
        planId = "plan-1",
        stepId = "step-2",
        attemptId = "attempt-3",
        projectId = 9L,
        stepType = ProjectWorkPlanStepType.RESEARCH,
        resultKind = ProjectWorkPlanResultReceipt.KIND_MESSAGE,
        resultId = "12",
        conversationId = 4L,
        userMessageId = 11L,
    )
}
