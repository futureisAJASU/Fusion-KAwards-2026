package com.projectnuke.fusion.inbox

import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.knowledge.KnowledgeOrganizationPolicy
import com.projectnuke.fusion.knowledge.SavedKnowledgeAccessGate
import com.projectnuke.fusion.retrieval.EmbeddingResult
import com.projectnuke.fusion.retrieval.LocalRagLexical
import com.projectnuke.fusion.retrieval.SemanticSimilarity
import com.projectnuke.fusion.retrieval.SymmetricEmbeddingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Smart Inbox Triage v1 — proposal coordinator.
 *
 * Mutation boundary (hard invariant): this seam exposes NO write operations.
 * [InboxSmartTriageDataSource] offers read-only queries; proposal generation
 * performs zero DB mutations, zero Inbox deletes, zero Task / Saved Knowledge /
 * Review Card / tag writes. Every durable mutation still requires an explicit
 * user tap routed into the existing repositories.
 *
 * Local-only: ranking uses the shared on-device [SymmetricEmbeddingSource]
 * (production: `KoEnE5RetrievalOwner.symmetricProxy`). No web search, no
 * outside-model calls, no egress tickets, no network.
 *
 * Rules establish eligibility ([InboxSmartTriagePolicy]), semantic similarity
 * only ranks candidates, the user confirms the mutation.
 */
sealed interface InboxSmartTriageResult {
    data class Ready(val proposal: InboxSmartTriageProposal) : InboxSmartTriageResult
    /** Item deleted/edited since the request, or missing. Regenerate. */
    data object Stale : InboxSmartTriageResult
    /** Same item/revision already being analyzed; duplicate tap suppressed. */
    data object DuplicateSuppressed : InboxSmartTriageResult
}

data class SmartProjectRow(
    val id: Long,
    val name: String,
    val instructions: String,
)

data class SmartTagRow(
    val key: String,
    val displayName: String,
)

/**
 * Read-only triage view over durable stores. Deliberately write-free: there
 * is no method here capable of mutating user data, so proposal generation
 * cannot mutate by construction.
 */
interface InboxSmartTriageDataSource {
    suspend fun getInboxItem(id: String): InboxItemEntity?
    suspend fun recentProjects(limit: Int): List<SmartProjectRow>
    suspend fun recentKnowledgeTitles(projectId: Long, limit: Int): List<String>
    suspend fun recentTaskTitles(projectId: Long, limit: Int): List<String>
    suspend fun existingTags(limit: Int): List<SmartTagRow>
    suspend fun inboxTagKeys(itemId: String): Set<String>
}

/** Production read-only source: bounded DAO reads only, never writes. */
class RoomInboxSmartTriageDataSource(
    private val database: AppDatabase,
    private val archiveAllowed: () -> Boolean = { false },
) : InboxSmartTriageDataSource {
    override suspend fun getInboxItem(id: String): InboxItemEntity? = withContext(Dispatchers.IO) {
        database.inboxItemDao().get(id)
    }

    override suspend fun recentProjects(limit: Int): List<SmartProjectRow> = withContext(Dispatchers.IO) {
        database.projectDao().getRecentProjectsBounded(limit.coerceIn(1, InboxSmartTriageLimits.MAX_PROJECTS_CONSIDERED))
            .map { SmartProjectRow(it.id, it.name, it.instructions) }
    }

    override suspend fun recentKnowledgeTitles(projectId: Long, limit: Int): List<String> =
        withContext(Dispatchers.IO) {
            SavedKnowledgeAccessGate.selectAccessibleBounded(
                database = database,
                projectId = projectId,
                pageSize = 16,
                requiredCount = limit.coerceIn(1, 8),
                archiveAllowed = archiveAllowed(),
                maxRowsScanned = 64,
            )
                .map { it.title }
        }

    override suspend fun recentTaskTitles(projectId: Long, limit: Int): List<String> =
        withContext(Dispatchers.IO) {
            database.projectTaskDao().getRecentForProjectBounded(projectId, limit.coerceIn(1, 8))
                .map { it.title }
        }

    override suspend fun existingTags(limit: Int): List<SmartTagRow> = withContext(Dispatchers.IO) {
        database.knowledgeOrganizationDao()
            .listTags(limit.coerceIn(1, InboxSmartTriageLimits.MAX_EXISTING_TAGS_CONSIDERED))
            .map { SmartTagRow(it.tagKey, it.displayName) }
    }

    override suspend fun inboxTagKeys(itemId: String): Set<String> = withContext(Dispatchers.IO) {
        database.knowledgeOrganizationDao().tagsForInboxItem(itemId).map { it.tagKey }.toSet()
    }
}

/**
 * Per-item single-flight ownership keyed by exact item identity + revision
 * (`itemId + updatedAt`). The same item/revision analyzed twice suppresses
 * the duplicate; different items (or a newer revision) proceed independently.
 * No global lock is held around the shared embedding engine — the engine
 * stays centrally serialized by its own owner.
 */
class InboxSmartTriageFlightOwner {
    private val lock = Any()
    private val active = HashSet<String>()

    fun acquire(key: String): Boolean = synchronized(lock) { active.add(key) }

    fun release(key: String) {
        synchronized(lock) { active.remove(key) }
    }
}

class InboxSmartTriageCoordinator(
    private val dataSource: InboxSmartTriageDataSource,
    private val flightOwner: InboxSmartTriageFlightOwner = InboxSmartTriageFlightOwner(),
) {
    /**
     * Builds one bounded ephemeral proposal. Zero mutations. Cancellation
     * propagates; semantic failure degrades to deterministic suggestions.
     */
    suspend fun propose(
        itemId: String,
        embeddings: SymmetricEmbeddingSource,
    ): InboxSmartTriageResult = withContext(Dispatchers.Default) {
        val snapshot = dataSource.getInboxItem(itemId) ?: return@withContext InboxSmartTriageResult.Stale
        proposeFrozenSnapshot(snapshot, embeddings)
    }

    /**
     * Builds a proposal only for the exact caller-frozen row. Batch review
     * uses this seam so it cannot pair an old selection revision with a newer
     * row fetched by ID. The row is checked both before and after generation.
     */
    suspend fun proposeSnapshot(
        snapshot: InboxItemEntity,
        embeddings: SymmetricEmbeddingSource,
    ): InboxSmartTriageResult = withContext(Dispatchers.Default) {
        proposeFrozenSnapshot(snapshot, embeddings)
    }

    private suspend fun proposeFrozenSnapshot(
        snapshot: InboxItemEntity,
        embeddings: SymmetricEmbeddingSource,
    ): InboxSmartTriageResult {
        val before = dataSource.getInboxItem(snapshot.id)
        if (before == null || !InboxSmartTriagePolicy.matchesSnapshot(before, snapshot)) {
            return InboxSmartTriageResult.Stale
        }
        val key = InboxSmartTriagePolicy.keyFor(snapshot)
        if (!flightOwner.acquire(key)) return InboxSmartTriageResult.DuplicateSuppressed
        return try {
            currentCoroutineContext().ensureActive()
            val classification = InboxSmartTriagePolicy.classify(snapshot)
            val itemText = InboxSmartTriagePolicy.boundItemText(snapshot)

            val projects = if (classification.projectSemanticEligible) {
                dataSource.recentProjects(InboxSmartTriageLimits.MAX_PROJECTS_CONSIDERED)
            } else emptyList()
            currentCoroutineContext().ensureActive()

            val profiles = projects.map { project ->
                // Recoverable read failures fall back to thinner profiles;
                // cancellation always propagates with identity.
                val knowledge = try {
                    dataSource.recentKnowledgeTitles(project.id, InboxSmartTriageLimits.MAX_TITLES_PER_PROJECT)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                currentCoroutineContext().ensureActive()
                val tasks = try {
                    dataSource.recentTaskTitles(project.id, InboxSmartTriageLimits.MAX_TITLES_PER_PROJECT)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                currentCoroutineContext().ensureActive()
                project to InboxSmartTriagePolicy.buildProjectProfile(
                    project.name, knowledge, tasks, project.instructions,
                )
            }.filter { it.second.isNotBlank() }
                .take(InboxSmartTriageLimits.MAX_PROJECTS_CONSIDERED)

            val tags = dataSource.existingTags(InboxSmartTriageLimits.MAX_EXISTING_TAGS_CONSIDERED)
            val linkedKeys = try {
                dataSource.inboxTagKeys(snapshot.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptySet()
            }
            currentCoroutineContext().ensureActive()

            val ranking = rankLocally(itemText, profiles, tags, embeddings)
            currentCoroutineContext().ensureActive()

            // Fail closed on drift: re-read before publishing. A deleted or
            // edited item never receives a proposal for old content.
            val live = dataSource.getInboxItem(snapshot.id)
            if (live == null || !InboxSmartTriagePolicy.matchesSnapshot(live, snapshot)) {
                return InboxSmartTriageResult.Stale
            }

            val projectCandidates = ranking.rankedProjects
                .take(InboxSmartTriageLimits.MAX_PROJECT_CANDIDATES)
                .filter { row -> projects.any { it.id == row.id } }
                .map { row ->
                    InboxSmartProjectCandidate(
                        projectId = row.id,
                        projectName = row.name,
                        reason = "이 Project의 기존 자료와 내용이 비슷해요.",
                    )
                }
                .distinctBy { it.projectId }

            var primary = classification.primaryAction
            var reason = classification.reason
            val type = runCatching { InboxCaptureType.valueOf(snapshot.captureType) }.getOrNull()
            val hasManagedFile = !snapshot.managedFileId.isNullOrBlank()

            // File-backed DOCUMENT: PROJECT only with a real candidate.
            if (type == InboxCaptureType.DOCUMENT && hasManagedFile && projectCandidates.isEmpty()) {
                primary = InboxSmartActionKind.KEEP_IN_INBOX
                reason = "정리 근거가 약해 Inbox에 두는 게 안전해 보여요."
            }
            // URL / WEB_CLIP: Project outranks saved knowledge only on strong
            // local semantic relevance; raw scores never leave this function.
            if ((type == InboxCaptureType.URL || type == InboxCaptureType.WEB_CLIP) &&
                primary == InboxSmartActionKind.SAVED_KNOWLEDGE &&
                ranking.strongTopRelevance && projectCandidates.isNotEmpty()
            ) {
                primary = InboxSmartActionKind.PROJECT
                reason = "이 Project의 기존 자료와 내용이 비슷해요."
            }

            val tagSuggestions = ranking.rankedTags
                .filter { it.key !in linkedKeys }
                .mapNotNull { KnowledgeOrganizationPolicy.normalizeTag(it.displayName) }
                .distinctBy { it.key }
                .take(InboxSmartTriageLimits.MAX_TAG_SUGGESTIONS)
                .map { InboxSmartTagSuggestion(it.displayName) }

            InboxSmartTriageResult.Ready(
                InboxSmartTriageProposal(
                    itemId = snapshot.id,
                    itemRevision = snapshot.updatedAt,
                    primaryAction = primary,
                    reason = reason,
                    projectCandidates = projectCandidates,
                    tagSuggestions = tagSuggestions,
                    semanticAvailable = ranking.semanticAvailable,
                ),
            )
        } finally {
            flightOwner.release(key)
        }
    }

    private data class LocalRanking(
        val rankedProjects: List<SmartProjectRow>,
        val rankedTags: List<SmartTagRow>,
        val strongTopRelevance: Boolean,
        val semanticAvailable: Boolean,
    )

    /**
     * Local cosine ranking over symmetric E5 vectors, gated by the candidate
     * admission policy. Cosine orders candidates; deterministic corroboration
     * decides whether they may be shown. Scores are consumed here and never
     * persisted or exposed.
     */
    private suspend fun rankLocally(
        itemText: String,
        profiles: List<Pair<SmartProjectRow, String>>,
        tags: List<SmartTagRow>,
        embeddings: SymmetricEmbeddingSource,
    ): LocalRanking {
        if (itemText.isBlank() || (profiles.isEmpty() && tags.isEmpty())) {
            return LocalRanking(emptyList(), lexicalTagFallback(itemText, tags), false, false)
        }
        val texts = buildList {
            add(itemText.take(2_000))
            profiles.forEach { add(it.second.take(2_000)) }
            tags.forEach { add(it.displayName.take(200)) }
        }
        val vectors = try {
            embedBatched(embeddings, texts)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (vectors == null || vectors.size != texts.size) {
            return LocalRanking(emptyList(), lexicalTagFallback(itemText, tags), false, false)
        }
        val itemVector = vectors[0]
        // Deterministic lexical grounding: shared tokens between the item
        // and the Project profile (name + recent titles), reusing the
        // existing local lexical seam. No morphology, no network.
        val projectCorroborated = profiles.map { (_, profile) ->
            LocalRagLexical.score(itemText, profile) > 0
        }
        val projectScores = profiles.mapIndexed { index, _ ->
            cosineOrZero(itemVector, vectors[1 + index])
        }
        val projectSignals = InboxSemanticAdmissionPolicy.projectSignals(projectScores, projectCorroborated)
        val admittedProjects = InboxSemanticAdmissionPolicy
            .admittedProjectPositions(projectSignals)
            .sorted()
            .take(InboxSmartTriageLimits.MAX_PROJECT_CANDIDATES)
            .map { position -> profiles[projectSignals[position].sourceIndex].first }
        val tagCorroborated = tags.map { tag -> isTagCorroborated(itemText, tag) }
        val tagScores = tags.mapIndexed { index, _ ->
            cosineOrZero(itemVector, vectors[1 + profiles.size + index])
        }
        val tagSignals = InboxSemanticAdmissionPolicy.tagSignals(tagScores, tagCorroborated)
        val admittedTags = InboxSemanticAdmissionPolicy
            .admittedTagPositions(tagSignals)
            .sorted()
            .map { position -> tags[tagSignals[position].sourceIndex] }
        return LocalRanking(
            rankedProjects = admittedProjects,
            rankedTags = admittedTags,
            strongTopRelevance = InboxSemanticAdmissionPolicy.strongTopProject(projectSignals),
            semanticAvailable = true,
        )
    }

    private fun cosineOrZero(a: FloatArray, b: FloatArray): Float = try {
        SemanticSimilarity.cosine(a, b)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        0f
    }

    private fun isTagCorroborated(itemText: String, tag: SmartTagRow): Boolean {
        val token = tag.displayName.lowercase().trim()
        return token.length >= 2 && itemText.lowercase().contains(token)
    }

    /** Batched within the engine call bound; no unbounded single call. */
    private suspend fun embedBatched(
        embeddings: SymmetricEmbeddingSource,
        texts: List<String>,
    ): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        val out = ArrayList<FloatArray>(texts.size)
        var cursor = 0
        while (cursor < texts.size) {
            currentCoroutineContext().ensureActive()
            val end = minOf(cursor + 8, texts.size)
            val batch = texts.subList(cursor, end).map { it.ifBlank { " " } }
            when (val result = embeddings.embedSources(batch)) {
                is EmbeddingResult.Success -> {
                    if (result.vectors.size != batch.size) return null
                    out += result.vectors
                }
                is EmbeddingResult.Failure -> return null
            }
            cursor = end
        }
        return out
    }

    /** Deterministic lexical fallback: existing vocabulary only, never generated tags. */
    private fun lexicalTagFallback(itemText: String, tags: List<SmartTagRow>): List<SmartTagRow> {
        if (itemText.isBlank() || tags.isEmpty()) return emptyList()
        val lowered = itemText.lowercase()
        return tags.filter { tag ->
            val token = tag.displayName.lowercase().trim()
            token.length >= 2 && lowered.contains(token)
        }.distinctBy { it.key }
            .take(InboxSmartTriageLimits.MAX_TAG_SUGGESTIONS)
    }

    companion object {
        /**
         * Pre-mutation freshness gate: re-reads the item and accepts the
         * proposal only when the live row still equals the snapshotted
         * revision. Stale proposals fail closed and must never invoke a
         * mutation.
         */
        suspend fun freshItemFor(
            dataSource: InboxSmartTriageDataSource,
            proposal: InboxSmartTriageProposal,
            snapshot: InboxItemEntity,
        ): InboxItemEntity? {
            if (proposal.itemId != snapshot.id || proposal.itemRevision != snapshot.updatedAt) return null
            val live = try {
                dataSource.getInboxItem(snapshot.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return null
            }
            if (live == null || live.updatedAt != proposal.itemRevision) return null
            if (!InboxSmartTriagePolicy.matchesSnapshot(live, snapshot)) return null
            return live
        }
    }
}
