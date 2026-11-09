package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.SubmissionCommitWitnessCodec
import com.projectnuke.fusion.data.SubmissionCommitWitnessContract
import com.projectnuke.fusion.data.SubmissionCommitWitnessEntity
import com.projectnuke.fusion.util.AttachmentReferenceMutationCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Only bounded implementation failures are recoverable at a request boundary. */
internal fun isBoundedNonFatalRequestFailure(failure: Throwable): Boolean =
    (failure is RuntimeException && failure !is CancellationException) || failure is LinkageError

/** Request cleanup must complete even when the caller is already cancelled. */
internal suspend fun settleFailedRequestBoundary(
    chatViewModel: com.projectnuke.fusion.chat.ChatViewModel,
    owner: MessageSubmissionOwner,
    conversationId: Long? = null,
    requestId: String? = null,
) = withContext(NonCancellable) {
    if (conversationId != null && requestId != null) {
        chatViewModel.finishRequestState(conversationId, requestId)
    }
    chatViewModel.settleSubmissionOwner(owner.sourceConversationId, owner.token)
}

internal data class SubmissionCommitState(
    var conversationId: Long,
    var conversationWasCreated: Boolean = false,
    var messageInserted: Boolean = false,
    var userMessageId: Long? = null,
    val witnessPayload: SubmissionCommitWitnessPayload? = null,
)

internal data class SubmissionCommitWitnessPayload(
    val token: String,
    val draftKey: Long,
    val capturedRawInput: String,
    val committedPaths: List<String>,
    val createdAt: Long,
)

internal data class SubmissionCommitResult(
    val conversationId: Long,
    val conversationWasCreated: Boolean,
    val userMessageId: Long?,
    val publicationFailure: Throwable?,
    val reconciliationFailed: Boolean,
)

/**
 * Owns the irreversible user-message commit boundary.
 *
 * Cancellation is observed before the commit starts. Once the block begins, the optional
 * conversation insert and user-message insert run inside the supplied database transaction, then
 * publication, composer reconciliation and timestamp update settle without a cancellable gap. The
 * transaction itself is the single rollback boundary, so no second best-effort row deletion is issued.
 *
 * The whole commit + settlement (durable draft/pending attachment ownership
 * -> committed user-message attachment reference -> draft reconciliation and
 * pending registration release) is serialized with destructive attachment
 * GC under [AttachmentReferenceMutationCoordinator.withMutation], so a
 * cleanup scan can never observe "pending registration gone AND the
 * committed message not yet durable" as deletion permission.
 */
internal suspend fun commitAndSettleUserSubmission(
    state: SubmissionCommitState,
    parentJob: Job?,
    runInTransaction: suspend (suspend () -> Unit) -> Unit,
    createConversation: suspend () -> Long,
    insertUserMessage: suspend (conversationId: Long) -> Unit = {},
    insertUserMessageReturningId: (suspend (conversationId: Long) -> Long)? = null,
    insertSubmissionWitness: (suspend (witness: SubmissionCommitWitnessEntity) -> Unit)? = null,
    assignCreatedConversation: (suspend (conversationId: Long) -> Unit)? = null,
    publishConversation: (conversationId: Long) -> Unit,
    reconcileCommittedDraft: suspend () -> Boolean,
    updateConversationTimestamp: suspend (conversationId: Long) -> Unit,
    onPublicationFailure: (Throwable) -> Unit = {},
    onTimestampFailure: (Throwable) -> Unit = {},
): SubmissionCommitResult = AttachmentReferenceMutationCoordinator.withMutation {
    withContext(NonCancellable) {
    if (state.witnessPayload != null) {
        check(insertUserMessageReturningId != null) {
            "schema-8 submissions require the inserted user-message ID"
        }
        check(insertSubmissionWitness != null) {
            "schema-8 submissions require a Room commit witness"
        }
        check(state.witnessPayload.token.length in 1..SubmissionCommitWitnessContract.MAX_TOKEN_CHARS)
        check(state.witnessPayload.draftKey >= 0L)
        check(state.witnessPayload.capturedRawInput.length <= SubmissionCommitWitnessContract.MAX_RAW_INPUT_CHARS)
        check(SubmissionCommitWitnessCodec.encodePaths(state.witnessPayload.committedPaths) != null) {
            "schema-8 submission paths exceed the bounded witness contract"
        }
    }
    if (parentJob != null && !parentJob.isActive) {
        throw CancellationException("Submission was cancelled before the database commit started")
    }

    val originalConversationId = state.conversationId
    try {
        runInTransaction {
            if (parentJob != null && !parentJob.isActive) {
                throw CancellationException("Submission was cancelled before the database transaction began")
            }
            if (state.conversationId == 0L) {
                state.conversationId = createConversation()
                state.conversationWasCreated = true
            }

            val insertedMessageId = if (insertUserMessageReturningId != null) {
                insertUserMessageReturningId.invoke(state.conversationId)
            } else {
                insertUserMessage(state.conversationId)
                0L
            }
            state.userMessageId = insertedMessageId.takeIf { it > 0L }
            state.witnessPayload?.let { payload ->
                check(insertedMessageId > 0L) { "user message ID is required for a schema-8 witness" }
                val committedPathsJson = checkNotNull(SubmissionCommitWitnessCodec.encodePaths(payload.committedPaths))
                insertSubmissionWitness!!.invoke(
                    SubmissionCommitWitnessEntity(
                        token = payload.token,
                        draftKey = payload.draftKey,
                        committedConversationId = state.conversationId,
                        userMessageId = insertedMessageId,
                        capturedRawInput = payload.capturedRawInput,
                        committedPathsJson = committedPathsJson,
                        createdAt = payload.createdAt,
                    )
                )
            }
            if (state.conversationWasCreated) {
                assignCreatedConversation?.invoke(state.conversationId)
            }
        }
        // Mark the message committed only after the transaction itself commits successfully.
        state.messageInserted = true
    } catch (commitFailure: Exception) {
        // The conversation insert and first message insert are one atomic transaction. Do not issue
        // a second delete after rollback: a rolled-back row id may be reused by another insert.
        state.conversationId = originalConversationId
        state.conversationWasCreated = false
        state.messageInserted = false
        state.userMessageId = null
        throw commitFailure
    }

    var publicationFailure: Throwable? = null
    if (state.conversationWasCreated) {
        try {
            publishConversation(state.conversationId)
        } catch (failure: Exception) {
            publicationFailure = failure
            onPublicationFailure(failure)
        }
    }

    // The message is committed at this point. Reconcile the exact captured composer identity
    // regardless of whether publishing the newly created conversation succeeded. The boolean
    // outcome is surfaced so the caller can record durable reconciliation debt for a retry.
    val reconciliationFailed = try {
        !reconcileCommittedDraft()
    } catch (_: Exception) {
        true
    }

    try {
        updateConversationTimestamp(state.conversationId)
    } catch (failure: Exception) {
        onTimestampFailure(failure)
    }

    SubmissionCommitResult(
        conversationId = state.conversationId,
        conversationWasCreated = state.conversationWasCreated,
        userMessageId = state.userMessageId,
        publicationFailure = publicationFailure,
        reconciliationFailed = reconciliationFailed,
    )
    }
}

internal suspend fun <T> installGenerationRequestAndSettleOwner(
    owner: MessageSubmissionOwner,
    getActiveOwner: () -> MessageSubmissionOwner?,
    setActiveOwner: suspend (MessageSubmissionOwner?) -> Unit,
    install: suspend () -> T,
): T {
    return try {
        // Keep installation cancellable. GenerationSessionRegistry owns its own exact lifecycle
        // settlement; shielding start() could install a request after the submitting screen is gone.
        install()
    } finally {
        withContext(NonCancellable) {
            setActiveOwner(getActiveOwner().clearIfMatches(owner))
        }
    }
}

internal data class CommittedDraftReconciliation(
    val shouldClearInput: Boolean,
    val remainingAttachments: List<LocalAttachment>,
    val committedPaths: List<String>,
)

internal fun reconcileCommittedDraft(
    currentInput: String,
    capturedRawInput: String,
    pendingAttachments: List<LocalAttachment>,
    capturedDraftPaths: List<String>,
): CommittedDraftReconciliation {
    val committedPaths = capturedDraftPaths.distinct()
    val committedSet = committedPaths.toSet()
    return CommittedDraftReconciliation(
        shouldClearInput = currentInput == capturedRawInput,
        remainingAttachments = pendingAttachments.filterNot { it.localPath in committedSet },
        committedPaths = committedPaths,
    )
}
