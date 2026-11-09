package com.projectnuke.fusion.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerAddMenuGestureTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun pager_swipeLeft_advancesPage_swipeRight_returnsPage() {
        composeTestRule.setContent {
            ComposerAddMenu(
                enabled = true,
                quickPromptsAllowed = true,
                quickPromptPresets = listOf("빠른 입력 1", "빠른 입력 2"),
                dictationActive = false,
                voiceModeActive = false,
                onCamera = {},
                onFile = {},
                onDictation = {},
                onVoiceMode = {},
                onQuickPrompt = {},
            )
        }

        // Starts on page 0 (자료·입력 도구) in LTR tab order.
        composeTestRule.onNodeWithText("자료·입력 도구").assertIsSelected()
        composeTestRule.onNodeWithText("빠른 입력").assertIsNotSelected()

        // Swipe LEFT (finger moves right -> left): page 0 -> page 1.
        composeTestRule.onNodeWithTag("composer-add-pages").performTouchInput {
            swipe(
                start = Offset(width * 0.85f, centerY),
                end = Offset(width * 0.15f, centerY),
                durationMillis = 150,
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("빠른 입력").assertIsSelected()
        composeTestRule.onNodeWithText("자료·입력 도구").assertIsNotSelected()

        // Swipe RIGHT (finger moves left -> right): page 1 -> page 0.
        composeTestRule.onNodeWithTag("composer-add-pages").performTouchInput {
            swipe(
                start = Offset(width * 0.15f, centerY),
                end = Offset(width * 0.85f, centerY),
                durationMillis = 150,
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("자료·입력 도구").assertIsSelected()
        composeTestRule.onNodeWithText("빠른 입력").assertIsNotSelected()
    }

    @Test
    fun direct_tab_tap_selects_corresponding_page() {
        composeTestRule.setContent {
            ComposerAddMenu(
                enabled = true,
                quickPromptsAllowed = true,
                quickPromptPresets = emptyList(),
                dictationActive = false,
                voiceModeActive = false,
                onCamera = {},
                onFile = {},
                onDictation = {},
                onVoiceMode = {},
                onQuickPrompt = {},
            )
        }

        composeTestRule.onNodeWithText("자료·입력 도구").assertIsSelected()
        composeTestRule.onNodeWithText("빠른 입력").assertIsNotSelected()

        // Direct tab tap selects page 1.
        composeTestRule.onNodeWithText("빠른 입력").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("빠른 입력").assertIsSelected()
        composeTestRule.onNodeWithText("자료·입력 도구").assertIsNotSelected()
    }
}
