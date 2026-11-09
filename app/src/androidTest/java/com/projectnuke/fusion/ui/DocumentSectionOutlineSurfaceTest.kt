package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.retrieval.DocumentSectionKind
import com.projectnuke.fusion.retrieval.DocumentSectionOutline
import com.projectnuke.fusion.retrieval.StructuredDocumentSection
import com.projectnuke.fusion.ui.theme.FusionTheme
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentSectionOutlineSurfaceTest {
    private val compose = createComposeRule()
    @get:Rule val hostRules = composeHostTestRules(compose)

    @Test fun blackThemeOutlineKeepsSectionQuestionAndSaveActionsUsableAtLargeFont() {
        val section = StructuredDocumentSection(
            title = "Long Korean and English section title that needs to wrap cleanly",
            kind = DocumentSectionKind.TABLE,
            text = "| Metric | Value |\n| --- | --- |\n| Temperature | 95 C |",
            pageNumber = 12,
            sourceChunkId = "page:12",
            sourceContentHashHex = "a".repeat(64),
            truncated = false,
        )
        var asked: StructuredDocumentSection? = null
        var saved: StructuredDocumentSection? = null
        compose.setContentOnResumedTestHost {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                FusionTheme(FusionThemeMode.BLACK) {
                    Box(Modifier.width(360.dp)) {
                        FusionReadingDialog(
                            onDismissRequest = {},
                            title = { Text("문서 목차") },
                            text = {
                                DocumentSectionOutlineContent(
                                    fileName = "HBM thermal review.pdf",
                                    state = DocumentOutlineLoadState.Ready(DocumentSectionOutline(listOf(section), partial = false)),
                                    onAskSection = { asked = it },
                                    onSaveSection = { saved = it },
                                )
                            },
                            confirmButton = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText(section.title).assertExists().performClick()
        compose.runOnIdle { assertEquals(section, asked) }
        compose.onNodeWithContentDescription("${section.title} 작업").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("섹션 저장").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(section, saved) }
        compose.onNodeWithText("제목을 기반으로 섹션을 구분해 찾았습니다.", substring = true).assertExists()
    }
    @Test fun keyedLoaderShowsLoadingThenSuccessAndCancelsOldFileRead() {
        val identity = androidx.compose.runtime.mutableStateOf(1L)
        val firstStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val firstCancelled = kotlinx.coroutines.CompletableDeferred<Unit>()
        val delayedResult = kotlinx.coroutines.CompletableDeferred<DocumentSectionOutline?>()
        compose.setContentOnResumedTestHost {
            val id = identity.value
            val state = rememberDocumentOutlineLoadState(id, "file-$id") {
                if (id == 1L) {
                    firstStarted.complete(Unit)
                    try { kotlinx.coroutines.awaitCancellation() }
                    finally { firstCancelled.complete(Unit) }
                } else {
                    delayedResult.await()
                }
            }.value
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.TextButton(onClick = { identity.value = 2L }) { Text("Change file") }
                DocumentSectionOutlineContent("file-$id", state, {}, {})
            }
        }
        compose.waitUntil(5_000) { firstStarted.isCompleted }
        compose.onNodeWithText("문서 목차를 불러오는 중…").assertIsDisplayed()
        compose.onNodeWithText("\uBB38\uC11C \uC139\uC158\uC744 \uC77D\uC744 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4.").assertDoesNotExist()
        compose.onNodeWithText("Change file").performClick()
        compose.waitUntil(5_000) { firstCancelled.isCompleted }
        compose.runOnIdle { delayedResult.complete(DocumentSectionOutline(emptyList(), partial = false)) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("\uBA85\uC2DC\uC801 \uAD6C\uC870\uB97C \uCC3E\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("\uBA85\uC2DC\uC801 \uAD6C\uC870\uB97C \uCC3E\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.").assertIsDisplayed()
    }

    @Test fun failedOutlineStateIsDistinctFromLoadingAndEmpty() {
        compose.setContentOnResumedTestHost {
            FusionTheme(FusionThemeMode.BLACK) {
                DocumentSectionOutlineContent("missing.pdf", DocumentOutlineLoadState.Failed, {}, {})
            }
        }
        compose.onNodeWithText("\uBB38\uC11C \uC139\uC158\uC744 \uC77D\uC744 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4.").assertIsDisplayed()
        compose.onNodeWithText("문서 목차를 불러오는 중…").assertDoesNotExist()
    }

}
