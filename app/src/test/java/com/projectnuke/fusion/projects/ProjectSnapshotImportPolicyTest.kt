package com.projectnuke.fusion.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSnapshotImportPolicyTest {
    @Test
    fun `duplicate long-extension names remain bounded and unique`() {
        val extension = "." + "x".repeat(ProjectSnapshotBounds.MAX_FILE_DISPLAY_NAME_CHARS - 2)
        val name = "a" + extension
        assertEquals(ProjectSnapshotBounds.MAX_FILE_DISPLAY_NAME_CHARS, name.length)

        val result = ProjectSnapshotImportPolicy.uniqueFileNames(listOf(name, name))

        assertEquals(2, result.size)
        assertEquals(name, result[0])
        assertTrue(result[1].length <= ProjectSnapshotBounds.MAX_FILE_DISPLAY_NAME_CHARS)
        assertTrue(result[0].lowercase() != result[1].lowercase())
        assertTrue(result[1].contains(" (2)"))
    }
}
