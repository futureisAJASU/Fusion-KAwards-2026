package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.chat.SelectionContextSnapshot
import com.projectnuke.fusion.ui.theme.FusionTheme
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FusionSearchSurfaceAccessRaceTest {
    private val compose = createComposeRule()
    @get:Rule val composeHostRules = composeHostTestRules(compose)

    @Test
    fun lateSelectionHandoffIsDiscardedAfterArchiveAccessChanges() {
        val accessStarted = CompletableDeferred<Unit>()
        val accessRelease = CompletableDeferred<Boolean>()
        var delayNextAccess = false
        var includeArchived by mutableStateOf(false)
        var handoffs = 0
        val source = object : FusionKnowledgeSource {
            override suspend fun search(query: String) = listOf(
                KnowledgeCandidate(
                    "project:1", KnowledgeKind.PROJECT, "검색 대상 프로젝트", "발췌 내용", "프로젝트",
                    KnowledgeTarget.Project(1L),
                ),
            )
            override suspend fun accessible(target: KnowledgeTarget): Boolean {
                if (delayNextAccess) {
                    delayNextAccess = false
                    accessStarted.complete(Unit)
                    return accessRelease.await()
                }
                return true
            }
        }

        compose.setContentOnResumedTestHost {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 1.5f),
            ) {
                FusionTheme(FusionThemeMode.LIGHT) {
                    Surface { Box(Modifier.width(360.dp)) {
                        FusionSearchSurface(
                            includeArchived = includeArchived,
                            onDismiss = {},
                            onNavigate = {},
                            searchSource = source,
                            onQuestionWithSelection = { handoffs++ },
                        )
                    } }
                }
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("needle")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("검색 대상 프로젝트").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("선택").performClick()
        compose.onNodeWithText("검색 대상 프로젝트").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("1개 선택").fetchSemanticsNodes().isNotEmpty()
        }

        compose.runOnIdle { delayNextAccess = true }
        compose.onNodeWithText("선택한 자료로 질문").performClick()
        compose.waitUntil(5_000) { accessStarted.isCompleted }
        compose.runOnIdle { includeArchived = true }
        compose.waitForIdle()
        accessRelease.complete(true)

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("선택").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertEquals(0, handoffs) }
        compose.onAllNodesWithText("선택한 자료로 질문").assertCountEquals(0)
    }
}
