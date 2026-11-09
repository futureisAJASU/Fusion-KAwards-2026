package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.data.ConversationCheckpointEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConversationCheckpointSurfaceTest {
    private val compose = createComposeRule()
    @get:Rule val hostRules = composeHostTestRules(compose)

    @Test
    fun `checkpoint list wraps long labels at one and a half font scale`() {
        val checkpoint = ConversationCheckpointEntity(
            id = 1,
            conversationId = 2,
            messageId = 3,
            title = "HBM thermal discussion with an intentionally long Korean label for wrapping",
            createdAt = 1,
            note = "한글과 English가 함께 있는 메모가 큰 글자에서도 읽혀야 합니다.",
        )
        compose.setContentOnResumedTestHost {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        ConversationCheckpointListDialog(
                            conversationTitle = "Long conversation title for accessible wrapping",
                            checkpoints = listOf(checkpoint),
                            onDismiss = {}, onOpen = {}, onContinue = {}, onRename = { _, _, _, _ -> }, onDelete = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(checkpoint.title).assertExists()
        compose.onNodeWithText("닫기").assertExists()
    }

    @Test
    fun `rename failure preserves draft and keeps editor open`() {
        var capturedCallback: ((Boolean) -> Unit)? = null
        var dismissCount = 0
        compose.setContentOnResumedTestHost {
            MaterialTheme {
                CheckpointTextDialog(
                    title = "체크포인트 수정",
                    titleValue = "original title",
                    noteValue = "original note",
                    onTitleChange = {},
                    onNoteChange = {},
                    onDismiss = { dismissCount++ },
                    onConfirm = { _, _, callback -> capturedCallback = callback },
                    editableTitle = true,
                    editableNote = true,
                )
            }
        }
        compose.onNodeWithText("저장").assertIsDisplayed()
        compose.onNodeWithText("저장").performClick()
        compose.runOnIdle { capturedCallback?.let { it(false) } }
        assertEquals(0, dismissCount)
        compose.onNodeWithText("저장").assertIsDisplayed()
        compose.runOnIdle { capturedCallback?.let { it(true) } }
        assertEquals(1, dismissCount)
    }

    @Test
    fun `rename save disables confirm while saving`() {
        var capturedCallback: ((Boolean) -> Unit)? = null
        compose.setContentOnResumedTestHost {
            MaterialTheme {
                CheckpointTextDialog(
                    title = "체크포인트 수정",
                    titleValue = "title",
                    noteValue = "",
                    onTitleChange = {},
                    onNoteChange = {},
                    onDismiss = {},
                    onConfirm = { _, _, callback -> capturedCallback = callback },
                    editableTitle = true,
                    editableNote = true,
                )
            }
        }
        compose.onNodeWithText("저장").performClick()
        compose.onNodeWithText("저장 중…").assertIsDisplayed()
        compose.onNodeWithText("저장 중…").assertIsNotEnabled()
    }

    @Test
    fun `saving state survives recomposition with new callback identity`() {
        var confirmCallCount = 0
        var capturedCallback: ((Boolean) -> Unit)? = null
        var forceRecompose by mutableStateOf(0)
        compose.setContentOnResumedTestHost {
            MaterialTheme {
                val observeRecompose = forceRecompose
                CheckpointTextDialog(
                    title = "체크포인트 수정",
                    titleValue = "title",
                    noteValue = "",
                    onTitleChange = {},
                    onNoteChange = {},
                    onDismiss = {},
                    onConfirm = { _, _, callback ->
                        confirmCallCount++
                        capturedCallback = callback
                    },
                    editableTitle = true,
                    editableNote = true,
                )
            }
        }
        compose.onNodeWithText("저장").performClick()
        compose.onNodeWithText("저장 중…").assertIsDisplayed()
        compose.onNodeWithText("저장 중…").assertIsNotEnabled()
        forceRecompose++
        compose.runOnIdle {
            assertEquals(1, confirmCallCount)
            compose.onNodeWithText("저장 중…").assertIsDisplayed()
            capturedCallback?.let { it(false) }
        }
        assertEquals(1, confirmCallCount)
        compose.runOnIdle {
            compose.onNodeWithText("저장").assertIsDisplayed()
        }
    }
}
