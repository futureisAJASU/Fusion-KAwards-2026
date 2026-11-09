package com.projectnuke.fusion.ui

internal data class ProjectDecisionSuggestionDraft(val title: String, val rationale: String)

internal object ProjectDecisionSuggestionPresentationPolicy {
    fun showSuggestions(
        currentDraft: ProjectDecisionSuggestionDraft,
        suggestionsDraft: ProjectDecisionSuggestionDraft?,
        loading: Boolean,
        hasSuggestions: Boolean,
    ): Boolean = !loading && hasSuggestions && suggestionsDraft == currentDraft
}
