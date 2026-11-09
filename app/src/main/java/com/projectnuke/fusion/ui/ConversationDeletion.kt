package com.projectnuke.fusion.ui

import android.content.Context
import com.projectnuke.fusion.chat.AutoCompactMemoryStore
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.ConversationDeletionResult
import com.projectnuke.fusion.chat.ManualCompactionOperationRegistry
import com.projectnuke.fusion.chat.ConversationStructuralMutationRegistry
import com.projectnuke.fusion.data.ChatDao
import com.projectnuke.fusion.data.ProjectDao
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.util.FusionResponseRatings
import com.projectnuke.fusion.util.isPhysicalCleanupSettled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Once the deletion worker exists, its lifetime—not the UI awaiter's
 * lifetime—owns the manual deletion barrier.
 */
internal suspend fun <T> awaitDeletionWorkerBeforeReleasingManualBarrier(
    barrier: ManualCompactionOperationRegistry.DeletionBarrier,
    worker: Deferred<T>,
    beforeAwait: suspend () -> Unit = {},
    finishBarrier: Boolean = true,
): T {
    return try {
        beforeAwait()
        worker.await()
    } finally {
        withContext(NonCancellable) {
            worker.join()
        }
        if (finishBarrier) barrier.finish()
    }
}

internal suspend fun <T> runDeletionWorkerAfterManualBarrier(
    conversationId: Long,
    barrier: ManualCompactionOperationRegistry.DeletionBarrier,
    beforeWorkerStart: suspend () -> Unit = {},
    startWorker: () -> Deferred<T>,
): T {
    var workerStarted = false
    var structuralBarrier: ConversationStructuralMutationRegistry.DeletionBarrier? = null
    try {
        barrier.cancelAndJoin()
        currentCoroutineContext().ensureActive()
        // The existing manual/submission barrier must settle first. Some of
        // those owners acquire a short structural lease while persisting.
        val structural = ConversationStructuralMutationRegistry.beginDeletion(conversationId)
        structuralBarrier = structural
        structural.awaitQuiescent()
        currentCoroutineContext().ensureActive()
        beforeWorkerStart()
        currentCoroutineContext().ensureActive()
        val worker = startWorker()
        workerStarted = true
        return try {
            awaitDeletionWorkerBeforeReleasingManualBarrier(
                barrier = barrier,
                worker = worker,
                finishBarrier = false,
            )
        } finally {
            withContext(NonCancellable) {
                structural.finish()
                barrier.finish()
            }
        }
    } finally {
        if (!workerStarted) {
            structuralBarrier?.finish()
            barrier.finish()
        }
    }
}

internal suspend fun deleteConversationProduction(
    context: Context,
    dao: ChatDao,
    projectDao: ProjectDao? = null,
    chatViewModel: ChatViewModel,
    conversationId: Long,
): ConversationDeletionResult {
    val appContext = context.applicationContext
    val manualDeletionBarrier = ManualCompactionOperationRegistry.beginDeletion(conversationId)
    var deletedMessageIds: Set<Long> = emptySet()
    val targetPendingPaths = linkedSetOf<String>()
    return runDeletionWorkerAfterManualBarrier(
        conversationId = conversationId,
        barrier = manualDeletionBarrier,
    ) {
        chatViewModel.deleteConversation(
                conversationId = conversationId,
                exists = {
                    val exists = dao.getConversationById(conversationId) != null
                    if (exists) {
                        deletedMessageIds = dao.getMessagesForConversation(conversationId)
                            .mapTo(linkedSetOf()) { it.id }
                    }
                    exists
                },
                commitDelete = { dao.deleteConversation(conversationId) },
                settleTarget = {
                    chatViewModel.draft(conversationId).pendingAttachments.forEach { attachment ->
                        targetPendingPaths.add(attachment.localPath)
                    }
                    chatViewModel.clear(conversationId)
                    val draft = chatViewModel.draft(conversationId)
                    if (draft.rawInput.isNotEmpty() || draft.pendingAttachments.isNotEmpty() ||
                        draft.importOwnership != null || draft.activeSubmissionToken != null
                    ) {
                        check(chatViewModel.clearDraft(conversationId))
                    }
                    if (chatViewModel.currentConversationId.value == conversationId) {
                        chatViewModel.selectConversation(
                            dao.getLatestConversation()?.id ?: ChatViewModel.NEW_CONVERSATION_ID
                        )
                    }
                },
                cleanupDerivedData = {
                    withContext(Dispatchers.IO) {
                        val responseOk = deleteResponseVersionState(appContext, conversationId)
                        val summaryOk = deleteConversationSummary(appContext, conversationId)
                        val memoryOk = deleteConversationOnlyMemoryCandidates(appContext, conversationId)
                        val autoCompactOk = AutoCompactMemoryStore(appContext).deleteDurably(conversationId)
                        val studyOk = com.projectnuke.fusion.chat.StudyProgressStore(java.io.File(appContext.filesDir, "study_progress")).delete(conversationId)
                        val researchAuditsOk = ResearchInterventionAuditStore.from(appContext).removeConversation(conversationId)
                        val ratingsOk = FusionResponseRatings.deleteForMessages(appContext, deletedMessageIds)
                        // Draft references removed by the deleted conversation
                        // are NOT proof of exclusive physical ownership (the
                        // same managed file may survive in a project row,
                        // another draft, or a historical message): release
                        // settles through the authority-aware seam. The
                        // deletion itself is never rolled back because this
                        // optional file GC is conservative — only the debt
                        // bookkeeping stays truthful (unsettled paths are
                        // retried, shared files are never deleted).
                        var attachmentsOk = true
                        AttachmentStorageManager.releaseRemovedDraftAttachments(
                            appContext,
                            dao,
                            projectDao,
                            targetPendingPaths,
                        ).forEach { (_, outcome) ->
                            if (!outcome.isPhysicalCleanupSettled()) {
                                attachmentsOk = false
                            }
                        }
                        AttachmentStorageManager.cleanupUnreferencedAttachments(appContext, dao, projectDao)
                        val debtOk = removeConversationCleanupDebt(appContext, conversationId)
                        if (!responseOk || !summaryOk || !memoryOk || !autoCompactOk || !studyOk || !researchAuditsOk || !ratingsOk || !attachmentsOk || !debtOk) {
                            throw IllegalStateException("cleanup incomplete")
                        }
                    }
                },
                recordCleanupDebt = {
                    withContext(Dispatchers.IO) {
                        check(ConversationCleanupDebtStore.record(
                            appContext,
                            ConversationCleanupDebt(conversationId, deletedMessageIds, targetPendingPaths, 0, 0L),
                        ))
                    }
                },
                recordCleanupDebtResult = {
                    withContext(Dispatchers.IO) {
                        ConversationCleanupDebtStore.record(
                            appContext,
                            ConversationCleanupDebt(conversationId, deletedMessageIds, targetPendingPaths, 0, 0L),
                        )
                    }
                },
        )
    }
}

private fun removeConversationCleanupDebt(context: Context, conversationId: Long): Boolean =
    ConversationCleanupDebtStore.remove(context, conversationId)
