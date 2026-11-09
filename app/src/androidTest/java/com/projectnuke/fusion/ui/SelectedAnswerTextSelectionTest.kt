package com.projectnuke.fusion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceClaim
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.data.EvidenceStore
import com.projectnuke.fusion.data.EvidenceVerificationResult
import com.projectnuke.fusion.data.EvidenceVerdict
import com.projectnuke.fusion.data.EVIDENCE_VERIFIER_VERSION
import com.projectnuke.fusion.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream

/** Real touch gestures exercise Foundation's native selection and text context menu. */
class SelectedAnswerTextSelectionTest {
    @get:Rule val compose = createComposeRule()

    private val answer = "Acme released ProductX2 in 2026."
    private val message = MessageEntity(7001L, 71L, "assistant", answer, 1L)
    private val store = object : EvidenceUiStore {
        override suspend fun load(message: MessageEntity) = EvidenceStore.LoadResult.Missing
        override suspend fun saveComplete(message: MessageEntity, report: EvidenceReport): EvidenceStore.SaveResult =
            error("native selection verification preview must not persist")
    }
    private val verifier = EvidenceUiVerifier { verifiedMessage, plan ->
        assertEquals(message, verifiedMessage)
        val plannedClaim = plan.claims.single().candidate
        assertEquals(answer, plannedClaim.text)
        EvidenceVerificationResult.Complete(
            EvidenceReport(
                schemaVersion = 1,
                answerSha256 = EvidenceFingerprint.canonicalAnswerHash(verifiedMessage.content),
                checkedAt = System.currentTimeMillis(),
                verifierVersion = EVIDENCE_VERIFIER_VERSION,
                claims = listOf(
                    EvidenceClaim(
                        id = plannedClaim.id,
                        text = plannedClaim.text,
                        verdict = EvidenceVerdict.INSUFFICIENT_EVIDENCE,
                        sourceIds = emptyList(),
                        reasonCode = null,
                    ),
                ),
                sources = emptyList(),
            ),
        )
    }

    private fun tapNativeToolbarAction(horizontalFraction: Float) {
        SystemClock.sleep(1_000L)
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        val x = metrics.widthPixels * horizontalFraction
        val y = 52f * metrics.density
        val command = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input tap ${x.toInt()} ${y.toInt()}")
        FileInputStream(command.fileDescriptor).use { it.readBytes() }
        command.close()
        SystemClock.sleep(300L)
    }

    private fun render() {
        compose.setContent {
            MaterialTheme {
                SelectedAnswerTextVerificationSurface(
                    message = message,
                    visibleAnswer = answer,
                    store = store,
                    verifier = verifier,
                    revision = null,
                    expectedSurfaceIdentity = AnswerSurfaceIdentity.of(message.conversationId, message),
                    style = TextStyle(fontSize = 18.sp),
                    color = Color.Black,
                    onOpenUrl = {},
                    currentMessageReader = { persisted -> if (persisted == message) message else null },
                )
            }
        }
    }

    private fun selectSentenceByDrag() {
        val textWidth = compose.onNodeWithText(answer).fetchSemanticsNode().size.width.toFloat()
        compose.onNodeWithText(answer).performTouchInput {
            val y = center.y
            down(androidx.compose.ui.geometry.Offset(1f, y))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveTo(androidx.compose.ui.geometry.Offset(textWidth - 1f, y), 120L)
            up()
        }
    }

    @Test fun longPressAndDragExposeNativeSelectionActionAndExactPreview() {
        render()
        selectSentenceByDrag()
        tapNativeToolbarAction(horizontalFraction = 0.68f)
        compose.onAllNodesWithText(answer).assertCountEquals(2)
        compose.onNodeWithText("\uD655\uC778 \uC2DC\uC791").performClick()
        compose.onNodeWithText("\uAE30\uC874 \uCD9C\uCC98\uB9CC \uD655\uC778").performClick()
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithText(SELECTED_CLAIM_TEMP_RESULT_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText(answer).assertCountEquals(3)
        compose.onNodeWithText(SELECTED_CLAIM_NOT_SAVED_NOTE).assertIsDisplayed()
    }
    @Test fun nativeCopyRemainsAvailableForAUserSelectedPassage() {
        render()
        compose.onNodeWithText(answer).performTouchInput {
            longClick(androidx.compose.ui.geometry.Offset(1f, center.y))
        }
        tapNativeToolbarAction(horizontalFraction = 0.13f)
        SystemClock.sleep(700L)

        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData? = clipboard.primaryClip
        assertEquals(1, clip?.itemCount)
        assertEquals("Acme", clip?.getItemAt(0)?.coerceToText(InstrumentationRegistry.getInstrumentation().targetContext)?.toString())
    }
}
