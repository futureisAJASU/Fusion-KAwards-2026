package com.projectnuke.fusion.projects

/**
 * Finding A: the Work Plan owns selection mode while its intent is bound.
 *
 * `FusionSearchSurface` must never clear Work Plan selection mode on filter
 * or archive changes; ordinary search keeps the legacy toggle behavior.
 * Pure so the transition is unit-testable without Compose.
 */
object ProjectWorkPlanSearchSelectionPolicy {
    /**
     * Selection mode owned by the Work Plan intent. A non-null intent means
     * mandatory selection mode; null means ordinary toggleable search.
     */
    fun selectionModeOwnedByWorkPlan(workPlanBound: Boolean): Boolean = workPlanBound

    /**
     * Mode after a filter/archive reset. Work Plan mode stays active;
     * ordinary search resets to inactive (legacy behavior preserved).
     */
    fun modeAfterFilterReset(workPlanBound: Boolean): Boolean = workPlanBound

    /**
     * Whether the manual selection-mode toggle may be shown. Hidden while
     * the Work Plan owns the mode so the user can never strand the flow
     * with selection disabled.
     */
    fun manualToggleVisible(workPlanBound: Boolean): Boolean = !workPlanBound
}
