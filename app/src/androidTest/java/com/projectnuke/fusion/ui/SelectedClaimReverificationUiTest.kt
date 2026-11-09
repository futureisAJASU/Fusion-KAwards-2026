package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.data.EVIDENCE_VERIFIER_VERSION
import com.projectnuke.fusion.data.EvidenceClaim
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.data.EvidenceVerdict
import com.projectnuke.fusion.ui.theme.FusionTheme
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * UX-P2B — rendered behaviour of the explicit single-claim reverification
 * surface inside the focused stored-claim detail of the PRODUCTION
 * [SavedClaimEvidenceDialog].
 *
 * These tests assert the transient result is clearly separated from the stored
 * record, is scoped to exactly one claim id, discloses that nothing is saved,
 * and fails closed. They require a device/emulator; when none is attached they
 * are COMPILE-ONLY and reported as NOT_EXECUTED.
 */
class SelectedClaimReverificationUiTest {
    private val compose = createComposeRule()

    @get:Rule
    val composeHostRules = composeHostTestRules(compose)

    private val identity = EvidenceAnswerIdentity(messageId = 1L, answerSha256 = "a".repeat(64))

    private fun source(id: String = "E1") = EvidenceSource(
        id = id,
        title = "Acme release",
        source = "Acme",
        url = "https://example.com/release",
        snippet = "Acme released ProductX2.",
        publishedAt = "2026-01-01",
        providerType = "FREE_DEFAULT",
        providerDisplayName = "Search",
        queryUsed = "Acme ProductX2 2026",
    )

    private fun ready(
        selectedClaimId: String? = "C1",
        claims: List<EvidenceClaim> = listOf(
            EvidenceClaim("C1", "Acme released ProductX2.", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, listOf("E1"), null),
            EvidenceClaim("C2", "Acme did not release ProductX2.", EvidenceVerdict.CONFLICTING_EVIDENCE, listOf("E1"), null),
        ),
        sources: List<EvidenceSource> = listOf(source()),
    ) = SavedClaimExplorerState.Ready(
        identity = identity,
        report = EvidenceReport(
            schemaVersion = 1,
            answerSha256 = "a".repeat(64),
            checkedAt = 1L,
            verifierVersion = EVIDENCE_VERIFIER_VERSION,
            claims = claims,
            sources = sources,
        ),
        selectedClaimId = selectedClaimId,
    )

    private fun transientReport(
        claimId: String = "C1",
        text: String = "Acme released ProductX2.",
        verdict: EvidenceVerdict = EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
    ) = EvidenceReport(
        schemaVersion = 1,
        answerSha256 = "a".repeat(64),
        checkedAt = 2L,
        verifierVersion = EVIDENCE_VERIFIER_VERSION,
        claims = listOf(EvidenceClaim(claimId, text, verdict, listOf("E1"), null)),
        sources = listOf(source()),
    )

    private fun content(
        state: SavedClaimExplorerState,
        reverificationState: SelectedClaimReverificationState = SelectedClaimReverificationState.Idle,
        reverifyAvailable: Boolean = true,
        onReverify: (String) -> Unit = {},
        onReverifyCancel: () -> Unit = {},
    ) {
        compose.setContentOnResumedTestHost {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 1.0f),
                LocalFluidMotionMode provides FluidMotionMode.MINIMAL,
            ) {
                FusionTheme(FusionThemeMode.LIGHT) {
                    Box(Modifier.width(360.dp)) {
                        SavedClaimEvidenceDialog(
                            state = state,
                            onSelectClaim = {},
                            onBackToClaims = {},
                            onDismiss = {},
                            onOpenUrl = {},
                            reverificationState = reverificationState,
                            reverifyAvailable = reverifyAvailable,
                            onReverify = onReverify,
                            onReverifyCancel = onReverifyCancel,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun idleDetailOffersReverificationWithoutReplacingTheStoredRecord() {
        content(state = ready())
        compose.onNodeWithText(SELECTED_CLAIM_STORED_LABEL).assertIsDisplayed()
        compose.onNodeWithText(SELECTED_CLAIM_REVERIFY_LABEL).performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText(SELECTED_CLAIM_REVERIFY_HINT).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun clickingReverifyReportsTheExactSelectedClaimId() {
        var requested: String? = null
        content(state = ready(selectedClaimId = "C2"), onReverify = { requested = it })
        compose.onNodeWithText(SELECTED_CLAIM_REVERIFY_LABEL).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("C2", requested) }
    }

    @Test
    fun runningStateShowsProgressAndCancel() {
        var cancelled = 0
        content(
            state = ready(),
            reverificationState = SelectedClaimReverificationState.Running("C1", webSearch = false),
            onReverifyCancel = { cancelled += 1 },
        )
        compose.onNodeWithText(SELECTED_CLAIM_REVERIFYING_LABEL).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(SELECTED_CLAIM_CANCEL_LABEL).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test
    fun transientResultIsSeparatedAndNeverClaimsToBeSaved() {
        content(
            state = ready(),
            reverificationState = SelectedClaimReverificationState.Result(
                claimId = "C1",
                report = transientReport(verdict = EvidenceVerdict.INSUFFICIENT_EVIDENCE),
                transientAudit = null,
                webSearchAuthorized = false,
            ),
        )
        compose.onNodeWithText(SELECTED_CLAIM_TEMP_RESULT_LABEL).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("저장된 판정").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("근거 부족").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("저장된 판정").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("이번 확인 · 임시", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("저장된 판정과 이번 확인의 판정이 다릅니다.", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(SELECTED_CLAIM_NOT_SAVED_NOTE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(SELECTED_CLAIM_SINGLE_SCOPE_NOTE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun storedAndTransientComparisonShowsEachSourceUnderItsOwnOrigin() {
        content(
            state = ready(),
            reverificationState = SelectedClaimReverificationState.Result(
                claimId = "C1",
                report = transientReport(verdict = EvidenceVerdict.INSUFFICIENT_EVIDENCE),
                transientAudit = null,
                webSearchAuthorized = false,
                storedReport = ready().report,
            ),
        )

        compose.onNodeWithText("검색 제공자 · Search").assertDoesNotExist()
        compose.onNodeWithText("연결된 자료 · 저장 1개 · 이번 1개 · 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("저장 자료").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("이번 자료 · 임시").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Acme release · 펼치기")[0].performScrollTo().performClick()
        compose.onNodeWithText("원문 발행처 · Acme").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("검색 기록 · 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("검색 제공자 · Search").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun temporaryOnlyResultExplainsMissingPreviousRecordAndShowsItsLinkedSource() {
        val temporary = transientReport()
        val temporaryOwner = SelectedClaimReverificationState.Result(
            claimId = "C1",
            report = temporary,
            transientAudit = null,
            webSearchAuthorized = false,
        )
        compose.setContentOnResumedTestHost {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 1.0f),
                LocalFluidMotionMode provides FluidMotionMode.MINIMAL,
            ) {
                FusionTheme(FusionThemeMode.LIGHT) {
                    Box(Modifier.width(360.dp)) {
                        ClaimEvidenceResultComparisonSection(
                            currentIdentity = identity,
                            claimId = "C1",
                            exactClaimText = "Acme released ProductX2.",
                            storedReport = null,
                            transientResult = temporaryOwner,
                            webSearchAuthorized = false,
                            onOpenUrl = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("이 주장과 정확히 일치하는 저장 기록이 없어 이번 임시 결과만 표시합니다.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("연결된 자료 · 이번 1개 · 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("Acme release · 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("Acme release").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(CLAIM_EVIDENCE_COMPARE_LABEL + " · 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("연결된 자료가 1개뿐이라 두 자료 비교는 할 수 없습니다.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun invalidTransientAnswerHashDoesNotRenderFallbackSources() {
        val invalid = transientReport().copy(
            answerSha256 = "b".repeat(64),
            sources = listOf(source().copy(title = "Invalid transient source")),
        )
        content(
            state = ready(),
            reverificationState = SelectedClaimReverificationState.Result(
                claimId = "C1",
                report = invalid,
                transientAudit = null,
                webSearchAuthorized = false,
            ),
        )

        compose.onNodeWithText("Invalid transient source", substring = true).assertDoesNotExist()
    }

    @Test
    fun transientResultForAnotherClaimNeverRendersUnderTheSelectedClaim() {
        content(
            state = ready(selectedClaimId = "C1"),
            reverificationState = SelectedClaimReverificationState.Result(
                claimId = "C2",
                report = transientReport(claimId = "C2", text = "Acme did not release ProductX2."),
                transientAudit = null,
                webSearchAuthorized = false,
            ),
        )
        compose.onNodeWithText(SELECTED_CLAIM_TEMP_RESULT_LABEL).assertDoesNotExist()
        compose.onNodeWithText(SELECTED_CLAIM_REVERIFY_LABEL).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun webAuthorizedResultDisclosesSearchUse() {
        content(
            state = ready(),
            reverificationState = SelectedClaimReverificationState.Result(
                claimId = "C1",
                report = transientReport(),
                transientAudit = null,
                webSearchAuthorized = true,
            ),
        )
        compose.onNodeWithText(SELECTED_CLAIM_WEB_USED_LABEL).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun failedResultShowsTruthfulCopyAndOffersManualRetry() {
        var retried: String? = null
        content(
            state = ready(),
            reverificationState = SelectedClaimReverificationState.Failed(
                claimId = "C1",
                reason = SelectedClaimFailureReason.DEADLINE_EXCEEDED,
            ),
            onReverify = { retried = it },
        )
        compose.onNodeWithText(SELECTED_CLAIM_TEMP_RESULT_LABEL).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("시간이 초과", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(SELECTED_CLAIM_RETRY_LABEL).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("C1", retried) }
    }

    @Test
    fun ineligibleResultShowsTruthfulExplanationWithoutVerdict() {
        val explanation = selectedClaimIneligibleExplanation(SelectedClaimIneligibleReason.OUTSIDE_EXECUTION_WINDOW)
        content(
            state = ready(),
            reverificationState = SelectedClaimReverificationState.Ineligible(
                claimId = "C1",
                reason = SelectedClaimIneligibleReason.OUTSIDE_EXECUTION_WINDOW,
                explanation = explanation,
            ),
        )
        compose.onNodeWithText(SELECTED_CLAIM_TEMP_RESULT_LABEL).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(explanation).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("근거로 확인됨").assertDoesNotExist()
    }

    @Test
    fun reverificationActionMeets48DpTouchTarget() {
        content(state = ready())
        compose.onNodeWithText(SELECTED_CLAIM_REVERIFY_LABEL).performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .assertHasClickAction()
    }

    @Test
    fun reverificationActionIsHiddenWhenNoVerifierIsAvailable() {
        content(state = ready(), reverifyAvailable = false)
        compose.onNodeWithText(SELECTED_CLAIM_STORED_LABEL).assertIsDisplayed()
        compose.onNodeWithText(SELECTED_CLAIM_REVERIFY_LABEL).assertDoesNotExist()
    }
}
