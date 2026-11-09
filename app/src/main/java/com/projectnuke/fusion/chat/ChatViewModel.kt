package com.projectnuke.fusion.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.createSavedStateHandle
import android.content.Context
import com.projectnuke.fusion.data.SubmissionCommitWitnessContract
import com.projectnuke.fusion.llm.*
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.util.AttachmentReferenceMutationCoordinator
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun fallbackEventCodesForDiagnostics(events: List<RuntimeFallbackEvent>): List<String> =
    events.map { it.reason.name }

internal fun visionWasRequestedForDiagnostics(multimodalImagePaths: List<String>): Boolean =
    multimodalImagePaths.isNotEmpty()

data class RuntimeDiagnostics(
    val requestedConfig: RequestedConfig,
    val attemptedBackend: String?,
    val selectedBackend: String?,
    val selectedVisionBackend: String? = null,
    val mtpRequested: Boolean,
    val mtpRuntimeStatus: MtpRuntimeStatus,
    val fallbackEventCodes: List<String> = emptyList(),
    val attemptSnapshot: RuntimeAttemptSnapshot?,
    val failureSnapshot: RuntimeFailureSnapshot?,
    val requestedOutputTokens: Int? = null,
    val appliedOutputTokens: Int? = null,
    val outputAdjustmentReason: String? = null,
    val contextMode: String? = null,
    val effectiveKvCapacityTokens: Int? = null,
    val compactEnabled: Boolean? = null,
    val compactConsidered: Boolean? = null,
    val compactUsed: Boolean? = null,
    val fallbackTrimmed: Boolean? = null,
)

internal enum class AttachmentImportSettlementResult {
    SETTLED,
    ALREADY_SETTLED,
    RETRY_REQUIRED,
}

data class RequestedConfig(
    val modelId: String,
    val accelerator: AcceleratorMode,
    val kvCacheCapacityTokens: Int,
    val mtpRequested: Boolean,
    val visionRequested: Boolean,
    val speculativeDecodingEnabled: Boolean,
) {
    /** Compatibility alias for callers that still use the old wording. */
    val enableVisionBackend: Boolean get() = visionRequested
}

data class ConversationGenerationState(
    val isGenerating: Boolean = false,
    val isStopping: Boolean = false,
    val activeRequestId: String? = null,
    val streamingText: String? = null,
    val streamingReasoningTags: Set<String> = emptySet(),
    val streamingMetricsLine: String? = null,
    val generationStatus: String? = null,
    val regeneratingMessageId: Long? = null,
    val extractingMemoryCandidates: Boolean = false,
    val actualWebSearchUsed: Boolean = false,
    /**
     * Frozen in-memory routing outcome for the current/last request. This is
     * intentionally not persisted; process death clears it and the next send
     * captures a fresh decision before generation.
     */
    val trustRoutingDecision: TrustRoutingDecision? = null,
    /** Last runtime diagnostics preserved after request finalization (success or failure). */
    val lastRuntimeDiagnostics: RuntimeDiagnostics? = null,
    val researchNotice: String? = null,
    /** Request-owned display identity; survives a fresh conversation's UI remount. */
    val autoSelectionExplanation: String? = null,
) {
    /** Returns a copy preserving lastRuntimeDiagnostics while clearing transient streaming fields. */
    fun finished(): ConversationGenerationState = copy(
        isGenerating = false,
        isStopping = false,
        activeRequestId = null,
        streamingText = null,
        streamingReasoningTags = emptySet(),
        streamingMetricsLine = null,
        generationStatus = null,
        regeneratingMessageId = null,
        extractingMemoryCandidates = false,
    )
}

data class PendingAttachmentIdentity(
    val name: String,
    val mimeType: String,
    val localPath: String,
)

enum class ComposerImportStatus {
    PICKER_OPEN,
    COPYING,
}

data class ComposerImportOwnership(
    val token: String,
    val status: ComposerImportStatus,
)

data class ComposerDraftState(
    val rawInput: String = "",
    val pendingAttachments: List<PendingAttachmentIdentity> = emptyList(),
    val version: Long = 0L,
    val importOwnership: ComposerImportOwnership? = null,
    val activeSubmissionToken: String? = null,
    val lastExternalHandoffId: String? = null,
    val lastExternalHandoffPartialNotice: String? = null,
)

data class ComposerSubmissionSnapshot(
    val token: String,
    val conversationId: Long,
    val rawInput: String,
    val pendingAttachments: List<PendingAttachmentIdentity>,
    val version: Long,
)

internal enum class SubmissionRejectionReason {
    MANUAL_COMPACTION_LEASE_HELD,
    EXISTING_SUBMISSION_OWNER,
    IMPORT_OWNER_ACTIVE,
    CONVERSATION_DELETION_IN_PROGRESS,
    DRAFT_PERSISTENCE_REJECTED,
    DRAFT_STORE_UNREADABLE,
}

internal enum class FreshNewChatFailure {
    CANCELLATION_FAILED,
    DRAFT_UNAVAILABLE,
    DRAFT_CLEAR_FAILED,
    INVALID_SEED_PROJECT,
    INVALID_SEED_PROMPT,
}

internal data class FreshNewChatResult(
    val success: Boolean,
    val epoch: Long,
    val pendingAttachmentPaths: List<String> = emptyList(),
    val failure: FreshNewChatFailure? = null,
)

internal sealed interface SubmissionAdmissionResult {
    data class Accepted(val snapshot: ComposerSubmissionSnapshot) : SubmissionAdmissionResult
    data class Rejected(val reason: SubmissionRejectionReason) : SubmissionAdmissionResult
}

internal enum class AttachmentImportRejection {
    DRAFT_UNAVAILABLE,
    EXISTING_SUBMISSION,
    EXISTING_IMPORT,
    MANUAL_COMPACTION,
    CONVERSATION_DELETION,
    OWNER_PERSISTENCE_REJECTED,
}

internal sealed interface AttachmentImportAdmission {
    data class Accepted(val owner: ComposerImportOwnership) : AttachmentImportAdmission
    data class Rejected(val reason: AttachmentImportRejection) : AttachmentImportAdmission
}

internal enum class RequestStateFinishResult {
    APPLIED,
    STALE_OWNER,
    ALREADY_FINISHED,
}

class ChatViewModel internal constructor(
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    context: Context? = null,
    private val draftStoreOverride: PersistentComposerDraftStore? = null,
) : ViewModel() {
    val scope: CoroutineScope = viewModelScope
    val registry = GenerationSessionRegistry(onResearchDeadline = { request ->
        update(request.conversationId) { current ->
            if (current.activeRequestId == null || current.activeRequestId == request.requestId)
                current.copy(researchNotice = "조사 제한 시간이 지나 중지했습니다.") else current
        }
    })
    private val deletionCoordinator = ConversationDeletionCoordinator()
    private val deletionDeferreds = ConcurrentHashMap<Long, Deferred<ConversationDeletionResult>>()
    private val freshNewChatMutex = Mutex()
    private val selectionContextOwner = SelectionContextOwner()

    private val _states = MutableStateFlow<Map<Long, ConversationGenerationState>>(emptyMap())
    val states: StateFlow<Map<Long, ConversationGenerationState>> = _states.asStateFlow()

    internal var draftMachine: ChatDraftStateMachine = ChatDraftStateMachine(
        store = draftStoreOverride ?: context?.let(::PersistentComposerDraftStore),
        scope = viewModelScope,
    )

    init {
        // A destroyed ViewModel cannot settle its composer request later. Do
        // not leave its process-local admission tokens blocking a replacement
        // owner for the same conversation.
        viewModelScope.coroutineContext[Job]?.invokeOnCompletion {
            draftMachine.drafts.value.forEach { (conversationId, draft) ->
                draft.activeSubmissionToken?.let { token ->
                    ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
                }
                draft.importOwnership?.token?.let { token ->
                    ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
                }
            }
        }
    }

    val composerDrafts: StateFlow<Map<Long, ComposerDraftState>>
        get() = draftMachine.drafts

    internal val draftHydrationHealth: StateFlow<DraftHydrationHealth>
        get() = draftMachine.hydrationHealth

    private val _currentConversationId = MutableStateFlow(
        savedStateHandle[CURRENT_CONVERSATION_KEY] ?: NEW_CONVERSATION_ID
    )
    val currentConversationId: StateFlow<Long> = _currentConversationId.asStateFlow()
    private val pendingProjectNewChatLock = Any()
    private val _pendingProjectNewChatId = MutableStateFlow<Long?>(
        savedStateHandle[PENDING_PROJECT_NEW_CHAT_KEY]
    )
    val pendingProjectNewChatId: StateFlow<Long?> = _pendingProjectNewChatId.asStateFlow()
    private val newChatEpochCounter = AtomicLong(savedStateHandle[NEW_CHAT_EPOCH_KEY] ?: 0L)
    private val _newChatEpoch = MutableStateFlow(newChatEpochCounter.get())
    val newChatEpoch: StateFlow<Long> = _newChatEpoch.asStateFlow()
    val selectionContextBinding: StateFlow<SelectionContextBinding?> = selectionContextOwner.binding
    private val _selectionScopeConversationId = MutableStateFlow<Long?>(savedStateHandle[SELECTION_SCOPE_CONVERSATION_KEY])
    val selectionScopeConversationId: StateFlow<Long?> = _selectionScopeConversationId.asStateFlow()

    fun installSelectionContext(snapshot: SelectionContextSnapshot, expectedEpoch: Long): Boolean {
        val installed = selectionContextOwner.install(snapshot, _currentConversationId.value, _newChatEpoch.value, expectedEpoch)
        if (installed) setSelectionScopeConversation(NEW_CONVERSATION_ID)
        return installed
    }

    fun promoteSelectionContext(snapshotId: String, conversationId: Long, expectedEpoch: Long): Boolean {
        val promoted = selectionContextOwner.promote(snapshotId, conversationId, expectedEpoch)
        if (promoted) setSelectionScopeConversation(conversationId)
        return promoted
    }

    fun selectionContextFor(conversationId: Long, epoch: Long): SelectionContextSnapshot? =
        selectionContextOwner.snapshotFor(conversationId, epoch)

    fun consumeSelectionContext(snapshotId: String, epoch: Long): Boolean {
        val consumed = selectionContextOwner.consume(snapshotId, epoch)
        if (consumed) setSelectionScopeConversation(null)
        return consumed
    }

    /** Drop the unavailable process-restored scope only after the user asks to clear it. */
    fun clearUnavailableSelectionScope(conversationId: Long): Boolean {
        if (_selectionScopeConversationId.value != conversationId || selectionContextOwner.binding.value != null) return false
        setSelectionScopeConversation(null)
        return true
    }

    fun selectionScopeUnavailableFor(conversationId: Long): Boolean =
        _selectionScopeConversationId.value == conversationId &&
            selectionContextOwner.snapshotFor(conversationId, _newChatEpoch.value) == null

    private fun setSelectionScopeConversation(conversationId: Long?) {
        _selectionScopeConversationId.value = conversationId
        savedStateHandle[SELECTION_SCOPE_CONVERSATION_KEY] = conversationId
    }

    fun selectConversation(conversationId: Long) {
        val bindingConversationId = selectionContextOwner.binding.value?.conversationId
        val scopedConversationId = _selectionScopeConversationId.value
        if ((bindingConversationId != null && bindingConversationId != conversationId) ||
            (bindingConversationId == null && scopedConversationId != null && scopedConversationId != conversationId)
        ) {
            selectionContextOwner.clear()
            setSelectionScopeConversation(null)
        }
        if (conversationId > NEW_CONVERSATION_ID) {
            clearPendingProjectNewChat()
        }
        _currentConversationId.value = conversationId
        savedStateHandle[CURRENT_CONVERSATION_KEY] = conversationId
        val selected = state(conversationId)
        FusionLifecycleLog.event(
            name = "VISIBLE_CONVERSATION_CHANGED",
            conversationId = conversationId,
            requestId = selected.activeRequestId,
            terminationIntent = selected.activeRequestId?.let { requestId ->
                registry.cancellationController.intentFor(
                    com.projectnuke.fusion.llm.GenerationRequestKey(conversationId, requestId),
                )
            },
            isGenerating = selected.isGenerating,
            isStopping = selected.isStopping,
            requestActive = selected.activeRequestId != null,
            registryActive = registry.hasLogicalRequest(conversationId),
            runtimeState = FusionLifecycleLog.runtimeStateName(FusionRuntimeLock.ownerSnapshot()),
        )
    }

    /**
     * Restore-time guard for the SavedStateHandle conversation id.
     *
     * A process can be killed between the durable Room delete-commit and the
     * deletion worker's settlement redirect, leaving a now-deleted id as the
     * restored authoritative surface: the UI would render an empty ghost
     * window and Send would target a nonexistent conversation row. Revalidate
     * against durable truth at cold start and fall back to New Chat whenever
     * the restored id no longer exists. Host-testable through the predicate.
     *
     * Stale-result fence: the durable existence check suspends, and while it
     * is suspended another authoritative path (or the user) may select a
     * different conversation B. The stale check for A may only settle by
     * selecting New Chat when the current conversation is still exactly A;
     * otherwise the late result is dropped like any other stale publication.
     * No lock is held across the database suspension and normal selection is
     * never blocked.
     */
    suspend fun revalidateRestoredConversation(conversationExists: suspend (Long) -> Boolean) {
        val checkedId = _currentConversationId.value
        if (checkedId <= NEW_CONVERSATION_ID) return
        val exists = conversationExists(checkedId)
        if (exists) return
        if (_currentConversationId.value != checkedId) return
        selectConversation(NEW_CONVERSATION_ID)
    }

    /** Sets the one pending project binding for the unsaved New Chat only. */
    fun setPendingProjectNewChat(projectId: Long?) {
        synchronized(pendingProjectNewChatLock) {
            _pendingProjectNewChatId.value = projectId
            savedStateHandle[PENDING_PROJECT_NEW_CHAT_KEY] = projectId
        }
    }

    fun clearPendingProjectNewChat() {
        synchronized(pendingProjectNewChatLock) {
            _pendingProjectNewChatId.value = null
            savedStateHandle[PENDING_PROJECT_NEW_CHAT_KEY] = null
        }
    }

    /** Consumes a binding only after its conversation assignment committed. */
    fun consumePendingProjectNewChat(projectId: Long): Boolean {
        synchronized(pendingProjectNewChatLock) {
            if (_pendingProjectNewChatId.value != projectId) return false
            _pendingProjectNewChatId.value = null
            savedStateHandle[PENDING_PROJECT_NEW_CHAT_KEY] = null
            return true
        }
    }

    /**
     * Opens a fresh composing surface. The epoch is deliberately independent
     * of conversation id so 0 -> 0 is still an observable reset action.
     * Only this operation may implement the product New Chat contract.
     */
    internal suspend fun openFreshNewChat(
        projectIdAfterCommit: Long? = null,
    ): FreshNewChatResult = freshNewChatMutex.withLock {
        openFreshNewChatLocked(initialRawInput = null, projectIdAfterCommit = projectIdAfterCommit)
    }

    /**
     * Atomically opens a fresh Project Chat seeded with [initialRawInput]
     * (Project Synthesis handoff). Reset and seed share the same
     * freshNewChatMutex ownership as [openFreshNewChat]: there is no visible
     * blank-New-Chat window between reset and seed.
     *
     * The pending project binding becomes [projectId] only after the fresh
     * composer adoption commits. Any failure leaves the old draft, selection,
     * epoch and project binding untouched and never generates.
     */
    internal suspend fun openFreshProjectChatSeeded(
        projectId: Long,
        initialRawInput: String,
    ): FreshNewChatResult = freshNewChatMutex.withLock {
        if (projectId <= 0L) {
            return@withLock FreshNewChatResult(
                success = false,
                epoch = newChatEpoch.value,
                failure = FreshNewChatFailure.INVALID_SEED_PROJECT,
            )
        }
        if (initialRawInput.isBlank() ||
            initialRawInput.length > ProjectSynthesisPromptPolicy.MAX_SYNTHESIS_PROMPT_CHARS
        ) {
            return@withLock FreshNewChatResult(
                success = false,
                epoch = newChatEpoch.value,
                failure = FreshNewChatFailure.INVALID_SEED_PROMPT,
            )
        }
        openFreshNewChatLocked(initialRawInput = initialRawInput, projectIdAfterCommit = projectId)
    }

    /**
     * Shared fresh-chat reset owned by [freshNewChatMutex]. The draft reset
     * mode and the post-commit project binding are independent: a null
     * [initialRawInput] durably clears the NEW_CHAT composer, otherwise it
     * durably replaces it with a fresh seeded draft. The pending project
     * binding is published only after the draft transition commits.
     */
    private suspend fun openFreshNewChatLocked(
        initialRawInput: String?,
        projectIdAfterCommit: Long?,
    ): FreshNewChatResult {
        // The state machine intentionally accepts durable commands before its
        // restore command. Fresh New Chat is a destructive ownership handoff,
        // so it must never clear/replace the pre-hydration empty baseline.
        // Unreadable ownership is also not equivalent to an empty draft.
        if (!draftMachine.awaitHydration() ||
            !shouldProcessReconciliationDebts(draftMachine.draftHealthNow())
        ) {
            return FreshNewChatResult(
                success = false,
                epoch = newChatEpoch.value,
                failure = FreshNewChatFailure.DRAFT_UNAVAILABLE,
            )
        }

        val visibleConversationId = currentConversationId.value
        val conversationsToSettle = buildList {
            add(visibleConversationId)
            if (visibleConversationId != NEW_CONVERSATION_ID) add(NEW_CONVERSATION_ID)
        }.distinct()

        for (conversationId in conversationsToSettle) {
            if (!settleGenerationForFreshNewChat(conversationId)) {
                return FreshNewChatResult(
                    success = false,
                    epoch = newChatEpoch.value,
                    failure = FreshNewChatFailure.CANCELLATION_FAILED,
                )
            }
        }

        val freshDraft = draft(NEW_CONVERSATION_ID)
        // P16-04: release exactly the ownership the committed reset removed.
        // The pre-command read above only supplies the removed attachment
        // paths for the success return; the authoritative removed ownership is
        // captured by the serialized consumer (clear) or the committed
        // transform (replace). The reset acknowledgement and the matching
        // releases are shielded so a cancelled caller cannot strand a
        // committed reset's leases.
        var removedOwnership: ComposerDraftState? = null
        val draftReset = withContext(NonCancellable) {
            val reset = if (initialRawInput == null) {
                val capture = draftMachine.clearDraftCapturing(NEW_CONVERSATION_ID)
                removedOwnership = capture.removed
                capture.cleared
            } else {
                draftMachine.replaceWithFreshDraftAfterHydration(
                    NEW_CONVERSATION_ID,
                    initialRawInput,
                    onCommittedRemoved = { removedOwnership = it },
                )
            }
            if (reset) {
                removedOwnership?.activeSubmissionToken?.let { token ->
                    ManualCompactionOperationRegistry.releaseSubmission(NEW_CONVERSATION_ID, token)
                }
                removedOwnership?.importOwnership?.token?.let { token ->
                    ManualCompactionOperationRegistry.releaseSubmission(NEW_CONVERSATION_ID, token)
                }
            }
            reset
        }
        currentCoroutineContext().ensureActive()
        if (!draftReset) {
            return FreshNewChatResult(
                success = false,
                epoch = newChatEpoch.value,
                failure = FreshNewChatFailure.DRAFT_CLEAR_FAILED,
            )
        }
        // The replaced draft's registry ownership was already released inside
        // the shielded reset above, mirroring clearDraft().
        // A fresh 0 surface must not restore conversation-local generation
        // state after the draft itself has been durably cleared.
        clear(NEW_CONVERSATION_ID)
        selectionContextOwner.clear()
        setSelectionScopeConversation(null)
        selectConversation(NEW_CONVERSATION_ID)
        if (projectIdAfterCommit != null) {
            setPendingProjectNewChat(projectIdAfterCommit)
        } else {
            clearPendingProjectNewChat()
        }
        val epoch = newChatEpochCounter.incrementAndGet()
        savedStateHandle[NEW_CHAT_EPOCH_KEY] = epoch
        _newChatEpoch.value = epoch
        return FreshNewChatResult(
            success = true,
            epoch = epoch,
            pendingAttachmentPaths = freshDraft.pendingAttachments.map { it.localPath },
        )
    }

    /**
     * Checks only durable/readable NEW_CHAT state. A false result is also
     * returned for unreadable ownership so callers cannot treat corruption as
     * proof that an event has not been adopted.
     */
    internal suspend fun isExternalHandoffAdopted(eventId: String): Boolean {
        if (!draftMachine.awaitHydration()) return false
        if (!shouldProcessReconciliationDebts(draftMachine.draftHealthNow())) return false
        return draft(NEW_CONVERSATION_ID).lastExternalHandoffId == eventId
    }

    /**
     * Returns the persisted partial-result notice for a replay of the exact
     * adopted event. The notice is committed atomically with attachment
     * adoption so a process death cannot turn a partial import into full
     * success or erase its user-visible explanation.
     */
    internal suspend fun externalHandoffPartialNotice(eventId: String): String? {
        if (!draftMachine.awaitHydration()) return null
        if (!shouldProcessReconciliationDebts(draftMachine.draftHealthNow())) return null
        val adopted = draft(NEW_CONVERSATION_ID)
        return adopted.lastExternalHandoffPartialNotice.takeIf {
            adopted.lastExternalHandoffId == eventId
        }
    }

    /**
     * Atomically adopts a coordinator-prepared external handoff. Preparation
     * owns staged files; this method is the only point that can replace the
     * NEW_CHAT composer and advance the fresh-chat epoch.
     */
    internal suspend fun adoptPreparedExternalHandoff(
        prepared: PreparedExternalHandoff,
    ): ExternalHandoffAdoptionResult = freshNewChatMutex.withLock {
        if (!prepared.isBounded()) {
            return@withLock ExternalHandoffAdoptionResult(
                success = false,
                failure = ExternalHandoffAdoptionFailure.INVALID_PAYLOAD,
            )
        }
        if (!draftMachine.awaitHydration() ||
            !shouldProcessReconciliationDebts(draftMachine.draftHealthNow())
        ) {
            return@withLock ExternalHandoffAdoptionResult(
                success = false,
                failure = ExternalHandoffAdoptionFailure.COMPOSER_UNAVAILABLE,
            )
        }
        if (draft(NEW_CONVERSATION_ID).lastExternalHandoffId == prepared.eventId) {
            return@withLock ExternalHandoffAdoptionResult(success = true)
        }

        val visibleConversationId = currentConversationId.value
        val conversationsToSettle = buildList {
            add(visibleConversationId)
            if (visibleConversationId != NEW_CONVERSATION_ID) add(NEW_CONVERSATION_ID)
        }.distinct()
        for (conversationId in conversationsToSettle) {
            if (!settleGenerationForFreshNewChat(conversationId)) {
                return@withLock ExternalHandoffAdoptionResult(
                    success = false,
                    failure = ExternalHandoffAdoptionFailure.GENERATION_SETTLEMENT_FAILED,
                )
            }
        }

        val freshDraft = draft(NEW_CONVERSATION_ID)
        val visibleDraft = draft(visibleConversationId)
        if (freshDraft.activeSubmissionToken != null ||
            freshDraft.importOwnership != null ||
            (visibleConversationId != NEW_CONVERSATION_ID &&
                (visibleDraft.activeSubmissionToken != null || visibleDraft.importOwnership != null))
        ) {
            return@withLock ExternalHandoffAdoptionResult(
                success = false,
                failure = ExternalHandoffAdoptionFailure.ACTIVE_COMPOSER_OWNER,
            )
        }

        // Cancellation before this point leaves the event retryable and the
        // old draft untouched. The replacement below is the commit point.
        currentCoroutineContext().ensureActive()
        val adopted = withContext(NonCancellable) {
            draftMachine.updateCritical(NEW_CONVERSATION_ID) { current ->
                if (current.activeSubmissionToken != null || current.importOwnership != null) {
                    null
                } else {
                    current.copy(
                        rawInput = prepared.rawInput,
                        pendingAttachments = prepared.pendingAttachments,
                        importOwnership = null,
                        activeSubmissionToken = null,
                        lastExternalHandoffId = prepared.eventId,
                        lastExternalHandoffPartialNotice = prepared.partialOutcomeNotice,
                        version = current.version + 1L,
                    )
                }
            }
        }
        if (!adopted) {
            return@withLock ExternalHandoffAdoptionResult(
                success = false,
                failure = ExternalHandoffAdoptionFailure.DRAFT_REPLACEMENT_FAILED,
            )
        }

        // The durable receipt is now authoritative. A late cancellation must
        // not turn this committed adoption into a retryable failure.
        withContext(NonCancellable) {
            clear(NEW_CONVERSATION_ID)
            selectConversation(NEW_CONVERSATION_ID)
            clearPendingProjectNewChat()
            val epoch = newChatEpochCounter.incrementAndGet()
            savedStateHandle[NEW_CHAT_EPOCH_KEY] = epoch
            _newChatEpoch.value = epoch
        }
        ExternalHandoffAdoptionResult(
            success = true,
            oldPendingAttachmentPaths = freshDraft.pendingAttachments.map { it.localPath },
        )
    }

    private suspend fun settleGenerationForFreshNewChat(conversationId: Long): Boolean {
        val stateRequestId = state(conversationId).activeRequestId
        if (stateRequestId == null && !registry.hasLogicalRequest(conversationId)) {
            return true
        }
        val requestId = stateRequestId
            ?: registry.activeSession(conversationId)?.requestId
            ?: registry.latestToken(conversationId)?.requestId
        if (requestId == null) {
            if (stateRequestId != null) finishRequestState(conversationId, stateRequestId)
            return !registry.hasLogicalRequest(conversationId)
        }

        return try {
            if (stateRequestId == requestId) {
                cancelGeneration(conversationId, "new-chat")
            } else {
                registry.cancelAndJoin(
                    conversationId = conversationId,
                    requestId = requestId,
                    intent = GenerationTerminationIntent.INTERNAL_CANCEL,
                    reason = "new-chat",
                )
                finishRequestState(conversationId, requestId)
            }
            !registry.hasLogicalRequest(conversationId) &&
                registry.activeSession(conversationId) == null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            // Fail closed ("not settled") without hiding fatal Errors:
            // LinkageError/VirtualMachineError must propagate per the
            // fatal-error policy instead of blocking fresh-chat as retryable.
            false
        }
    }

    fun state(conversationId: Long): ConversationGenerationState =
        _states.value[conversationId] ?: ConversationGenerationState()

    fun update(conversationId: Long, transform: (ConversationGenerationState) -> ConversationGenerationState) {
        _states.update { current ->
            val existing = current[conversationId] ?: ConversationGenerationState()
            current + (conversationId to transform(existing))
        }
    }

    fun updateRequestState(
        conversationId: Long,
        requestId: String,
        requireActiveSession: Boolean = false,
        transform: (ConversationGenerationState) -> ConversationGenerationState,
    ) {
        _states.update { current ->
            val existing = current[conversationId] ?: return@update current
            if (existing.activeRequestId != requestId) return@update current
            if (requireActiveSession && !registry.isActive(conversationId, requestId)) return@update current
            current + (conversationId to transform(existing))
        }
    }

    internal fun finishRequestState(conversationId: Long, requestId: String): RequestStateFinishResult {
        var result = RequestStateFinishResult.ALREADY_FINISHED
        _states.update { current ->
            val existing = current[conversationId]
            if (existing == null || existing.activeRequestId == null) {
                result = RequestStateFinishResult.ALREADY_FINISHED
                return@update current
            }
            if (existing.activeRequestId != requestId) {
                result = RequestStateFinishResult.STALE_OWNER
                return@update current
            }
            result = RequestStateFinishResult.APPLIED
            current + (conversationId to existing.finished())
        }
        FusionLifecycleLog.event(
            name = when (result) {
                RequestStateFinishResult.APPLIED -> "REQUEST_STATE_FINISH_APPLIED"
                RequestStateFinishResult.STALE_OWNER,
                RequestStateFinishResult.ALREADY_FINISHED -> "REQUEST_STATE_FINISH_STALE"
            },
            conversationId = conversationId,
            requestId = requestId,
            terminationIntent = registry.cancellationController.intentFor(
                com.projectnuke.fusion.llm.GenerationRequestKey(conversationId, requestId),
            ),
            isGenerating = state(conversationId).isGenerating,
            isStopping = state(conversationId).isStopping,
            requestActive = state(conversationId).activeRequestId != null,
            registryActive = registry.hasLogicalRequest(conversationId),
            runtimeState = FusionLifecycleLog.runtimeStateName(FusionRuntimeLock.ownerSnapshot()),
        )
        return result
    }

    fun clear(conversationId: Long) {
        _states.update { it - conversationId }
    }

    /** Read-only lifecycle seam for tests; the deferred map remains private. */
    internal fun deletionDeferredCountForTest(): Int = deletionDeferreds.size

    suspend fun cancelGeneration(conversationId: Long) {
        val requestId = state(conversationId).activeRequestId ?: return
        update(conversationId) {
            if (it.activeRequestId != requestId) it else it.copy(
                isStopping = true,
                generationStatus = "응답 중지 중...",
            )
        }
        FusionLifecycleLog.event(
            name = "STOP_REQUESTED",
            conversationId = conversationId,
            requestId = requestId,
            terminationIntent = GenerationTerminationIntent.USER_STOP,
            isGenerating = state(conversationId).isGenerating,
            isStopping = state(conversationId).isStopping,
            requestActive = true,
            registryActive = registry.hasLogicalRequest(conversationId),
            runtimeState = FusionLifecycleLog.runtimeStateName(FusionRuntimeLock.ownerSnapshot()),
        )
        try {
            registry.stopAndJoin(conversationId, requestId)
        } finally {
            finishRequestState(conversationId, requestId)
        }
    }

    /** Compatibility seam for structural-cancellation tests and callers. */
    suspend fun cancelGeneration(conversationId: Long, reason: String) {
        val requestId = state(conversationId).activeRequestId ?: return
        update(conversationId) {
            if (it.activeRequestId != requestId) it else it.copy(isStopping = true)
        }
        try {
            registry.cancelAndJoin(
                conversationId,
                requestId,
                GenerationTerminationIntent.INTERNAL_CANCEL,
                reason,
            )
        } finally {
            finishRequestState(conversationId, requestId)
        }
    }

    suspend fun cancelAndAwait(conversationId: Long, reason: String = "delete"): Boolean {
        val existingOwner = registry.deletionOwner(conversationId)
        val acquiredOwner = existingOwner == null &&
            registry.claimDeletionOwnership(conversationId, reason)
        if (existingOwner != null && existingOwner != reason) return false
        return try {
            registry.settleForDeletion(conversationId, reason)
        } finally {
            clear(conversationId)
            if (acquiredOwner) registry.releaseDeletionOwnership(conversationId, reason)
        }
    }

    internal fun deleteConversation(
        conversationId: Long,
        exists: suspend () -> Boolean,
        commitDelete: suspend () -> Unit,
        settleTarget: suspend () -> Unit,
        cleanupDerivedData: suspend () -> Unit,
        recordCleanupDebt: suspend () -> Unit,
        recordCleanupDebtResult: (suspend () -> Boolean)? = null,
    ): Deferred<ConversationDeletionResult> {
        deletionDeferreds[conversationId]?.let { return it }
        val candidate = CompletableDeferred<ConversationDeletionResult>()
        val installed = deletionDeferreds.putIfAbsent(conversationId, candidate)
        if (installed != null) return installed

        val deletionReason = "delete-conversation"
        val ownsDeletionOwnership =
            registry.claimDeletionOwnership(conversationId, deletionReason)
        if (!ownsDeletionOwnership) {
            candidate.complete(ConversationDeletionResult.BUSY)
            deletionDeferreds.remove(conversationId, candidate)
            return candidate
        }
        val cleanupWorkerState = AtomicBoolean(false)
        val cleanupWorker = {
            if (cleanupWorkerState.compareAndSet(false, true)) {
                registry.releaseDeletionOwnership(conversationId, deletionReason)
                deletionDeferreds.remove(conversationId, candidate)
            }
        }
        val worker = viewModelScope.launch {
            try {
                val result = deletionCoordinator.delete(
                    conversationId = conversationId,
                    cancelAndJoin = {
                        try {
                            registry.settleForDeletion(
                                conversationId,
                                reason = "delete-conversation",
                            )
                        } finally {
                            clear(conversationId)
                        }
                    },
                    exists = exists,
                    commitDelete = commitDelete,
                    settleTarget = settleTarget,
                    cleanupDerivedData = cleanupDerivedData,
                    recordCleanupDebt = recordCleanupDebt,
                    recordCleanupDebtResult = recordCleanupDebtResult,
                )
                candidate.complete(result)
            } catch (cancelled: CancellationException) {
                candidate.cancel(cancelled)
            } catch (failure: Throwable) {
                candidate.completeExceptionally(failure)
            } finally {
                cleanupWorker()
            }
        }
        worker.invokeOnCompletion { cause ->
            // A ViewModel scope may already be cancelled before the launch
            // block is dispatched. In that case the body/finally above is
            // never entered; the worker completion boundary still owns the
            // deletion candidate and registry release.
            cleanupWorker()
            if (!candidate.isCompleted) {
                if (cause is CancellationException) {
                    candidate.cancel(cause)
                } else if (cause != null) {
                    candidate.completeExceptionally(cause)
                } else {
                    candidate.cancel(CancellationException("Deletion worker exited without a result"))
                }
            }
        }
        candidate.invokeOnCompletion { cause ->
            if (cause is CancellationException) worker.cancel(cause)
        }
        return candidate
    }

    fun draft(conversationId: Long): ComposerDraftState =
        draftMachine.drafts.value[conversationId] ?: ComposerDraftState()

    internal fun updateDraftText(conversationId: Long, rawInput: String): ComposerTextEditResult {
        val proposal = ComposerDraftPolicy.proposeText(draft(conversationId).rawInput, rawInput)
        if (proposal is ComposerTextEditResult.Accepted) {
            draftMachine.updateText(conversationId) { current ->
                current.copy(rawInput = proposal.text, version = current.version + 1L)
            }
        }
        return proposal
    }

    /**
     * Durable one-shot composer replacement for external inline handoff.
     * Unlike [updateDraftText] (debounced fire-and-forget), the critical
     * state-machine path writes the exact [rawInput] before returning:
     * `true` means the draft durably carries the payload, `false` means the
     * in-memory draft was rolled back and nothing new was written. An
     * in-flight submission or attachment import is never overwritten; the
     * write simply fails instead.
     */
    suspend fun replaceDraftTextDurably(conversationId: Long, rawInput: String): Boolean {
        return draftMachine.updateCritical(conversationId) { current ->
            if (current.activeSubmissionToken != null) null
            else if (current.importOwnership != null) null
            else current.copy(rawInput = rawInput, version = current.version + 1L)
        }
    }

    /**
     * Atomic voice-dictation draft application: replaces [expectedRawInput]
     * with [nextRawInput] only when the composer still holds exactly the
     * controller-owned text. A concurrent user edit (or an in-flight
     * submission/import) fails the write instead of being overwritten, so
     * the caller must supersede voice ownership on `false`. Ordinary
     * composer text only — never sends, generates, or touches model/search.
     */
    suspend fun applyVoiceDraftText(
        conversationId: Long,
        expectedRawInput: String,
        nextRawInput: String,
    ): Boolean {
        return draftMachine.updateCritical(conversationId) { current ->
            if (current.activeSubmissionToken != null) null
            else if (current.importOwnership != null) null
            else if (current.rawInput != expectedRawInput) null
            else current.copy(rawInput = nextRawInput, version = current.version + 1L)
        }
    }

    fun appendQuickPrompt(conversationId: Long, prompt: String, append: (String, String) -> String) {
        draftMachine.updateText(conversationId) { current ->
            current.copy(
                rawInput = append(current.rawInput, prompt),
                version = current.version + 1L,
            )
        }
    }

    /**
     * Acknowledged durable prompt append for F2 (Next Actions) and F3
     * (Study Session) flows. Unlike [appendQuickPrompt] (debounced
     * fire-and-forget), the critical state-machine path persists the exact
     * appended draft before returning: `true` means the composer durably
     * carries the prompt, `false` means the write was rejected or failed
     * and the in-memory draft was rolled back. An in-flight submission or
     * attachment import is never overwritten; the append simply fails.
     *
     * Ordinary keyboard typing keeps using [updateDraftText]; no second
     * draft store is introduced.
     */
    suspend fun appendPromptDurably(
        conversationId: Long,
        prompt: String,
        append: (String, String) -> String,
    ): Boolean {
        return draftMachine.updateCritical(conversationId) { current ->
            if (current.activeSubmissionToken != null) null
            else if (current.importOwnership != null) null
            else current.copy(
                rawInput = append(current.rawInput, prompt),
                version = current.version + 1L,
            )
        }
    }

    suspend fun removePendingAttachment(conversationId: Long, localPath: String): Boolean {
        var removed = false
        return draftMachine.updateCritical(conversationId) { current ->
            val remaining = current.pendingAttachments.filterNot {
                if (!removed && it.localPath == localPath) {
                    removed = true
                    true
                } else {
                    false
                }
            }
            if (!removed) null else current.copy(
                pendingAttachments = remaining,
                version = current.version + 1L,
            )
        } && removed
    }

    /** Test-only state-machine setup that deliberately does not claim a registry lease. */
    internal fun beginAttachmentImport(conversationId: Long): ComposerImportOwnership {
        val owner = ComposerImportOwnership(
            token = UUID.randomUUID().toString(),
            status = ComposerImportStatus.PICKER_OPEN,
        )
        enqueueAttachmentImport(conversationId, owner)
        return owner
    }

    /**
     * Production picker admission. Hydration and readable durable ownership
     * are prerequisites for every ownership decision. The process-local lease
     * is acquired before the acknowledged state-machine publication, and is
     * released on every path that fails to publish a live draft owner.
     */
    internal suspend fun tryBeginAttachmentImport(conversationId: Long): AttachmentImportAdmission {
        if (!draftMachine.awaitHydration() ||
            !shouldProcessReconciliationDebts(draftMachine.draftHealthNow())
        ) {
            return AttachmentImportAdmission.Rejected(AttachmentImportRejection.DRAFT_UNAVAILABLE)
        }

        val beforeLease = draft(conversationId)
        when {
            beforeLease.activeSubmissionToken != null ->
                return AttachmentImportAdmission.Rejected(AttachmentImportRejection.EXISTING_SUBMISSION)
            beforeLease.importOwnership != null ->
                return AttachmentImportAdmission.Rejected(AttachmentImportRejection.EXISTING_IMPORT)
        }

        val owner = ComposerImportOwnership(
            token = UUID.randomUUID().toString(),
            status = ComposerImportStatus.PICKER_OPEN,
        )
        val registryAdmission = ManualCompactionOperationRegistry.tryAcquireSubmissionDetailed(
            conversationId,
            owner.token,
        )
        if (!registryAdmission.acquired) {
            val rejection = when (registryAdmission.rejection) {
                ManualCompactionOperationRegistry.SubmissionRegistryRejection.MANUAL_COMPACTION_LEASE_HELD -> AttachmentImportRejection.MANUAL_COMPACTION
                ManualCompactionOperationRegistry.SubmissionRegistryRejection.CONVERSATION_DELETION_IN_PROGRESS -> AttachmentImportRejection.CONVERSATION_DELETION
                ManualCompactionOperationRegistry.SubmissionRegistryRejection.EXISTING_SUBMISSION_OWNER -> AttachmentImportRejection.EXISTING_SUBMISSION
                null -> AttachmentImportRejection.OWNER_PERSISTENCE_REJECTED
            }
            return AttachmentImportAdmission.Rejected(rejection)
        }

        var ownerPublished = false
        try {
            // The registry lease closes the first race window. Re-read the
            // authoritative state before publishing the import owner.
            val afterLease = draft(conversationId)
            val competingRejection = when {
                afterLease.activeSubmissionToken != null -> AttachmentImportRejection.EXISTING_SUBMISSION
                afterLease.importOwnership != null -> AttachmentImportRejection.EXISTING_IMPORT
                else -> null
            }
            if (competingRejection != null) {
                return AttachmentImportAdmission.Rejected(competingRejection)
            }

            val published = withContext(NonCancellable) {
                val accepted = draftMachine.updateImmediate(conversationId) { current ->
                    if (current.activeSubmissionToken != null || current.importOwnership != null) {
                        current
                    } else {
                        current.copy(
                            importOwnership = owner,
                            version = current.version + 1L,
                        )
                    }
                }
                if (accepted) ownerPublished = true
                accepted
            }
            if (!published) {
                val current = draft(conversationId)
                val reason = when {
                    current.activeSubmissionToken != null -> AttachmentImportRejection.EXISTING_SUBMISSION
                    current.importOwnership != null -> AttachmentImportRejection.EXISTING_IMPORT
                    else -> AttachmentImportRejection.OWNER_PERSISTENCE_REJECTED
                }
                return AttachmentImportAdmission.Rejected(reason)
            }

            // If cancellation arrived while the acknowledged command was
            // completing, settle the newly published owner before propagating
            // cancellation; never leave draft and registry truth split.
            currentCoroutineContext().ensureActive()
            return AttachmentImportAdmission.Accepted(owner)
        } catch (cancelled: CancellationException) {
            if (ownerPublished) {
                withContext(NonCancellable) {
                    settleAttachmentImportAfterCallback(conversationId, owner.token)
                }
            }
            throw cancelled
        } finally {
            if (!ownerPublished) {
                ManualCompactionOperationRegistry.releaseSubmission(conversationId, owner.token)
            }
        }
    }

    private fun enqueueAttachmentImport(
        conversationId: Long,
        owner: ComposerImportOwnership,
    ) {
        draftMachine.queueImmediate(conversationId) { current ->
            current.copy(importOwnership = owner, version = current.version + 1L)
        }
    }

    fun pendingPickerImport(): Pair<Long, ComposerImportOwnership>? =
        draftMachine.drafts.value.entries.firstNotNullOfOrNull { (conversationId, draft) ->
            draft.importOwnership
                ?.takeIf { it.status == ComposerImportStatus.PICKER_OPEN }
                ?.let { conversationId to it }
        }

    internal suspend fun pendingPickerImportMatches(conversationId: Long, token: String): Boolean {
        if (!draftMachine.awaitHydration()) return false
        val owner = draft(conversationId).importOwnership ?: return false
        return owner.token == token && owner.status == ComposerImportStatus.PICKER_OPEN
    }

    suspend fun beginAttachmentCopy(conversationId: Long, token: String): Boolean {
        return draftMachine.updateImmediate(conversationId) { current ->
            val owner = current.importOwnership
            if (owner?.token != token) current
            else current.copy(
                importOwnership = owner.copy(status = ComposerImportStatus.COPYING),
                version = current.version + 1L,
            )
        }
    }

    suspend fun completeAttachmentImport(
        conversationId: Long,
        token: String,
        attachments: List<PendingAttachmentIdentity>,
    ): Boolean {
        // The state-machine command may commit after this caller is cancelled:
        // cancellation of reply.await() does not retract an accepted command.
        // Keep only the commit acknowledgement and its matching process lease
        // release in a non-cancellable region, then report cancellation to the
        // caller once durable and process-local ownership agree.
        val completed = withContext(NonCancellable) {
            var accepted = false
            val persisted = draftMachine.updateCritical(conversationId) { current ->
                if (current.importOwnership?.token != token) null
                else {
                    accepted = true
                    current.copy(
                        pendingAttachments = current.pendingAttachments + attachments,
                        importOwnership = null,
                        version = current.version + 1L,
                    )
                }
            }
            val result = persisted && accepted
            if (result) ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
            result
        }
        currentCoroutineContext().ensureActive()
        return completed
    }

    /**
     * Camera-to-chat adoption as ONE reference-mutation gate transaction
     * (P14-05).
     *
     * `beginAttachmentCopy` (PICKER_OPEN -> COPYING) and
     * `completeAttachmentImport` (COPYING -> durable draft attachment) used to
     * run as two separate state-machine commands with a suspension gap
     * between them. A camera abandonment landing in that gap observed no
     * durable draft reference (the attachment is committed only by the second
     * command) and a non-PICKER_OPEN owner (so the picker settlement was
     * skipped), deleted the pending file, and the resumed second command
     * then committed a draft attachment naming the deleted file.
     *
     * Holding the process-wide reference-mutation gate across both commands
     * closes the gap: the abandonment's gated discard either runs first (it
     * then settles the still-PICKER_OPEN owner, so `begin` fails and nothing
     * is committed) or after the adoption (the durable draft file names the
     * path, so the discard preserves it). Lock order is gate -> draft
     * channel, the same order `commitAndSettleUserSubmission` already uses
     * (gate -> draft reconciliation), so no inversion is introduced; the
     * state-machine consumer never acquires the gate.
     */
    suspend fun adoptCameraAttachment(
        conversationId: Long,
        token: String,
        attachments: List<PendingAttachmentIdentity>,
    ): Boolean = AttachmentReferenceMutationCoordinator.withMutation {
        if (!beginAttachmentCopy(conversationId, token)) return@withMutation false
        completeAttachmentImport(conversationId, token, attachments)
    }

    suspend fun settleAttachmentImport(conversationId: Long, token: String): Boolean {
        var accepted = false
        val settled = draftMachine.updateCritical(conversationId) { current ->
            if (current.importOwnership?.token != token) null
            else {
                accepted = true
                current.copy(importOwnership = null, version = current.version + 1L)
            }
        } && accepted
        if (settled) ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
        return settled
    }

    /**
     * Callback settlement must be safe even when the picker callback is
     * cancelled. A failed state transition may release only an orphaned
     * process lease; a still-live matching draft owner remains fail-closed.
     */
    internal suspend fun settleAttachmentImportAfterCallback(
        conversationId: Long,
        token: String,
    ): AttachmentImportSettlementResult = withContext(NonCancellable) {
        if (settleAttachmentImport(conversationId, token)) {
            AttachmentImportSettlementResult.SETTLED
        } else if (draft(conversationId).importOwnership?.token == token) {
            // Persistence rejected the clear while the matching owner remains
            // live. Keep its registry lease and let the abandonment owner
            // retain a retry debt; this is not successful cleanup.
            AttachmentImportSettlementResult.RETRY_REQUIRED
        } else {
            ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
            AttachmentImportSettlementResult.ALREADY_SETTLED
        }
    }

    internal fun draftHealthNow(): DraftHydrationHealth = draftMachine.draftHealthNow()

    internal suspend fun recoverUnreadableDraftState(): DraftRecoveryResult =
        draftMachine.recoverUnreadableDurableState()

    internal suspend fun beginSubmissionResult(conversationId: Long): SubmissionAdmissionResult {
        // Fail-safe: unreadable durable composer ownership is never proof of
        // empty ownership. A new Send must not silently assume the persisted
        // activeSubmissionToken is absent.
        if (!draftMachine.awaitHydration()) {
            return rejectedSubmission(conversationId, SubmissionRejectionReason.DRAFT_PERSISTENCE_REJECTED)
        }
        if (!shouldProcessReconciliationDebts(draftMachine.draftHealthNow())) {
            return rejectedSubmission(conversationId, SubmissionRejectionReason.DRAFT_STORE_UNREADABLE)
        }
        // Schema-8 owners are explicitly distinguishable from legacy UUID
        // owners. A missing witness is safe to reclaim only for this protocol.
        val token = SubmissionCommitWitnessContract.PROTOCOL_PREFIX + UUID.randomUUID().toString()
        val draftBefore = draft(conversationId)
        val registryAdmission = ManualCompactionOperationRegistry.tryAcquireSubmissionDetailed(
            conversationId,
            token,
        )
        if (!registryAdmission.acquired) {
            val reason = when {
                registryAdmission.rejection == ManualCompactionOperationRegistry.SubmissionRegistryRejection.MANUAL_COMPACTION_LEASE_HELD -> SubmissionRejectionReason.MANUAL_COMPACTION_LEASE_HELD
                registryAdmission.rejection == ManualCompactionOperationRegistry.SubmissionRegistryRejection.CONVERSATION_DELETION_IN_PROGRESS -> SubmissionRejectionReason.CONVERSATION_DELETION_IN_PROGRESS
                draftBefore.importOwnership != null -> SubmissionRejectionReason.IMPORT_OWNER_ACTIVE
                else -> SubmissionRejectionReason.EXISTING_SUBMISSION_OWNER
            }
            return rejectedSubmission(conversationId, reason)
        }
        var snapshot: ComposerSubmissionSnapshot? = null
        // The acknowledgement wait is the cancellable point, not the accepted
        // command: a caller cancelled at reply.await() must not orphan a
        // durably committed token (P16-01). Shield only the commit
        // acknowledgement and the matching lease decision, then converge:
        // a committed admission that can no longer reach its caller is rolled
        // back (exact token only; the fresh token cannot have a Room commit
        // witness yet) before cancellation propagates.
        var ownerPublished = false
        try {
            val persisted = withContext(NonCancellable) {
                val ok = draftMachine.updateCritical(conversationId) { current ->
                    if (current.activeSubmissionToken != null || current.importOwnership != null) null
                    else {
                        snapshot = ComposerSubmissionSnapshot(
                            token = token,
                            conversationId = conversationId,
                            rawInput = current.rawInput,
                            pendingAttachments = current.pendingAttachments,
                            version = current.version,
                        )
                        current.copy(activeSubmissionToken = token)
                    }
                }
                if (ok) ownerPublished = true
                ok
            }
            if (!persisted) {
                ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
                val current = draft(conversationId)
                val reason = when {
                    current.importOwnership != null -> SubmissionRejectionReason.IMPORT_OWNER_ACTIVE
                    current.activeSubmissionToken != null -> SubmissionRejectionReason.EXISTING_SUBMISSION_OWNER
                    else -> SubmissionRejectionReason.DRAFT_PERSISTENCE_REJECTED
                }
                return rejectedSubmission(conversationId, reason)
            }
            // A pending cancellation that arrived while the acknowledged
            // command was completing still converges through the catch below:
            // the newly published owner is settled before it propagates.
            currentCoroutineContext().ensureActive()
        } catch (failure: Throwable) {
            if (ownerPublished) {
                withContext(NonCancellable) {
                    runCatching {
                        draftMachine.updateCritical(conversationId) { current ->
                            if (current.activeSubmissionToken != token) null
                            else current.copy(activeSubmissionToken = null)
                        }
                    }
                    ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
                }
            } else {
                ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
            }
            throw failure
        }
        val accepted = SubmissionAdmissionResult.Accepted(checkNotNull(snapshot))
        FusionLifecycleLog.event(
            name = "NEXT_SEND_ADMISSION_ACCEPTED",
            conversationId = conversationId,
            requestId = accepted.snapshot.token,
            draftSubmission = true,
            requestActive = state(conversationId).activeRequestId != null,
            registryActive = registry.hasLogicalRequest(conversationId),
            runtimeState = FusionLifecycleLog.runtimeStateName(FusionRuntimeLock.ownerSnapshot()),
        )
        return accepted
    }

    /** Compatibility convenience for non-UI callers; production UI uses the typed result. */
    suspend fun beginSubmission(conversationId: Long): ComposerSubmissionSnapshot? =
        (beginSubmissionResult(conversationId) as? SubmissionAdmissionResult.Accepted)?.snapshot

    private fun rejectedSubmission(
        conversationId: Long,
        reason: SubmissionRejectionReason,
    ): SubmissionAdmissionResult.Rejected {
        val current = draft(conversationId)
        val generation = state(conversationId)
        FusionLifecycleLog.event(
            name = "NEXT_SEND_ADMISSION_REJECTED",
            conversationId = conversationId,
            requestId = generation.activeRequestId,
            reason = reason.name,
            terminationIntent = generation.activeRequestId?.let { requestId ->
                registry.cancellationController.intentFor(
                    com.projectnuke.fusion.llm.GenerationRequestKey(conversationId, requestId),
                )
            },
            draftSubmission = current.activeSubmissionToken != null,
            requestActive = generation.activeRequestId != null,
            registryActive = registry.hasLogicalRequest(conversationId),
            runtimeState = FusionLifecycleLog.runtimeStateName(FusionRuntimeLock.ownerSnapshot()),
        )
        return SubmissionAdmissionResult.Rejected(reason)
    }

    suspend fun settleSubmissionOwner(conversationId: Long, token: String): Boolean {
        // The acknowledgement wait is cancellable, the accepted command is
        // not (P16-02): shield only the durable acknowledgement and the exact
        // lease release, then let a pending cancellation propagate once both
        // ownership layers agree.
        val settled = withContext(NonCancellable) {
            val ok = draftMachine.updateCritical(conversationId) { current ->
                if (current.activeSubmissionToken != token) null
                else current.copy(activeSubmissionToken = null)
            }
            if (ok) {
                ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
            } else if (draft(conversationId).activeSubmissionToken != token) {
                // No matching durable owner remains: an exact-token release
                // only repairs an orphaned process lease and can never release
                // a newer owner's lease (release is exact-token idempotent).
                ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
            }
            ok
        }
        currentCoroutineContext().ensureActive()
        return settled
    }

    suspend fun reconcileCommittedSubmission(
        snapshot: ComposerSubmissionSnapshot,
        committedPaths: List<String>,
    ): Boolean {
        // Same acknowledgement shield as settlement (P16-03): the Room commit
        // already happened, so a cancelled caller must not strand the process
        // lease of a durably reconciled submission.
        val reconciled = withContext(NonCancellable) {
            val ok = draftMachine.reconcileCommittedSubmission(
                draftKey = snapshot.conversationId,
                token = snapshot.token,
                capturedRawInput = snapshot.rawInput,
                committedPaths = committedPaths.toSet(),
            )
            if (ok) {
                // Exact-token release is idempotent and also repairs a same-process
                // durable-debt retry whose original owner survived the commit.
                ManualCompactionOperationRegistry.releaseSubmission(
                    snapshot.conversationId,
                    snapshot.token,
                )
            }
            ok
        }
        currentCoroutineContext().ensureActive()
        return reconciled
    }

    suspend fun clearDraft(conversationId: Long): Boolean {
        // P16-04: the removed ownership is captured by the serialized consumer,
        // never snapshotted before the command. A submission admitted between a
        // stale read and the actual clear is still released exactly, and a
        // cancelled acknowledgement cannot strand a committed clear's leases.
        val capture = withContext(NonCancellable) {
            draftMachine.clearDraftCapturing(conversationId)
        }
        if (capture.cleared) {
            withContext(NonCancellable) {
                capture.removed?.activeSubmissionToken?.let { token ->
                    ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
                }
                capture.removed?.importOwnership?.token?.let { token ->
                    ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        return capture.cleared
    }

    companion object {
        const val NEW_CONVERSATION_ID = 0L
        private const val CURRENT_CONVERSATION_KEY = "current_conversation_id"
        private const val PENDING_PROJECT_NEW_CHAT_KEY = "pending_project_new_chat_id"
        private const val NEW_CHAT_EPOCH_KEY = "new_chat_epoch"
        private const val SELECTION_SCOPE_CONVERSATION_KEY = "selection_scope_conversation_id"

        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras,
                ): T {
                    return ChatViewModel(
                        savedStateHandle = extras.createSavedStateHandle(),
                        context = context.applicationContext,
                    ) as T
                }
            }

        internal fun forTesting(store: PersistentComposerDraftStore): ChatViewModel =
            ChatViewModel(draftStoreOverride = store)
    }
}
