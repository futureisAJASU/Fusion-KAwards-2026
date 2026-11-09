package com.projectnuke.fusion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.projectnuke.fusion.documents.CameraKnowledgeCaptureState
import com.projectnuke.fusion.documents.CameraOcrStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CameraKnowledgeCaptureSurfaceTest {
    private val compose = createComposeRule()
    @get:Rule val composeHostRules = composeHostTestRules(compose)

    @Test
    fun previewShowsLocalSourceAndWaitsForExplicitDestinationChoice() {
        val actions = mutableListOf<String>()
        compose.setContentOnResumedTestHost {
            MaterialTheme {
                CameraKnowledgeCaptureSurface(
                    state = CameraKnowledgeCaptureState(
                        path = "managed/camera_ocr_sample.jpg",
                        displayName = "camera_ocr_sample.jpg",
                        status = CameraOcrStatus.PREVIEW,
                        previewText = "Recognized whiteboard text",
                    ),
                    projectAvailable = true,
                    onRetry = { actions += "retry" },
                    onAddToChat = { actions += "chat" },
                    onAddToProject = { actions += "project" },
                    onSaveOcrTextToInbox = { actions += "inbox" },
                    onDismiss = { actions += "dismiss" },
                )
            }
        }

        compose.onNodeWithText("카메라 · 기기 내 OCR").assertIsDisplayed()
        compose.onNodeWithText("Recognized whiteboard text").assertIsDisplayed()
        compose.onNodeWithText("채팅에 첨부").assertIsDisplayed()
        compose.onNodeWithText("OCR 텍스트를 Inbox에 저장").assertIsDisplayed()
        compose.onNodeWithText("프로젝트 지식에 추가").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), actions) }
        compose.onNodeWithText("채팅에 첨부").performClick()
        compose.runOnIdle { assertEquals(listOf("chat"), actions) }
    }
}
