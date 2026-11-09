package com.projectnuke.fusion.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceAttentionPolicyTest {

    @Test
    fun reviewOutranksFailed() {
        val review = WorkspaceAttentionPolicy.selectWorkPlanAttention(
            listOf(
                WorkspaceAttentionPolicy.PlanStepRow("p1", "s1", "REVIEW", "Title", updatedAt = 1000L),
                WorkspaceAttentionPolicy.PlanStepRow("p1", "s2", "FAILED", "Failed", updatedAt = 500L),
            )
        )
        assertEquals(2, review.size)
        assertEquals(WorkspaceAttentionKind.WORK_PLAN_REVIEW, review[0].kind)
        assertEquals(WorkspaceAttentionKind.WORK_PLAN_FAILED, review[1].kind)
    }

    @Test
    fun runningNotIncludedAsRetryable() {
        val running = WorkspaceAttentionPolicy.selectWorkPlanAttention(
            listOf(
                WorkspaceAttentionPolicy.PlanStepRow("p1", "s1", "RUNNING", "Title", updatedAt = 1000L),
            )
        )
        assertTrue(running.isEmpty())
    }

    @Test
    fun dueTaskOutranksOrdinaryTask() {
        val tasks = listOf(
            com.projectnuke.fusion.data.ProjectTaskEntity(
                id = 1, projectId = 1L, title = "Task 1", note = "",
                state = "OPEN", reminderAt = System.currentTimeMillis() - 1000,
                reminderEnabled = true, createdAt = System.currentTimeMillis(), updatedAt = 100,
            ),
            com.projectnuke.fusion.data.ProjectTaskEntity(
                id = 2, projectId = 1L, title = "Task 2", note = "",
                state = "OPEN", reminderAt = null,
                reminderEnabled = false, createdAt = System.currentTimeMillis(), updatedAt = 200,
            ),
        )
        val result = WorkspaceAttentionPolicy.selectTaskAttention(tasks, System.currentTimeMillis())
        assertEquals(2, result.size)
        assertEquals("task:1", result[0].item.stableId)
    }

    @Test
    fun totalBoundedToMaxAttention() {
        val manyTasks = (1..20).map {
            com.projectnuke.fusion.data.ProjectTaskEntity(
                id = it.toLong(), projectId = 1L, title = "Task $it", note = "",
                state = "OPEN", reminderAt = null, reminderEnabled = false,
                createdAt = System.currentTimeMillis(), updatedAt = it.toLong(),
            )
        }
        val result = WorkspaceAttentionPolicy.selectTaskAttention(manyTasks, System.currentTimeMillis())
        assertTrue(result.size <= WorkspaceAttentionPolicy.MAX_PER_KIND)
    }

}
