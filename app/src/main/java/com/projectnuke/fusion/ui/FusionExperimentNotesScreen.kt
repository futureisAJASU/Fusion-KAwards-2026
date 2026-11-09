package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val FusionExperimentNotesPrefs = "fusion_experiment_notes"
private const val FusionExperimentNotesKey = "fusion_experiment_notes_text"

private val ExperimentNotesPanelBg: Color @Composable get() = LocalFusionSemanticColors.current.panel
private val ExperimentNotesCardBg: Color @Composable get() = LocalFusionSemanticColors.current.menu
private val ExperimentNotesTextPrimary: Color @Composable get() = LocalFusionSemanticColors.current.onSurface
private val ExperimentNotesTextSecondary: Color @Composable get() = LocalFusionSemanticColors.current.onSurfaceVariant
private val ExperimentNotesAccentBlue: Color @Composable get() = LocalFusionSemanticColors.current.primary
private val ExperimentNotesDanger: Color @Composable get() = LocalFusionSemanticColors.current.error

@Composable
fun FusionExperimentNotesDialog(
    context: Context,
    clipboard: ClipboardManager,
    onDismiss: () -> Unit
) {
    val prefs = remember(context) {
        context.getSharedPreferences(FusionExperimentNotesPrefs, Context.MODE_PRIVATE)
    }
    var noteText by remember {
        mutableStateOf(prefs.getString(FusionExperimentNotesKey, "").orEmpty())
    }
    var showResetConfirm by remember { mutableStateOf(false) }

    FusionReadingDialog(
        scrollBody = false,
        onDismissRequest = onDismiss,
        title = { Text("실험 노트") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ExperimentNotesCard {
                    Text("모델 테스트와 벤치마크에서 발견한 내용을 기록합니다.", color = ExperimentNotesTextSecondary, fontSize = 12.sp)
                    Text("실험 노트는 이 기기에만 저장됩니다.", color = ExperimentNotesTextSecondary, fontSize = 12.sp)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        ExperimentNotesCard {
                            OutlinedTextField(
                                value = noteText,
                                onValueChange = { noteText = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 220.dp),
                                placeholder = {
                                    Text("예: S24에서는 MTP 끔이 더 빠릅니다.", color = ExperimentNotesTextSecondary)
                                },
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = ExperimentNotesTextPrimary,
                                    fontSize = 13.sp
                                ),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                minLines = 8,
                                maxLines = 16,
                                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = ExperimentNotesTextPrimary,
                                    unfocusedTextColor = ExperimentNotesTextPrimary,
                                    focusedBorderColor = ExperimentNotesAccentBlue,
                                    unfocusedBorderColor = ExperimentNotesTextSecondary,
                                    focusedContainerColor = ExperimentNotesCardBg,
                                    unfocusedContainerColor = ExperimentNotesCardBg,
                                    focusedPlaceholderColor = ExperimentNotesTextSecondary,
                                    unfocusedPlaceholderColor = ExperimentNotesTextSecondary
                                )
                            )
                        }
                    }
                }
                ExperimentNotesActionFooter(
                    onCopy = {
                        if (noteText.isBlank()) {
                            Toast.makeText(context, "복사할 실험 노트가 없습니다.", Toast.LENGTH_SHORT).show()
                        } else {
                            clipboard.setText(AnnotatedString(noteText))
                            Toast.makeText(context, "실험 노트를 복사했습니다.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onSave = {
                        prefs.edit().putString(FusionExperimentNotesKey, noteText).apply()
                        Toast.makeText(context, "실험 노트를 저장했습니다.", Toast.LENGTH_SHORT).show()
                    },
                    onReset = { showResetConfirm = true },
                    onDismiss = onDismiss
                )
            }
        },
        confirmButton = {},
        dismissButton = {},
        containerColor = ExperimentNotesPanelBg,
        titleContentColor = ExperimentNotesTextPrimary,
        textContentColor = ExperimentNotesTextPrimary
    )

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("실험 노트를 초기화하시겠습니까?") },
            text = { Text("저장된 실험 노트 내용만 삭제됩니다. 채팅 기록과 모델 파일은 삭제되지 않습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().remove(FusionExperimentNotesKey).apply()
                    noteText = ""
                    showResetConfirm = false
                    Toast.makeText(context, "실험 노트를 초기화했습니다.", Toast.LENGTH_SHORT).show()
                }) { Text("초기화", color = ExperimentNotesDanger) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("취소", color = ExperimentNotesTextSecondary)
                }
            },
            containerColor = ExperimentNotesPanelBg,
            titleContentColor = ExperimentNotesTextPrimary,
            textContentColor = ExperimentNotesTextPrimary
        )
    }
}

@Composable
private fun ExperimentNotesActionFooter(
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = onCopy,
                modifier = Modifier.weight(1f)
            ) { Text("복사", color = ExperimentNotesAccentBlue) }

            TextButton(
                onClick = onSave,
                modifier = Modifier.weight(1f)
            ) { Text("저장", color = ExperimentNotesAccentBlue) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = onReset,
                modifier = Modifier.weight(1f)
            ) { Text("초기화", color = ExperimentNotesDanger) }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            ) { Text("닫기", color = ExperimentNotesTextSecondary) }
        }
    }
}

@Composable
private fun ExperimentNotesCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ExperimentNotesCardBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            content()
        }
    }
}
