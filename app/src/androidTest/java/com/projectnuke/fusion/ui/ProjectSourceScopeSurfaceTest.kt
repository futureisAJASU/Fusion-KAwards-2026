package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.chat.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProjectSourceScopeSurfaceTest {
    private val compose = createComposeRule()
    @get:Rule val composeHostRules = composeHostTestRules(compose)
    @Test fun selectedSourceRemainsUsableAtLargeFontAndNarrowWidth() {
        var selected = ProjectSourceScope()
        val p = ProjectContextSnapshot(7, "", listOf(ProjectFileSnapshot(1,
            "아주 긴 한국어 프로젝트 연구자료 문서 이름입니다.pdf", "application/pdf", "/private")))
        compose.setContentOnResumedTestHost { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Box(Modifier.width(320.dp)) { ProjectSourceScopeControl(p, ProjectSourceScope(), true) { selected = it } }
            }
        } }
        compose.onNodeWithText("자료 · 전체 자료").performClick()
        compose.onNodeWithText(p.files[0].displayName).performScrollTo().performClick()
        compose.onNodeWithText("선택한 파일 사용").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(1L), selected.fileIds) }
    }
}
