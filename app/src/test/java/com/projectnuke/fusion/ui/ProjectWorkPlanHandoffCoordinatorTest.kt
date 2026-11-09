package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.SelectionContextPurpose
import com.projectnuke.fusion.chat.SelectionContextSnapshot
import com.projectnuke.fusion.chat.SelectionSnapshotResult
import com.projectnuke.fusion.chat.SelectionSourceIdentity
import com.projectnuke.fusion.chat.SelectionSourceKind
import com.projectnuke.fusion.chat.SelectionSourceSnapshot
import com.projectnuke.fusion.projects.ProjectWorkPlanAttempt
import com.projectnuke.fusion.projects.ProjectWorkPlanAttemptResult
import com.projectnuke.fusion.projects.ProjectWorkPlanDestination
import com.projectnuke.fusion.projects.ProjectWorkPlanExtractionContentPolicy
import com.projectnuke.fusion.projects.ProjectWorkPlanHandoffPolicy
import com.projectnuke.fusion.projects.ProjectWorkPlanHandoffPreparation
import com.projectnuke.fusion.projects.ProjectWorkPlanPreparedAction
import com.projectnuke.fusion.projects.ProjectWorkPlanResultDetails
import com.projectnuke.fusion.projects.ProjectWorkPlanResultReceipt
import com.projectnuke.fusion.projects.ProjectWorkPlanStepType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding H regression: orchestration authority lives in the coordinator.
 * Compose routes typed events; the coordinator validates and settles
 * through the gateway without owning any UI.
 */
class ProjectWorkPlanHandoffCoordinatorTest {
    private class FakeGateway(
        var selectionResult: ProjectWorkPlanAttemptResult = ProjectWorkPlanAttemptResult.APPLIED,
        var extractionResult: ProjectWorkPlanAttemptResult = ProjectWorkPlanAttemptResult.APPLIED,
    ) : WorkPlanSettlementGateway {
        var settledSelections = 0
        var settledExtractions = 0
        var cancelled = 0

        override suspend fun settleMessage(receipt: ProjectWorkPlanResultReceipt) =
            ProjectWorkPlanAttemptResult.APPLIED

        override suspend fun settleSelection(
            receipt: ProjectWorkPlanResultReceipt,
            entries: List<ProjectWorkPlanResultDetails.SourceEntry>,
            now: Long,
        ): ProjectWorkPlanAttemptResult {
            settledSelections++
            return selectionResult
        }

        override suspend fun settleExtraction(
            receipt: ProjectWorkPlanResultReceipt,
            entry: ProjectWorkPlanResultDetails.SourceEntry,
            extractionType: String,
            now: Long,
        ): ProjectWorkPlanAttemptResult {
            settledExtractions++
            return extractionResult
        }

        override suspend fun cancel(attempt: ProjectWorkPlanAttempt): Boolean {
            cancelled++
            return true
        }
    }

    private fun attempt(type: ProjectWorkPlanStepType) = ProjectWorkPlanAttempt(
        planId = "plan-1",
        stepId = "step-2",
        attemptId = "attempt-3",
        projectId = 9L,
        type = type,
        title = "Check",
        instruction = "Review the Project sources",
        planTitle = "Plan",
        goal = "Goal",
        desiredOutput = "Output",
        constraintText = "Constraint",
    )

    private fun action(type: ProjectWorkPlanStepType): ProjectWorkPlanPreparedAction {
        val preparation = ProjectWorkPlanHandoffPolicy.prepare(attempt(type))
        assertTrue(preparation is ProjectWorkPlanHandoffPreparation.Ready)
        return (preparation as ProjectWorkPlanHandoffPreparation.Ready).action
    }

    private fun snapshot(vararg kinds: SelectionSourceKind): SelectionContextSnapshot {
        val sources = kinds.mapIndexed { index, kind ->
            val identity = when (kind) {
                SelectionSourceKind.PROJECT -> SelectionSourceIdentity(kind, projectId = (index + 1).toLong())
                SelectionSourceKind.SAVED_KNOWLEDGE ->
                    SelectionSourceIdentity(kind, savedKnowledgeId = "note-$index")
                else -> SelectionSourceIdentity(kind, projectId = (index + 1).toLong())
            }
            SelectionSourceSnapshot(identity, "Source $index", "excerpt $index")
        }
        val result = SelectionContextSnapshot.create(
            sources = sources,
            createdAt = 1L,
            purpose = SelectionContextPurpose.QUESTION,
        )
        assertTrue(result is SelectionSnapshotResult.Valid)
        return (result as SelectionSnapshotResult.Valid).snapshot
    }

    @Test
    fun `compare routes a frozen action without settling`() = runTest {
        val gateway = FakeGateway()
        val coordinator = ProjectWorkPlanHandoffCoordinator(gateway)
        val route = coordinator.routeSelection(
            action = action(ProjectWorkPlanStepType.COMPARE_SOURCES),
            snapshot = snapshot(SelectionSourceKind.PROJECT, SelectionSourceKind.PROJECT),
            isAccessible = { true },
            reader = ProjectWorkPlanExtractionContentPolicy.CanonicalReader { "body" },
        )
        assertTrue(route is ProjectWorkPlanHandoffCoordinator.SelectionRoute.DispatchPrepared)
        assertEquals(0, gateway.settledSelections)
    }

    @Test
    fun `extraction resolves canonical content not the excerpt`() = runTest {
        val gateway = FakeGateway()
        val coordinator = ProjectWorkPlanHandoffCoordinator(gateway)
        val route = coordinator.routeSelection(
            action = action(ProjectWorkPlanStepType.STRUCTURED_EXTRACT),
            snapshot = snapshot(SelectionSourceKind.SAVED_KNOWLEDGE),
            isAccessible = { true },
            reader = ProjectWorkPlanExtractionContentPolicy.CanonicalReader { "canonical full body" },
        )
        assertTrue(route is ProjectWorkPlanHandoffCoordinator.SelectionRoute.ExtractionReady)
        val source = (route as ProjectWorkPlanHandoffCoordinator.SelectionRoute.ExtractionReady).source
        assertEquals("canonical full body", source.text)
        assertTrue(source.durableIdentity != null)
    }

    @Test
    fun `inaccessible extraction source is rejected without settling`() = runTest {
        val gateway = FakeGateway()
        val coordinator = ProjectWorkPlanHandoffCoordinator(gateway)
        val route = coordinator.routeSelection(
            action = action(ProjectWorkPlanStepType.STRUCTURED_EXTRACT),
            snapshot = snapshot(SelectionSourceKind.SAVED_KNOWLEDGE),
            isAccessible = { false },
            reader = ProjectWorkPlanExtractionContentPolicy.CanonicalReader { "canonical full body" },
        )
        assertTrue(route is ProjectWorkPlanHandoffCoordinator.SelectionRoute.Rejected)
        assertEquals(0, gateway.settledExtractions)
    }

    @Test
    fun `review selection settles durable identities and reports stale`() = runTest {
        val gateway = FakeGateway(selectionResult = ProjectWorkPlanAttemptResult.STALE)
        val coordinator = ProjectWorkPlanHandoffCoordinator(gateway)
        val outcome = coordinator.settleReviewSelection(
            action = action(ProjectWorkPlanStepType.REVIEW_KNOWLEDGE),
            snapshot = snapshot(SelectionSourceKind.PROJECT, SelectionSourceKind.PROJECT),
            isAccessible = { true },
        )
        assertEquals(ProjectWorkPlanHandoffCoordinator.SettleOutcome.Stale, outcome)
        assertEquals(1, gateway.settledSelections)
    }

    @Test
    fun `deleted review source never settles`() = runTest {
        val gateway = FakeGateway()
        val coordinator = ProjectWorkPlanHandoffCoordinator(gateway)
        val outcome = coordinator.settleReviewSelection(
            action = action(ProjectWorkPlanStepType.REVIEW_KNOWLEDGE),
            snapshot = snapshot(SelectionSourceKind.PROJECT, SelectionSourceKind.PROJECT),
            isAccessible = { false },
        )
        assertTrue(outcome is ProjectWorkPlanHandoffCoordinator.SettleOutcome.Rejected)
        assertEquals(0, gateway.settledSelections)
    }

    @Test
    fun `extraction accept revalidates access before settlement`() = runTest {
        val gateway = FakeGateway()
        val coordinator = ProjectWorkPlanHandoffCoordinator(gateway)
        val identity = SelectionSourceIdentity(SelectionSourceKind.SAVED_KNOWLEDGE, savedKnowledgeId = "note-0")
        val result = StructuredExtractionResult(
            type = StructuredExtractionType.KEY_NUMBERS,
            title = "t",
            columns = listOf("c"),
            rows = emptyList(),
            sourceTitle = "Note",
            sourceKind = "저장 지식",
            sourceReference = identity.stableKey(),
            durableIdentity = identity,
            warnings = emptyList(),
        )
        val outcome = coordinator.settleExtraction(
            action = action(ProjectWorkPlanStepType.STRUCTURED_EXTRACT),
            result = result,
            isAccessible = { false },
        )
        assertTrue(outcome is ProjectWorkPlanHandoffCoordinator.SettleOutcome.Rejected)
        assertEquals(0, gateway.settledExtractions)
    }
}
