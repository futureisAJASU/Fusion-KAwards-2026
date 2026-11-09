package com.projectnuke.fusion.chat

import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectFileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProjectContextSnapshotTest {
    @Test
    fun `snapshot contains only explicit project resources and stays frozen`() {
        val project = ProjectEntity(
            id = 4L,
            name = "project-name-sentinel",
            instructions = "instruction-sentinel",
            createdAt = 1L,
            updatedAt = 2L,
        )
        val files = mutableListOf(
            ProjectFileEntity(
                id = 9L,
                projectId = 4L,
                displayName = "file-sentinel.txt",
                mimeType = "text/plain",
                localPath = "managed/file-sentinel.txt",
                addedAt = 3L,
            ),
        )

        val snapshot = ProjectContextSnapshot.from(project, files)
        files += ProjectFileEntity(
            id = 10L,
            projectId = 4L,
            displayName = "later.txt",
            mimeType = "text/plain",
            localPath = "managed/later.txt",
            addedAt = 4L,
        )

        assertEquals(4L, snapshot.projectId)
        assertEquals("instruction-sentinel", snapshot.instructions)
        assertEquals(listOf("managed/file-sentinel.txt"), snapshot.files.map { it.localPath })
        assertFalse(snapshot.files.any { it.displayName == "later.txt" })
        // No sibling history, summary, or memory field exists in the snapshot authority.
        assertEquals(1, snapshot.files.size)

        // A later Project edit produces a different snapshot while the frozen
        // one captured for a Work Plan step keeps the exact original authority.
        val later = ProjectContextSnapshot.from(project.copy(instructions = "later-instruction"), files)
        assertEquals("later-instruction", later.instructions)
        assertEquals("instruction-sentinel", snapshot.instructions)
        assertEquals(2, later.files.size)
        assertEquals(1, snapshot.files.size)
        assertFalse(snapshot.files.any { it.localPath == "managed/later.txt" })
    }
}
