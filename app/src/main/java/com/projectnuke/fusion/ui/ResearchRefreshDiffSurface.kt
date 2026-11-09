package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.research.ResearchRefreshDiffPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ResearchRefreshDiffConversationEntry(
    database: AppDatabase,
    conversationId: Long,
    includeArchived: Boolean,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember(database, context, includeArchived) {
        ResearchRefreshDiffRepository(context, database) { includeArchived }
    }
    val snapshot by produceState<ResearchRefreshDiffSnapshot?>(null, repository, conversationId) {
        value = try {
            repository.loadFromRefreshedConversation(conversationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    snapshot?.let { exact ->
        ResearchRefreshDiffAction(
            repository = repository,
            sourceConversationId = exact.sourceConversationId,
            sourceMessageId = exact.sourceMessageId,
            refreshedConversationId = exact.refreshedConversationId,
            initiallyAvailable = true,
            label = "변화 비교",
        )
    }
}

@Composable
internal fun ResearchRefreshDiffAction(
    repository: ResearchRefreshDiffRepository,
    sourceConversationId: Long,
    sourceMessageId: Long,
    refreshedConversationId: Long,
    initiallyAvailable: Boolean,
    label: String = "변화 비교",
) {
    var show by remember(sourceConversationId, sourceMessageId, refreshedConversationId) { mutableStateOf(false) }
    val state by produceState<ResearchRefreshDiffLoad>(ResearchRefreshDiffLoad.Idle, repository, show,
        sourceConversationId, sourceMessageId, refreshedConversationId) {
        if (!show) {
            value = ResearchRefreshDiffLoad.Idle
        } else {
            value = try {
                withContext(Dispatchers.IO) {
                    repository.load(sourceConversationId, sourceMessageId, refreshedConversationId)
                        ?.let(ResearchRefreshDiffLoad::Ready) ?: ResearchRefreshDiffLoad.Stale
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ResearchRefreshDiffLoad.Stale
            }
        }
    }
    if (initiallyAvailable) {
        TextButton(onClick = { show = true }, modifier = Modifier.heightIn(min = FusionSpacing.control)) {
            Text(label)
        }
    }
    if (show) {
        ResearchRefreshDiffSurface(
            snapshot = (state as? ResearchRefreshDiffLoad.Ready)?.snapshot,
            loading = state == ResearchRefreshDiffLoad.Idle,
            stale = state == ResearchRefreshDiffLoad.Stale,
            onDismiss = { show = false },
        )
    }
}

@Composable
internal fun ResearchRefreshDiffSurface(
    snapshot: ResearchRefreshDiffSnapshot?,
    loading: Boolean,
    stale: Boolean,
    onDismiss: () -> Unit,
) {
    var showPrevious by remember(snapshot?.refreshedMessageId) { mutableStateOf(false) }
    var showCurrent by remember(snapshot?.refreshedMessageId) { mutableStateOf(false) }
    FusionReadingDialog(
        onDismissRequest = onDismiss,
        title = { Text("Research 변화 비교", maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FusionSpacing.section)) {
                when {
                    loading -> Text("정확한 Research 관계를 확인하는 중…")
                    stale -> Text("비교할 Research 결과를 현재 열 수 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    snapshot == null -> Text("비교할 Research 결과를 찾지 못했습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                        Text("이전 결과와 이번 결과의 표현을 비교합니다. 차이는 사실 판정이 아닙니다.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val result = snapshot.result
                        if (!result.hasChanges) {
                            Text("눈에 띄는 변화 후보를 찾지 못했어요.")
                            TextButton(onClick = { showPrevious = !showPrevious }, modifier = Modifier.heightIn(min = FusionSpacing.control)) {
                                Text(if (showPrevious) "이전 Research 접기" else "이전 Research 읽기 · ${snapshot.sourceConversationTitle}")
                            }
                            if (showPrevious) Text(snapshot.previousVisibleAnswer, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { showCurrent = !showCurrent }, modifier = Modifier.heightIn(min = FusionSpacing.control)) {
                                Text(if (showCurrent) "현재 Research 접기" else "현재 Research 읽기 · ${snapshot.refreshedConversationTitle}")
                            }
                            if (showCurrent) Text(snapshot.currentVisibleAnswer, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            DiffSection("새로 언급된 내용", result.newlyMentioned)
                            DiffSection("이번 결과에서 찾기 어려운 이전 내용", result.notFoundPrevious)
                            if (result.numericCandidates.isNotEmpty()) {
                                Text("수치·날짜 변화 후보", style = MaterialTheme.typography.titleSmall)
                                result.numericCandidates.forEach { item ->
                                    Text("이전: ${item.previous}\n현재: ${item.current}", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (result.sources.added.isNotEmpty() || result.sources.notSeenThisTime.isNotEmpty() || result.sources.continued.isNotEmpty()) {
                                Text("출처 변화", style = MaterialTheme.typography.titleSmall)
                                DiffSection("새로 포함된 출처", result.sources.added)
                                DiffSection("이번 결과에서 사용되지 않은 이전 출처", result.sources.notSeenThisTime)
                                DiffSection("계속 사용된 출처", result.sources.continued)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = FusionSpacing.control)) { Text("닫기") } },
    )
}

@Composable
private fun DiffSection(title: String, values: List<String>) {
    if (values.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FusionSpacing.row)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        values.take(ResearchRefreshDiffPolicy.MAX_DIFF_ITEMS_PER_SECTION).forEach { value ->
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private sealed interface ResearchRefreshDiffLoad {
    data object Idle : ResearchRefreshDiffLoad
    data class Ready(val snapshot: ResearchRefreshDiffSnapshot) : ResearchRefreshDiffLoad
    data object Stale : ResearchRefreshDiffLoad
}
