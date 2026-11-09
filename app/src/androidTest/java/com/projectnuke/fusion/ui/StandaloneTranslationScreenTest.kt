package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StandaloneTranslationScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun inlineCompactActionDoesNotCollapseWeightedHeaderText() {
        compose.setContent {
            MaterialTheme {
                Row(modifier = Modifier.width(240.dp).testTag("translation-header")) {
                    Column(modifier = Modifier.weight(1f).testTag("translation-title")) {
                        androidx.compose.material3.Text("번역")
                        androidx.compose.material3.Text("기기에서 처리")
                    }
                    TranslationCompactAction(
                        label = "닫기",
                        onClick = {},
                        modifier = Modifier.testTag("translation-close"),
                    )
                }
            }
        }

        val titleWidth = compose.onAllNodesWithTag("translation-title").fetchSemanticsNodes().single().boundsInRoot.width
        val actionWidth = compose.onAllNodesWithTag("translation-close").fetchSemanticsNodes().single().boundsInRoot.width
        assertTrue("weighted title must remain wider than compact action", titleWidth > actionWidth * 2f)
        compose.onNodeWithText("번역").assertIsDisplayed()
    }

    @Test
    fun explicitFullWidthCallerStillFillsParent() {
        compose.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(240.dp).testTag("translation-full-width-parent")) {
                    TranslationCompactAction(
                        label = "번역하기",
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().testTag("translation-full-width"),
                    )
                }
            }
        }

        val parentWidth = compose.onAllNodesWithTag("translation-full-width-parent").fetchSemanticsNodes().single().boundsInRoot.width
        val actionWidth = compose.onAllNodesWithTag("translation-full-width").fetchSemanticsNodes().single().boundsInRoot.width
        assertTrue("explicit full-width caller must fill its parent", kotlin.math.abs(parentWidth - actionWidth) < 1f)
    }
}
