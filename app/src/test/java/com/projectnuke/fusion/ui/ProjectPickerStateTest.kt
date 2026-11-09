package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ProjectEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Project-picker load ownership: one presentation belongs to exactly
// (conversationId, generation). Stale loads — older conversations, dismissed
// pickers, same-conversation reopens — can never publish or authorize
// mutations for a newer owner.
class ProjectPickerStateTest {

    private val projectX = ProjectEntity(id = 100L, name = "Project X", createdAt = 1L, updatedAt = 1L)
    private val projectY = ProjectEntity(id = 200L, name = "Project Y", createdAt = 1L, updatedAt = 1L)

    @Test
    fun `older conversation completion cannot overwrite a newer picker`() {
        // A1 issued for conversation A (gen 1), then B1 for conversation B (gen 2).
        val a1 = ProjectPickerLoadResult(
            owner = ProjectPickerLoadOwner(10L, 1L),
            projects = listOf(projectX),
            assignment = 100L,
        )
        val b1 = ProjectPickerLoadResult(
            owner = ProjectPickerLoadOwner(20L, 2L),
            projects = listOf(projectX, projectY),
            assignment = 200L,
        )
        val currentOwner = ProjectPickerLoadOwner(20L, 2L)

        // B1 finishes first and publishes: accepted while it is current.
        assertTrue(canPublishProjectPickerLoad(currentOwner, 20L, b1))
        // A1 finishes late: rejected even though the conversation exists.
        assertFalse(canPublishProjectPickerLoad(currentOwner, 20L, a1))
        // B remains authoritative.
        assertTrue(canPublishProjectPickerLoad(currentOwner, 20L, b1))
    }

    @Test
    fun `dismiss invalidates an in-flight completion`() {
        val a1 = ProjectPickerLoadResult(
            owner = ProjectPickerLoadOwner(10L, 1L),
            projects = listOf(projectX),
            assignment = 100L,
        )
        // Dismissal clears the current owner; the late completion is rejected.
        assertFalse(canPublishProjectPickerLoad(null, 10L, a1))
    }

    @Test
    fun `same conversation reopen is fenced by generation`() {
        val a1 = ProjectPickerLoadResult(
            owner = ProjectPickerLoadOwner(10L, 1L),
            projects = listOf(projectX),
            assignment = 100L,
        )
        val a2 = ProjectPickerLoadResult(
            owner = ProjectPickerLoadOwner(10L, 2L),
            projects = listOf(projectX, projectY),
            assignment = null,
        )
        val currentOwner = ProjectPickerLoadOwner(10L, 2L)

        // The newer same-conversation load publishes...
        assertTrue(canPublishProjectPickerLoad(currentOwner, 10L, a2))
        // ...and the older one completes later: conversation equality must
        // not make it authoritative again.
        assertFalse(canPublishProjectPickerLoad(currentOwner, 10L, a1))
    }

    @Test
    fun `visible picker state cannot cross conversations`() {
        val ownerA = ProjectPickerLoadOwner(10L, 3L)
        assertTrue(isProjectPickerOwnerVisible(ownerA, 10L))
        assertFalse(isProjectPickerOwnerVisible(ownerA, 20L))
        assertFalse(isProjectPickerOwnerVisible(null, 10L))
    }

    @Test
    fun `action target and displayed assignment share one owner`() {
        val ownerA = ProjectPickerLoadOwner(10L, 3L)
        // In conversation A the action may mutate A...
        assertEquals(10L, projectPickerActionTarget(ownerA, 10L))
        // ...but an A-owned assignment can never authorize a mutation of B.
        assertNull(projectPickerActionTarget(ownerA, 20L))
        // And no owner at all fails closed.
        assertNull(projectPickerActionTarget(null, 10L))
    }

    @Test
    fun `concrete failing order - B publishes then late A is fenced`() {
        // 1. A belongs to X, B belongs to Y. Picker opened in A (gen 1).
        val a1 = ProjectPickerLoadResult(
            owner = ProjectPickerLoadOwner(10L, 1L),
            projects = listOf(projectX, projectY),
            assignment = 100L,
        )
        // 2. Picker dismissed and reopened for B (gen 2), still in flight.
        val b1 = ProjectPickerLoadResult(
            owner = ProjectPickerLoadOwner(20L, 2L),
            projects = listOf(projectX, projectY),
            assignment = 200L,
        )
        val bOwner = ProjectPickerLoadOwner(20L, 2L)
        assertTrue(isProjectPickerOwnerVisible(bOwner, 20L))
        assertTrue(canPublishProjectPickerLoad(bOwner, 20L, b1))
        // 3. A1's suspended DAO reads complete after B1 published.
        assertFalse(canPublishProjectPickerLoad(bOwner, 20L, a1))
        // 4. The B surface keeps showing B's own assignment (Y), and the
        //    remove/assign actions are bound to conversation B only.
        assertEquals(200L, b1.assignment)
        assertEquals(20L, projectPickerActionTarget(bOwner, 20L))
        assertNull(projectPickerActionTarget(ProjectPickerLoadOwner(10L, 1L), 20L))
    }
}
