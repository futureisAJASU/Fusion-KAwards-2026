package com.projectnuke.fusion.projects

import com.projectnuke.fusion.data.AppDatabase
import androidx.room.withTransaction
import com.projectnuke.fusion.knowledge.SavedKnowledgeAccessGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ProjectReportResearchSummary(
    val conversationId: Long,
    val summary: String,
)

data class ProjectReportSourceSummary(
    val projectFileId: Long,
    val summary: String,
)

/** Reads the bounded current Project packet and renders its deterministic report. */
class ProjectReportRepository(private val database: AppDatabase) {
    companion object {
        const val MAX_REPORT_CONVERSATIONS = 32
    }

    suspend fun buildMarkdown(
        projectId: Long,
        authorizedConversationIds: Set<Long>,
        researchSummaries: List<ProjectReportResearchSummary>,
        sourceSummaries: List<ProjectReportSourceSummary> = emptyList(),
        archivedContentAuthorized: Boolean,
        generatedAt: Long = System.currentTimeMillis(),
    ): String? = withContext(Dispatchers.IO) {
        if (projectId <= 0L || generatedAt < 0L || authorizedConversationIds.size > MAX_REPORT_CONVERSATIONS ||
            authorizedConversationIds.any { it <= 0L } ||
            sourceSummaries.size > ProjectReportPolicy.MAX_FILES ||
            sourceSummaries.any { it.projectFileId <= 0L || it.summary.length > 4_000 }
        ) return@withContext null
        database.withTransaction {
        val project = database.projectDao().getProjectById(projectId) ?: return@withTransaction null
        val currentRows = if (archivedContentAuthorized) {
            database.projectDao().getConversationsForProjectBounded(projectId, MAX_REPORT_CONVERSATIONS)
        } else {
            database.projectDao().getActiveConversationsForProjectBounded(projectId, MAX_REPORT_CONVERSATIONS)
        }
        val currentById = currentRows.associateBy { it.id }
        if (authorizedConversationIds.any { id ->
                val row = currentById[id]
                row == null || (row.isArchived && !archivedContentAuthorized)
            }
        ) return@withTransaction null
        val reportRows = authorizedConversationIds.mapNotNull(currentById::get)
        val taskRows = database.projectTaskDao().getRecentForProjectBounded(projectId, 64)
        val decisionRows = database.projectDecisionDao().getActiveDecisions(projectId, ProjectReportPolicy.MAX_DECISIONS)
        val knowledgeRows = SavedKnowledgeAccessGate.selectAccessibleBounded(
            database,
            projectId,
            pageSize = 24,
            requiredCount = ProjectReportPolicy.MAX_KNOWLEDGE,
            archiveAllowed = archivedContentAuthorized,
        )
        val fileRows = database.projectDao().getProjectFilesBounded(projectId, 32)
        val planDao = database.projectWorkPlanDao()
        val plans = planDao.getPlansExcludingArchived(projectId, ProjectWorkPlanRepository.MAX_PLANS)
            .filter { it.status in setOf("ACTIVE", "PAUSED", "COMPLETED") }
            .take(ProjectReportPolicy.MAX_PLANS)
            .map { plan -> ProjectWorkPlanWithSteps(plan, planDao.getSteps(plan.id)) }
        val summaryByConversation = researchSummaries.asSequence()
            .filter { it.conversationId in authorizedConversationIds }
            .distinctBy { it.conversationId }
            .associateBy { it.conversationId }
        val recentResearch = reportRows.asSequence()
            .filter { it.refreshedFromConversationId != null }
            .sortedByDescending { it.updatedAt }
            .mapNotNull { row ->
                summaryByConversation[row.id]?.let { summary ->
                    ProjectReportPolicy.Research(row.id, row.title, summary.summary)
                }
            }
            .take(ProjectReportPolicy.MAX_RESEARCH)
            .toList()
        val recentChanges = buildList {
            reportRows.sortedByDescending { it.updatedAt }.take(6).forEach {
                add(ProjectReportPolicy.Change("대화", it.title))
            }
            fileRows.sortedByDescending { it.addedAt }.take(3).forEach {
                add(ProjectReportPolicy.Change("자료", it.displayName))
            }
            knowledgeRows.take(3).forEach {
                add(ProjectReportPolicy.Change("저장한 지식", it.title))
            }
            taskRows.sortedByDescending { it.updatedAt }.take(3).forEach {
                add(ProjectReportPolicy.Change("할 일", it.title))
            }
            decisionRows.sortedByDescending { it.updatedAt }.take(2).forEach {
                add(ProjectReportPolicy.Change("결정", it.title))
            }
            plans.take(2).forEach {
                add(ProjectReportPolicy.Change("작업 계획", it.plan.title))
            }
        }
        ProjectReportPolicy.build(
            ProjectReportPolicy.Input(
                projectName = project.name,
                instructions = project.instructions,
                activeDecisions = decisionRows.map {
                    ProjectReportPolicy.Decision(it.title, it.rationale)
                },
                tasks = taskRows.map { ProjectReportPolicy.Task(it.title, it.state) },
                savedKnowledge = knowledgeRows.map { ProjectReportPolicy.Knowledge(it.title, it.savedText) },
                recentResearch = recentResearch,
                sourceFiles = fileRows.map { file ->
                    val summary = sourceSummaries.firstOrNull { it.projectFileId == file.id }?.summary.orEmpty()
                    ProjectReportPolicy.SourceFile(file.displayName, file.mimeType, summary)
                },
                plans = plans.map { row ->
                    ProjectReportPolicy.Plan(
                        title = row.plan.title,
                        status = row.plan.status,
                        currentStepPosition = row.plan.currentStepPosition,
                        steps = row.steps.map { ProjectReportPolicy.PlanStep(it.title, it.state) },
                    )
                },
                recentChanges = recentChanges,
                generatedAt = generatedAt,
            ),
        )
        }
    }
}
