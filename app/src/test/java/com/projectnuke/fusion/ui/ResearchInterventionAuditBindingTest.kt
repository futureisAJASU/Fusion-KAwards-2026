package com.projectnuke.fusion.ui

import com.projectnuke.fusion.audit.ResearchDraftClaimFindingStatus
import com.projectnuke.fusion.audit.ResearchInterventionAction
import com.projectnuke.fusion.audit.ResearchInterventionAuditClaimRecord
import com.projectnuke.fusion.audit.ResearchInterventionAuditRecord
import com.projectnuke.fusion.audit.ResearchInterventionDraftStage
import com.projectnuke.fusion.audit.ResearchInterventionFindingSubject
import com.projectnuke.fusion.audit.ResearchInterventionReason
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ResearchInterventionAuditBindingTest {
    @Test
    fun `conflicting evidence keeps its distinct user visible status`() {
        assertEquals("자료 간 상충", researchInterventionClaimStatusLabel(ResearchDraftClaimFindingStatus.CONFLICTING))
    }

    @Test
    fun `abstention findings are explicitly labeled as abandoned draft findings`() {
        val record = record(
            answer = "The available evidence is not enough to answer this confidently.",
            subject = ResearchInterventionFindingSubject.PRE_PERSISTENCE_DRAFT,
            stage = ResearchInterventionDraftStage.ORIGINAL,
        )

        assertEquals("보류된 저장 전 초안 · 원본 초안", researchInterventionAuditSubjectLabel(record))
        assertEquals(
            "아래 주장은 저장 전에 원본 초안에서 확인했지만 답변을 보류했습니다. 사용자에게 표시된 보류 안내문 자체를 검증한 결과가 아닙니다.",
            researchInterventionAuditSubjectExplanation(record),
        )
    }

    @Test
    fun `audit binding rejects stale message conversation and answer versions`() {
        val visibleAnswer = "The available evidence is not enough to answer this confidently."
        val record = record(
            answer = visibleAnswer,
            subject = ResearchInterventionFindingSubject.PRE_PERSISTENCE_DRAFT,
            stage = ResearchInterventionDraftStage.ORIGINAL,
        )
        val message = MessageEntity(
            id = record.messageId,
            conversationId = record.conversationId,
            role = "assistant",
            content = visibleAnswer,
            createdAt = 1L,
        )

        assertSame(record, bindResearchInterventionAudit(record, message, record.conversationId, visibleAnswer))
        assertNull(bindResearchInterventionAudit(record, message.copy(id = 99L), record.conversationId, visibleAnswer))
        assertNull(bindResearchInterventionAudit(record, message, 99L, visibleAnswer))
        assertNull(bindResearchInterventionAudit(record, message, record.conversationId, "different response version"))
    }

    private fun record(
        answer: String,
        subject: ResearchInterventionFindingSubject,
        stage: ResearchInterventionDraftStage,
    ) = ResearchInterventionAuditRecord(
        messageId = 42L,
        conversationId = 71L,
        answerSha256 = EvidenceFingerprint.canonicalAnswerHash(answer),
        savedAt = 1L,
        action = if (subject == ResearchInterventionFindingSubject.PRE_PERSISTENCE_DRAFT) {
            ResearchInterventionAction.ABSTAIN
        } else {
            ResearchInterventionAction.DELIVER_WITH_SCOPED_AUDIT
        },
        reason = ResearchInterventionReason.INSUFFICIENT_EVIDENCE,
        wholeAnswerCertified = false,
        claimScopedOnly = true,
        revisionAttempted = false,
        retrievalAttempted = false,
        claims = listOf(
            ResearchInterventionAuditClaimRecord(
                claimId = "AC1",
                claimText = "A claim from the draft that was withheld.",
                claimTextSha256 = "a".repeat(64),
                status = ResearchDraftClaimFindingStatus.INSUFFICIENT,
                evidence = emptyList(),
            ),
        ),
        findingSubject = subject,
        draftStage = stage,
    )
}
