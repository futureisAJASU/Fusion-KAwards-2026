package com.projectnuke.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderReviewCardDestinationTest {
    @Test
    fun personalUserAuthoredCardWithoutSourceCanOpen() {
        assertEquals(
            ReminderReviewCardDestination.PERSONAL_CARD,
            resolveReminderReviewCardDestination(
                scope = "PERSONAL",
                projectId = null,
                projectExists = false,
                projectDeleting = false,
                deletionPending = false,
                sourceConversationId = null,
                sourceConversationArchived = null,
                sourceSavedKnowledgeId = null,
                savedKnowledgeAccessible = false,
                archiveAllowed = false,
            ),
        )
    }

    @Test
    fun accessiblePersonalSourcesCanOpenButLockedArchivedSourcesCannot() {
        assertEquals(
            ReminderReviewCardDestination.PERSONAL_CARD,
            resolveReminderReviewCardDestination(
                "PERSONAL", null, false, false, false,
                sourceConversationId = 7L,
                sourceConversationArchived = false,
                sourceSavedKnowledgeId = null,
                savedKnowledgeAccessible = false,
                archiveAllowed = false,
            ),
        )
        assertNull(
            resolveReminderReviewCardDestination(
                "PERSONAL", null, false, false, false,
                sourceConversationId = 7L,
                sourceConversationArchived = true,
                sourceSavedKnowledgeId = null,
                savedKnowledgeAccessible = false,
                archiveAllowed = false,
            ),
        )
        assertEquals(
            ReminderReviewCardDestination.PERSONAL_CARD,
            resolveReminderReviewCardDestination(
                "PERSONAL", null, false, false, false,
                sourceConversationId = 7L,
                sourceConversationArchived = true,
                sourceSavedKnowledgeId = null,
                savedKnowledgeAccessible = false,
                archiveAllowed = true,
            ),
        )
    }

    @Test
    fun accessibleSavedKnowledgeFallbackCanOpenWhenConversationIsGone() {
        assertEquals(
            ReminderReviewCardDestination.PERSONAL_CARD,
            resolveReminderReviewCardDestination(
                "PERSONAL", null, false, false, false,
                sourceConversationId = 7L,
                sourceConversationArchived = null,
                sourceSavedKnowledgeId = "saved-1",
                savedKnowledgeAccessible = true,
                archiveAllowed = false,
            ),
        )
    }

    @Test
    fun projectCardRequiresExistingNonDeletingProjectAndLiveCard() {
        fun destination(
            projectExists: Boolean = true,
            projectDeleting: Boolean = false,
            deletionPending: Boolean = false,
        ) = resolveReminderReviewCardDestination(
            "PROJECT", 12L, projectExists, projectDeleting, deletionPending,
            sourceConversationId = null,
            sourceConversationArchived = null,
            sourceSavedKnowledgeId = null,
            savedKnowledgeAccessible = false,
            archiveAllowed = false,
        )

        assertEquals(ReminderReviewCardDestination.PROJECT_CARD, destination())
        assertNull(destination(projectExists = false))
        assertNull(destination(projectDeleting = true))
        assertNull(destination(deletionPending = true))
    }

    @Test
    fun unknownScopeAndMissingPersonalSourcesAreRejected() {
        assertNull(
            resolveReminderReviewCardDestination(
                "OTHER", null, false, false, false,
                null, null, null, false, false,
            ),
        )
        assertNull(
            resolveReminderReviewCardDestination(
                "PERSONAL", null, false, false, false,
                7L, null, null, false, false,
            ),
        )
    }
}
