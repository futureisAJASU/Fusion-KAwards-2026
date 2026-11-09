package com.projectnuke.fusion.ui

import com.projectnuke.fusion.projects.ProjectReviewSessionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectReviewOpenRequestPolicyTest {
    private val targetA = ProjectReviewSessionPolicy.ProjectReviewTarget.Task(1L, 10L)
    private val targetB = ProjectReviewSessionPolicy.ProjectReviewTarget.Task(1L, 11L)
    private val request = ProjectReviewOpenRequestToken(projectId = 1L, generation = 4L, target = targetA)

    @Test
    fun requestMayNavigateOnlyWhileItsProjectSessionAndExactTargetStillOwnIt() {
        assertTrue(ProjectReviewOpenRequestPolicy.isCurrent(request, 1L, 4L, true, targetA))
        assertFalse(ProjectReviewOpenRequestPolicy.isCurrent(request, 1L, 5L, true, targetA)) // skip/next/reopen
        assertFalse(ProjectReviewOpenRequestPolicy.isCurrent(request, 1L, 4L, false, targetA)) // dismiss
        assertFalse(ProjectReviewOpenRequestPolicy.isCurrent(request, 2L, 4L, true, targetA)) // same-name project switch
        assertFalse(ProjectReviewOpenRequestPolicy.isCurrent(request, 1L, 4L, true, targetB)) // current row changed
        assertFalse(ProjectReviewOpenRequestPolicy.isCurrent(request, 1L, 4L, true, null)) // deleted/unavailable row
    }
}
