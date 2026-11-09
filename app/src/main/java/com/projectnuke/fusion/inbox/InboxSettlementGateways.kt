package com.projectnuke.fusion.inbox

import androidx.room.withTransaction
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectTaskEntity
import com.projectnuke.fusion.data.ProjectTaskInsertResult
import com.projectnuke.fusion.data.ReviewCardInsertResult
import com.projectnuke.fusion.data.StudyReviewCardEntity
import com.projectnuke.fusion.study.QuickReviewCardDraft
import com.projectnuke.fusion.study.QuickReviewCardPolicy
import com.projectnuke.fusion.study.QuickReviewCardPreparation
import com.projectnuke.fusion.study.QuickReviewCardProposal
import com.projectnuke.fusion.study.QuickReviewCardWriteResult
import com.projectnuke.fusion.study.QuickReviewCardWriter
import com.projectnuke.fusion.study.ReviewCardScope
import com.projectnuke.fusion.tasks.ProjectTaskDraft
import com.projectnuke.fusion.tasks.ProjectTaskPolicy
import com.projectnuke.fusion.tasks.ProjectTaskPreparation
import com.projectnuke.fusion.tasks.ProjectTaskProposal
import com.projectnuke.fusion.tasks.ProjectTaskWriteResult
import com.projectnuke.fusion.tasks.ProjectTaskWriter
import kotlinx.coroutines.CancellationException

/**
 * Smart Triage reviewed-action settlement (corrective v1).
 *
 * Smart Triage proposals for Task / Review Card open the EXISTING editable
 * capture surfaces (`ProjectTaskCaptureSurface`,
 * `StudyReviewCardCaptureSurface`) driven by the existing controllers. Only
 * the explicit Save reaches these writers. Cancellation/dismiss never
 * invokes them, so the Inbox stays untouched.
 *
 * Consumption semantics (move, not copy): the destination insert and the
 * exact Inbox delete settle in ONE Room transaction, and only after the
 * destination validated. Ordering is fail-closed:
 *
 * ```text
 * verify exact Inbox snapshot (get == frozen row) else SourceUnavailable
 * -> destination policy prepare (Invalid / SensitiveContent stay final)
 * -> project availability where applicable
 * -> destination insert (capacity respected)
 * -> exact Inbox delete (row-count verified; rollback otherwise)
 * ```
 *
 * The Inbox source never disappears before successful destination
 * persistence, and a stale/disappeared source never consumes a newer
 * revision: verification compares the full frozen row, not just the id.
 *
 * No semantic classification lives here; policies and DAO bound semantics
 * are reused, never duplicated or weakened.
 */
interface InboxTaskSettlementStore {
    suspend fun <T> inTransaction(block: suspend () -> T): T
    suspend fun getInboxItem(id: String): InboxItemEntity?
    suspend fun getProjectById(id: Long): ProjectEntity?
    suspend fun insertTask(task: ProjectTaskEntity): ProjectTaskInsertResult
    suspend fun deleteInboxItem(id: String): Int
}

class RoomInboxTaskSettlementStore(
    private val database: AppDatabase,
) : InboxTaskSettlementStore {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction(block)

    override suspend fun getInboxItem(id: String): InboxItemEntity? =
        database.inboxItemDao().get(id)

    override suspend fun getProjectById(id: Long): ProjectEntity? =
        database.projectDao().getProjectById(id)

    override suspend fun insertTask(task: ProjectTaskEntity): ProjectTaskInsertResult =
        database.projectTaskDao().insertBounded(task)

    override suspend fun deleteInboxItem(id: String): Int =
        database.inboxItemDao().delete(id)
}

class InboxTaskSettlementGateway(
    private val store: InboxTaskSettlementStore,
) : ProjectTaskWriter {
    /** Exact Inbox row frozen when the editable draft opened. */
    @Volatile
    var snapshot: InboxItemEntity? = null

    override suspend fun save(
        proposal: ProjectTaskProposal,
        archiveAccessIncluded: Boolean,
    ): ProjectTaskWriteResult = try {
        store.inTransaction {
            val frozen = snapshot ?: return@inTransaction ProjectTaskWriteResult.SourceUnavailable
            val live = store.getInboxItem(frozen.id)
            if (live == null || live != frozen) {
                return@inTransaction ProjectTaskWriteResult.SourceUnavailable
            }
            if (store.getProjectById(proposal.projectId) == null) {
                return@inTransaction ProjectTaskWriteResult.ProjectUnavailable
            }
            when (ProjectTaskPolicy.prepare(
                ProjectTaskDraft(
                    proposal.projectId, "", proposal.title, proposal.note,
                    proposal.sourceConversationId, proposal.sourceMessageId,
                ),
                proposal.title,
                proposal.note,
                proposal.createdAt,
            )) {
                ProjectTaskPreparation.Invalid -> return@inTransaction ProjectTaskWriteResult.Invalid
                ProjectTaskPreparation.SensitiveContent -> return@inTransaction ProjectTaskWriteResult.SensitiveContent
                is ProjectTaskPreparation.Ready -> Unit
            }
            when (store.insertTask(ProjectTaskPolicy.entity(proposal))) {
                ProjectTaskInsertResult.CapacityReached -> ProjectTaskWriteResult.CapacityReached
                ProjectTaskInsertResult.ProjectUnavailable -> ProjectTaskWriteResult.ProjectUnavailable
                is ProjectTaskInsertResult.Inserted -> {
                    if (store.deleteInboxItem(frozen.id) != 1) throw InboxSettlementRollback()
                    ProjectTaskWriteResult.Stored
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ProjectTaskWriteResult.Failed
    }
}

interface InboxReviewCardSettlementStore {
    suspend fun <T> inTransaction(block: suspend () -> T): T
    suspend fun getInboxItem(id: String): InboxItemEntity?
    suspend fun getProjectById(id: Long): ProjectEntity?
    suspend fun insertReviewCard(card: StudyReviewCardEntity): ReviewCardInsertResult
    suspend fun deleteInboxItem(id: String): Int
}

class RoomInboxReviewCardSettlementStore(
    private val database: AppDatabase,
) : InboxReviewCardSettlementStore {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction(block)

    override suspend fun getInboxItem(id: String): InboxItemEntity? =
        database.inboxItemDao().get(id)

    override suspend fun getProjectById(id: Long): ProjectEntity? =
        database.projectDao().getProjectById(id)

    override suspend fun insertReviewCard(card: StudyReviewCardEntity): ReviewCardInsertResult =
        database.studyReviewCardDao().insertBounded(card)

    override suspend fun deleteInboxItem(id: String): Int =
        database.inboxItemDao().delete(id)
}

class InboxReviewCardSettlementGateway(
    private val store: InboxReviewCardSettlementStore,
) : QuickReviewCardWriter {
    /** Exact Inbox row frozen when the editable draft opened. */
    @Volatile
    var snapshot: InboxItemEntity? = null

    override suspend fun save(
        proposal: QuickReviewCardProposal,
        archiveAccessIncluded: Boolean,
    ): QuickReviewCardWriteResult = try {
        store.inTransaction {
            val frozen = snapshot ?: return@inTransaction QuickReviewCardWriteResult.SourceUnavailable
            val live = store.getInboxItem(frozen.id)
            if (live == null || live != frozen) {
                return@inTransaction QuickReviewCardWriteResult.SourceUnavailable
            }
            if (proposal.scope == ReviewCardScope.PROJECT) {
                val projectId = proposal.projectId
                    ?: return@inTransaction QuickReviewCardWriteResult.ProjectUnavailable
                if (store.getProjectById(projectId) == null) {
                    return@inTransaction QuickReviewCardWriteResult.ProjectUnavailable
                }
            }
            val draft = QuickReviewCardDraft(
                front = proposal.front,
                back = proposal.back,
                scope = proposal.scope,
                projectId = proposal.projectId,
                sourceKind = proposal.sourceKind,
                sourceConversationId = proposal.sourceConversationId,
                sourceMessageId = proposal.sourceMessageId,
                sourceSavedKnowledgeId = proposal.sourceSavedKnowledgeId,
                sourceProjectId = proposal.sourceProjectId,
                sourceProjectFileId = proposal.sourceProjectFileId,
            )
            when (QuickReviewCardPolicy.prepare(draft, proposal.front, proposal.back, proposal.dueAt, proposal.id)) {
                QuickReviewCardPreparation.Invalid -> return@inTransaction QuickReviewCardWriteResult.Invalid
                QuickReviewCardPreparation.SensitiveContent -> return@inTransaction QuickReviewCardWriteResult.SensitiveContent
                is QuickReviewCardPreparation.Ready -> Unit
            }
            when (store.insertReviewCard(QuickReviewCardPolicy.toEntity(proposal))) {
                ReviewCardInsertResult.CapacityReached -> QuickReviewCardWriteResult.CapacityReached
                ReviewCardInsertResult.ProjectUnavailable -> QuickReviewCardWriteResult.ProjectUnavailable
                ReviewCardInsertResult.Inserted -> {
                    if (store.deleteInboxItem(frozen.id) != 1) throw InboxSettlementRollback()
                    QuickReviewCardWriteResult.Stored
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        QuickReviewCardWriteResult.Failed
    }
}

private class InboxSettlementRollback : IllegalStateException()
