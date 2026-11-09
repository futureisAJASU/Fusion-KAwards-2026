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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.data.EVIDENCE_VERIFIER_VERSION
import com.projectnuke.fusion.data.EvidenceClaim
import com.projectnuke.fusion.data.EvidenceOutdatedReason
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.data.EvidenceVerdict
import com.projectnuke.fusion.ui.theme.FusionTheme
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * UX-P2A — rendered behaviour of the READ-ONLY saved-claim evidence explorer.
 *
 * These are real Compose instrumentation tests over the PRODUCTION
 * [SavedClaimEvidenceDialog]: selecting a stored claim by id, focused detail,
 * switching claims, returning to the list, fail-closed copy, 48dp touch targets,
 * large-font/compact-width usability and accessible original-link semantics.
 * They require a device/emulator; when none is attached they are
 * COMPILE-ONLY and reported as NOT_EXECUTED.
 */
class SavedClaimEvidenceExplorerUiTest {
    private val compose = createComposeRule()

    @get:Rule
    val composeHostRules = composeHostTestRules(compose)

    private val identity = EvidenceAnswerIdentity(messageId = 1L, answerSha256 = "a".repeat(64))

    private fun source(
        id: String = "E1",
        title: String = "Acme release",
        url: String = "https://example.com/release",
        snippet: String = "Acme released ProductX2.",
    ) = EvidenceSource(
        id = id,
        title = title,
        source = "Acme",
        url = url,
        snippet = snippet,
        publishedAt = "2026-01-01",
        providerType = "FREE_DEFAULT",
        providerDisplayName = "Search",
        queryUsed = "Acme ProductX2 2026",
    )

    private fun ready(
        selectedClaimId: String? = null,
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
            checkedAt = System.currentTimeMillis(),
            verifierVersion = EVIDENCE_VERIFIER_VERSION,
            claims = claims,
            sources = sources,
        ),
        selectedClaimId = selectedClaimId,
    )

    private fun content(
        fontScale: Float = 1.0f,
        widthDp: Int = 360,
        state: SavedClaimExplorerState,
        onSelectClaim: (String) -> Unit = {},
        onBackToClaims: () -> Unit = {},
        onDismiss: () -> Unit = {},
        onOpenUrl: (String) -> Unit = {},
    ) {
        compose.setContentOnResumedTestHost {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalFluidMotionMode provides FluidMotionMode.MINIMAL,
            ) {
                FusionTheme(FusionThemeMode.LIGHT) {
                    Box(Modifier.width(widthDp.dp)) {
                        SavedClaimEvidenceDialog(
                            state = state,
                            onSelectClaim = onSelectClaim,
                            onBackToClaims = onBackToClaims,
                            onDismiss = onDismiss,
                            onOpenUrl = onOpenUrl,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun claimsAreSelectableAndFocusedDetailShowsLinkedEvidence() {
        var selected: String? = null
        content(state = ready(), onSelectClaim = { selected = it })

        compose.onNodeWithText(SAVED_CLAIMS_TITLE).assertIsDisplayed()
        compose.onNodeWithText("Acme released ProductX2.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Acme did not release ProductX2.").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Acme released ProductX2.").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("C1", selected) }
    }

    @Test
    fun focusedDetailShowsOnlyTheSelectedClaimsSourcesAndReturnsToTheList() {
        var back = 0
        content(
            state = ready(
                selectedClaimId = "C2",
                claims = listOf(
                    EvidenceClaim("C1", "first claim", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, emptyList(), null),
                    EvidenceClaim("C2", "second claim", EvidenceVerdict.CONFLICTING_EVIDENCE, listOf("E1"), null),
                ),
            ),
            onBackToClaims = { back += 1 },
        )

        // Detail shows exactly the selected claim's verdict + linked source.
        compose.onNodeWithText("second claim").assertIsDisplayed()
        compose.onNodeWithText("상충하는 근거가 있음").assertIsDisplayed()
        compose.onNodeWithText("연결된 자료 1개 · 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("Acme release · 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("Acme release").performScrollTo().assertIsDisplayed()
        // The unrelated claim is not shown in the focused detail.
        compose.onNodeWithText("first claim").assertDoesNotExist()

        compose.onNodeWithContentDescription("이전 화면으로").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, back) }
    }

    @Test
    fun missingReportShowsEmptyStateCopy() {
        content(state = SavedClaimExplorerState.Unavailable(identity, SavedClaimAbsence.MISSING))
        compose.onNodeWithText(SAVED_CLAIMS_MISSING_LABEL).assertIsDisplayed()
        compose.onNodeWithText(SAVED_CLAIMS_READ_ONLY_NOTE).assertIsDisplayed()
    }

    @Test
    fun partialCoverageShowsGuidanceWithoutClaimingWholeAnswerVerification() {
        content(
            state = ready(
                claims = listOf(
                    EvidenceClaim("C1", "checked claim", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, emptyList(), null),
                    EvidenceClaim("C2", "unchecked claim", EvidenceVerdict.NOT_CHECKED, emptyList(), null),
                ),
                sources = emptyList(),
            ),
        )
        compose.onNodeWithText("일부 주장 확인").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("검증 범위와 한계 · 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("판정되지 않았습니다", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("확인한 주장이 없음").assertDoesNotExist()
    }

    @Test
    fun notAuditedCoverageShowsGuidance() {
        content(
            state = ready(
                claims = listOf(
                    EvidenceClaim("C1", "one", EvidenceVerdict.NOT_CHECKED, emptyList(), null),
                    EvidenceClaim("C2", "two", EvidenceVerdict.NOT_CHECKED, emptyList(), null),
                ),
                sources = emptyList(),
            ),
        )
        compose.onNodeWithText("확인한 주장이 없음").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("검증 범위와 한계 · 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("보장하지 않습니다", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun staleReportShowsFailClosedCopy() {
        content(state = SavedClaimExplorerState.Unavailable(identity, SavedClaimAbsence.STALE))
        compose.onNodeWithText("이 답변이 변경되어 이전 근거 확인 결과를 사용할 수 없습니다.").assertIsDisplayed()
    }

    @Test
    fun corruptReportShowsFailClosedCopy() {
        content(state = SavedClaimExplorerState.Unavailable(identity, SavedClaimAbsence.CORRUPT))
        compose.onNodeWithText("저장된 근거 확인 결과를 읽을 수 없습니다.").assertIsDisplayed()
    }

    @Test
    fun danglingSourceReferenceFailsClosedWhenTheReportIsNotCodecCompatible() {
        content(
            state = ready(
                selectedClaimId = "C1",
                claims = listOf(
                    EvidenceClaim("C1", "claim", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, listOf("E9"), null),
                ),
                sources = emptyList(),
            ),
        )
        compose.onNodeWithText(outdatedMessage(EvidenceOutdatedReason.RULES_CHANGED)).assertIsDisplayed()
        compose.onNodeWithText("claim").assertDoesNotExist()
    }

    @Test
    fun longKoreanClaimTextKeepsActionsReachableAtLargeFontScale() {
        val longClaim = "아주 긴 한국어 주장 텍스트가 반복되어 좁은 화면과 큰 글꼴에서도 ".repeat(12)
        content(
            fontScale = 1.5f,
            widthDp = 300,
            state = ready(
                selectedClaimId = "C1",
                claims = listOf(
                    EvidenceClaim("C1", longClaim, EvidenceVerdict.INSUFFICIENT_EVIDENCE, listOf("E1"), "NO_USABLE_SOURCE"),
                ),
            ),
        )
        compose.onNodeWithContentDescription("이전 화면으로").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("닫기").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun claimRowsMeet48DpTouchTargets() {
        content(state = ready())
        compose.onNodeWithText("Acme released ProductX2.").performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .assertHasClickAction()
    }

    @Test
    fun originalSourceLinkRetainsAccessibleSemantics() {
        content(state = ready(selectedClaimId = "C1"))
        compose.onNodeWithText("연결된 자료 1개 · 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("Acme release · 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("원문 열기").performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Acme release").assertIsDisplayed()
    }

    @Test
    fun sourceComparisonSelectsOnlyClaimLinkedSourcesAndAllowsSwitching() {
        var openedUrl: String? = null
        content(
            state = ready(
                selectedClaimId = "C1",
                claims = listOf(
                    EvidenceClaim(
                        "C1",
                        "Acme released ProductX2.",
                        EvidenceVerdict.CONFLICTING_EVIDENCE,
                        listOf("E2", "E1"),
                        null,
                    ),
                ),
                sources = listOf(
                    source("E1", title = "First source"),
                    source("E2", title = "Second source"),
                    source("E3", title = "Unassociated source"),
                ),
            ),
            onOpenUrl = { openedUrl = it },
        )

        compose.onNodeWithText(CLAIM_EVIDENCE_COMPARE_LABEL + " · 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("자료 A · Second source").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("자료 B · First source").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Unassociated source", substring = true).assertDoesNotExist()

        compose.onNodeWithText("자료 A · Second source").performClick()
        compose.onNodeWithText("First source · E1").performClick()
        compose.onNodeWithText("자료 A · First source").assertIsDisplayed()
        compose.onNodeWithText("자료 B · Second source").assertIsDisplayed()

        compose.onAllNodesWithText("원문 열기").get(1).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("https://example.com/release", openedUrl) }
    }

    @Test
    fun sameSourceSelectionKeepsBothSelectorsVisibleAndCanRecover() {
        content(
            state = ready(
                selectedClaimId = "C1",
                claims = listOf(
                    EvidenceClaim(
                        "C1",
                        "Acme released ProductX2.",
                        EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
                        listOf("E1", "E2"),
                        null,
                    ),
                ),
                sources = listOf(
                    source("E1", title = "First source"),
                    source("E2", title = "Second source"),
                    source("E3", title = "Unassociated source"),
                ),
            ),
        )

        compose.onNodeWithText(CLAIM_EVIDENCE_COMPARE_LABEL + " · 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("자료 B · Second source").performScrollTo().performClick()
        compose.onNodeWithText("First source · E1").performClick()

        compose.onNodeWithText("같은 자료가 양쪽에 선택되어 있습니다. 서로 다른 자료를 선택해 주세요.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("자료 A 선택, 현재 First source")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("자료 B 선택, 현재 First source")
            .assertIsDisplayed()
        compose.onNodeWithText("Unassociated source · E3").assertDoesNotExist()

        compose.onNodeWithContentDescription("자료 B 선택, 현재 First source").performClick()
        compose.onNodeWithText("Second source · E2").performClick()

        compose.onNodeWithText("같은 자료가 양쪽에 선택되어 있습니다. 서로 다른 자료를 선택해 주세요.")
            .assertDoesNotExist()
        compose.onNodeWithContentDescription("자료 A 선택, 현재 First source").assertIsDisplayed()
        compose.onNodeWithContentDescription("자료 B 선택, 현재 Second source").assertIsDisplayed()
    }

    @Test
    fun singleLinkedSourceExplainsWhyTwoSourceComparisonIsUnavailable() {
        content(state = ready(selectedClaimId = "C1"))

        compose.onNodeWithText(CLAIM_EVIDENCE_COMPARE_LABEL + " · 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("연결된 자료가 1개뿐이라 두 자료 비교는 할 수 없습니다.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Acme release").assertIsDisplayed()
    }

    @Test
    fun sourceSelectorsKeepKoreanAccessibilityAnd48DpTargetsAtLargeFontCompactWidth() {
        content(
            fontScale = 1.5f,
            widthDp = 300,
            state = ready(
                selectedClaimId = "C1",
                claims = listOf(
                    EvidenceClaim(
                        "C1",
                        "아주 긴 한국어 사실 주장입니다.",
                        EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
                        listOf("E1", "E2"),
                        null,
                    ),
                ),
                sources = listOf(
                    source("E1", title = "첫 번째 긴 한국어 자료 제목"),
                    source("E2", title = "두 번째 긴 한국어 자료 제목"),
                ),
            ),
        )

        compose.onNodeWithText(CLAIM_EVIDENCE_COMPARE_LABEL + " · 펼치기").performScrollTo().performClick()
        compose.onNodeWithContentDescription("자료 A 선택, 현재 첫 번째 긴 한국어 자료 제목")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .assertHasClickAction()
        compose.onNodeWithText("자료 B · 두 번째 긴 한국어 자료 제목").performScrollTo().assertIsDisplayed()
    }
}
