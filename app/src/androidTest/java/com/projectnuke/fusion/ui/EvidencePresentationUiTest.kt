package com.projectnuke.fusion.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.fusion.privacy.AuthorizedTargetedQuery
import com.projectnuke.fusion.privacy.PostAnswerVerificationSearchIdentity
import com.projectnuke.fusion.data.EVIDENCE_VERIFIER_VERSION
import com.projectnuke.fusion.data.EvidenceClaim
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.data.EvidenceVerdict
import com.projectnuke.fusion.ui.theme.FusionTheme
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Production Compose surfaces, synthetic records only. Capture is opt-in; no runtime dependency. */
@RunWith(Parameterized::class)
internal class EvidencePresentationUiTest(
    private val width: Int,
    private val scale: Float,
    private val theme: FusionThemeMode,
    private val motion: FluidMotionMode,
) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}dp-{1}x-{2}-{3}")
        fun configurations(): List<Array<Any>> = listOf(
            arrayOf(300, 1f, FusionThemeMode.LIGHT, FluidMotionMode.FULL),
            arrayOf(360, 1.5f, FusionThemeMode.DARK, FluidMotionMode.REDUCED),
            arrayOf(300, 2f, FusionThemeMode.BLACK, FluidMotionMode.MINIMAL),
            arrayOf(640, 1f, FusionThemeMode.LIGHT, FluidMotionMode.MINIMAL),
            arrayOf(640, 2f, FusionThemeMode.DARK, FluidMotionMode.REDUCED),
        )
    }
    private val compose = createComposeRule()
    @get:Rule val rules = composeHostTestRules(compose)
    private val identity = EvidenceAnswerIdentity(1L, "a".repeat(64))
    private val storedCheckedAt = System.currentTimeMillis() - 86_400_000L
    private val claimText = "이 제품은 한국어와 English 문서를 함께 처리할 수 있습니다."
    private val sources = listOf("A", "B", "C").mapIndexed { index, label ->
        EvidenceSource("E${index + 1}", "자료 $label", "발행처 $label", "https://example.com/$label",
            "발췌문 $label: 실제 저장된 자료의 일부입니다.", "2026-01-01", "FREE_DEFAULT", "Search", "제품 한국어 English")
    }
    private fun report(verdict: EvidenceVerdict = EvidenceVerdict.SUPPORTED_BY_EVIDENCE) = EvidenceReport(
        1, identity.answerSha256, storedCheckedAt, EVIDENCE_VERIFIER_VERSION,
        listOf(EvidenceClaim("C1", claimText, verdict, sources.map { it.id }, null)), sources,
    )
    private fun result(stored: EvidenceReport? = report()) = SelectedClaimReverificationState.Result(
        "C1", report(EvidenceVerdict.INSUFFICIENT_EVIDENCE).copy(checkedAt = storedCheckedAt + 86_340_000L), null, false, storedReport = stored,
    )
    private fun content(block: @Composable () -> Unit) {
        compose.setContentOnResumedTestHost {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalFluidMotionMode provides motion,
            ) {
                FusionTheme(theme) {
                    androidx.compose.material3.Surface {
                        Box(Modifier.width(width.dp)) {
                            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) { block() }
                        }
                    }
                }
            }
        }
    }
    private fun capture(state: String) {
        if (InstrumentationRegistry.getArguments().getString("uxp32.capture") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ux-p32").apply { mkdirs() }
        File(directory, "$state-$width-$scale-$theme-$motion.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    @Test fun semanticReadingAndActionColorsMeetTextContrast() {
        var pairs = emptyList<Pair<Color, Color>>()
        content {
            val semantic = com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors.current
            val material = androidx.compose.material3.MaterialTheme.colorScheme
            pairs = listOf(
                semantic.onSurface to semantic.background,
                semantic.onSurfaceVariant to semantic.background,
                semantic.onSurface to semantic.panel,
                material.onSurfaceVariant to semantic.panel,
                material.primary to semantic.background,
                material.onPrimary to material.primary,
            )
        }
        compose.runOnIdle {
            assertEquals(6, pairs.size)
            pairs.forEach { (foreground, background) ->
                val values = listOf(foreground.luminance(), background.luminance()).sorted()
                val contrast = (values[1] + .05f) / (values[0] + .05f)
                assertTrue("$theme text contrast $contrast must be >=4.5", contrast >= 4.5f)
            }
        }
    }
    @Test fun normalAssistantAnswerKeepsEvidenceInExistingActions() {
        content {
            AssistantMessage(content = "## 답변\n\n" + claimText, createdAt = 1L,
                webSearchEnabled = false, onRetry = {}, onRegenerate = {}, onToggleWebSearch = {},
                onSaveAsDecision = {}, showNextActions = true)
        }
        compose.onNodeWithText(claimText).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("근거 비교", substring = true).assertDoesNotExist()
        capture("normal-answer")
    }
    @Test fun severalClaimRowsKeepScopeCollapsedAndRouteToExactDetail() {
        var selected: String? = null
        val claims = (1..6).map { EvidenceClaim("C$it", "주장 $it: " + claimText.repeat(2),
            EvidenceVerdict.INSUFFICIENT_EVIDENCE, sources.map { it.id }, null) }
        content {
            SavedClaimEvidenceDialog(SavedClaimExplorerState.Ready(identity, report().copy(claims = claims), null),
                { selected = it }, {}, {}, {})
        }
        compose.onNodeWithText(SAVED_CLAIMS_VERDICT_CAVEAT).assertDoesNotExist()
        compose.onNodeWithText(claims.first().text).performScrollTo().assertHeightIsAtLeast(48.dp)
        capture("saved-list")
        compose.onNodeWithText(claims.first().text).performClick()
        compose.runOnIdle { assertEquals("C1", selected) }
    }
    @Test fun resultIsCompactAndSourcesExpandWithinTwoActions() {
        content { SelectedClaimTempResult(result(), {}, identity) }
        compose.onAllNodesWithText(claimText).assertCountEquals(1)
        compose.onAllNodesWithText(EvidenceVerdict.INSUFFICIENT_EVIDENCE.evidenceLabel()).assertCountEquals(1)
        compose.onNodeWithText("자료 A").assertDoesNotExist()
        compose.onNodeWithText("검색 제공자 · Search").assertDoesNotExist()
        capture("result-initial")
        compose.onNodeWithText("연결된 자료 · 저장 3개 · 이번 3개 · 펼치기")
            .performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onAllNodesWithText("자료 A · 펼치기")[0].performScrollTo().performClick()
        compose.onNodeWithText("발췌문 A: 실제 저장된 자료의 일부입니다.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("연결된 자료 · 저장 3개 · 이번 3개 · 접기").performScrollTo().performClick()
        compose.onNodeWithText("발췌문 A: 실제 저장된 자료의 일부입니다.").assertDoesNotExist()
    }
    @Test fun openingPairReplacesExpandedSourceInspector() {
        content { SelectedClaimTempResult(result(), {}, identity) }
        compose.onNodeWithText("연결된 자료 · 저장 3개 · 이번 3개 · 펼치기").performScrollTo().performClick()
        compose.onAllNodesWithText("자료 A · 펼치기")[0].performScrollTo().performClick()
        compose.onNodeWithText("발췌문 A: 실제 저장된 자료의 일부입니다.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(CLAIM_EVIDENCE_COMPARE_LABEL + " · 펼치기").performScrollTo().performClick()
        compose.onAllNodesWithText("발췌문 A: 실제 저장된 자료의 일부입니다.").assertCountEquals(1)
        capture("exclusive-pair")
    }
    @Test fun noHistoryShowsTemporaryVerdictWithoutInventedStoredRecord() {
        content { SelectedClaimTempResult(result(null), {}, identity) }
        compose.onNodeWithText("저장된 판정").assertDoesNotExist()
        compose.onNodeWithText("이 주장과 정확히 일치하는 저장 기록이 없어 이번 임시 결과만 표시합니다.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(SELECTED_CLAIM_NOT_SAVED_NOTE).performScrollTo().assertIsDisplayed()
        capture("temporary-only")
    }
    @Test fun sameVerdictIsExplicitAndDoesNotDuplicateClaim() {
        content { SelectedClaimTempResult(result().copy(report = report()), {}, identity) }
        compose.onAllNodesWithText(claimText).assertCountEquals(1)
        compose.onNodeWithText("저장된 판정과 이번 판정이 같습니다.").performScrollTo().assertIsDisplayed()
        capture("result-same")
    }
    @Test fun invalidIdentityLeaksNeitherClaimNorSource() {
        content { SelectedClaimTempResult(result().copy(report = report().copy(answerSha256 = "b".repeat(64))), {}, identity) }
        compose.onNodeWithText(claimText).assertDoesNotExist()
        compose.onNodeWithText("자료 A").assertDoesNotExist()
        compose.onNodeWithText(EvidenceVerdict.SUPPORTED_BY_EVIDENCE.evidenceLabel()).assertDoesNotExist()
    }
    @Test fun multiClaimTemporaryPayloadDoesNotRenderAnArbitraryFirstClaim() {
        val invalid = result().copy(report = report().copy(claims = report().claims +
            EvidenceClaim("C2", "표시하면 안 되는 두 번째 주장", EvidenceVerdict.NOT_CHECKED, emptyList(), null)))
        content { SelectedClaimTempResult(invalid, {}, identity) }
        compose.onNodeWithText(claimText).assertDoesNotExist()
        compose.onNodeWithText("표시하면 안 되는 두 번째 주장").assertDoesNotExist()
        compose.onNodeWithText("자료 A").assertDoesNotExist()
    }
    @Test fun sourceComparisonKeepsMetadataCollapsedAndDuplicateSelectionRecoverable() {
        content { ClaimEvidenceSourcePairComparison(identity, report(), ClaimEvidenceReportOrigin.STORED, "C1", {}) }
        compose.onNodeWithText("검색 제공자 · Search").assertDoesNotExist()
        capture("pair-initial")
        compose.onNodeWithText("자료 B · 자료 B").performScrollTo().performClick()
        compose.onNodeWithText("자료 A · E1").performClick()
        compose.onNodeWithText("같은 자료가 양쪽에 선택되어 있습니다. 서로 다른 자료를 선택해 주세요.")
            .performScrollTo().assertIsDisplayed()
        capture("pair-duplicate")
        compose.onNodeWithText("자료 B · 자료 A").performScrollTo().performClick()
        compose.onNodeWithText("자료 C · E3").performClick()
        compose.onNodeWithText("자료 C").performScrollTo().assertIsDisplayed()
    }
    @Test fun focusedSavedDetailHasOnePrimaryAndNoEagerSourceText() {
        content {
            SavedClaimEvidenceDialog(SavedClaimExplorerState.Ready(identity, report(), "C1"), {}, {}, {}, {},
                reverifyAvailable = true)
        }
        compose.onAllNodesWithTag("evidence-primary").assertCountEquals(1)
        compose.onNodeWithText(SELECTED_CLAIM_REVERIFY_LABEL).performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("발췌문 A: 실제 저장된 자료의 일부입니다.").assertDoesNotExist()
        capture("saved-detail")
    }
    @Test fun consentKeepsExactQueryAndThreeExplicitActions() {
        var choice = ""
        val exactQuery = "제품 한국어 English 추가 자료"
        content {
            EvidenceSearchAuthorizationDialog(
                PostAnswerVerificationSearchPrompt("one", PostAnswerVerificationSearchIdentity(1L, 1L, identity.answerSha256, 1L),
                    listOf(AuthorizedTargetedQuery("C1", exactQuery))),
                { choice = "cancel" }, { choice = "local" }, { choice = "web" },
                selectedClaimText = claimText.repeat(20),
            )
        }
        compose.onNodeWithText(exactQuery).assertIsDisplayed()
        compose.onNodeWithText("답변이나 문서 내용은 보내지 않고, 표시된 검색어만 전송합니다.").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("evidence-primary").assertCountEquals(1)
        compose.onNodeWithText("웹에서도 확인").assertHasClickAction()
        compose.onNodeWithText("취소").assertHasClickAction()
        compose.onNodeWithText("기존 출처만 확인").assertHeightIsAtLeast(48.dp)
        capture("consent")
        compose.onNodeWithText("기존 출처만 확인").performClick()
        compose.runOnIdle { assertEquals("local", choice) }
    }
    @Test fun unsupportedPreviewPreservesEveryPartAndReasonWithoutVerdict() {
        val second = "서로 다른 두 번째 문장입니다."
        val explanation = "선택한 내용에 주장이 여러 개 있습니다. 하나만 선택해 주세요."
        content {
            SelectedAnswerSelectionPreview(AnswerTextSelectionDecision.Unsupported(
                listOf(claimText, second), AnswerTextSelectionRefusal.MULTIPLE_CLAIMS, explanation))
        }
        compose.onAllNodesWithText(claimText).assertCountEquals(1)
        compose.onNodeWithText(second).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(explanation).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(SELECTED_CLAIM_TEMP_RESULT_LABEL).assertDoesNotExist()
        compose.onNodeWithText(EvidenceVerdict.SUPPORTED_BY_EVIDENCE.evidenceLabel()).assertDoesNotExist()
        capture("unsupported-preview")
    }
    @Test fun exactLongSelectionUsesScrollableBodyWithPersistentActions() {
        val exact = claimText.repeat(30)
        var cancelled = false
        content {
            EvidenceFocusedDialog({ cancelled = true }, { androidx.compose.material3.Text("선택 문장 확인") },
                { androidx.compose.material3.Text(exact) },
                { androidx.compose.material3.TextButton({}) { androidx.compose.material3.Text("확인 시작") } },
                { androidx.compose.material3.TextButton({ cancelled = true }) { androidx.compose.material3.Text("취소") } })
        }
        compose.onNodeWithText(exact).assertExists()
        compose.onNodeWithText("취소").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, cancelled) }
    }
}
