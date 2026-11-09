package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.chat.AnswerSecondaryAction
import com.projectnuke.fusion.chat.AnswerSecondaryActionContext
import com.projectnuke.fusion.data.ProjectDecisionEntity
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectTaskEntity
import com.projectnuke.fusion.data.ProjectWorkPlanEntity
import com.projectnuke.fusion.data.ProjectWorkPlanStepEntity
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.data.SavedKnowledgeNoteSourceEntity
import com.projectnuke.fusion.data.StudyReviewCardEntity
import com.projectnuke.fusion.knowledge.ProjectKnowledgeHealthDataSource
import com.projectnuke.fusion.knowledge.ProjectKnowledgeHealthLoadState
import com.projectnuke.fusion.knowledge.ProjectKnowledgeHealthPolicy
import com.projectnuke.fusion.knowledge.ProjectKnowledgeHealthRepository
import com.projectnuke.fusion.projects.ProjectPulseItem
import com.projectnuke.fusion.projects.ProjectPulseSection
import com.projectnuke.fusion.projects.ProjectPulseSnapshot
import com.projectnuke.fusion.projects.ProjectDecisionCaptureController
import com.projectnuke.fusion.projects.ProjectDecisionCaptureGateway
import com.projectnuke.fusion.projects.ProjectDecisionCaptureResult
import com.projectnuke.fusion.projects.ProjectDecisionDraft
import com.projectnuke.fusion.projects.ProjectReviewSessionPolicy
import com.projectnuke.fusion.ui.theme.FusionTheme
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import com.projectnuke.fusion.trust.TrustRoutingPresentationKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectKnowledgeHealthAndReviewSessionTest {
    private val compose = createComposeRule()
    @get:Rule val hostRules = composeHostTestRules(compose)

    @Test
    fun normalNonEmptyPulseStartsReviewAndOpensCanonicalTargetAtCompactLargeText() {
        val plan = ProjectPulseItem.WorkPlan(1, "plan-1", "step-1", "검토 계획", "검토 단계", "REVIEW")
        val task = ProjectPulseItem.Task(1, 42, "기한 지난 할 일", 900)
        val canonicalPlan = plan.copy(title = "현재 저장된 검토 계획")
        val source = FakePulseDataSource(mapOf(
            ProjectReviewSessionPolicy.ProjectReviewTarget.WorkPlan(
                1, "plan-1", "step-1", ProjectReviewSessionPolicy.ProjectReviewKind.WORK_PLAN_REVIEW,
            ) to canonicalPlan,
            ProjectReviewSessionPolicy.ProjectReviewTarget.Task(1, 42) to task.copy(title = "현재 저장된 할 일"),
        ))
        val repository = ProjectPulseRepository(source)
        val snapshot = pulseSnapshot(plan, task)
        var opened: ProjectPulseItem? = null
        setCompactLargeTextContent {
            ProjectPulseSurface(1, "Project Nuke Fusion", snapshot, false, false, repository, { opened = it }, {})
        }

        compose.onNodeWithText("알림 예정 시간이 지났어요").assertIsDisplayed()
        compose.onNodeWithText("검토 시작").assertIsDisplayed().performClick()
        compose.onNodeWithText("프로젝트 검토").assertIsDisplayed()
        compose.onNodeWithText("다음").assertDoesNotExist()
        compose.onNodeWithText("건너뛰기").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("열기").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()

        assertEquals(canonicalPlan, opened)
        assertEquals(1, source.resolveCount)
        assertEquals(0, source.durableWrites)
    }

    @Test
    fun staleTargetKeepsSessionOpenAndDoesNotOpenDestination() {
        val plan = ProjectPulseItem.WorkPlan(1, "plan-1", "step-1", "검토 계획", "검토 단계", "REVIEW")
        val source = FakePulseDataSource(emptyMap())
        setCompactLargeTextContent {
            ProjectPulseSurface(1, "Project", pulseSnapshot(plan), false, false, ProjectPulseRepository(source), {}, {})
        }

        compose.onNodeWithText("검토 시작").performClick()
        compose.onNodeWithText("열기").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("이 항목은 더 이상 검토할 수 없어요.").assertIsDisplayed()
        compose.onNodeWithText("다음").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("열기").assertDoesNotExist()
        compose.onNodeWithText("건너뛰기").assertDoesNotExist()
        assertEquals(1, source.resolveCount)
        assertEquals(0, source.durableWrites)

        compose.onNodeWithText("다음").performClick()
        compose.onNodeWithText("이번 검토에서 확인할 항목을 모두 살펴봤어요.").assertIsDisplayed()
        compose.onNodeWithText("완료").assertHeightIsAtLeast(48.dp)
        assertEquals(0, source.durableWrites)
    }

    @Test
    fun skipAndNextProgressToDistinctFinishedState() {
        val plan = ProjectPulseItem.WorkPlan(1, "plan-1", "step-1", "첫 검토 계획", "검토 단계", "REVIEW")
        val task = ProjectPulseItem.Task(1, 42, "두 번째 기한 할 일", 900)
        val source = FakePulseDataSource(emptyMap())
        setCompactLargeTextContent {
            ProjectPulseSurface(1, "Project", pulseSnapshot(plan, task), false, false, ProjectPulseRepository(source), {}, {})
        }

        compose.onNodeWithText("검토 시작").performClick()
        compose.onNodeWithText("첫 검토 계획").assertIsDisplayed()
        compose.onNodeWithText("건너뛰기").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("두 번째 기한 할 일").assertIsDisplayed()
        compose.onNodeWithText("건너뛰기").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("다음").assertDoesNotExist()
        compose.onNodeWithText("이번 검토에서 확인할 항목을 모두 살펴봤어요.").assertIsDisplayed()
        compose.onNodeWithText("완료").assertHeightIsAtLeast(48.dp)
        compose.onAllNodesWithText("이번 검토에서 확인할 항목이 없어요.").assertCountEquals(0)
        assertEquals(0, source.resolveCount)
        assertEquals(0, source.durableWrites)
    }

    @Test
    fun reviewSessionKeepsItsFrozenItemWhenSnapshotAddsAnEarlierItem() {
        val projectId = mutableStateOf(1L)
        val snapshot = mutableStateOf(pulseSnapshot(
            ProjectPulseItem.WorkPlan(1, "plan-b", "step-b", "B item", "step", "REVIEW"),
            ProjectPulseItem.WorkPlan(1, "plan-c", "step-c", "C item", "step", "REVIEW"),
        ))
        setCompactLargeTextContent {
            ProjectPulseSurface(projectId.value, "Same name", snapshot.value, false, false,
                ProjectPulseRepository(FakePulseDataSource(emptyMap())), {}, {})
        }

        compose.onNodeWithText("寃???쒖옉").performClick()
        compose.onNodeWithText("嫄대꼬?곌린").performClick()
        compose.onNodeWithText("C item").assertIsDisplayed()
        compose.runOnIdle {
            snapshot.value = pulseSnapshot(
                ProjectPulseItem.WorkPlan(1, "plan-a", "step-a", "A item", "step", "REVIEW"),
                ProjectPulseItem.WorkPlan(1, "plan-b", "step-b", "B item", "step", "REVIEW"),
                ProjectPulseItem.WorkPlan(1, "plan-c", "step-c", "C item", "step", "REVIEW"),
            )
        }
        compose.onNodeWithText("C item").assertIsDisplayed()
        compose.onNodeWithText("B item").assertDoesNotExist()
    }

    @Test
    fun switchingBetweenSameNameProjectsResetsReviewOwnership() {
        val projectId = mutableStateOf(1L)
        val snapshot = mutableStateOf(pulseSnapshot(
            ProjectPulseItem.WorkPlan(1, "plan-a", "step-a", "Project A item", "step", "REVIEW"),
            ProjectPulseItem.WorkPlan(1, "plan-b", "step-b", "Project A second", "step", "REVIEW"),
        ))
        setCompactLargeTextContent {
            ProjectPulseSurface(projectId.value, "Same name", snapshot.value, false, false,
                ProjectPulseRepository(FakePulseDataSource(emptyMap())), {}, {})
        }

        compose.onNodeWithText("寃???쒖옉").performClick()
        compose.onNodeWithText("嫄대꼬?곌린").performClick()
        compose.onNodeWithText("Project A second").assertIsDisplayed()
        compose.runOnIdle {
            projectId.value = 2L
            snapshot.value = pulseSnapshot(
                ProjectPulseItem.WorkPlan(2, "plan-c", "step-c", "Project B item", "step", "REVIEW"),
            )
        }
        compose.onNodeWithText("寃???쒖옉").performClick()
        compose.onNodeWithText("Project B item").assertIsDisplayed()
        compose.onNodeWithText("1 / 1").assertIsDisplayed()
    }

    @Test
    fun repeatedReviewOpenTapsResolveAndNavigateOnlyOnce() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val item = ProjectPulseItem.WorkPlan(1, "plan-a", "step-a", "Review target", "step", "REVIEW")
        val target = ProjectReviewSessionPolicy.ProjectReviewTarget.WorkPlan(
            1, "plan-a", "step-a", ProjectReviewSessionPolicy.ProjectReviewKind.WORK_PLAN_REVIEW,
        )
        val canonical = item.copy(title = "Canonical target")
        val source = FakePulseDataSource(mapOf(target to canonical)) { _ -> gate.await() }
        var openCount = 0
        setCompactLargeTextContent {
            ProjectPulseSurface(1, "Project", pulseSnapshot(item), false, false,
                ProjectPulseRepository(source), { openCount++ }, {})
        }

        compose.onNodeWithText("寃???쒖옉").performClick()
        compose.onNodeWithText("?닿린").performClick()
        compose.runOnIdle { assertEquals(1, source.resolveCount) }
        compose.onNodeWithText("?닿린").assertIsNotEnabled()
        gate.complete(Unit)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, source.resolveCount)
            assertEquals(1, openCount)
        }
    }

    @Test
    fun skippingWhileResearchTargetResolutionIsPendingCannotNavigateTheSkippedItem() {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val first = ProjectPulseItem.WorkPlan(1, "plan-a", "step-a", "First review", "step", "REVIEW")
        val second = ProjectPulseItem.WorkPlan(1, "plan-b", "step-b", "Second review", "step", "REVIEW")
        val firstTarget = ProjectReviewSessionPolicy.ProjectReviewTarget.WorkPlan(
            1, "plan-a", "step-a", ProjectReviewSessionPolicy.ProjectReviewKind.WORK_PLAN_REVIEW,
        )
        val secondTarget = ProjectReviewSessionPolicy.ProjectReviewTarget.WorkPlan(
            1, "plan-b", "step-b", ProjectReviewSessionPolicy.ProjectReviewKind.WORK_PLAN_REVIEW,
        )
        val source = FakePulseDataSource(mapOf(firstTarget to first.copy(title = "Canonical first"))) { request ->
            if (request == 1) started.complete(Unit)
            gate.await()
        }
        var opened: ProjectPulseItem? = null
        setCompactLargeTextContent {
            ProjectPulseSurface(1, "Project", pulseSnapshot(first, second), false, false,
                ProjectPulseRepository(source), { opened = it }, {})
        }

        compose.onNodeWithText("검토 시작").performClick()
        compose.onNodeWithText("열기").performClick()
        compose.waitUntil { started.isCompleted }
        compose.onNodeWithText("건너뛰기").performClick()
        compose.onNodeWithText("Second review").assertIsDisplayed()
        gate.complete(Unit)
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(null, opened) }
        compose.onNodeWithText("Second review").assertIsDisplayed()
        assertEquals(1, source.resolveCount)
        assertEquals(0, source.durableWrites)
        assertEquals(firstTarget, source.resolveCurrentTargetObserved)
    }

    @Test
    fun dismissAndProjectSwitchRevokePendingOpenAuthorityBeforeAReplacementSession() {
        val firstStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val firstGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val secondStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val secondGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val projectId = mutableStateOf(1L)
        val snapshot = mutableStateOf(pulseSnapshot(
            ProjectPulseItem.WorkPlan(1, "plan-a", "step-a", "Old project item", "step", "REVIEW"),
        ))
        val oldTarget = ProjectReviewSessionPolicy.ProjectReviewTarget.WorkPlan(
            1, "plan-a", "step-a", ProjectReviewSessionPolicy.ProjectReviewKind.WORK_PLAN_REVIEW,
        )
        val newTarget = ProjectReviewSessionPolicy.ProjectReviewTarget.WorkPlan(
            2, "plan-b", "step-b", ProjectReviewSessionPolicy.ProjectReviewKind.WORK_PLAN_REVIEW,
        )
        val source = FakePulseDataSource(mapOf(
            oldTarget to ProjectPulseItem.WorkPlan(1, "plan-a", "step-a", "Canonical old", "step", "REVIEW"),
            newTarget to ProjectPulseItem.WorkPlan(2, "plan-b", "step-b", "Canonical new", "step", "REVIEW"),
        )) { request ->
            if (request == 1) {
                firstStarted.complete(Unit)
                firstGate.await()
            } else {
                secondStarted.complete(Unit)
                secondGate.await()
            }
        }
        val opened = mutableListOf<ProjectPulseItem>()
        setCompactLargeTextContent {
            ProjectPulseSurface(projectId.value, "Same name", snapshot.value, false, false,
                ProjectPulseRepository(source), { opened += it }, {})
        }

        compose.onNodeWithText("검토 시작").performClick()
        compose.onNodeWithText("열기").performClick()
        compose.waitUntil { firstStarted.isCompleted }
        compose.onNodeWithTag("project_review_session_dismiss").performClick()
        compose.runOnIdle {
            projectId.value = 2L
            snapshot.value = pulseSnapshot(
                ProjectPulseItem.WorkPlan(2, "plan-b", "step-b", "New project item", "step", "REVIEW"),
            )
        }
        compose.onNodeWithText("검토 시작").performClick()
        compose.onNodeWithText("열기").performClick()
        compose.waitUntil { secondStarted.isCompleted }

        firstGate.complete(Unit)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(emptyList<ProjectPulseItem>(), opened) }
        secondGate.complete(Unit)
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(2, source.resolveCount)
            assertEquals(listOf("Canonical new"), opened.map { (it as ProjectPulseItem.WorkPlan).title })
        }
        assertEquals(0, source.durableWrites)
    }

    @Test
    fun returningToSameProjectCannotReviveAnOlderPendingOpenRequest() {
        val firstStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val firstGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val replacementStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val replacementGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val projectId = mutableStateOf(1L)
        val first = ProjectPulseItem.WorkPlan(1, "same-plan", "same-step", "Same project target", "step", "REVIEW")
        val otherProject = ProjectPulseItem.WorkPlan(2, "other-plan", "other-step", "Other project target", "step", "REVIEW")
        val target = ProjectReviewSessionPolicy.ProjectReviewTarget.WorkPlan(
            1, "same-plan", "same-step", ProjectReviewSessionPolicy.ProjectReviewKind.WORK_PLAN_REVIEW,
        )
        val otherTarget = ProjectReviewSessionPolicy.ProjectReviewTarget.WorkPlan(
            2, "other-plan", "other-step", ProjectReviewSessionPolicy.ProjectReviewKind.WORK_PLAN_REVIEW,
        )
        val source = FakePulseDataSource(mapOf(
            target to first.copy(title = "Canonical replacement"),
            otherTarget to otherProject.copy(title = "Canonical other project"),
        )) { request ->
            if (request == 1) {
                firstStarted.complete(Unit)
                firstGate.await()
            } else {
                replacementStarted.complete(Unit)
                replacementGate.await()
            }
        }
        val opened = mutableListOf<ProjectPulseItem>()
        setCompactLargeTextContent {
            val item = if (projectId.value == 1L) first else otherProject
            ProjectPulseSurface(
                projectId.value,
                "Same display name",
                pulseSnapshot(item),
                false,
                false,
                ProjectPulseRepository(source),
                { opened += it },
                {},
            )
        }

        compose.onNodeWithText("검토 시작").performClick()
        compose.onNodeWithText("열기").performClick()
        compose.waitUntil { firstStarted.isCompleted }
        compose.onNodeWithTag("project_review_session_dismiss").performClick()
        compose.runOnIdle { projectId.value = 2L }
        compose.runOnIdle { projectId.value = 1L }
        compose.onNodeWithText("검토 시작").performClick()
        compose.onNodeWithText("열기").performClick()
        compose.waitUntil { replacementStarted.isCompleted }

        firstGate.complete(Unit)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(emptyList<ProjectPulseItem>(), opened) }

        replacementGate.complete(Unit)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, source.resolveCount)
            assertEquals(listOf("Canonical replacement"), opened.map { (it as ProjectPulseItem.WorkPlan).title })
        }
    }

    @Test
    fun knowledgeHealthFailureShowsLoadFailureCopyInsteadOfEmptyState() {
        val row = knowledge("health-note")
        val repository = ProjectKnowledgeHealthRepository(FakeKnowledgeHealthDataSource(row)) { false }
        setCompactLargeTextContent {
            ProjectKnowledgeHealthSurface(
                projectId = 1,
                state = ProjectKnowledgeHealthLoadState.Failed,
                repository = repository,
                onOpenKnowledge = { _, _ -> },
                onDismiss = {},
            )
        }

        compose.onNodeWithText("저장 지식 상태를 불러오지 못했어요. 다시 시도해 주세요.").assertIsDisplayed()
        compose.onNodeWithText("확인할 항목이 없어요.").assertDoesNotExist()
    }

    @Test
    fun decisionActionUsesRevisedFriendlyKoreanCopy() {
        val context = AnswerSecondaryActionContext(
            persistedConversation = true,
            isProjectAssigned = true,
            responseVersionCount = 1,
            visibleAnswerAvailable = true,
            translationAvailable = false,
            receiptAvailable = false,
            routingKind = TrustRoutingPresentationKind.NONE,
            isStudyMode = false,
            isRetrySearchRecovery = false,
        )
        setCompactLargeTextContent {
            AnswerSecondaryActionSheet(
                expanded = true,
                onDismiss = {},
                actions = listOf(AnswerSecondaryAction.SAVE_AS_DECISION),
                context = context,
                compareLiveAvailable = true,
                onProject = {},
                onCompare = {},
                onTranslation = {},
                onRelatedMaterials = {},
                onViewContext = {},
                onSaveKnowledge = {},
                onCreateProjectTask = {},
                onCreateReviewCard = {},
                onSaveAsDecision = {},
                inline = true,
            )
        }

        compose.onNodeWithText("프로젝트 결정으로 저장할 내용을 검토해 주세요.").assertIsDisplayed()
    }

    @Test
    fun knowledgeHealthRowRechecksProjectAndAccessAtCompactLargeText() {
        val row = knowledge("health-note")
        val source = FakeKnowledgeHealthDataSource(row)
        val repository = ProjectKnowledgeHealthRepository(source) { false }
        val observation = ProjectKnowledgeHealthPolicy.HealthObservation(
            ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind.NOTE_LINK_UNAVAILABLE,
            row.id,
            "연결 노트 제목",
            "연결된 출처 일부를 열 수 없음",
        )
        val opened = mutableListOf<Pair<String, Long>>()
        setCompactLargeTextContent {
            ProjectKnowledgeHealthSurface(
                projectId = 1,
                state = ProjectKnowledgeHealthLoadState.Ready(listOf(observation)),
                repository = repository,
                onOpenKnowledge = { id, projectId -> opened += id to projectId },
                onDismiss = {},
            )
        }

        compose.onNodeWithText("연결 노트 제목").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()

        assertEquals(listOf(row.id to 1L), opened)
        assertEquals(listOf(false), source.accessChecks)
    }

    @Test
    fun archivePermissionChangesImmediatelyMaskAndRestoreProtectedHealthTitle() {
        val row = knowledge("health-archive")
        val repository = ProjectKnowledgeHealthRepository(FakeKnowledgeHealthDataSource(row)) { true }
        val observation = ProjectKnowledgeHealthPolicy.HealthObservation(
            ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind.SOURCE_UNAVAILABLE,
            row.id,
            "Confidential archived project title",
            "원본 답변을 찾을 수 없음",
            archiveProtected = true,
        )
        var archiveAllowed by mutableStateOf(true)
        setCompactLargeTextContent {
            ProjectKnowledgeHealthSurface(
                projectId = 1,
                state = ProjectKnowledgeHealthLoadState.Ready(listOf(observation)),
                repository = repository,
                archiveAllowed = archiveAllowed,
                onOpenKnowledge = { _, _ -> },
                onDismiss = {},
            )
        }

        compose.onNodeWithText("Confidential archived project title").assertIsDisplayed()
        compose.runOnIdle { archiveAllowed = false }
        compose.onNodeWithText("Confidential archived project title").assertDoesNotExist()
        compose.onNodeWithText("접근 제한된 자료").assertIsDisplayed()
        compose.runOnIdle { archiveAllowed = true }
        compose.onNodeWithText("Confidential archived project title").assertIsDisplayed()
    }

    @Test
    fun lockedArchiveDoesNotHideIndependentNoteTitle() {
        val row = knowledge("health-independent")
        val repository = ProjectKnowledgeHealthRepository(FakeKnowledgeHealthDataSource(row)) { false }
        val observation = ProjectKnowledgeHealthPolicy.HealthObservation(
            ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind.INDEPENDENT_COPY,
            row.id,
            "My independent note",
            "원본과 별도로 저장된 사본",
        )
        setCompactLargeTextContent {
            ProjectKnowledgeHealthSurface(
                projectId = 1,
                state = ProjectKnowledgeHealthLoadState.Ready(listOf(observation)),
                repository = repository,
                archiveAllowed = false,
                onOpenKnowledge = { _, _ -> },
                onDismiss = {},
            )
        }

        compose.onNodeWithText("My independent note").assertIsDisplayed()
    }

    @Test
    fun decisionReplacementSwitchesTrackControllerSelectionImmediately() {
        val controller = ProjectDecisionCaptureController(object : ProjectDecisionCaptureGateway {
            override suspend fun settle(
                source: com.projectnuke.fusion.projects.FrozenProjectDecisionSource,
                draft: ProjectDecisionDraft,
            ) = ProjectDecisionCaptureResult.Created("saved")
        })
        val decisions = listOf(
            ProjectDecisionEntity("first", 1, "First decision", "", "ACTIVE", createdAt = 1, updatedAt = 1),
            ProjectDecisionEntity("second", 1, "Second decision", "", "ACTIVE", createdAt = 2, updatedAt = 2),
        )
        setCompactLargeTextContent {
            ProjectDecisionCaptureSurface(controller, decisions, onDismiss = {})
        }
        val switches = compose.onAllNodes(isToggleable())
        switches.assertCountEquals(2)

        switches[0].assertIsOff().performClick()
        compose.runOnIdle { assertEquals("first", controller.supersedesDecisionId) }
        compose.onAllNodes(isToggleable())[0].assertIsOn()
        compose.onAllNodes(isToggleable())[1].assertIsOff()

        compose.onAllNodes(isToggleable())[1].performClick()
        compose.runOnIdle { assertEquals("second", controller.supersedesDecisionId) }
        compose.onAllNodes(isToggleable())[0].assertIsOff()
        compose.onAllNodes(isToggleable())[1].assertIsOn()

        compose.onAllNodes(isToggleable())[1].performClick()
        compose.runOnIdle { assertEquals(null, controller.supersedesDecisionId) }
        compose.onAllNodes(isToggleable())[1].assertIsOff()
    }

    @Test
    fun largeDecisionListScrollsAtNarrowWidthAndKeepsSaveActionReachable() {
        val controller = ProjectDecisionCaptureController(object : ProjectDecisionCaptureGateway {
            override suspend fun settle(
                source: com.projectnuke.fusion.projects.FrozenProjectDecisionSource,
                draft: ProjectDecisionDraft,
            ) = ProjectDecisionCaptureResult.Created("saved")
        })
        val decisions = (1..48).map { index ->
            ProjectDecisionEntity("decision-$index", 1, "Decision $index", "", "ACTIVE", createdAt = index.toLong(), updatedAt = index.toLong())
        }
        compose.setContentOnResumedTestHost {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                FusionTheme(FusionThemeMode.LIGHT) {
                    Surface { Box(Modifier.width(320.dp)) { ProjectDecisionCaptureSurface(controller, decisions, {}) } }
                }
            }
        }

        compose.onNodeWithText("Decision 48").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("project-decision-save").assertIsDisplayed()
    }

    private fun setCompactLargeTextContent(
        fontScale: Float = 1.5f,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        compose.setContentOnResumedTestHost {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                FusionTheme(FusionThemeMode.LIGHT) {
                    Surface { Box(Modifier.width(320.dp)) { content() } }
                }
            }
        }
    }

    private fun pulseSnapshot(vararg items: ProjectPulseItem) = ProjectPulseSnapshot(
        projectId = items.firstOrNull()?.let { item -> when (item) {
            is ProjectPulseItem.WorkPlan -> item.projectId
            is ProjectPulseItem.Task -> item.projectId
            is ProjectPulseItem.Decision -> item.projectId
            is ProjectPulseItem.Research -> item.projectId
            is ProjectPulseItem.ReviewCard -> item.projectId
        } } ?: 1,
        projectName = "Project",
        sections = listOf(ProjectPulseSection("진행 중", items.toList())),
        generatedAtMillis = 1000,
    )

    private class FakePulseDataSource(
        private val canonical: Map<ProjectReviewSessionPolicy.ProjectReviewTarget, ProjectPulseItem>,
        private val beforeResolve: suspend (request: Int) -> Unit = {},
    ) : ProjectPulseDataSource {
        var resolveCount = 0
        var durableWrites = 0
        var resolveCurrentTargetObserved: ProjectReviewSessionPolicy.ProjectReviewTarget? = null
        override suspend fun project(id: Long) = ProjectEntity(id, "Project", createdAt = 1, updatedAt = 1)
        override suspend fun activePlan(projectId: Long): ProjectWorkPlanEntity? = null
        override suspend fun planSteps(planId: String): List<ProjectWorkPlanStepEntity> = emptyList()
        override suspend fun openTasks(projectId: Long, now: Long, limit: Int): List<ProjectTaskEntity> = emptyList()
        override suspend fun activeDecisions(projectId: Long, limit: Int): List<ProjectDecisionEntity> = emptyList()
        override suspend fun researchCandidates(now: Long, projectId: Long): ResearchRefreshQueueLoadResult =
            ResearchRefreshQueueLoadResult(emptyList(), ResearchRefreshQueueCoverage.COMPLETE)
        override suspend fun projectForConversation(conversationId: Long): Long? = null
        override suspend fun reviewCards(projectId: Long, limit: Int): List<StudyReviewCardEntity> = emptyList()
        override suspend fun current(item: ProjectPulseItem, now: Long): Boolean = true
        override suspend fun resolveCurrentReviewTarget(
            target: ProjectReviewSessionPolicy.ProjectReviewTarget,
            now: Long,
        ): ProjectPulseItem? {
            resolveCount++
            resolveCurrentTargetObserved = target
            beforeResolve(resolveCount)
            return canonical[target]
        }
    }

    private class FakeKnowledgeHealthDataSource(private var row: SavedKnowledgeEntity) : ProjectKnowledgeHealthDataSource {
        val accessChecks = mutableListOf<Boolean>()
        override suspend fun projectItems(projectId: Long, limit: Int): List<SavedKnowledgeEntity> = listOf(row)
        override suspend fun assistantSourceProof(row: SavedKnowledgeEntity) =
            com.projectnuke.fusion.knowledge.ProjectKnowledgeAssistantSourceProof(null, false, null, null, null)
        override suspend fun documentSourceProof(row: SavedKnowledgeEntity) = null
        override suspend fun noteSources(noteId: String, limit: Int): List<SavedKnowledgeNoteSourceEntity> = emptyList()
        override suspend fun noteSourceAccessible(source: SavedKnowledgeNoteSourceEntity, archiveAllowed: Boolean) = true
        override suspend fun savedKnowledge(id: String) = row.takeIf { it.id == id }
        override suspend fun rowAccessible(row: SavedKnowledgeEntity, archiveAllowed: Boolean): Boolean {
            accessChecks += archiveAllowed
            return row.id == this.row.id && row.projectId == this.row.projectId
        }
    }

    private fun knowledge(id: String) = SavedKnowledgeEntity(
        id, "PROJECT", 1, "Health note", "body", "USER_NOTE", null, null, 1, 1,
    )
}
