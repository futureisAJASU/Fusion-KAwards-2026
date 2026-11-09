package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason

// C2-D explicit cloud-escalation consent.
//
// This is the pure, host-testable policy layer. Compose only renders the
// resulting offers and pending state and emits explicit user actions (tap,
// confirm, cancel). It never re-derives provider, privacy, or attachment
// policy inline.
internal sealed interface CloudEscalationOffer {
    // No escalation surface at all: LOCAL/SEARCH/ABSTAIN, or external mode.
    data object Hidden : CloudEscalationOffer

    // The optional escalation action may be offered to the user.
    data class Shown(val label: String) : CloudEscalationOffer

    // Escalation is deterministically unavailable; show bounded explanation.
    data class Blocked(val notice: String) : CloudEscalationOffer
}

/**
 * Phase 11 provider selection for escalation consent. Never silently hops
 * providers after consent:
 * - a runnable selected provider is used/frozen;
 * - a selected-but-unusable provider refuses with settings guidance;
 * - a genuinely absent selection follows the existing explicit default
 *   (first runnable), if any.
 */
internal sealed interface CloudProviderPick {
    data class Use(val provider: com.projectnuke.fusion.ai.model.AiProviderConfig) : CloudProviderPick
    data object SelectedUnusable : CloudProviderPick
    data object NoneAvailable : CloudProviderPick
}

internal fun pickEscalationProvider(
    selectedId: String?,
    providers: List<com.projectnuke.fusion.ai.model.AiProviderConfig>,
    isRunnable: (com.projectnuke.fusion.ai.model.AiProviderConfig) -> Boolean,
): CloudProviderPick {
    if (selectedId != null && providers.none { it.id == selectedId }) {
        return CloudProviderPick.SelectedUnusable
    }
    val selected = selectedId?.let { id -> providers.firstOrNull { it.id == id } }
    if (selected != null) {
        return if (isRunnable(selected)) {
            CloudProviderPick.Use(selected)
        } else {
            CloudProviderPick.SelectedUnusable
        }
    }
    return providers.firstOrNull(isRunnable)?.let { CloudProviderPick.Use(it) }
        ?: CloudProviderPick.NoneAvailable
}

internal object CloudEscalationPolicy {
    // Decides whether an optional cloud-escalation action may be shown for a
    // completed assistant message. Core rule: nothing may leave the device
    // until the user explicitly confirms the specific escalation; never show
    // an automatic switch, and never climb over the trust policy.
    fun offer(
        decision: TrustRoutingDecision?,
        generationModeKey: String?,
        providerAvailable: Boolean,
        hasAttachments: Boolean,
        actionLabel: String?,
        // Phase 7 (conservative): no dedicated private-history consent UX
        // exists yet, so escalation is unavailable when its history would
        // contain private-derived context.
        historyTainted: Boolean = false,
        // Unified execution-profile presentation gate. False suppresses the
        // offer surface entirely (Hidden) even for CLOUD_SUGGEST; true keeps
        // the existing policy below. Presentation permission only: never
        // provider-call authorization, and explicit confirmation + DataEgress
        // authorization remain mandatory.
        allowCloudEscalationOffer: Boolean = true,
    ): CloudEscalationOffer {
        // Only CLOUD_SUGGEST may offer escalation. ABSTAIN is terminal for the
        // current route; LOCAL/SEARCH stay quiet. This also covers unknown data.
        if (decision?.route != TrustRoute.CLOUD_SUGGEST) return CloudEscalationOffer.Hidden

        // The active execution profile does not present cloud escalation
        // (LOCAL stays local; EXTERNAL is already external).
        if (!allowCloudEscalationOffer) return CloudEscalationOffer.Hidden

        // The user is already using an external provider; never re-offer.
        if (generationModeKey == EXTERNAL_AI_API_MODE) return CloudEscalationOffer.Hidden

        // Attachment transmission policy belongs to C5. Never upload a local
        // attachment merely because CLOUD_SUGGEST fired. Keep C2-D conservative.
        if (hasAttachments) {
            return CloudEscalationOffer.Blocked("첨부 파일이 포함된 요청은 외부 AI로 전환할 수 없어요.")
        }

        if (historyTainted) {
            return CloudEscalationOffer.Blocked("로컬 문서에서 가져온 이전 맥락은 외부 AI에 보내지 않았어요.")
        }

        // No usable/configured external provider: no network call, no silent
        // provider pick, only a bounded user-facing message.
        if (!providerAvailable) {
            return CloudEscalationOffer.Blocked("외부 AI 설정이 필요해요.")
        }

        return CloudEscalationOffer.Shown(actionLabel ?: FALLBACK_ACTION_LABEL)
    }

    // Bounded, deterministic dialog title. Never exposes keys or credentials.
    fun title(providerDisplayName: String?): String = "외부 AI 모델로 다시 요청할까요?"

    // Confirmation copy. The data-scope sentence mirrors what the existing
    // external-generation path (ExternalAiChatRunner + FinalPromptAssembler +
    // ChatScreen external retry) actually transmits: the request text, related
    // conversation history, and stored memory / conversation-summary context.
    fun consentBody(providerDisplayName: String?): String {
        val providerLine = providerDisplayName
            ?.takeIf { it.isNotBlank() }
            ?.let { "\n\n외부 AI 제공자: $it" }
            .orEmpty()
        return "이 전환은 이번 답변만 더 강한 외부 AI 모델로 다시 요청해요.\n\n" +
            "요청 내용과 관련된 대화 기록(저장된 기억·요약을 포함한 맥락)이 " +
            "외부 AI 제공자에게 전송됩니다." + providerLine +
            "\n\n이 동의는 이번 전환 하나에만 적용돼요. 취소하면 아무 데이터도 전송되지 않아요."
    }

    private const val EXTERNAL_AI_API_MODE = "EXTERNAL_AI_API"
    private const val FALLBACK_ACTION_LABEL = "더 강한 모델로 다시 요청"
}

// Pure representation of the user's explicit decision for one escalation.
internal enum class CloudEscalationConsentDecision {
    CONFIRM,
    CANCEL,
}

// Immutable request snapshot frozen when the user taps the escalation action,
// before any confirmation dialog is shown. Execution after confirmation MUST
// use only the frozen fields; it must never re-read mutable UI selections to
// silently change the provider/model/context for the already-approved request.
internal data class PendingCloudEscalation(
    val conversationId: Long,
    val targetAssistantMessageId: Long,
    val userMessageId: Long,
    val versionGroupId: Long,
    val providerId: String,
    val providerDisplayName: String?,
    // Fully frozen external-generation request for the approved escalation.
    val escalatedRequest: GenerationRequestSnapshot,
    // Frozen conversation messages captured before the dialog was shown.
    val fullMessageEntities: List<MessageEntity>,
    // Frozen response-version state so execution targets the approved branch.
    val capturedResponseVersionState: ResponseVersionState,
    // Explicitly frozen trust + attachment status (C2-D 1.5/1.7). The
    // escalated request already carries a copy, but the top-level binding
    // makes ownership audits trivial and prevents silent substitution.
    val originDecision: TrustRoutingDecision = escalatedRequest.trustRoutingDecision,
    val hadAttachmentsAtOfferTime: Boolean = false,
)

// Bounds for pending cloud work. Only one escalation may be in flight at a
// time (single-flight gate below); the queue beyond that is zero.
internal object CloudEscalationBounds {
    const val MAX_PENDING_CLOUD_ESCALATIONS = 1
    const val MAX_PROVIDER_DISPLAY_NAME_CHARS = 64
}

// Live authoritative state for confirmation-time validation. This is loaded
// fresh from durable storage at confirm/execute/persist time; the frozen
// dialog-opening message list is NEVER used as current truth.
internal data class CloudEscalationLiveState(
    val conversationExists: Boolean,
    val activeMessages: List<MessageEntity>,
    val versions: ResponseVersionState,
)

internal enum class CloudEscalationStaleness {
    CONVERSATION_GONE,
    TARGET_DELETED,
    ORIGIN_DELETED,
    GROUP_MOVED,
    REQUEST_MISMATCH,
}

internal sealed interface CloudEscalationLiveVerdict {
    data object Valid : CloudEscalationLiveVerdict
    data class Stale(val reason: CloudEscalationStaleness) : CloudEscalationLiveVerdict
}

/**
 * Confirmation-time live validator. The pending escalation still refers to
 * the same conversation, user turn, assistant target, response-version
 * group, and approved provider/request snapshot — checked against LIVE
 * durable state, never against the frozen dialog-opening list.
 *
 * Navigation to another conversation alone does not invalidate: the check
 * runs against the frozen conversation's durable target. Deletion or
 * version/group mutation does invalidate.
 */
internal fun validateEscalationLive(
    pending: PendingCloudEscalation,
    live: CloudEscalationLiveState,
): CloudEscalationLiveVerdict {
    if (!live.conversationExists) return CloudEscalationLiveVerdict.Stale(CloudEscalationStaleness.CONVERSATION_GONE)
    if (pending.conversationId <= 0L) return CloudEscalationLiveVerdict.Stale(CloudEscalationStaleness.REQUEST_MISMATCH)
    if (pending.targetAssistantMessageId <= 0L) return CloudEscalationLiveVerdict.Stale(CloudEscalationStaleness.REQUEST_MISMATCH)
    if (pending.userMessageId <= 0L) return CloudEscalationLiveVerdict.Stale(CloudEscalationStaleness.REQUEST_MISMATCH)
    if (pending.providerId.isBlank()) return CloudEscalationLiveVerdict.Stale(CloudEscalationStaleness.REQUEST_MISMATCH)
    val target = live.activeMessages.firstOrNull { it.id == pending.targetAssistantMessageId }
    if (target == null || target.role != "assistant") {
        return CloudEscalationLiveVerdict.Stale(CloudEscalationStaleness.TARGET_DELETED)
    }
    val origin = live.activeMessages.firstOrNull { it.id == pending.userMessageId }
    if (origin == null || origin.role != "user") {
        return CloudEscalationLiveVerdict.Stale(CloudEscalationStaleness.ORIGIN_DELETED)
    }
    // The response-version/group relationship must still hold live. A version
    // switch that keeps group membership is fine; regrouping is stale.
    val liveGroup = live.versions.groupByMessageId[pending.targetAssistantMessageId]
        ?: -pending.targetAssistantMessageId
    if (liveGroup != pending.versionGroupId) {
        return CloudEscalationLiveVerdict.Stale(CloudEscalationStaleness.GROUP_MOVED)
    }
    // The frozen request must still describe this conversation/provider.
    if (pending.escalatedRequest.conversationId != pending.conversationId) {
        return CloudEscalationLiveVerdict.Stale(CloudEscalationStaleness.REQUEST_MISMATCH)
    }
    if (pending.escalatedRequest.externalProviderId != pending.providerId) {
        return CloudEscalationLiveVerdict.Stale(CloudEscalationStaleness.REQUEST_MISMATCH)
    }
    if (pending.escalatedRequest.generationModeKey != EXTERNAL_AI_API_MODE_FROZEN) {
        return CloudEscalationLiveVerdict.Stale(CloudEscalationStaleness.REQUEST_MISMATCH)
    }
    // Attachment-bearing escalations are never valid in C2-D.
    if (pending.hadAttachmentsAtOfferTime) return CloudEscalationLiveVerdict.Stale(CloudEscalationStaleness.REQUEST_MISMATCH)
    if (pending.escalatedRequest.attachmentIds.isNotEmpty()) return CloudEscalationLiveVerdict.Stale(CloudEscalationStaleness.REQUEST_MISMATCH)
    if (pending.escalatedRequest.multimodalImagePaths.isNotEmpty()) return CloudEscalationLiveVerdict.Stale(CloudEscalationStaleness.REQUEST_MISMATCH)
    return CloudEscalationLiveVerdict.Valid
}
internal fun validatePendingEscalation(
    pending: PendingCloudEscalation,
    currentConversationId: Long,
    activeMessages: List<MessageEntity>,
): Boolean {
    if (currentConversationId != pending.conversationId) return false
    if (pending.conversationId <= 0L) return false
    if (pending.targetAssistantMessageId <= 0L) return false
    if (pending.userMessageId <= 0L) return false
    if (pending.providerId.isBlank()) return false
    // Both endpoints must still exist on the active branch.
    val hasTarget = activeMessages.any { it.id == pending.targetAssistantMessageId }
    if (!hasTarget) return false
    val hasOrigin = activeMessages.any { it.id == pending.userMessageId && it.role == "user" }
    if (!hasOrigin) return false
    // The frozen request must still describe this conversation/provider.
    if (pending.escalatedRequest.conversationId != pending.conversationId) return false
    if (pending.escalatedRequest.externalProviderId != pending.providerId) return false
    if (pending.escalatedRequest.generationModeKey != EXTERNAL_AI_API_MODE_FROZEN) return false
    // Attachment-bearing escalations are never valid in C2-D.
    if (pending.hadAttachmentsAtOfferTime) return false
    if (pending.escalatedRequest.attachmentIds.isNotEmpty()) return false
    if (pending.escalatedRequest.multimodalImagePaths.isNotEmpty()) return false
    return true
}

private const val EXTERNAL_AI_API_MODE_FROZEN = "EXTERNAL_AI_API"

// Resolves the exact request to execute after explicit confirmation. The
// current UI selections are intentionally ignored: provider/model switches
// made while the dialog was open must not retarget the approved request.
internal fun resolveEscalationExecutionRequest(
    pending: PendingCloudEscalation,
): GenerationRequestSnapshot = pending.escalatedRequest

// Cloud provenance: a successful escalation is a capability escalation, never
// a verification verdict. Labels must never claim verified/more-accurate.
internal fun cloudEscalationProvenanceLabel(providerDisplayName: String?): String {
    val safeName = providerDisplayName?.takeIf { it.isNotBlank() }
        ?.take(CloudEscalationBounds.MAX_PROVIDER_DISPLAY_NAME_CHARS)
        ?.trim()
    return if (safeName.isNullOrEmpty()) {
        "외부 AI로 생성된 대체 답변이에요."
    } else {
        "외부 AI($safeName)로 생성된 대체 답변이에요."
    }
}

internal val FORBIDDEN_CLOUD_PROVENANCE_CLAIMS = listOf(
    "검증",
    "verified",
    "fact checked",
    "더 정확",
    "more accurate",
    "more truthful",
    "사실 확인됨",
)

internal fun isCloudProvenanceLabelSafe(label: String): Boolean =
    FORBIDDEN_CLOUD_PROVENANCE_CLAIMS.none { label.contains(it, ignoreCase = true) }

// Escalation never mutates the persisted default generation mode. The caller
// keeps its own mode; this documents the invariant in a testable seam.
internal fun defaultModeAfterEscalation(currentModeKey: String): String = currentModeKey
// Single-flight guard for the escalated external attempt. At most one confirm
// may pass while the guard is held; every further confirm before release is
// rejected, so a rapid double-confirm can never produce a second external call.
internal class CloudEscalationExecutionGate {
    private val held = java.util.concurrent.atomic.AtomicBoolean(false)

    // True only for the first in-flight acquisition; false for every concurrent one.
    fun tryEnter(): Boolean = held.compareAndSet(false, true)
    fun release() { held.set(false) }
}

// Finds the user message that originated [targetMessageId] on the active
// branch. Prefers the response-version group's message id; otherwise returns
// the nearest preceding user message (for older, ungrouped messages).
internal fun findOriginUserMessage(
    activeMessages: List<MessageEntity>,
    targetMessageId: Long,
    groupId: Long,
): MessageEntity? {
    val byGroup = activeMessages.firstOrNull { it.id == groupId && it.role == "user" }
    if (byGroup != null) return byGroup
    val targetIndex = activeMessages.indexOfFirst { it.id == targetMessageId }
    if (targetIndex <= 0) return null
    for (i in targetIndex - 1 downTo 0) {
        if (activeMessages[i].role == "user") return activeMessages[i]
    }
    return null
}

// Builds a CLOUD_SUGGEST decision for escalations when stored metadata is
// unavailable; the route/reason semantics never change for this request.
internal fun fallbackCloudSuggestionDecision(): TrustRoutingDecision = TrustRoutingDecision(
    route = TrustRoute.CLOUD_SUGGEST,
    reason = TrustRoutingReason.HIGH_COMPLEXITY_OPTIONAL_CLOUD,
    signals = emptySet(),
)

// Freezes a fully resolved, immutable external-generation request for one
// approved escalation. Every field that must not silently change after the
// user confirms is captured here and only here.
internal fun buildCloudEscalationSnapshot(
    conversationId: Long,
    requestId: String,
    providerId: String,
    providerRequestSnapshot: ExternalProviderRequestSnapshot,
    generationSettings: GenerationSettings,
    reasoningEnabled: Boolean,
    promptLabInstruction: String,
    history: List<ChatMessage>,
    historyMessageIds: List<Long>,
    promptText: String,
    rawUserText: String,
    createdAt: Long,
    trustRoutingDecision: TrustRoutingDecision,
    interactionMode: com.projectnuke.fusion.chat.ChatInteractionMode =
        com.projectnuke.fusion.chat.ChatInteractionMode.NORMAL,
): GenerationRequestSnapshot = GenerationRequestSnapshot(
    requestId = requestId,
    conversationId = conversationId,
    generationModeKey = ChatGenerationMode.EXTERNAL_AI_API.name,
    selectedModelId = null,
    selectedModelPath = null,
    selectedModelStableId = null,
    modelFamily = null,
    settings = generationSettings.copy(speculativeDecodingEnabled = false),
    reasoningEnabled = reasoningEnabled,
    webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
    attachmentIds = emptyList(),
    multimodalImagePaths = emptyList(),
    promptText = promptText,
    rawUserText = rawUserText,
    createdAt = createdAt,
    history = history,
    historyMessageIds = historyMessageIds,
    promptLabInstruction = promptLabInstruction,
    externalProviderId = providerId,
    externalProviderSelectionFrozen = true,
    externalMaxOutputTokens = providerRequestSnapshot.maxTokens,
    externalProviderRequest = providerRequestSnapshot,
    trustRoutingDecision = trustRoutingDecision,
    interactionMode = interactionMode,
    // Diagnostics only: an approved escalation always executes the explicit
    // external path. The frozen fields above stay authoritative.
    executionProfileKey = ChatExecutionProfile.EXTERNAL_AI.name,
)