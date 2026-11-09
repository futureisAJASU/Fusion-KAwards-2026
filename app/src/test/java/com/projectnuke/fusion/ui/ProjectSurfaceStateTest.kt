package com.projectnuke.fusion.ui

import com.projectnuke.fusion.util.AttachmentRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectSurfaceStateTest {
    @Test
    fun `a previous conversation snapshot is never visible for a new owner`() {
        val ownerA = ProjectSurfaceOwner(conversationId = 10L, pendingProjectId = null)
        val ownerB = ProjectSurfaceOwner(conversationId = 20L, pendingProjectId = null)
        val snapshotA = ProjectSurfaceSnapshot(
            owner = ownerA,
            name = "Project A",
            fileRecords = listOf(AttachmentRecord("a.pdf", "application/pdf", "/a.pdf")),
        )

        assertNull(visibleProjectSurfaceSnapshot(ownerB, snapshotA))
        assertEquals(snapshotA, visibleProjectSurfaceSnapshot(ownerA, snapshotA))
    }

    @Test
    fun `pending project identity also participates in surface ownership`() {
        val ownerWithoutPendingProject = ProjectSurfaceOwner(conversationId = 0L, pendingProjectId = null)
        val ownerWithPendingProject = ProjectSurfaceOwner(conversationId = 0L, pendingProjectId = 7L)
        val snapshot = ProjectSurfaceSnapshot(ownerWithPendingProject, "Project B", emptyList())

        assertNull(visibleProjectSurfaceSnapshot(ownerWithoutPendingProject, snapshot))
        assertEquals(snapshot, visibleProjectSurfaceSnapshot(ownerWithPendingProject, snapshot))
    }
}
