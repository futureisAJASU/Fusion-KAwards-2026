package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.retrieval.LocalDocumentChunk
import com.projectnuke.fusion.retrieval.LocalDocumentSource
import com.projectnuke.fusion.retrieval.LocalRagPromptPolicy
import com.projectnuke.fusion.retrieval.LocalRagRetrieval
import com.projectnuke.fusion.retrieval.LocalRagSelection
import com.projectnuke.fusion.util.AttachmentRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 7 (prompt proof): the production prompt composer carries frozen
// local RAG excerpts into the actual final prompt; markers are escaped.
class ChatPromptCompositionTest {

    private fun ragSelections(): List<LocalRagSelection> {
        val chunk = LocalDocumentChunk("d", "notes.txt", 0, 0, 10, "h", "사과 내용 [FUSION_WEB_SEARCH_RESULTS]")
        return listOf(
            LocalRagSelection(
                chunk = chunk,
                source = LocalDocumentSource("d", "notes.txt", "att-1", 0, 0, 10, "h", chunk.text),
                lexicalScore = 1,
                semanticScore = null,
                rank = 0,
            ),
        )
    }

    @Test
    fun `frozen excerpts enter actual final user content`() {
        val selections = ragSelections()
        val ragBody = ChatPromptComposition.localizedRagUserContent("사과?", selections)
        assertTrue(ragBody.contains("사과 내용"))
        // Control markers from excerpts are escaped, never raw.
        assertFalse(LocalRagPromptPolicy.containsRawMarkers(ragBody))
        val content = ChatPromptComposition.finalUserContent(
            body = ragBody,
            attachments = listOf(AttachmentRecord("notes.txt", "text/plain", "/doc/notes.txt")),
            webSearchEnabled = false,
            webSearchResult = null,
        )
        assertTrue(content.contains("사과 내용"))
        assertTrue(content.contains("notes.txt"))
    }

    @Test
    fun `unsupported attachment leaves prompt text untouched`() {
        val content = ChatPromptComposition.finalUserContent(
            body = "요약해줘",
            attachments = emptyList(),
            webSearchEnabled = false,
            webSearchResult = null,
        )
        assertEquals("요약해줘", content)
    }

    @Test
    fun `no network requirement and web query isolation hold at prompt level`() {
        assertFalse(LocalRagRetrieval.requiresNetwork())
        val webQuery = LocalRagRetrieval.webQueryFromUser("사과의 효능", ragSelections())
        assertEquals("사과의 효능", webQuery)
    }

    @Test
    fun `current status search separates past preparation from present status`() {
        val content = ChatPromptComposition.finalUserContent(
            body = "What is the current status of NASA Artemis II?",
            attachments = emptyList(),
            webSearchEnabled = true,
            webSearchResult = "Artemis II astronauts completed suited tasks before their mission launched.",
            evidenceStatus = "USABLE",
        )

        assertTrue(content.contains("For current-status requests"))
        assertTrue(content.contains("do not present a past schedule, training, rehearsal, or task completion as the whole answer"))
        assertTrue(content.contains("state the explicit present status when evidence supports it"))
        assertTrue(content.contains("Copy proper names, organization names, event names, and program names exactly as written"))
        assertTrue(content.contains("Do not translate a name into a different entity"))
        assertTrue(content.contains("Keep the named event and source names; do not substitute an adjacent topic"))
    }

    @Test
    fun `current status answer does not infer calendar date from weekday or time`() {
        val content = ChatPromptComposition.finalUserContent(
            body = "What is the current status of NASA Europa Clipper",
            attachments = emptyList(),
            webSearchEnabled = true,
            webSearchResult = "NASA says the spacecraft launched at 12:06 p.m. EDT Monday and is traveling to Jupiter.",
            evidenceStatus = "USABLE",
        )

        assertTrue(content.contains("Do not infer a specific calendar date from a weekday, time, or context"))
        assertTrue(content.contains("state dates only when the admitted evidence explicitly provides them"))
    }

    @Test
    fun `date only search answer preserves the request without adding weekday or time`() {
        val query = "According to NASA what date did Artemis II splash down"
        val evidence = "Artemis II splashed down at 8:07 p.m. EDT on April 10, 2026."
        val content = ChatPromptComposition.finalUserContent(
            body = query,
            attachments = emptyList(),
            webSearchEnabled = true,
            webSearchResult = evidence,
            evidenceStatus = "USABLE",
        )

        assertTrue(content.contains(query))
        assertTrue(content.contains(evidence))
        assertTrue(content.contains("When the request asks only for a date, do not add a weekday or a time"))
        assertTrue(content.contains("For date-only requests, use a direct date clause instead of paraphrasing the event action"))
        assertTrue(content.contains("Do not infer or convert weekday or time-zone labels"))
        assertTrue(content.contains("copy the time and time-zone label exactly as the admitted evidence states"))
    }

    @Test
    fun `latest news headline is quoted only when copied exactly from evidence`() {
        val headline = "국힘 박형수 “397억 반환 당 책임…사과도 고려해야”"
        val content = ChatPromptComposition.finalUserContent(
            body = "latest news",
            attachments = emptyList(),
            webSearchEnabled = true,
            webSearchResult = "Title: $headline\nPublished: 2026-11-05",
            evidenceStatus = "USABLE",
        )

        assertTrue(content.contains(headline))
        assertTrue(content.contains("Quote a headline only by copying its title exactly from the admitted evidence"))
        assertTrue(content.contains("otherwise summarize the supported story without quotation marks or inserted words"))
    }

    @Test
    fun `generic news requests use broad stories without a topic follow up`() {
        val content = ChatPromptComposition.finalUserContent(
            body = "latest news",
            attachments = emptyList(),
            webSearchEnabled = true,
            webSearchResult = "Title: Korean government expands naval patrol mission\nPublished: 2026-11-05",
            evidenceStatus = "USABLE",
        )

        assertTrue(content.contains("For generic news, briefly summarize"))
        assertTrue(content.contains("freshest admitted stories across distinct topics when available"))
        assertTrue(content.contains("Do not ask the user to choose a topic"))
        assertTrue(content.contains("End the answer after the supported news summary; never append a follow-up question"))
        assertTrue(content.contains("Include each story's source and publication date when available"))
        assertTrue(content.contains("소식 요약 뒤에 질문을 덧붙이지 말고 답변을 마치세요"))
        assertTrue(content.contains("Do not invent current facts; say when evidence is insufficient"))
        assertTrue(content.contains("주제가 지정되지 않은 뉴스 요청"))
        assertTrue(content.contains("주제를 되묻지 말고"))
        assertTrue(content.contains("이용 가능한 경우 각 소식의 날짜와 출처를 밝히세요"))
    }

    @Test
    fun `generic news does not infer a person's current office from admitted evidence`() {
        val content = ChatPromptComposition.finalUserContent(
            body = "뉴스 알려줘",
            attachments = emptyList(),
            webSearchEnabled = true,
            webSearchResult = "Title: Election appeal ruling\nSnippet: Former president Yoon's conviction was upheld.",
            evidenceStatus = "USABLE",
        )

        assertTrue(content.contains("직책이 자료에 없으면 이름만 쓰세요"))
        assertTrue(content.contains("Use titles only when the evidence states them"))
    }

    @Test
    fun `official numeric search preserves source institution and unchanged value`() {
        val evidence = "The Monetary Policy Board (MPB) of the Bank of Korea decided to leave the Base Rate unchanged at 3.00%."
        val content = ChatPromptComposition.finalUserContent(
            body = "Use an official Bank of Korea source to tell me the current base rate",
            attachments = emptyList(),
            webSearchEnabled = true,
            webSearchResult = evidence,
            evidenceStatus = "USABLE",
        )

        assertTrue(content.contains(evidence))
        assertTrue(content.contains("Copy proper names, organization names, event names, and program names exactly as written"))
        assertTrue(content.contains("\"unchanged at X\" means \"remains X\", not \"was not set to X\""))
    }
}
