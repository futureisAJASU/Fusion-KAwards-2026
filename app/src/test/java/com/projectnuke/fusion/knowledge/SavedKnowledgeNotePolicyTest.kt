package com.projectnuke.fusion.knowledge

import com.projectnuke.fusion.chat.SelectionSourceIdentity
import com.projectnuke.fusion.chat.SelectionSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedKnowledgeNotePolicyTest {
    @Test fun acceptsEditableMarkdownWithSeparateDurableSourceReferences() {
        val references = listOf(
            SavedKnowledgeNoteReferenceDraft(
                SelectionSourceIdentity(SelectionSourceKind.PROJECT_DOCUMENT, projectId = 7L, fileId = 12L),
                "paper.pdf · p.3",
            ),
            SavedKnowledgeNoteReferenceDraft(
                SelectionSourceIdentity(SelectionSourceKind.CONVERSATION, conversationId = 21L),
                "Research summary",
            ),
        )
        val draft = SavedKnowledgeNotePolicy.normalize("  HBM notes  ", "# Finding\nEditable", references)

        assertEquals("HBM notes", draft?.title)
        assertEquals("# Finding\nEditable", draft?.body)
        assertEquals(references, draft?.references)
    }

    @Test fun rejectsBoundsDuplicateAliasesAndEphemeralSources() {
        val project = SavedKnowledgeNoteReferenceDraft(
            SelectionSourceIdentity(SelectionSourceKind.PROJECT, projectId = 7L), "Project",
        )
        assertNull(SavedKnowledgeNotePolicy.normalize(" ", "body", emptyList()))
        assertNull(SavedKnowledgeNotePolicy.normalize("title", "x".repeat(MAX_SAVED_NOTE_BODY_CHARS + 1), emptyList()))
        assertNull(SavedKnowledgeNotePolicy.normalize("title", "body", listOf(project, project)))
        assertNull(SavedKnowledgeNotePolicy.normalize(
            "title", "body", listOf(SavedKnowledgeNoteReferenceDraft(
                SelectionSourceIdentity(SelectionSourceKind.MEMORY, memoryId = "temporary"), "Memory",
            )),
        ))
        assertTrue(SavedKnowledgeNotePolicy.isDurableNoteSource(SelectionSourceKind.PROJECT_DOCUMENT))
    }

    @Test fun extractedNoteDraftKeepsItsExplicitProjectDestination() {
        val reference = SavedKnowledgeNoteReferenceDraft(
            SelectionSourceIdentity(SelectionSourceKind.PROJECT_DOCUMENT, projectId = 7L, fileId = 12L),
            "Design notes",
        )
        val draft = SavedKnowledgeNotePolicy.normalize("Extracted", "Speed: 3.2 GHz", listOf(reference), projectId = 7L)

        assertEquals(7L, draft?.projectId)
        assertEquals(listOf(reference), draft?.references)
    }

    @Test fun lockedArchiveSourcesHideLinkedNotesUntilArchiveIsUnlocked() {
        assertTrue(SavedKnowledgeNoteArchivePolicy.isVisible(false, false, emptyList()))
        assertTrue(SavedKnowledgeNoteArchivePolicy.isVisible(false, false, listOf(false, false)))
        assertEquals(false, SavedKnowledgeNoteArchivePolicy.isVisible(false, false, listOf(false, true)))
        assertEquals(false, SavedKnowledgeNoteArchivePolicy.isVisible(false, true, emptyList()))
        assertTrue(SavedKnowledgeNoteArchivePolicy.isVisible(true, true, listOf(true)))
    }
}
