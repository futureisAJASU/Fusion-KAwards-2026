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
import com.projectnuke.fusion.knowledge.KnowledgeHygieneCandidate
import com.projectnuke.fusion.knowledge.KnowledgeHygieneReview
import com.projectnuke.fusion.ui.theme.FusionTheme
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnowledgeHygieneSurfaceTest {
    private val compose = createComposeRule()
    @get:Rule val hostRules = composeHostTestRules(compose)

    private fun render(mode: FusionThemeMode, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
        val review = KnowledgeHygieneReview(
            sourceId = "source", sourceTitle = "현재 항목",
            provenanceObservations = listOf("원본 답변에 연결됨", "웹 출처 정보 있음"),
            candidates = listOf(KnowledgeHygieneCandidate("candidate", "아주 긴 한국어 제목의 저장 지식 후보", "일부 내용이 겹침")),
        )
        compose.setContentOnResumedTestHost {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                FusionTheme(mode) { Surface { Box(Modifier.width(320.dp)) {
                    KnowledgeHygieneSurface(review, false, false, false, onOpen, onDismiss)
                } } }
            }
        }
    }

    @Test fun compactLargeTextActionsRemainReachableAndDismissDoesNotOpenCandidate() {
        var openCount = 0
        var dismissCount = 0
        render(FusionThemeMode.BLACK, { openCount++ }, { dismissCount++ })
        compose.onNodeWithText("아주 긴 한국어 제목의 저장 지식 후보").assertIsDisplayed()
        compose.onNodeWithText("열기").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("닫기").assertIsDisplayed()
        compose.onNodeWithText("닫기").performClick()
        compose.runOnIdle {
            assertEquals(0, openCount)
            assertEquals(1, dismissCount)
        }
    }

    @Test fun lightThemeCandidateActionOpensOnlyItsExactId() {
        var opened: String? = null
        render(FusionThemeMode.LIGHT, { opened = it }, {})
        compose.onNodeWithText("열기").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("candidate", opened) }
    }
}
