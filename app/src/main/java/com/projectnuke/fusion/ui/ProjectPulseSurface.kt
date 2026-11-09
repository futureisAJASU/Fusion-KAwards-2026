package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.projects.ProjectPulseItem
import com.projectnuke.fusion.projects.ProjectPulseSection
import com.projectnuke.fusion.projects.ProjectPulseSnapshot
import com.projectnuke.fusion.projects.ProjectReviewSessionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProjectPulseDialog(
    database: AppDatabase,
    projectId: Long,
    projectName: String,
    includeArchived: () -> Boolean,
    onOpenTarget: (ProjectPulseItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember(database, context, projectId) {
        projectPulseRepository(context, database, includeArchived)
    }
    val snapshot by produceState<ProjectPulseLoadState>(ProjectPulseLoadState.Loading, repository, projectId) {
        value = try {
            repository.load(projectId)?.let(ProjectPulseLoadState::Ready) ?: ProjectPulseLoadState.Failed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProjectPulseLoadState.Failed
        }
    }
    ProjectPulseSurface(
        projectId = projectId,
        projectName = projectName,
        snapshot = (snapshot as? ProjectPulseLoadState.Ready)?.snapshot,
        loading = snapshot == ProjectPulseLoadState.Loading,
        error = snapshot == ProjectPulseLoadState.Failed,
        repository = repository,
        onOpenTarget = onOpenTarget,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun ProjectPulseSurface(
    projectId: Long,
    projectName: String,
    snapshot: ProjectPulseSnapshot?,
    loading: Boolean,
    error: Boolean,
    repository: ProjectPulseRepository?,
    onOpenTarget: (ProjectPulseItem) -> Unit,
    onDismiss: () -> Unit,
) {
    var stale by remember(projectId) { mutableStateOf(false) }
    var reviewSessionOpen by remember(projectId) { mutableStateOf(false) }
    var reviewIndex by remember(projectId) { mutableStateOf(0) }
    var targetUnavailable by remember(projectId) { mutableStateOf(false) }
    var openingReviewTarget by remember(projectId) { mutableStateOf(false) }
    // Request generations belong to the surviving composable owner, not the
    // current project key. Keeping this monotonic prevents an A -> B -> A
    // switch from reusing a generation owned by a stale request from A.
    var reviewOperationGeneration by remember { mutableLongStateOf(0L) }
    var reviewSessionItems by remember(projectId) {
        mutableStateOf(emptyList<ProjectReviewSessionPolicy.ReviewItem>())
    }
    val scope = rememberCoroutineScope()
    val currentProjectId by rememberUpdatedState(projectId)
    val availableReviewItems = remember(snapshot) {
        snapshot?.let(ProjectReviewSessionPolicy::buildItems).orEmpty()
    }
    val dismissPulse = {
        reviewOperationGeneration++
        openingReviewTarget = false
        reviewSessionOpen = false
        reviewIndex = 0
        targetUnavailable = false
        reviewSessionItems = emptyList()
        onDismiss()
    }

    if (reviewSessionOpen) {
        ProjectReviewSessionSurface(
            items = reviewSessionItems,
            currentIndex = reviewIndex.coerceIn(0, reviewSessionItems.size),
            targetUnavailable = targetUnavailable,
            openingTarget = openingReviewTarget,
            onOpenItem = { item ->
                if (openingReviewTarget) return@ProjectReviewSessionSurface
                val request = ProjectReviewOpenRequestToken(projectId, reviewOperationGeneration, item.target)
                openingReviewTarget = true
                scope.launch {
                    try {
                        val canonical = repository?.resolveCurrentReviewTarget(item.target)
                        if (!ProjectReviewOpenRequestPolicy.isCurrent(
                                request = request,
                                currentProjectId = currentProjectId,
                                currentGeneration = reviewOperationGeneration,
                                reviewSessionOpen = reviewSessionOpen,
                                currentTarget = reviewSessionItems.getOrNull(reviewIndex)?.target,
                            )
                        ) return@launch
                        if (canonical == null) {
                            targetUnavailable = true
                        } else {
                            targetUnavailable = false
                            reviewSessionOpen = false
                            onOpenTarget(canonical)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (ProjectReviewOpenRequestPolicy.isCurrent(
                                request = request,
                                currentProjectId = currentProjectId,
                                currentGeneration = reviewOperationGeneration,
                                reviewSessionOpen = reviewSessionOpen,
                                currentTarget = reviewSessionItems.getOrNull(reviewIndex)?.target,
                            )
                        ) targetUnavailable = true
                    } finally {
                        if (request.projectId == currentProjectId && request.generation == reviewOperationGeneration) {
                            openingReviewTarget = false
                        }
                    }
                }
            },
            onSkip = {
                reviewOperationGeneration++
                openingReviewTarget = false
                reviewIndex = ProjectReviewSessionPolicy.advance(reviewIndex, reviewSessionItems.size)
                targetUnavailable = false
            },
            onNext = {
                reviewOperationGeneration++
                openingReviewTarget = false
                reviewIndex = ProjectReviewSessionPolicy.advance(reviewIndex, reviewSessionItems.size)
                targetUnavailable = false
            },
            onFinish = {
                reviewOperationGeneration++
                openingReviewTarget = false
                reviewSessionOpen = false
                reviewIndex = 0
                targetUnavailable = false
                reviewSessionItems = emptyList()
            },
            onDismiss = {
                reviewOperationGeneration++
                openingReviewTarget = false
                reviewSessionOpen = false
                reviewIndex = 0
                targetUnavailable = false
                reviewSessionItems = emptyList()
            },
        )
    }

    FusionReadingDialog(
        onDismissRequest = dismissPulse,
        title = { Text("Project Pulse", maxLines = 1) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FusionSpacing.section)) {
                Text(projectName, style = MaterialTheme.typography.titleSmall)
                if (snapshot?.researchQueueCoverageIncomplete == true) {
                    Text(
                        "Research 원본 일부를 확인하지 못했어요. 표시된 목록이 불완전할 수 있어요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    loading -> Text("프로젝트 상태를 확인하는 중…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error -> Text("프로젝트 상태를 불러오지 못했어요.", color = MaterialTheme.colorScheme.error)
                    stale -> Text("선택한 항목이 바뀌었거나 더 이상 열 수 없어요. 다시 확인해 주세요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    snapshot == null -> Text("프로젝트 상태를 확인할 수 없어요.", color = MaterialTheme.colorScheme.error)
                    snapshot.sections.isEmpty() -> Text("지금 표시할 프로젝트 상태가 없어요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> snapshot.sections.forEachIndexed { index, section ->
                        PulseSection(section, snapshot.projectId, repository, onOpenTarget) { stale = true }
                        if (index != snapshot.sections.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                if (!loading && !error && !stale && availableReviewItems.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            reviewOperationGeneration++
                            openingReviewTarget = false
                            targetUnavailable = false
                            reviewSessionItems = availableReviewItems
                            reviewIndex = 0
                            reviewSessionOpen = true
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = FusionSpacing.control),
                    ) { Text("검토 시작") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = dismissPulse, modifier = Modifier.heightIn(min = FusionSpacing.control)) { Text("닫기") }
        },
    )
}

@Composable
private fun PulseSection(
    section: ProjectPulseSection,
    projectId: Long,
    repository: ProjectPulseRepository?,
    onOpenTarget: (ProjectPulseItem) -> Unit,
    onStale: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(FusionSpacing.row)) {
        Text(section.title, style = MaterialTheme.typography.titleSmall)
        section.items.forEach { item ->
            val (title, subtitle) = pulseRowText(item)
            TextButton(
                onClick = {
                    scope.launch {
                        val current = itemProjectId(item) == projectId && repository?.revalidate(item) == true
                        if (current) onOpenTarget(item) else onStale()
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = FusionSpacing.control),
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = FusionSpacing.row),
                    verticalArrangement = Arrangement.spacedBy(FusionSpacing.row)) {
                    Text(title, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
                    Text(subtitle, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun itemProjectId(item: ProjectPulseItem): Long = when (item) {
    is ProjectPulseItem.WorkPlan -> item.projectId
    is ProjectPulseItem.Task -> item.projectId
    is ProjectPulseItem.Decision -> item.projectId
    is ProjectPulseItem.Research -> item.projectId
    is ProjectPulseItem.ReviewCard -> item.projectId
}

private fun pulseRowText(item: ProjectPulseItem): Pair<String, String> = when (item) {
    is ProjectPulseItem.WorkPlan -> item.title to listOfNotNull(
        item.stepTitle?.let { "현재 단계 · $it" }, item.stepState?.let(::stepStateLabel),
    ).joinToString(" · ").ifBlank { "진행 중인 작업 계획" }
    is ProjectPulseItem.Task -> item.title to if (item.dueAt != null) "알림 예정 시간이 지났어요" else "열린 할 일"
    is ProjectPulseItem.Decision -> item.title to "현재 결정"
    is ProjectPulseItem.Research -> item.title to "다시 확인할 Research"
    is ProjectPulseItem.ReviewCard -> item.title to "복습 카드"
}

private fun stepStateLabel(state: String): String = when (state) {
    "REVIEW" -> "검토 필요"
    "FAILED" -> "실패한 단계"
    "RUNNING" -> "진행 중"
    "READY" -> "실행 준비"
    "COMPLETED" -> "완료"
    "SKIPPED" -> "건너뜀"
    else -> state
}

private sealed interface ProjectPulseLoadState {
    data object Loading : ProjectPulseLoadState
    data class Ready(val snapshot: ProjectPulseSnapshot) : ProjectPulseLoadState
    data object Failed : ProjectPulseLoadState
}
