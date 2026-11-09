package com.projectnuke.fusion.retrieval

/** Symmetric feature-task embedding of bounded source texts. */
interface SymmetricEmbeddingSource {
    suspend fun embedSources(texts: List<String>): EmbeddingResult
}

object DisabledSymmetricEmbeddingSource : SymmetricEmbeddingSource {
    override suspend fun embedSources(texts: List<String>): EmbeddingResult =
        EmbeddingResult.Failure(EmbeddingFailureReason.CLOSED, "disabled")
}
