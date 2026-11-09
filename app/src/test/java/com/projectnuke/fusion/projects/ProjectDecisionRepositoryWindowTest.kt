package com.projectnuke.fusion.projects

import com.projectnuke.fusion.data.ProjectDecisionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectDecisionRepositoryWindowTest {
    @Test
    fun `old active decision survives a full recent history window`() {
        val oldActive = decision("old-active", "ACTIVE", createdAt = 1)
        val recentHistory = (1..32).map { decision("history-$it", "SUPERSEDED", createdAt = 100L + it) }

        val state = projectDecisionListState(recentHistory, listOf(oldActive))

        assertEquals(listOf("old-active"), state.activeItems.map { it.id })
        assertTrue(state.items.any { it.id == oldActive.id })
        assertEquals(32, state.items.count { it.status == "SUPERSEDED" })
    }

    @Test
    fun `all active decisions remain visible beyond the recent window`() {
        val active = (1..64).map { decision("active-$it", "ACTIVE", createdAt = it.toLong()) }
        val recent = active.sortedByDescending { it.createdAt }.take(32)

        val state = projectDecisionListState(recent, active)

        assertEquals(64, state.activeItems.size)
        assertEquals(64, state.items.size)
        assertEquals(active.map { it.id }.toSet(), state.activeItems.map { it.id }.toSet())
    }

    @Test
    fun `old active decision remains an exact replacement target`() = runBlocking {
        val oldActive = decision("old-active", "ACTIVE", createdAt = 1)
        val recentHistory = (1..32).map { decision("history-$it", "SUPERSEDED", createdAt = 100L + it) }
        val state = projectDecisionListState(recentHistory, listOf(oldActive))
        var supersedesId: String? = null
        val controller = ProjectDecisionCaptureController(object : ProjectDecisionCaptureGateway {
            override suspend fun settle(
                source: FrozenProjectDecisionSource,
                draft: ProjectDecisionDraft,
            ): ProjectDecisionCaptureResult {
                supersedesId = draft.supersedesDecisionId
                return ProjectDecisionCaptureResult.Created("replacement")
            }
        })
        controller.begin(
            FrozenProjectDecisionSource(1, 2, 3, 4, 3, listOf(3), "answer-hash"),
            "Replacement",
            "Reason",
        )
        controller.selectSupersede(state.activeItems.single().id)

        assertEquals(ProjectDecisionCaptureResult.Created("replacement"), controller.save())
        assertEquals("old-active", supersedesId)
    }

    @Test
    fun `decision shortcut resolution remains exact across old rows and projects`() = runBlocking {
        val oldDecision = decision("old-active", "ACTIVE", projectId = 1, createdAt = 1)
        val sameIdOtherProject = decision("old-active", "ACTIVE", projectId = 2, createdAt = 1)
        val resolver = ProjectDecisionLineageResolver { id, projectId ->
            listOf(oldDecision, sameIdOtherProject).firstOrNull { it.id == id && it.projectId == projectId }
        }

        assertEquals(oldDecision, resolver.resolve("old-active", 1))
        assertNull(resolver.resolve("old-active", 3))
    }

    private fun decision(
        id: String,
        status: String,
        projectId: Long = 1,
        createdAt: Long,
    ) = ProjectDecisionEntity(
        id = id,
        projectId = projectId,
        title = id,
        rationale = "",
        status = status,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
