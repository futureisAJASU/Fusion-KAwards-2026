package com.projectnuke.fusion.provenance

import com.projectnuke.fusion.data.KnowledgeUsageCandidate
import com.projectnuke.fusion.retrieval.LocalRagSourceEntry
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeBacklinkPolicyTest {
    @Test
    fun `requires exact persisted managed file identity and strips hidden source path`() {
        val exact = candidate(1, 11, "정확한 출처 답변", "/managed/a.pdf")
        val similar = candidate(2, 12, "비슷한 경로 답변", "/managed/a.pdf.copy")

        val result = KnowledgeBacklinkPolicy.select("/managed/a.pdf", listOf(similar, exact))

        assertEquals(1, result.size)
        assertEquals(11L, result.single().conversationId)
        assertEquals("정확한 출처 답변", result.single().answerPreview)
        assertFalse(result.single().answerPreview.contains("/managed/a.pdf"))
    }

    @Test
    fun `keeps newest matching conversations bounded and deduplicated`() {
        val candidates = listOf(
            candidate(1, 10, "old", "/managed/a.pdf", createdAt = 1),
            candidate(2, 10, "new same conversation", "/managed/a.pdf", createdAt = 2),
            candidate(3, 11, "second", "/managed/a.pdf", createdAt = 3),
            candidate(4, 12, "third", "/managed/a.pdf", createdAt = 4),
            candidate(5, 13, "fourth", "/managed/a.pdf", createdAt = 5),
        )

        val result = KnowledgeBacklinkPolicy.select("/managed/a.pdf", candidates)

        assertEquals(3, result.size)
        assertEquals(listOf(13L, 12L, 11L), result.map { it.conversationId })
        assertTrue(result.all { it.answerPreview.length <= KnowledgeBacklinkPolicy.MAX_PREVIEW_CHARS })
    }

    @Test
    fun `does not claim identity from truncated receipt path`() {
        val truncated = "/managed/" + "x".repeat(120)
        val original = truncated + "/a.pdf"

        assertTrue(KnowledgeBacklinkPolicy.select(original, listOf(candidate(1, 10, "answer", truncated))).isEmpty())
    }

    @Test
    fun `withholds ambiguous model reasoning from backlink preview`() {
        val answerWithUnownedThought = candidate(
            1,
            10,
            "<think>private reasoning</think> final answer",
            "/managed/a.pdf",
        )

        val result = KnowledgeBacklinkPolicy.select("/managed/a.pdf", listOf(answerWithUnownedThought))

        assertEquals(1, result.size)
        assertFalse(result.single().answerPreview.contains("private reasoning"))
        assertEquals("답변 미리 보기를 표시할 수 없습니다.", result.single().answerPreview)
    }

    private fun candidate(
        messageId: Long,
        conversationId: Long,
        body: String,
        sourcePath: String,
        createdAt: Long = messageId,
    ): KnowledgeUsageCandidate = KnowledgeUsageCandidate(
        messageId = messageId,
        conversationId = conversationId,
        content = LocalRagSourceMetadata.append(
            body,
            listOf(
                LocalRagSourceEntry(
                    fileName = "a.pdf",
                    attachmentId = sourcePath,
                    chunkIndex = 0,
                    startOffset = 0,
                    endOffset = 10,
                    contentHashHex = "a".repeat(64),
                    excerpt = "private source excerpt",
                    rank = 1,
                    lexicalScore = 1,
                    semanticScore = null,
                ),
            ),
        ),
        createdAt = createdAt,
        conversationTitle = "대화 $conversationId",
    )
}
