package com.projectnuke.fusion.projects

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding A regression: Work Plan Search opens with selection mode active
 * and remains active after filter/archived resets. Ordinary search keeps
 * the legacy reset + manual toggle.
 */
class ProjectWorkPlanSearchSelectionPolicyTest {
    @Test
    fun `work plan intent owns mandatory selection mode`() {
        assertTrue(ProjectWorkPlanSearchSelectionPolicy.selectionModeOwnedByWorkPlan(true))
        assertFalse(ProjectWorkPlanSearchSelectionPolicy.selectionModeOwnedByWorkPlan(false))
    }

    @Test
    fun `filter reset preserves work plan mode and resets ordinary search`() {
        assertTrue(ProjectWorkPlanSearchSelectionPolicy.modeAfterFilterReset(true))
        assertFalse(ProjectWorkPlanSearchSelectionPolicy.modeAfterFilterReset(false))
    }

    @Test
    fun `manual toggle is hidden while the work plan owns the mode`() {
        assertFalse(ProjectWorkPlanSearchSelectionPolicy.manualToggleVisible(true))
        assertTrue(ProjectWorkPlanSearchSelectionPolicy.manualToggleVisible(false))
    }

    @Test
    fun `repeated resets never strand a work plan without selection`() {
        var mode = ProjectWorkPlanSearchSelectionPolicy.selectionModeOwnedByWorkPlan(true)
        repeat(3) {
            mode = ProjectWorkPlanSearchSelectionPolicy.modeAfterFilterReset(true)
            assertTrue(mode)
        }
    }
}
