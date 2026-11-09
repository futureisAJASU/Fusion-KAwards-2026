package com.projectnuke.fusion.projects

/** Phase J transient review queue. Typed kinds carry behavior; Korean labels are presentation only. */
internal object ProjectReviewSessionPolicy {
    const val MAX_PROJECT_REVIEW_ITEMS = 10

    enum class ProjectReviewKind {
        WORK_PLAN_REVIEW,
        DUE_TASK,
        DUE_REVIEW_CARD,
        WORK_PLAN_FAILED,
        RESEARCH_REFRESH,
    }

    sealed interface ProjectReviewTarget {
        data class WorkPlan(
            val projectId: Long,
            val planId: String,
            val stepId: String?,
            val kind: ProjectReviewKind,
        ) : ProjectReviewTarget
        data class Task(val projectId: Long, val taskId: Long) : ProjectReviewTarget
        data class Research(val projectId: Long, val conversationId: Long, val messageId: Long) : ProjectReviewTarget
        data class ReviewCard(val projectId: Long, val cardId: String) : ProjectReviewTarget
    }

    data class ReviewItem(
        val target: ProjectReviewTarget,
        val kind: ProjectReviewKind,
        val title: String,
        val subtitle: String,
    )

    fun labelFor(kind: ProjectReviewKind): String = when (kind) {
        ProjectReviewKind.WORK_PLAN_REVIEW -> "검토 필요"
        ProjectReviewKind.DUE_TASK -> "알림 확인"
        ProjectReviewKind.DUE_REVIEW_CARD -> "복습 카드"
        ProjectReviewKind.WORK_PLAN_FAILED -> "실패한 단계"
        ProjectReviewKind.RESEARCH_REFRESH -> "다시 확인할 Research"
    }

    fun priority(kind: ProjectReviewKind): Int = when (kind) {
        ProjectReviewKind.WORK_PLAN_REVIEW -> 0
        ProjectReviewKind.DUE_TASK -> 1
        ProjectReviewKind.DUE_REVIEW_CARD -> 2
        ProjectReviewKind.WORK_PLAN_FAILED -> 3
        ProjectReviewKind.RESEARCH_REFRESH -> 4
    }

    fun buildItems(snapshot: ProjectPulseSnapshot, now: Long = snapshot.generatedAtMillis): List<ReviewItem> {
        val candidates = snapshot.items.mapNotNull { item ->
            when (item) {
                is ProjectPulseItem.WorkPlan -> {
                    val kind = when (item.stepState) {
                        "REVIEW" -> ProjectReviewKind.WORK_PLAN_REVIEW
                        "FAILED" -> ProjectReviewKind.WORK_PLAN_FAILED
                        else -> null
                    } ?: return@mapNotNull null
                    ReviewItem(
                        target = ProjectReviewTarget.WorkPlan(item.projectId, item.planId, item.stepId, kind),
                        kind = kind,
                        title = item.title,
                        subtitle = listOfNotNull(item.stepTitle, labelFor(kind)).joinToString(" · "),
                    )
                }
                is ProjectPulseItem.Task -> {
                    val dueAt = item.dueAt ?: return@mapNotNull null
                    if (dueAt > now) return@mapNotNull null
                    ReviewItem(
                        ProjectReviewTarget.Task(item.projectId, item.taskId),
                        ProjectReviewKind.DUE_TASK,
                        item.title,
                        "알림 예정 시간이 지났어요",
                    )
                }
                is ProjectPulseItem.Research -> ReviewItem(
                    ProjectReviewTarget.Research(item.projectId, item.conversationId, item.messageId),
                    ProjectReviewKind.RESEARCH_REFRESH,
                    item.title,
                    "다시 확인할 Research",
                )
                is ProjectPulseItem.ReviewCard -> {
                    if (item.dueAt > now) return@mapNotNull null
                    ReviewItem(
                        ProjectReviewTarget.ReviewCard(item.projectId, item.cardId),
                        ProjectReviewKind.DUE_REVIEW_CARD,
                        item.title,
                        "복습 카드",
                    )
                }
                is ProjectPulseItem.Decision -> null
            }
        }
        return candidates.sortedWith(
            compareBy<ReviewItem> { priority(it.kind) }.thenBy { it.title }.thenBy { targetKey(it.target) },
        ).take(MAX_PROJECT_REVIEW_ITEMS)
    }

    /** Advance exactly one item; size is also the explicit finished index. */
    fun advance(index: Int, size: Int): Int = if (size <= 0) 0 else (index + 1).coerceAtMost(size)

    private fun targetKey(target: ProjectReviewTarget): String = when (target) {
        is ProjectReviewTarget.WorkPlan -> "plan:${target.planId}:${target.stepId.orEmpty()}"
        is ProjectReviewTarget.Task -> "task:${target.taskId}"
        is ProjectReviewTarget.Research -> "research:${target.conversationId}:${target.messageId}"
        is ProjectReviewTarget.ReviewCard -> "card:${target.cardId}"
    }
}
