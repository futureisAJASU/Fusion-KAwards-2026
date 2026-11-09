package com.projectnuke.fusion.research

import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.chat.AnswerContextRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchPackExporterTest {
    @Test
    fun `markdown emits visible answer and stable numbered source records without local paths`() {
        val markdown = ResearchPackExporter.markdown(snapshot())

        assertNotNull(markdown)
        assertTrue(markdown!!.contains("## Answer\n\nVisible final answer"))
        assertTrue(markdown.contains("[1] Public paper — https://example.org/paper"))
        assertTrue(markdown.contains("[2] Notes — report.pdf, p. 4 (local document)"))
        assertFalse(markdown.contains("/data/user/0"))
        assertFalse(markdown.contains("C:\\Users\\junyo\\private.txt"))
        assertTrue(markdown.contains("[local path omitted]"))
        assertTrue(markdown.contains("Route: LOCAL_MODEL"))
        assertFalse(markdown.contains("hiddenPrompt"))
    }

    @Test
    fun `json is structured and redacts sensitive URL query values`() {
        val json = ResearchPackExporter.json(
            snapshot().copy(sources = listOf(
                ResearchPackSource(
                    ResearchPackSource.Type.WEB,
                    "Private API page",
                    url = "https://example.org/paper?api_key=secret-value&section=3#private-fragment",
                ),
            )),
        )

        assertNotNull(json)
        assertTrue(json!!.contains("\"type\": \"FUSION_RESEARCH_PACK\""))
        assertTrue(json.contains("\"citation\": \"[1]\""))
        assertTrue(json.contains("api_key=REDACTED"))
        assertTrue(json.contains("section=3"))
        assertFalse(json.contains("secret-value"))
        assertFalse(json.contains("private-fragment"))
    }

    @Test
    fun `only durable Research context receipt is exported`() {
        val normalReceipt = AnswerContextReceipt.build(AnswerContextRoute.LOCAL_MODEL, interactionMode = "NORMAL")

        val prepared = ResearchPackExporter.prepare(snapshot().copy(contextReceipt = normalReceipt))

        assertNotNull(prepared)
        assertNull(prepared!!.contextReceipt)
    }

    @Test
    fun `oversized or empty answer cannot be exported`() {
        assertNull(ResearchPackExporter.prepare(snapshot().copy(visibleAnswer = " ")))
        assertNull(ResearchPackExporter.markdown(snapshot().copy(visibleAnswer = "x".repeat(ResearchPackExporter.MAX_ANSWER_CHARS + 1))))
    }

    private fun snapshot() = ResearchPackSnapshot(
        conversationTitle = "Research title",
        query = "Research query",
        visibleAnswer = "Visible final answer from C:\\Users\\junyo\\private.txt",
        createdAt = 1_728_000_000_000L,
        projectName = "Semiconductor notes",
        sources = listOf(
            ResearchPackSource(ResearchPackSource.Type.WEB, "Public paper", "https://example.org/paper"),
            ResearchPackSource(
                ResearchPackSource.Type.DOCUMENT,
                "Notes",
                documentName = "/data/user/0/com.app/files/report.pdf",
                pageNumber = 4,
            ),
        ),
        audit = ResearchPackAudit(
            state = ResearchPackAudit.State.CURRENT,
            checkedAt = 1_728_000_000_000L,
            coverage = ResearchPackAudit.Coverage.PARTIALLY_AUDITED,
            claimCount = 1,
            supportedCount = 1,
        ),
        contextReceipt = AnswerContextReceipt.build(
            route = AnswerContextRoute.LOCAL_MODEL,
            interactionMode = "RESEARCH",
            projectContextUsed = true,
            projectInstructionsApplied = true,
            localRagDocumentCount = 1,
            localRagChunkCount = 2,
            webSourceCount = 1,
        ),
    )
}
