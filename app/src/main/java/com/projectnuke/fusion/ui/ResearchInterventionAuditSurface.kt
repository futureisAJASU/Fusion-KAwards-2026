package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.audit.ResearchDraftClaimFindingStatus
import com.projectnuke.fusion.audit.ResearchInterventionAuditRecord
import com.projectnuke.fusion.audit.ResearchInterventionDraftStage
import com.projectnuke.fusion.audit.ResearchInterventionFindingSubject
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

internal fun researchInterventionClaimStatusLabel(
    status: ResearchDraftClaimFindingStatus,
): String = when (status) {
    ResearchDraftClaimFindingStatus.SUPPORTED -> "근거 있음"
    ResearchDraftClaimFindingStatus.CONTRADICTED -> "자료와 충돌"
    ResearchDraftClaimFindingStatus.CONFLICTING -> "자료 간 상충"
    ResearchDraftClaimFindingStatus.INSUFFICIENT -> "근거 부족"
    ResearchDraftClaimFindingStatus.UNVERIFIABLE -> "확인 불가"
}

internal fun bindResearchInterventionAudit(
    record: ResearchInterventionAuditRecord?,
    message: MessageEntity?,
    conversationId: Long,
    visibleAnswer: String,
): ResearchInterventionAuditRecord? = record?.takeIf {
    message != null &&
        it.messageId == message.id &&
        it.conversationId == conversationId &&
        it.answerSha256 == EvidenceFingerprint.canonicalAnswerHash(visibleAnswer)
}

internal fun researchInterventionAuditDraftStageLabel(stage: ResearchInterventionDraftStage): String = when (stage) {
    ResearchInterventionDraftStage.ORIGINAL -> "원본 초안"
    ResearchInterventionDraftStage.REVISED -> "수정 초안"
    ResearchInterventionDraftStage.LEGACY_UNKNOWN -> "당시 검토 초안"
}

internal fun researchInterventionAuditSubjectLabel(record: ResearchInterventionAuditRecord): String = when (record.findingSubject) {
    ResearchInterventionFindingSubject.FINAL_USER_VISIBLE_ANSWER ->
        "최종 답변 · ${researchInterventionAuditDraftStageLabel(record.draftStage)}"
    ResearchInterventionFindingSubject.PRE_PERSISTENCE_DRAFT ->
        "보류된 저장 전 초안 · ${researchInterventionAuditDraftStageLabel(record.draftStage)}"
}

internal fun researchInterventionAuditSubjectExplanation(record: ResearchInterventionAuditRecord): String = when (record.findingSubject) {
    ResearchInterventionFindingSubject.FINAL_USER_VISIBLE_ANSWER ->
        "아래 결과는 사용자에게 표시된 최종 답변의 개별 주장만 다룹니다. 답변 전체의 진실성을 보증하지 않습니다."
    ResearchInterventionFindingSubject.PRE_PERSISTENCE_DRAFT ->
        "아래 주장은 저장 전에 ${researchInterventionAuditDraftStageLabel(record.draftStage)}에서 확인했지만 답변을 보류했습니다. 사용자에게 표시된 보류 안내문 자체를 검증한 결과가 아닙니다."
}

@Composable
internal fun ResearchInterventionAuditSurface(
    record: ResearchInterventionAuditRecord,
    onOpenUrl: (String) -> Unit,
) {
    var showDialog by remember(record.messageId, record.answerSha256) { mutableStateOf(false) }
    val semanticColors = LocalFusionSemanticColors.current
    val scopeLabel = if (record.wholeAnswerCertified) {
        "답변 전체 근거 확인"
    } else {
        "일부 주장만 근거 확인"
    }
    val summary = buildString {
        append(researchInterventionAuditSubjectLabel(record))
        append(" · ")
        append(scopeLabel)
        val statuses = record.claims.map { researchInterventionClaimStatusLabel(it.status) }.distinct()
        if (statuses.isNotEmpty()) {
            append(" · ")
            append(statuses.joinToString(", "))
        }
    }

    TextButton(
        onClick = { showDialog = true },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(30.dp),
    ) {
        Text(
            text = summary,
            color = semanticColors.primary,
            fontSize = 12.sp,
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(
                    if (record.findingSubject == ResearchInterventionFindingSubject.PRE_PERSISTENCE_DRAFT) {
                        "저장 전 초안 확인 결과"
                    } else {
                        "최종 답변 확인 결과"
                    },
                )
            },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        text = researchInterventionAuditSubjectExplanation(record),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = semanticColors.onSurfaceVariant,
                    )
                    if (record.claims.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "표시할 수 있는 개별 주장 결과가 없습니다.",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = semanticColors.onSurfaceVariant,
                        )
                    }
                    record.claims.forEach { claim ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = researchInterventionClaimStatusLabel(claim.status),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = semanticColors.onSurface,
                        )
                        if (claim.claimText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = claim.claimText,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                color = semanticColors.onSurface,
                            )
                        }
                        claim.evidence.forEach { evidence ->
                            Spacer(modifier = Modifier.height(4.dp))
                            val title = evidence.title.ifBlank { evidence.providerDisplayName }
                            if (evidence.url.isNotBlank()) {
                                TextButton(
                                    onClick = { onOpenUrl(evidence.url) },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 0.dp,
                                        vertical = 0.dp,
                                    ),
                                ) {
                                    Text(
                                        text = title,
                                        fontSize = 12.sp,
                                        color = semanticColors.primary,
                                    )
                                }
                            } else {
                                Text(
                                    text = title,
                                    fontSize = 12.sp,
                                    color = semanticColors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "근거 부족은 틀렸다는 뜻이 아닙니다. 확인되지 않았다는 뜻입니다.",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = semanticColors.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("닫기") }
            },
        )
    }
}
