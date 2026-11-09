package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ai.ExternalAiChatResult
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.trust.TrustExecutionFailure
import com.projectnuke.fusion.trust.TrustFailurePolicy
import com.projectnuke.fusion.trust.TrustRetryGate
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingPresentationMapper

// C2-D production coordinator: the only place that turns frozen consent into
// an executed external attempt and a persisted alternate response version.
//
// Production caller: ChatScreen (CLOUD_SUGGEST suggested action -> freeze ->
// consent dialog -> confirm). The executor seam binds to the same production
// external provider runner already used for explicit EXTERNAL_AI_API mode
// runs; host tests inject fake transports behind real runner construction.
internal sealed interface CloudEscalationExecutionResult {
    data class Success(
        val content: String,
        val providerDisplayName: String?,
        val providerId: String,
        val reasoningContent: String? = null,
    ) : CloudEscalationExecutionResult

    data class Failure(
        val failure: TrustExecutionFailure.CloudEscalationFailed,
    ) : CloudEscalationExecutionResult
}

// Production external execution seam used by both ChatScreen and integration
// tests. The production implementation delegates to the existing
// ExternalAiChatRunner path (explicit EXTERNAL_AI_API runner).
internal fun interface CloudEscalationExecutor {
    suspend fun execute(pending: PendingCloudEscalation): ExternalAiChatResult
}

internal class CloudEscalationCoordinator(
    private val executor: CloudEscalationExecutor,
    val executionGate: CloudEscalationExecutionGate = CloudEscalationExecutionGate(),
    private val retryGate: TrustRetryGate = TrustRetryGate(),
) {
    // Bounded pending state: at most one frozen escalation in flight
    // (CloudEscalationBounds.MAX_PENDING_CLOUD_ESCALATIONS = 1).
    private var currentPending: PendingCloudEscalation? = null
    private var lastFailure: TrustExecutionFailure.CloudEscalationFailed? = null

    fun offerFor(
        decision: TrustRoutingDecision?,
        generationModeKey: String?,
        providerAvailable: Boolean,
        hasAttachments: Boolean,
        historyTainted: Boolean = false,
        // Unified execution-profile presentation gate. False suppresses the
        // offer even for CLOUD_SUGGEST; never authorizes a provider call.
        allowCloudEscalationOffer: Boolean = true,
    ): CloudEscalationOffer = CloudEscalationPolicy.offer(
        decision = decision,
        generationModeKey = generationModeKey,
        providerAvailable = providerAvailable,
        hasAttachments = hasAttachments,
        actionLabel = TrustRoutingPresentationMapper.CLOUD_SUGGEST_ACTION_LABEL,
        historyTainted = historyTainted,
        allowCloudEscalationOffer = allowCloudEscalationOffer,
    )

    // Freezes the pending escalation exactly once, before the consent dialog
    // is shown. No external call, no state mutation beyond the frozen value.
    fun freezePending(pending: PendingCloudEscalation): Boolean {
        currentPending = pending
        lastFailure = null
        return true
    }

    // Cancel: closes the dialog, 0 external calls, local answer unchanged.
    fun cancel() {
        currentPending = null
        lastFailure = null
    }

    fun lastFailureOrDefault(): TrustExecutionFailure.CloudEscalationFailed =
        lastFailure ?: cloudFailed(currentPending)

    /**
     * Explicit confirm. Validates the frozen pending request against LIVE
     * durable state (phase 1a), holds the single-flight gate, and performs
     * exactly one external generation attempt through the production
     * executor. A failed or stale attempt returns a typed
     * CloudEscalationFailed state; the original local answer is untouched
     * and no provider is switched automatically.
     *
     * The [loadLive] lambda must load authoritative state for the FROZEN
     * conversation (Room branch + durable versions), never the frozen
     * dialog-opening list and never only the currently open conversation.
     */
    suspend fun confirm(
        pending: PendingCloudEscalation,
        loadLive: suspend () -> CloudEscalationLiveState,
    ): CloudEscalationExecutionResult {
        currentPending = pending
        if (validateEscalationLive(pending, loadLive()) !is CloudEscalationLiveVerdict.Valid) {
            currentPending = null
            lastFailure = cloudFailed(pending)
            return CloudEscalationExecutionResult.Failure(lastFailure!!)
        }
        // Single-flight: a rapid double-confirm can never produce a second
        // external call while the first attempt is still in flight. Each
        // release is paired only with an acquisition made by this invocation;
        // a failed contender must never release another confirmation's gate.
        val ownsExecutionGate = executionGate.tryEnter()
        if (!ownsExecutionGate) {
            lastFailure = cloudFailed(pending)
            return CloudEscalationExecutionResult.Failure(lastFailure!!)
        }
        val ownsRetryGate = retryGate.tryEnter()
        if (!ownsRetryGate) {
            executionGate.release()
            lastFailure = cloudFailed(pending)
            return CloudEscalationExecutionResult.Failure(lastFailure!!)
        }
        return try {
            // The frozen request is authoritative; UI selections are ignored.
            check(resolveEscalationExecutionRequest(pending).requestId == pending.escalatedRequest.requestId)
            when (val result = executor.execute(pending)) {
                is ExternalAiChatResult.Success -> {
                    lastFailure = null
                    CloudEscalationExecutionResult.Success(
                        content = result.content,
                        providerDisplayName = result.providerDisplayName,
                        providerId = pending.providerId,
                        reasoningContent = result.reasoningContent,
                    )
                }
                is ExternalAiChatResult.Truncated -> {
                    val content = presentTruncatedExternalAnswer(result.content)
                    if (content == null) {
                        CloudEscalationExecutionResult.Failure(
                            cloudFailed(pending).also { lastFailure = it },
                        )
                    } else {
                        lastFailure = null
                        CloudEscalationExecutionResult.Success(
                            content = content,
                            providerDisplayName = result.providerDisplayName,
                            providerId = pending.providerId,
                            reasoningContent = result.reasoningContent,
                        )
                    }
                }
                is ExternalAiChatResult.Empty,
                is ExternalAiChatResult.NoProvider,
                is ExternalAiChatResult.BlockedAttachment,
                is ExternalAiChatResult.Error -> CloudEscalationExecutionResult.Failure(
                    cloudFailed(pending).also { lastFailure = it },
                )
            }
        } finally {
            if (ownsExecutionGate) executionGate.release()
            if (ownsRetryGate) retryGate.release()
        }
    }

    /**
     * Persists the escalated external answer as an alternate response version
     * of the SAME originating user request. The original local answer stays
     * in the same version group; the user turn is never duplicated.
     *
     * Phase 1b: the live state is re-validated AFTER the external result
     * returned and BEFORE persistence. If the target/user/version was
     * deleted or invalidated while the provider call was in flight, the
     * completion is discarded (null) — no attachment to another branch, no
     * stray assistant message, surviving state untouched.
     */
    internal suspend fun persistEscalatedResponse(
        pending: PendingCloudEscalation,
        escalatedContent: String,
        loadLive: suspend () -> CloudEscalationLiveState,
        loadPreviousState: () -> ResponseVersionState,
        insertMessage: suspend (String) -> Long,
        insertResponseWitness: suspend (ResponseVersionCommitPlan, Long) -> Unit = { _, _ -> },
        runRoomTransaction: (suspend (suspend () -> Unit) -> Unit)? = null,
        saveState: (ResponseVersionState) -> Unit,
        restoreState: (ResponseVersionState) -> Unit,
        deleteMessage: suspend (Long) -> Unit,
        deleteWitness: suspend (Long) -> Boolean = { true },
        updateConversationTimestamp: suspend () -> Unit,
        onTimestampFailure: (Throwable) -> Unit = {},
    ): PersistedResponseVersion<ResponseVersionState>? {
        if (validateEscalationLive(pending, loadLive()) !is CloudEscalationLiveVerdict.Valid) {
            return null
        }
        return try {
            persistAssistantVersion(
                conversationId = pending.conversationId,
                beforePersist = {
                    if (validateEscalationLive(pending, loadLive()) !is CloudEscalationLiveVerdict.Valid) {
                        throw ResponseVersionMutationRejectedException()
                    }
                },
                loadPreviousState = loadPreviousState,
                buildCommitPlan = { previous ->
                    ResponseVersionCommitPlan(
                        targetAssistantMessageId = pending.targetAssistantMessageId,
                        groupId = previous.groupByMessageId[pending.targetAssistantMessageId]
                            ?: -pending.targetAssistantMessageId,
                    )
                },
                insertMessage = { insertMessage(escalatedContent) },
                insertResponseWitness = insertResponseWitness,
                runRoomTransaction = runRoomTransaction,
                buildUpdatedState = { previous, plan, messageId ->
                    val groupId = plan.groupId
                    previous.copy(
                        groupByMessageId = previous.groupByMessageId +
                            (pending.targetAssistantMessageId to groupId) +
                            (messageId to groupId),
                        activeMessageIdByGroup = previous.activeMessageIdByGroup + (groupId to messageId),
                    )
                },
                saveState = saveState,
                restoreState = restoreState,
                deleteMessage = deleteMessage,
                deleteWitness = deleteWitness,
                updateConversationTimestamp = updateConversationTimestamp,
                onTimestampFailure = onTimestampFailure,
            )
        } catch (_: ResponseVersionMutationRejectedException) {
            null
        }
    }

    private fun pendingKey(pending: PendingCloudEscalation?): String =
        pending?.let { "${it.conversationId}#${it.targetAssistantMessageId}" } ?: "no-pending"

    private fun cloudFailed(pending: PendingCloudEscalation?): TrustExecutionFailure.CloudEscalationFailed =
        TrustFailurePolicy.cloudFailureAsExecutionFailure(pendingKey(pending))
            as TrustExecutionFailure.CloudEscalationFailed
}
