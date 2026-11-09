package com.projectnuke.fusion.knowledge

import android.content.Context
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.retrieval.EmbeddingResult
import com.projectnuke.fusion.retrieval.KoEnE5RetrievalOwner
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class KnowledgeHygieneCandidate(val id: String, val title: String, val label: String)

data class KnowledgeHygieneReview(
    val sourceId: String,
    val sourceTitle: String,
    val provenanceObservations: List<String>,
    val candidates: List<KnowledgeHygieneCandidate>,
)

/** Recomputable local review; no writes, network calls, or retained embedding scores. */
class KnowledgeHygieneRepository(
    context: Context,
    private val database: AppDatabase = AppDatabase.getInstance(context.applicationContext),
    private val includeArchived: () -> Boolean,
) {
    private val appContext = context.applicationContext
    private val symmetric = KoEnE5RetrievalOwner.symmetricProxy(
        appContext.assets,
        File(appContext.filesDir, "koen-e5"),
    )

    suspend fun load(sourceId: String): KnowledgeHygieneReview? = withContext(Dispatchers.IO) {
        val archiveAllowed = includeArchived()
        val source = database.savedKnowledgeDao().getById(sourceId) ?: return@withContext null
        if (!SavedKnowledgeAccessGate.isRowAccessible(database, source, archiveAllowed)) return@withContext null
        val rows = database.savedKnowledgeDao().latest(KnowledgeHygienePolicy.MAX_HYGIENE_POOL)
        val permitted = ArrayList<SavedKnowledgeEntity>(rows.size)
        for (row in rows) {
            coroutineContext.ensureActive()
            if (row.id != source.id) {
                val allowed = try {
                    SavedKnowledgeAccessGate.isRowAccessible(database, row, archiveAllowed) &&
                        hasRenderableAuthority(row, archiveAllowed)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (allowed) permitted += row
            }
        }
        val sourceIdentity = durableIdentity(source)
        val candidateInputs = ArrayList<KnowledgeHygienePolicy.CandidateInput>(permitted.size)
        for (row in permitted) {
            coroutineContext.ensureActive()
            candidateInputs += KnowledgeHygienePolicy.CandidateInput(
                id = row.id,
                title = row.title.take(256),
                text = row.savedText.take(KnowledgeHygienePolicy.MAX_EMBED_TEXT_CHARS),
                sourceUrl = row.sourceUrl,
                durableSourceIdentity = durableIdentity(row),
                semanticRank = Int.MAX_VALUE,
            )
        }
        fun admit(candidates: List<KnowledgeHygienePolicy.CandidateInput>) = KnowledgeHygienePolicy.admit(
            sourceId = source.id,
            sourceTitle = source.title,
            sourceText = source.savedText.take(KnowledgeHygienePolicy.MAX_EMBED_TEXT_CHARS),
            sourceUrl = source.sourceUrl,
            sourceIdentity = sourceIdentity,
            candidates = candidates,
        )
        // Deterministic corroboration chooses the bounded set first; shared E5 only orders those rows.
        val lexicalCandidates = admit(candidateInputs)
        val selectedIds = lexicalCandidates.map { it.id }.toSet()
        val ranks = semanticRanks(source, permitted.filter { it.id in selectedIds })
        val admitted = admit(candidateInputs.map { it.copy(semanticRank = ranks[it.id] ?: Int.MAX_VALUE) })
        KnowledgeHygieneReview(
            sourceId = source.id,
            sourceTitle = source.title,
            provenanceObservations = provenance(source, archiveAllowed),
            candidates = admitted.map { KnowledgeHygieneCandidate(it.id, it.title, it.classification) },
        )
    }

    /** Re-read both exact identities and access authority immediately before navigation. */
    suspend fun revalidatePair(sourceId: String, candidateId: String): SavedKnowledgeEntity? = withContext(Dispatchers.IO) {
        val archiveAllowed = includeArchived()
        val source = database.savedKnowledgeDao().getById(sourceId) ?: return@withContext null
        val candidate = database.savedKnowledgeDao().getById(candidateId) ?: return@withContext null
        if (source.id == candidate.id ||
            !SavedKnowledgeAccessGate.isRowAccessible(database, source, archiveAllowed) ||
            !SavedKnowledgeAccessGate.isRowAccessible(database, candidate, archiveAllowed) ||
            !hasRenderableAuthority(candidate, archiveAllowed)
        ) null else candidate
    }

    private suspend fun semanticRanks(source: SavedKnowledgeEntity, rows: List<SavedKnowledgeEntity>): Map<String, Int> {
        if (rows.isEmpty()) return emptyMap()
        val inputs = listOf(source) + rows
        val vectors = ArrayList<FloatArray>(inputs.size)
        // The shared owner enforces the production 32-text call bound. Calls are sequential and cancellable.
        for (batch in inputs.chunked(32)) {
            coroutineContext.ensureActive()
            when (val result = symmetric.embedSources(batch.map(::embeddingText))) {
                is EmbeddingResult.Success -> vectors.addAll(result.vectors)
                is EmbeddingResult.Failure -> return emptyMap()
            }
        }
        val sourceVector = vectors.firstOrNull() ?: return emptyMap()
        return rows.mapIndexedNotNull { index, row ->
            val score = cosine(sourceVector, vectors.getOrNull(index + 1) ?: return@mapIndexedNotNull null)
            if (score.isFinite()) row.id to score else null
        }.sortedWith(compareByDescending<Pair<String, Float>> { it.second }.thenBy { it.first })
            .mapIndexed { index, pair -> pair.first to index }.toMap()
    }

    private fun embeddingText(row: SavedKnowledgeEntity): String =
        (row.title + "\n" + row.savedText).take(KnowledgeHygienePolicy.MAX_EMBED_TEXT_CHARS)

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        if (left.isEmpty() || left.size != right.size) return Float.NaN
        var result = 0f
        for (index in left.indices) result += left[index] * right[index]
        return result
    }

    private suspend fun durableIdentity(row: SavedKnowledgeEntity): String? = when (row.provenanceType) {
        "ASSISTANT_ANSWER" -> if (row.sourceConversationId != null && row.sourceMessageId != null)
            "answer:${row.sourceConversationId}:${row.sourceMessageId}" else null
        "WEB_CLIP" -> row.sourceUrl?.let { "web:${it.trim().lowercase()}" }
        "DOCUMENT_HIGHLIGHT" -> database.documentHighlightSourceDao().getByKnowledgeId(row.id)?.let {
            "document:${it.projectId}:${it.projectFileId}:${it.sourceChunkId.orEmpty()}:${it.sourceContentHashHex.orEmpty()}"
        }
        else -> null
    }

    private suspend fun hasRenderableAuthority(row: SavedKnowledgeEntity, archiveAllowed: Boolean): Boolean = when (row.provenanceType) {
        "ASSISTANT_ANSWER" -> {
            val conversation = row.sourceConversationId?.let { database.chatDao().getConversationById(it) }
            val answer = if (row.sourceConversationId != null && row.sourceMessageId != null) {
                database.savedKnowledgeDao().getSourceAnswer(row.sourceMessageId, row.sourceConversationId)
            } else null
            conversation != null && (!conversation.isArchived || archiveAllowed) && answer?.role == "assistant"
        }
        "WEB_CLIP" -> !row.sourceUrl.isNullOrBlank()
        "DOCUMENT_HIGHLIGHT" -> {
            val source = database.documentHighlightSourceDao().getByKnowledgeId(row.id) ?: return false
            val project = database.projectDao().getProjectFileById(source.projectFileId)
            project != null && project.projectId == source.projectId &&
                database.projectDao().getProjectById(source.projectId) != null
        }
        "USER_NOTE" -> true // Standalone user-authored notes are their own durable source.
        else -> true // Imported snapshots and captures are independent, durable copies.
    }

    private suspend fun provenance(row: SavedKnowledgeEntity, archiveAllowed: Boolean): List<String> = when (row.provenanceType) {
        "ASSISTANT_ANSWER" -> {
            val source = if (row.sourceConversationId != null && row.sourceMessageId != null) {
                database.savedKnowledgeDao().getSourceAnswer(row.sourceMessageId, row.sourceConversationId)
            } else null
            val conversation = row.sourceConversationId?.let { database.chatDao().getConversationById(it) }
            if (source?.role == "assistant" && conversation != null && (!conversation.isArchived || archiveAllowed))
                listOf("원본 답변에 연결됨") else listOf("원본 대화를 현재 열 수 없음")
        }
        "WEB_CLIP" -> if (!row.sourceUrl.isNullOrBlank()) listOf("웹 출처 정보 있음") else listOf("웹 출처 정보 없음")
        "USER_NOTE" -> listOf("사용자 작성 노트")
        "DOCUMENT_HIGHLIGHT" -> if (database.documentHighlightSourceDao().getByKnowledgeId(row.id) != null)
            listOf("문서 발췌 출처 있음") else listOf("문서 발췌 출처를 찾을 수 없음")
        else -> listOf("저장 시점 출처 사본")
    }
}
