package com.projectnuke.fusion.ui

import android.content.Context
import androidx.room.withTransaction
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.data.escapeSqlLikeQuery
import com.projectnuke.fusion.knowledge.SavedKnowledgeNoteArchivePolicy
import com.projectnuke.fusion.retrieval.DocumentIndexStore
import com.projectnuke.fusion.retrieval.KoEnE5RetrievalOwner
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.util.visibleMessageContent
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal const val SAVED_KNOWLEDGE_SEARCH_PAGE_SIZE = 32
internal const val SAVED_KNOWLEDGE_SEARCH_SCAN_LIMIT = 256
internal const val SAVED_KNOWLEDGE_SEARCH_RESULT_LIMIT = 96

internal enum class AccessibleSearchCoverage {
    COMPLETE,
    ADDITIONAL_ACCESSIBLE_RESULTS,
    RAW_ROWS_UNCHECKED,
}

internal data class AccessibleSearchPageResult<T>(
    val rows: List<T>,
    val rawScanBudgetExhausted: Boolean,
    val resultLimitReached: Boolean,
    val coverage: AccessibleSearchCoverage,
) {
    val rawScanLimitReached: Boolean get() = coverage == AccessibleSearchCoverage.RAW_ROWS_UNCHECKED
    val scanLimitReached: Boolean get() = rawScanLimitReached
    /** true = extra accessible row observed, null = raw budget leaves access unknown. */
    val additionalAccessibleResults: Boolean? get() = when (coverage) {
        AccessibleSearchCoverage.COMPLETE -> false
        AccessibleSearchCoverage.ADDITIONAL_ACCESSIBLE_RESULTS -> true
        AccessibleSearchCoverage.RAW_ROWS_UNCHECKED -> null
    }
}

internal suspend fun <T> collectAccessibleSearchPages(
    pageSize: Int,
    scanLimit: Int,
    resultLimit: Int,
    loadPage: suspend (limit: Int, offset: Int) -> List<T>,
    accessible: suspend (T) -> Boolean,
): List<T> {
    return collectAccessibleSearchPagesWithCoverage(
        pageSize = pageSize,
        scanLimit = scanLimit,
        resultLimit = resultLimit,
        loadPage = loadPage,
        accessible = accessible,
    ).rows
}

internal suspend fun <T> collectAccessibleSearchPagesWithCoverage(
    pageSize: Int,
    scanLimit: Int,
    resultLimit: Int,
    loadPage: suspend (limit: Int, offset: Int) -> List<T>,
    accessible: suspend (T) -> Boolean,
): AccessibleSearchPageResult<T> {
    if (pageSize <= 0 || scanLimit <= 0 || resultLimit <= 0) {
        return AccessibleSearchPageResult(emptyList(), false, false, AccessibleSearchCoverage.COMPLETE)
    }
    val result = mutableListOf<T>()
    var scanned = 0
    var sourceExhausted = false
    var coverage = AccessibleSearchCoverage.COMPLETE
    while (scanned < scanLimit && !sourceExhausted && coverage != AccessibleSearchCoverage.ADDITIONAL_ACCESSIBLE_RESULTS) {
        coroutineContext.ensureActive()
        val requested = minOf(pageSize, scanLimit - scanned)
        val page = loadPage(requested, scanned)
        if (page.isEmpty()) {
            sourceExhausted = true
            break
        }
        val boundedPage = page.take(requested)
        for (row in boundedPage) {
            coroutineContext.ensureActive()
            val permitted = accessible(row)
            coroutineContext.ensureActive()
            scanned++
            if (permitted) {
                if (result.size < resultLimit) result += row
                else {
                    coverage = AccessibleSearchCoverage.ADDITIONAL_ACCESSIBLE_RESULTS
                    break
                }
            }
        }
        if (boundedPage.size < requested) {
            sourceExhausted = true
        }
    }
    val rawScanBudgetExhausted = scanned >= scanLimit
    if (rawScanBudgetExhausted && !sourceExhausted && coverage == AccessibleSearchCoverage.COMPLETE) {
        coroutineContext.ensureActive()
        if (loadPage(1, scanLimit).isNotEmpty()) coverage = AccessibleSearchCoverage.RAW_ROWS_UNCHECKED
        else sourceExhausted = true
    }
    return AccessibleSearchPageResult(
        rows = result,
        rawScanBudgetExhausted = rawScanBudgetExhausted,
        resultLimitReached = result.size >= resultLimit,
        coverage = coverage,
    )
}

internal interface FusionKnowledgeSource {
    suspend fun search(query: String): List<KnowledgeCandidate>
    suspend fun search(query: String, filters: KnowledgeSearchFilters): List<KnowledgeCandidate> = search(query)
    suspend fun searchWithStatus(query: String, filters: KnowledgeSearchFilters): KnowledgeSearchOutcome =
        KnowledgeSearchOutcome(search(query, filters))
    suspend fun relatedTo(
        target: KnowledgeTarget,
        seed: RelatedKnowledgeSeed? = null,
        limit: Int = RELATED_KNOWLEDGE_VISIBLE_LIMIT,
    ): List<KnowledgeCandidate> = emptyList()
    suspend fun filterOptions(): KnowledgeSearchFilterOptions = KnowledgeSearchFilterOptions()
    suspend fun accessible(target: KnowledgeTarget): Boolean
}

internal class FusionKnowledgeSearchRepository(
    private val context: Context,
    private val includeArchived: () -> Boolean,
) : FusionKnowledgeSource {
    private val db = AppDatabase.getInstance(context)
    private val semantic = KoEnE5RetrievalOwner.proxy(context.assets, File(context.filesDir, "koen-e5"))

    override suspend fun search(query: String): List<KnowledgeCandidate> = search(query, KnowledgeSearchFilters())

    override suspend fun filterOptions(): KnowledgeSearchFilterOptions = withContext(Dispatchers.IO) {
        KnowledgeSearchFilterOptions(
            tags = db.knowledgeOrganizationDao().listTags(48),
            collections = db.knowledgeOrganizationDao().listCollections(48),
            projects = db.projectDao().getRecentProjectsBounded(32),
        )
    }

    override suspend fun search(query: String, filters: KnowledgeSearchFilters): List<KnowledgeCandidate> =
        searchWithStatus(query, filters).candidates

    override suspend fun searchWithStatus(query: String, filters: KnowledgeSearchFilters): KnowledgeSearchOutcome = withContext(Dispatchers.IO) {
        val archiveAllowed = includeArchived()
        val chat = db.knowledgeSearchDao()
        val project = chat
        val bodyHits = if (query.isBlank()) emptyList() else chat.searchConversationMessageHits(
            escapeSqlLikeQuery(query), archiveAllowed, 96,
        )
        val visibleBodyByConversation = bodyHits.mapNotNull { hit ->
            visibleMessageContent(hit.content)
                .takeIf { it.contains(query, ignoreCase = true) }
                ?.let { hit.conversation.id to it }
        }.toMap()
        val conversations = (chat.searchConversationTitles(escapeSqlLikeQuery(query), archiveAllowed, 48) +
            chat.getSearchConversations(archiveAllowed, 48) + bodyHits.map { it.conversation })
            .distinctBy { it.id }
        val ids = conversations.map { it.id }.toSet()
        val messages = chat.getSearchMessages(ids.toList(), 96)
        val summaries = loadConversationSummaries(context, ids).associateBy { it.conversationId }
        val candidates = mutableListOf<KnowledgeCandidate>()
        conversations.forEach { row ->
            val body = visibleBodyByConversation[row.id]
            val selectedText = body ?: summaries[row.id]?.summary.orEmpty()
            candidates += KnowledgeCandidate("c:${row.id}", KnowledgeKind.CONVERSATION, row.title,
                knowledgePreview(selectedText, query), if (body == null) "대화 · 요약" else "대화",
                KnowledgeTarget.Conversation(row.id), knowledgePreview(selectedText, query, 1_200))
        }
        val projectRows = if (filters.projectId != null) {
            listOfNotNull(project.searchProjectById(filters.projectId, escapeSqlLikeQuery(query)))
        } else {
            project.searchProjects(escapeSqlLikeQuery(query), 32) + project.getSearchProjects(32)
        }
        projectRows
            .distinctBy { it.id }.forEach { row ->
                candidates += KnowledgeCandidate("p:${row.id}", KnowledgeKind.PROJECT, row.name,
                    row.instructions.take(1000), "프로젝트", KnowledgeTarget.Project(row.id),
                    knowledgePreview(row.instructions, query, 1_200))
            }
        val root = AttachmentStorageManager.getAttachmentDirectory(context)
        val store = DocumentIndexStore(File(context.filesDir, "document_index"))
        suspend fun addDocument(key: String, name: String, path: String, target: KnowledgeTarget, loadIndex: Boolean) {
            coroutineContext.ensureActive()
            val source = AttachmentStorageManager.resolveManagedAttachment(context, path) ?: return
            // Search reads existing derived indexes only; it never extracts/indexes a source.
            val index = if (loadIndex) store.load(source.canonicalPath, source) else null
            val pages = index?.pages.orEmpty().filter { it.text.isNotBlank() }
            val page = pages.firstOrNull { it.text.contains(query, true) } ?: pages.firstOrNull()
            val sourceText = page?.text.orEmpty()
            candidates += KnowledgeCandidate(key, KnowledgeKind.DOCUMENT, name, knowledgePreview(sourceText, query),
                if (index?.metadata?.kind == com.projectnuke.fusion.retrieval.LocalDocumentKind.OCR_IMAGE) "이미지 · OCR"
                else page?.let { "문서 · p.${it.pageNumber}" + if (it.ocrDerived) " · OCR" else "" } ?: "문서", target,
                knowledgePreview(sourceText, query, 1_200))
        }
        val projectFiles = filters.projectId?.let { project.getSearchFilesForProject(it, 32) }
            ?: project.getSearchFiles(32)
        projectFiles.forEachIndexed { index, row ->
            addDocument("d:${row.id}", row.displayName, row.localPath,
                KnowledgeTarget.Project(row.projectId, row.id), index < 8)
        }
        messages.take(24).forEachIndexed { index, message ->
            AttachmentMessageCodec.parseTrustedAttachmentMessage(message.content, root).records.take(4).forEachIndexed { part, row ->
                addDocument("a:${message.conversationId}:${com.projectnuke.fusion.retrieval.DocumentIndexContract.keyFor(row.localPath)}", row.name, row.localPath,
                    KnowledgeTarget.Conversation(message.conversationId, row.localPath), index < 8)
            }
        }
        val accessibleConversationIds = chat.getSearchConversationIds(archiveAllowed).toSet()
        ArchiveAccessPolicy.accessibleMemories(
            loadAllConversationMemoryCandidates(context), accessibleConversationIds,
        )
            .filter { it.enabled && it.savedByUser && it.scope != MemoryScope.DISABLED }.take(64).forEach { row ->
                candidates += KnowledgeCandidate("m:${row.id}", KnowledgeKind.MEMORY, "저장된 메모리",
                    row.text, "메모리", KnowledgeTarget.Memory(row.id), knowledgePreview(row.text, query, 1_200))
            }
        val savedPattern = "%${escapeSqlLikeQuery(query)}%"
        // Membership-first selection: when a tag/collection filter is active,
        // the membership JOIN runs inside the DAO query so LIMIT applies to
        // rows already within that membership ("latest within tag"), not to
        // an arbitrary raw window later subset by tag. No-filter behavior is
        // unchanged. Access gate, ordering, and per-kind caps are preserved.
        val savedRows = savedSearchRows(savedPattern, filters, archiveAllowed)
        savedRows.rows.forEach { row ->
            // Matching rows were access-filtered inside a bounded snapshot scan;
            // final target admission below rechecks access before presentation.
            val sourceConversation = row.sourceConversationId?.let { db.chatDao().getConversationById(it) }
            val highlightSource = if (row.provenanceType == "DOCUMENT_HIGHLIGHT") {
                db.documentHighlightSourceDao().getByKnowledgeId(row.id)
            } else null
            val sourceFile = highlightSource?.let { db.projectDao().getProjectFileById(it.projectFileId) }
            val sourceFileAvailable = sourceFile?.let {
                AttachmentStorageManager.resolveManagedAttachment(context, it.localPath) != null
            } == true
            val origin = when {
                row.provenanceType == "USER_NOTE" -> "연구 노트"
                row.provenanceType == "DOCUMENT_HIGHLIGHT" && !sourceFileAvailable -> "문서 하이라이트 · 원본 파일을 찾을 수 없음"
                highlightSource != null -> "문서 하이라이트 · ${sourceFile?.displayName.orEmpty()}" +
                    (highlightSource.pageNumber?.let { " · p.$it" } ?: "")
                row.sourceConversationId != null && sourceConversation == null -> "저장한 답변 사본 · 원본 대화 없음"
                else -> "저장한 답변 사본"
            }
            val freshness = if (row.provenanceType == "WEB_CLIP") {
                webClipFreshnessLabel(row.retrievedAt, System.currentTimeMillis())
            } else null
            candidates += KnowledgeCandidate(
                "s:${row.id}", KnowledgeKind.SAVED_KNOWLEDGE, row.title,
                knowledgePreview(row.savedText + "\n" + highlightSource?.userNote.orEmpty(), query), listOfNotNull(origin, freshness).joinToString(" · "),
                KnowledgeTarget.SavedKnowledge(row.id), knowledgePreview(row.savedText, query, 1_200),
            )
        }
        val inboxPattern = escapeSqlLikeQuery(query)
        val inboxRows = when {
            filters.tagKey != null && filters.collectionId != null ->
                (db.inboxItemDao().searchInTagAndCollection(inboxPattern, filters.tagKey, filters.collectionId, 32) +
                    db.inboxItemDao().latestInTagAndCollection(filters.tagKey, filters.collectionId, 24))
            filters.tagKey != null ->
                (db.inboxItemDao().searchInTag(inboxPattern, filters.tagKey, 32) +
                    db.inboxItemDao().latestInTag(filters.tagKey, 24))
            filters.collectionId != null ->
                (db.inboxItemDao().searchInCollection(inboxPattern, filters.collectionId, 32) +
                    db.inboxItemDao().latestInCollection(filters.collectionId, 24))
            else ->
                (db.inboxItemDao().search(inboxPattern, 32) + db.inboxItemDao().getLatestBounded(24))
        }
        inboxRows.distinctBy { it.id }.take(56).forEach { row ->
            candidates += KnowledgeCandidate(
                "i:${row.id}", KnowledgeKind.INBOX, row.title,
                knowledgePreview(row.content.ifBlank { row.originalUri.orEmpty() }, query), "Inbox",
                KnowledgeTarget.InboxItem(row.id), knowledgePreview(row.content.ifBlank { row.originalUri.orEmpty() }, query, 1_200),
            )
        }
        if (query.trim().length >= 2) {
            db.conversationCheckpointDao().search(escapeSqlLikeQuery(query), archiveAllowed, 32).forEach { checkpoint ->
                candidates += KnowledgeCandidate(
                    key = "checkpoint:${checkpoint.id}",
                    kind = KnowledgeKind.CHECKPOINT,
                    title = checkpoint.title,
                    preview = checkpoint.note,
                    metadata = "체크포인트 · ${checkpoint.conversationTitle}",
                    target = KnowledgeTarget.Checkpoint(checkpoint.id, checkpoint.conversationId, checkpoint.messageId),
                    selectionExcerpt = "",
                )
            }
        }
        val organizationDao = db.knowledgeOrganizationDao()
        val filtered = candidates.filter { candidate ->
            if (filters.kind != null && candidate.kind != filters.kind) return@filter false
            if (filters.projectId != null) {
                val belongsToProject = knowledgeTargetBelongsToProject(
                    candidate.target,
                    filters.projectId,
                    conversationProjectId = { conversationId -> db.projectDao().getProjectIdForConversation(conversationId) },
                    savedKnowledgeProjectId = { savedId -> db.savedKnowledgeDao().getById(savedId)?.projectId },
                )
                if (!belongsToProject) return@filter false
            }
            if (filters.tagKey != null) {
                val hasTag = when (val target = candidate.target) {
                    is KnowledgeTarget.SavedKnowledge -> organizationDao.savedItemHasTag(target.id, filters.tagKey) != null
                    is KnowledgeTarget.InboxItem -> organizationDao.inboxItemHasTag(target.id, filters.tagKey) != null
                    else -> false
                }
                if (!hasTag) return@filter false
            }
            if (filters.collectionId != null) {
                val inCollection = when (val target = candidate.target) {
                    is KnowledgeTarget.SavedKnowledge -> organizationDao.savedItemInCollection(target.id, filters.collectionId) != null
                    is KnowledgeTarget.InboxItem -> organizationDao.inboxItemInCollection(target.id, filters.collectionId) != null
                    else -> false
                }
                if (!inCollection) return@filter false
            }
            true
        }
        // Access filtering occurs before ranking/top-K so inaccessible
        // high-ranked entries cannot consume user-visible slots and starve
        // accessible lower-ranked results. Deterministic ordering among
        // permitted candidates is preserved by rankKnowledge.
        // CancellationException propagates; recoverable accessible() failure
        // is already fail-closed inside accessible().
        val permitted = filtered.filter { accessible(it.target) }
        KnowledgeSearchOutcome(
            candidates = rankKnowledge(query, permitted, semantic),
            savedKnowledgeScanIncomplete = savedRows.coverage == AccessibleSearchCoverage.RAW_ROWS_UNCHECKED,
            savedKnowledgeRawScanBudgetExhausted = savedRows.rawScanBudgetExhausted,
            savedKnowledgeResultLimitReached = savedRows.resultLimitReached,
            savedKnowledgeAdditionalResults = savedRows.coverage == AccessibleSearchCoverage.ADDITIONAL_ACCESSIBLE_RESULTS,
        )
    }

    private data class SavedSearchRows(
        val rows: List<SavedKnowledgeEntity>,
        val rawScanBudgetExhausted: Boolean,
        val resultLimitReached: Boolean,
        val coverage: AccessibleSearchCoverage,
    )

    private suspend fun savedSearchRows(
        pattern: String,
        filters: KnowledgeSearchFilters,
        archiveAllowed: Boolean,
    ): SavedSearchRows = db.withTransaction {
        val dao = db.savedKnowledgeDao()
        val matching = collectAccessibleSearchPagesWithCoverage(
            pageSize = SAVED_KNOWLEDGE_SEARCH_PAGE_SIZE,
            scanLimit = SAVED_KNOWLEDGE_SEARCH_SCAN_LIMIT,
            resultLimit = SAVED_KNOWLEDGE_SEARCH_RESULT_LIMIT,
            loadPage = { limit, offset ->
                when {
                    filters.projectId != null && filters.tagKey != null && filters.collectionId != null ->
                        dao.searchInTagAndCollectionProjectPage(
                            pattern, filters.tagKey, filters.collectionId, filters.projectId, limit, offset,
                        )
                    filters.projectId != null && filters.tagKey != null ->
                        dao.searchInTagProjectPage(pattern, filters.tagKey, filters.projectId, limit, offset)
                    filters.projectId != null && filters.collectionId != null ->
                        dao.searchInCollectionProjectPage(pattern, filters.collectionId, filters.projectId, limit, offset)
                    filters.projectId != null ->
                        dao.searchProjectPage(pattern, filters.projectId, limit, offset)
                    filters.tagKey != null && filters.collectionId != null ->
                        dao.searchInTagAndCollectionPage(pattern, filters.tagKey, filters.collectionId, limit, offset)
                    filters.tagKey != null -> dao.searchInTagPage(pattern, filters.tagKey, limit, offset)
                    filters.collectionId != null -> dao.searchInCollectionPage(pattern, filters.collectionId, limit, offset)
                    else -> dao.searchPage(pattern, limit, offset)
                }
            },
            accessible = { row ->
                com.projectnuke.fusion.knowledge.SavedKnowledgeAccessGate.isRowAccessible(db, row, archiveAllowed)
            },
        )
        val latest = when {
            filters.projectId != null && filters.tagKey != null && filters.collectionId != null ->
                dao.latestInTagAndCollectionProject(filters.tagKey, filters.collectionId, filters.projectId, 48)
            filters.projectId != null && filters.tagKey != null ->
                dao.latestInTagProject(filters.tagKey, filters.projectId, 48)
            filters.projectId != null && filters.collectionId != null ->
                dao.latestInCollectionProject(filters.collectionId, filters.projectId, 48)
            filters.projectId != null -> dao.latestInProject(filters.projectId, 48)
            filters.tagKey != null && filters.collectionId != null ->
                dao.latestInTagAndCollection(filters.tagKey, filters.collectionId, 48)
            filters.tagKey != null -> dao.latestInTag(filters.tagKey, 48)
            filters.collectionId != null -> dao.latestInCollection(filters.collectionId, 48)
            else -> dao.latest(48)
        }
        val accessibleLatest = latest.filter { row ->
            com.projectnuke.fusion.knowledge.SavedKnowledgeAccessGate.isRowAccessible(db, row, archiveAllowed)
        }
        val combined = (matching.rows + accessibleLatest).distinctBy { it.id }
        SavedSearchRows(
            rows = combined.take(SAVED_KNOWLEDGE_SEARCH_RESULT_LIMIT),
            rawScanBudgetExhausted = matching.rawScanBudgetExhausted,
            resultLimitReached = matching.resultLimitReached || combined.size > SAVED_KNOWLEDGE_SEARCH_RESULT_LIMIT,
            // Latest rows are bounded fallback candidates and need not match
            // the query, so they cannot prove that another search result exists.
            coverage = matching.coverage,
        )
    }

    /** User-invoked, bounded retrieval for a detail surface; E5 remains lazy through the shared owner proxy. */
    override suspend fun relatedTo(
        target: KnowledgeTarget,
        seed: RelatedKnowledgeSeed?,
        limit: Int,
    ): List<KnowledgeCandidate> = withContext(Dispatchers.IO) {
        val boundedLimit = limit.coerceIn(0, RELATED_KNOWLEDGE_VISIBLE_LIMIT)
        if (boundedLimit == 0 || !accessible(target)) return@withContext emptyList()
        val source = seed ?: relatedSeed(target) ?: return@withContext emptyList()
        val query = relatedKnowledgeQuery(source)
        if (query.length < 2) return@withContext emptyList()
        suspend fun resolve(candidateQuery: String): List<KnowledgeCandidate> =
            selectRelatedKnowledge(target, search(candidateQuery).filter { accessible(it.target) }, boundedLimit)
        resolve(query).ifEmpty {
            val fallback = relatedKnowledgeFallbackQuery(source)
            if (fallback.length >= 2 && fallback != query) resolve(fallback) else emptyList()
        }
    }

    private suspend fun relatedSeed(target: KnowledgeTarget): RelatedKnowledgeSeed? = when (target) {
        is KnowledgeTarget.Conversation -> {
            val conversation = db.chatDao().getConversationById(target.id) ?: return null
            val text = if (target.attachmentPath != null) {
                indexedDocumentExcerpt(target.attachmentPath)
            } else {
                db.knowledgeSearchDao().getSearchMessages(listOf(target.id), 4)
                    .asSequence().map { visibleMessageContent(it.content) }
                    .filter(String::isNotBlank).joinToString("\n").take(1_200)
            }
            RelatedKnowledgeSeed(conversation.title, text)
        }
        is KnowledgeTarget.Project -> {
            val project = db.projectDao().getProjectById(target.id) ?: return null
            val file = target.fileId?.let { db.projectDao().getProjectFileById(it) }
                ?.takeIf { it.projectId == target.id }
            RelatedKnowledgeSeed(file?.displayName ?: project.name,
                file?.let { indexedDocumentExcerpt(it.localPath) } ?: project.instructions.take(1_200))
        }
        is KnowledgeTarget.Decision -> db.projectDecisionDao().get(target.decisionId, target.projectId)
            ?.let { RelatedKnowledgeSeed(it.title, it.rationale.take(1_200)) }
        is KnowledgeTarget.Memory -> loadAllConversationMemoryCandidates(context)
            .firstOrNull { it.id == target.id && it.enabled && it.savedByUser && it.scope != MemoryScope.DISABLED }
            ?.let { RelatedKnowledgeSeed(it.conversationTitle ?: "저장된 메모리", it.text.take(1_200)) }
        is KnowledgeTarget.SavedKnowledge -> db.savedKnowledgeDao().getById(target.id)
            ?.let { RelatedKnowledgeSeed(it.title, it.savedText.take(1_200)) }
        is KnowledgeTarget.InboxItem -> db.inboxItemDao().get(target.id)
            ?.let { RelatedKnowledgeSeed(it.title, it.content.take(1_200)) }
        is KnowledgeTarget.Checkpoint -> null
    }

    private fun indexedDocumentExcerpt(path: String): String {
        val source = AttachmentStorageManager.resolveManagedAttachment(context, path) ?: return ""
        return DocumentIndexStore(File(context.filesDir, "document_index"))
            .load(source.canonicalPath, source)?.pages.orEmpty()
            .asSequence().map { it.text }.filter(String::isNotBlank)
            .take(2).joinToString("\n").take(1_200)
    }

    /** Revalidate durable identity and access again at the moment of navigation. */
    override suspend fun accessible(target: KnowledgeTarget): Boolean = withContext(Dispatchers.IO) {
        if (target is KnowledgeTarget.SavedKnowledge && target.expectedProjectId != null) {
            val row = db.savedKnowledgeDao().getById(target.id) ?: return@withContext false
            if (row.id != target.id || row.projectId != target.expectedProjectId) return@withContext false
        }
        // Issue 5: the six selectable source kinds share exactly one access
        // rule with Work Plan gates and result display. Decision, Inbox,
        // and Checkpoint targets are not selectable sources and keep their
        // existing inline checks below.
        val identity = target.toSourceIdentity()
        if (identity != null) {
            return@withContext com.projectnuke.fusion.knowledge.SourceAccessAuthority(
                archiveAccessIncluded = { includeArchived() },
                lookups = RoomSourceAccessLookups(context, db),
            ).isAccessible(identity)
        }
        when (target) {
            is KnowledgeTarget.Decision -> db.projectDao().getProjectById(target.projectId) != null &&
                db.projectDecisionDao().get(target.decisionId, target.projectId) != null
            is KnowledgeTarget.InboxItem -> db.inboxItemDao().get(target.id) != null
            is KnowledgeTarget.Checkpoint -> db.conversationCheckpointDao().getOwned(target.id, target.conversationId)
                ?.takeIf { it.messageId == target.messageId }
                ?.let { checkpoint ->
                    val conversation = db.chatDao().getConversationById(checkpoint.conversationId)
                    conversation != null && (!conversation.isArchived || includeArchived()) &&
                        db.conversationCheckpointDao().hasAssistantAnchor(checkpoint.conversationId, checkpoint.messageId)
                } == true
            else -> false
        }
    }
}
