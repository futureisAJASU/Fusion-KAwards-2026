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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProjectKnowledgeDigestSurfaceTest {
    private val compose = createComposeRule()
    @get:Rule val hostRules = composeHostTestRules(compose)

    @Test
    fun `digest rows and range actions wrap at large font and open existing targets`() {
        val entry = ProjectDigestEntry(
            "saved:1", ProjectDigestCategory.NEW_CONTENT,
            "A long project knowledge title with Korean and English 내용 that should wrap cleanly",
            "Inbox에서 추가", 100, ProjectDigestTarget.SavedKnowledge("saved-1"),
        )
        var selectedRange = 0
        var opened: ProjectDigestEntry? = null
        compose.setContentOnResumedTestHost {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp)) {
                        ProjectKnowledgeDigestSurface(
                            projectName = "Knowledge Project",
                            state = ProjectKnowledgeDigestState.Ready(
                                ProjectKnowledgeDigest(
                                    1, "Knowledge Project", 7, 0, 100,
                                    listOf(ProjectDigestSection(ProjectDigestCategory.NEW_CONTENT, listOf(entry))),
                                ),
                            ),
                            selectedRangeDays = 7,
                            onSelectRange = { selectedRange = it },
                            onDismiss = {},
                            onOpenEntry = { opened = it },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("최근 30일").assertExists().performClick()
        compose.onNodeWithText(entry.title).assertExists().performClick()
        compose.runOnIdle {
            assertEquals(30, selectedRange)
            assertEquals(entry, opened)
        }
    }
}
