package com.projectnuke.fusion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceFingerprintTest {

    private val visibleAnswer = "This is the visible assistant answer."
    private val searchMetadata = """
        [FUSION_SEARCH_SOURCES_JSON]
        [{"title": "Result 1", "url": "https://example.com"}]
        [/FUSION_SEARCH_SOURCES_JSON]
    """.trimIndent()

    private val fusionMetrics = "<fusion_metrics>some metrics</fusion_metrics>"
    private val fusionThinking = "<fusion_thinking>some thinking</fusion_thinking>"
    private val attachmentEnvelope = "<fusion_attachment_v2>name|mime|/path/to/file</fusion_attachment_v2>"

    @Test
    fun `same visible answer plus different search-source metadata yields same hash`() {
        val answerWithSources = visibleAnswer + "\n\n" + searchMetadata
        val answerWithoutSources = visibleAnswer
        val hash1 = EvidenceFingerprint.canonicalAnswerHash(answerWithSources)
        val hash2 = EvidenceFingerprint.canonicalAnswerHash(answerWithoutSources)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `same visible answer plus different fusion metrics yields same hash`() {
        val answerWithMetrics = visibleAnswer + "\n\n" + fusionMetrics
        val hash1 = EvidenceFingerprint.canonicalAnswerHash(answerWithMetrics)
        val hash2 = EvidenceFingerprint.canonicalAnswerHash(visibleAnswer)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `same visible answer plus different reasoning block yields same hash`() {
        val answerWithThinking = visibleAnswer + "\n\n" + fusionThinking
        val hash1 = EvidenceFingerprint.canonicalAnswerHash(answerWithThinking)
        val hash2 = EvidenceFingerprint.canonicalAnswerHash(visibleAnswer)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `visible answer change yields different hash`() {
        val hash1 = EvidenceFingerprint.canonicalAnswerHash("First visible answer.")
        val hash2 = EvidenceFingerprint.canonicalAnswerHash("Second visible answer.")
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `hidden attachment metadata does not alter hash`() {
        val answerWithAttachment = visibleAnswer + "\n\n" + attachmentEnvelope
        val hash1 = EvidenceFingerprint.canonicalAnswerHash(answerWithAttachment)
        val hash2 = EvidenceFingerprint.canonicalAnswerHash(visibleAnswer)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `deterministic across repeated calls`() {
        val hash1 = EvidenceFingerprint.canonicalAnswerHash(visibleAnswer)
        val hash2 = EvidenceFingerprint.canonicalAnswerHash(visibleAnswer)
        val hash3 = EvidenceFingerprint.canonicalAnswerHash(visibleAnswer)
        assertEquals(hash1, hash2)
        assertEquals(hash2, hash3)
    }

    @Test
    fun `stale evidence is detected`() {
        val evidenceSha = EvidenceFingerprint.canonicalAnswerHash(visibleAnswer)
        val differentAnswer = "A completely different answer."
        assertTrue(EvidenceFingerprint.isStale(evidenceSha, differentAnswer))
    }

    @Test
    fun `fresh evidence is not stale`() {
        val evidenceSha = EvidenceFingerprint.canonicalAnswerHash(visibleAnswer)
        assertTrue(!EvidenceFingerprint.isStale(evidenceSha, visibleAnswer))
    }

    @Test
    fun `empty answer produces deterministic hash`() {
        val hash1 = EvidenceFingerprint.canonicalAnswerHash("")
        val hash2 = EvidenceFingerprint.canonicalAnswerHash("")
        assertEquals(hash1, hash2)
    }

    @Test
    fun `visible line-break change yields different hash`() {
        val hash1 = EvidenceFingerprint.canonicalAnswerHash("A\nB")
        val hash2 = EvidenceFingerprint.canonicalAnswerHash("A B")
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `visible code indentation change yields different hash`() {
        val a = "if (x) {\n    foo()\n}"
        val b = "if (x) {\nfoo()\n}"
        assertNotEquals(EvidenceFingerprint.canonicalAnswerHash(a), EvidenceFingerprint.canonicalAnswerHash(b))
    }

    @Test
    fun `hidden metadata presence does not mask visible formatting`() {
        val base = "A\nB"
        val withMetrics = base + "\n\n" + fusionMetrics
        assertEquals(
            EvidenceFingerprint.canonicalAnswerHash(base),
            EvidenceFingerprint.canonicalAnswerHash(withMetrics)
        )
        assertNotEquals(
            EvidenceFingerprint.canonicalAnswerHash(base),
            EvidenceFingerprint.canonicalAnswerHash("A B")
        )
    }

    @Test
    fun `answer wrapper unwrap keeps visible text stable`() {
        val wrapped = "<fusion_answer>hello world</fusion_answer>"
        assertEquals(
            EvidenceFingerprint.canonicalAnswerHash("hello world"),
            EvidenceFingerprint.canonicalAnswerHash(wrapped)
        )
    }

    @Test
    fun `platform line endings normalize`() {
        assertEquals(
            EvidenceFingerprint.canonicalAnswerHash("A\nB"),
            EvidenceFingerprint.canonicalAnswerHash("A\r\nB")
        )
    }
}
