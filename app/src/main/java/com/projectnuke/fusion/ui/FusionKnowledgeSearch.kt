package com.projectnuke.fusion.ui

import com.projectnuke.fusion.retrieval.SemanticCandidateMatcher
import com.projectnuke.fusion.retrieval.SemanticSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import com.projectnuke.fusion.chat.SelectionContextSnapshot
import com.projectnuke.fusion.chat.SelectionSourceIdentity
import com.projectnuke.fusion.chat.SelectionSourceKind
import com.projectnuke.fusion.chat.SelectionSourceSnapshot

internal enum class KnowledgeKind(val label: String) {
    CHECKPOINT("체크포인트"),
    CONVERSATION("대화"), PROJECT("프로젝트"), DOCUMENT("문서"), MEMORY("메모리"), SAVED_KNOWLEDGE("저장 지식"), INBOX("Inbox")
}

internal sealed interface KnowledgeTarget {
    data class Checkpoint(val id: Long, val conversationId: Long, val messageId: Long) : KnowledgeTarget
    data class Conversation(val id: Long, val attachmentPath: String? = null) : KnowledgeTarget
    data class Project(val id: Long, val fileId: Long? = null) : KnowledgeTarget
    data class Decision(val projectId: Long, val decisionId: String) : KnowledgeTarget
    data class Memory(val id: String) : KnowledgeTarget
    data class SavedKnowledge(val id: String, val expectedProjectId: Long? = null) : KnowledgeTarget
    data class InboxItem(val id: String) : KnowledgeTarget
}

internal data class KnowledgeCandidate(
    val key: String,
    val kind: KnowledgeKind,
    val title: String,
    val preview: String,
    val metadata: String,
    val target: KnowledgeTarget,
    val selectionExcerpt: String = preview,
)

internal fun KnowledgeCandidate.toSelectionSourceSnapshot(): SelectionSourceSnapshot? {
    val identity = when (val source = target) {
        is KnowledgeTarget.Conversation -> if (source.attachmentPath == null) {
            SelectionSourceIdentity(SelectionSourceKind.CONVERSATION, conversationId = source.id)
        } else {
            SelectionSourceIdentity(SelectionSourceKind.ATTACHED_DOCUMENT, conversationId = source.id,
                managedPath = source.attachmentPath)
        }
        is KnowledgeTarget.Project -> if (source.fileId == null) {
            SelectionSourceIdentity(SelectionSourceKind.PROJECT, projectId = source.id)
        } else {
            SelectionSourceIdentity(SelectionSourceKind.PROJECT_DOCUMENT, projectId = source.id, fileId = source.fileId)
        }
        is KnowledgeTarget.Decision -> return null
        is KnowledgeTarget.Memory -> SelectionSourceIdentity(SelectionSourceKind.MEMORY, memoryId = source.id)
        is KnowledgeTarget.SavedKnowledge -> SelectionSourceIdentity(SelectionSourceKind.SAVED_KNOWLEDGE, savedKnowledgeId = source.id)
        is KnowledgeTarget.InboxItem -> return null
        is KnowledgeTarget.Checkpoint -> return null
    }
    val excerpt = when (val source = target) {
        is KnowledgeTarget.Project -> if (source.fileId == null && selectionExcerpt.isBlank()) "프로젝트 이름: $title" else selectionExcerpt
        else -> selectionExcerpt
    }
    return SelectionSourceSnapshot(identity, title, excerpt)
}

internal fun SelectionSourceIdentity.toKnowledgeTarget(): KnowledgeTarget? = when (kind) {
    SelectionSourceKind.CONVERSATION -> conversationId?.let { KnowledgeTarget.Conversation(it) }
    SelectionSourceKind.PROJECT -> projectId?.let { KnowledgeTarget.Project(it) }
    SelectionSourceKind.PROJECT_DOCUMENT -> if (projectId != null && fileId != null) KnowledgeTarget.Project(projectId, fileId) else null
    SelectionSourceKind.ATTACHED_DOCUMENT -> if (conversationId != null && managedPath != null) KnowledgeTarget.Conversation(conversationId, managedPath) else null
    SelectionSourceKind.MEMORY -> memoryId?.let { KnowledgeTarget.Memory(it) }
    SelectionSourceKind.SAVED_KNOWLEDGE -> savedKnowledgeId?.let { KnowledgeTarget.SavedKnowledge(it) }
}

/**
 * Issue 5: selectable-target to durable-identity mapping for the shared
 * access authority. Mirrors [toSelectionSourceSnapshot] without excerpt or
 * title; non-selectable targets (Decision, Inbox, Checkpoint) map to null
 * and keep their own checks.
 */
internal fun KnowledgeTarget.toSourceIdentity(): SelectionSourceIdentity? = when (this) {
    is KnowledgeTarget.Conversation -> if (attachmentPath == null) {
        SelectionSourceIdentity(SelectionSourceKind.CONVERSATION, conversationId = id)
    } else {
        SelectionSourceIdentity(
            SelectionSourceKind.ATTACHED_DOCUMENT,
            conversationId = id,
            managedPath = attachmentPath,
        )
    }
    is KnowledgeTarget.Project -> if (fileId == null) {
        SelectionSourceIdentity(SelectionSourceKind.PROJECT, projectId = id)
    } else {
        SelectionSourceIdentity(SelectionSourceKind.PROJECT_DOCUMENT, projectId = id, fileId = fileId)
    }
    is KnowledgeTarget.Memory -> SelectionSourceIdentity(SelectionSourceKind.MEMORY, memoryId = id)
    is KnowledgeTarget.SavedKnowledge -> SelectionSourceIdentity(SelectionSourceKind.SAVED_KNOWLEDGE, savedKnowledgeId = id)
    is KnowledgeTarget.Decision,
    is KnowledgeTarget.InboxItem,
    is KnowledgeTarget.Checkpoint,
    -> null
}

internal data class KnowledgeSearchFilters(
    val kind: KnowledgeKind? = null,
    val projectId: Long? = null,
    val tagKey: String? = null,
    val collectionId: String? = null,
) {
    val activeCount: Int get() = listOf(kind, projectId, tagKey, collectionId).count { it != null }
}

internal suspend fun knowledgeTargetBelongsToProject(
    target: KnowledgeTarget,
    projectId: Long,
    conversationProjectId: suspend (Long) -> Long?,
    savedKnowledgeProjectId: suspend (String) -> Long?,
): Boolean = when (target) {
    is KnowledgeTarget.Project -> target.id == projectId
    is KnowledgeTarget.SavedKnowledge -> savedKnowledgeProjectId(target.id) == projectId
    is KnowledgeTarget.Conversation -> conversationProjectId(target.id) == projectId
    is KnowledgeTarget.Checkpoint -> conversationProjectId(target.conversationId) == projectId
    is KnowledgeTarget.Decision -> target.projectId == projectId
    is KnowledgeTarget.InboxItem,
    is KnowledgeTarget.Memory,
    -> false
}

internal data class KnowledgeSearchFilterOptions(
    val tags: List<com.projectnuke.fusion.data.KnowledgeTagEntity> = emptyList(),
    val collections: List<com.projectnuke.fusion.data.KnowledgeCollectionEntity> = emptyList(),
    val projects: List<com.projectnuke.fusion.data.ProjectEntity> = emptyList(),
)

internal data class KnowledgeSearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<KnowledgeCandidate> = emptyList(),
    val savedKnowledgeScanIncomplete: Boolean = false,
    val savedKnowledgeRawScanBudgetExhausted: Boolean = false,
    val savedKnowledgeResultLimitReached: Boolean = false,
    val savedKnowledgeAdditionalResults: Boolean = false,
    val error: Boolean = false,
)

internal data class KnowledgeSearchOutcome(
    val candidates: List<KnowledgeCandidate>,
    val savedKnowledgeScanIncomplete: Boolean = false,
    val savedKnowledgeRawScanBudgetExhausted: Boolean = false,
    val savedKnowledgeResultLimitReached: Boolean = false,
    val savedKnowledgeAdditionalResults: Boolean = false,
)

internal fun boundedKnowledgeQuery(raw: String): String = raw.trim().take(100)

internal fun knowledgePreview(text: String, query: String, limit: Int = 1000): String {
    val match = text.indexOf(query, ignoreCase = true)
    val start = (match - 40).coerceAtLeast(0)
    return text.drop(start).take(limit)
}

internal suspend fun admitKnowledgeNavigation(lookup: suspend () -> Boolean): Boolean = try {
    lookup()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

/** Exact titles precede body matches, then semantic candidates. Stable identity breaks ties. */
internal suspend fun rankKnowledge(
    query: String,
    candidates: List<KnowledgeCandidate>,
    semantic: SemanticCandidateMatcher,
): List<KnowledgeCandidate> {
    if (query.length < 2) return emptyList()
    val grouped = KnowledgeKind.entries.associateWith { kind ->
        candidates.asSequence().filter { it.kind == kind }.distinctBy { it.key }.toList()
    }
    // Bound semantic/ranking work without letting earlier query domains
    // consume the entire prefix before later domains (including Saved
    // Knowledge) are considered.
    val bounded = (0 until 256).flatMap { index ->
        KnowledgeKind.entries.mapNotNull { kind -> grouped[kind]?.getOrNull(index) }
    }.take(256)
    fun lexical(c: KnowledgeCandidate): Int = when {
        c.title.equals(query, true) -> 3
        c.title.contains(query, true) -> 2
        c.preview.contains(query, true) -> 1
        else -> 0
    }
    // Round-robin by domain prevents one large category from consuming E5's 24 segments.
    val boundedByKind = KnowledgeKind.entries.map { kind -> bounded.filter { it.kind == kind } }
    val semanticCandidates = (0 until 6).flatMap { index -> boundedByKind.mapNotNull { it.getOrNull(index) } }
    val ranked = semantic.rank(query, semanticCandidates.map {
        SemanticSegment(it.key, (it.title + "\n" + it.preview).take(1000))
    }, 24)
    val matches = ranked.matches.filter { ranked.unavailableReason == null && it.score.isFinite() && it.score >= 0.72f }
        .associate { it.segmentId to it.score }
    coroutineContext.ensureActive()
    return bounded.filter { lexical(it) > 0 || it.key in matches }
        .sortedWith(compareBy<KnowledgeCandidate> { it.kind.ordinal }
            .thenByDescending { lexical(it) }.thenByDescending { matches[it.key] ?: -1f }.thenBy { it.key })
        .groupBy { it.kind }.values.flatMap { it.take(8) }
        .map { it.copy(title = it.title.take(180), preview = it.preview.replace(Regex("\\s+"), " ").take(160),
            selectionExcerpt = it.selectionExcerpt.replace(Regex("\\s+"), " ").take(SelectionContextSnapshot.MAX_EXCERPT_CHARS)) }
}

/** One screen owns its job. A revision fences even a non-cooperative stale backend. */
internal class FusionSearchController(
    private val scope: CoroutineScope,
    private val search: suspend (String) -> List<KnowledgeCandidate>,
    private val debounceMillis: Long = 300,
    private val searchWithStatus: suspend (String) -> KnowledgeSearchOutcome = { query ->
        KnowledgeSearchOutcome(search(query))
    },
) {
    private val mutableState = MutableStateFlow(KnowledgeSearchState())
    val state = mutableState.asStateFlow()
    private var revision = 0L
    private var job: Job? = null

    fun query(raw: String) {
        val query = boundedKnowledgeQuery(raw)
        val ticket = ++revision
        job?.cancel()
        mutableState.value = KnowledgeSearchState(query, loading = query.length >= 2)
        if (query.length < 2) return
        job = scope.launch {
            try {
                delay(debounceMillis)
                val outcome = searchWithStatus(query)
                coroutineContext.ensureActive()
                if (ticket == revision) mutableState.value = KnowledgeSearchState(
                    query = query,
                    results = outcome.candidates,
                    savedKnowledgeScanIncomplete = outcome.savedKnowledgeScanIncomplete,
                    savedKnowledgeRawScanBudgetExhausted = outcome.savedKnowledgeRawScanBudgetExhausted,
                    savedKnowledgeResultLimitReached = outcome.savedKnowledgeResultLimitReached,
                    savedKnowledgeAdditionalResults = outcome.savedKnowledgeAdditionalResults,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (ticket == revision) mutableState.value = KnowledgeSearchState(query, error = true)
            }
        }
    }

    fun close() {
        revision++
        job?.cancel()
        mutableState.value = KnowledgeSearchState()
    }
}
