package com.projectnuke.fusion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AutoModelSelectionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun autoIsExplicitAndModelPickerDoesNotTransmit() {
        var picker = 0
        compose.setContent { var auto by remember { mutableStateOf(false) }; MaterialTheme {
            AutoModelSelectionControl(auto, true, if (auto) "자동 선택 · 로컬 모델" else null, { auto = it }, { picker++ })
        } }
        compose.onNodeWithText("모델 · 직접 선택").performClick()
        compose.onNodeWithText("자동 선택 · 로컬 우선").performClick()
        compose.onNodeWithText("모델 · 자동 선택").assertIsDisplayed()
        compose.onNodeWithText("모델 · 자동 선택").performClick()
        compose.onNodeWithText("모델 선택").performClick()
        compose.runOnIdle { assertEquals(1, picker) }
    }
}
