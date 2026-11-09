package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.workspace.WorkspaceAttentionItem
import com.projectnuke.fusion.workspace.WorkspaceAttentionKind
import com.projectnuke.fusion.workspace.WorkspaceAttentionRepository
import com.projectnuke.fusion.workspace.WorkspaceAttentionSnapshot
import com.projectnuke.fusion.workspace.WorkspaceActionTargetPolicy
import com.projectnuke.fusion.workspace.WorkspaceTaskTarget
import com.projectnuke.fusion.workspace.WorkspaceWorkPlanTarget
import kotlinx.coroutines.launch

@Composable
internal fun WorkspaceActionCenterSurface(
    database: com.projectnuke.fusion.data.AppDatabase,
    onBack: () -> Unit,
    onOpenProjectPlan: (WorkspaceWorkPlanTarget) -> Unit,
    onOpenTask: (WorkspaceTaskTarget) -> Unit,
    onOpenReviewCard: (String) -> Unit,
    onOpenInbox: () -> Unit,
    onOpenResearchRefresh: (Long, Long) -> Unit,
    includeArchived: Boolean,
) {
    val context = LocalContext.current
    val repository = remember(database, context) { WorkspaceAttentionRepository(database, context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<WorkspaceAttentionSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }

    androidx.compose.runtime.LaunchedEffect(repository, includeArchived) {
        try {
            loading = true
            snapshot = repository.load(includeArchived = includeArchived)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            snapshot = WorkspaceAttentionSnapshot(emptyList(), researchQueueCoverageIncomplete = true)
        } finally {
            loading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("뒤로") }
            Spacer(modifier = Modifier.width(12.dp))
            Text("지금 할 일", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("불러오는 중...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            if (snapshot?.researchQueueCoverageIncomplete == true) {
                Text(
                    "Research 검사 범위가 제한되어 일부 원본을 확인하지 못했어요.",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (snapshot?.items.orEmpty().isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (snapshot?.researchQueueCoverageIncomplete == true)
                            "일부 Research 자료를 확인하지 못해 목록이 불완전할 수 있어요."
                        else "지금 확인할 항목이 없어요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(snapshot?.items.orEmpty(), key = { it.stableId }) { item ->
                    DrawerActionRow(
                        title = item.title,
                        subtitle = item.subtitle,
                        leading = when (item.kind) {
                            WorkspaceAttentionKind.WORK_PLAN_REVIEW -> "W"
                            WorkspaceAttentionKind.WORK_PLAN_FAILED -> "W"
                            WorkspaceAttentionKind.OPEN_TASK -> "T"
                            WorkspaceAttentionKind.DUE_REVIEW_CARD -> "C"
                            WorkspaceAttentionKind.INBOX_ITEM -> "I"
                            WorkspaceAttentionKind.RESEARCH_REFRESH -> "R"
                        },
                        onClick = {
                            when (item.kind) {
                                WorkspaceAttentionKind.WORK_PLAN_REVIEW,
                                WorkspaceAttentionKind.WORK_PLAN_FAILED -> {
                                    WorkspaceActionTargetPolicy.workPlan(item)?.let(onOpenProjectPlan)
                                }
                                WorkspaceAttentionKind.OPEN_TASK -> {
                                    WorkspaceActionTargetPolicy.task(item)?.let(onOpenTask)
                                }
                                WorkspaceAttentionKind.DUE_REVIEW_CARD -> {
                                    onOpenReviewCard(item.targetId)
                                }
                                WorkspaceAttentionKind.INBOX_ITEM -> {
                                    onOpenInbox()
                                }
                                WorkspaceAttentionKind.RESEARCH_REFRESH -> {
                                    val parts = item.targetId.split(":")
                                    if (parts.size >= 2) {
                                        val conversationId = parts[0].toLongOrNull()
                                        val messageId = parts[1].toLongOrNull()
                                        if (conversationId != null && messageId != null) onOpenResearchRefresh(conversationId, messageId)
                                    }
                                }
                            }
                        },
                    )
                }
            }
            }
        }
    }
}
