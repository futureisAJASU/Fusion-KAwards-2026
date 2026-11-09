package com.projectnuke.fusion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class TranslationSelectionSemanticsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun languageSelectionUsesTheActionNode() {
        compose.setContent {
            var selected by remember { mutableStateOf(false) }
            MaterialTheme { TranslationLanguageChip("한국어", selected) { selected = !selected } }
        }
        compose.onNodeWithText("한국어").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithText("한국어").performClick().assertIsNotSelected()
    }
}
