package com.projectnuke.fusion.util

import com.projectnuke.fusion.retrieval.LocalRagSourceEntry
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadata
import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.search.appendSearchSourcesMetadata
import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.chat.AnswerContextRoute
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.ui.storedAssistantContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleMessageContentTest {
    private fun v1Message(name: String, path: String, body: String): String =
        "<fusion_attachment>\nname=$name\nmime=text/plain\npath=$path\n</fusion_attachment>\n\n$body"

    private fun v2Message(name: String, path: String, body: String): String =
        "<fusion_attachment_v2>$name|text/plain|$path</fusion_attachment_v2>\n\n$body"

    private fun v3Message(name: String, path: String, body: String): String =
        AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(AttachmentRecord(name, "text/plain", path)),
            body,
        )

    private fun sourcesMessage(visible: String, marker: String): String =
        appendSearchSourcesMetadata(
            visible,
            listOf(
                WebSearchSource(
                    title = "Title $marker",
                    source = null,
                    url = "https://example.test/$marker",
                    snippet = "Snippet $marker",
                    publishedAt = null,
                    providerType = WebSearchProviderType.FREE_DEFAULT,
                    providerDisplayName = "무료 기본 검색",
                    searchCategory = null,
                    queryUsed = "query $marker",
                    rank = 1,
                    score = null,
                    qualityHints = emptyList(),
                ),
            ),
        )

    @Test
    fun `v1 attachment message exposes visible body only`() {
        val visible = visibleMessageContent(v1Message("report.txt", "/root/report.txt", "visible body alpha"))
        assertTrue(visible.contains("visible body alpha"))
        assertFalse(visible.contains("report.txt"))
        assertFalse(visible.contains("/root/report.txt"))
        assertFalse(visible.contains("fusion_attachment"))
    }

    @Test
    fun `v2 attachment message exposes visible body only`() {
        val visible = visibleMessageContent(v2Message("notes.md", "/root/notes.md", "visible body beta"))
        assertTrue(visible.contains("visible body beta"))
        assertFalse(visible.contains("notes.md"))
        assertFalse(visible.contains("/root/notes.md"))
        assertFalse(visible.contains("fusion_attachment"))
    }

    @Test
    fun `v3 attachment message exposes visible body without base64 or path`() {
        val raw = v3Message("photo.png", "/root/photo.png", "visible body gamma")
        assertTrue("stored form must carry a v3 envelope", raw.contains("fusion_attachment_v3"))
        val visible = visibleMessageContent(raw)
        assertTrue(visible.contains("visible body gamma"))
        assertFalse(visible.contains("photo.png"))
        assertFalse(visible.contains("/root/photo.png"))
        assertFalse(visible.contains("fusion_attachment_v3"))
        // Base64 payload of the envelope must not leak either.
        val payload = raw.substringAfter("<fusion_attachment_v3>").substringBefore("</fusion_attachment_v3>")
        assertTrue(payload.isNotEmpty())
        assertFalse(visible.contains(payload.take(16)))
    }

    @Test
    fun `hidden search metadata is not visible unless also in the answer`() {
        val marker = "qzxw Vestibule 4821"
        val hiddenOnly = sourcesMessage("plain visible answer", marker)
        val visible = visibleMessageContent(hiddenOnly)
        assertTrue(visible.contains("plain visible answer"))
        assertFalse(visible.contains(marker))
        assertFalse(visible.contains("FUSION_SEARCH_SOURCES_JSON"))

        val repeated = sourcesMessage("the answer mentions qzxw Vestibule 4821 aloud", marker)
        assertTrue(visibleMessageContent(repeated).contains("qzxw Vestibule 4821"))
    }

    @Test
    fun `ordinary messages pass through unchanged`() {
        assertEquals("plain hello world", visibleMessageContent("plain hello world"))
        assertEquals("", visibleMessageContent(""))
    }

    @Test
    fun `metrics and thinking stay hidden while answer inner text stays visible`() {
        val raw = "shown intro<fusion_metrics>{\"tokens\": 42}</fusion_metrics>" +
            "<fusion_thinking>secret reasoning trek</fusion_thinking>" +
            "<fusion_answer>final answer text</fusion_answer> tail"
        val visible = visibleMessageContent(raw)
        assertTrue(visible.contains("shown intro"))
        assertTrue(visible.contains("final answer text"))
        assertTrue(visible.contains("tail"))
        assertFalse(visible.contains("secret reasoning trek"))
        assertFalse(visible.contains("fusion_metrics"))
        assertFalse(visible.contains("fusion_thinking"))
        assertFalse(visible.contains("fusion_answer"))
    }

    @Test
    fun `export covers v1 v2 v3 with placeholders and no raw leakage`() {
        val v1 = exportMessageBody(v1Message("report.txt", "/root/report.txt", "body one"))
        assertTrue(v1.contains("[첨부파일: report.txt]"))
        assertTrue(v1.contains("body one"))
        assertFalse(v1.contains("/root/report.txt"))
        assertFalse(v1.contains("fusion_attachment"))

        val v2 = exportMessageBody(v2Message("notes.md", "/root/notes.md", "body two"))
        assertTrue(v2.contains("[첨부파일: notes.md]"))
        assertFalse(v2.contains("fusion_attachment_v2"))

        val v3raw = v3Message("photo.png", "/root/photo.png", "body three")
        val v3 = exportMessageBody(v3raw)
        assertTrue(v3.contains("[첨부파일: photo.png]"))
        assertTrue(v3.contains("body three"))
        assertFalse(v3.contains("fusion_attachment_v3"))
        val payload = v3raw.substringAfter("<fusion_attachment_v3>").substringBefore("</fusion_attachment_v3>")
        assertFalse(v3.contains(payload.take(16)))
        assertFalse(v3.contains("/root/photo.png"))
    }

    @Test
    fun `export drops hidden search metadata`() {
        val exported = exportMessageBody(sourcesMessage("exported answer", "Zebra Crossing 99"))
        assertTrue(exported.contains("exported answer"))
        assertFalse(exported.contains("Zebra Crossing 99"))
        assertFalse(exported.contains("FUSION_SEARCH_SOURCES_JSON"))
    }

    @Test
    fun `title uses visible body and never exposes v3 envelope`() {
        val titled = titleMessageBody(v3Message("photo.png", "/root/photo.png", "trip highlights"))
        assertTrue(titled.contains("trip highlights"))
        assertFalse(titled.contains("fusion_attachment_v3"))
        assertFalse(titled.contains("photo.png"))
    }

    @Test
    fun `displayAttachmentName prefers name then basename then fallback`() {
        assertEquals("report.txt", displayAttachmentName(AttachmentRecord("report.txt", "t", "/root/other.txt")))
        assertEquals("other.txt", displayAttachmentName(AttachmentRecord("", "t", "/root/other.txt")))
        assertEquals("파일", displayAttachmentName(AttachmentRecord("", "t", "")))
    }

    @Test
    fun `Fusion-owned reasoning is hidden by every visible surface`() {
        val raw = "intro<fusion_thinking>secret\nreasoning</fusion_thinking><fusion_answer>answer</fusion_answer>"
        assertEquals("introanswer", stripHiddenMetadataBlocks(raw))
        assertEquals("introanswer", visibleMessageContent(raw))
        assertEquals("introanswer", visibleMessageContentPreservingWhitespace(raw))
        assertEquals("introanswer", titleMessageBody(raw))
        assertEquals("introanswer", exportMessageBody(raw))
        assertEquals(ExplicitReasoningContent("secret\nreasoning", "introanswer"), splitExplicitReasoning(raw))
    }

    @Test
    fun `generic visible content preserves model-native literal markers`() {
        val qwenLiteral = "Qwen emits <think>literal reasoning markup</think> here"
        assertEquals(qwenLiteral, visibleMessageContent(qwenLiteral))
        assertEquals("<thinking>literal</thinking>", visibleMessageContent("<thinking>literal</thinking>"))
        assertEquals("example <think", visibleMessageContent("example <think"))
        assertEquals(qwenLiteral, titleMessageBody(qwenLiteral))
        assertEquals(qwenLiteral, exportMessageBody(qwenLiteral))
        assertEquals(qwenLiteral, visibleMessageContentPreservingWhitespace(qwenLiteral))
    }

    @Test
    fun `unclosed and nested reasoning stays hidden including apparent answer wrappers`() {
        listOf("fusion_thinking").forEach { outer ->
            listOf("fusion_thinking").forEach { inner ->
                val prefix = "visible<$outer>outer<$inner>inner</$inner>tail"
                assertEquals("visible", stripHiddenMetadataBlocks(prefix))
                assertEquals("visible", stripHiddenMetadataBlocks("$prefix<fusion_answer>secret</fusion_answer>"))
                val complete = "$prefix</$outer><fusion_answer>answer</fusion_answer>"
                assertEquals(ExplicitReasoningContent("outerinnertail", "visibleanswer"), splitExplicitReasoning(complete))
            }
        }
    }

    @Test
    fun `mismatched closes cannot release nested reasoning`() {
        val raw = "visible<fusion_thinking>outer<fusion_thinking>inner</fusion_answer>secret</fusion_thinking>still hidden"
        assertEquals("visible", stripHiddenMetadataBlocks(raw))
        assertEquals("outerinnersecretstill hidden", splitExplicitReasoning(raw).thinking)
    }

    @Test
    fun `every streaming prefix withholds partial tags and hidden reasoning`() {
        listOf("fusion_thinking").forEach { tag ->
            val stream = "<$tag>outer<$tag>inner</$tag>tail</$tag><fusion_answer>answer</fusion_answer>"
            for (length in 0..stream.length) {
                val raw = "visible" + stream.take(length)
                val split = splitExplicitReasoning(raw)
                val answerStart = stream.indexOf("answer</fusion_answer>")
                val expected = "visible" + "answer".take((length - answerStart).coerceIn(0, 6))
                assertEquals("tag=$tag length=$length", expected, split.answer)
                assertEquals(split.answer, stripHiddenMetadataBlocks(raw))
                assertFalse(split.thinking.contains('<'))
            }
        }
    }

    @Test
    fun `stray closes and partial control tags are hidden but ordinary markup is kept`() {
        listOf("fusion_thinking", "fusion_answer", "fusion_metrics").forEach { tag ->
            listOf("<$tag>", "</$tag>").forEach { token ->
                for (length in 1 until token.length) {
                    assertEquals("visible", stripHiddenMetadataBlocks("visible" + token.take(length)))
                }
            }
        }
        assertEquals("</think></thinking>answer", stripHiddenMetadataBlocks("</think></thinking></fusion_thinking>answer"))
        assertEquals("visible<think>literal</think>", stripHiddenMetadataBlocks("visible<think>literal</think>"))
        assertEquals("visible<thinking>literal</thinking>", stripHiddenMetadataBlocks("visible<thinking>literal</thinking>"))
        assertEquals("example <think", stripHiddenMetadataBlocks("example <think"))
        assertEquals("a < b and <div>text</div>", stripHiddenMetadataBlocks("a < b and <div>text</div>"))
        assertEquals("literal <thinker", stripHiddenMetadataBlocks("literal <thinker"))
        assertEquals("  line one\n    line two", splitExplicitReasoning("  line one\n    line two  ").answer)
    }

    @Test
    fun `answer context receipt is removed before parsing marker-like payloads`() {
        val raw = "[FUSION_ANSWER_CONTEXT_JSON]{\"note\":\"<think>secret\"}[/FUSION_ANSWER_CONTEXT_JSON]" +
            "<fusion_thinking>reasoning</fusion_thinking><fusion_answer>visible</fusion_answer>"
        assertEquals(ExplicitReasoningContent("reasoning", "visible"), splitExplicitReasoning(raw))
        assertEquals("visible", stripHiddenMetadataBlocks(raw))
    }

    private fun ragMessage(): String = LocalRagSourceMetadata.append(
        "visible rag answer body",
        listOf(
            LocalRagSourceEntry(
                fileName = "secret-report.txt",
                attachmentId = "/storage/emulated/0/Fusion/attachments/uuid-secret-report.txt",
                chunkIndex = 0,
                startOffset = 0,
                endOffset = 64,
                contentHashHex = "abcdef0123456789",
                excerpt = "exclusive excerpt Zebra Crossing 99",
                rank = 1,
                lexicalScore = 7,
                semanticScore = null,
            ),
        ),
    )

    @Test
    fun `local rag provenance is not searchable titled or exported`() {
        val raw = ragMessage()
        assertTrue("stored form must carry the rag block", raw.contains("FUSION_LOCAL_RAG_SOURCES_JSON"))

        val visible = visibleMessageContent(raw)
        assertTrue(visible.contains("visible rag answer body"))
        assertFalse(visible.contains("FUSION_LOCAL_RAG_SOURCES_JSON"))
        assertFalse(visible.contains("uuid-secret-report.txt"))
        assertFalse(visible.contains("/storage/emulated/0"))
        assertFalse(visible.contains("exclusive excerpt Zebra Crossing 99"))

        val titled = titleMessageBody(raw)
        assertTrue(titled.contains("visible rag answer body"))
        assertFalse(titled.contains("FUSION_LOCAL_RAG_SOURCES_JSON"))
        assertFalse(titled.contains("uuid-secret-report.txt"))

        val exported = exportMessageBody(raw)
        assertTrue(exported.contains("visible rag answer body"))
        assertFalse(exported.contains("FUSION_LOCAL_RAG_SOURCES_JSON"))
        assertFalse(exported.contains("uuid-secret-report.txt"))
        assertFalse(exported.contains("exclusive excerpt Zebra Crossing 99"))

        val preserved = visibleMessageContentPreservingWhitespace(raw)
        assertTrue(preserved.contains("visible rag answer body"))
        assertFalse(preserved.contains("FUSION_LOCAL_RAG_SOURCES_JSON"))
        assertFalse(preserved.contains("uuid-secret-report.txt"))
    }

    @Test
    fun `a stored receipt is removed from every canonical answer surface`() {
        val stored = storedAssistantContent(
            answer = "visible stored answer",
            trustRoutingDecision = TrustRoutingDecision.local(TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE),
            contextReceipt = AnswerContextReceipt.build(
                route = AnswerContextRoute.LOCAL_MODEL,
                webSourceCount = 1,
            ),
        )

        assertEquals("visible stored answer", visibleMessageContent(stored))
        assertEquals("visible stored answer", exportMessageBody(stored))
        assertEquals("visible stored answer", titleMessageBody(stored))
        assertEquals("visible stored answer", visibleMessageContentPreservingWhitespace(stored))
        assertFalse(visibleMessageContent(stored).contains("FUSION_ANSWER_CONTEXT_JSON"))
        assertFalse(exportMessageBody(stored).contains("schemaVersion"))
        assertFalse(titleMessageBody(stored).contains("webSourceCount"))
        assertFalse(visibleMessageContentPreservingWhitespace(stored).contains("FUSION_ANSWER_CONTEXT_JSON"))
    }
}
