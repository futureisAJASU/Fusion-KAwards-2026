package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.chat.AnswerContextPresentation
import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

/**
 * Next Action Flow v1C — Answer Context Inspector dialog.
 *
 * Shows ONLY facts the immutable AnswerContextReceipt actually proves about
 * how this specific persisted answer was produced: which information paths
 * were used. It renders the pure [AnswerContextPresentation] rows — no ranges,
 * no percentages, no confidence, no reasoning trace, no prompt/excerpt/path
 * disclosure — and performs zero inference / zero network / zero parsing at
 * open time (the receipt was already parsed by the caller). Source-DETAIL
 * counts summarize; the existing source capsules/dialogs remain the detail
 * affordance, so no second source list exists here.
 */
@Composable
internal fun AnswerContextInspectorDialog(
    receipt: AnswerContextReceipt,
    onDismiss: () -> Unit,
) {
    val semantic = LocalFusionSemanticColors.current
    val rows = AnswerContextPresentation.inspectorRows(receipt)
    FusionReadingDialog(
        onDismissRequest = onDismiss,
        title = { Text("이 답변의 컨텍스트", color = semantic.onSurface) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "이 답변을 만드는 데 사용된 정보 경로입니다.",
                    color = semantic.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(10.dp))
                rows.forEach { row ->
                    Row2(section = row.section, value = row.value)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("확인", color = semantic.primary)
            }
        },
        containerColor = semantic.menu,
        titleContentColor = semantic.onSurface,
        textContentColor = semantic.onSurface,
    )
}

@Composable
private fun Row2(section: String, value: String) {
    val semantic = LocalFusionSemanticColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = section,
            color = semantic.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = semantic.onSurface,
            fontSize = 14.sp,
        )
    }
}