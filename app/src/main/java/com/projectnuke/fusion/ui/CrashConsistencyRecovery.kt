package com.projectnuke.fusion.ui

import android.content.Context
import android.util.Log
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.ComposerSubmissionSnapshot
import com.projectnuke.fusion.chat.ManualCompactionOperationRegistry
import com.projectnuke.fusion.chat.shouldProcessReconciliationDebts
import com.projectnuke.fusion.data.ChatDao
import com.projectnuke.fusion.data.ResponseVersionCommitWitnessDao
import com.projectnuke.fusion.data.ResponseVersionCommitWitnessEntity
import com.projectnuke.fusion.data.SubmissionCommitWitnessCodec
import com.projectnuke.fusion.data.SubmissionCommitWitnessContract
import com.projectnuke.fusion.data.SubmissionCommitWitnessDao
import com.projectnuke.fusion.data.SubmissionCommitWitnessEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val CRASH_RECOVERY_BATCH_SIZE = 256
private const val RESPONSE_VERSION_RECOVERY_CHUNK_SIZE = 64

private data class MergeResult(
    val state: ResponseVersionState,
    val conflictSkipped: Boolean,
    val unsettledIds: Set<Long>, // witness IDs skipped due to unresolved conflict or superseding group mismatch
)

internal fun mergeResponseVersionCommitWitness(
    current: ResponseVersionState,
    witness: com.projectnuke.fusion.data.ResponseVersionCommitWitnessEntity,
): ResponseVersionState = mergeResponseVersionCommitWitnesses(current, listOf(witness)).state

/**
 * Reconcile a bounded run of witnesses against one mutable working state.
 * Building immutable maps for each row made a large same-conversation page
 * quadratic; this indexes each group's highest durable message once and
 * copies the maps only after the chunk is complete.
 */
private fun mergeResponseVersionCommitWitnesses(
    current: ResponseVersionState,
    witnesses: List<ResponseVersionCommitWitnessEntity>,
): MergeResult {
    if (witnesses.isEmpty()) return MergeResult(current, conflictSkipped = false, unsettledIds = emptySet())
    val groups = current.groupByMessageId.toMutableMap()
    val active = current.activeMessageIdByGroup.toMutableMap()
    val highestMemberByGroup = mutableMapOf<Long, Long>()
    groups.forEach { (messageId, groupId) ->
        if (messageId > (highestMemberByGroup[groupId] ?: Long.MIN_VALUE)) {
            highestMemberByGroup[groupId] = messageId
        }
    }
    var changed = false
    var conflictSkipped = false
    val unsettledIds = mutableSetOf<Long>()

    witnesses.forEach { witness ->
        val targetGroupNow = groups[witness.targetAssistantMessageId]
        val messageGroupNow = groups[witness.messageId]
        // Two different durable owners for the same pair are ambiguous
        // evidence. Preserve both mappings rather than choosing silently.
        if (targetGroupNow != null && messageGroupNow != null && targetGroupNow != messageGroupNow) {
            conflictSkipped = true
            unsettledIds += witness.messageId
            return@forEach
        }
        // The common save-succeeded/delete-failed case is already represented
        // completely. Its stale witness must not make a backward active choice.
        if (targetGroupNow != null && messageGroupNow == targetGroupNow) return@forEach

        val existingGroup = targetGroupNow ?: messageGroupNow
        val hasLaterGroupMember = existingGroup != null && (
            (highestMemberByGroup[existingGroup]?.let { it > witness.messageId } == true) ||
                (active[existingGroup]?.let { it > witness.messageId } == true)
        )
        // Room's generated INTEGER PRIMARY KEY is the commit-order witness:
        // a later inserted message receives a greater ID. Timestamps are not
        // used to order response versions.
        val resolvedGroup = when {
            messageGroupNow != null -> messageGroupNow
            targetGroupNow != null && hasLaterGroupMember -> targetGroupNow
            else -> witness.groupId
        }
        if (messageGroupNow != null && messageGroupNow != resolvedGroup) {
            unsettledIds += witness.messageId
            return@forEach
        }

        // A newer response may have established another group while this
        // witness was pending. Move the target group only when this witness
        // is the newer durable member.
        if (targetGroupNow != null && targetGroupNow != resolvedGroup) {
            groups.entries.forEach { entry ->
                if (entry.value == targetGroupNow) entry.setValue(resolvedGroup)
            }
            val movedMaximum = highestMemberByGroup.remove(targetGroupNow)
            if (movedMaximum != null && movedMaximum > (highestMemberByGroup[resolvedGroup] ?: Long.MIN_VALUE)) {
                highestMemberByGroup[resolvedGroup] = movedMaximum
            }
            active.remove(targetGroupNow)
            changed = true
        }

        if (groups[witness.targetAssistantMessageId] != resolvedGroup) {
            groups[witness.targetAssistantMessageId] = resolvedGroup
            changed = true
        }
        if (groups[witness.messageId] != resolvedGroup) {
            groups[witness.messageId] = resolvedGroup
            changed = true
        }
        if (witness.messageId > (highestMemberByGroup[resolvedGroup] ?: Long.MIN_VALUE)) {
            highestMemberByGroup[resolvedGroup] = witness.messageId
        }
        // An ungrouped witness may only write the active pointer when it
        // initializes a missing one, or when it completes its own commit and
        // the group contains nothing besides that commit's base and its new
        // message — no alternative version ever existed to choose against, so
        // the completion cannot clobber a selection (P20-02-C/D). Every other
        // durable selection is a later explicit user decision that this
        // unreflected witness must never overwrite (P20-01-A/D, P20-02-A).
        if (messageGroupNow == null && !hasLaterGroupMember && active[resolvedGroup] != witness.messageId) {
            val soleSelectableBase = active[resolvedGroup] == witness.targetAssistantMessageId &&
                groups.entries.none { (memberId, memberGroup) ->
                    memberGroup == resolvedGroup &&
                        memberId != witness.targetAssistantMessageId &&
                        memberId != witness.messageId
                }
            if (active[resolvedGroup] == null || soleSelectableBase) {
                active[resolvedGroup] = witness.messageId
                changed = true
            }
        }
    }

    return if (changed) {
        MergeResult(ResponseVersionState(groups, active), conflictSkipped = conflictSkipped, unsettledIds = unsettledIds.toSet())
    } else {
        MergeResult(current, conflictSkipped = conflictSkipped, unsettledIds = unsettledIds.toSet())
    }
}

/**
 * Reconciles schema-8 submission witnesses only after composer hydration is
 * readable. Every operation is idempotent; a failed durable write leaves the
 * Room witness as the retry payload.
 *
 * Fairness (P17-04): witnesses are scanned in stable `(createdAt, token)`
 * keyset pages instead of a single fixed first page. A permanently damaged
 * oldest prefix no longer starves healthy witnesses behind it: the cursor
 * advances past unresolvable rows within one pass, while the next invocation
 * restarts from the oldest row so failed entries are reconsidered. Only one
 * page is held in memory and the scan stops at the first short page, so work
 * per invocation stays bounded by the table size. No durable cursor is kept,
 * so a process restart can neither skip a recoverable witness nor wrap
 * around incorrectly; rows inserted or deleted concurrently are picked up on
 * the next pass at the latest.
 */
internal suspend fun recoverSchema8SubmissionWitnesses(
    chatViewModel: ChatViewModel,
    witnessDao: SubmissionCommitWitnessDao,
    reconcileCommitted: suspend (SubmissionCommitWitnessEntity, List<String>) -> Boolean = { witness, committedPaths ->
        chatViewModel.reconcileCommittedSubmission(
            snapshot = ComposerSubmissionSnapshot(
                token = witness.token,
                conversationId = witness.draftKey,
                rawInput = witness.capturedRawInput,
                pendingAttachments = emptyList(),
                version = 0L,
            ),
            committedPaths = committedPaths,
        )
    },
    unregisterPendingAttachment: (String) -> Unit,
) = withContext(Dispatchers.IO) {
    if (!shouldProcessReconciliationDebts(chatViewModel.draftHealthNow())) return@withContext

    var cursorCreatedAt = 0L
    var cursorToken: String? = null
    while (true) {
        val witnesses: List<SubmissionCommitWitnessEntity> = try {
            val cursor = cursorToken
            if (cursor == null) {
                witnessDao.getBatch(CRASH_RECOVERY_BATCH_SIZE)
            } else {
                witnessDao.getBatchAfter(cursorCreatedAt, cursor, CRASH_RECOVERY_BATCH_SIZE)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w("FusionRecovery", "Submission witness load failed; preserving witnesses", failure)
            return@withContext
        }
        if (witnesses.isEmpty()) break

        witnesses.forEach { witness -> recoverOneSchema8SubmissionWitness(
            chatViewModel = chatViewModel,
            witnessDao = witnessDao,
            reconcileCommitted = reconcileCommitted,
            unregisterPendingAttachment = unregisterPendingAttachment,
            witness = witness,
        ) }

        if (witnesses.size < CRASH_RECOVERY_BATCH_SIZE) break
        val last = witnesses.last()
        cursorCreatedAt = last.createdAt
        cursorToken = last.token
    }

    // A schema-8 token without a witness proves that its Room transaction did
    // not commit. Clear only ownership; draft text and attachments remain
    // byte/field equivalent. Legacy UUID tokens intentionally do not enter
    // this repair path.
    //
    // Ownership handshake with concurrent admission (P18-01): this pass runs
    // while the composer can already admit new Sends, and a token admitted in
    // *this* process also has no witness until its Room transaction commits.
    // The process-local submission lease is the liveness authority: it is
    // acquired before the durable token is published and released only after
    // the durable token is cleared, so a held lease proves the token is owned
    // by a live operation of this process and must not be reclaimed. Tokens
    // with no lease are previous-process or rolled-back owners and stay
    // exactly as recoverable as before.
    chatViewModel.composerDrafts.value.forEach { (draftKey, draft) ->
        val token = draft.activeSubmissionToken ?: return@forEach
        if (!SubmissionCommitWitnessContract.isSchema8Token(token)) return@forEach
        val witnessRead = try {
            witnessDao.get(token)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w("FusionRecovery", "Cannot decide schema-8 token $token without witness read", failure)
            return@forEach
        }
        if (witnessRead != null) return@forEach
        // Re-validate liveness immediately before acting on the negative
        // witness result: a live owner of this process holds its lease from
        // before the durable token was published until after the durable
        // token is cleared, so absence of a witness alone is never proof of
        // abandonment for a token this process may still own.
        if (ManualCompactionOperationRegistry.holdsSubmission(draftKey, token)) return@forEach
        val settled = try {
            chatViewModel.settleSubmissionOwner(draftKey, token)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w("FusionRecovery", "Schema-8 owner $token could not be settled", failure)
            false
        }
        if (!settled) {
            Log.w("FusionRecovery", "Schema-8 owner $token settlement was not durable")
        }
    }
}

/**
 * One idempotent schema-8 witness step. Any stage that still needs the
 * witness preserves it; corrupt payloads are preserved and skipped without
 * blocking the rest of the batch.
 */
private suspend fun recoverOneSchema8SubmissionWitness(
    chatViewModel: ChatViewModel,
    witnessDao: SubmissionCommitWitnessDao,
    reconcileCommitted: suspend (SubmissionCommitWitnessEntity, List<String>) -> Boolean,
    unregisterPendingAttachment: (String) -> Unit,
    witness: SubmissionCommitWitnessEntity,
) {
    val committedPaths = SubmissionCommitWitnessCodec.decodePaths(witness.committedPathsJson)
    if (committedPaths == null) {
        Log.w("FusionRecovery", "Submission witness ${witness.token} is corrupt; preserving it")
        return
    }

    val current = chatViewModel.draft(witness.draftKey)
    val tokenMatches = current.activeSubmissionToken == witness.token
    if (tokenMatches) {
        val reconciled = try {
            reconcileCommitted(witness, committedPaths)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w("FusionRecovery", "Submission witness ${witness.token} reconciliation failed", failure)
            false
        }
        if (!reconciled) return
    }

    val deleted = try {
        witnessDao.delete(witness.token)
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Log.w("FusionRecovery", "Submission witness ${witness.token} delete failed", failure)
        false
    }
    if (!deleted) return
    // A superseding draft may have deliberately adopted one of these
    // paths. Keep that live registration; durable draft references remain
    // authoritative even though the old witness is now settled.
    val protectedBySupersedingDraft = if (tokenMatches) {
        emptySet()
    } else {
        current.pendingAttachments.map { it.localPath }.toSet()
    }
    committedPaths.filterNot { it in protectedBySupersedingDraft }.forEach { path ->
        try {
            unregisterPendingAttachment(path)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w("FusionRecovery", "Pending registration cleanup failed for $path", failure)
        }
    }
}

/**
 * Repairs assistant response-version structure after a Room/SP process-death
 * gap. The normal structural lease is acquired before the response mutex for
 * every witness, matching production mutation ownership.
 *
 * Fairness (P17-04): same stable keyset pagination as
 * [recoverSchema8SubmissionWitnesses] — a stuck oldest page cannot starve
 * healthy witnesses behind it within one pass, and the next invocation
 * restarts from the oldest row. Stale witnesses (deleted conversation or FK
 * cleanup) are deleted as the scan advances, so they never obstruct it.
 */
internal suspend fun recoverResponseVersionCommitWitnesses(
    context: Context,
    dao: ChatDao,
    witnessDao: ResponseVersionCommitWitnessDao,
) = withContext(Dispatchers.IO) {
    var cursorCreatedAt = 0L
    var cursorMessageId: Long? = null
    while (true) {
        val witnesses: List<ResponseVersionCommitWitnessEntity> = try {
            val cursor = cursorMessageId
            if (cursor == null) {
                witnessDao.getBatch(CRASH_RECOVERY_BATCH_SIZE)
            } else {
                witnessDao.getBatchAfter(cursorCreatedAt, cursor, CRASH_RECOVERY_BATCH_SIZE)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w("FusionRecovery", "Response-version witness load failed; preserving witnesses", failure)
            return@withContext
        }
        if (witnesses.isEmpty()) break

        witnesses.groupBy { it.conversationId }.forEach { (conversationId, conversationWitnesses) ->
            conversationWitnesses.chunked(RESPONSE_VERSION_RECOVERY_CHUNK_SIZE).forEach { chunk ->
                try {
                    recoverResponseVersionWitnessChunk(context, dao, witnessDao, conversationId, chunk)
                } catch (_: ResponseVersionMutationRejectedException) {
                    // Conversation deletion owns the structural lease; retry later.
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    // Bounded recovery failure only: the chunk's witnesses stay for retry.
                    // Cancellation and fatal Errors propagate instead of continuing.
                    Log.w("FusionRecovery", "Response-version witness chunk for $conversationId failed", failure)
                }
            }
        }

        if (witnesses.size < CRASH_RECOVERY_BATCH_SIZE) break
        val last = witnesses.last()
        cursorCreatedAt = last.createdAt
        cursorMessageId = last.messageId
    }
}

/**
 * One idempotent response-version witness step, run under the normal
 * structural-lease -> response-mutex ordering.
 */
private suspend fun recoverResponseVersionWitnessChunk(
    context: Context,
    dao: ChatDao,
    witnessDao: ResponseVersionCommitWitnessDao,
    conversationId: Long,
    witnesses: List<ResponseVersionCommitWitnessEntity>,
) {
    ResponseVersionMutationCoordinator.withMutation(conversationId) {
        val conversation = dao.getConversationById(conversationId)
        if (conversation == null) {
            // Absence is authoritative here: conversation deletion has made
            // all of this bounded chunk's structural evidence irrelevant.
            deleteResponseVersionWitnesses(witnessDao, witnesses)
            return@withMutation
        }

        val validWitnesses = mutableListOf<ResponseVersionCommitWitnessEntity>()
        val rejectedWitnesses = mutableListOf<ResponseVersionCommitWitnessEntity>()
        witnesses.forEach { witness ->
            try {
                val inserted = witnessDao.getMessageById(witness.messageId)
                val target = witnessDao.getMessageById(witness.targetAssistantMessageId)
                if (witness.conversationId != conversationId ||
                    inserted == null ||
                    inserted.conversationId != conversationId ||
                    inserted.role != "assistant" ||
                    target == null ||
                    target.conversationId != conversationId ||
                    target.role != "assistant"
                ) {
                    // A witness is stale evidence, not permission to invent
                    // a grouping for an unrelated message.
                    rejectedWitnesses += witness
                } else {
                    validWitnesses += witness
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Log.w("FusionRecovery", "Response-version witness ${witness.messageId} validation failed", failure)
            }
        }

        if (validWitnesses.isEmpty()) {
            deleteResponseVersionWitnesses(witnessDao, rejectedWitnesses)
            return@withMutation
        }

        val current = when (val loaded = loadResponseVersionStateResult(context, conversationId)) {
            ResponseVersionLoad.Missing -> ResponseVersionState()
            is ResponseVersionLoad.Valid -> loaded.state
            ResponseVersionLoad.Corrupt,
            ResponseVersionLoad.Oversized,
            -> {
                Log.w(
                    "FusionRecovery",
                    "Response-version state $conversationId is not readable; preserving witness",
                )
                deleteResponseVersionWitnesses(witnessDao, rejectedWitnesses)
                return@withMutation
            }
        }
        val converged = settleResponseVersionWitnesses(context, conversationId, current, validWitnesses)
        // One preference read/write per settled run. Witness rows that were
        // not durably applied remain the retry payload; rejected rows are
        // stale evidence and are always cleared.
        deleteResponseVersionWitnesses(witnessDao, rejectedWitnesses + converged)
    }
}

/**
 * Applies a bounded witness run and returns exactly the rows whose durable
 * effect is now saved. When the merged state exceeds the durable bounds the
 * run is bisected against freshly reloaded state, so one oversized witness
 * can never starve individually repairable siblings in the same chunk
 * (P20-04): each side replays exactly the single-chunk order for its own
 * rows, so a split converges to the same state a fitting single chunk would
 * have produced. A transient commit failure or a size-1 oversized result
 * preserves its rows unchanged for the next invocation.
 */
private fun settleResponseVersionWitnesses(
    context: Context,
    conversationId: Long,
    current: ResponseVersionState,
    witnesses: List<ResponseVersionCommitWitnessEntity>,
): List<ResponseVersionCommitWitnessEntity> {
    if (witnesses.isEmpty()) return emptyList()
    val mergeResult = mergeResponseVersionCommitWitnesses(current, witnesses)
    val repaired = mergeResult.state
    val conflictSkipped = mergeResult.conflictSkipped
    // If the durable state did not change:
    // - If conflicts were skipped, preserve the ambiguous evidence.
    // - Otherwise, the witnesses are fully applied no-ops and can be settled.
    val unsettledIds = mergeResult.unsettledIds
    if (repaired === current) {
        if (unsettledIds.isNotEmpty()) {
            Log.w("FusionRecovery", "Response-version witness chunk for $conversationId has unresolved conflicts (witnesses ${unsettledIds.joinToString()}); deleting only settled no-ops, preserving ambiguous evidence")
            return witnesses.filter { it.messageId !in unsettledIds }
        }
        return witnesses
    }
    if (saveResponseVersionState(context, conversationId, repaired)) {
        return witnesses.filter { it.messageId !in unsettledIds }
    }
    if (responseVersionStateEncodesWithinBounds(repaired)) {
        Log.w("FusionRecovery", "Response-version witness chunk for $conversationId SP save failed; preserving witnesses")
        return emptyList()
    }
    if (witnesses.size == 1) {
        Log.w("FusionRecovery", "Response-version witness ${witnesses[0].messageId} exceeds durable bounds; preserving it")
        return emptyList()
    }
    val midpoint = witnesses.size / 2
    val leftState = reloadResponseVersionStateForBisection(context, conversationId) ?: return emptyList()
    val settledLeft = settleResponseVersionWitnesses(
        context,
        conversationId,
        leftState,
        witnesses.take(midpoint),
    )
    val rightState = reloadResponseVersionStateForBisection(context, conversationId) ?: return settledLeft
    return settledLeft + settleResponseVersionWitnesses(
        context,
        conversationId,
        rightState,
        witnesses.drop(midpoint),
    )
}

private fun reloadResponseVersionStateForBisection(
    context: Context,
    conversationId: Long,
): ResponseVersionState? = when (val loaded = loadResponseVersionStateResult(context, conversationId)) {
    ResponseVersionLoad.Missing -> ResponseVersionState()
    is ResponseVersionLoad.Valid -> loaded.state
    ResponseVersionLoad.Corrupt,
    ResponseVersionLoad.Oversized,
    -> {
        Log.w(
            "FusionRecovery",
            "Response-version state $conversationId became unreadable during bisection; preserving witnesses",
        )
        null
    }
}

private suspend fun deleteResponseVersionWitnesses(
    witnessDao: ResponseVersionCommitWitnessDao,
    witnesses: List<ResponseVersionCommitWitnessEntity>,
) {
    witnesses.forEach { witness ->
        try {
            witnessDao.delete(witness.messageId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w("FusionRecovery", "Response-version witness ${witness.messageId} delete failed", failure)
        }
    }
}
