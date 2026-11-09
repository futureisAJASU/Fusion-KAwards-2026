package com.projectnuke.fusion.knowledge

import androidx.room.withTransaction
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Phase 1 shared gate: access control applies to the actual stored-knowledge
 * body, not merely to metadata IDs. All project/report/snapshot/review-card/
 * search/detail consumers route through this single domain-level check, which
 * mirrors [SourceAccessAuthority] saved-knowledge visibility without
 * duplicating archive logic in each caller.
 *
 * Fail-closed on recoverable storage exceptions; [CancellationException] and
 * fatal [Error] propagate and are never converted into "not accessible".
 */
object SavedKnowledgeAccessGate {
    fun isVisible(
        archiveAllowed: Boolean,
        ownSourceArchived: Boolean,
        linkedSourcesArchived: List<Boolean>,
    ): Boolean =
        SavedKnowledgeNoteArchivePolicy.isVisible(archiveAllowed, ownSourceArchived, linkedSourcesArchived)

    suspend fun isRowAccessible(
        database: AppDatabase,
        row: SavedKnowledgeEntity,
        archiveAllowed: Boolean,
    ): Boolean {
        try {
            val ownArchived = row.sourceConversationId?.let { sourceId ->
                database.chatDao().getConversationById(sourceId)?.isArchived
            } == true
            // Missing source conversation: preserve existing independent-copy
            // contract (provenance only); only a positively archived source denies.
            val linkedArchived: List<Boolean> =
                if (row.provenanceType == "USER_NOTE") {
                    database.savedKnowledgeNoteSourceDao().getForNote(row.id, 8).mapNotNull { source ->
                        when (source.sourceKind) {
                            "CONVERSATION" -> source.conversationId?.let { id ->
                                database.chatDao().getConversationById(id)?.isArchived
                            }
                            "SAVED_KNOWLEDGE" -> source.savedKnowledgeId?.let { id ->
                                database.savedKnowledgeDao().getById(id)?.sourceConversationId?.let { conversationId ->
                                    database.chatDao().getConversationById(conversationId)?.isArchived
                                }
                            }
                            else -> null
                        }
                    }
                } else {
                    emptyList()
                }
            return SavedKnowledgeNoteArchivePolicy.isVisible(archiveAllowed, ownArchived, linkedArchived)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Recoverable read failure fails closed where callers require it.
            return false
        }
        // Fatal Error propagates to the caller; never mapped to false here.
    }

    suspend fun filterAccessible(
        database: AppDatabase,
        rows: List<SavedKnowledgeEntity>,
        archiveAllowed: Boolean,
    ): List<SavedKnowledgeEntity> {
        if (rows.isEmpty()) return rows
        return rows.filter { isRowAccessible(database, it, archiveAllowed) }
    }

    /**
     * Bounded accessible selection for project-scoped report/snapshot consumers.
     *
     * Pages deterministically through [projectId] items (ordered by
     * updatedAt DESC, id DESC) until [requiredCount] accessible rows are
     * collected or all rows for this project have been scanned.
     *
     * There is no fixed per-project cap on the number of stored knowledge rows,
     * so the scan terminates only when:
     *  - [requiredCount] accessible rows have been collected; or
     *  - the DAO returns an empty page (all rows exhausted).
     *
     * This guarantees that inaccessible (archived-source) rows can never
     * permanently starve accessible rows, regardless of how many precede
     * them in sort order.
     *
     * CancellationException and fatal Error propagate; recoverable read
     * exceptions on a single row fail that row closed (excluded) without
     * aborting the page scan.
     */
    suspend fun selectAccessibleBounded(
        database: AppDatabase,
        projectId: Long,
        pageSize: Int,
        requiredCount: Int,
        archiveAllowed: Boolean,
        maxRowsScanned: Int = Int.MAX_VALUE,
    ): List<SavedKnowledgeEntity> {
        if (requiredCount <= 0 || pageSize <= 0 || maxRowsScanned <= 0) return emptyList()
        return database.withTransaction {
            collectAccessibleKnowledgePages(
                pageSize = pageSize,
                requiredCount = requiredCount,
                maxRowsScanned = maxRowsScanned,
                loadPage = { limit, offset ->
                    database.savedKnowledgeDao().getProjectItemsBoundedOffset(projectId, limit, offset)
                },
                accessible = { row ->
                    try {
                        isRowAccessible(database, row, archiveAllowed)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                },
            )
        }
    }
}

/** Page through a stable read view; callers that use OFFSET must hold a DB snapshot. */
internal suspend fun <T> collectAccessibleKnowledgePages(
    pageSize: Int,
    requiredCount: Int,
    maxRowsScanned: Int,
    loadPage: suspend (limit: Int, offset: Int) -> List<T>,
    accessible: suspend (T) -> Boolean,
): List<T> {
    if (pageSize <= 0 || requiredCount <= 0 || maxRowsScanned <= 0) return emptyList()
    val result = ArrayList<T>(requiredCount)
    var offset = 0
    while (result.size < requiredCount && offset < maxRowsScanned) {
        currentCoroutineContext().ensureActive()
        val page = loadPage(minOf(pageSize, maxRowsScanned - offset), offset)
        if (page.isEmpty()) break
        for (row in page) {
            currentCoroutineContext().ensureActive()
            if (result.size >= requiredCount) break
            if (accessible(row)) result += row
        }
        offset += page.size
    }
    return result
}
