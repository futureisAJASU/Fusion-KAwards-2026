package com.projectnuke.fusion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.fusion.audit.VerificationPairRequest
import com.projectnuke.fusion.audit.VerifierBackendResult
import com.projectnuke.fusion.data.*
import com.projectnuke.fusion.privacy.AuthorizedTargetedQuery
import com.projectnuke.fusion.privacy.PostAnswerVerificationSearchIdentity
import com.projectnuke.fusion.model.RuntimeCpuDeviceSignals
import com.projectnuke.fusion.model.RuntimeCpuPolicyInput
import com.projectnuke.fusion.model.RuntimePerformancePreference
import com.projectnuke.fusion.verifier.FusionVerifierRuntimeOwner
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EvidenceDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun repeatedEvidenceOpenCloseRecreatesVerifiedRuntimeSafely() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("v0_9c/parity_fixture.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        val row = (0 until fixture.getJSONArray("rows").length())
            .map { fixture.getJSONArray("rows").getJSONObject(it) }
            .first { it.getString("kind") == "gold" }
        val request = VerificationPairRequest(row.getString("claim"), row.getString("evidence"), evidenceIdentity = "evidence-ui-cycle")
        val verifierDir = File(context.filesDir, "verifier/current")
        val proxy = FusionVerifierRuntimeOwner.proxy(verifierDir) {
            val signals = RuntimeCpuDeviceSignals.observe(context.applicationContext)
            RuntimeCpuPolicyInput(RuntimePerformancePreference(), signals)
        }
        val visible = androidx.compose.runtime.mutableStateOf(false)
        val report = EvidenceReport(1, "a".repeat(64), 1, EVIDENCE_VERIFIER_VERSION, emptyList(), emptyList())
        try {
            compose.setContent {
                MaterialTheme {
                    if (visible.value) {
                        EvidenceDialog(EvidenceUiState(execution = EvidenceExecutionState.COMPLETE, report = report), {}, {},
                            { visible.value = false }, {})
                    } else {
                        TextButton(onClick = { visible.value = true }) { Text("Open Evidence") }
                    }
                }
            }
            repeat(3) { cycle ->
                compose.onNodeWithText("Open Evidence").performClick()
                compose.onNodeWithText("닫기").assertIsDisplayed()
                assertTrue("verified C2 inference must work while Evidence is open", proxy.verify(request) is VerifierBackendResult.Verified)
                assertNotNull(FusionVerifierRuntimeOwner.currentBackendForTests())
                FusionVerifierRuntimeOwner.close()
                assertNull(FusionVerifierRuntimeOwner.currentBackendForTests())
                compose.onNodeWithText("닫기").performClick()
                compose.onNodeWithText("Open Evidence").assertIsDisplayed()
                assertEquals(false, visible.value)
            }
            val artifactName = if (android.os.Build.DEVICE == "y2q") {
                "s20_continuation_evidence_ui_cycles.json"
            } else {
                "s24_continuation_evidence_ui_cycles.json"
            }
            File(requireNotNull(context.getExternalFilesDir(null)), artifactName)
                .writeText(org.json.JSONObject().put("device_codename", android.os.Build.DEVICE)
                    .put("cycles", 3).put("verified_runtime_per_cycle", true)
                    .put("close_recreate_boundary", "UI_OPEN_VERIFY_RUNTIME_CLOSE_UI_CLOSE").toString(2), Charsets.UTF_8)
        } finally {
            FusionVerifierRuntimeOwner.close()
        }
    }

    @Test fun emptyReportShowsNoCheckableClaims() {
        val report = EvidenceReport(1, "a".repeat(64), 1, EVIDENCE_VERIFIER_VERSION, emptyList(), emptyList())
        compose.setContent {
            MaterialTheme {
                EvidenceDialog(EvidenceUiState(execution = EvidenceExecutionState.COMPLETE, report = report), {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("확인할 주장을 찾지 못했습니다.").assertIsDisplayed()
    }

    @Test fun zeroClaimReportShowsIncompleteWindowAndOverlengthWarnings() {
        val report = EvidenceReport(
            1, "a".repeat(64), 1, EVIDENCE_VERIFIER_VERSION, emptyList(), emptyList(),
            candidateWindowTruncated = true,
            overlengthSegmentsExcluded = true,
        )
        compose.setContent {
            MaterialTheme {
                EvidenceDialog(EvidenceUiState(execution = EvidenceExecutionState.COMPLETE, report = report), {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("기록된 확인 주장은 없지만, 답변 일부가 확인 범위에 포함되지 않았어요. 아래 제한 안내를 확인해 주세요.")
            .assertIsDisplayed()
        compose.onNodeWithText("확인 후보는 최대 48개입니다. 그보다 많은 후보는 확인 범위에서 빠질 수 있어요.")
            .assertIsDisplayed()
        compose.onNodeWithText("개별 확인 문장이 허용 길이(10000자)를 넘어 일부 문장을 확인하지 않았어요.")
            .assertIsDisplayed()
        compose.onNodeWithText("확인할 사실 주장을 찾지 못했습니다.").assertDoesNotExist()
    }

    @Test fun currentEvidenceShowsPartialCoverageAndBoundedSummary() {
        val source = EvidenceSource("E1", "Source", "Publisher", "https://example.com", "Excerpt", null, "WEB", "Web", "query")
        val claim = EvidenceClaim("C1", "A claim", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, listOf("E1"), null)
        val report = EvidenceReport(1, "a".repeat(64), 1, EVIDENCE_VERIFIER_VERSION, listOf(claim), listOf(source))
        compose.setContent {
            MaterialTheme {
                EvidenceDialog(EvidenceUiState(execution = EvidenceExecutionState.COMPLETE, report = report), {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("확인 가능한 답변 주장을 연결된 자료와 대조합니다.").assertIsDisplayed()
        compose.onNodeWithText("자료와의 일치 여부는 답변 전체의 사실성을 보장하지 않습니다.").assertIsDisplayed()
        compose.onNodeWithText("일부 주장 확인").assertIsDisplayed()
        compose.onNodeWithText("자료에서 확인  1").assertIsDisplayed()
        compose.onNodeWithText("외부 자료").assertIsDisplayed()
        compose.onNodeWithText("답변 전체 확인 완료").assertDoesNotExist()
    }

    @Test fun staleReportOffersExplicitRecheck() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                EvidenceDialog(EvidenceUiState(stored = EvidenceStoredState.STALE), { retries++ }, {}, {}, {})
            }
        }
        compose.onNodeWithText("이 답변이 변경되어 이전 근거 확인 결과를 사용할 수 없습니다.").assertIsDisplayed()
        compose.onNodeWithText("다시 확인").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun failureUsesBoundedCopyAndRetry() {
        compose.setContent {
            MaterialTheme {
                EvidenceDialog(EvidenceUiState(execution = EvidenceExecutionState.FAILED), {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("사실 확인을 완료하지 못했습니다. 다시 시도할 수 있습니다.").assertIsDisplayed()
        compose.onNodeWithText("다시 확인").assertIsDisplayed()
    }

    @Test fun postAnswerSearchConfirmationShowsExactQueriesAndThreeChoices() {
        val prompt = PostAnswerVerificationSearchPrompt(
            operationId = "operation-1",
            identity = PostAnswerVerificationSearchIdentity(7L, 41L, "a".repeat(64), 41L),
            queries = listOf(AuthorizedTargetedQuery("C1", "Acme ProductX2 2026")),
        )
        var choice: PostAnswerVerificationSearchChoice? = null
        compose.setContent {
            MaterialTheme {
                EvidenceSearchAuthorizationDialog(
                    prompt = prompt,
                    onCancel = { choice = PostAnswerVerificationSearchChoice.CANCEL },
                    onExistingSourcesOnly = { choice = PostAnswerVerificationSearchChoice.EXISTING_SOURCES_ONLY },
                    onWebSearch = { choice = PostAnswerVerificationSearchChoice.WEB },
                )
            }
        }

        compose.onNodeWithText("웹에서도 확인할까요?").assertIsDisplayed()
        compose.onNodeWithText("Acme ProductX2 2026").assertIsDisplayed()
        compose.onNodeWithText("기존 출처만 확인").assertIsDisplayed()
        compose.onNodeWithText("웹에서도 확인").assertIsDisplayed()
        compose.onNodeWithText("취소").assertIsDisplayed()
        compose.onNodeWithText("웹에서도 확인").performClick()
        compose.runOnIdle { assertEquals(PostAnswerVerificationSearchChoice.WEB, choice) }
    }

    @Test fun claimExpandsSourceWithSeparateQueryProvenance() {
        val source = EvidenceSource("E1", "Acme release", "Acme", "https://example.com", "Acme released ProductX2.", "2026", "FREE_DEFAULT", "Search", "Acme query")
        val claim = EvidenceClaim("C1", "Acme released ProductX2.", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, listOf("E1"), null)
        val report = EvidenceReport(1, "a".repeat(64), 1, EVIDENCE_VERIFIER_VERSION, listOf(claim), listOf(source))
        var opened: String? = null
        compose.setContent {
            MaterialTheme {
                EvidenceDialog(EvidenceUiState(execution = EvidenceExecutionState.COMPLETE, report = report), {}, {}, {}, { opened = it })
            }
        }
        compose.onNodeWithText("자료 보기").performScrollTo().performClick()
        compose.onNodeWithText("출처 정보").performScrollTo().performClick()
        compose.onNodeWithText("검색어 (검색 기록): Acme query").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("원문 열기").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("https://example.com", opened) }
    }

    @Test fun outdatedReportExplainsRulesUpgradeAndOffersExplicitRecheck() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                EvidenceDialog(EvidenceUiState(stored = EvidenceStoredState.OUTDATED), { retries++ }, {}, {}, {})
            }
        }
        compose.onNodeWithText(
            "\uADFC\uAC70 \uD655\uC778 \uADDC\uCE59\uC774 \uC5C5\uB370\uC774\uD2B8\uB418\uC5B4 \uC774\uC804 \uACB0\uACFC\uB97C \uB2E4\uC2DC \uD655\uC778\uD574\uC57C \uD569\uB2C8\uB2E4.",
        ).assertIsDisplayed()
        compose.onNodeWithText("다시 확인").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun legacyCompleteReportDoesNotRenderCurrentVerdicts() {
        val claim = EvidenceClaim("C1", "legacy claim", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, emptyList(), null)
        val report = EvidenceReport(1, "a".repeat(64), 1, "rules-v1", listOf(claim), emptyList())
        compose.setContent {
            MaterialTheme {
                EvidenceDialog(EvidenceUiState(execution = EvidenceExecutionState.COMPLETE, report = report), {}, {}, {}, {})
            }
        }
        compose.onNodeWithText(
            "\uADFC\uAC70 \uD655\uC778 \uADDC\uCE59\uC774 \uC5C5\uB370\uC774\uD2B8\uB418\uC5B4 \uC774\uC804 \uACB0\uACFC\uB97C \uB2E4\uC2DC \uD655\uC778\uD574\uC57C \uD569\uB2C8\uB2E4.",
        ).assertIsDisplayed()
        compose.onNodeWithText("legacy claim").assertDoesNotExist()
    }
}
