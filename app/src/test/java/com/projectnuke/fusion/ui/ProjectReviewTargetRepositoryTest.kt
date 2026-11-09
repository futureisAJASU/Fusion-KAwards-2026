package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ProjectDecisionEntity
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectTaskEntity
import com.projectnuke.fusion.data.ProjectWorkPlanEntity
import com.projectnuke.fusion.data.ProjectWorkPlanStepEntity
import com.projectnuke.fusion.data.StudyReviewCardEntity
import com.projectnuke.fusion.projects.ProjectPulseItem
import com.projectnuke.fusion.projects.ProjectReviewSessionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectReviewTargetRepositoryTest {
    @Test
    fun `review target opens canonical durable row only after exact resolution`() = runBlocking {
        val target = ProjectReviewSessionPolicy.ProjectReviewTarget.Task(projectId = 4, taskId = 82)
        val canonical = ProjectPulseItem.Task(projectId = 4, taskId = 82, title = "Current durable title", dueAt = 900)
        val source = FakePulseSource().apply { resolved = canonical }
        val repository = ProjectPulseRepository(source)

        val result = repository.resolveCurrentReviewTarget(target, now = 1000)

        assertEquals(target, source.lastTarget)
        assertEquals(1000L, source.lastNow)
        assertEquals(canonical, result)
        assertEquals(0, source.durableWrites)
    }

    @Test
    fun `stale target and ordinary read failure fail closed`() = runBlocking {
        val target = ProjectReviewSessionPolicy.ProjectReviewTarget.ReviewCard(4, "card-1")
        val source = FakePulseSource()
        val repository = ProjectPulseRepository(source)

        assertNull(repository.resolveCurrentReviewTarget(target, 1000))
        source.resolveFailure = IllegalStateException("read failed")
        assertNull(repository.resolveCurrentReviewTarget(target, 1000))
        assertEquals(0, source.durableWrites)
    }

    @Test
    fun `target resolution propagates cancellation`() = runBlocking {
        val source = FakePulseSource().apply { resolveFailure = CancellationException("cancelled") }

        try {
            ProjectPulseRepository(source).resolveCurrentReviewTarget(
                ProjectReviewSessionPolicy.ProjectReviewTarget.Task(4, 82),
                1000,
            )
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    private class FakePulseSource : ProjectPulseDataSource {
        var resolved: ProjectPulseItem? = null
        var resolveFailure: Exception? = null
        var lastTarget: ProjectReviewSessionPolicy.ProjectReviewTarget? = null
        var lastNow: Long? = null
        var durableWrites: Int = 0

        override suspend fun project(id: Long) = ProjectEntity(id, "Project", createdAt = 1, updatedAt = 1)
        override suspend fun activePlan(projectId: Long): ProjectWorkPlanEntity? = null
        override suspend fun planSteps(planId: String): List<ProjectWorkPlanStepEntity> = emptyList()
        override suspend fun openTasks(projectId: Long, now: Long, limit: Int): List<ProjectTaskEntity> = emptyList()
        override suspend fun activeDecisions(projectId: Long, limit: Int): List<ProjectDecisionEntity> = emptyList()
        override suspend fun researchCandidates(now: Long, projectId: Long): ResearchRefreshQueueLoadResult =
            ResearchRefreshQueueLoadResult(emptyList(), ResearchRefreshQueueCoverage.COMPLETE)
        override suspend fun projectForConversation(conversationId: Long): Long? = null
        override suspend fun reviewCards(projectId: Long, limit: Int): List<StudyReviewCardEntity> = emptyList()
        override suspend fun current(item: ProjectPulseItem, now: Long): Boolean = false

        override suspend fun resolveCurrentReviewTarget(
            target: ProjectReviewSessionPolicy.ProjectReviewTarget,
            now: Long,
        ): ProjectPulseItem? {
            lastTarget = target
            lastNow = now
            resolveFailure?.let { throw it }
            return resolved
        }
    }
}
