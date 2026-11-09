package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.chat.AnswerSecondaryAction
import com.projectnuke.fusion.chat.AnswerSecondaryActionContext
import com.projectnuke.fusion.chat.AnswerSecondaryActionPolicy
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

/**
 * Next Action Flow v1B — the compact "더 보기" secondary action sheet.
 *
 * A DropdownMenu (the existing Fusion compact-menu pattern, not a card
 * dashboard) rendered only when [expanded]. It lists the secondary actions
 * resolved from the pure [AnswerSecondaryActionPolicy] over CURRENT live state;
 * rows redraw every composition so a stale response-version group or deleted
 * conversation resolves away. Compare row is dropped if the live compare
 * snapshot is unavailable; every row is a single click owner with ≥48dp target
 * and semantic colors. The caller may place these rows in the shared overflow.
 */
@Composable
internal fun AnswerSecondaryActionSheet(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: List<AnswerSecondaryAction>,
    context: AnswerSecondaryActionContext,
    compareLiveAvailable: Boolean,
    onProject: () -> Unit,
    onCompare: () -> Unit,
    onTranslation: () -> Unit,
    onRelatedMaterials: () -> Unit,
    onStructuredExtraction: () -> Unit = {},
    onViewContext: () -> Unit,
    onSaveKnowledge: () -> Unit,
    onCreateProjectTask: () -> Unit,
    onCreateReviewCard: () -> Unit,
    onSaveAsDecision: () -> Unit,
    onSaveCheckpoint: () -> Unit = {},
    inline: Boolean = false,
) {
    val semantic = LocalFusionSemanticColors.current
    val displayActions =
        if (compareLiveAvailable) actions else actions.filterNot { it == AnswerSecondaryAction.COMPARE_RESPONSES }
    if (!expanded || displayActions.isEmpty()) return
    val items: @Composable () -> Unit = {
        displayActions.forEach { action ->
            val title: String
            val subtitle: String
            val onClick: () -> Unit
            when (action) {
                AnswerSecondaryAction.ADD_TO_PROJECT,
                AnswerSecondaryAction.CHANGE_PROJECT,
                -> {
                    title = AnswerSecondaryActionPolicy.projectActionLabel(context)
                    subtitle = AnswerSecondaryActionPolicy.projectActionSubtitle(context)
                    onClick = onProject
                }
                AnswerSecondaryAction.COMPARE_RESPONSES -> {
                    title = "답변 비교"
                    subtitle = "다른 응답 버전과 비교합니다."
                    onClick = onCompare
                }
                AnswerSecondaryAction.OPEN_IN_TRANSLATION -> {
                    title = "번역에서 열기"
                    subtitle = "답변을 번역 도구로 보냅니다."
                    onClick = onTranslation
                }
                AnswerSecondaryAction.VIEW_ANSWER_CONTEXT -> {
                    title = "이 답변의 컨텍스트"
                    subtitle = "사용한 정보 경로를 확인합니다."
                    onClick = onViewContext
                }
                AnswerSecondaryAction.FIND_RELATED_MATERIALS -> {
                    title = "관련 자료 찾기"
                    subtitle = "관련 자료를 찾을 질문을 작성합니다."
                    onClick = onRelatedMaterials
                }
                AnswerSecondaryAction.EXTRACT_STRUCTURED -> {
                    title = "구조화해서 추출"
                    subtitle = "수치, 스펙, 일정, 비교 항목 또는 표를 읽기 좋게 정리합니다."
                    onClick = onStructuredExtraction
                }
                AnswerSecondaryAction.SAVE_TO_KNOWLEDGE -> {
                    title = "노트로 저장"
                    subtitle = "보이는 답변을 검토한 뒤 개인 또는 프로젝트에 보관합니다."
                    onClick = onSaveKnowledge
                }
                AnswerSecondaryAction.CREATE_PROJECT_TASK -> {
                    title = "할 일로 만들기"
                    subtitle = "프로젝트에 추가하기 전에 제목과 메모를 확인합니다."
                    onClick = onCreateProjectTask
                }
                AnswerSecondaryAction.SAVE_AS_DECISION -> {
                    title = "결정으로 저장"
                    subtitle = "프로젝트 결정으로 저장할 내용을 검토해 주세요."
                    onClick = onSaveAsDecision
                }
                AnswerSecondaryAction.CREATE_REVIEW_CARD -> {
                    title = "복습 카드 만들기"
                    subtitle = "질문과 답을 검토한 뒤 복습에 저장합니다."
                    onClick = onCreateReviewCard
                }
                AnswerSecondaryAction.SAVE_CHECKPOINT -> {
                    title = "체크포인트 저장"
                    subtitle = "이 대화 지점으로 돌아올 수 있게 저장합니다."
                    onClick = onSaveCheckpoint
                }
            }
            DropdownMenuItem(
                text = {
                    Column(modifier = Modifier.heightIn(min = FusionSpacing.menuItemMinHeight)) {
                        Text(
                            text = title,
                            color = semantic.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            color = semantic.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                onClick = onClick,
            )
        }
    }
    if (inline) items() else FusionDropdownMenu(expanded = true, onDismissRequest = onDismiss, containerColor = semantic.menu) { items() }
}
