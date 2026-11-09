package com.projectnuke.fusion.inbox

import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.retrieval.SymmetricEmbeddingSource
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bounded smart batch review. Suggestions remain transient and read-only. */
object InboxSmartBatchReviewLimits {
    const val MIN_SMART_BATCH_ITEMS = 2
    const val MAX_SMART_BATCH_ITEMS = 12
    const val MAX_PROPOSALS = 12
}

object InboxSmartBatchReviewPolicy {
    fun canStart(itemCount: Int): Boolean =
        itemCount in InboxSmartBatchReviewLimits.MIN_SMART_BATCH_ITEMS..InboxSmartBatchReviewLimits.MAX_SMART_BATCH_ITEMS

    fun bulkActionLabel(itemCount: Int): String = when {
        itemCount > InboxSmartBatchReviewLimits.MAX_SMART_BATCH_ITEMS ->
            "정리 제안은 한 번에 최대 12개까지 가능합니다."
        itemCount < InboxSmartBatchReviewLimits.MIN_SMART_BATCH_ITEMS ->
            "정리 제안 (2개 이상 선택)"
        else -> "정리 제안"
    }
}

enum class InboxSmartBatchItemStatus { READY, STALE, UNAVAILABLE, SKIPPED, OPENED }

/** Full entity snapshots keep the session key bound to every captured field. */
data class InboxSmartBatchSessionKey(
    val runId: String,
    val items: List<InboxItemEntity>,
)

data class BatchProposal(
    val snapshot: InboxItemEntity,
    val status: InboxSmartBatchItemStatus,
    val proposal: InboxSmartTriageProposal? = null,
) {
    val itemId: String get() = snapshot.id
    val itemRevision: Long get() = snapshot.updatedAt
}

interface InboxSmartBatchProposalSource {
    suspend fun proposeBatch(items: List<InboxItemEntity>): List<BatchProposal>
}

/**
 * Sequential, bounded adapter over the existing single-item Smart Triage
 * owner. It passes each exact frozen entity snapshot through the coordinator
 * and accepts a proposal only when its identity and revision match that row.
 */
class InboxSmartBatchReviewCoordinator(
    private val smartCoordinator: InboxSmartTriageCoordinator,
    private val embeddings: SymmetricEmbeddingSource,
) : InboxSmartBatchProposalSource {
    override suspend fun proposeBatch(items: List<InboxItemEntity>): List<BatchProposal> = withContext(Dispatchers.IO) {
        val frozen = items.distinctBy { it.id }
            .take(InboxSmartBatchReviewLimits.MAX_PROPOSALS)
            .map { it.copy() }
        if (!InboxSmartBatchReviewPolicy.canStart(frozen.size) || frozen.size != items.size) {
            return@withContext emptyList()
        }

        buildList {
            for (snapshot in frozen) {
                currentCoroutineContext().ensureActive()
                val statusAndProposal = try {
                    when (val result = smartCoordinator.proposeSnapshot(snapshot, embeddings)) {
                        is InboxSmartTriageResult.Ready -> {
                            val proposal = result.proposal
                            if (proposal.itemId == snapshot.id && proposal.itemRevision == snapshot.updatedAt) {
                                InboxSmartBatchItemStatus.READY to proposal
                            } else {
                                InboxSmartBatchItemStatus.STALE to null
                            }
                        }
                        InboxSmartTriageResult.Stale -> InboxSmartBatchItemStatus.STALE to null
                        InboxSmartTriageResult.DuplicateSuppressed -> InboxSmartBatchItemStatus.UNAVAILABLE to null
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    InboxSmartBatchItemStatus.UNAVAILABLE to null
                }
                add(BatchProposal(snapshot, statusAndProposal.first, statusAndProposal.second))
            }
        }
    }
}

data class InboxSmartBatchReviewState(
    val sessionKey: InboxSmartBatchSessionKey? = null,
    val proposals: List<BatchProposal> = emptyList(),
    val loading: Boolean = false,
    val failureMessage: String? = null,
)

/** Owns cancellation, session fencing, and per-item review state. */
class InboxSmartBatchReviewController(
    private val proposalSource: InboxSmartBatchProposalSource,
    private val scope: CoroutineScope,
    private val newRunId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(InboxSmartBatchReviewState())
    val state: StateFlow<InboxSmartBatchReviewState> = mutableState.asStateFlow()
    private var activeJob: Job? = null

    fun start(items: List<InboxItemEntity>): Boolean {
        val frozen = items.distinctBy { it.id }.map { it.copy() }
        if (!InboxSmartBatchReviewPolicy.canStart(frozen.size) || frozen.size != items.size) return false
        val key = InboxSmartBatchSessionKey(newRunId(), frozen)
        synchronized(lock) {
            activeJob?.cancel()
            mutableState.value = InboxSmartBatchReviewState(sessionKey = key, loading = true)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val result = try {
                    proposalSource.proposeBatch(key.items)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    synchronized(lock) {
                        if (mutableState.value.sessionKey == key) {
                            mutableState.value = InboxSmartBatchReviewState(
                                sessionKey = key,
                                failureMessage = "정리 제안을 만들지 못했습니다.",
                            )
                            activeJob = null
                        }
                    }
                    return@launch
                }
                synchronized(lock) {
                    if (mutableState.value.sessionKey == key) {
                        val byId = result.associateBy { it.itemId }
                        val ordered = key.items.map { snapshot ->
                            val candidate = byId[snapshot.id]?.takeIf { it.snapshot == snapshot }
                            when {
                                candidate == null -> BatchProposal(snapshot, InboxSmartBatchItemStatus.UNAVAILABLE)
                                candidate.status == InboxSmartBatchItemStatus.READY &&
                                    (candidate.proposal == null || candidate.proposal.itemId != snapshot.id ||
                                        candidate.proposal.itemRevision != snapshot.updatedAt) ->
                                    BatchProposal(snapshot, InboxSmartBatchItemStatus.STALE)
                                else -> candidate
                            }
                        }
                        mutableState.value = InboxSmartBatchReviewState(sessionKey = key, proposals = ordered)
                        activeJob = null
                    }
                }
            }
            activeJob = job
            job.start()
        }
        return true
    }

    fun markSkipped(itemId: String, expectedSession: InboxSmartBatchSessionKey? = state.value.sessionKey) =
        updateItem(itemId, expectedSession, InboxSmartBatchItemStatus.SKIPPED)

    fun markOpened(itemId: String, expectedSession: InboxSmartBatchSessionKey? = state.value.sessionKey) =
        updateItem(itemId, expectedSession, InboxSmartBatchItemStatus.OPENED)

    fun markStale(itemId: String, expectedSession: InboxSmartBatchSessionKey? = state.value.sessionKey) =
        updateItem(itemId, expectedSession, InboxSmartBatchItemStatus.STALE)

    private fun updateItem(
        itemId: String,
        expectedSession: InboxSmartBatchSessionKey?,
        status: InboxSmartBatchItemStatus,
    ) {
        synchronized(lock) {
            val current = mutableState.value
            if (expectedSession == null || current.sessionKey != expectedSession || current.loading) return
            mutableState.value = current.copy(
                proposals = current.proposals.map { item ->
                    if (item.itemId == itemId) item.copy(status = status,
                        proposal = if (status == InboxSmartBatchItemStatus.STALE ||
                            status == InboxSmartBatchItemStatus.SKIPPED || status == InboxSmartBatchItemStatus.OPENED
                        ) null else item.proposal)
                    else item
                },
            )
        }
    }

    /** Dismiss and composition disposal both cancel and clear the session. */
    fun dismiss() {
        synchronized(lock) {
            activeJob?.cancel()
            activeJob = null
            mutableState.value = InboxSmartBatchReviewState()
        }
    }

    fun dispose() = dismiss()
}
