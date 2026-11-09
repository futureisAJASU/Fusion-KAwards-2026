package com.projectnuke.fusion.ui

import com.projectnuke.fusion.translation.DedicatedTranslationBackendManifest
import com.projectnuke.fusion.translation.LocalTranslationCoordinator
import com.projectnuke.fusion.translation.LocalTranslator
import com.projectnuke.fusion.translation.TranslationBounds
import com.projectnuke.fusion.translation.TranslationPolicy
import com.projectnuke.fusion.translation.TranslationRequest
import com.projectnuke.fusion.translation.TranslationResult
import com.projectnuke.fusion.translation.TranslationRoute
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Chat "번역에서 열기": the seeded source is exactly the user-visible
// answer (thinking/metrics/envelopes/metadata removed) via the shared
// presentation path; opening never auto-translates.
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantTranslationHandoffTest {

    private fun seed(
        content: String,
        reasoningEnabled: Boolean = false,
        generationModeKey: String? = "LOCAL_MODEL",
    ): String = assistantVisibleAnswerForHandoff(content, reasoningEnabled, generationModeKey)

    @Test
    fun `visible normal answer seeds translation exactly`() {
        val answer = "HBM은 고대역폭 메모리입니다.\n두 번째 문단도 그대로 유지됩니다."
        assertEquals(answer, seed(answer))
    }

    @Test
    fun `thinking block is removed`() {
        val raw = "<fusion_thinking>숨은 추론 과정</fusion_thinking>\n\n보이는 답변입니다."
        val seeded = seed(raw)
        assertFalse(seeded.contains("숨은 추론"))
        assertTrue(seeded.contains("보이는 답변입니다."))
    }

    @Test
    fun `metrics block is removed`() {
        val raw = "보이는 답변입니다.\n<fusion_metrics>12.3 tok/s</fusion_metrics>"
        val seeded = seed(raw)
        assertFalse(seeded.contains("tok/s"))
        assertTrue(seeded.contains("보이는 답변입니다."))
    }

    @Test
    fun `search source metadata is removed`() {
        val raw = "보이는 답변입니다.\n\n[FUSION_SEARCH_SOURCES_JSON]\n" +
            """[{"title":"T","url":"https://example.test/hidden","snippet":"s","queryUsed":"q"}]""" +
            "\n[/FUSION_SEARCH_SOURCES_JSON]"
        val seeded = seed(raw)
        assertFalse(seeded.contains("example.test"))
        assertFalse(seeded.contains("FUSION_SEARCH_SOURCES_JSON"))
        assertTrue(seeded.contains("보이는 답변입니다."))
    }

    @Test
    fun `trust routing metadata is removed`() {
        val raw = "보이는 답변입니다.\n\n[FUSION_TRUST_ROUTE_JSON]\n" +
            """{"route":"LOCAL","reason":"X"}""" +
            "\n[/FUSION_TRUST_ROUTE_JSON]"
        val seeded = seed(raw)
        assertFalse(seeded.contains("FUSION_TRUST_ROUTE_JSON"))
        assertTrue(seeded.contains("보이는 답변입니다."))
    }

    @Test
    fun `local rag provenance metadata is removed`() {
        val raw = "보이는 답변입니다.\n\n[FUSION_LOCAL_RAG_SOURCES_JSON]\n" +
            """[{"fileName":"secret.txt","excerpt":"비밀 발췌"}]""" +
            "\n[/FUSION_LOCAL_RAG_SOURCES_JSON]"
        val seeded = seed(raw)
        assertFalse(seeded.contains("비밀 발췌"))
        assertFalse(seeded.contains("FUSION_LOCAL_RAG_SOURCES_JSON"))
        assertTrue(seeded.contains("보이는 답변입니다."))
    }

    @Test
    fun `attachment envelope is removed`() {
        val raw = AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(AttachmentRecord("notes.txt", "text/plain", "/root/notes.txt")),
            "보이는 답변입니다.",
        )
        val seeded = seed(raw)
        assertFalse(seeded.contains("notes.txt"))
        assertFalse(seeded.contains("fusion_attachment"))
        assertTrue(seeded.contains("보이는 답변입니다."))
    }

    @Test
    fun `combined hidden blocks leave only the visible answer`() {
        // Production-like terminal ordering: hidden provenance blocks are
        // appended after the answer (trust terminal last, as the codec
        // requires for stripping).
        val raw = "<fusion_thinking>추론</fusion_thinking>\n" +
            "첫 문장.\n" +
            "<fusion_metrics>m</fusion_metrics>\n" +
            "[FUSION_SEARCH_SOURCES_JSON]\n[]\n[/FUSION_SEARCH_SOURCES_JSON]\n" +
            "[FUSION_LOCAL_RAG_SOURCES_JSON]\n[]\n[/FUSION_LOCAL_RAG_SOURCES_JSON]\n" +
            "[FUSION_TRUST_ROUTE_JSON]\n{}\n[/FUSION_TRUST_ROUTE_JSON]"
        assertEquals("첫 문장.", seed(raw))
    }

    @Test
    fun `long answer is seeded without silent truncation`() {
        val long = "가".repeat(TranslationBounds.MAX_INPUT_CHARS + 500)
        val seeded = seed(long)
        // The truthful source is seeded whole; the existing Translation
        // policy (not the handoff) blocks oversized input truthfully.
        assertEquals(long, seeded)
        assertFalse(
            StandaloneTranslationPolicy.canStartWith(seeded, "en", TranslationBackendsForTest.current),
        )
        assertTrue(
            StandaloneTranslationPolicy.translateDisabledReason(
                StandaloneTranslationState(sourceText = seeded, targetLanguage = "en"),
                TranslationBackendsForTest.current,
            )!!.contains(TranslationBounds.MAX_INPUT_CHARS.toString()),
        )
    }

    // ---- controller open semantics (no auto-translate) ----------------------

    private object TranslationBackendsForTest {
        val current get() = com.projectnuke.fusion.translation.TranslationBackends.CURRENT
    }

    private class RecordingBackend : LocalTranslator {
        override val capabilities = DedicatedTranslationBackendManifest(
            backendId = "test-backend",
            implementationVersion = "0.1",
            modelArtifactId = "test-artifact",
            supportedTargetLanguages = setOf("ko", "en"),
            supportedSourceLanguages = setOf("ko", "en"),
            supportsAutomaticSourceDetection = true,
            supportsContext = false,
            supportsStyle = false,
        ).toCapabilities()

        var translateCalls = 0

        override suspend fun translate(request: TranslationRequest): TranslationResult {
            translateCalls++
            val artifact = TranslationPolicy.makeArtifact(request, "translated: ${request.originalText}", capabilities)
                ?: return TranslationResult.Failure("invalid")
            return TranslationResult.Success(artifact)
        }
    }

    @Test
    fun `open seeds source exactly with no auto translate request`() = runTest {
        val backend = RecordingBackend()
        val controller = StandaloneTranslationController(
            scope = this,
            coordinator = LocalTranslationCoordinator(backend),
        )
        controller.open("보이는 답변입니다.")
        runCurrent()

        assertEquals("보이는 답변입니다.", controller.state.value.sourceText)
        assertEquals(0, backend.translateCalls)
        assertEquals(StandaloneTranslationPhase.IDLE, controller.state.value.phase)
        assertNull(controller.state.value.artifact)
    }

    @Test
    fun `second open replaces the first and cancels stale work`() = runTest {
        val backend = RecordingBackend()
        val controller = StandaloneTranslationController(
            scope = this,
            coordinator = LocalTranslationCoordinator(backend),
        )
        controller.open("첫 번째 답변")
        controller.open("두 번째 답변")
        runCurrent()

        assertEquals("두 번째 답변", controller.state.value.sourceText)
        assertEquals(0, backend.translateCalls)
    }

    @Test
    fun `close returns to a clean session`() = runTest {
        val backend = RecordingBackend()
        val controller = StandaloneTranslationController(
            scope = this,
            coordinator = LocalTranslationCoordinator(backend),
        )
        controller.open("보이는 답변입니다.")
        runCurrent()
        controller.close()
        assertEquals("", controller.state.value.sourceText)
        assertEquals(StandaloneTranslationPhase.IDLE, controller.state.value.phase)
        assertEquals(0, backend.translateCalls)
    }
}
