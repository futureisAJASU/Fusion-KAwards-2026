package com.projectnuke.fusion.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding B regression: MESSAGE settlement requires direct response
 * ownership. A later unrelated assistant response, an answer to a later
 * normal message, or a regenerated answer for another origin never settles
 * the earlier attempt.
 */
class ProjectWorkPlanMessageOwnershipTest {
    private fun ref(id: Long, role: String) =
        ProjectWorkPlanMessageOwnership.TimelineRef(id, role)

    @Test
    fun `the direct answer owns the committed user message`() {
        val timeline = listOf(ref(10, "user"), ref(11, "assistant"))
        assertTrue(
            ProjectWorkPlanMessageOwnership.isOwnedAssistant(timeline, emptyMap(), 11, 10),
        )
        assertEquals(
            11L,
            ProjectWorkPlanMessageOwnership.firstOwnedAssistantId(timeline, emptyMap(), 10),
        )
    }

    @Test
    fun `a later unrelated assistant response cannot settle the earlier attempt`() {
        // A (10) cancelled/failed, then normal B (12) answered by 13.
        val timeline = listOf(ref(10, "user"), ref(12, "user"), ref(13, "assistant"))
        assertFalse(
            ProjectWorkPlanMessageOwnership.isOwnedAssistant(timeline, emptyMap(), 13, 10),
        )
        assertNull(
            ProjectWorkPlanMessageOwnership.firstOwnedAssistantId(timeline, emptyMap(), 10),
        )
        assertEquals(
            13L,
            ProjectWorkPlanMessageOwnership.firstOwnedAssistantId(timeline, emptyMap(), 12),
        )
    }

    @Test
    fun `cancelled work plan generation followed by a normal chat response never settles`() {
        // Work Plan user A (20), generation failed with no answer; normal
        // user B (21) answered by 22; unrelated insertion 23.
        val timeline = listOf(
            ref(20, "user"),
            ref(21, "user"),
            ref(22, "assistant"),
            ref(23, "assistant"),
        )
        assertNull(
            ProjectWorkPlanMessageOwnership.firstOwnedAssistantId(timeline, emptyMap(), 20),
        )
        assertFalse(ProjectWorkPlanMessageOwnership.isOwnedAssistant(timeline, emptyMap(), 22, 20))
        assertFalse(ProjectWorkPlanMessageOwnership.isOwnedAssistant(timeline, emptyMap(), 23, 20))
    }

    @Test
    fun `a version group owner is authoritative over table order`() {
        // Regenerated answer 30 for user 10 recorded with group owner 10,
        // even though a later user 29 sits between them in raw order.
        val timeline = listOf(ref(10, "user"), ref(29, "user"), ref(30, "assistant"))
        assertTrue(
            ProjectWorkPlanMessageOwnership.isOwnedAssistant(timeline, mapOf(30L to 10L), 30, 10),
        )
        // The same row without the group belongs to the later user.
        assertFalse(
            ProjectWorkPlanMessageOwnership.isOwnedAssistant(timeline, emptyMap(), 30, 10),
        )
    }

    @Test
    fun `a regenerated answer for another origin never settles the attempt`() {
        val timeline = listOf(ref(10, "user"), ref(11, "assistant"), ref(12, "assistant"))
        // 12 is a regenerated answer grouped to user 10: owned.
        assertTrue(
            ProjectWorkPlanMessageOwnership.isOwnedAssistant(timeline, mapOf(12L to 10L), 12, 10),
        )
        // 12 grouped to a different origin is not owned by 10.
        assertFalse(
            ProjectWorkPlanMessageOwnership.isOwnedAssistant(timeline, mapOf(12L to 99L), 12, 10),
        )
    }

    @Test
    fun `already tried assistants are skipped without losing later owned answers`() {
        val timeline = listOf(ref(10, "user"), ref(11, "assistant"), ref(12, "user"), ref(13, "assistant"))
        assertNull(
            ProjectWorkPlanMessageOwnership.firstOwnedAssistantId(
                timeline,
                emptyMap(),
                10,
                excludeAssistantIds = setOf(11),
            ),
        )
        // The normal follow-up still settles its own message.
        assertEquals(
            13L,
            ProjectWorkPlanMessageOwnership.firstOwnedAssistantId(timeline, emptyMap(), 12),
        )
    }

    @Test
    fun `invalid identities never own`() {
        val timeline = listOf(ref(10, "user"), ref(11, "assistant"))
        assertFalse(ProjectWorkPlanMessageOwnership.isOwnedAssistant(timeline, emptyMap(), 11, 0))
        assertFalse(ProjectWorkPlanMessageOwnership.isOwnedAssistant(timeline, emptyMap(), 10, 10))
        assertFalse(ProjectWorkPlanMessageOwnership.isOwnedAssistant(timeline, emptyMap(), 999, 10))
        assertNull(ProjectWorkPlanMessageOwnership.firstOwnedAssistantId(timeline, emptyMap(), 0))
    }
}
