package com.projectnuke.fusion.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun InboxSmartActionKind.label(): String = when (this) {
    InboxSmartActionKind.KEEP_IN_INBOX -> "Inbox에 유지"
    InboxSmartActionKind.SAVED_KNOWLEDGE -> "저장 지식으로 이동"
    InboxSmartActionKind.PROJECT -> "Project에 추가"
    InboxSmartActionKind.PROJECT_TASK -> "할 일로 만들기"
    InboxSmartActionKind.REVIEW_CARD -> "복습 카드 만들기"
}

/**
 * Review-only proposal surface. Nothing here mutates data: every button
 * delegates to the EXISTING Inbox action paths owned by FusionInboxScreen,
 * which still require explicit confirmation and enforce destination policy.
 */
@Composable
internal fun InboxSmartTriageDialog(
    proposal: InboxSmartTriageProposal,
    onPrimary: (InboxSmartActionKind) -> Unit,
    onMoveToProject: (projectId: Long) -> Unit,
    onMakeTask: (projectId: Long) -> Unit,
    onAddTag: (tag: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("정리 제안") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!proposal.semanticAvailable) {
                    Text(
                        "Project 의미 분석을 사용하지 못해 기본 제안만 보여줘요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("추천", style = MaterialTheme.typography.titleSmall)
                Text(
                    proposal.primaryAction.label(),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    proposal.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (proposal.primaryAction != InboxSmartActionKind.KEEP_IN_INBOX) {
                    TextButton(
                        onClick = { onPrimary(proposal.primaryAction) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(proposal.primaryAction.label()) }
                }

                if (proposal.projectCandidates.isNotEmpty()) {
                    Text("추천 Project", style = MaterialTheme.typography.titleSmall)
                    proposal.projectCandidates.forEach { candidate ->
                        TextButton(
                            onClick = { onMoveToProject(candidate.projectId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(candidate.projectName) }
                        Text(
                            "이 Project가 가장 가까워 보여요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }

                if (proposal.tagSuggestions.isNotEmpty()) {
                    Text("태그 제안", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        proposal.tagSuggestions.forEach { tag ->
                            AssistChip(
                                onClick = { onAddTag(tag.value) },
                                label = { Text("#${tag.value}") },
                            )
                        }
                    }
                    Text(
                        "선택한 태그 추가를 눌러야 적용돼요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text("다른 선택", style = MaterialTheme.typography.titleSmall)
                Text(
                    "정리 후보를 직접 고를 수 있어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryActions(
                    proposal = proposal,
                    onPrimary = onPrimary,
                    onMakeTask = onMakeTask,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun SecondaryActions(
    proposal: InboxSmartTriageProposal,
    onPrimary: (InboxSmartActionKind) -> Unit,
    onMakeTask: (projectId: Long) -> Unit,
) {
    val primary = proposal.primaryAction
    val topProject = proposal.projectCandidates.firstOrNull()
    if (primary != InboxSmartActionKind.SAVED_KNOWLEDGE) {
        TextButton(
            onClick = { onPrimary(InboxSmartActionKind.SAVED_KNOWLEDGE) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(InboxSmartActionKind.SAVED_KNOWLEDGE.label()) }
    }
    if (primary != InboxSmartActionKind.PROJECT_TASK) {
        if (topProject != null) {
            TextButton(
                onClick = { onMakeTask(topProject.projectId) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("${InboxSmartActionKind.PROJECT_TASK.label()} · ${topProject.projectName}") }
        } else {
            TextButton(
                onClick = { onPrimary(InboxSmartActionKind.PROJECT_TASK) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(InboxSmartActionKind.PROJECT_TASK.label()) }
        }
    }
    if (primary != InboxSmartActionKind.REVIEW_CARD) {
        TextButton(
            onClick = { onPrimary(InboxSmartActionKind.REVIEW_CARD) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(InboxSmartActionKind.REVIEW_CARD.label()) }
    }
}
