package com.projectnuke.fusion.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.fusion.chat.ChatInteractionModeScope
import com.projectnuke.fusion.data.ProjectDecisionEntity
import com.projectnuke.fusion.model.*
import com.projectnuke.fusion.ui.theme.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FusionPopupUiTest {
    private val compose = createComposeRule()
    @get:Rule val composeHostRules = composeHostTestRules(compose)

    @Test fun blackLargeTextDecisionRowKeepsItsAccessibleTarget() {
        var opened = 0
        val decision = ProjectDecisionEntity(
            id = "decision-ui",
            projectId = 7L,
            title = "C2 INT8 block32를 최종 verifier로 사용",
            rationale = "",
            status = "ACTIVE",
            createdAt = 1L,
            updatedAt = 1L,
        )
        compose.setContentOnResumedTestHost {
            FusionTheme(FusionThemeMode.BLACK) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f),
                    LocalFluidMotionMode provides FluidMotionMode.MINIMAL) {
                    DecisionRow(decision, onClick = { opened++ }, onReplace = {})
                }
            }
        }
        val row = compose.onNodeWithText(decision.title)
        row.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun anchoredMenuDismissesOnBackWithoutCallingAnAction() {
        var chosen = 0
        compose.setContentOnResumedTestHost {
            FusionTheme(FusionThemeMode.BLACK) {
                CompositionLocalProvider(LocalFluidMotionMode provides FluidMotionMode.MINIMAL) {
                    var expanded by remember { mutableStateOf(false) }
                    val anchor = rememberFusionPopupAnchor()
                    Box(Modifier.fillMaxSize()) {
                        TextButton(onClick = { expanded = true }, modifier = Modifier.fusionPopupAnchor(anchor)) { Text("모델 선택") }
                        FusionDropdownMenu(expanded, { expanded = false }, anchor = anchor) {
                            DropdownMenuItem(text = { Text("현재 모델") }, onClick = { chosen++; expanded = false })
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("모델 선택").performClick()
        compose.onNodeWithText("현재 모델").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText("현재 모델").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, chosen) }
        compose.onNodeWithText("모델 선택").performClick()
        compose.onNodeWithText("현재 모델").performClick()
        compose.runOnIdle { assertEquals(1, chosen) }
    }

    @Test fun blackLargeTextComposerKeepsTuneAndSendAndOpensAtTune() {
        var applied = 0
        var workflowApplied = 0
        compose.setContentOnResumedTestHost {
            FusionTheme(FusionThemeMode.BLACK) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f),
                    LocalFluidMotionMode provides FluidMotionMode.MINIMAL) {
                    val anchor = rememberFusionPopupAnchor()
                    var expanded by remember { mutableStateOf(false) }
                    var values by remember { mutableStateOf(LocalSettingsEditValues(GenerationSettings(), true,
                        LocalContextMode.AUTO, 4096, LocalCompactPolicy())) }
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.width(320.dp)) {
                            Spacer(Modifier.height(220.dp))
                            ChatInputBar(value = "한국어 메시지", onValueChange = {}, enabled = true,
                                isGenerating = false, isSubmittingMessage = false, hasAttachments = false, isImportingAttachments = false,
                                onPlusClick = {}, onSendClick = {}, onStopClick = {}, pendingAttachments = emptyList(),
                                attachmentRefreshKey = 0, onRemoveAttachment = {}, onAppendQuickPrompt = {},
                                interactionModeScope = ChatInteractionModeScope(1, 0), quickSettingsAnchor = anchor,
                                onToggleQuickSettings = { expanded = !expanded })
                        }
                        FusionDropdownMenu(expanded, { expanded = false }, anchor = anchor, preferredHeight = 480.dp,
                            modifier = Modifier.testTag("tune-popup")) {
                            ComposerQuickSettingsPanel(currentValues = values, modelFamilyKey = "qwen",
                                supportsReasoning = true, maxSupportedOutputTokens = 8192,
                                target = ComposerQuickSettingsTarget("test-model", null, "Test model"),
                                generationStatus = ComposerQuickGenerationStatus.IDLE, reasoningActive = false,
                                selectedWorkflow = null, hasProjectSources = false, canApplyWorkflow = true,
                                onSelectWorkflow = { workflowApplied++ },
                                onApply = { _, next -> values = next; applied++ }, onOpenFullSettings = {})
                        }
                    }
                }
            }
        }
        val tune = compose.onNodeWithContentDescription("빠른 응답 설정 열기")
        tune.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("메시지 보내기").assertIsDisplayed()
        val tuneNode = tune.fetchSemanticsNode()
        val tuneBounds = Rect(tuneNode.positionOnScreen, tuneNode.boundsInRoot.size)
        tune.performClick()
        compose.onNodeWithText("빠른 응답 설정").assertIsDisplayed()
        val popupNode = compose.onNodeWithTag("tune-popup").fetchSemanticsNode()
        val popup = Rect(popupNode.positionOnScreen, popupNode.boundsInRoot.size)
        val initialBitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "fusion-tune-black-initial.png")
            .outputStream().use { initialBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        initialBitmap.recycle()
        val gap = maxOf(0f, popup.top - tuneBounds.bottom, tuneBounds.top - popup.bottom)
        assertTrue("popup must stay near Tune: popup=$popup trigger=$tuneBounds gap=$gap",
            gap <= with(compose.density) { 12.dp.toPx() } + 1f)
        compose.onNodeWithText("워크플로 선택").performScrollTo().performClick()
        val expandedPopupNode = compose.onNodeWithTag("tune-popup").fetchSemanticsNode()
        val expandedPopup = Rect(expandedPopupNode.positionOnScreen, expandedPopupNode.boundsInRoot.size)
        assertEquals(popup.top, expandedPopup.top, 1f)
        assertEquals(popup.height, expandedPopup.height, 1f)
        compose.onNodeWithText("논문 비교").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, workflowApplied) }
        compose.onNodeWithText("공부").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, applied) }
        compose.onNodeWithText("자세히 · 2048").performScrollTo().assertIsDisplayed()
        val detailedHeight = compose.onNodeWithText("자세히 · 2048").fetchSemanticsNode().boundsInRoot.height
        val veryDetailedHeight = compose.onNodeWithText("매우 자세히 · 4096").fetchSemanticsNode().boundsInRoot.height
        assertEquals(detailedHeight, veryDetailedHeight, 1f)
        compose.onNodeWithText("추론").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("빠른 응답 설정").performScrollTo().assertIsDisplayed()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.cacheDir, "fusion-tune-black.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
