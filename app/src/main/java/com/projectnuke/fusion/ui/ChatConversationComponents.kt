package com.projectnuke.fusion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun InChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val semanticColors = LocalFusionSemanticColors.current
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = semanticColors.panel) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("대화 내 검색", color = semanticColors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(color = semanticColors.onSurface, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isBlank()) Text("메시지를 검색합니다.", color = semanticColors.onSurfaceVariant, fontSize = 14.sp)
                    inner()
                },
            )
            Spacer(Modifier.width(10.dp))
            Text("닫기", color = semanticColors.primary, fontSize = 13.sp, modifier = Modifier.clickable { onClose() })
        }
    }
}

@Composable
internal fun InChatSearchResultRow(role: String, preview: String, onClick: () -> Unit) {
    val semanticColors = LocalFusionSemanticColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        color = semanticColors.panel,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(role, color = semanticColors.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(preview.ifBlank { "(내용 없음)" }, color = semanticColors.onSurface, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun EmptyInChatSearchResults() {
    val semanticColors = LocalFusionSemanticColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("검색 결과가 없습니다.", color = semanticColors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun ConversationSummaryDialog(
    summaryMemory: ConversationSummaryMemory?,
    onGenerate: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val updatedAtText = remember(summaryMemory?.updatedAt) {
        summaryMemory?.updatedAt
            ?.takeIf { it > 0L }
            ?.let { SimpleDateFormat("yyyy.MM.dd a h:mm", Locale.KOREAN).format(Date(it)) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("대화 요약") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "현재 대화의 핵심 내용을 저장해 긴 대화에서도 맥락을 유지합니다.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = ReleaseCardBgOrPanel()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (summaryMemory == null) {
                            Text("저장된 요약이 없습니다.", color = TextSecondary, fontSize = 14.sp)
                        } else {
                            updatedAtText?.let {
                                Text("업데이트: $it", color = TextSecondary, fontSize = 12.sp)
                            }
                            Text(
                                text = summaryMemory.summary,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onGenerate) { Text("요약 생성", color = AccentBlue) }
                TextButton(onClick = onEdit) { Text("직접 편집", color = AccentBlue) }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCopy, enabled = summaryMemory != null) { Text("요약 복사", color = AccentBlue) }
                TextButton(onClick = onDelete, enabled = summaryMemory != null) { Text("요약 삭제", color = DangerRed) }
                TextButton(onClick = onDismiss) { Text("닫기", color = TextSecondary) }
            }
        },
        containerColor = PanelBg,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary
    )
}

@Composable
internal fun ConversationSummaryEditorDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("대화 요약 편집") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                placeholder = { Text("이 대화에서 계속 참고할 핵심 내용을 입력해 주세요.") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = LineColor,
                    focusedContainerColor = PanelBg,
                    unfocusedContainerColor = PanelBg,
                    focusedPlaceholderColor = TextSecondary,
                    unfocusedPlaceholderColor = TextSecondary
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text("저장", color = AccentBlue)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소", color = TextSecondary)
            }
        },
        containerColor = PanelBg,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary
    )
}

@Composable
internal fun MemoryCandidateDialog(
    candidateText: String,
    isExtracting: Boolean,
    savedCount: Int,
    onExtract: () -> Unit,
    onCopy: () -> Unit,
    onSaveSelected: (List<String>) -> Unit,
    onSaveAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val semanticColors = LocalFusionSemanticColors.current
    val parsedCandidates = remember(candidateText) { parseMemoryCandidateLines(candidateText) }
    var selectedCandidates by remember(candidateText) { mutableStateOf(parsedCandidates.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("메모리 후보 추출") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "이 대화에서 나중에 참고할 만한 정보를 정리합니다.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Text(
                    text = "자동 저장하지 않습니다. 필요한 항목만 확인해 주세요.",
                    color = semanticColors.primary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                if (savedCount > 0) {
                    Text(
                        text = "저장된 후보 ${savedCount}개",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = ReleaseCardBgOrPanel()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isExtracting) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = AccentBlue,
                                    strokeWidth = 2.dp
                                )
                                Text("메모리 후보를 추출하는 중입니다.", color = TextPrimary, fontSize = 13.sp)
                            }
                        } else if (candidateText.isBlank()) {
                            Text("추출된 메모리 후보가 없습니다.", color = TextSecondary, fontSize = 14.sp)
                        } else if (parsedCandidates.isEmpty()) {
                            Text(
                                text = candidateText,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        } else {
                            parsedCandidates.forEach { candidate ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Checkbox(
                                        checked = selectedCandidates.contains(candidate),
                                        onCheckedChange = { checked ->
                                            selectedCandidates = if (checked) {
                                                selectedCandidates + candidate
                                            } else {
                                                selectedCandidates - candidate
                                            }
                                        }
                                    )
                                    Text(
                                        text = candidate,
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onExtract, enabled = !isExtracting) { Text("후보 추출", color = AccentBlue) }
                TextButton(onClick = { onSaveSelected(selectedCandidates.toList()) }, enabled = parsedCandidates.isNotEmpty() && !isExtracting) {
                    Text("선택 항목 저장", color = AccentBlue, maxLines = 1)
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSaveAll, enabled = parsedCandidates.isNotEmpty() && !isExtracting) {
                    Text("전체 저장", color = AccentBlue)
                }
                TextButton(onClick = onCopy, enabled = candidateText.isNotBlank() && !isExtracting) {
                    Text("후보 복사", color = AccentBlue)
                }
                TextButton(onClick = onDismiss) { Text("닫기", color = TextSecondary) }
            }
        },
        containerColor = PanelBg,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary
    )
}

@Composable
private fun ReleaseCardBgOrPanel(): Color = LocalFusionSemanticColors.current.menu

internal fun parseMemoryCandidateLines(raw: String): List<String> {
    return raw
        .lines()
        .map { line ->
            line.replace(Regex("""^(?:[\-\*\u2022\u25CF\u25E6]\s*|\d+[\.\)]\s*)"""), "").trim()
        }
        .filter { it.isNotBlank() }
        .distinct()
}
