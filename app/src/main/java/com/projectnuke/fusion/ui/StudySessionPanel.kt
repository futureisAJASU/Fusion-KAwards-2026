package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.chat.STUDY_MODE_ACTION_OPTIONS
import com.projectnuke.fusion.chat.StudySessionAction

/** Every callback still uses the parent's durable draft and single-flight gates. */
@Composable
internal fun StudySessionPanel(
    enabled: Boolean,
    gradingEnabled: Boolean,
    explanationEnabled: Boolean,
    exitEnabled: Boolean,
    onRequestQuestion: () -> Unit,
    onRequestHint: () -> Unit,
    onRequestGrading: () -> Unit,
    onRequestExplanation: () -> Unit,
    onRequestNext: () -> Unit,
    onExitToNormal: () -> Unit,
    onOpenProgress: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberFusionPopupAnchor()
    LaunchedEffect(enabled, gradingEnabled, explanationEnabled, exitEnabled) { expanded = false }
    Row(Modifier.fillMaxWidth().padding(horizontal = FusionSpacing.screen), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(enabled = enabled, onClick = onRequestHint) { Text("힌트 준비") }
        Box {
            TextButton(onClick = { expanded = true }, modifier = Modifier.fusionPopupAnchor(anchor)) { Text("학습 작업") }
            FusionDropdownMenu(expanded, { expanded = false }, anchor = anchor) {
                Text(com.projectnuke.fusion.chat.STUDY_MODE_HELPER_COPY,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                STUDY_MODE_ACTION_OPTIONS.forEach { option ->
                    val allowed = when (option.action) {
                        StudySessionAction.GRADING -> gradingEnabled
                        StudySessionAction.EXPLANATION -> explanationEnabled
                        else -> enabled
                    }
                    DropdownMenuItem(text = { Text(option.label) }, enabled = allowed, onClick = {
                        expanded = false
                        when (option.action) {
                            StudySessionAction.QUESTION -> onRequestQuestion()
                            StudySessionAction.HINT -> onRequestHint()
                            StudySessionAction.GRADING -> onRequestGrading()
                            StudySessionAction.EXPLANATION -> onRequestExplanation()
                            StudySessionAction.NEXT_QUESTION -> onRequestNext()
                        }
                    })
                }
                DropdownMenuItem(text = { Text("학습 기록 · 다시 볼 항목") }, onClick = { expanded = false; onOpenProgress() })
                DropdownMenuItem(text = { Text(com.projectnuke.fusion.chat.STUDY_MODE_END_LABEL) },
                    enabled = exitEnabled, onClick = { expanded = false; onExitToNormal() })
            }
        }
    }
}
