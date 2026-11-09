package com.projectnuke.fusion.workspace

/** Exact durable identities carried from an Action Center row to its owner. */
data class WorkspaceWorkPlanTarget(
    val projectId: Long,
    val planId: String,
    val stepId: String,
)

data class WorkspaceTaskTarget(
    val projectId: Long,
    val taskId: Long,
)

object WorkspaceActionTargetPolicy {
    fun workPlan(item: WorkspaceAttentionItem): WorkspaceWorkPlanTarget? {
        if (item.kind != WorkspaceAttentionKind.WORK_PLAN_REVIEW &&
            item.kind != WorkspaceAttentionKind.WORK_PLAN_FAILED
        ) return null
        val projectId = item.projectId?.takeIf { it > 0L } ?: return null
        val parts = item.targetId.split(':', limit = 2)
        if (parts.size != 2 || parts.any(String::isBlank)) return null
        return WorkspaceWorkPlanTarget(projectId, parts[0], parts[1])
    }

    fun task(item: WorkspaceAttentionItem): WorkspaceTaskTarget? {
        if (item.kind != WorkspaceAttentionKind.OPEN_TASK) return null
        val projectId = item.projectId?.takeIf { it > 0L } ?: return null
        val taskId = item.targetId.toLongOrNull()?.takeIf { it > 0L } ?: return null
        return WorkspaceTaskTarget(projectId, taskId)
    }

    fun canOpenWorkPlan(
        target: WorkspaceWorkPlanTarget,
        projectExists: Boolean,
        planProjectId: Long?,
        stepPlanId: String?,
    ): Boolean = projectExists && planProjectId == target.projectId && stepPlanId == target.planId

    fun canOpenTask(
        target: WorkspaceTaskTarget,
        projectExists: Boolean,
        taskId: Long?,
        taskProjectId: Long?,
    ): Boolean = projectExists && taskId == target.taskId && taskProjectId == target.projectId
}
