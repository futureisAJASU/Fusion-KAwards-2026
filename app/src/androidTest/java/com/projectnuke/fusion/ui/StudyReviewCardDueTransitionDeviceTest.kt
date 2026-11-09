package com.projectnuke.fusion.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.projectnuke.fusion.data.StudyReviewCardEntity
import com.projectnuke.fusion.study.ReviewCardRating
import com.projectnuke.fusion.ui.theme.FusionTheme
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import org.junit.Rule
import org.junit.Test

class StudyReviewCardDueTransitionDeviceTest {
    private val compose = createComposeRule()
    @get:Rule val composeHostRules = composeHostTestRules(compose)

    @Test
    fun cardBecomesReviewableWhenItsDueTimePassesWithoutDatabaseChanges() {
        var now = System.currentTimeMillis()
        val card = StudyReviewCardEntity(
            id = "due-transition", scope = "PERSONAL", projectId = null,
            front = "due card front", back = "due card answer", sourceKind = "USER_TEXT",
            sourceConversationId = null, sourceMessageId = null, sourceSavedKnowledgeId = null,
            sourceProjectId = null, sourceProjectFileId = null, dueAt = now + 3_000L,
            createdAt = 1L, updatedAt = 1L,
        )

        compose.setContentOnResumedTestHost {
            FusionTheme(FusionThemeMode.LIGHT) {
                Surface {
                    StudyQuickReviewCardRow(
                        card = card,
                        notificationsAllowed = false,
                        onRate = { _, _ -> },
                        onScheduleReminder = {},
                        onEnableReminder = {},
                        onDisableReminder = {},
                        onCancelReminder = {},
                        onDelete = {},
                        clockMillis = { now },
                    )
                }
            }
        }

        compose.onNodeWithText("예정된 복습").assertIsNotEnabled()
        now += 3_000L
        compose.mainClock.advanceTimeBy(3_100L)
        compose.onNodeWithText("답 보기").assertIsEnabled()
    }
}
