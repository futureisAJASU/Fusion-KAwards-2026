package com.projectnuke.fusion.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

private val AbHistoryBg: Color
    @Composable get() = LocalFusionSemanticColors.current.background
private val AbHistoryCard: Color
    @Composable get() = LocalFusionSemanticColors.current.menu
private val AbHistoryPanel: Color
    @Composable get() = LocalFusionSemanticColors.current.panel
private val AbHistoryText: Color
    @Composable get() = LocalFusionSemanticColors.current.onSurface
private val AbHistorySubtle: Color
    @Composable get() = LocalFusionSemanticColors.current.onSurfaceVariant
private val AbHistoryAccent: Color
    @Composable get() = LocalFusionSemanticColors.current.primary
private val AbHistoryFail: Color
    @Composable get() = LocalFusionSemanticColors.current.error

@Composable
fun ModelAbTestHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<StoredAbTestSession>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedSession by remember { mutableStateOf<StoredAbTestSession?>(null) }
    var pendingDeleteSession by remember { mutableStateOf<StoredAbTestSession?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val filteredSessions = remember(sessions, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            sessions
        } else {
            sessions.filter { session ->
                session.fullPrompt.contains(query, ignoreCase = true) ||
                    session.results.any { it.modelName.contains(query, ignoreCase = true) } ||
                    formatAbHistoryTime(session.createdAt).contains(query)
            }
        }
    }

    BackHandler {
        if (selectedSession != null) selectedSession = null else onBack()
    }

    LaunchedEffect(context) {
        sessions = ModelAbTestHistoryStore.loadAsync(context)
    }

    if (selectedSession != null) {
        AbHistoryDetail(
            session = selectedSession!!,
            onBack = { selectedSession = null },
            onCopy = {
                clipboard.setText(AnnotatedString(selectedSession!!.toMarkdown()))
                Toast.makeText(context, "A/B 테스트 기록을 복사했습니다.", Toast.LENGTH_SHORT).show()
            },
            onRating = { targetLabel, rating ->
                val session = selectedSession ?: return@AbHistoryDetail
                scope.launch {
                    if (!ModelAbTestHistoryStore.updateRating(context, session.id, targetLabel, rating)) {
                        Toast.makeText(context, "평가를 저장하지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    sessions = ModelAbTestHistoryStore.loadAsync(context)
                    selectedSession = sessions.firstOrNull { it.id == session.id }
                    val toast = when (rating) {
                        AbResultRating.NONE -> "\uD3C9\uAC00\uB97C \uCDE8\uC18C\uD588\uC2B5\uB2C8\uB2E4."
                        AbResultRating.PREFERRED -> "\uC120\uD638 \uACB0\uACFC\uB85C \uD45C\uC2DC\uD588\uC2B5\uB2C8\uB2E4."
                        AbResultRating.DISLIKED -> "\uD3C9\uAC00\uB97C \uC800\uC7A5\uD588\uC2B5\uB2C8\uB2E4."
                    }
                    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
                }
            }
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AbHistoryBg).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("최근 A/B 테스트", color = AbHistoryText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("A/B 테스트 기록에는 테스트 프롬프트와 생성 결과가 저장될 수 있습니다.", color = AbHistorySubtle, fontSize = 12.sp)
                }
                TextButton(onClick = onBack) { Text("뒤로", color = AbHistoryText) }
            }
        }
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("A/B 테스트 기록을 검색합니다.", color = AbHistorySubtle) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AbHistoryAccent,
                    unfocusedBorderColor = AbHistoryPanel,
                    cursorColor = AbHistoryAccent,
                    focusedTextColor = AbHistoryText,
                    unfocusedTextColor = AbHistoryText
                )
            )
        }
        item {
            TextButton(enabled = sessions.isNotEmpty(), onClick = { showClearConfirm = true }) {
                Text("전체 기록 삭제", color = if (sessions.isNotEmpty()) AbHistoryFail else AbHistorySubtle)
            }
        }
        if (filteredSessions.isEmpty()) {
            item {
                HistoryCard {
                    Text(
                        if (sessions.isEmpty()) "저장된 A/B 테스트 기록이 없습니다." else "검색 결과가 없습니다.",
                        color = AbHistorySubtle
                    )
                }
            }
        } else {
            items(filteredSessions, key = { it.id }) { session ->
                HistorySessionCard(
                    session = session,
                    onOpen = { selectedSession = session },
                    onCopy = {
                        clipboard.setText(AnnotatedString(session.toMarkdown()))
                        Toast.makeText(context, "A/B 테스트 기록을 복사했습니다.", Toast.LENGTH_SHORT).show()
                    },
                    onDelete = { pendingDeleteSession = session }
                )
            }
        }
    }

    if (showClearConfirm || pendingDeleteSession != null) {
        AlertDialog(
            onDismissRequest = {
                showClearConfirm = false
                pendingDeleteSession = null
            },
            title = { Text("A/B 테스트 기록을 삭제하시겠습니까?") },
            text = { Text("저장된 A/B 테스트 결과만 삭제됩니다. 채팅 기록과 모델 파일은 삭제되지 않습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val deleted = pendingDeleteSession?.let { ModelAbTestHistoryStore.delete(context, it.id) }
                            ?: ModelAbTestHistoryStore.clear(context)
                        if (!deleted) {
                            Toast.makeText(context, "기록을 삭제하지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        sessions = ModelAbTestHistoryStore.loadAsync(context)
                        selectedSession = null
                        pendingDeleteSession = null
                        showClearConfirm = false
                        Toast.makeText(context, "A/B \uD14C\uC2A4\uD2B8 \uAE30\uB85D\uC744 \uC0AD\uC81C\uD588\uC2B5\uB2C8\uB2E4.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("\uC0AD\uC81C", color = AbHistoryFail) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingDeleteSession = null
                    showClearConfirm = false
                }) { Text("취소", color = AbHistorySubtle) }
            },
            containerColor = AbHistoryPanel,
            titleContentColor = AbHistoryText,
            textContentColor = AbHistoryText
        )
    }
}

@Composable
private fun HistorySessionCard(
    session: StoredAbTestSession,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val fastest = session.results.filter { it.success && it.decodeTokensPerSecond != null }
        .maxByOrNull { it.decodeTokensPerSecond ?: 0.0 }
    HistoryCard {
        Text(session.promptPreview.ifBlank { "프롬프트 없음" }, color = AbHistoryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(formatAbHistoryTime(session.createdAt), color = AbHistorySubtle, fontSize = 12.sp)
        Text("대상 ${session.targetCount}개 · 가장 빠른 대상 ${fastest?.targetLabel ?: "측정 불가"} · 실패 ${session.failureCount}개", color = AbHistorySubtle, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onOpen) { Text("열기", color = AbHistoryAccent) }
            TextButton(onClick = onCopy) { Text("복사", color = AbHistoryText) }
            TextButton(onClick = onDelete) { Text("삭제", color = AbHistoryFail) }
        }
    }
}

@Composable
private fun AbHistoryDetail(
    session: StoredAbTestSession,
    onBack: () -> Unit,
    onCopy: () -> Unit,
    onRating: (String, AbResultRating) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AbHistoryBg).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("A/B 테스트 결과", color = AbHistoryText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(formatAbHistoryTime(session.createdAt), color = AbHistorySubtle, fontSize = 12.sp)
                }
                TextButton(onClick = onBack) { Text("뒤로", color = AbHistoryText) }
            }
        }
        item {
            HistoryCard {
                Text("테스트 프롬프트", color = AbHistoryText, fontWeight = FontWeight.SemiBold)
                Text(session.fullPrompt, color = AbHistorySubtle, fontSize = 13.sp)
                Text("대상 수: ${session.targetCount}", color = AbHistorySubtle, fontSize = 12.sp)
                TextButton(onClick = onCopy) { Text("결과 복사", color = AbHistoryAccent) }
            }
        }
        item {
            HistoryCard {
                Text("비교 요약", color = AbHistoryText, fontWeight = FontWeight.SemiBold)
                Text(buildStoredAbComparisonSummary(session), color = AbHistorySubtle, fontSize = 12.sp)
            }
        }
        items(session.results, key = { it.targetLabel }) { result ->
            HistoryCard {
                Text("대상 ${result.targetLabel} · ${result.modelName}", color = AbHistoryText, fontWeight = FontWeight.SemiBold)
                Text("상태: ${if (result.success) "성공" else "실패"}", color = if (result.success) AbHistorySubtle else AbHistoryFail, fontSize = 12.sp)
                Text(result.settingsSummary(), color = AbHistorySubtle, fontSize = 12.sp)
                Text(
                    "첫 토큰 ${result.firstTokenLatencyMs?.let { "${it}ms" } ?: "측정 불가"} · 총 ${result.totalGenerationTimeMs}ms · 전체 ${String.format(java.util.Locale.US, "%.1f tok/s", result.totalTokensPerSecond)} · 디코딩 ${result.decodeTokensPerSecond?.let { String.format(java.util.Locale.US, "%.1f tok/s", it) } ?: "측정 불가"}",
                    color = AbHistorySubtle,
                    fontSize = 12.sp
                )
                if (result.success) {
                    Text(result.answer.orEmpty(), color = AbHistoryText, fontSize = 13.sp)
                } else {
                    Text(result.errorSummary ?: "이 대상의 실행에 실패했습니다.", color = AbHistoryFail, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = result.success, onClick = {
                        clipboard.setText(AnnotatedString(result.answer.orEmpty()))
                        Toast.makeText(context, "답변을 복사했습니다.", Toast.LENGTH_SHORT).show()
                    }) { Text("답변 복사", color = if (result.success) AbHistoryAccent else AbHistorySubtle) }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(result.settingsSummary()))
                        Toast.makeText(context, "설정을 복사했습니다.", Toast.LENGTH_SHORT).show()
                    }) { Text("설정 복사", color = AbHistoryText) }
                    TextButton(onClick = {
                        val text = session.copy(results = listOf(result)).toMarkdown()
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(context, "결과를 복사했습니다.", Toast.LENGTH_SHORT).show()
                    }) { Text("결과 복사", color = AbHistoryText) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        onRating(result.targetLabel, if (result.rating == AbResultRating.PREFERRED) AbResultRating.NONE else AbResultRating.PREFERRED)
                    }) { Text(if (result.rating == AbResultRating.PREFERRED) "선호 취소" else "선호", color = AbHistoryAccent) }
                    TextButton(onClick = {
                        onRating(result.targetLabel, if (result.rating == AbResultRating.DISLIKED) AbResultRating.NONE else AbResultRating.DISLIKED)
                    }) { Text(if (result.rating == AbResultRating.DISLIKED) "평가 취소" else "비선호", color = AbHistorySubtle) }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = AbHistoryCard,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content)
    }
}
