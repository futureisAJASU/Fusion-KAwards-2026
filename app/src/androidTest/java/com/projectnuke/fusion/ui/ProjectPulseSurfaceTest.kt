package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.data.ProjectDecisionEntity
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectTaskEntity
import com.projectnuke.fusion.data.ProjectWorkPlanEntity
import com.projectnuke.fusion.data.ProjectWorkPlanStepEntity
import com.projectnuke.fusion.data.StudyReviewCardEntity
import com.projectnuke.fusion.projects.ProjectPulseItem
import com.projectnuke.fusion.projects.ProjectPulseSection
import com.projectnuke.fusion.projects.ProjectPulseSnapshot
import com.projectnuke.fusion.ui.theme.FusionTheme
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectPulseSurfaceTest {
    private val compose = createComposeRule()
    @get:Rule val hostRules = composeHostTestRules(compose)

    private fun render(mode: FusionThemeMode, onOpen: (ProjectPulseItem) -> Unit, onDismiss: () -> Unit) {
        val plan = ProjectPulseItem.WorkPlan(1, "plan-1", "step-1", "계획", "아주 긴 한국어 단계 제목이 포함된 검토 작업", "REVIEW")
        val task = ProjectPulseItem.Task(1, 42, "열린 할 일 대상 42", null)
        val decision = ProjectPulseItem.Decision(1, "decision-7", "현재 결정")
        val research = ProjectPulseItem.Research(1, 100, 200, "다시 확인할 Research")
        val card = ProjectPulseItem.ReviewCard(1, "card-9", "복습 카드", 10)
        val snapshot = ProjectPulseSnapshot(1, "Project Nuke Fusion", listOf(
            ProjectPulseSection("진행 중", listOf(plan)), ProjectPulseSection("열린 할 일", listOf(task)),
            ProjectPulseSection("현재 결정", listOf(decision)), ProjectPulseSection("다시 확인할 Research", listOf(research)),
            ProjectPulseSection("복습", listOf(card)),
        ))
        compose.setContentOnResumedTestHost {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                FusionTheme(mode) { Surface { Box(Modifier.width(320.dp)) {
                    ProjectPulseSurface(1, "Project Nuke Fusion", snapshot, false, false, validRepository(), onOpen, onDismiss)
                } } }
            }
        }
    }

    private fun validRepository() = ProjectPulseRepository(object : ProjectPulseDataSource {
        override suspend fun project(id: Long) = ProjectEntity(id, "Project Nuke Fusion", createdAt = 1, updatedAt = 1)
        override suspend fun activePlan(projectId: Long): ProjectWorkPlanEntity? = null
        override suspend fun planSteps(planId: String): List<ProjectWorkPlanStepEntity> = emptyList()
        override suspend fun openTasks(projectId: Long, now: Long, limit: Int): List<ProjectTaskEntity> = emptyList()
        override suspend fun activeDecisions(projectId: Long, limit: Int): List<ProjectDecisionEntity> = emptyList()
        override suspend fun researchCandidates(now: Long, projectId: Long): ResearchRefreshQueueLoadResult =
            ResearchRefreshQueueLoadResult(emptyList(), ResearchRefreshQueueCoverage.COMPLETE)
        override suspend fun projectForConversation(conversationId: Long): Long? = null
        override suspend fun reviewCards(projectId: Long, limit: Int): List<StudyReviewCardEntity> = emptyList()
        override suspend fun current(item: ProjectPulseItem, now: Long): Boolean = true
    })

    @Test fun compactLargeTextManySectionsKeepRowsReachableAndDismissIsReadOnly() {
        var openCount = 0
        var dismissCount = 0
        render(FusionThemeMode.BLACK, { openCount++ }, { dismissCount++ })
        compose.onNodeWithText("아주 긴 한국어 단계 제목이 포함된 검토 작업").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("열린 할 일 대상 42").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("복습 카드").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("닫기").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(0, openCount)
            assertEquals(1, dismissCount)
        }
    }

    @Test fun lightThemeActionsRemainReachableAndPreserveExactIdentity() {
        var opened: ProjectPulseItem? = null
        render(FusionThemeMode.LIGHT, { opened = it }, {})
        compose.onNodeWithText("열린 할 일 대상 42").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(42L, (opened as ProjectPulseItem.Task).taskId) }
    }

    @Test fun directResearchRowRevalidatesAndOpensTheExactProjectScopedSource() {
        val expectedItem = ProjectPulseItem.Research(7L, 129L, 908L, "Project B Research candidate")
        var revalidated: ProjectPulseItem? = null
        var opened: ProjectPulseItem? = null
        val repository = ProjectPulseRepository(object : ProjectPulseDataSource {
            override suspend fun project(id: Long) = ProjectEntity(id, "Same name", createdAt = 1, updatedAt = 1)
            override suspend fun activePlan(projectId: Long): ProjectWorkPlanEntity? = null
            override suspend fun planSteps(planId: String): List<ProjectWorkPlanStepEntity> = emptyList()
            override suspend fun openTasks(projectId: Long, now: Long, limit: Int): List<ProjectTaskEntity> = emptyList()
            override suspend fun activeDecisions(projectId: Long, limit: Int): List<ProjectDecisionEntity> = emptyList()
            override suspend fun researchCandidates(now: Long, projectId: Long): ResearchRefreshQueueLoadResult =
                ResearchRefreshQueueLoadResult(emptyList(), ResearchRefreshQueueCoverage.COMPLETE)
            override suspend fun projectForConversation(conversationId: Long): Long? = 7L
            override suspend fun reviewCards(projectId: Long, limit: Int): List<StudyReviewCardEntity> = emptyList()
            override suspend fun current(item: ProjectPulseItem, now: Long): Boolean {
                revalidated = item
                return item == expectedItem
            }
        })
        val snapshot = ProjectPulseSnapshot(
            projectId = 7L,
            projectName = "Same name",
            sections = listOf(ProjectPulseSection("다시 확인할 Research", listOf(expectedItem))),
        )
        compose.setContentOnResumedTestHost {
            FusionTheme(FusionThemeMode.LIGHT) {
                Surface { Box(Modifier.width(320.dp)) {
                    ProjectPulseSurface(7L, "Same name", snapshot, false, false, repository, { opened = it }, {})
                } }
            }
        }

        compose.onNodeWithText(expectedItem.title).performScrollTo().performClick()
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(expectedItem, revalidated)
            assertEquals(expectedItem, opened)
        }
    }
}
