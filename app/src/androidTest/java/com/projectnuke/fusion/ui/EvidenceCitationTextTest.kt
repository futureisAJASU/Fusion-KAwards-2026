package com.projectnuke.fusion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.projectnuke.fusion.data.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class EvidenceCitationTextTest {
    @get:Rule val compose = createComposeRule()
    private val text = "Acme released ProductX2 in 2026."
    private val message = MessageEntity(1, 10, "assistant", text, 1)
    private val source = EvidenceSource("E1", "Acme release source", "Acme", "https://example.com", text, "2026", "FREE_DEFAULT", "Search", "Acme query")
    private fun report(verdict: EvidenceVerdict = EvidenceVerdict.SUPPORTED_BY_EVIDENCE) = MessageEvidenceReport(1,
        EvidenceReport(1, EvidenceFingerprint.canonicalAnswerHash(text), 1, EVIDENCE_VERIFIER_VERSION,
            listOf(EvidenceClaim("C1", text, verdict, listOf("E1"), null)), listOf(source)))

    @Test fun supportedMarkerOpensItsSource() {
        compose.setContent {
            MaterialTheme {
                SelectionContainer { ValidatedEvidenceText(message, text, report(), TextStyle.Default, Color.Black) {} }
            }
        }
        compose.onAllNodes(hasClickAction()).onFirst().performClick()
        compose.onNodeWithText("검색 근거 [E1]").assertIsDisplayed()
        compose.onNodeWithText("Acme release source").assertIsDisplayed()
    }

    @Test fun rawFabricatedMarkerHasNoClickAction() {
        val owner = message.copy(content = "$text [E999]")
        compose.setContent {
            MaterialTheme { ValidatedEvidenceText(owner, owner.content, null, TextStyle.Default, Color.Black) {} }
        }
        compose.onNodeWithText(owner.content).assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun conflictingClaimHasNoSupportLink() {
        compose.setContent {
            MaterialTheme { ValidatedEvidenceText(message, text, report(EvidenceVerdict.CONFLICTING_EVIDENCE), TextStyle.Default, Color.Black) {} }
        }
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun durableRevisionRefreshAddsLinksThroughStoreLoad() {
        val revision = mutableStateOf<MessageEvidenceRevision?>(null)
        val report = report().report
        var loads = 0
        val store = object : EvidenceUiStore {
            override suspend fun load(message: MessageEntity): EvidenceStore.LoadResult {
                loads++
                assertEquals(1L, message.id)
                return EvidenceStore.LoadResult.Valid(report)
            }
            override suspend fun saveComplete(message: MessageEntity, report: EvidenceReport): EvidenceStore.SaveResult = error("render must not save")
        }
        compose.setContent {
            MaterialTheme { EvidenceAnswerText(message, text, store, revision.value, TextStyle.Default, Color.Black) {} }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.runOnIdle {
            revision.value = MessageEvidenceRevision(1, report.answerSha256, 1, report.verifierVersion, 1, EvidenceCodec.serialize(report).length)
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size == 1 }
        compose.onAllNodes(hasClickAction()).onFirst().performClick()
        compose.onNodeWithText("검색 근거 [E1]").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, loads)
            assertEquals(text, message.content)
        }
    }
}
