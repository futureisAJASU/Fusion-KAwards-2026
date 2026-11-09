package com.projectnuke.fusion.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectDecisionSuggestionPresentationPolicyTest {
    @Test
    fun `edited title or rationale hides suggestions until exact draft has been ranked`() {
        val original = ProjectDecisionSuggestionDraft("Original title", "Original rationale")
        val editedTitle = original.copy(title = "A substantially different title")
        val editedRationale = original.copy(rationale = "A substantially different rationale")

        assertFalse(ProjectDecisionSuggestionPresentationPolicy.showSuggestions(editedTitle, original, false, true))
        assertFalse(ProjectDecisionSuggestionPresentationPolicy.showSuggestions(editedRationale, original, false, true))
        assertFalse(ProjectDecisionSuggestionPresentationPolicy.showSuggestions(original, original, true, true))
        assertTrue(ProjectDecisionSuggestionPresentationPolicy.showSuggestions(editedTitle, editedTitle, false, true))
    }
}
