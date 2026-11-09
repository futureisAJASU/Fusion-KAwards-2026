package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.data.BenchmarkResultEntity

private val DevLogPanelBg: Color @Composable get() = LocalFusionSemanticColors.current.panel
private val DevLogCardBg: Color @Composable get() = LocalFusionSemanticColors.current.menu
private val DevLogTextPrimary: Color @Composable get() = LocalFusionSemanticColors.current.onSurface
private val DevLogTextSecondary: Color @Composable get() = LocalFusionSemanticColors.current.onSurfaceVariant
private val DevLogAccentBlue: Color @Composable get() = LocalFusionSemanticColors.current.primary

@Composable
fun DeveloperLogDialog(
    context: Context,
    prefs: SharedPreferences,
    clipboard: ClipboardManager,
    benchmarkResults: List<BenchmarkResultEntity>,
    onDismiss: () -> Unit
) {
    var refreshKey by remember { mutableStateOf(0) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var loadedEvents by remember { mutableStateOf<Pair<Int, List<FusionDeveloperLogEvent>>?>(null) }
    val events = loadedEvents?.takeIf { it.first == refreshKey }?.second
    var snapshotState by remember { mutableStateOf<FusionDeveloperLogSnapshot?>(null) }
    LaunchedEffect(refreshKey) {
        snapshotState = null
        val loaded = withContext(Dispatchers.IO) { FusionDeveloperLogStore.load(context) }
        loadedEvents = refreshKey to loaded
    }
    LaunchedEffect(refreshKey, benchmarkResults, events) {
        val currentEvents = events ?: return@LaunchedEffect
        snapshotState = buildFusionDeveloperLogSnapshotAsync(
            context = context,
            prefs = prefs,
            benchmarkResults = benchmarkResults,
            events = currentEvents
        )
    }
    val snapshot = snapshotState

    FusionReadingDialog(
        scrollBody = false,
        onDismissRequest = onDismiss,
        title = { Text("개발자 로그") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (snapshot == null) {
                    DeveloperSectionCard("개발자 로그", "로그 정보를 불러오는 중입니다...")
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item { DeveloperSectionCard("현재 상태", snapshot.sections.currentStatus) }
                        item { DeveloperSectionCard("모델", snapshot.sections.model) }
                        item { DeveloperSectionCard("메모리", snapshot.sections.deviceMemory) }
                        item { DeveloperSectionCard("저장된 메모리", snapshot.sections.savedMemory) }
                        item { DeveloperSectionCard("A/B 테스트", snapshot.sections.abTests) }
                        item { DeveloperSectionCard("최근 오류", snapshot.sections.recentErrors) }
                        item { DeveloperSectionCard("벤치마크", snapshot.sections.benchmark) }
                        item { DeveloperSectionCard("설정", snapshot.sections.settings) }
                    }
                }

                DeveloperLogActionFooter(
                    onCopyLog = {
                        snapshot?.let {
                            clipboard.setText(AnnotatedString(it.fullLogText))
                            Toast.makeText(context, "개발자 로그를 복사했습니다.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onCopyErrorReport = {
                        snapshot?.let {
                            clipboard.setText(AnnotatedString(it.errorReportText))
                            Toast.makeText(context, "오류 보고서를 복사했습니다.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onClear = { showClearConfirm = true },
                    onDismiss = onDismiss
                )
            }
        },
        confirmButton = {},
        dismissButton = {},
        containerColor = DevLogPanelBg,
        titleContentColor = DevLogTextPrimary,
        textContentColor = DevLogTextPrimary
    )

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("개발자 로그를 지우시겠습니까?") },
            text = { Text("최근 오류 및 진단 기록만 지워집니다. 채팅 기록은 삭제되지 않습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    FusionDeveloperLogStore.clear(context)
                    refreshKey += 1
                    showClearConfirm = false
                    Toast.makeText(context, "개발자 로그를 지웠습니다.", Toast.LENGTH_SHORT).show()
                }) { Text("지우기", color = DevLogAccentBlue) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("취소", color = DevLogTextSecondary) }
            },
            containerColor = DevLogPanelBg,
            titleContentColor = DevLogTextPrimary,
            textContentColor = DevLogTextPrimary
        )
    }
}

@Composable
private fun DeveloperLogActionFooter(
    onCopyLog: () -> Unit,
    onCopyErrorReport: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(shape = RoundedCornerShape(12.dp), color = DevLogCardBg, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                DeveloperFooterButton("개발자 로그 복사", onCopyLog, Modifier.weight(1f), DevLogAccentBlue)
                DeveloperFooterButton("오류 보고서 복사", onCopyErrorReport, Modifier.weight(1f), DevLogAccentBlue)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                DeveloperFooterButton("로그 지우기", onClear, Modifier.weight(1f), DevLogAccentBlue)
                DeveloperFooterButton("닫기", onDismiss, Modifier.weight(1f), DevLogTextSecondary)
            }
        }
    }
}

@Composable
private fun DeveloperFooterButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = DevLogAccentBlue
) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text, color = color, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DeveloperSectionCard(title: String, text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = DevLogCardBg, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = DevLogTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            text.lines().forEach { line ->
                if (line.isNotBlank()) {
                    Text(line, color = DevLogTextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}
