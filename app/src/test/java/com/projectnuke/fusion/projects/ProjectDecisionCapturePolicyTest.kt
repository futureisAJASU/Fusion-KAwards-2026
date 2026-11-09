package com.projectnuke.fusion.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectDecisionCapturePolicyTest {
    private val source = ProjectDecisionCaptureControllerTest.source()

    private fun live() = ProjectDecisionCaptureLiveSource(
        projectExists = true,
        conversationExists = true,
        conversationArchived = false,
        archiveAccessAllowed = true,
        assignedProjectId = source.projectId,
        assistantMessageExists = true,
        messageConversationId = source.conversationId,
        messageRole = "assistant",
        messageCreatedAt = source.assistantMessageCreatedAt,
        answerFingerprint = source.answerFingerprint,
        responseVersionGroupId = source.responseVersionGroupId,
        responseVersionMessageIds = source.responseVersionMessageIds,
        activeAssistantMessageId = source.assistantMessageId,
    )

    @Test
    fun exactFrozenSourceIsAccepted() {
        assertNull(ProjectDecisionCapturePolicy.validate(source, live()))
    }

    @Test
    fun deletedProjectConversationOrMessageFailsClosed() {
        assertEquals(
            ProjectDecisionCaptureResult.ProjectMissing,
            ProjectDecisionCapturePolicy.validate(source, live().copy(projectExists = false)),
        )
        assertEquals(
            ProjectDecisionCaptureResult.SourceUnavailable,
            ProjectDecisionCapturePolicy.validate(source, live().copy(conversationExists = false)),
        )
        assertEquals(
            ProjectDecisionCaptureResult.SourceUnavailable,
            ProjectDecisionCapturePolicy.validate(source, live().copy(assistantMessageExists = false)),
        )
    }

    @Test
    fun projectMembershipAndArchiveAuthorityAreRechecked() {
        assertEquals(
            ProjectDecisionCaptureResult.SourceChanged,
            ProjectDecisionCapturePolicy.validate(source, live().copy(assignedProjectId = 4L)),
        )
        assertEquals(
            ProjectDecisionCaptureResult.ArchiveInaccessible,
            ProjectDecisionCapturePolicy.validate(
                source,
                live().copy(conversationArchived = true, archiveAccessAllowed = false),
            ),
        )
    }

    @Test
    fun exactAssistantAnswerAndResponseVersionMustStillBeCurrent() {
        val drifts = listOf(
            live().copy(messageConversationId = 11L),
            live().copy(messageRole = "user"),
            live().copy(messageCreatedAt = 31L),
            live().copy(answerFingerprint = ProjectDecisionCapturePolicy.fingerprint("changed answer")),
            live().copy(responseVersionGroupId = 99L),
            live().copy(responseVersionMessageIds = listOf(19L, 20L)),
            live().copy(activeAssistantMessageId = 19L),
        )
        drifts.forEach { current ->
            assertEquals(ProjectDecisionCaptureResult.SourceChanged, ProjectDecisionCapturePolicy.validate(source, current))
        }
    }
}
