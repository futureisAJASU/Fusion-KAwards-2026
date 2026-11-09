package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.knowledge.KnowledgeHygieneRepository
import com.projectnuke.fusion.knowledge.KnowledgeHygieneReview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun KnowledgeHygieneSurface(
    review: KnowledgeHygieneReview?,
    loading: Boolean,
    error: Boolean,
    stale: Boolean,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    FusionReadingDialog(
        onDismissRequest = onDismiss,
        title = { Text("정리 후보", maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FusionSpacing.section)) {
                Text("내용이 많이 겹치는 저장 지식을 기기 안에서 찾아봅니다.",
                    style = MaterialTheme.typography.bodyMedium)
                if (loading) Text("저장 지식을 확인하는 중…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error) Text("정리 후보를 불러오지 못했습니다.", color = MaterialTheme.colorScheme.error)
                if (stale) Text("선택한 항목을 더 이상 열 수 없습니다. 목록을 다시 확인해 주세요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                review?.let { data ->
                    Text("현재 항목 출처", style = MaterialTheme.typography.titleSmall)
                    data.provenanceObservations.forEach { observation ->
                        Text(observation, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("비슷한 항목", style = MaterialTheme.typography.titleSmall)
                    if (data.candidates.isEmpty()) {
                        Text("정리할 만큼 비슷한 항목을 찾지 못했어요.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        data.candidates.forEach { candidate ->
                            Column(Modifier.fillMaxWidth().heightIn(min = FusionSpacing.control)
                                .padding(vertical = FusionSpacing.row),
                                verticalArrangement = Arrangement.spacedBy(FusionSpacing.row)) {
                                Text(candidate.title, style = MaterialTheme.typography.bodyLarge)
                                Text(candidate.label, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { onOpen(candidate.id) }, modifier = Modifier.heightIn(min = FusionSpacing.control)) {
                                        Text("열기")
                                    }
                                }
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
internal fun KnowledgeHygieneDialog(
    database: AppDatabase,
    sourceId: String,
    includeArchived: () -> Boolean,
    onOpenCandidate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val repository = remember(database) {
        KnowledgeHygieneRepository(
            context = context,
            database = database,
            includeArchived = includeArchived,
        )
    }
    val state by produceState<KnowledgeHygieneLoadState>(KnowledgeHygieneLoadState.Loading, repository, sourceId) {
        value = try {
            repository.load(sourceId)?.let(KnowledgeHygieneLoadState::Ready) ?: KnowledgeHygieneLoadState.Missing
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            KnowledgeHygieneLoadState.Failed
        }
    }
    var stale by remember(sourceId) { mutableStateOf(false) }
    KnowledgeHygieneSurface(
        review = (state as? KnowledgeHygieneLoadState.Ready)?.review,
        loading = state == KnowledgeHygieneLoadState.Loading,
        error = state == KnowledgeHygieneLoadState.Failed || state == KnowledgeHygieneLoadState.Missing,
        stale = stale,
        onOpen = { candidateId ->
            scope.launch {
                val candidate = withContext(Dispatchers.IO) {
                    repository.revalidatePair(sourceId, candidateId)
                }
                if (candidate == null) stale = true else onOpenCandidate(candidate.id)
            }
        },
        onDismiss = onDismiss,
    )
}

private sealed interface KnowledgeHygieneLoadState {
    data object Loading : KnowledgeHygieneLoadState
    data class Ready(val review: KnowledgeHygieneReview) : KnowledgeHygieneLoadState
    data object Missing : KnowledgeHygieneLoadState
    data object Failed : KnowledgeHygieneLoadState
}
