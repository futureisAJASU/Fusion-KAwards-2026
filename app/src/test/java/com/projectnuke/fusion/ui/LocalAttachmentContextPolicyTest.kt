package com.projectnuke.fusion.ui

import com.projectnuke.fusion.retrieval.LocalRagSourceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAttachmentContextPolicyTest {

    private fun entry(
        fileName: String,
        attachmentId: String = fileName,
        chunkIndex: Int = 0,
    ) = LocalRagSourceEntry(
        fileName = fileName,
        attachmentId = attachmentId,
        chunkIndex = chunkIndex,
        startOffset = 0,
        endOffset = 100,
        contentHashHex = "a".repeat(64),
        excerpt = "발췌 내용",
        rank = 1,
        lexicalScore = 10,
        semanticScore = null,
    )

    // ---- supported vs unsupported affordance ----

    @Test
    fun `supported text type gets local context affordance`() {
        assertTrue(LocalAttachmentContextPolicy.isLocalContextType("text/plain", "note.txt"))
        assertTrue(LocalAttachmentContextPolicy.isLocalContextType("text/markdown", "doc.md"))
        assertTrue(LocalAttachmentContextPolicy.isLocalContextType("application/json", "data.json"))
        assertTrue(LocalAttachmentContextPolicy.isLocalContextType("", "notes.txt"))
        assertEquals("로컬 문서", LocalAttachmentContextPolicy.localContextLabel("text/plain", "note.txt"))
        assertTrue(
            LocalAttachmentContextPolicy.localContextDescription("text/plain", "note.txt") != null,
        )
    }

    @Test
    fun `supported pdf type gets local context affordance`() {
        assertTrue(LocalAttachmentContextPolicy.isLocalContextType("application/pdf", "doc.pdf"))
        assertEquals("로컬 문서", LocalAttachmentContextPolicy.localContextLabel("application/pdf", "doc.pdf"))
        assertTrue(LocalAttachmentContextPolicy.localContextDescription("application/pdf", "doc.pdf") != null)
    }

    @Test
    fun `unsupported type never claims rag use`() {
        assertFalse(
            LocalAttachmentContextPolicy.isLocalContextType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "doc.docx",
            ),
        )
        assertFalse(LocalAttachmentContextPolicy.isLocalContextType("application/octet-stream", "photo.png"))
        assertFalse(LocalAttachmentContextPolicy.isLocalContextType("", "archive.zip"))
        assertNull(
            LocalAttachmentContextPolicy.localContextLabel(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "doc.docx",
            ),
        )
        assertNull(
            LocalAttachmentContextPolicy.localContextDescription(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "doc.docx",
            ),
        )
    }

    @Test
    fun `unsupported message is actionable without pretending otherwise`() {
        val message = LocalAttachmentContextPolicy.unsupportedTypeMessage()
        assertTrue(message.contains("txt"))
        assertTrue(message.contains("md"))
        assertTrue(message.contains("json"))
        assertTrue(message.contains("csv"))
        assertFalse(message.contains("번역"))
    }

    @Test
    fun `text unavailable message tells user to re-add the file`() {
        val message = LocalAttachmentContextPolicy.textUnavailableMessage()
        assertTrue(message.contains("읽지 못했어요"))
        assertTrue(message.contains("다시 추가"))
    }

    // ---- provenance copy never claims verification ----

    @Test
    fun `surface labels never carry verified language`() {
        val verificationWords = listOf("검증", "확인", "인증", "증명")
        val labels = LocalAttachmentContextPolicy.primaryLabels() +
            LocalAttachmentContextPolicy.answerReferenceSummary(listOf(entry("a.txt"), entry("b.txt"))) +
            LocalAttachmentContextPolicy.documentLocationLabel(0) +
            LocalAttachmentContextPolicy.sourceKindLabel(FusionSourceKind.LOCAL_ATTACHMENT)
        labels.forEach { label ->
            verificationWords.forEach { word ->
                assertFalse("label '$label' must not contain verification word '$word'", label.contains(word))
            }
        }
    }

    @Test
    fun `disclaimer keeps reference-context meaning without certainty`() {
        val disclaimer = LocalAttachmentContextPolicy.localProvenanceDisclaimer()
        assertTrue(disclaimer.contains("참고 자료"))
        assertTrue(disclaimer.contains("검증된 내용이라는 뜻이 아니에요"))
    }

    // ---- web vs local distinction ----

    @Test
    fun `web and local source kinds stay distinct`() {
        assertNotEquals(
            LocalAttachmentContextPolicy.sourceKindLabel(FusionSourceKind.WEB_SOURCE),
            LocalAttachmentContextPolicy.sourceKindLabel(FusionSourceKind.LOCAL_ATTACHMENT),
        )
        assertEquals("검색 출처", LocalAttachmentContextPolicy.sourceKindLabel(FusionSourceKind.WEB_SOURCE))
        assertEquals("첨부 문서", LocalAttachmentContextPolicy.sourceKindLabel(FusionSourceKind.LOCAL_ATTACHMENT))
        assertEquals("첨부 문서 참조", LocalAttachmentContextPolicy.localSourcesDialogTitle())
    }

    // ---- human-readable capsule copy ----

    @Test
    fun `reference summary counts distinct documents not chunks`() {
        val oneDoc = listOf(
            entry("a.txt", attachmentId = "id1", chunkIndex = 0),
            entry("a.txt", attachmentId = "id1", chunkIndex = 1),
        )
        assertEquals("첨부 문서 1개 참고", LocalAttachmentContextPolicy.answerReferenceSummary(oneDoc))

        val twoDocs = oneDoc + entry("b.md", attachmentId = "id2", chunkIndex = 0)
        assertEquals("첨부 문서 2개 참고", LocalAttachmentContextPolicy.answerReferenceSummary(twoDocs))
    }

    @Test
    fun `document location uses approximate chunk position only`() {
        assertEquals("문서 #1", LocalAttachmentContextPolicy.documentLocationLabel(0))
        assertEquals("문서 #3", LocalAttachmentContextPolicy.documentLocationLabel(2))
        assertFalse(LocalAttachmentContextPolicy.documentLocationLabel(0).contains("offset"))
    }

    // ---- fail-closed copy for changed sources ----

    @Test
    fun `changed source message states the answer did not start`() {
        val message = LocalAttachmentContextPolicy.changedSourceMessage()
        assertTrue(message.contains("답변을 시작하지 않았어요"))
        assertTrue(message.contains("다시 첨부"))
        assertFalse(message.contains("계속"))
    }
}