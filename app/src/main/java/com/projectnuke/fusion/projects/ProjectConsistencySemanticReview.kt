package com.projectnuke.fusion.projects

import com.projectnuke.fusion.audit.AuditVerificationLabel
import com.projectnuke.fusion.audit.VerificationPairRequest
import com.projectnuke.fusion.audit.VerifierBackend
import com.projectnuke.fusion.audit.VerifierBackendAvailability
import com.projectnuke.fusion.audit.VerifierBackendResult
import com.projectnuke.fusion.audit.verifySafely
import com.projectnuke.fusion.retrieval.EmbeddingFailureReason
import com.projectnuke.fusion.retrieval.EmbeddingResult
import com.projectnuke.fusion.retrieval.FusionEmbeddingLimits
import com.projectnuke.fusion.retrieval.SemanticSimilarity
import com.projectnuke.fusion.retrieval.SymmetricEmbeddingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal const val MAX_PROJECT_CONSISTENCY_VERIFIER_PAIRS = 12
internal const val MIN_PROJECT_CONSISTENCY_PAIR_SIMILARITY = 0.65f

/** Deterministic-first, ephemeral semantic discovery and verifier-assisted review. */
internal object ProjectConsistencySemanticReview {
    private data class PairKey(val firstId: String, val secondId: String) {
        companion object {
            fun of(a: String, b: String) = if (a <= b) PairKey(a, b) else PairKey(b, a)
        }
    }

    private data class Candidate(
        val key: PairKey,
        val first: ProjectConsistencySource,
        val second: ProjectConsistencySource,
        val score: Float,
    )

    private val candidateOrder = compareByDescending<Candidate> { it.score }
        .thenBy { it.key.firstId }
        .thenBy { it.key.secondId }

    suspend fun review(
        input: List<ProjectConsistencySource>,
        embeddings: SymmetricEmbeddingSource,
        verifier: VerifierBackend,
    ): List<ProjectConsistencyIssue> {
        val sources = input.asSequence()
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.id }
            .take(MAX_PROJECT_CONSISTENCY_SOURCES)
            .map { it.copy(title = it.title.trim().take(180), text = it.text.take(MAX_PROJECT_CONSISTENCY_EXCERPT_CHARS)) }
            .toList()
        val deterministic = ProjectConsistencyPolicy.reviewCandidates(sources)
        val deterministicVisible = deterministic.take(MAX_PROJECT_CONSISTENCY_ISSUES)
        val remaining = MAX_PROJECT_CONSISTENCY_ISSUES - deterministicVisible.size
        if (remaining <= 0 || sources.size < 2 || verifier.descriptor.availability == VerifierBackendAvailability.UNAVAILABLE) {
            return deterministicVisible
        }
        coroutineContext.ensureActive()

        val coveredPairs = deterministic.asSequence()
            .map { PairKey.of(it.sourceA.id, it.sourceB.id) }
            .toSet()
        val texts = sources.map { source -> "${source.title}\n${source.text}".take(FusionEmbeddingLimits.MAX_TEXT_CHARS) }
        val vectors = embedBatched(embeddings, texts) ?: return deterministicVisible
        if (vectors.size != sources.size || vectors.map(FloatArray::size).distinct().size != 1) return deterministicVisible

        val candidates = withContext(Dispatchers.Default) {
            val candidateLimit = minOf(remaining, MAX_PROJECT_CONSISTENCY_VERIFIER_PAIRS)
            val ranked = ArrayList<Candidate>(candidateLimit)
            for (i in sources.indices) {
                for (j in i + 1 until sources.size) {
                    coroutineContext.ensureActive()
                    val a = sources[i]
                    val b = sources[j]
                    val key = PairKey.of(a.id, b.id)
                    if (key in coveredPairs) continue
                    val score = SemanticSimilarity.cosine(vectors[i], vectors[j])
                    if (score >= MIN_PROJECT_CONSISTENCY_PAIR_SIMILARITY) {
                        val (first, second) = if (a.id <= b.id) a to b else b to a
                        val candidate = Candidate(key, first, second, score)
                        val insertion = ranked.indexOfFirst { candidateOrder.compare(candidate, it) < 0 }
                            .let { if (it < 0) ranked.size else it }
                        if (insertion < candidateLimit) {
                            ranked.add(insertion, candidate)
                            if (ranked.size > candidateLimit) ranked.removeAt(candidateLimit)
                        }
                    }
                }
            }
            ranked.toList()
        }

        val supplemental = ArrayList<ProjectConsistencyIssue>(candidates.size)
        for (candidate in candidates) {
            coroutineContext.ensureActive()
            val labels = ArrayList<AuditVerificationLabel>(2)
            val directions = listOf(candidate.first to candidate.second, candidate.second to candidate.first)
            for ((claim, evidence) in directions) {
                coroutineContext.ensureActive()
                val request = VerificationPairRequest(
                    claimText = "${claim.title}\n${claim.text}".take(10_000),
                    evidenceText = "${evidence.title}\n${evidence.text}".take(10_000),
                    evidenceIdentity = evidence.id,
                )
                when (val result = verifier.verifySafely(request)) {
                    is VerifierBackendResult.Verified -> labels += result.label
                    VerifierBackendResult.Unavailable, is VerifierBackendResult.Failure -> Unit
                }
            }
            if (AuditVerificationLabel.CONTRADICTED in labels && AuditVerificationLabel.SUPPORTED !in labels) {
                supplemental += ProjectConsistencyIssue(
                    id = "${ProjectConsistencyIssueKind.POTENTIAL_CONFLICT.name}:${candidate.key.firstId}:${candidate.key.secondId}",
                    kind = ProjectConsistencyIssueKind.POTENTIAL_CONFLICT,
                    statement = "의미가 비슷한 두 자료에서 서로 충돌할 수 있는 표현이 발견되었습니다. 오류나 거짓으로 판정하지 않습니다.",
                    sourceA = candidate.first,
                    sourceB = candidate.second,
                    followUp = "두 자료의 기준 시점, 조건, 적용 범위와 원문을 확인해 주세요.",
                    origin = ProjectConsistencyIssueOrigin.SEMANTIC_VERIFIER,
                )
            }
        }
        return (deterministicVisible + supplemental).distinctBy { it.id }.take(MAX_PROJECT_CONSISTENCY_ISSUES)
    }

    private suspend fun embedBatched(
        source: SymmetricEmbeddingSource,
        texts: List<String>,
    ): List<FloatArray>? {
        val vectors = ArrayList<FloatArray>(texts.size)
        var start = 0
        while (start < texts.size) {
            coroutineContext.ensureActive()
            val end = minOf(start + FusionEmbeddingLimits.MAX_TEXTS_PER_CALL, texts.size)
            when (val result = source.embedSources(texts.subList(start, end))) {
                is EmbeddingResult.Success -> vectors += result.vectors
                is EmbeddingResult.Failure -> {
                    if (result.reason == EmbeddingFailureReason.CANCELLED) throw CancellationException("embedding cancelled")
                    return null
                }
            }
            start = end
        }
        return vectors
    }
}
