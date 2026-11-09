package com.projectnuke.fusion.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.llm.GenerationTerminationIntent
import com.projectnuke.fusion.llm.GenerationRequestKey
import com.projectnuke.fusion.llm.FusionLifecycleLog
import com.projectnuke.fusion.chat.GenerationSessionRegistry
import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.retrieval.LocalRagSelection
import com.projectnuke.fusion.retrieval.LocalRagSourceEntry
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadata
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.search.appendSearchSourcesMetadata
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingMetadataCodec
import com.projectnuke.fusion.trust.TrustRoutingPresentationMapper

internal data class PersistedResponseVersion<State>(
    val messageId: Long,
    val state: State,
)

/** Exact structural identity shared by the Room witness and SP update. */
internal data class ResponseVersionCommitPlan(
    val targetAssistantMessageId: Long,
    val groupId: Long,
)

/**
 * Canonical production composition for a stored assistant row. The caller
 * supplies frozen execution facts; this function only serializes them. A
 * model/provider response cannot smuggle in a second receipt because any
 * existing receipt is removed before the one authoritative receipt is
 * appended.
 */
internal fun composePersistedAssistantContent(
    answer: String,
    trustRoutingDecision: TrustRoutingDecision,
    webSources: List<WebSearchSource> = emptyList(),
    localRagSelections: List<LocalRagSelection>? = null,
    contextReceipt: AnswerContextReceipt? = null,
): String {
    val withoutReceipt = AnswerContextReceipt.strip(answer)
    val withWebSources = appendSearchSourcesMetadata(withoutReceipt, webSources)
    val withReceipt = contextReceipt?.let { AnswerContextReceipt.append(withWebSources, it) } ?: withWebSources
    val withTrust = TrustRoutingMetadataCodec.append(withReceipt, trustRoutingDecision)
    val selections = localRagSelections.orEmpty()
    if (selections.isEmpty()) return withTrust
    return LocalRagSourceMetadata.append(
        withTrust,
        selections.map { selection ->
            LocalRagSourceEntry(
                ocrDerived = selection.source.ocrDerived,
                fileName = selection.source.displayName,
                attachmentId = selection.source.attachmentId,
                chunkIndex = selection.chunk.chunkIndex,
                startOffset = selection.chunk.startOffset,
                endOffset = selection.chunk.endOffset,
                contentHashHex = selection.source.contentHashHex,
                excerpt = selection.source.excerpt,
                rank = selection.rank,
                lexicalScore = selection.lexicalScore,
                semanticScore = selection.semanticScore,
                pageNumber = selection.chunk.pageNumber,
            )
        },
    )
}

internal sealed interface UserStopPartialDecision {
    data class Eligible(val text: String) : UserStopPartialDecision
    data object Ineligible : UserStopPartialDecision
}

/**
 * Claims one exact response slot for a USER_STOP partial. The controller
 * intent is authoritative; the outcome intent is only an additional typed
 * consistency check when the engine supplied one.
 */
internal fun decideUserStopPartial(
    registry: GenerationSessionRegistry,
    requestKey: GenerationRequestKey,
    outcome: GenerationOutcome.Cancelled,
    fallbackText: String? = null,
    sanitizeOutput: (String) -> String = { it.trim() },
    candidateAlreadySanitized: Boolean = false,
): UserStopPartialDecision {
    if (outcome.terminationIntent != null &&
        outcome.terminationIntent != GenerationTerminationIntent.USER_STOP
    ) {
        return UserStopPartialDecision.Ineligible
    }
    if (registry.cancellationController.intentFor(requestKey) != GenerationTerminationIntent.USER_STOP) {
        return UserStopPartialDecision.Ineligible
    }
    val candidate = fallbackText ?: outcome.partialText
    val text = if (candidateAlreadySanitized) candidate.trim() else sanitizeOutput(candidate)
    if (text.isBlank()) return UserStopPartialDecision.Ineligible
    return if (registry.claimUserStopPartial(requestKey.conversationId, requestKey.requestId)) {
        FusionLifecycleLog.event(
            name = "USER_STOP_PARTIAL_ADOPTION_STARTED",
            conversationId = requestKey.conversationId,
            requestId = requestKey.requestId,
            terminationIntent = GenerationTerminationIntent.USER_STOP,
            registryActive = registry.hasLogicalRequest(requestKey.conversationId),
        )
        UserStopPartialDecision.Eligible(text)
    } else {
        UserStopPartialDecision.Ineligible
    }
}

/**
 * Obtains and adopts the exact request-owned fallback buffer once. The caller
 * supplies the irreversible persistence operation so normal and versioned
 * responses retain their distinct durable semantics.
 */
internal suspend fun adoptUserStopFallback(
    registry: GenerationSessionRegistry,
    requestKey: GenerationRequestKey,
    sanitizeOutput: (String) -> String,
    persist: suspend (String) -> Unit,
): Boolean {
    val raw = registry.cancellationController.partialSnapshot(requestKey) ?: return false
    return when (val decision = decideUserStopPartial(
        registry = registry,
        requestKey = requestKey,
        outcome = GenerationOutcome.Cancelled(
            partialText = raw,
            terminationIntent = GenerationTerminationIntent.USER_STOP,
        ),
        fallbackText = raw,
        sanitizeOutput = sanitizeOutput,
    )) {
        is UserStopPartialDecision.Eligible -> {
            if (!registry.admitAssistantResponsePersistence(requestKey)) return false
            persist(decision.text)
            true
        }
        UserStopPartialDecision.Ineligible -> false
    }
}

internal suspend fun <State> adoptUserStopVersionFallback(
    registry: GenerationSessionRegistry,
    requestKey: GenerationRequestKey,
    sanitizeOutput: (String) -> String,
    loadPreviousState: () -> State,
    buildCommitPlan: (previous: State) -> ResponseVersionCommitPlan,
    insertMessage: suspend (String) -> Long,
    insertResponseWitness: suspend (plan: ResponseVersionCommitPlan, messageId: Long) -> Unit = { _, _ -> },
    runRoomTransaction: (suspend (suspend () -> Unit) -> Unit)? = null,
    buildUpdatedState: (previous: State, plan: ResponseVersionCommitPlan, messageId: Long) -> State,
    saveState: (State) -> Unit,
    restoreState: (State) -> Unit,
    deleteMessage: suspend (messageId: Long) -> Unit,
    deleteWitness: suspend (messageId: Long) -> Boolean = { true },
    updateConversationTimestamp: suspend () -> Unit,
    onTimestampFailure: (Throwable) -> Unit = {},
    onWitnessDeletionFailure: (Throwable) -> Unit = {},
): PersistedResponseVersion<State>? {
    var persisted: PersistedResponseVersion<State>? = null
    adoptUserStopFallback(
        registry = registry,
        requestKey = requestKey,
        sanitizeOutput = sanitizeOutput,
    ) { text ->
        persisted = persistAssistantVersion(
            loadPreviousState = loadPreviousState,
            buildCommitPlan = buildCommitPlan,
            insertMessage = { insertMessage(text) },
            insertResponseWitness = insertResponseWitness,
            runRoomTransaction = runRoomTransaction,
            buildUpdatedState = buildUpdatedState,
            saveState = saveState,
            restoreState = restoreState,
            deleteMessage = deleteMessage,
            deleteWitness = deleteWitness,
            updateConversationTimestamp = updateConversationTimestamp,
            onTimestampFailure = onTimestampFailure,
            onWitnessDeletionFailure = onWitnessDeletionFailure,
            lifecycleRequestKey = requestKey,
        )
    }
    return persisted
}

/**
 * Persists an assistant message as the irreversible result. Conversation ordering metadata is
 * intentionally best-effort: a timestamp failure must never delete a successfully stored answer.
 */
internal suspend fun persistAssistantMessage(
    insertMessage: suspend () -> Long,
    updateConversationTimestamp: suspend () -> Unit,
    onTimestampFailure: (Throwable) -> Unit = {},
    lifecycleRequestKey: GenerationRequestKey? = null,
    beforePersist: suspend () -> Unit = {},
): Long = withContext(NonCancellable + Dispatchers.IO) {
    beforePersist()
    try {
        val messageId = insertMessage()
        try {
            updateConversationTimestamp()
        } catch (failure: Exception) {
            onTimestampFailure(failure)
        }
        messageId
    } finally {
        lifecycleRequestKey?.let { key ->
            FusionLifecycleLog.event(
                name = "USER_STOP_PARTIAL_ADOPTION_FINISHED",
                conversationId = key.conversationId,
                requestId = key.requestId,
                terminationIntent = GenerationTerminationIntent.USER_STOP,
            )
        }
    }
}

/**
 * Persists the deterministic response for a non-generating routing outcome.
 * A null result means this route intentionally has no assistant-side notice.
 */
internal suspend fun persistDeterministicTrustRoutingResponse(
    decision: TrustRoutingDecision,
    generationModeKey: String?,
    insertMessage: suspend (content: String) -> Long,
    updateConversationTimestamp: suspend () -> Unit,
    onTimestampFailure: (Throwable) -> Unit = {},
    contextReceipt: com.projectnuke.fusion.chat.AnswerContextReceipt? = null,
): Long? {
    val presentation = TrustRoutingPresentationMapper.map(decision, generationModeKey)
    val response = presentation.assistantResponse ?: return null
    return persistAssistantMessage(
        insertMessage = {
            insertMessage(
                composePersistedAssistantContent(
                    answer = response,
                    trustRoutingDecision = decision,
                    contextReceipt = contextReceipt,
                ),
            )
        },
        updateConversationTimestamp = updateConversationTimestamp,
        onTimestampFailure = onTimestampFailure,
    )
}

/**
 * Persists a regenerated answer and its version metadata as one logical result. Only failures in
 * the message insert or version-state save roll the result back. Conversation ordering metadata is
 * best-effort and cannot invalidate an otherwise complete response.
 */
internal suspend fun <State> persistAssistantVersion(
    loadPreviousState: () -> State,
    buildCommitPlan: (previous: State) -> ResponseVersionCommitPlan,
    insertMessage: suspend () -> Long,
    insertResponseWitness: suspend (plan: ResponseVersionCommitPlan, messageId: Long) -> Unit = { _, _ -> },
    runRoomTransaction: (suspend (suspend () -> Unit) -> Unit)? = null,
    buildUpdatedState: (previous: State, plan: ResponseVersionCommitPlan, messageId: Long) -> State,
    saveState: (State) -> Unit,
    restoreState: (State) -> Unit,
    deleteMessage: suspend (messageId: Long) -> Unit,
    deleteWitness: suspend (messageId: Long) -> Boolean = { true },
    updateConversationTimestamp: suspend () -> Unit,
    onTimestampFailure: (Throwable) -> Unit = {},
    onWitnessDeletionFailure: (Throwable) -> Unit = {},
    lifecycleRequestKey: GenerationRequestKey? = null,
    conversationId: Long? = lifecycleRequestKey?.conversationId,
    beforePersist: suspend () -> Unit = {},
): PersistedResponseVersion<State> = if (conversationId != null) {
    // Keep the wait for the per-conversation mutex cancellable. Once acquired,
    // the short Room + preference mutation remains NonCancellable so its
    // existing rollback and USER_STOP semantics are preserved.
    ResponseVersionMutationCoordinator.withMutation(conversationId) {
        withContext(NonCancellable + Dispatchers.IO) {
            persistAssistantVersionUnlocked(
                loadPreviousState = loadPreviousState,
                buildCommitPlan = buildCommitPlan,
                insertMessage = insertMessage,
                insertResponseWitness = insertResponseWitness,
                runRoomTransaction = runRoomTransaction,
                buildUpdatedState = buildUpdatedState,
                saveState = saveState,
                restoreState = restoreState,
                deleteMessage = deleteMessage,
                deleteWitness = deleteWitness,
                updateConversationTimestamp = updateConversationTimestamp,
                onTimestampFailure = onTimestampFailure,
                onWitnessDeletionFailure = onWitnessDeletionFailure,
                lifecycleRequestKey = lifecycleRequestKey,
                beforePersist = beforePersist,
            )
        }
    }
} else {
    withContext(NonCancellable + Dispatchers.IO) {
        persistAssistantVersionUnlocked(
            loadPreviousState = loadPreviousState,
            buildCommitPlan = buildCommitPlan,
            insertMessage = insertMessage,
            insertResponseWitness = insertResponseWitness,
            runRoomTransaction = runRoomTransaction,
            buildUpdatedState = buildUpdatedState,
            saveState = saveState,
            restoreState = restoreState,
            deleteMessage = deleteMessage,
            deleteWitness = deleteWitness,
            updateConversationTimestamp = updateConversationTimestamp,
            onTimestampFailure = onTimestampFailure,
            onWitnessDeletionFailure = onWitnessDeletionFailure,
            lifecycleRequestKey = lifecycleRequestKey,
            beforePersist = beforePersist,
        )
    }
}

private suspend fun <State> persistAssistantVersionUnlocked(
    loadPreviousState: () -> State,
    buildCommitPlan: (previous: State) -> ResponseVersionCommitPlan,
    insertMessage: suspend () -> Long,
    insertResponseWitness: suspend (plan: ResponseVersionCommitPlan, messageId: Long) -> Unit,
    runRoomTransaction: (suspend (suspend () -> Unit) -> Unit)?,
    buildUpdatedState: (previous: State, plan: ResponseVersionCommitPlan, messageId: Long) -> State,
    saveState: (State) -> Unit,
    restoreState: (State) -> Unit,
    deleteMessage: suspend (messageId: Long) -> Unit,
    deleteWitness: suspend (messageId: Long) -> Boolean,
    updateConversationTimestamp: suspend () -> Unit,
    onTimestampFailure: (Throwable) -> Unit,
    onWitnessDeletionFailure: (Throwable) -> Unit,
    lifecycleRequestKey: GenerationRequestKey?,
    beforePersist: suspend () -> Unit,
): PersistedResponseVersion<State> {
    beforePersist()
    val previousState = loadPreviousState()
    val commitPlan = buildCommitPlan(previousState)
    var insertedMessageId: Long? = null
    var stateSaveAttempted = false

    return try {
        var messageId = 0L
        val roomCommit: suspend () -> Unit = {
            messageId = insertMessage()
            insertedMessageId = messageId
            insertResponseWitness(commitPlan, messageId)
        }
        if (runRoomTransaction != null) {
            runRoomTransaction.invoke(roomCommit)
        } else {
            roomCommit()
        }
        val updatedState = buildUpdatedState(previousState, commitPlan, messageId)
        stateSaveAttempted = true
        saveState(updatedState)

        try {
            updateConversationTimestamp()
        } catch (failure: Exception) {
            onTimestampFailure(failure)
        }

        try {
            val witnessDeleted = deleteWitness(messageId)
            if (!witnessDeleted) {
                onWitnessDeletionFailure(IllegalStateException("Response-version witness deletion did not affect message $messageId"))
            }
        } catch (failure: Exception) {
            // The assistant row and SP state are already durable. Leaving
            // the witness is safe because startup recovery is idempotent.
            // Fatal Errors are NOT caught: post-commit cleanup failure must
            // propagate instead of being reported as a bounded warning.
            onWitnessDeletionFailure(failure)
        }

        PersistedResponseVersion(messageId = messageId, state = updatedState)
    } catch (failure: Exception) {
        insertedMessageId?.let { messageId ->
            try {
                deleteMessage(messageId)
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
        }
        if (stateSaveAttempted) {
            try {
                restoreState(previousState)
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
        }
        throw failure
    } finally {
        lifecycleRequestKey?.let { key ->
            FusionLifecycleLog.event(
                name = "USER_STOP_PARTIAL_ADOPTION_FINISHED",
                conversationId = key.conversationId,
                requestId = key.requestId,
                terminationIntent = GenerationTerminationIntent.USER_STOP,
            )
        }
    }
}
