package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.modelzoo.FusionModelSpec
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.modelzoo.ModelRuntimeFormat
import com.projectnuke.fusion.util.FusionMemoryManager
import com.projectnuke.fusion.util.collectFusionSocInfo
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val HealthPanelBg: Color @Composable get() = LocalFusionSemanticColors.current.panel
private val HealthCardBg: Color @Composable get() = LocalFusionSemanticColors.current.menu
private val HealthTextPrimary: Color @Composable get() = LocalFusionSemanticColors.current.onSurface
private val HealthTextSecondary: Color @Composable get() = LocalFusionSemanticColors.current.onSurfaceVariant
private val HealthAccentBlue: Color @Composable get() = LocalFusionSemanticColors.current.primary
private val HealthWarning: Color @Composable get() = LocalFusionSemanticColors.current.warning
private val HealthError: Color @Composable get() = LocalFusionSemanticColors.current.error

enum class FusionHealthStatus {
    PASS,
    WARNING,
    FAIL,
    UNKNOWN
}

data class FusionHealthCheckItem(
    val id: String,
    val title: String,
    val detail: String,
    val status: FusionHealthStatus,
    val actionLabel: String? = null,
    val actionKey: String? = null
)

private data class FusionHealthCheckGroup(
    val title: String,
    val items: List<FusionHealthCheckItem>
)

private data class FusionHealthSnapshot(
    val groups: List<FusionHealthCheckGroup>,
    val overallStatus: FusionHealthStatus,
    val overallTitle: String,
    val overallSummary: String,
    val reportText: String
)

internal class FusionHealthCheckRequestGate {
    private val latestRequest = AtomicLong(0L)

    fun begin(): Long = latestRequest.incrementAndGet()

    fun isLatest(requestId: Long): Boolean = latestRequest.get() == requestId

    fun invalidate() {
        latestRequest.incrementAndGet()
    }
}

@Composable
fun FusionHealthCheckDialog(
    context: Context,
    prefs: SharedPreferences,
    db: AppDatabase,
    clipboard: ClipboardManager,
    onDismiss: () -> Unit,
    onOpenModelLibrary: (() -> Unit)? = null,
    onOpenAdvancedSettings: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val requestGate = remember { FusionHealthCheckRequestGate() }
    val activeJob = remember { AtomicReference<Job?>(null) }
    var snapshot by remember { mutableStateOf<FusionHealthSnapshot?>(null) }

    DisposableEffect(requestGate) {
        onDispose {
            requestGate.invalidate()
            activeJob.getAndSet(null)?.cancel()
        }
    }

    fun runHealthCheck(showToast: Boolean) {
        val requestId = requestGate.begin()
        val job = scope.launch {
            val nextSnapshot = withContext(Dispatchers.IO) {
                buildFusionHealthSnapshot(context, prefs, db)
            }
            if (!requestGate.isLatest(requestId)) return@launch
            snapshot = nextSnapshot
            DeveloperLogStore.record(context, "health", "상태 점검을 실행했습니다.")
            if (nextSnapshot.overallStatus == FusionHealthStatus.FAIL) {
                DeveloperLogStore.record(context, "health", "상태 점검에서 문제가 발견되었습니다.")
            }
            if (showToast) {
                Toast.makeText(context, "상태 점검을 완료했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        activeJob.getAndSet(job)?.cancel()
    }

    fun handleAction(actionKey: String) {
        when (actionKey) {
            "open_model_library" -> {
                onDismiss()
                onOpenModelLibrary?.invoke()
            }
            "open_advanced_settings" -> {
                onDismiss()
                onOpenAdvancedSettings?.invoke()
            }
            "memory_cleanup" -> {
                FusionMemoryManager.clearLightweightCaches()
                FusionMemoryManager.unloadIdleEngines()
                runHealthCheck(showToast = true)
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        runHealthCheck(showToast = false)
    }

    val currentSnapshot = snapshot

    FusionReadingDialog(
        scrollBody = false,
        onDismissRequest = onDismiss,
        title = { Text("Fusion 상태 점검") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HealthSummaryCard(currentSnapshot)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        HealthPlainCard(
                            title = "안내",
                            body = "앱의 주요 기능 상태를 빠르게 확인합니다."
                        )
                    }
                    if (currentSnapshot == null) {
                        item {
                            HealthPlainCard(
                                title = "점검 중",
                                body = "상태 점검 항목을 확인하고 있습니다."
                            )
                        }
                    } else {
                        items(currentSnapshot.groups, key = { it.title }) { group ->
                            HealthGroupCard(group = group, onAction = ::handleAction)
                        }
                    }
                    item {
                        HealthFooterCard(
                            onRefresh = {
                                runHealthCheck(showToast = true)
                            },
                            onCopy = {
                                currentSnapshot?.let {
                                    clipboard.setText(AnnotatedString(it.reportText))
                                    Toast.makeText(context, "점검 보고서를 복사했습니다.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDismiss = onDismiss
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
        containerColor = HealthPanelBg,
        titleContentColor = HealthTextPrimary,
        textContentColor = HealthTextPrimary
    )
}

@Composable
private fun HealthSummaryCard(snapshot: FusionHealthSnapshot?) {
    val title = snapshot?.overallTitle ?: "점검 중"
    val summary = snapshot?.overallSummary ?: "상태 점검 항목을 불러오고 있습니다."
    val color = statusColor(snapshot?.overallStatus ?: FusionHealthStatus.UNKNOWN)
    Surface(shape = RoundedCornerShape(12.dp), color = HealthCardBg, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(summary, color = HealthTextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HealthGroupCard(group: FusionHealthCheckGroup, onAction: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = HealthCardBg, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(group.title, color = HealthAccentBlue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            group.items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "${statusLabel(item.status)} · ${item.title}",
                            color = statusColor(item.status),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = item.detail,
                            color = HealthTextSecondary,
                            fontSize = 11.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!item.actionLabel.isNullOrBlank() && !item.actionKey.isNullOrBlank()) {
                        Text(
                            text = item.actionLabel,
                            color = HealthAccentBlue,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clickable { onAction(item.actionKey) }
                                .padding(top = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthPlainCard(title: String, body: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = HealthCardBg, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = HealthTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = HealthTextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HealthFooterCard(
    onRefresh: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(shape = RoundedCornerShape(12.dp), color = HealthCardBg, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onRefresh) { Text("다시 점검", color = HealthAccentBlue, fontSize = 12.sp) }
                TextButton(onClick = onCopy) { Text("점검 보고서 복사", color = HealthAccentBlue, fontSize = 12.sp) }
                TextButton(onClick = onDismiss) { Text("닫기", color = HealthTextSecondary, fontSize = 12.sp) }
            }
        }
    }
}

private suspend fun buildFusionHealthSnapshot(
    context: Context,
    prefs: SharedPreferences,
    db: AppDatabase
): FusionHealthSnapshot {
    val chatDao = db.chatDao()
    val benchmarkDao = db.benchmarkDao()
    val memorySnapshot = FusionMemoryManager.getMemorySnapshot(context)
    val socInfo = collectFusionSocInfo()
    val runtimeSnapshot = resolveFusionRuntimeDiagnosticsSnapshot(context, prefs)
    val selectedModelName = runtimeSnapshot.selectedModelName
    val selectedSpec = runtimeSnapshot.selectedSpec
    val modelSummary = mapSelectedModelReadinessForHealth(
        readiness = runtimeSnapshot.readiness,
        externalLinkedSelected = selectedSpec?.externallyReferenced == true,
        externalAccessible = selectedSpec?.externallyReferenced == true &&
            checkExternalModelAccess(context, selectedSpec),
    )
    val effectiveProfile = runtimeSnapshot.effectiveProfile
    val effectiveSettings = effectiveProfile.generationSettings
    val acceleratorName = effectiveSettings.accelerator.name
    val maxTokens = effectiveSettings.maxTokens
    val topK = effectiveSettings.topK
    val topP = effectiveSettings.topP
    val temperature = effectiveSettings.temperature
    val mtpEnabled = effectiveSettings.speculativeDecodingEnabled == true
    val reasoningEnabled = effectiveProfile.reasoningEnabled
    val reasoningBudget = effectiveSettings.reasoningBudgetTokens
    val webSearchEnabled = runtimeSnapshot.webSearchEnabled
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val lowMemoryMode = activityManager?.isLowRamDevice ?: false
    val conversationCount = runCatching { chatDao.getConversationCount() }.getOrElse { -1 }
    val messageCount = runCatching { chatDao.getMessageCount() }.getOrElse { -1 }
    val benchmarkCount = runCatching { benchmarkDao.countResults() }.getOrElse { -1 }
    val latestBenchmark = runCatching { benchmarkDao.getLatest() }.getOrNull()
    val appInfo = resolveFusionDiagnosticsAppInfo(context)
    val externalLinkedModels = FusionModelCatalog.all(context).filter { it.externallyReferenced }
    val logState = loadDeveloperLogState(context)

    val modelItems = listOf(
        FusionHealthCheckItem(
            id = "selected_model_exists",
            title = "선택된 모델",
            detail = modelSummary.summary,
            status = modelSummary.status,
            actionLabel = if (modelSummary.status == FusionHealthStatus.PASS) null else "모델 라이브러리 열기",
            actionKey = if (modelSummary.status == FusionHealthStatus.PASS) null else "open_model_library"
        ),
        runCatching {
            val format = selectedSpec?.runtimeFormat ?: ModelRuntimeFormat.UNKNOWN
            if (format == ModelRuntimeFormat.UNKNOWN) {
                FusionHealthCheckItem(
                    id = "runtime_format",
                    title = "모델 실행 형식",
                    detail = "모델 실행 형식을 확인할 수 없습니다.",
                    status = FusionHealthStatus.WARNING
                )
            } else {
                FusionHealthCheckItem(
                    id = "runtime_format",
                    title = "모델 실행 형식",
                    detail = "선택된 모델을 확인했습니다. 현재 형식: ${format.name}",
                    status = FusionHealthStatus.PASS
                )
            }
        }.getOrElse {
            FusionHealthCheckItem("runtime_format", "모델 실행 형식", "모델 실행 형식을 확인할 수 없습니다.", FusionHealthStatus.FAIL)
        },
        runCatching {
            val family = selectedSpec?.family ?: ModelFamily.CUSTOM
            val adapter = FusionPromptAdapters.forFamily(family)
            val detail = if (selectedSpec == null) {
                "이 모델 패밀리의 프롬프트 어댑터를 확인해 주세요."
            } else {
                "프롬프트 어댑터를 확인했습니다. 계열: ${adapter.family.name}"
            }
            FusionHealthCheckItem(
                id = "prompt_adapter",
                title = "프롬프트 어댑터",
                detail = detail,
                status = if (selectedSpec == null) FusionHealthStatus.WARNING else FusionHealthStatus.PASS
            )
        }.getOrElse {
            FusionHealthCheckItem("prompt_adapter", "프롬프트 어댑터", "이 모델 패밀리의 프롬프트 어댑터를 확인해 주세요.", FusionHealthStatus.FAIL)
        }
    )

    val settingsItems = listOf(
        runCatching {
            val sane = maxTokens > 0 &&
                topK > 0 &&
                topP in 0f..1f &&
                temperature in 0f..2f &&
                reasoningBudget >= 0
            FusionHealthCheckItem(
                id = "advanced_settings",
                title = "고급 설정",
                detail = if (sane) {
                    "선택된 생성 설정을 확인했습니다. " +
                        FusionRuntimeDiagnosticsFormat.effectiveGenerationSummaryLine(
                            effectiveProfile,
                            webSearchEnabled,
                        )
                } else {
                    "일부 생성 설정 값이 유효하지 않습니다."
                },
                status = if (sane) FusionHealthStatus.PASS else FusionHealthStatus.WARNING,
                actionLabel = if (sane) null else "고급 설정 열기",
                actionKey = if (sane) null else "open_advanced_settings"
            )
        }.getOrElse {
            FusionHealthCheckItem("advanced_settings", "고급 설정", "일부 생성 설정 값이 유효하지 않습니다.", FusionHealthStatus.FAIL, "고급 설정 열기", "open_advanced_settings")
        },
        runCatching {
            buildSettingsBackupJson(context, prefs)
            FusionHealthCheckItem(
                id = "settings_backup",
                title = "설정 백업 JSON",
                detail = "설정 백업 JSON을 생성할 수 있습니다.",
                status = FusionHealthStatus.PASS
            )
        }.getOrElse {
            FusionHealthCheckItem("settings_backup", "설정 백업 JSON", "설정 백업 JSON을 생성할 수 없습니다.", FusionHealthStatus.FAIL)
        }
    )

    val storageItems = listOf(
        runCatching {
            val cacheDir = context.cacheDir
            val filesDir = context.filesDir
            val tempFile = File(cacheDir, "fusion-health-check.tmp")
            tempFile.writeText("ok")
            val readBack = tempFile.readText() == "ok"
            tempFile.delete()
            val ok = filesDir.exists() && cacheDir.exists() && readBack
            FusionHealthCheckItem(
                id = "app_storage",
                title = "앱 저장소",
                detail = if (ok) {
                    "앱 저장소를 확인했습니다. filesDir와 cacheDir에 접근할 수 있습니다."
                } else {
                    "앱 저장소에 접근할 수 없습니다."
                },
                status = if (ok) FusionHealthStatus.PASS else FusionHealthStatus.FAIL
            )
        }.getOrElse {
            FusionHealthCheckItem("app_storage", "앱 저장소", "앱 저장소에 접근할 수 없습니다.", FusionHealthStatus.FAIL)
        },
        runCatching {
            if (externalLinkedModels.isEmpty()) {
                FusionHealthCheckItem(
                    id = "external_links",
                    title = "외부 모델 파일 연결",
                    detail = "확인할 외부 모델 파일 연결이 없습니다.",
                    status = FusionHealthStatus.UNKNOWN
                )
            } else {
                val failed = externalLinkedModels.filterNot { checkExternalModelAccess(context, it) }
                if (failed.isEmpty()) {
                    FusionHealthCheckItem(
                        id = "external_links",
                        title = "외부 모델 파일 연결",
                        detail = "외부 모델 파일 연결을 확인했습니다. ${externalLinkedModels.size}개 연결이 유효합니다.",
                        status = FusionHealthStatus.PASS
                    )
                } else {
                    FusionHealthCheckItem(
                        id = "external_links",
                        title = "외부 모델 파일 연결",
                        detail = "일부 외부 모델 파일에 접근할 수 없습니다.",
                        status = FusionHealthStatus.WARNING,
                        actionLabel = "모델 라이브러리 열기",
                        actionKey = "open_model_library"
                    )
                }
            }
        }.getOrElse {
            FusionHealthCheckItem("external_links", "외부 모델 파일 연결", "일부 외부 모델 파일에 접근할 수 없습니다.", FusionHealthStatus.FAIL, "모델 라이브러리 열기", "open_model_library")
        }
    )

    val memoryItems = listOf(
        runCatching {
            val totalText = formatBytes(memorySnapshot.totalMem)
            val availText = formatBytes(memorySnapshot.availMem)
            val low = memorySnapshot.lowMemory || memorySnapshot.availMem < memorySnapshot.threshold * 2
            FusionHealthCheckItem(
                id = "memory_snapshot",
                title = "메모리 상태",
                detail = "전체 RAM: $totalText · 사용 가능 RAM: $availText · 저메모리 상태: ${if (memorySnapshot.lowMemory) "예" else "아니요"} · 저메모리 모드: ${if (lowMemoryMode) "켜짐" else "꺼짐"}",
                status = if (low) FusionHealthStatus.WARNING else FusionHealthStatus.PASS,
                actionLabel = if (low) "모델 리소스 정리" else null,
                actionKey = if (low) "memory_cleanup" else null
            )
        }.getOrElse {
            FusionHealthCheckItem("memory_snapshot", "메모리 상태", "현재 사용 가능한 메모리가 낮습니다.", FusionHealthStatus.FAIL)
        },
        runCatching {
            val unknown = socInfo.detectedSocVendor.name == "UNKNOWN"
            FusionHealthCheckItem(
                id = "soc_info",
                title = "기기 AP 정보",
                detail = if (unknown) {
                    "기기 AP 정보를 확인할 수 없습니다."
                } else {
                    "기기 AP 정보를 확인했습니다. 현재 AP: ${socInfo.vendorLabel}"
                },
                status = if (unknown) FusionHealthStatus.WARNING else FusionHealthStatus.PASS
            )
        }.getOrElse {
            FusionHealthCheckItem("soc_info", "기기 AP 정보", "기기 AP 정보를 확인할 수 없습니다.", FusionHealthStatus.FAIL)
        }
    )

    val databaseItems = listOf(
        FusionHealthCheckItem(
            id = "room_database",
            title = "Room 데이터베이스",
            detail = if (conversationCount >= 0 && messageCount >= 0 && benchmarkCount >= 0) {
                "데이터베이스에 접근할 수 있습니다. 대화 ${conversationCount}개 · 메시지 ${messageCount}개 · 벤치마크 ${benchmarkCount}개"
            } else {
                "데이터베이스 상태를 확인할 수 없습니다."
            },
            status = if (conversationCount >= 0 && messageCount >= 0 && benchmarkCount >= 0) FusionHealthStatus.PASS else FusionHealthStatus.FAIL
        )
    )

    val permissionItems = listOf(
        runCatching {
            val persistedCount = context.contentResolver.persistedUriPermissions.count { it.isReadPermission }
            val status = when {
                externalLinkedModels.isNotEmpty() && persistedCount == 0 -> FusionHealthStatus.WARNING
                persistedCount > 0 -> FusionHealthStatus.PASS
                else -> FusionHealthStatus.UNKNOWN
            }
            val detail = when {
                externalLinkedModels.isNotEmpty() && persistedCount == 0 -> "외부 모델 파일 읽기 권한을 다시 확인해 주세요."
                persistedCount > 0 -> "외부 파일 읽기 권한을 확인했습니다. 유지된 권한 ${persistedCount}개"
                else -> "현재 확인할 외부 파일 권한이 없습니다."
            }
            FusionHealthCheckItem("file_permissions", "외부 파일 권한", detail, status)
        }.getOrElse {
            FusionHealthCheckItem("file_permissions", "외부 파일 권한", "권한 상태를 확인할 수 없습니다.", FusionHealthStatus.FAIL)
        }
    )

    val benchmarkItems = listOf(
        FusionHealthCheckItem(
            id = "benchmark_history",
            title = "벤치마크 기록",
            detail = when {
                benchmarkCount < 0 -> "벤치마크 기록 상태를 확인할 수 없습니다."
                benchmarkCount == 0 -> "저장된 벤치마크 기록이 없습니다."
                else -> "벤치마크 기록 ${benchmarkCount}개를 확인했습니다. 최신 모델: ${latestBenchmark?.modelName ?: "확인 필요"}"
            },
            status = when {
                benchmarkCount < 0 -> FusionHealthStatus.FAIL
                benchmarkCount == 0 -> FusionHealthStatus.UNKNOWN
                else -> FusionHealthStatus.PASS
            }
        )
    )

    val exportItems = listOf(
        FusionHealthCheckItem(
            id = "chat_export",
            title = "채팅 내보내기",
            detail = when {
                conversationCount < 0 -> "채팅 내보내기 상태를 확인할 수 없습니다."
                conversationCount == 0 -> "내보낼 채팅이 없습니다."
                else -> "채팅 내보내기를 사용할 수 있습니다."
            },
            status = when {
                conversationCount < 0 -> FusionHealthStatus.FAIL
                conversationCount == 0 -> FusionHealthStatus.UNKNOWN
                else -> FusionHealthStatus.PASS
            }
        ),
        FusionHealthCheckItem(
            id = "settings_import_export",
            title = "설정 백업 및 복원",
            detail = "설정 백업 및 복원을 사용할 수 있습니다.",
            status = FusionHealthStatus.PASS
        )
    )

    val developerItems = listOf(
        runCatching {
            when (logState) {
                LogState.OK -> FusionHealthCheckItem(
                    id = "developer_log",
                    title = "개발자 로그",
                    detail = "개발자 로그를 사용할 수 있습니다.",
                    status = FusionHealthStatus.PASS
                )
                LogState.MALFORMED -> FusionHealthCheckItem(
                    id = "developer_log",
                    title = "개발자 로그",
                    detail = "개발자 로그 형식을 다시 확인해 주세요.",
                    status = FusionHealthStatus.WARNING
                )
                LogState.ERROR -> FusionHealthCheckItem(
                    id = "developer_log",
                    title = "개발자 로그",
                    detail = "개발자 로그를 사용할 수 없습니다.",
                    status = FusionHealthStatus.FAIL
                )
            }
        }.getOrElse {
            FusionHealthCheckItem("developer_log", "개발자 로그", "개발자 로그를 사용할 수 없습니다.", FusionHealthStatus.FAIL)
        }
    )

    val groups = listOf(
        FusionHealthCheckGroup("모델", modelItems),
        FusionHealthCheckGroup("설정", settingsItems),
        FusionHealthCheckGroup("저장소", storageItems),
        FusionHealthCheckGroup("메모리", memoryItems),
        FusionHealthCheckGroup("데이터베이스", databaseItems),
        FusionHealthCheckGroup("권한", permissionItems),
        FusionHealthCheckGroup("벤치마크", benchmarkItems),
        FusionHealthCheckGroup("내보내기", exportItems),
        FusionHealthCheckGroup("개발자", developerItems)
    )

    val overallStatus = groups
        .flatMap { it.items }
        .map { it.status }
        .let { statuses ->
            when {
                statuses.any { it == FusionHealthStatus.FAIL } -> FusionHealthStatus.FAIL
                statuses.any { it == FusionHealthStatus.WARNING } -> FusionHealthStatus.WARNING
                statuses.any { it == FusionHealthStatus.PASS } -> FusionHealthStatus.PASS
                else -> FusionHealthStatus.UNKNOWN
            }
        }

    val overallTitle = when (overallStatus) {
        FusionHealthStatus.PASS -> "정상"
        FusionHealthStatus.WARNING -> "주의 필요"
        FusionHealthStatus.FAIL -> "문제 발견"
        FusionHealthStatus.UNKNOWN -> "확인 필요"
    }
    val overallSummary = when (overallStatus) {
        FusionHealthStatus.PASS -> "주요 기능을 사용할 수 있습니다."
        FusionHealthStatus.WARNING -> "일부 항목을 확인해 주세요."
        FusionHealthStatus.FAIL -> "수정이 필요한 항목이 있습니다."
        FusionHealthStatus.UNKNOWN -> "일부 항목을 아직 확인하지 못했습니다."
    }

    val reportText = buildString {
        appendLine("Fusion 상태 점검 보고서")
        appendLine("앱 버전: ${appInfo.versionName} (${appInfo.versionCode})")
        appendLine("기기: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("SoC/AP: ${socInfo.vendorLabel}")
        appendLine("전체 RAM: ${formatBytes(memorySnapshot.totalMem)}")
        appendLine("사용 가능 RAM: ${formatBytes(memorySnapshot.availMem)}")
        appendLine("저메모리 상태: ${if (memorySnapshot.lowMemory) "예" else "아니요"}")
        appendLine("저메모리 모드: ${if (lowMemoryMode) "켜짐" else "꺼짐"}")
        appendLine("선택된 모델: ${selectedModelName.ifBlank { "없음" }}")
        appendLine("모델 경로 상태: ${modelSummary.reportLine}")
        appendLine("가속기: $acceleratorName")
        appendLine("maxTokens: $maxTokens")
        appendLine("temperature: ${formatFloat(temperature)}")
        appendLine("topK: $topK")
        appendLine("topP: ${formatFloat(topP)}")
        appendLine("MTP: ${if (mtpEnabled) "켜짐" else "꺼짐"}")
        appendLine("Reasoning: ${if (reasoningEnabled) "켜짐" else "꺼짐"}")
        appendLine("Reasoning budget: $reasoningBudget")
        appendLine("Web Search: ${if (webSearchEnabled) "켜짐" else "꺼짐"}")
        appendLine("대화 수: ${if (conversationCount >= 0) conversationCount else "확인 실패"}")
        appendLine("메시지 수: ${if (messageCount >= 0) messageCount else "확인 실패"}")
        appendLine("벤치마크 수: ${if (benchmarkCount >= 0) benchmarkCount else "확인 실패"}")
        appendLine()
        appendLine("[점검 결과]")
        groups.forEach { group ->
            appendLine("- ${group.title}")
            group.items.forEach { item ->
                appendLine("  - ${statusLabel(item.status)} · ${item.title}: ${item.detail}")
            }
        }
    }.trimEnd()

    return FusionHealthSnapshot(
        groups = groups,
        overallStatus = overallStatus,
        overallTitle = overallTitle,
        overallSummary = overallSummary,
        reportText = reportText
    )
}

/** Health-specific presentation of the AUTHORITATIVE readiness truth. This
 * mapper never re-derives file/catalog truth on its own: runnable truth comes
 * from [resolveLocalModelReadinessTruth], and external linked models keep
 * their persisted-URI access checks supplied by the caller. */
internal data class FusionSelectedModelHealthPresentation(
    val status: FusionHealthStatus,
    val summary: String,
    val reportLine: String,
)

internal fun mapSelectedModelReadinessForHealth(
    readiness: LocalModelReadinessTruth,
    externalLinkedSelected: Boolean,
    externalAccessible: Boolean,
): FusionSelectedModelHealthPresentation = when {
    readiness.isRunnable -> FusionSelectedModelHealthPresentation(
        status = FusionHealthStatus.PASS,
        summary = "선택된 모델을 확인했습니다.",
        reportLine = if (readiness.actualManagedRunnablePath != null) {
            "로컬 파일 접근 가능"
        } else {
            "기본 모델 사용 가능"
        },
    )
    externalLinkedSelected && externalAccessible -> FusionSelectedModelHealthPresentation(
        status = FusionHealthStatus.PASS,
        summary = "선택된 모델을 확인했습니다.",
        reportLine = "외부 파일 연결 접근 가능",
    )
    externalLinkedSelected -> FusionSelectedModelHealthPresentation(
        status = FusionHealthStatus.FAIL,
        summary = "선택된 모델 파일에 접근할 수 없습니다. 모델 파일을 다시 연결해 주세요.",
        reportLine = "외부 파일 연결 접근 실패",
    )
    readiness.selectedManagedPath != null -> FusionSelectedModelHealthPresentation(
        status = FusionHealthStatus.FAIL,
        summary = "선택된 모델 파일에 접근할 수 없습니다. 모델 파일을 다시 연결해 주세요.",
        reportLine = "로컬 파일 접근 실패",
    )
    else -> FusionSelectedModelHealthPresentation(
        status = FusionHealthStatus.WARNING,
        summary = "선택된 모델을 사용할 수 없습니다. 모델 라이브러리에서 모델을 연결해 주세요.",
        reportLine = "모델 파일 연결 필요",
    )
}

private fun checkExternalModelAccess(context: Context, spec: FusionModelSpec): Boolean {
    val uriString = spec.uriString ?: return false
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
    val persisted = context.contentResolver.persistedUriPermissions.any { perm ->
        perm.uri == uri && perm.isReadPermission
    }
    if (!persisted) return false
    return runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length >= -1L
        } ?: run {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { true } ?: false
        }
    }.getOrDefault(false)
}

private enum class LogState { OK, MALFORMED, ERROR }

private fun loadDeveloperLogState(context: Context): LogState {
    return runCatching {
        val prefs = context.getSharedPreferences("fusion_developer_log", Context.MODE_PRIVATE)
        val raw = prefs.getString("events", null)
        if (raw.isNullOrBlank()) {
            DeveloperLogStore.load(context)
            LogState.OK
        } else {
            org.json.JSONArray(raw)
            DeveloperLogStore.load(context)
            LogState.OK
        }
    }.getOrElse {
        if (it is org.json.JSONException) LogState.MALFORMED else LogState.ERROR
    }
}

private fun statusLabel(status: FusionHealthStatus): String = when (status) {
    FusionHealthStatus.PASS -> "정상"
    FusionHealthStatus.WARNING -> "주의"
    FusionHealthStatus.FAIL -> "문제"
    FusionHealthStatus.UNKNOWN -> "확인 필요"
}

@Composable
private fun statusColor(status: FusionHealthStatus): Color = when (status) {
    FusionHealthStatus.PASS -> HealthAccentBlue
    FusionHealthStatus.WARNING -> HealthWarning
    FusionHealthStatus.FAIL -> HealthError
    FusionHealthStatus.UNKNOWN -> HealthTextSecondary
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 GB"
    val gb = bytes / (1024f * 1024f * 1024f)
    return String.format(Locale.US, "%.2f GB", gb)
}

private fun formatFloat(value: Float): String {
    return String.format(Locale.US, "%.2f", value)
}
