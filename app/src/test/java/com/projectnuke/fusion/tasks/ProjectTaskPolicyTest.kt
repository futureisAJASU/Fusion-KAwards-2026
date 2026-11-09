package com.projectnuke.fusion.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTaskPolicyTest {
    @Test fun answerTransformIsBoundedAndCarriesExactSourceOwnership() {
        val draft = ProjectTaskPolicy.fromAnswer(7L, "Project", "Task title\n" + "note ".repeat(1200), 20L, 21L)
        val prepared = ProjectTaskPolicy.prepare(draft, draft.title, draft.note, 30L) as ProjectTaskPreparation.Ready
        assertEquals(7L, prepared.proposal.projectId)
        assertEquals(20L, prepared.proposal.sourceConversationId)
        assertEquals(21L, prepared.proposal.sourceMessageId)
        assertTrue(prepared.proposal.title.length <= ProjectTaskPolicy.MAX_TITLE_CHARS)
        assertTrue(prepared.proposal.note.length <= ProjectTaskPolicy.MAX_NOTE_CHARS)
    }

    @Test fun manualTasksHaveNoSourceAndSensitiveMaterialIsRejected() {
        val manual = ProjectTaskPolicy.prepare(ProjectTaskDraft(9, "P", "Task", ""), "Task", "", 1)
        assertEquals(null, (manual as ProjectTaskPreparation.Ready).proposal.sourceMessageId)
        assertEquals(
            ProjectTaskPreparation.SensitiveContent,
            ProjectTaskPolicy.prepare(ProjectTaskDraft(9, "P", "Task", "key=abcdefghijklmnopqrstuvwxyz"), "Task", "key=abcdefghijklmnopqrstuvwxyz", 1),
        )
    }
}
