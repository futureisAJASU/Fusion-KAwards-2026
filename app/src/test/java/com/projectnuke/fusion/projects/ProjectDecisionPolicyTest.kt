package com.projectnuke.fusion.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProjectDecisionPolicyTest {
    @Test
    fun `decision title is normalized and rationale remains optional`() {
        val result = ProjectDecisionPolicy.validate(ProjectDecisionDraft("  HBM   stack  ", "  "))

        assertNotNull(result)
        assertEquals("HBM stack", result!!.title)
        assertEquals("", result.rationale)
    }

    @Test
    fun `decision fields are bounded and source identity must be paired`() {
        assertNull(ProjectDecisionPolicy.validate(ProjectDecisionDraft(" ", "")))
        assertNull(ProjectDecisionPolicy.validate(ProjectDecisionDraft("x".repeat(201), "")))
        assertNull(ProjectDecisionPolicy.validate(ProjectDecisionDraft("valid", "x".repeat(1_001))))
        assertNull(ProjectDecisionPolicy.validate(ProjectDecisionDraft("valid", "", sourceConversationId = 3L)))
        assertNull(ProjectDecisionPolicy.validate(ProjectDecisionDraft("valid", "", sourceConversationId = -3L, sourceMessageId = 4L)))
        assertEquals("valid", ProjectDecisionPolicy.validate(
            ProjectDecisionDraft("valid", "", sourceConversationId = 3L, sourceMessageId = 4L),
        )?.title)
    }

    @Test
    fun `supersession identity cannot be blank`() {
        assertNull(ProjectDecisionPolicy.validate(ProjectDecisionDraft("valid", "", supersedesDecisionId = " ")))
        assertNotNull(ProjectDecisionPolicy.validate(ProjectDecisionDraft("valid", "", supersedesDecisionId = "prior")))
    }
}
