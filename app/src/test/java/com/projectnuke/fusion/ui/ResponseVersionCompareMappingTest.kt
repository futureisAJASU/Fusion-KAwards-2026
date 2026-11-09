package com.projectnuke.fusion.ui

import com.projectnuke.fusion.retrieval.LocalRagSourceEntry
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadata
import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.search.appendSearchSourcesMetadata
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingMetadataCodec
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 compare mapping: every compared version passes through the same
 * canonical visible-answer authority the bubble renders. Hidden machine
 * metadata and attachment envelopes can never appear in a compared body,
 * and blank versions are unusable so Compare stays unavailable for them.
 */
class ResponseVersionCompareMappingTest {

    private fun map(
        content: String,
        reasoningEnabled: Boolean = false,
        generationModeKey: String? = "LOCAL_MODEL",
    ): String = assistantVisibleAnswerForHandoff(content, reasoningEnabled, generationModeKey)

    private fun productionVersionContent(answer: String): String {
        val withSearch = appendSearchSourcesMetadata(
            answer,
            listOf(
                WebSearchSource(
                    title = "Hidden Title",
                    source = "HiddenSource",
                    url = "https://hidden.example.test/v",
                    snippet = "hidden-snippet",
                    publishedAt = null,
                    providerType = WebSearchProviderType.FREE_DEFAULT,
                    providerDisplayName = "HiddenProvider",
                    searchCategory = "web",
                    queryUsed = "hidden-query",
                    rank = 1,
                    score = 0.9,
                ),
            ),
        )
        val withRag = LocalRagSourceMetadata.append(
            withSearch,
            listOf(
                LocalRagSourceEntry(
                    fileName = "secret-doc.pdf",
                    attachmentId = "att-secret",
                    chunkIndex = 1,
                    startOffset = 0,
                    endOffset = 64,
                    contentHashHex = "cafef00d",
                    excerpt = "비밀 발췌 본문",
                    rank = 0,
                    lexicalScore = 3,
                    semanticScore = 0.5,
                    pageNumber = 2,
                ),
            ),
        )
        return TrustRoutingMetadataCodec.append(
            withRag,
            TrustRoutingDecision(
                route = TrustRoute.SEARCH,
                reason = TrustRoutingReason.CURRENT_OR_RECENT,
                signals = emptySet(),
            ),
        )
    }

    @Test
    fun `production mapping strips hidden machine metadata`() {
        val mapped = map(productionVersionContent("비교 대상 답변입니다."))

        assertEquals("비교 대상 답변입니다.", mapped)
        assertFalse(mapped.contains("hidden.example.test"))
        assertFalse(mapped.contains("FUSION_SEARCH_SOURCES_JSON"))
        assertFalse(mapped.contains("FUSION_TRUST_ROUTE_JSON"))
        assertFalse(mapped.contains("FUSION_LOCAL_RAG_SOURCES_JSON"))
        assertFalse(mapped.contains("비밀 발췌 본문"))
        assertFalse(mapped.contains("secret-doc.pdf"))
        assertFalse(mapped.contains("hidden-query"))
    }

    @Test
    fun `attachment envelope and local path never appear in compare visible answer`() {
        val stored = AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(AttachmentRecord("report.pdf", "application/pdf", "/data/user/0/pkg/secret/report.pdf")),
            "첨부된 문서 요약입니다.",
        )
        val mapped = map(stored)

        assertEquals("첨부된 문서 요약입니다.", mapped)
        assertFalse(mapped.contains("/data/user"))
        assertFalse(mapped.contains("report.pdf"))
        assertFalse(mapped.contains("fusion_attachment"))
    }

    @Test
    fun `blank canonical versions are unusable for compare`() {
        // Metadata-only version: nothing user-visible remains.
        val metadataOnly = TrustRoutingMetadataCodec.append(
            "",
            TrustRoutingDecision(
                route = TrustRoute.SEARCH,
                reason = TrustRoutingReason.CURRENT_OR_RECENT,
                signals = emptySet(),
            ),
        )
        assertTrue(map(metadataOnly).isBlank())
        assertTrue(map("   ").isBlank())
    }

    @Test
    fun `distinct versions map to distinct compare bodies`() {
        val first = map(productionVersionContent("첫 번째 버전 답변"))
        val second = map(productionVersionContent("두 번째 버전 답변"))

        assertEquals("첫 번째 버전 답변", first)
        assertEquals("두 번째 버전 답변", second)
        assertTrue(first != second)
    }
}
