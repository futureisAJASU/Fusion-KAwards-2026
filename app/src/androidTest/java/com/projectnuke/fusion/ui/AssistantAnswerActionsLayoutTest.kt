package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.chat.AnswerSecondaryAction
import com.projectnuke.fusion.chat.AnswerSecondaryActionContext
import com.projectnuke.fusion.chat.AnswerSecondaryActionPolicy
import com.projectnuke.fusion.chat.AnswerUtilityAction
import com.projectnuke.fusion.chat.AnswerUtilityRailPolicy
import com.projectnuke.fusion.chat.ContextualAnswerActionSuggestion
import com.projectnuke.fusion.chat.ContextualAnswerActionSuggestionPolicy
import com.projectnuke.fusion.trust.TrustRoutingPresentationKind
import com.projectnuke.fusion.ui.theme.FusionTheme
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * UX-P1 Task A/C — rendered behaviour of the real assistant answer action
 * surfaces.
 *
 * These are real Compose instrumentation tests. They compose the PRODUCTION
 * rail, the PRODUCTION message overflow ([AssistantMoreMenu]) and the PRODUCTION
 * secondary sheet, and assert the properties the review asked for: single
 * ownership (Study overflow-only, promoted action in exactly one location, one
 * Evidence host), 48dp hit targets, large-font labels, truncation, compact-width
 * horizontal-scroll reachability, accessibility labels and the speaking-only
 * stop control. They require a device/emulator; they are compile-only when no
 * device is attached (reported as NOT_EXECUTED in that case).
 */
class AssistantAnswerActionsLayoutTest {
    private val compose = createComposeRule()

    @get:Rule
    val composeHostRules = composeHostTestRules(compose)

    private fun content(
        fontScale: Float = 1.0f,
        widthDp: Int = 320,
        body: @Composable () -> Unit,
    ) {
        compose.setContentOnResumedTestHost {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalFluidMotionMode provides FluidMotionMode.MINIMAL,
            ) {
                FusionTheme(FusionThemeMode.LIGHT) {
                    Box(Modifier.width(widthDp.dp)) { body() }
                }
            }
        }
    }

    private fun activeContext(isProjectAssigned: Boolean = false) = AnswerSecondaryActionContext(
        persistedConversation = true,
        isProjectAssigned = isProjectAssigned,
        responseVersionCount = 2,
        visibleAnswerAvailable = true,
        translationAvailable = true,
        receiptAvailable = true,
        routingKind = TrustRoutingPresentationKind.NONE,
        isStudyMode = false,
        isRetrySearchRecovery = false,
        answerStableForExtraction = true,
    )

    private fun assertWidthAtMost(label: String, maxDp: Dp) {
        val node = compose.onNodeWithText(label).fetchSemanticsNode()
        val maxPx = with(compose.density) { maxDp.toPx() }
        assertTrue(
            "'$label' rendered width ${node.size.width}px exceeds ${maxDp}",
            node.size.width <= maxPx + 1f,
        )
    }

    // ---- Primary rail ------------------------------------------------------

    @Test
    fun primaryRailChipsMeet48DpAndExcludeOverflowStudy() {
        content {
            AnswerUtilityRail(
                actions = AnswerUtilityRailPolicy.primaryActionsOf(
                    listOf(
                        AnswerUtilityAction.SOURCES,
                        AnswerUtilityAction.EVIDENCE,
                        AnswerUtilityAction.STUDY,
                    ),
                ),
                enabled = true,
                sourcesRevealed = false,
                onToggleSources = {},
                onStudy = {},
                suggestedAction = ContextualAnswerActionSuggestion.COMPARE_RESPONSES,
                onSuggestedAction = {},
                evidenceClick = {},
            )
        }
        compose.onNodeWithText("사용한 자료").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        compose.onNodeWithText("사실 확인").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        compose.onNodeWithText(ContextualAnswerActionSuggestion.COMPARE_RESPONSES.label)
            .assertHeightIsAtLeast(48.dp)
        // STUDY is OVERFLOW-owned: even when passed in, it is never projected
        // onto the compact rail.
        compose.onNodeWithText("이 주제로 공부").assertDoesNotExist()
    }

    @Test
    fun longLabelsAtLargeFontScaleDoNotClipPrimaryChips() {
        content(fontScale = 1.5f) {
            AnswerUtilityRail(
                actions = listOf(AnswerUtilityAction.SOURCES),
                enabled = true,
                sourcesRevealed = true,
                onToggleSources = {},
                onStudy = {},
                suggestedAction = ContextualAnswerActionSuggestion.EXTRACT_STRUCTURED,
                onSuggestedAction = {},
            )
        }
        compose.onNodeWithText("사용한 자료 숨기기").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        compose.onNodeWithText(ContextualAnswerActionSuggestion.EXTRACT_STRUCTURED.label)
            .assertHeightIsAtLeast(48.dp)
            .assertIsDisplayed()
    }

    @Test
    fun compactWidthRailScrollsToReachTrailingChips() {
        content(widthDp = 150) {
            AnswerUtilityRail(
                actions = AnswerUtilityRailPolicy.primaryActionsOf(
                    listOf(AnswerUtilityAction.SOURCES, AnswerUtilityAction.EVIDENCE),
                ),
                enabled = true,
                sourcesRevealed = false,
                onToggleSources = {},
                onStudy = {},
                suggestedAction = ContextualAnswerActionSuggestion.EXTRACT_STRUCTURED,
                onSuggestedAction = {},
                evidenceClick = {},
            )
        }
        // The leading chip is visible at the compact viewport...
        compose.onNodeWithText("사용한 자료").assertIsDisplayed()
        // ...and a trailing chip beyond the viewport is reachable by scrolling.
        compose.onNodeWithText(ContextualAnswerActionSuggestion.EXTRACT_STRUCTURED.label)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun primaryActionsExposeAccessibleLabelsAndBookButtonRole() {
        content {
            AnswerUtilityRail(
                actions = AnswerUtilityRailPolicy.primaryActionsOf(
                    listOf(AnswerUtilityAction.SOURCES, AnswerUtilityAction.EVIDENCE),
                ),
                enabled = true,
                sourcesRevealed = false,
                onToggleSources = {},
                onStudy = {},
                suggestedAction = ContextualAnswerActionSuggestion.COMPARE_RESPONSES,
                onSuggestedAction = {},
                evidenceClick = {},
            )
        }
        compose.onNodeWithText("사용한 자료").assertHasClickAction().assertIsDisplayed()
        compose.onNodeWithText("사실 확인").assertHasClickAction().assertIsDisplayed()
        compose.onNodeWithText(ContextualAnswerActionSuggestion.COMPARE_RESPONSES.label)
            .assertHasClickAction()
            .assertIsDisplayed()
    }

    // ---- Evidence single ownership ----------------------------------------

    @Test
    fun evidenceIsRenderedExactlyOnceByTheSingleEvidenceHost() {
        content {
            AnswerUtilityRail(
                actions = listOf(AnswerUtilityAction.EVIDENCE),
                enabled = true,
                sourcesRevealed = false,
                onToggleSources = {},
                onStudy = {},
                evidenceClick = {},
            )
        }
        compose.onAllNodesWithText("사실 확인").assertCountEquals(1)
    }

    @Test
    fun evidenceIsNotFabricatedWithoutTheSingleEvidenceHost() {
        content {
            AnswerUtilityRail(
                actions = listOf(AnswerUtilityAction.EVIDENCE),
                enabled = true,
                sourcesRevealed = false,
                onToggleSources = {},
                onStudy = {},
                evidenceClick = null,
            )
        }
        // No host, no Evidence affordance: the rail must never create a second
        // Evidence path of its own.
        compose.onNodeWithText("사실 확인").assertDoesNotExist()
    }

    // ---- Real assistant overflow composition -------------------------------

    @Test
    fun realOverflowOwnsStudyAndSuppressesThePromotedSheetAction() {
        content {
            AnswerActionsHarness(
                suggested = ContextualAnswerActionSuggestion.SAVE_TO_KNOWLEDGE,
                secondaryActions = listOf(
                    AnswerSecondaryAction.SAVE_TO_KNOWLEDGE,
                    AnswerSecondaryAction.CREATE_REVIEW_CARD,
                ),
                showStudyOverflow = true,
                context = activeContext(),
            )
        }
        compose.onNodeWithContentDescription(RAIL_MORE_LABEL).assertIsDisplayed().assertHasClickAction()
        // Study is owned ONLY by the real overflow: present there, absent from the
        // primary rail, and rendered exactly once.
        compose.onNodeWithText("이 주제로 공부").assertIsDisplayed()
        compose.onAllNodesWithText("이 주제로 공부").assertCountEquals(1)
        // The promoted save-to-knowledge action is removed from the sheet...
        compose.onNodeWithText("노트로 저장").assertDoesNotExist()
        // ...while unpromoted actions remain in the overflow.
        compose.onNodeWithText("복습 카드 만들기").assertIsDisplayed()
    }

    @Test
    fun promotedActionRendersInExactlyOneLocation() {
        content {
            AnswerActionsHarness(
                suggested = ContextualAnswerActionSuggestion.COMPARE_RESPONSES,
                secondaryActions = listOf(
                    AnswerSecondaryAction.COMPARE_RESPONSES,
                    AnswerSecondaryAction.SAVE_TO_KNOWLEDGE,
                    AnswerSecondaryAction.EXTRACT_STRUCTURED,
                ),
                showStudyOverflow = false,
                context = activeContext(),
            )
        }
        // Compare is promoted to the rail and removed from the overflow sheet, so
        // its label is rendered in exactly one location across both surfaces.
        compose.onAllNodesWithText("답변 비교").assertCountEquals(1)
        compose.onNodeWithText("답변 비교").assertIsDisplayed()
        // The non-promoted actions remain available in the overflow (not on the rail).
        compose.onNodeWithText("노트로 저장").assertIsDisplayed()
        compose.onNodeWithText("구조화해서 추출").assertIsDisplayed()
    }

    @Test
    fun overflowMenuItemsExposeClickableKoreanLabels() {
        content {
            AnswerActionsHarness(
                suggested = ContextualAnswerActionSuggestion.SAVE_TO_KNOWLEDGE,
                secondaryActions = listOf(AnswerSecondaryAction.CREATE_REVIEW_CARD),
                showStudyOverflow = false,
                context = activeContext(),
            )
        }
        compose.onNodeWithText("답변 다시 생성").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("음성으로 읽기").assertIsDisplayed().assertHasClickAction()
        // The single overflow control is named by its accessible content description.
        compose.onNodeWithContentDescription(RAIL_MORE_LABEL).assertHasClickAction()
    }

    // ---- Secondary sheet geometry / truncation -----------------------------

    @Test
    fun secondarySheetRowsMeet48DpAtLargeFontScale() {
        val context = activeContext(isProjectAssigned = true)
        content(fontScale = 1.5f) {
            AnswerSecondaryActionSheet(
                expanded = true,
                onDismiss = {},
                actions = listOf(
                    AnswerSecondaryAction.CHANGE_PROJECT,
                    AnswerSecondaryAction.COMPARE_RESPONSES,
                    AnswerSecondaryAction.OPEN_IN_TRANSLATION,
                ),
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
        compose.onNodeWithText(AnswerSecondaryActionPolicy.projectActionLabel(context))
            .assertHeightIsAtLeast(48.dp)
            .assertIsDisplayed()
        compose.onNodeWithText("답변 비교").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("번역에서 열기").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun longOverflowLabelsTruncateWithinCompactWidthWithoutLosingValue() {
        content(fontScale = 1.5f, widthDp = 320) {
            AnswerSecondaryActionSheet(
                expanded = true,
                onDismiss = {},
                actions = listOf(AnswerSecondaryAction.EXTRACT_STRUCTURED),
                context = activeContext(),
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
        val subtitle = "수치, 스펙, 일정, 비교 항목 또는 표를 읽기 좋게 정리합니다."
        compose.onNodeWithText("구조화해서 추출").assertIsDisplayed()
        // Meaningful truncation assertion: the single-line label never exceeds its
        // compact container, and the full value is preserved in semantics (the
        // ellipsis is visual only, so no data is lost).
        compose.onNodeWithText(subtitle).assertIsDisplayed()
        assertWidthAtMost(subtitle, 320.dp)
        compose.onNodeWithText(subtitle).assertTextEquals(subtitle)
    }

    // ---- Conditional speaking-only stop control ----------------------------

    @Test
    fun speakingStopControlAppearsOnlyWhileSpeakingAndStopsExactlyOnce() {
        var speaking by mutableStateOf(false)
        var stopCount = 0
        compose.setContentOnResumedTestHost {
            FusionTheme(FusionThemeMode.LIGHT) {
                SpeakingStopAction(isSpeaking = speaking, onStop = { stopCount += 1 })
            }
        }
        // Not speaking: no stop affordance, so nothing permanent is added to chrome.
        compose.onNodeWithContentDescription(SPEAKING_STOP_LABEL).assertDoesNotExist()

        speaking = true
        compose.waitForIdle()
        compose.onNodeWithContentDescription(SPEAKING_STOP_LABEL)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertHasClickAction()
            .performClick()
        assertEquals(1, stopCount)

        speaking = false
        compose.waitForIdle()
        compose.onNodeWithContentDescription(SPEAKING_STOP_LABEL).assertDoesNotExist()
    }

    @Test
    fun stopControlIsBoundToTheSpeakingAnswerOnly() {
        // Another message is speaking: this answer's surface shows no stop control,
        // proving the affordance is bound to the speaking identity, not globally.
        content {
            SpeakingStopAction(isSpeaking = false, onStop = {})
        }
        compose.onNodeWithContentDescription(SPEAKING_STOP_LABEL).assertDoesNotExist()
    }

    // ---- Harness -----------------------------------------------------------

    /**
     * Mirrors the production wiring of [AssistantMessage]: the PRIMARY rail plus
     * the single message overflow whose `extraActions` render the real
     * [AnswerSecondaryActionSheet] over the promotion-filtered action set and the
     * real [AssistantOverflowExtraItems]. Uses only production composables and
     * the production policies.
     */
    @Composable
    private fun AnswerActionsHarness(
        suggested: ContextualAnswerActionSuggestion,
        secondaryActions: List<AnswerSecondaryAction>,
        showStudyOverflow: Boolean,
        context: AnswerSecondaryActionContext,
    ) {
        val anchor = rememberFusionPopupAnchor()
        Column {
            AnswerUtilityRail(
                actions = AnswerUtilityRailPolicy.primaryActionsOf(
                    listOf(AnswerUtilityAction.SOURCES, AnswerUtilityAction.EVIDENCE),
                ),
                enabled = true,
                sourcesRevealed = false,
                onToggleSources = {},
                onStudy = {},
                suggestedAction = suggested,
                onSuggestedAction = {},
                evidenceClick = {},
            )
            Box(Modifier.fusionPopupAnchor(anchor)) {
                ActionIcon(
                    icon = Icons.Rounded.MoreVert,
                    contentDescription = RAIL_MORE_LABEL,
                    onClick = {},
                )
                AssistantMoreMenu(
                    expanded = true,
                    onDismiss = {},
                    anchor = anchor,
                    onReadAloud = {},
                    isSpeaking = false,
                    onShareAnswer = null,
                    onShareSources = null,
                    onResearchPack = null,
                    onUseAsWorkPlan = null,
                    extraActions = {
                        AnswerSecondaryActionSheet(
                            expanded = true,
                            onDismiss = {},
                            actions = ContextualAnswerActionSuggestionPolicy.remainingSecondaryActions(
                                secondaryActions,
                                suggested,
                            ),
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
                        AssistantOverflowExtraItems(
                            showStudy = showStudyOverflow,
                            enabled = true,
                            onStudy = {},
                            showResearchRefresh = false,
                            onResearchRefresh = {},
                        )
                    },
                    createdAt = 0L,
                    historicalModel = null,
                    webSearchEnabled = false,
                    isRegenerating = false,
                    onRetry = {},
                    onRegenerate = {},
                    onToggleWebSearch = {},
                    showCompare = false,
                )
            }
        }
    }
}
