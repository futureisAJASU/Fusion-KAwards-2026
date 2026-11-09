package com.projectnuke.fusion.retrieval

// Production KoEn-E5 semantic adapter for the C4 local attachment RAG seam.
//
// Reuses the single accepted owner (KoEnE5RetrievalOwner) exactly as wired for
// semantic Evidence: no new ONNX owner/session, the existing suspension,
// mutex lifecycle, generation fencing, and failure semantics. The batched
// KoEn API is the preferred path: one suspend rank() call per bounded
// candidate subset; per-pair synchronous blocking calls are never used.
//
// Failure → lexical-only: any owner unavailability yields an empty map, so
// retrieval degrades to lexical-only without failing the answer path.
class KoEnE5LocalSemanticBatchRanker(
    private val matcher: SemanticCandidateMatcher,
) : LocalSemanticBatchRanker {
    override suspend fun scoreBatch(
        query: String,
        chunks: List<LocalDocumentChunk>,
    ): Map<String, Double?> {
        if (chunks.isEmpty()) return emptyMap()
        val segments = chunks.map { chunk ->
            SemanticSegment(
                id = "${chunk.docId}#${chunk.chunkIndex}",
                text = chunk.text.take(FusionEmbeddingLimits.MAX_TEXT_CHARS),
            )
        }
        // Raw bound stays MAX_TEXT_CHARS; the matcher reserves prefix
        // capacity centrally so this maximum-allowed payload cannot fail as
        // INVALID_INPUT after prefixing.
        val claim = query.take(FusionEmbeddingLimits.MAX_TEXT_CHARS)
        val result = matcher.rank(
            claim = claim,
            segments = segments,
            limit = segments.size,
        )
        // Owner unavailable / cancelled / inference failure → lexical-only.
        if (result.unavailableReason != null) return emptyMap()
        // Ranking-only cosine signals; never truth or confidence.
        return result.matches.associate { match -> match.segmentId to match.score.toDouble() }
    }
}
