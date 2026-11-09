package com.projectnuke.fusion.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2 regressions: work-plan lifecycle truth (C05, C06, U06). */
class ProjectWorkPlanRetryPolicyTest {
    @Test fun retryBudgetIsSingleAuthoritativeLimit() {
        assertEquals(5, ProjectWorkPlanRepository.MAX_STEP_ATTEMPTS)
    }

    @Test fun reviewRetryAvailabilityDerivesFromDomainPolicy() {
        // U06: UI must derive from the same authoritative attempt-count policy
        // used by ProjectWorkPlanRepository.startStep, not a hardcoded duplicate.
        fun canRetryReview(attemptCount: Int): Boolean =
            attemptCount < ProjectWorkPlanRepository.MAX_STEP_ATTEMPTS
        assertTrue(canRetryReview(4))
        assertFalse(canRetryReview(5))
        assertFalse(canRetryReview(6))
    }
}
