package com.projectnuke.fusion.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.fusion.chat.*
import com.projectnuke.fusion.data.*
import com.projectnuke.fusion.ui.theme.FusionTheme
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in manual device fixture. No database, preferences, network or clipboard writes.
 * This is a rendering/control session, not an automated correctness assertion.
 * Exit by creating external-files/p41-audit-finish; the 20-minute limit is a failsafe.
 */
class InteractiveVisualAuditTest {
    private val compose = createComposeRule()
    @get:Rule val rules = composeHostTestRules(compose)

    @Test fun inspectProductionSurfacesWithSyntheticData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Interactive audit is opt-in", InstrumentationRegistry.getArguments().getString("p41.manual") == "true")
        val finish = File(instrumentation.targetContext.getExternalFilesDir(null), "p41-audit-finish")
        val select = File(instrumentation.targetContext.getExternalFilesDir(null), "p41-audit-select")
        check(!finish.exists()) { "Remove only the task-owned finish marker before starting a new session" }
        val answer = "Acme released ProductX2 in 2026."
        val message = MessageEntity(7001L, 71L, "assistant", answer, 1L)
        val identity = EvidenceAnswerIdentity(message.id, EvidenceFingerprint.canonicalAnswerHash(answer))
        val claim = EvidenceClaim("C1", answer, EvidenceVerdict.SUPPORTED_BY_EVIDENCE, listOf("E1", "E2"), null)
        val sources = listOf("A", "B").mapIndexed { index, label ->
            EvidenceSource("E${index + 1}", "합성 자료 $label", "예시 발행처 $label", "https://example.com/$label",
                "합성 발췌문 $label: Acme released ProductX2 in 2026.", "2026-01-01", "FREE_DEFAULT", "Synthetic", "Acme ProductX2 2026")
        }
        val report = EvidenceReport(1, identity.answerSha256, System.currentTimeMillis() - 86_400_000L,
            EVIDENCE_VERIFIER_VERSION, listOf(claim), sources)
        val store = object : EvidenceUiStore {
            override suspend fun load(message: MessageEntity) = EvidenceStore.LoadResult.Missing
            override suspend fun saveComplete(message: MessageEntity, report: EvidenceReport): EvidenceStore.SaveResult =
                error("Manual fixture must never persist")
        }
        val verifier = EvidenceUiVerifier { verified, plan ->
            EvidenceVerificationResult.Complete(report.copy(answerSha256 = EvidenceFingerprint.canonicalAnswerHash(verified.content),
                checkedAt = System.currentTimeMillis(), claims = plan.claims.map {
                    EvidenceClaim(it.candidate.id, it.candidate.text, EvidenceVerdict.INSUFFICIENT_EVIDENCE, emptyList(), null)
                }, sources = emptyList()))
        }
        val current = "# 한국어 English\n동일한 문장입니다.\n" +
            (1..12).joinToString("\n") { "현재 답변 $it: 한국어와 English가 함께 있는 긴 문장입니다." } +
            "\n```kotlin\nval current = true\n```"
        val comparison = current.replace("현재 답변", "비교 답변").replace("true", "false")
        compose.setContentOnResumedTestHost {
            FusionTheme(FusionThemeMode.DARK) {
                var page by remember { mutableStateOf("home") }
                var selectedClaim by remember { mutableStateOf<String?>(null) }
                var snapshot by remember { mutableStateOf(ResponseVersionComparePolicy.snapshot(listOf(
                    ResponseVersionCompareEntry(1, 1, comparison), ResponseVersionCompareEntry(2, 2, answer),
                    ResponseVersionCompareEntry(3, 3, current)), 3)!!) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(FusionSpacing.screen)
                        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap)) {
                        Text("P4.1 합성 데이터 · 수동 점검", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { page = "home" }) { Text("점검 목록") }
                        when (page) {
                            "home" -> {
                                TextButton(onClick = { page = "compare" }) { Text("답변 비교 열기") }
                                TextButton(onClick = { page = "saved" }) { Text("저장 주장 열기") }
                                TextButton(onClick = { page = "selection" }) { Text("실제 텍스트 선택") }
                                TextButton(onClick = { page = "answer" }) { Text("마크다운 답변") }
                                TextButton(onClick = { page = "result" }) { Text("임시 결과 열기") }
                            }
                            "selection" -> SelectedAnswerTextVerificationSurface(message, answer, store, verifier, null,
                                AnswerSurfaceIdentity.of(message.conversationId, message), MaterialTheme.typography.bodyLarge,
                                MaterialTheme.colorScheme.onSurface, {}, { expected -> expected.takeIf { it == message } })
                            "answer" -> AssistantMessage(content = current, createdAt = 1L, webSearchEnabled = false,
                                onRetry = {}, onRegenerate = {}, onToggleWebSearch = {}, onSaveAsDecision = {}, showNextActions = true)
                        }
                    }
                    when (page) {
                        "compare" -> ResponseVersionCompareDialog(snapshot,
                            { ResponseVersionComparePolicy.select(snapshot, it)?.let { next -> snapshot = next } }, {}, { page = "home" })
                        "saved" -> SavedClaimEvidenceDialog(SavedClaimExplorerState.Ready(identity, report, selectedClaim),
                            { selectedClaim = it }, { selectedClaim = null }, { page = "home" }, {})
                        "result" -> FusionReadingDialog(onDismissRequest = { page = "home" }, title = { Text("합성 임시 결과") },
                            text = { SelectedClaimTempResult(SelectedClaimReverificationState.Result("C1",
                                report.copy(checkedAt = System.currentTimeMillis(), claims = listOf(claim.copy(verdict = EvidenceVerdict.INSUFFICIENT_EVIDENCE))),
                                null, false, storedReport = report), {}, identity) },
                            confirmButton = { TextButton(onClick = { page = "home" }) { Text("닫기") } })
                    }
                }
            }
        }
        val deadline = SystemClock.elapsedRealtime() + 20 * 60_000L
        while (!finish.exists() && SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()
            if (select.exists()) {
                check(select.delete())
                val node = compose.onNodeWithText(answer)
                val width = node.fetchSemanticsNode().size.width.toFloat()
                node.performTouchInput {
                    down(Offset(1f, center.y))
                    advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
                    moveTo(Offset(width - 1f, center.y), 120L)
                    up()
                }
            }
            SystemClock.sleep(250)
        }
    }
}
