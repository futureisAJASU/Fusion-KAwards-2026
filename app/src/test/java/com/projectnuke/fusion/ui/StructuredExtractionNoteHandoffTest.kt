package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.SelectionSourceIdentity
import com.projectnuke.fusion.chat.SelectionSourceKind
import com.projectnuke.fusion.knowledge.MAX_SAVED_NOTE_BODY_CHARS
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredExtractionNoteHandoffTest {
    private fun result(identity: SelectionSourceIdentity? = sourceIdentity, text: String = "x") =
        StructuredExtractionResult(
            type = StructuredExtractionType.TABLE,
            title = "Extracted",
            columns = emptyList(),
            rows = listOf(StructuredExtractionRow(listOf(text), "")),
            sourceTitle = "Source",
            sourceKind = "Document",
            sourceReference = "project:1/file:2",
            durableIdentity = identity,
            warnings = emptyList(),
        )

    @Test
    fun `missing durable identity is distinct from body size failure`() {
        assertEquals(
            StructuredExtractionNoteHandoff.MissingSourceIdentity,
            result(identity = null).toNoteHandoff(),
        )
        val oneCharacterLength = StructuredExtractionPolicy.asPlainText(result(text = "x")).length
        val exactLimit = result(text = "x".repeat(MAX_SAVED_NOTE_BODY_CHARS - oneCharacterLength + 1))
        val overLimit = result(text = "x".repeat(MAX_SAVED_NOTE_BODY_CHARS + 1 - oneCharacterLength + 1))
        val ready = exactLimit.toNoteHandoff()
        assertTrue(ready is StructuredExtractionNoteHandoff.Ready)
        assertEquals(MAX_SAVED_NOTE_BODY_CHARS, (ready as StructuredExtractionNoteHandoff.Ready).draft.body.length)
        assertEquals(StructuredExtractionNoteHandoff.BodyTooLong, overLimit.toNoteHandoff())
    }

    @Test
    fun `partial extraction warnings survive note handoff`() {
        val note = result().copy(warnings = listOf("Table columns beyond the 8-column limit were omitted."))
        val draft = (note.toNoteHandoff() as StructuredExtractionNoteHandoff.Ready).draft
        assertTrue(draft.body.contains("8-column limit were omitted"))
    }

    @Test
    fun `deleted project keeps the extraction and project rename uses current identity record`() = runTest {
        val deleted = prepareStructuredExtractionNoteHandoff(
            result(), 7L, "old name", resolveCurrentProjectName = { null },
        )
        assertEquals(StructuredExtractionNoteHandoff.ProjectNoLongerExists, deleted)

        val renamed = prepareStructuredExtractionNoteHandoff(
            result(), 7L, "old name", resolveCurrentProjectName = { id -> "Project $id current" },
        ) as StructuredExtractionNoteHandoff.Ready
        assertEquals(7L, renamed.draft.projectId)
        assertEquals("Project 7 current", renamed.draft.projectName)
    }

    private companion object {
        val sourceIdentity = SelectionSourceIdentity(
            kind = SelectionSourceKind.PROJECT_DOCUMENT,
            projectId = 1L,
            fileId = 2L,
        )
    }
}
