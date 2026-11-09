package com.projectnuke.fusion.inbox

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** The visible bulk owner for selection actions, including bounded Smart Review. */
@Composable
internal fun InboxBulkActions(
    selectedCount: Int,
    triageInFlight: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onMoveToProject: () -> Unit,
    onMoveToKnowledge: () -> Unit,
    onAddTag: () -> Unit,
    onSmartReview: () -> Unit,
    onDelete: () -> Unit,
) {
    TextButton(
        onClick = { onExpandedChange(true) },
        enabled = selectedCount > 0 && !triageInFlight,
    ) { Text("작업 ($selectedCount)") }
    DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
        DropdownMenuItem(text = { Text("Project에 이동") }, onClick = onMoveToProject)
        DropdownMenuItem(text = { Text("저장 지식으로 이동") }, onClick = onMoveToKnowledge)
        DropdownMenuItem(text = { Text("태그 추가") }, onClick = onAddTag)
        DropdownMenuItem(
            text = { Text(InboxSmartBatchReviewPolicy.bulkActionLabel(selectedCount)) },
            enabled = InboxSmartBatchReviewPolicy.canStart(selectedCount) && !triageInFlight,
            onClick = onSmartReview,
        )
        DropdownMenuItem(text = { Text("삭제") }, onClick = onDelete)
    }
}
