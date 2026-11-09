package com.projectnuke.fusion.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding E regression: exhaustive result-kind compatibility. A receipt
 * with the correct plan/step/attempt but the wrong result kind is
 * rejected as INVALID without mutating the step. Task, Decision, and
 * Manual steps accept no generic receipt kind.
 */
class ProjectWorkPlanReceiptKindCompatibilityTest {
    private val planState = ProjectWorkPlanReceiptPlanState(
        projectId = 9L,
        status = ProjectWorkPlanStatus.ACTIVE.name,
        archivedAt = null,
    )

    private fun stepState(type: ProjectWorkPlanStepType) = ProjectWorkPlanReceiptStepState(
        planId = "plan-1",
        type = type.name,
        state = ProjectWorkPlanStepState.RUNNING.name,
        activeAttemptId = "attempt-3",
        attemptCount = 1,
        linkedTaskId = null,
    )

    private fun receipt(type: ProjectWorkPlanStepType, kind: String) = ProjectWorkPlanResultReceipt(
        planId = "plan-1",
        stepId = "step-2",
        attemptId = "attempt-3",
        projectId = 9L,
        stepType = type,
        resultKind = kind,
        resultId = when (kind) {
            ProjectWorkPlanResultReceipt.KIND_MESSAGE -> "12"
            else -> "x:${"c".repeat(64)}#TYPE"
        },
        conversationId = if (kind == ProjectWorkPlanResultReceipt.KIND_MESSAGE) 4L else null,
        userMessageId = if (kind == ProjectWorkPlanResultReceipt.KIND_MESSAGE) 11L else null,
    )

    @Test
    fun `expected semantics accept exactly the compatible kinds`() {
        val expected = mapOf(
            ProjectWorkPlanStepType.RESEARCH to setOf(ProjectWorkPlanResultReceipt.KIND_MESSAGE),
            ProjectWorkPlanStepType.COMPARE_SOURCES to setOf(ProjectWorkPlanResultReceipt.KIND_MESSAGE),
            ProjectWorkPlanStepType.ASK_WITH_PROJECT_CONTEXT to setOf(ProjectWorkPlanResultReceipt.KIND_MESSAGE),
            ProjectWorkPlanStepType.WRITE_DRAFT to setOf(ProjectWorkPlanResultReceipt.KIND_MESSAGE),
            ProjectWorkPlanStepType.STRUCTURED_EXTRACT to setOf(ProjectWorkPlanResultReceipt.KIND_EXTRACTION),
            ProjectWorkPlanStepType.REVIEW_KNOWLEDGE to setOf(
                ProjectWorkPlanResultReceipt.KIND_SELECTION,
                ProjectWorkPlanResultReceipt.KIND_MESSAGE,
            ),
        )
        expected.forEach { (type, kinds) ->
            assertEquals(kinds, ProjectWorkPlanReceiptPolicy.allowedResultKinds(type))
            kinds.forEach { kind ->
                assertEquals(
                    "accept $type + $kind",
                    ProjectWorkPlanReceiptVerdict.Accept,
                    ProjectWorkPlanReceiptPolicy.validate(receipt(type, kind), planState, stepState(type)),
                )
            }
        }
    }

    @Test
    fun `every incompatible kind for a correct active step fails closed as invalid`() {
        val allKinds = setOf(
            ProjectWorkPlanResultReceipt.KIND_MESSAGE,
            ProjectWorkPlanResultReceipt.KIND_SELECTION,
            ProjectWorkPlanResultReceipt.KIND_EXTRACTION,
        )
        val compatible = mapOf(
            ProjectWorkPlanStepType.RESEARCH to setOf(ProjectWorkPlanResultReceipt.KIND_MESSAGE),
            ProjectWorkPlanStepType.COMPARE_SOURCES to setOf(ProjectWorkPlanResultReceipt.KIND_MESSAGE),
            ProjectWorkPlanStepType.ASK_WITH_PROJECT_CONTEXT to setOf(ProjectWorkPlanResultReceipt.KIND_MESSAGE),
            ProjectWorkPlanStepType.WRITE_DRAFT to setOf(ProjectWorkPlanResultReceipt.KIND_MESSAGE),
            ProjectWorkPlanStepType.STRUCTURED_EXTRACT to setOf(ProjectWorkPlanResultReceipt.KIND_EXTRACTION),
            ProjectWorkPlanStepType.REVIEW_KNOWLEDGE to setOf(
                ProjectWorkPlanResultReceipt.KIND_SELECTION,
                ProjectWorkPlanResultReceipt.KIND_MESSAGE,
            ),
        )
        compatible.forEach { (type, kinds) ->
            (allKinds - kinds).forEach { wrong ->
                val verdict = ProjectWorkPlanReceiptPolicy.validate(receipt(type, wrong), planState, stepState(type))
                assertEquals(
                    "reject $type + $wrong",
                    ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_RESULT_KIND),
                    verdict,
                )
                assertEquals(
                    ProjectWorkPlanAttemptResult.INVALID,
                    ProjectWorkPlanReceiptPolicy.attemptResultFor(ProjectWorkPlanReceiptRejection.WRONG_RESULT_KIND),
                )
            }
        }
    }

    @Test
    fun `task decision and manual steps accept no generic receipt`() {
        listOf(
            ProjectWorkPlanStepType.CREATE_TASK,
            ProjectWorkPlanStepType.PROPOSE_DECISION,
            ProjectWorkPlanStepType.MANUAL,
        ).forEach { type ->
            assertTrue(ProjectWorkPlanReceiptPolicy.allowedResultKinds(type).isEmpty())
            setOf(
                ProjectWorkPlanResultReceipt.KIND_MESSAGE,
                ProjectWorkPlanResultReceipt.KIND_SELECTION,
                ProjectWorkPlanResultReceipt.KIND_EXTRACTION,
            ).forEach { kind ->
                assertEquals(
                    "reject $type + $kind",
                    ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_RESULT_KIND),
                    ProjectWorkPlanReceiptPolicy.validate(receipt(type, kind), planState, stepState(type)),
                )
            }
        }
    }
}
