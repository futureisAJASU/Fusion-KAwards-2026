package com.projectnuke.fusion.projects

import com.projectnuke.fusion.chat.SelectionSourceIdentity
import com.projectnuke.fusion.chat.SelectionSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Findings D + G regression: SELECTION survives process reconstruction as
 * actual source identities (never an opaque UUID), and extraction result
 * identity never collides through lossy truncation.
 */
class ProjectWorkPlanResultDetailsTest {
    private fun project(id: Long) =
        SelectionSourceIdentity(SelectionSourceKind.PROJECT, projectId = id)

    private fun saved(id: String) =
        SelectionSourceIdentity(SelectionSourceKind.SAVED_KNOWLEDGE, savedKnowledgeId = id)

    @Test
    fun `selection entries round trip as identities plus titles`() {
        val entries = listOf(
            ProjectWorkPlanResultDetails.SourceEntry(project(3L), "Project three"),
            ProjectWorkPlanResultDetails.SourceEntry(saved("note-7"), "Note seven"),
        )
        val encoded = ProjectWorkPlanResultDetails.encodeSources(entries)
        assertNotNull(encoded)
        val decoded = ProjectWorkPlanResultDetails.decodeSources(encoded!!)
        assertNotNull(decoded)
        assertEquals(2, decoded!!.size)
        assertEquals(entries.map { it.identity.stableKey() }, decoded.map { it.identity.stableKey() })
        assertEquals(listOf("Project three", "Note seven"), decoded.map { it.title })
    }

    @Test
    fun `excerpts are never persisted only identities and titles`() {
        val entries = listOf(ProjectWorkPlanResultDetails.SourceEntry(saved("n1"), "T1"))
        val encoded = ProjectWorkPlanResultDetails.encodeSources(entries)!!
        assertTrue(!encoded.contains("excerpt"))
    }

    @Test
    fun `malformed or oversized payloads never decode`() {
        assertNull(ProjectWorkPlanResultDetails.decodeSources(""))
        assertNull(ProjectWorkPlanResultDetails.decodeSources("[not json]"))
        assertNull(ProjectWorkPlanResultDetails.decodeSources("[{\"kind\":\"NOPE\",\"title\":\"x\"}]"))
        assertNull(ProjectWorkPlanResultDetails.decodeSources("[]"))
        assertNull(ProjectWorkPlanResultDetails.encodeSources(emptyList()))
        val tooMany = (1..9).map {
            ProjectWorkPlanResultDetails.SourceEntry(project(it.toLong()), "P$it")
        }
        assertNull(ProjectWorkPlanResultDetails.encodeSources(tooMany))
        assertNull(ProjectWorkPlanResultDetails.selectionResultId(emptyList()))
    }

    @Test
    fun `duplicates are rejected`() {
        val entries = listOf(
            ProjectWorkPlanResultDetails.SourceEntry(project(3L), "A"),
            ProjectWorkPlanResultDetails.SourceEntry(project(3L), "B"),
        )
        assertNull(ProjectWorkPlanResultDetails.encodeSources(entries))
        assertNull(ProjectWorkPlanResultDetails.selectionResultId(listOf(project(3L), project(3L))))
    }

    @Test
    fun `long identities sharing a prefix stay distinct`() {
        val prefix = "documents/" + "x".repeat(300)
        val first = SelectionSourceIdentity(
            SelectionSourceKind.ATTACHED_DOCUMENT,
            conversationId = 1L,
            managedPath = prefix + "-a",
        )
        val second = SelectionSourceIdentity(
            SelectionSourceKind.ATTACHED_DOCUMENT,
            conversationId = 1L,
            managedPath = prefix + "-b",
        )
        val firstId = ProjectWorkPlanResultDetails.extractionResultId(first, "SPECIFICATIONS")
        val secondId = ProjectWorkPlanResultDetails.extractionResultId(second, "SPECIFICATIONS")
        assertNotNull(firstId)
        assertNotNull(secondId)
        assertNotEquals(firstId, secondId)
        assertTrue(firstId!!.length <= ProjectWorkPlanResultReceipt.MAX_RESULT_ID_CHARS)
        assertTrue(secondId!!.length <= ProjectWorkPlanResultReceipt.MAX_RESULT_ID_CHARS)
    }

    @Test
    fun `multiple extraction types stay distinct for the same source`() {
        val identity = saved("note-7")
        val ids = listOf("KEY_NUMBERS", "SPECIFICATIONS", "SCHEDULE", "COMPARISON", "TABLE").map {
            ProjectWorkPlanResultDetails.extractionResultId(identity, it)
        }
        assertTrue(ids.all { it != null })
        assertEquals(5, ids.distinct().size)
        assertTrue(ids.all { it!!.length <= ProjectWorkPlanResultReceipt.MAX_RESULT_ID_CHARS })
    }

    @Test
    fun `selection ids are stable and distinct per identity set`() {
        val first = ProjectWorkPlanResultDetails.selectionResultId(listOf(project(1L), project(2L)))
        val second = ProjectWorkPlanResultDetails.selectionResultId(listOf(project(1L), project(2L)))
        val other = ProjectWorkPlanResultDetails.selectionResultId(listOf(project(1L), project(3L)))
        assertNotNull(first)
        assertEquals(first, second)
        assertNotEquals(first, other)
        assertTrue(first!!.startsWith("s:"))
    }
}
