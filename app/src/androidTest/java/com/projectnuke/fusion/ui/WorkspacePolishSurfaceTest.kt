package com.projectnuke.fusion.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.fusion.chat.*
import com.projectnuke.fusion.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class WorkspacePolishSurfaceTest {
    private val compose = createComposeRule()
    @get:Rule val composeHostRules = composeHostTestRules(compose)
    private fun fixture(mode: FusionThemeMode, content: @Composable () -> Unit) {
        compose.setContentOnResumedTestHost {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f),
                LocalFluidMotionMode provides FluidMotionMode.MINIMAL) {
                FusionTheme(mode) { Surface { Box(Modifier.width(320.dp)) { content() } } }
            }
        }
    }
    private fun screenshot(name: String, title: String) {
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A Dialog is a separate Android window. Capture the actual display, not its activity underneath.
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.cacheDir, "workspace-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    private fun search(mode: FusionThemeMode) {
        val source = object : FusionKnowledgeSource {
            override suspend fun search(query: String) = listOf(KnowledgeCandidate("p:1", KnowledgeKind.PROJECT,
                "긴 한국어 이름의 프로젝트 연구 자료", "관련 내용을 짧게 확인하고 원래 자료로 이동합니다.", "프로젝트",
                KnowledgeTarget.Project(1)))
            override suspend fun accessible(target: KnowledgeTarget) = true
        }
        fixture(mode) { FusionSearchSurface(false, {}, {}, source) }
        compose.onNode(hasSetTextAction()).performTextInput("연구")
        compose.waitUntil(5000) { compose.onAllNodesWithText("긴 한국어 이름의 프로젝트 연구 자료").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("긴 한국어 이름의 프로젝트 연구 자료").assertIsDisplayed()
        compose.onNodeWithContentDescription("검색 닫기").assertIsDisplayed()
        screenshot("search-${mode.name.lowercase()}", "Fusion 검색")
    }
    @Test fun lightSearchKeepsLongLabelsAndActionsReadable() = search(FusionThemeMode.LIGHT)
    @Test fun darkSearchKeepsLongLabelsAndActionsReadable() = search(FusionThemeMode.DARK)
    @Test fun autoThemeUsesDeviceNightConfigurationWithLongLabels() = search(FusionThemeMode.AUTO)
    @Test fun sourceSelectionUsesCheckedSemanticsAndRejectsRemovedSelection() {
        val file = ProjectFileSnapshot(1, "아주 긴 한국어 연구자료 문서 이름.pdf", "application/pdf", "/private")
        val project = ProjectContextSnapshot(7, "", listOf(file))
        var selected: ProjectSourceScope? = null
        fixture(FusionThemeMode.LIGHT) {
            ProjectSourceScopeControl(project, ProjectSourceScope(ProjectSourceScopeKind.SELECTED_FILES, 7, listOf(99)), true) { selected = it }
        }
        compose.onNodeWithText("자료 · 선택한 파일 1개").performClick()
        compose.onNodeWithText("선택한 파일 사용").assertIsNotEnabled()
        compose.onNodeWithText(file.displayName).performScrollTo().performClick().assertIsOn()
        screenshot("scope-light", "사용할 자료")
        compose.onNodeWithText("선택한 파일 사용").performClick()
        compose.runOnIdle { assertEquals(listOf(1L), selected?.fileIds) }
    }
    @Test fun compactIconsAndLongTranslationActionsKeep48DpTargets() {
        fixture(FusionThemeMode.DARK) {
            Column {
                ActionIcon(Icons.Rounded.ContentCopy, "답변 복사") {}
                TranslationCompactAction("입력창에 긴 번역 결과 넣기", {})
                TranslationLanguageChip("한국어", true, {})
                FusionBusyLine()
            }
        }
        compose.onNodeWithContentDescription("답변 복사").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("입력창에 긴 번역 결과 넣기").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("한국어").assertIsSelected().assertHeightIsAtLeast(48.dp)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertExists()
    }
    @Test fun branchTreeKeepsCurrentStateAndNavigationVisibleInDarkTheme() {
        val source = object : BranchMapSource {
            override suspend fun load(id: Long) = listOf(BranchMapNode(1, "처음 시작한 대화", 0, false),
                BranchMapNode(2, "한국어 이름이 아주 긴 현재 대화의 연구 갈래", 1, true))
            override suspend fun accessible(id: Long) = true
        }
        fixture(FusionThemeMode.DARK) { BranchMapSurface(2, false, {}, {}, source) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("현재 대화").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("한국어 이름이 아주 긴 현재 대화의 연구 갈래").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithText("닫기").assertIsDisplayed()
        screenshot("branch-dark", "대화 갈래")
    }
    private fun composer(voice: Boolean, onSend: () -> Unit = {}, onExit: () -> Unit = {}) {
        fixture(FusionThemeMode.LIGHT) {
            ChatInputBar(value = if (voice) "" else "한국어 메시지", onValueChange = {}, enabled = true,
                isGenerating = false, isSubmittingMessage = false, hasAttachments = false, isImportingAttachments = false,
                onPlusClick = {}, onSendClick = onSend, onStopClick = {}, pendingAttachments = emptyList(),
                attachmentRefreshKey = 0, onRemoveAttachment = {}, onAppendQuickPrompt = {},
                interactionModeScope = ChatInteractionModeScope(1, 0), voiceModeActive = voice, onVoiceModeExit = onExit)
        }
    }
    @Test fun narrowComposerKeepsSendAndToolsAccessible() {
        var sent = 0
        composer(false, onSend = { sent++ })
        compose.onNodeWithContentDescription("메시지 보내기").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("자료와 입력 도구 추가").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("빠른 응답 설정 열기").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { assertEquals(1, sent) }
        screenshot("composer-light", "한국어 메시지")
    }
    @Test fun narrowVoiceComposerDoesNotTurnItsPlaceholderIntoAnUnboundedPanel() {
        var exits = 0
        composer(true, onExit = { exits++ })
        val fieldHeightPx = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.height
        assertTrue("voice mode must not size the composer from an unbounded placeholder",
            fieldHeightPx <= with(compose.density) { 180.dp.toPx() })
        compose.onNodeWithContentDescription("자료와 입력 도구 추가").performClick()
        compose.onNodeWithText("음성 입력").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("보이스 모드 종료").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, exits) }
        screenshot("voice-light", "보이스 모드 사용 중 · 종료")
    }
}
