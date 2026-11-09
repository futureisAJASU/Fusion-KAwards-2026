package com.projectnuke.fusion.projects

import android.content.Context
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ProjectDecisionEntity
import com.projectnuke.fusion.retrieval.EmbeddingResult
import com.projectnuke.fusion.retrieval.FusionEmbeddingLimits
import com.projectnuke.fusion.retrieval.KoEnE5RetrievalOwner
import com.projectnuke.fusion.retrieval.SymmetricEmbeddingSource
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Local, bounded, read-only candidate review for Decision capture assistance.
 *
 * Admission rules:
 * - Only ACTIVE decisions from the SAME Project.
 * - SUPERSEDED decisions excluded from replacement suggestions.
 * - Title/token overlap and durable source identity may admit.
 * - Semantic similarity (via existing shared symmetric proxy) is ranking
 *   support ONLY; it never admits a candidate alone.
 * - Score/cosine is never exposed.
 */
internal fun interface ProjectDecisionCandidateSource {
    suspend fun activeDecisions(projectId: Long, limit: Int): List<ProjectDecisionEntity>
}

internal class ProjectDecisionIntelligenceRepository(
    private val source: ProjectDecisionCandidateSource,
    private val symmetric: SymmetricEmbeddingSource,
    embeddingBatchLimit: Int = FusionEmbeddingLimits.MAX_TEXTS_PER_CALL,
) {
    private val embeddingBatchLimit = embeddingBatchLimit.coerceIn(1, FusionEmbeddingLimits.MAX_TEXTS_PER_CALL)

    constructor(database: AppDatabase, context: Context) : this(
        ProjectDecisionCandidateSource { projectId, limit ->
            database.projectDecisionDao().getActiveDecisions(projectId, limit)
        },
        KoEnE5RetrievalOwner.symmetricProxy(
            context.applicationContext.assets,
            File(context.applicationContext.filesDir, "koen-e5"),
        ),
    )

    suspend fun suggestActiveCandidates(
        projectId: Long,
        currentTitle: String,
        currentRationale: String,
        frozenSourceConversationId: Long? = null,
        frozenSourceMessageId: Long? = null,
    ): List<ProjectDecisionIntelligencePolicy.Candidate> = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        if (projectId <= 0L) return@withContext emptyList()

        val candidates = source.activeDecisions(projectId, ProjectDecisionIntelligencePolicy.MAX_DECISION_CANDIDATES)
            .filter { it.projectId == projectId && it.status == "ACTIVE" }
            .distinctBy { it.id }
            .take(ProjectDecisionIntelligencePolicy.MAX_DECISION_CANDIDATES)

        // Deterministic admission signals (no similarity required)
        val canonicalCurrentTitle = canonical(currentTitle)

        val admittedInfo = mutableListOf<Pair<ProjectDecisionEntity, String>>()

        for (candidate in candidates) {
            coroutineContext.ensureActive()
            if (candidate.projectId != projectId || candidate.status != "ACTIVE") continue

            val reasons = mutableListOf<String>()
            val title = canonical(candidate.title)
            val rationaleText = candidate.rationale

            // Title near-equality / strong overlap
            if (canonicalCurrentTitle.isNotEmpty() && title == canonicalCurrentTitle) {
                reasons += "title_near"
            } else {
                val titleOverlap = overlap(currentTitle + "\n" + currentRationale, candidate.title + "\n" + rationaleText)
                if (titleOverlap >= 0.7f) {
                    reasons += "content_related"
                }
            }

            // Shared durable source identity
            val sameSource = sameDurableSource(candidate, frozenSourceConversationId, frozenSourceMessageId)
            if (sameSource) {
                reasons += "same_source"
            }

            if (reasons.isNotEmpty()) {
                val label = if (reasons.contains("same_source")) "same_source" else reasons.first()
                admittedInfo += candidate to label
            }
        }

        val admitted = admittedInfo.distinctBy { it.first.id }
        val deterministicOrder = compareByDescending<ProjectDecisionEntity> { it.createdAt }.thenBy { it.id }
        if (admitted.isEmpty()) return@withContext emptyList()

        // Rank every deterministically admitted Decision before applying the UI's top-3 bound.
        // The current draft is the first input and is embedded exactly once.
        val batchTexts = listOf(
            (currentTitle + "\n" + currentRationale).take(ProjectDecisionIntelligencePolicy.MAX_DECISION_EMBED_CHARS),
        ) + admitted.map { (decision, _) ->
            (decision.title + "\n" + decision.rationale).take(ProjectDecisionIntelligencePolicy.MAX_DECISION_EMBED_CHARS)
        }
        val vectors = try {
            embedInBoundedBatches(batchTexts)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val scores = vectors?.let { allVectors ->
            val current = allVectors.first()
            admitted.mapIndexed { index, _ -> cosine(current, allVectors[index + 1]) }
        }
        val semanticRankingAvailable = scores != null && scores.all { it.isFinite() }
        val rankingResults = admitted.mapIndexed { index, (decision, reason) ->
                ProjectDecisionIntelligencePolicy.Candidate(
                    decision = decision,
                    label = ProjectDecisionIntelligencePolicy.labelForReason(
                        reason,
                    ),
                    reason = reason,
                    rankingScore = if (semanticRankingAvailable) requireNotNull(scores)[index] else Float.NaN,
                )
        }

        val ordered = if (semanticRankingAvailable) {
            rankingResults.sortedWith(compareByDescending<ProjectDecisionIntelligencePolicy.Candidate> { it.rankingScore }
                .thenByDescending { it.decision.createdAt }.thenBy { it.decision.id })
        } else {
            rankingResults.sortedWith(Comparator { left, right -> deterministicOrder.compare(left.decision, right.decision) })
        }
        return@withContext ordered.take(ProjectDecisionIntelligencePolicy.MAX_DECISION_SUGGESTIONS)
    }

    private suspend fun embedInBoundedBatches(texts: List<String>): List<FloatArray>? {
        val vectors = ArrayList<FloatArray>(texts.size)
        for (batch in texts.chunked(embeddingBatchLimit)) {
            require(batch.size <= FusionEmbeddingLimits.MAX_TEXTS_PER_CALL)
            val result = symmetric.embedSources(batch)
            val success = result as? EmbeddingResult.Success ?: return null
            if (success.vectors.size != batch.size || success.dimension <= 0 ||
                success.vectors.any { it.size != success.dimension || it.isEmpty() }
            ) return null
            vectors += success.vectors
        }
        return vectors.takeIf { it.size == texts.size }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return Float.NaN
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denom > 0f) dot / denom else Float.NaN
    }

    private fun canonical(value: String): String = value.trim().lowercase()

    private fun tokens(value: String): Set<String> = canonical(value)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 }.toSet()

    private fun overlap(a: String, b: String): Float {
        val setA = tokens(a)
        val setB = tokens(b)
        if (setA.isEmpty() || setB.isEmpty()) return 0f
        val shared = setA.intersect(setB).size
        return shared.toFloat() / minOf(setA.size, setB.size)
    }

    private fun sameDurableSource(
        candidate: ProjectDecisionEntity,
        frozenSourceConversationId: Long?,
        frozenSourceMessageId: Long?,
    ): Boolean {
        // Conservative same-source admission: both decision and frozen capture source
        // point to the exact same assistant message in the same conversation.
        if (frozenSourceConversationId == null || frozenSourceMessageId == null) return false
        return candidate.sourceConversationId == frozenSourceConversationId &&
            candidate.sourceMessageId == frozenSourceMessageId
    }
}
