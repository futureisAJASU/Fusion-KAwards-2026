package com.projectnuke.fusion.workspace

import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.ui.ResearchRefreshQueueRepository
import com.projectnuke.fusion.ui.RoomResearchRefreshQueueDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

class WorkspaceAttentionRepository(
    private val database: AppDatabase,
    context: android.content.Context,
) {
    private val researchRefreshQueueRepository = ResearchRefreshQueueRepository(
        RoomResearchRefreshQueueDataSource(database, context.applicationContext),
    )

    suspend fun load(
        now: Long = System.currentTimeMillis(),
        includeArchived: Boolean = false,
    ): WorkspaceAttentionSnapshot =
        withContext(Dispatchers.IO) {
            try {
                val workPlanItems = loadWorkPlanAttention()
                val taskItems = loadTaskAttention(now)
                val reviewItems = loadReviewCardAttention(now)
                val inboxResults = loadInboxAttention()
                val refreshResult = researchRefreshQueueRepository.load(now, includeArchived)
                val refreshItems = WorkspaceAttentionPolicy.selectResearchRefreshAttention(refreshResult.candidates)

                val combined = workPlanItems + taskItems + reviewItems + inboxResults + refreshItems
                val items = combined.distinctBy { it.stableId }
                    .sortedWith(
                        compareBy<WorkspaceAttentionItem> { it.priority }
                            .thenByDescending { it.updatedAt }
                            .thenBy { it.stableId }
                    )
                    .take(WorkspaceAttentionPolicy.MAX_TOTAL_ATTENTION)
                WorkspaceAttentionSnapshot(
                    items = items,
                    researchQueueCoverageIncomplete = refreshResult.coverageIncomplete,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }

    private suspend fun loadWorkPlanAttention(): List<WorkspaceAttentionItem> {
        val projects = database.projectDao().getRecentProjectsBounded(256)
        val steps = mutableListOf<WorkspaceAttentionPolicy.PlanStepRow>()
        projects.forEach { project ->
            val activePlan = database.projectWorkPlanDao().getActivePlan(project.id)
            if (activePlan != null) {
                database.projectWorkPlanDao().getSteps(activePlan.id).forEach { step ->
                    steps.add(
                        WorkspaceAttentionPolicy.PlanStepRow(
                            planId = activePlan.id,
                            stepId = step.id,
                            state = step.state,
                            title = step.title,
                            projectId = project.id,
                            updatedAt = step.updatedAt,
                        )
                    )
                }
            }
        }
        return WorkspaceAttentionPolicy.selectWorkPlanAttention(steps)
            .map {
                WorkspaceAttentionItem(
                    stableId = "plan:${it.planId}:step:${it.stepId}",
                    kind = it.kind,
                    title = it.title,
                    subtitle = it.subtitle,
                    projectId = it.projectId.takeIf { pid -> pid > 0L },
                    targetId = "${it.planId}:${it.stepId}",
                    priority = it.priority,
                    updatedAt = it.updatedAt,
                )
            }
    }

    private suspend fun loadTaskAttention(now: Long): List<WorkspaceAttentionItem> {
        val projects = database.projectDao().getRecentProjectsBounded(256)
        val tasks = mutableListOf<com.projectnuke.fusion.data.ProjectTaskEntity>()
        projects.forEach { project ->
            tasks.addAll(database.projectTaskDao().getOpenForProjectBounded(project.id, 32))
        }
        return WorkspaceAttentionPolicy.selectTaskAttention(tasks, now)
            .map { it.item }
    }

    private suspend fun loadReviewCardAttention(now: Long): List<WorkspaceAttentionItem> {
        val cards = try {
            database.studyReviewCardDao().observePersonal(WorkspaceAttentionPolicy.MAX_PER_KIND)
                .firstOrNull() ?: emptyList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        return WorkspaceAttentionPolicy.selectReviewCardAttention(cards.filter { it.dueAt <= now }, now)
            .map { it.item }
    }

    private suspend fun loadInboxAttention(): List<WorkspaceAttentionItem> {
        val items = database.inboxItemDao().getLatestBounded(WorkspaceAttentionPolicy.MAX_TOTAL_ATTENTION)
        return WorkspaceAttentionPolicy.selectInboxAttention(items)
            .map { it.item }
    }

}

data class WorkspaceAttentionSnapshot(
    val items: List<WorkspaceAttentionItem>,
    val researchQueueCoverageIncomplete: Boolean,
)
