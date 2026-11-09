package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.research.ResearchRefreshDiffPolicy
import com.projectnuke.fusion.ui.theme.FusionTheme
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResearchRefreshDiffSurfaceTest {
    private val compose = createComposeRule()
    @get:Rule val hostRules = composeHostTestRules(compose)

    private fun render(mode: FusionThemeMode, onDismiss: () -> Unit) {
        val longText = "이번 결과에서 새로 언급된 한국어 연구 자료의 설명입니다. ".repeat(15)
        val snapshot = ResearchRefreshDiffSnapshot(
            sourceConversationId = 1, sourceMessageId = 2, refreshedConversationId = 3, refreshedMessageId = 4,
            sourceConversationTitle = "이전 연구 제목", refreshedConversationTitle = "이번 연구 제목",
            previousVisibleAnswer = "이전 결과", currentVisibleAnswer = longText,
            result = ResearchRefreshDiffPolicy.Result(
                newlyMentioned = listOf(longText.take(500)), notFoundPrevious = listOf("이전 결과의 긴 한국어 내용을 다시 찾기 어렵습니다."),
                numericCandidates = listOf(ResearchRefreshDiffPolicy.NumericChange("12 GB", "16 GB")),
                sources = ResearchRefreshDiffPolicy.SourceChanges(listOf("새 출처"), listOf("이전 출처"), listOf("계속된 출처")),
            ),
        )
        compose.setContentOnResumedTestHost {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                FusionTheme(mode) { Surface { Box(Modifier.width(320.dp)) {
                    ResearchRefreshDiffSurface(snapshot, false, false, onDismiss)
                } } }
            }
        }
    }

    @Test fun compactLargeTextKeepsLongDiffReadableAndDismissActionReachable() {
        var dismissCount = 0
        render(FusionThemeMode.BLACK) { dismissCount++ }
        compose.onNodeWithText("새로 언급된 내용").assertIsDisplayed()
        compose.onNodeWithText("Research 변화 비교").assertIsDisplayed()
        compose.onNodeWithText("닫기").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, dismissCount) }
    }

    @Test fun lightThemeShowsNeutralNumericCandidateLanguage() {
        render(FusionThemeMode.LIGHT) {}
        compose.onNodeWithText("수치·날짜 변화 후보").assertIsDisplayed()
        compose.onNodeWithText("이전: 12 GB\n현재: 16 GB").assertIsDisplayed()
        compose.onNodeWithText("이전 답변이 틀림").assertDoesNotExist()
    }
}
