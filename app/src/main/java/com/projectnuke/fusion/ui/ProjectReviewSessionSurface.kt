package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.projects.ProjectReviewSessionPolicy

/** Transient guided Project review; no durable mutation authority. */
@Composable
internal fun ProjectReviewSessionSurface(
    items: List<ProjectReviewSessionPolicy.ReviewItem>,
    currentIndex: Int,
    targetUnavailable: Boolean = false,
    openingTarget: Boolean = false,
    onOpenItem: (ProjectReviewSessionPolicy.ReviewItem) -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    onDismiss: () -> Unit,
) {
    val currentItem = items.getOrNull(currentIndex)
    val finished = items.isNotEmpty() && currentIndex >= items.size
    FusionReadingDialog(
        onDismissRequest = onDismiss,
        title = { Text("프로젝트 검토") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FusionSpacing.section)) {
                when {
                    items.isEmpty() -> Text("이번 검토에서 확인할 항목이 없어요.", style = MaterialTheme.typography.bodyMedium)
                    finished -> Text("이번 검토에서 확인할 항목을 모두 살펴봤어요.", style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        Text("${currentIndex + 1} / ${items.size}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(currentItem?.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        Text(currentItem?.subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        currentItem?.let {
                            Text(ProjectReviewSessionPolicy.labelFor(it.kind), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (targetUnavailable) {
                            Text("이 항목은 더 이상 검토할 수 없어요.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                if (currentItem != null && targetUnavailable) {
                    TextButton(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("다음")
                    }
                } else if (currentItem != null) {
                    TextButton(
                        onClick = { onOpenItem(currentItem) },
                        enabled = !openingTarget,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("열기")
                    }
                    TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("건너뛰기")
                    }
                } else if (finished || items.isEmpty()) {
                    TextButton(onClick = onFinish, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (finished) "완료" else "닫기")
                    }
                }
            }
        },
        dismissButton = if (currentItem != null) ({
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp).testTag("project_review_session_dismiss"),
            ) { Text("닫기") }
        }) else null,
    )
}
