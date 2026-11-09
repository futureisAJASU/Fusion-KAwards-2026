package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.chat.*
import com.projectnuke.fusion.ui.theme.FusionTheme
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Real dialog, synthetic immutable answers; no evidence or response store dependency. */
@RunWith(Parameterized::class)
internal class ResponseVersionCompareUiTest(private val width: Int, private val scale: Float, private val theme: FusionThemeMode) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}dp-{1}x-{2}")
        fun configurations(): List<Array<Any>> = listOf(
            arrayOf(300,1f,FusionThemeMode.LIGHT), arrayOf(360,1.5f,FusionThemeMode.DARK),
            arrayOf(300,2f,FusionThemeMode.BLACK), arrayOf(640,2f,FusionThemeMode.DARK))
    }
    private val compose = createComposeRule()
    @get:Rule val rules = composeHostTestRules(compose)
    private val a = "# 한국어 English\n동일한 문장입니다.\n" + "현재 내용 😀 한국어와 English가 함께 있는 긴 문장입니다. ".repeat(30) + "\n"
    private val b = "# 한국어 English\n동일한 문장입니다.\n" + "비교 내용 😃 한국어와 English가 함께 있는 긴 문장입니다. ".repeat(30) + "\n"
    private var copied: String? = null
    private var dismissed = false
    private fun show(identical: Boolean = false, invalid: Boolean = false, oversized: Boolean = false) {
        compose.setContentOnResumedTestHost {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,scale),
                LocalFluidMotionMode provides FluidMotionMode.MINIMAL) {
                FusionTheme(theme) {
                    var s by remember { mutableStateOf(ResponseVersionComparePolicy.snapshot(listOf(
                        ResponseVersionCompareEntry(1,1,if(identical)a else b), ResponseVersionCompareEntry(2,2,"세 번째 English 답변"),
                        ResponseVersionCompareEntry(3,3,if(oversized) "큰 답변 ".repeat(30_000) else a)),3)!!.let { if(invalid) it.copy(comparisonMessageId=3) else it }) }
                    var open by remember { mutableStateOf(true) }
                    Box(Modifier.width(width.dp)) {
                        if (open)
                        ResponseVersionCompareDialog(s,{ ResponseVersionComparePolicy.select(s,it)?.let { next -> s=next } },
                            { copied=it },{ dismissed=true; open=false }, modifier = Modifier.width(width.dp))
                    }
                }
            }
        }
    }
    @Test fun initialSummaryDisclosureAndFullCopy() {
        show()
        compose.onNodeWithContentDescription("비교 답변 2/3").assertIsSelected()
        // Default is ordinal 2; select ordinal 1 through real transient callback.
        compose.onNodeWithContentDescription("비교 답변 1/3").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("추가 1구간 · 삭제 1구간 · 동일 1구간").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("동일한 내용 보기").performScrollTo().performClick()
        compose.onNodeWithText("동일한 내용 숨기기").assertIsDisplayed().performClick()
        compose.onNodeWithText("전체 답변").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("response-version-reading-list").performScrollToNode(hasContentDescription("현재 답변 전체 복사"))
        compose.onNodeWithContentDescription("현재 답변 전체 복사").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(a,copied) }
        compose.onNodeWithTag("response-version-reading-list").performScrollToNode(hasContentDescription("비교 답변 전체 복사"))
        compose.onNodeWithContentDescription("비교 답변 전체 복사").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(b,copied) }
        compose.onNodeWithText("닫기").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
        compose.onNodeWithText("답변 비교").assertDoesNotExist()
    }
    @Test fun rapidSelectionNeverShowsOldPair() {
        show()
        compose.onNodeWithContentDescription("비교 답변 1/3").performScrollTo().performClick()
        compose.onNodeWithContentDescription("비교 답변 2/3").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("추가 1구간 · 삭제 1구간 · 동일 0구간").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("전체 답변").performScrollTo().performClick()
        compose.onNodeWithTag("response-version-reading-list").performScrollToNode(hasText("세 번째 English 답변"))
        compose.onNodeWithText("세 번째 English 답변").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(b).assertDoesNotExist()
        compose.onNodeWithTag("response-version-reading-list").performScrollToIndex(0)
        compose.onAllNodesWithText("현재 답변 · 3/3").onFirst().performScrollTo().assertIsDisplayed()
    }
    @Test fun identicalMessagesKeepDistinctRoles() {
        show(identical=true)
        compose.onNodeWithContentDescription("비교 답변 1/3").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("두 답변의 표시된 텍스트가 동일합니다.").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("현재 답변 · 3/3").onFirst().performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("비교 답변 · 1/3").performScrollTo().assertIsDisplayed()
    }
    @Test fun invalidSnapshotFailsClosed() {
        show(invalid=true)
        compose.onNodeWithText("비교할 답변을 확인할 수 없습니다.").assertIsDisplayed()
        compose.onNodeWithText("전체 답변").assertDoesNotExist()
    }
    @Test fun oversizedDiffKeepsFullAnswerModeAvailable() {
        show(oversized=true)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("답변이 길거나 줄 수가 많아 상세 비교를 표시할 수 없습니다. 전체 답변에서 원문을 확인하세요.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("전체 답변").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("비교 답변에만 있음").assertDoesNotExist()
    }
    @Test fun immediateDismissDiscardsPendingPresentation() {
        show()
        compose.onNodeWithText("닫기").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("답변 비교").assertDoesNotExist()
        compose.runOnIdle { assertNull(copied); assertTrue(dismissed) }
    }}
