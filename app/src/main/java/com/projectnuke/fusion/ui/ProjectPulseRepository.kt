package com.projectnuke.fusion.ui

import android.content.Context
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.projects.ProjectPulseItem
import com.projectnuke.fusion.projects.ProjectPulsePolicy
import com.projectnuke.fusion.projects.ProjectPulseSnapshot
import com.projectnuke.fusion.projects.ProjectReviewSessionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface ProjectPulseDataSource {
    suspend fun project(id: Long): com.projectnuke.fusion.data.ProjectEntity?
    suspend fun activePlan(projectId: Long): com.projectnuke.fusion.data.ProjectWorkPlanEntity?
    suspend fun planSteps(planId: String): List<com.projectnuke.fusion.data.ProjectWorkPlanStepEntity>
    suspend fun openTasks(projectId: Long, now: Long, limit: Int): List<com.projectnuke.fusion.data.ProjectTaskEntity>
    suspend fun activeDecisions(projectId: Long, limit: Int): List<com.projectnuke.fusion.data.ProjectDecisionEntity>
    suspend fun researchCandidates(now: Long, projectId: Long): ResearchRefreshQueueLoadResult
    suspend fun projectForConversation(conversationId: Long): Long?
    suspend fun reviewCards(projectId: Long, limit: Int): List<com.projectnuke.fusion.data.StudyReviewCardEntity>
    suspend fun current(item: ProjectPulseItem, now: Long): Boolean
    suspend fun resolveCurrentReviewTarget(target: ProjectReviewSessionPolicy.ProjectReviewTarget, now: Long): ProjectPulseItem? = null
}

internal class RoomProjectPulseDataSource(
    private val context: Context,
    private val database: AppDatabase,
    private val includeArchived: () -> Boolean,
) : ProjectPulseDataSource {
    private val refreshQueue by lazy {
        ResearchRefreshQueueRepository(RoomResearchRefreshQueueDataSource(database, context))
    }
    override suspend fun project(id: Long) = database.projectDao().getProjectById(id)
    override suspend fun activePlan(projectId: Long) = database.projectWorkPlanDao().getActivePlan(projectId)
    override suspend fun planSteps(planId: String) = database.projectWorkPlanDao().getSteps(planId)
    override suspend fun openTasks(projectId: Long, now: Long, limit: Int) = database.projectTaskDao().getOpenForProjectPulseBounded(projectId, now, limit)
    override suspend fun activeDecisions(projectId: Long, limit: Int) = database.projectDecisionDao().getActiveDecisions(projectId, limit)
    override suspend fun researchCandidates(now: Long, projectId: Long) =
        refreshQueue.load(now, includeArchived(), projectId)
    override suspend fun projectForConversation(conversationId: Long) = database.projectDao().getProjectIdForConversation(conversationId)
    override suspend fun reviewCards(projectId: Long, limit: Int) = database.studyReviewCardDao().getProjectCardsBounded(projectId, limit)

    override suspend fun current(item: ProjectPulseItem, now: Long): Boolean {
        val projectId = when (item) {
            is ProjectPulseItem.WorkPlan -> item.projectId
            is ProjectPulseItem.Task -> item.projectId
            is ProjectPulseItem.Decision -> item.projectId
            is ProjectPulseItem.Research -> item.projectId
            is ProjectPulseItem.ReviewCard -> item.projectId
        }
        if (project(projectId)?.id != projectId) return false
        return when (item) {
            is ProjectPulseItem.WorkPlan -> {
                val plan = database.projectWorkPlanDao().getPlan(item.planId, item.projectId)
                    ?.takeIf { it.status == "ACTIVE" && it.archivedAt == null } ?: return false
                val stepId = item.stepId ?: return true
                val step = database.projectWorkPlanDao().getStep(stepId, item.planId) ?: return false
                step.planId == plan.id && plan.currentStepPosition == step.position
            }
            is ProjectPulseItem.Task -> database.projectTaskDao().get(item.taskId, item.projectId)?.state == "OPEN"
            is ProjectPulseItem.Decision -> database.projectDecisionDao().get(item.decisionId, item.projectId)?.status == "ACTIVE"
            is ProjectPulseItem.Research -> {
                val candidate = refreshQueue.load(now, includeArchived(), item.projectId).candidates.firstOrNull {
                    it.sourceConversationId == item.conversationId && it.sourceMessageId == item.messageId &&
                        it.frozenScope.projectId == item.projectId && it.receipt.projectContextId == item.projectId
                } ?: return false
                projectForConversation(candidate.sourceConversationId) == item.projectId
            }
            is ProjectPulseItem.ReviewCard -> database.studyReviewCardDao().get(item.cardId)?.let {
                it.scope == "PROJECT" && it.projectId == item.projectId && it.dueAt <= now
            } == true
        }
    }

    override suspend fun resolveCurrentReviewTarget(
        target: ProjectReviewSessionPolicy.ProjectReviewTarget,
        now: Long,
    ): ProjectPulseItem? {
        if (now < 0L || project(target.projectId())?.id != target.projectId()) return null
        return when (target) {
            is ProjectReviewSessionPolicy.ProjectReviewTarget.WorkPlan -> {
                val plan = database.projectWorkPlanDao().getPlan(target.planId, target.projectId)
                    ?.takeIf { it.id == target.planId && it.projectId == target.projectId && it.status == "ACTIVE" && it.archivedAt == null }
                    ?: return null
                val stepId = target.stepId ?: return null
                val step = database.projectWorkPlanDao().getStep(stepId, target.planId)
                    ?.takeIf { it.id == stepId && it.planId == plan.id && it.position == plan.currentStepPosition }
                    ?: return null
                val expectedState = when (target.kind) {
                    ProjectReviewSessionPolicy.ProjectReviewKind.WORK_PLAN_REVIEW -> "REVIEW"
                    ProjectReviewSessionPolicy.ProjectReviewKind.WORK_PLAN_FAILED -> "FAILED"
                    else -> return null
                }
                if (step.state != expectedState) return null
                ProjectPulseItem.WorkPlan(target.projectId, plan.id, step.id, plan.title, step.title, step.state)
            }
            is ProjectReviewSessionPolicy.ProjectReviewTarget.Task -> {
                val task = database.projectTaskDao().get(target.taskId, target.projectId)
                    ?.takeIf { it.id == target.taskId && it.projectId == target.projectId && it.state == "OPEN" }
                    ?: return null
                val dueAt = task.reminderAt?.takeIf { task.reminderEnabled && it <= now } ?: return null
                ProjectPulseItem.Task(target.projectId, task.id, task.title, dueAt)
            }
            is ProjectReviewSessionPolicy.ProjectReviewTarget.Research -> {
                val candidate = refreshQueue.load(now, includeArchived(), target.projectId).candidates.firstOrNull {
                    it.sourceConversationId == target.conversationId && it.sourceMessageId == target.messageId &&
                        it.frozenScope.projectId == target.projectId && it.receipt.projectContextId == target.projectId
                } ?: return null
                if (projectForConversation(candidate.sourceConversationId) != target.projectId) return null
                ProjectPulseItem.Research(target.projectId, candidate.sourceConversationId, candidate.sourceMessageId, candidate.conversationTitle)
            }
            is ProjectReviewSessionPolicy.ProjectReviewTarget.ReviewCard -> {
                val card = database.studyReviewCardDao().get(target.cardId)
                    ?.takeIf { it.id == target.cardId && it.scope == "PROJECT" && it.projectId == target.projectId && it.dueAt <= now }
                    ?: return null
                ProjectPulseItem.ReviewCard(target.projectId, card.id, card.front, card.dueAt)
            }
        }
    }

    private fun ProjectReviewSessionPolicy.ProjectReviewTarget.projectId(): Long = when (this) {
        is ProjectReviewSessionPolicy.ProjectReviewTarget.WorkPlan -> projectId
        is ProjectReviewSessionPolicy.ProjectReviewTarget.Task -> projectId
        is ProjectReviewSessionPolicy.ProjectReviewTarget.Research -> projectId
        is ProjectReviewSessionPolicy.ProjectReviewTarget.ReviewCard -> projectId
    }
}

class ProjectPulseRepository internal constructor(private val source: ProjectPulseDataSource) {
    suspend fun load(projectId: Long, now: Long = System.currentTimeMillis()): ProjectPulseSnapshot? = withContext(Dispatchers.IO) {
        if (projectId <= 0L || now < 0L) return@withContext null
        try {
            val project = source.project(projectId) ?: return@withContext null
            if (project.id != projectId) return@withContext null
            val plan = source.activePlan(projectId)?.takeIf { it.projectId == projectId && it.status == "ACTIVE" && it.archivedAt == null }
            val steps = plan?.let { source.planSteps(it.id).filter { step -> step.planId == it.id } }.orEmpty()
            val tasks = source.openTasks(projectId, now, MAX_TASK_QUERY).filter { it.projectId == projectId && it.state == "OPEN" }
            val decisions = source.activeDecisions(projectId, MAX_PULSE_QUERY).filter { it.projectId == projectId && it.status == "ACTIVE" }
            val queue = source.researchCandidates(now, projectId)
            val projectIds = LinkedHashMap<Long, Long?>()
            for (candidate in queue.candidates) {
                projectIds[candidate.sourceConversationId] = source.projectForConversation(candidate.sourceConversationId)
            }
            val cards = source.reviewCards(projectId, MAX_REVIEW_QUERY).filter { it.projectId == projectId && it.scope == "PROJECT" }
            ProjectPulsePolicy.project(
                projectId, project.name, now, plan, steps, tasks, decisions,
                queue.candidates, projectIds, cards,
                researchQueueCoverageIncomplete = queue.coverageIncomplete,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun revalidate(item: ProjectPulseItem, now: Long = System.currentTimeMillis()): Boolean = withContext(Dispatchers.IO) {
        try {
            source.current(item, now)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    internal suspend fun resolveCurrentReviewTarget(
        target: ProjectReviewSessionPolicy.ProjectReviewTarget,
        now: Long = System.currentTimeMillis(),
    ): ProjectPulseItem? = withContext(Dispatchers.IO) {
        try {
            source.resolveCurrentReviewTarget(target, now)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_TASK_QUERY = 64
        private const val MAX_PULSE_QUERY = 16
        private const val MAX_REVIEW_QUERY = 64
    }
}

internal fun projectPulseRepository(context: Context, database: AppDatabase, includeArchived: () -> Boolean) =
    ProjectPulseRepository(RoomProjectPulseDataSource(context.applicationContext, database, includeArchived))
