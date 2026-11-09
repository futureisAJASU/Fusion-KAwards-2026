package com.projectnuke.fusion.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedKnowledgePolicyTest {
    private fun draft(text: String = "visible answer", projectId: Long? = 7L) = SavedKnowledgeDraft(
        title = "제안 제목",
        visibleText = text,
        sourceConversationId = 10L,
        sourceMessageId = 12L,
        projectId = projectId,
        projectName = projectId?.let { "Project" },
    )

    @Test fun `personal and project snapshots have explicit scopes and bounded identity`() {
        val personal = SavedKnowledgePolicy.prepare(
            draft(), title = "개인 자료", scope = SavedKnowledgeScope.PERSONAL, now = 20L, id = "fixed-id",
        ) as SavedKnowledgePreparation.Ready
        assertEquals(null, personal.proposal.projectId)
        assertEquals("fixed-id", SavedKnowledgePolicy.toEntity(personal.proposal).id)
        assertEquals("PERSONAL", SavedKnowledgePolicy.toEntity(personal.proposal).scope)

        val project = SavedKnowledgePolicy.prepare(
            draft(), title = "프로젝트 자료", scope = SavedKnowledgeScope.PROJECT, now = 21L,
        ) as SavedKnowledgePreparation.Ready
        assertEquals(7L, project.proposal.projectId)
        assertTrue(project.proposal.id.isNotBlank())
    }

    @Test fun `hidden metadata is stripped and visible text and title are bounded`() {
        val hidden = draft("answer <fusion_metrics>internal metric</fusion_metrics> " + "x".repeat(13_000))
        val prepared = SavedKnowledgePolicy.prepare(
            hidden, "T".repeat(200), SavedKnowledgeScope.PERSONAL, 1L,
        ) as SavedKnowledgePreparation.Ready
        assertEquals(SavedKnowledgePolicy.MAX_TITLE_CHARS, prepared.proposal.title.length)
        assertEquals(SavedKnowledgePolicy.MAX_TEXT_CHARS, prepared.proposal.visibleText.length)
        assertFalse(prepared.proposal.visibleText.contains("internal metric"))
    }

    @Test fun `project scope without a project and malformed source fail closed`() {
        assertEquals(
            SavedKnowledgePreparation.Invalid,
            SavedKnowledgePolicy.prepare(draft(projectId = null), "x", SavedKnowledgeScope.PROJECT, 1L),
        )
        assertEquals(
            SavedKnowledgePreparation.Invalid,
            SavedKnowledgePolicy.prepare(draft().copy(sourceMessageId = 0L), "x", SavedKnowledgeScope.PERSONAL, 1L),
        )
    }

    @Test fun `likely secrets and internal absolute paths are rejected`() {
        assertTrue(SavedKnowledgePolicy.containsSensitiveMaterial("key=abcdefghijklmnopqrstuvwxyz123456"))
        assertTrue(SavedKnowledgePolicy.containsSensitiveMaterial("C:\\Users\\junyo\\private.txt"))
        assertFalse(SavedKnowledgePolicy.containsSensitiveMaterial("일반적인 답변 텍스트"))
    }
}
