package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.data.AppDatabase
import kotlinx.coroutines.CancellationException

@Composable
internal fun ResearchRefreshQueueSurface(
    database: AppDatabase,
    includeArchived: Boolean,
    requestedTarget: Pair<Long, Long>?,
    onBack: () -> Unit,
    onPrepareRefresh: (ResearchRefreshQueueCandidate) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember(database, context) {
        ResearchRefreshQueueRepository(RoomResearchRefreshQueueDataSource(database, context))
    }
    val queueLoad by produceState<ResearchRefreshQueueLoadResult?>(null, repository, includeArchived, requestedTarget) {
        value = try {
            repository.load(includeArchived = includeArchived, requestedTarget = requestedTarget)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ResearchRefreshQueueLoadResult(emptyList(), ResearchRefreshQueueCoverage.FAILED)
        }
    }
    val candidates = queueLoad?.candidates
    val orderedCandidates = remember(candidates, requestedTarget) {
        candidates.orEmpty().sortedByDescending { candidate ->
            candidate.sourceConversationId == requestedTarget?.first &&
                candidate.sourceMessageId == requestedTarget.second
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("다시 확인할 Research", style = MaterialTheme.typography.titleLarge)
        Text(
            "오래된 Research 결과를 다시 살펴볼 수 있어요. 다시 확인 대상으로 표시됐다고 해서 내용이 틀렸다는 뜻은 아니에요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (queueLoad?.coverage == ResearchRefreshQueueCoverage.INCOMPLETE) {
            Text(
                "검사 범위가 제한되어 일부 오래된 원본을 확인하지 못했어요. 아래 목록이 불완전할 수 있어요.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (queueLoad?.visibleLimitReached == true) {
            Text(
                "다시 확인할 후보가 ${ResearchRefreshQueuePolicy.MAX_CANDIDATES}개보다 많아 일부만 표시했어요. 선택한 원본은 별도로 확인합니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        when (candidates) {
            null -> Text("불러오는 중…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                if (queueLoad?.coverage == ResearchRefreshQueueCoverage.FAILED) {
                    Text("Research 원본을 불러오지 못했어요. 다시 시도해 주세요.", color = MaterialTheme.colorScheme.error)
                } else when (queueLoad?.requestedTargetStatus) {
                    ResearchRefreshQueueTargetStatus.INELIGIBLE -> Text(
                        "선택한 Research 원본을 다시 확인했지만 삭제되었거나 현재 권한·응답·새로고침 조건에 맞지 않아요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ResearchRefreshQueueTargetStatus.NOT_EXAMINED -> Text(
                        "선택한 원본을 제한된 검사 범위에서 확인하지 못했어요. 대기열에 없다고 단정할 수 없습니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ResearchRefreshQueueTargetStatus.FAILED -> Text(
                        "선택한 원본의 최신 상태를 확인하지 못했어요. 다시 시도해 주세요.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Unit
                }
                if (queueLoad?.coverage != ResearchRefreshQueueCoverage.FAILED && orderedCandidates.isEmpty()) {
                    Text(
                        when {
                            queueLoad?.coverageIncomplete == true -> "검사한 범위에서 다시 확인할 Research를 찾지 못했어요."
                            queueLoad?.requestedTargetStatus == ResearchRefreshQueueTargetStatus.NOT_EXAMINED ->
                                "선택한 원본을 확인하지 못해 대기열이 비어 있는지 판단할 수 없어요."
                            else -> "다시 확인할 Research가 없어요."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (queueLoad?.coverage != ResearchRefreshQueueCoverage.FAILED) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(orderedCandidates, key = { it.stableId }) { candidate ->
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(candidate.conversationTitle, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "마지막 조사 후 ${candidate.ageDays}일 · 다시 확인할 Research",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = { onPrepareRefresh(candidate) }) {
                                    Text("현재 정보로 다시 확인")
                                }
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick = onBack) { Text("뒤로") }
    }
}
