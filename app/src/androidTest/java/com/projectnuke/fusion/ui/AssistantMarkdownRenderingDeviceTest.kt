package com.projectnuke.fusion.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.projectnuke.fusion.ui.theme.FusionTheme
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import org.junit.Rule
import org.junit.Test

class AssistantMarkdownRenderingDeviceTest {
    private val compose = createComposeRule()
    @get:Rule val composeHostRules = composeHostTestRules(compose)

    @Test
    fun markdownStructuresRenderVisibleContentOnDevice() {
        compose.setContentOnResumedTestHost {
            FusionTheme(FusionThemeMode.LIGHT) {
                Surface {
                    AssistantMarkdownContent(
                        visibleAnswer = "```x```TAIL\n\n    y = 1\n\na\\*b\\*c\n\n| Name | Value |\n| --- | --- |\n| A | B |",
                    )
                }
            }
        }

        compose.onNodeWithText("TAIL").assertExists()
        compose.onNodeWithText("y = 1").assertExists()
        compose.onNodeWithText("a*b*c").assertExists()
        compose.onNodeWithText("Name").assertExists()
        compose.onNodeWithText("B").assertExists()
    }
}
