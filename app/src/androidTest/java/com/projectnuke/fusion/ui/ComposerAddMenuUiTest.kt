package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.ChatInteractionModeScope
import com.projectnuke.fusion.ui.theme.FusionTheme
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ComposerAddMenuUiTest {
    private val compose = createComposeRule()
    @get:Rule val composeHostRules = composeHostTestRules(compose)

    private fun openMenu() = compose.onNodeWithContentDescription("자료와 입력 도구 추가").performClick()
    private fun popupBounds(): Rect {
        val node = compose.onNodeWithTag("composer-add-popup").fetchSemanticsNode()
        return Rect(node.positionOnScreen, node.boundsInRoot.size)
    }

    @Test fun rightSwipeAndTabsKeepGeometryAndPreserveEveryAction() {
        var files = 0
        var cameras = 0
        var dictations = 0
        var voices = 0
        var sends = 0
        var appended: String? = null
        compose.setContentOnResumedTestHost {
            FusionTheme(FusionThemeMode.BLACK) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f),
                    LocalFluidMotionMode provides FluidMotionMode.MINIMAL) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.width(320.dp).align(Alignment.BottomStart)) {
                            ChatInputBar(value = "초안", onValueChange = {}, enabled = true,
                                isGenerating = false, isSubmittingMessage = false, hasAttachments = false,
                                isImportingAttachments = false, onPlusClick = { files++ },
                                onCameraCaptureClick = { cameras++ }, onSendClick = { sends++ }, onStopClick = {},
                                pendingAttachments = emptyList(), attachmentRefreshKey = 0, onRemoveAttachment = {},
                                onAppendQuickPrompt = { appended = it }, quickPromptPresets = listOf("핵심 내용을 간단하게 요약해 줘", "번역해 줘"),
                                onMicClick = { dictations++ }, onVoiceModeClick = { voices++ },
                                interactionModeScope = ChatInteractionModeScope(1, 0))
                        }
                    }
                }
            }
        }
        openMenu()
        compose.onNodeWithText("파일 첨부").assertIsDisplayed()
        val initial = popupBounds()
        compose.onNodeWithTag("composer-add-pages").performTouchInput { swipeRight() }
        compose.onNodeWithText("번역해 줘").assertIsDisplayed()
        compose.onNodeWithText("빠른 입력").assertIsSelected()
        val paged = popupBounds()
        assertEquals(initial.left, paged.left, 1f)
        assertEquals(initial.top, paged.top, 1f)
        assertEquals(initial.width, paged.width, 1f)
        assertEquals(initial.height, paged.height, 1f)
        compose.onNodeWithText("자료·입력 도구").performClick()
        compose.onNodeWithText("파일 첨부").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("composer-add-popup").assertDoesNotExist()
        openMenu()
        compose.onNodeWithText("카메라로 자료 캡처").performClick()
        openMenu()
        compose.onNodeWithText("음성 입력").performClick()
        openMenu()
        compose.onNodeWithText("보이스 모드").performClick()
        openMenu()
        compose.onNodeWithText("빠른 입력").performClick()
        compose.onNodeWithText("번역해 줘").performClick()
        compose.onNodeWithTag("composer-add-popup").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, files)
            assertEquals(1, cameras)
            assertEquals(1, dictations)
            assertEquals(1, voices)
            assertEquals("번역해 줘", appended)
            assertEquals(0, sends)
        }
        openMenu()
        compose.onNodeWithText("파일 첨부").assertIsDisplayed()
    }

    @Test fun scopeAndAvailabilityChangesCloseTransientMenu() {
        var currentScope by mutableStateOf(ChatInteractionModeScope(1, 0))
        var available by mutableStateOf(true)
        var actions = 0
        compose.setContentOnResumedTestHost {
            FusionTheme {
                ChatInputBar(value = "", onValueChange = {}, enabled = available,
                    isGenerating = false, isSubmittingMessage = false, hasAttachments = false,
                    isImportingAttachments = false, onPlusClick = { actions++ }, onSendClick = {},
                    onStopClick = {}, pendingAttachments = emptyList(), attachmentRefreshKey = 0,
                    onRemoveAttachment = {}, onAppendQuickPrompt = { actions++ }, interactionModeScope = currentScope)
            }
        }
        openMenu()
        compose.onNodeWithText("빠른 입력").performClick()
        compose.runOnIdle { currentScope = ChatInteractionModeScope(2, 1) }
        compose.onNodeWithTag("composer-add-popup").assertDoesNotExist()
        openMenu()
        compose.onNodeWithText("파일 첨부").assertIsDisplayed()
        compose.runOnIdle { available = false }
        compose.onNodeWithTag("composer-add-popup").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun studyModeKeepsToolsAndExplainsUnavailableQuickPrompts() {
        var appended = 0
        compose.setContentOnResumedTestHost {
            FusionTheme {
                ChatInputBar(value = "", onValueChange = {}, enabled = true,
                    isGenerating = false, isSubmittingMessage = false, hasAttachments = false,
                    isImportingAttachments = false, onPlusClick = {}, onSendClick = {}, onStopClick = {},
                    pendingAttachments = emptyList(), attachmentRefreshKey = 0, onRemoveAttachment = {},
                    onAppendQuickPrompt = { appended++ }, quickPromptPresets = listOf("요약해 줘"),
                    interactionModeScope = ChatInteractionModeScope(1, 0), interactionMode = ChatInteractionMode.STUDY)
            }
        }
        openMenu()
        compose.onNodeWithText("파일 첨부").assertIsDisplayed()
        compose.onNodeWithText("빠른 입력").performClick()
        compose.onNodeWithText("현재 대화 모드에서는 빠른 입력을 사용할 수 없습니다.").assertIsDisplayed()
        compose.onNodeWithText("요약해 줘").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, appended) }
    }
}
