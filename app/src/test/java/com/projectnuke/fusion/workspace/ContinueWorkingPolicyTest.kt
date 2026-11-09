package com.projectnuke.fusion.workspace

import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectTaskShortcutRow
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWorkingPolicyTest {
    @Test
    fun `selection is deterministic bounded and sorted by durable update time`() {
        val selected = ContinueWorkingPolicy.select(
            projects = listOf(ProjectEntity(id = 1, name = "older project", createdAt = 1, updatedAt = 10)),
            openTasks = listOf(ProjectTaskShortcutRow(2, 1, "task", "Project", 30)),
            inboxItems = listOf(InboxItemEntity("i-1", "NOTE", "capture", "text", null, null, null, null, 1, 20, "READY")),
        )
        assertEquals(listOf("task:2", "inbox:i-1", "project:1"), selected.map { it.stableId })
    }

    @Test
    fun `invalid identities are excluded and only three shortcuts survive`() {
        val selected = ContinueWorkingPolicy.select(
            projects = (1L..5L).map { ProjectEntity(id = it, name = "project$it", createdAt = it, updatedAt = it) },
            openTasks = listOf(ProjectTaskShortcutRow(0, 0, "invalid", "", 100)),
            inboxItems = emptyList(),
        )
        assertEquals(ContinueWorkingPolicy.MAX_SHORTCUTS, selected.size)
        assertEquals(listOf("project:5", "project:4", "project:3"), selected.map { it.stableId })
    }

    @Test
    fun `inbox subtitle uses a user label and hides unknown storage values`() {
        val selected = ContinueWorkingPolicy.select(
            projects = emptyList(),
            openTasks = emptyList(),
            inboxItems = listOf(
                InboxItemEntity("known", "SHARED_TEXT", "Shared", "text", null, null, null, null, 1, 20, "READY"),
                InboxItemEntity("unknown", "FUTURE_INTERNAL_KIND", "Unknown", "text", null, null, null, null, 1, 10, "READY"),
            ),
        )
        assertEquals(listOf("Inbox · 공유한 텍스트", "Inbox · 자료"), selected.map { it.subtitle })
    }
}
