package com.projectnuke.fusion.ui

import com.projectnuke.fusion.projects.ProjectReviewSessionPolicy

internal data class ProjectReviewOpenRequestToken(
    val projectId: Long,
    val generation: Long,
    val target: ProjectReviewSessionPolicy.ProjectReviewTarget,
)

internal object ProjectReviewOpenRequestPolicy {
    fun isCurrent(
        request: ProjectReviewOpenRequestToken,
        currentProjectId: Long,
        currentGeneration: Long,
        reviewSessionOpen: Boolean,
        currentTarget: ProjectReviewSessionPolicy.ProjectReviewTarget?,
    ): Boolean =
        reviewSessionOpen &&
            request.projectId == currentProjectId &&
            request.generation == currentGeneration &&
            request.target == currentTarget
}
