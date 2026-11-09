package com.projectnuke.fusion.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.chat.AssistantShareBuildResult
import com.projectnuke.fusion.chat.AssistantShareLocalSource
import com.projectnuke.fusion.chat.AssistantSharePayloadPolicy
import com.projectnuke.fusion.chat.AssistantShareWebSource
import com.projectnuke.fusion.chat.toAssistantShareInput
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.search.parseSearchSourcesMetadata
import com.projectnuke.fusion.trust.TrustRoutingMetadataCodec
import com.projectnuke.fusion.trust.TrustRoutingPresentationKind
import com.projectnuke.fusion.trust.TrustRoutingPresentationMapper
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors
import com.projectnuke.fusion.util.stripHiddenMetadataEnvelopes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ResponseRegenerationAction(
    val menuLabel: String,
    val instruction: String?
) {
    Retry("답변 다시 생성", null),
    Shorter("더 짧게", "이전 답변의 핵심 내용을 유지하면서 더 짧고 간결하게 다시 작성해 주세요."),
    MoreDetailed("더 자세히", "이전 답변의 핵심 내용을 유지하면서 더 자세히 풀어서 설명해 주세요."),
    Table("표로 정리", "이전 답변의 핵심 내용을 표로 정리해 주세요. 필요한 경우 짧은 요약도 함께 포함해 주세요."),
    ExpertTone("전문가 톤", "이전 답변의 핵심 내용을 유지하면서 더 전문적이고 정확한 톤으로 다시 작성해 주세요.")
}


@Composable
internal fun AssistantMessage(
    content: String,
    evidenceMessage: MessageEntity? = null,
    evidenceStore: EvidenceUiStore? = null,
    evidenceVerifier: EvidenceUiVerifier? = null,
    evidenceRevision: com.projectnuke.fusion.data.MessageEvidenceRevision? = null,
    createdAt: Long,
    generationModeKey: String? = null,
    webSearchEnabled: Boolean,
    showActions: Boolean = true,
    isRegenerating: Boolean = false,
    regenerationStatus: String? = null,
    versionIndex: Int = 0,
    versionCount: Int = 1,
    onPreviousVersion: () -> Unit = {},
    onNextVersion: () -> Unit = {},
    onRetry: () -> Unit,
    onRegenerate: (ResponseRegenerationAction) -> Unit,
    onToggleWebSearch: () -> Unit,
    onBranch: () -> Unit = {},
    onOpenInTranslation: () -> Unit = {},
    isSpeaking: Boolean = false,
    onReadAloud: () -> Unit = {},
    cloudEscalationOffer: CloudEscalationOffer? = null,
    onCloudSuggestAction: () -> Unit = {},
    searchRecovery: com.projectnuke.fusion.trust.TrustRecoveryAction? = null,
    onSearchRetry: () -> Unit = {},
    searchRetryLabel: String = "다시 검색",
    translationCoordinator: com.projectnuke.fusion.translation.LocalTranslationCoordinator? = null,
    showNextActions: Boolean = false,
    isProjectConversation: Boolean = false,
    projectFileCount: Int = 0,
    isStudyMode: Boolean = false,
    nextActionsEnabled: Boolean = true,
    onNextActionAppend: (String) -> Unit = {},
    onEnterStudyMode: () -> Unit = {},
    // Next Action Flow v1B secondary actions: conversation/translation/project
    // seams needed by the "더 보기" action sheet.
    conversationId: Long = 0L,
    translationAvailable: Boolean = true,
    onOpenProjectPicker: () -> Unit = {},
    onSaveKnowledge: (String) -> Unit = {},
    onCreateProjectTask: (String) -> Unit = {},
    onCreateReviewCard: (String) -> Unit = {},
    onSaveAsDecision: (String) -> Unit,
    onResearchPack: ((String) -> Unit)? = null,
    onResearchRefresh: ((String) -> Unit)? = null,
    onUseAsWorkPlan: ((String) -> Unit)? = null,
    compareGroup: ChatTimelineItem.AssistantVersions? = null,
    dynamicFollowUps: List<String> = emptyList(),
    reasoningTags: Set<String> = setOf("fusion_thinking"),
    isStreaming: Boolean = false,
    reasoningIdentity: Any? = null,
    archiveAccessIncluded: Boolean = false,
    extractionProjectId: Long? = null,
    extractionProjectName: String? = null,
    researchInterventionAudit: com.projectnuke.fusion.audit.ResearchInterventionAuditRecord? = null,
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val semanticColors = LocalFusionSemanticColors.current
    var showSources by remember { mutableStateOf(false) }
    var showLocalSources by remember { mutableStateOf(false) }
    // Next Action Flow v1A: per-bubble reveal toggle for the EXISTING source
    // presentation. Purely presentation state; revealing never performs
    // network/retrieval/parsing/generation.
    // Next Action Flow v1C corrective (PHASE A): presentation state is keyed
    // by the EXACT answer-surface identity (conversationId + assistantMessageId
    // + canonical content hash), never by visible text alone — so a response
    // version or conversation change resets even when content strings are
    // identical. No durable persistence.
    val answerSurfaceIdentity = remember(evidenceMessage, conversationId) {
        com.projectnuke.fusion.ui.AnswerSurfaceIdentity.of(conversationId, evidenceMessage)
    }
    val currentAnswerSurfaceIdentity by rememberUpdatedState(answerSurfaceIdentity)
    val extractionScope = rememberCoroutineScope()
    val extractionNoteRepository = remember(context.applicationContext) {
        com.projectnuke.fusion.knowledge.SavedKnowledgeNoteRepository(
            com.projectnuke.fusion.data.AppDatabase.getInstance(context.applicationContext),
        )
    }
    val checkpointRepository = remember(context.applicationContext) {
        com.projectnuke.fusion.data.ConversationCheckpointRepository(
            com.projectnuke.fusion.data.AppDatabase.getInstance(context.applicationContext),
        )
    }
    var extractionNoteSaving by remember(answerSurfaceIdentity) { mutableStateOf(false) }
    var extractionNoteError by remember(answerSurfaceIdentity) { mutableStateOf<String?>(null) }
    var moreExpanded by remember(answerSurfaceIdentity) { mutableStateOf(false) }
    var sourcesRevealed by remember(answerSurfaceIdentity) { mutableStateOf(false) }
    // v1B: "답변 활용" secondary action sheet state, keyed to the exact
    // answer-surface identity so response-version/conversation switch or New
    // Chat closes it and can never act on a stale context.
    var secondarySheetOpen by remember(answerSurfaceIdentity) { mutableStateOf(false) }
    var secondarySheetExpectedIdentity by remember(answerSurfaceIdentity) {
        mutableStateOf<AnswerSurfaceIdentity?>(null)
    }
    var secondarySheetExpectedGroupId by remember(answerSurfaceIdentity) {
        mutableStateOf<Long?>(null)
    }
    var structuredExtractionSource by remember(answerSurfaceIdentity) {
        mutableStateOf<StructuredExtractionSource?>(null)
    }
    var evidenceOpenTrigger by remember(answerSurfaceIdentity) { mutableStateOf(0L) }
    // UX-P2A read-only entry trigger. Distinct from evidenceOpenTrigger because
    // it opens the read-only saved-claims explorer and never the verification path.
    var savedClaimsOpenTrigger by remember(answerSurfaceIdentity) { mutableStateOf(0L) }
    // Checkpoint capture drafts survive Activity recreation in-memory (U02).
    val db = com.projectnuke.fusion.data.AppDatabase.getInstance(context.applicationContext)
    val creationDrafts: CreationDraftsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val extractionNoteDraft = creationDrafts.researchNoteDraft
    // v1C: Answer Context Inspector. The receipt is immutable provenance bound
    // to THIS persisted answer/version; parsing the current message's content
    // means switching response versions resolves the new version's receipt and
    // a version without a receipt yields none (no fabricated context).
    val contextReceipt = remember(content) {
        com.projectnuke.fusion.chat.AnswerContextReceipt.parse(content)
    }
    var showContextInspector by remember(answerSurfaceIdentity) { mutableStateOf(false) }
    var contextInspectorIdentity by remember(answerSurfaceIdentity) {
        mutableStateOf<AnswerSurfaceIdentity?>(null)
    }
    val searchSources = remember(content) {
        parseSearchSourcesMetadata(content)
    }
    // C4 local attachment provenance: filename/chunk identity separate from
    // web URLs, never a verification claim. Hoisted early so the v1A answer
    // utility rail and source reveal share the same already-parsed source set.
    val localRagSources = remember(content) {
        com.projectnuke.fusion.retrieval.LocalRagSourceMetadata.parse(content)
    }
    val trustRoutingDecision = remember(content) {
        TrustRoutingMetadataCodec.decode(content)
    }
    val routingPresentation = remember(trustRoutingDecision, generationModeKey) {
        TrustRoutingPresentationMapper.map(trustRoutingDecision, generationModeKey)
    }
    val visibleContent = remember(content) { stripHiddenMetadataEnvelopes(content) }
    val metricsSplit = remember(visibleContent) {
        splitFusionMetrics(visibleContent)
    }
    val effectiveReasoningTags = if (isStreaming) reasoningTags else setOf("fusion_thinking")
    val parsed = remember(metricsSplit.content, effectiveReasoningTags) {
        parseAssistantOutput(metricsSplit.content, reasoningEnabled = true, reasoningTags = effectiveReasoningTags)
    }
    // Rendered answer and handoff seed share one path by construction.
    val visibleAnswer = remember(content, generationModeKey) {
        assistantVisibleAnswerForHandoff(content, reasoningEnabled = true, generationModeKey)
    }

    // Next Action Flow v1A - answer utility rail eligibility. Pure decision
    // layer: which DIRECT actions (사용한 자료 / 사실 확인 / 이 주제로 공부)
    // belong to THIS active visible answer. Bound to the leader gate and the
    // typed eligibility policy exactly like the follow-up row; never consumes
    // the max-3 question capacity. Actions bind to the active message/version
    // (this bubble instance), not to list position.
    val evAvailable = canOfferPostAnswerFactVerification(
        message = evidenceMessage,
        visibleAnswerAvailable = parsed.answer.isNotBlank(),
        evidenceStoreAvailable = evidenceStore != null,
        verifierAvailable = evidenceVerifier != null,
        translationSurfaceAvailable = translationCoordinator != null,
        isStreaming = isStreaming,
        isRegenerating = isRegenerating,
    )
    val railContext = com.projectnuke.fusion.chat.AnswerUtilityRailContext(
        routingKind = routingPresentation.kind,
        isRetrySearchRecovery =
            searchRecovery is com.projectnuke.fusion.trust.TrustRecoveryAction.RetrySearch,
        isStudyMode = isStudyMode,
        hasWebSources = searchSources.isNotEmpty(),
        hasLocalRagSources = localRagSources.isNotEmpty(),
        evidenceAvailable = evAvailable,
    )
    val railActions = remember(railContext, showNextActions, visibleAnswer) {
        if (showNextActions && visibleAnswer.isNotBlank()) {
            com.projectnuke.fusion.chat.AnswerUtilityRailPolicy.actions(railContext)
        } else {
            emptyList()
        }
    }
    val railShowsSources = com.projectnuke.fusion.chat.AnswerUtilityAction.SOURCES in railActions
    val railShowsEvidence = com.projectnuke.fusion.chat.AnswerUtilityAction.EVIDENCE in railActions
    // UX-P2A: the READ-ONLY saved-claims explorer is offered from the single
    // message overflow for any eligible assistant answer that has an evidence
    // store. It is clearly separate from the rail's "사실 확인" verification
    // entry and shares no verifier/search/planner ownership.
    val savedClaimsAvailable = canOfferSavedClaimEvidence(evidenceMessage, evidenceStore != null)
    // Single-ownership presentation split from the ONE policy (pure projections,
    // no inline branching): the compact rail renders only PRIMARY analysis
    // actions; OVERFLOW actions (Study, ...) are owned by the single message
    // overflow (⋮). No action is rendered twice.
    val primaryRailActions = remember(railActions) {
        com.projectnuke.fusion.chat.AnswerUtilityRailPolicy.primaryActionsOf(railActions)
    }
    val overflowRailActions = remember(railActions) {
        com.projectnuke.fusion.chat.AnswerUtilityRailPolicy.overflowActionsOf(railActions)
    }
    // Source presentation is already-parsed; the rail only reveals/toggles it.
    val sourcesHiddenByRail = railShowsSources && !sourcesRevealed

    // Next Action Flow v1B - "더 보기" secondary actions. Pure decision layer
    // fed ONLY by typed/scalar live state (conversation persistence, project
    // assignment, response-version count, canonical-answer presence). The
    // sheet is a separate product layer from the question row and the primary
    // rail; it never consumes the follow-up max-3 capacity. Recomputed every
    // composition so the sheet resolves against CURRENT live state and fails
    // closed when a target disappears (deleted version/conversation, New Chat).
    val secondaryContext = com.projectnuke.fusion.chat.AnswerSecondaryActionContext(
        persistedConversation = conversationId > 0L,
        isProjectAssigned = isProjectConversation,
        responseVersionCount = versionCount,
        visibleAnswerAvailable = visibleAnswer.isNotBlank(),
        translationAvailable = translationAvailable,
        receiptAvailable = contextReceipt != null,
        routingKind = routingPresentation.kind,
        isStudyMode = isStudyMode,
        isRetrySearchRecovery =
            searchRecovery is com.projectnuke.fusion.trust.TrustRecoveryAction.RetrySearch,
        answerStableForExtraction = !isStreaming && !isRegenerating,
    )
    val secondaryActions = remember(secondaryContext, showNextActions) {
        if (showNextActions && visibleAnswer.isNotBlank()) {
            com.projectnuke.fusion.chat.AnswerSecondaryActionPolicy.actions(secondaryContext)
        } else {
            buildList {
                if (com.projectnuke.fusion.chat.AnswerSecondaryActionPolicy.canSaveKnowledge(secondaryContext)) {
                    add(com.projectnuke.fusion.chat.AnswerSecondaryAction.SAVE_TO_KNOWLEDGE)
                }
                if (com.projectnuke.fusion.chat.AnswerSecondaryActionPolicy.canCreateProjectTask(secondaryContext)) {
                    add(com.projectnuke.fusion.chat.AnswerSecondaryAction.CREATE_PROJECT_TASK)
                }
                if (com.projectnuke.fusion.chat.AnswerSecondaryActionPolicy.canCreateReviewCard(secondaryContext)) {
                    add(com.projectnuke.fusion.chat.AnswerSecondaryAction.CREATE_REVIEW_CARD)
                }
                if (com.projectnuke.fusion.chat.AnswerSecondaryActionPolicy.canSaveCheckpoint(secondaryContext)) {
                    add(com.projectnuke.fusion.chat.AnswerSecondaryAction.SAVE_CHECKPOINT)
                }
                if (com.projectnuke.fusion.chat.AnswerSecondaryActionPolicy.canExtractStructured(secondaryContext)) {
                    add(com.projectnuke.fusion.chat.AnswerSecondaryAction.EXTRACT_STRUCTURED)
                }
            }
        }
    }
    val suppressLegacyTranslation =
        com.projectnuke.fusion.chat.AnswerSecondaryActionPolicy.shouldSuppressLegacyDuplicate(
            com.projectnuke.fusion.chat.AnswerSecondaryAction.OPEN_IN_TRANSLATION,
            secondaryActions,
        )
    val suppressLegacyCompare =
        com.projectnuke.fusion.chat.AnswerSecondaryActionPolicy.shouldSuppressLegacyDuplicate(
            com.projectnuke.fusion.chat.AnswerSecondaryAction.COMPARE_RESPONSES,
            secondaryActions,
        )
    // Response Version Compare v1: transient presentation-only surface.
    // Candidates come from the authoritative versionGroup.versions and are
    // mapped through the same canonical visible-answer authority the bubble
    // renders; blank versions are unusable. The dialog freezes the live
    // snapshot on open and never live-follows later Room or navigation
    // changes; open/select/close performs zero durable response-version
    // writes.
    val compareEntries = remember(compareGroup?.versions, generationModeKey) {
        compareGroup?.versions?.mapIndexedNotNull { index, version ->
            val answer = assistantVisibleAnswerForHandoff(
                version.content,
                reasoningEnabled = true,
                generationModeKey,
            )
            if (answer.isBlank()) null
            else com.projectnuke.fusion.chat.ResponseVersionCompareEntry(
                messageId = version.id,
                ordinal = index + 1,
                visibleAnswer = answer,
            )
        }.orEmpty()
    }
    val compareLive = remember(compareEntries, compareGroup) {
        val group = compareGroup ?: return@remember null
        com.projectnuke.fusion.chat.ResponseVersionComparePolicy.snapshot(
            compareEntries,
            group.activeMessage.id,
            group.versions.size,
        )
    }
    val compareAvailable = compareLive != null && !isRegenerating
    val contextualActionSuggestion = remember(
        secondaryContext,
        secondaryActions,
        visibleAnswer,
        contextReceipt,
        onResearchRefresh,
        compareAvailable,
        showNextActions,
    ) {
        if (!showNextActions || !com.projectnuke.fusion.chat.NextActionEligibilityPolicy.isEligible(
                routingKind = routingPresentation.kind,
                isRetrySearchRecovery = searchRecovery is com.projectnuke.fusion.trust.TrustRecoveryAction.RetrySearch,
                isStudyMode = isStudyMode,
            )
        ) {
            null
        } else {
            val availableActions = secondaryActions.toMutableSet().apply {
                if (!compareAvailable) remove(com.projectnuke.fusion.chat.AnswerSecondaryAction.COMPARE_RESPONSES)
            }
            com.projectnuke.fusion.chat.ContextualAnswerActionSuggestionPolicy.suggest(
                com.projectnuke.fusion.chat.ContextualAnswerActionContext(
                    availableActions = availableActions,
                    isResearchResult = contextReceipt?.interactionMode == "RESEARCH",
                    researchRefreshAvailable = onResearchRefresh != null,
                    answerLooksTabular = com.projectnuke.fusion.chat.NextActionSuggestionPolicy.looksTabular(visibleAnswer),
                    hasAnswerReceipt = contextReceipt != null,
                ),
            )
        }
    }
    // Single-ownership promotion (pure helper, no inline branching): an action
    // promoted to the compact rail is removed from the overflow sheet for THIS
    // answer, so the same capability is never offered by two surfaces.
    // RESEARCH_REFRESH maps to no secondary action (its own overflow row is
    // suppressed below instead).
    val displaySecondaryActions = remember(secondaryActions, contextualActionSuggestion) {
        com.projectnuke.fusion.chat.ContextualAnswerActionSuggestionPolicy
            .remainingSecondaryActions(secondaryActions, contextualActionSuggestion)
    }
    var compareFrozen by remember(answerSurfaceIdentity, compareGroup?.groupId, compareGroup?.versions?.map { it.id }) {
        mutableStateOf<com.projectnuke.fusion.chat.ResponseVersionCompareSnapshot?>(null)
    }
    val openSecondarySheet: () -> Unit = {
        secondarySheetExpectedIdentity = answerSurfaceIdentity
        secondarySheetExpectedGroupId = compareGroup?.groupId
        secondarySheetOpen = true
    }
    val runContextualActionSuggestion: () -> Unit = {
        val expectedIdentity = answerSurfaceIdentity
        val expectedGroupId = compareGroup?.groupId
        when (contextualActionSuggestion) {
            com.projectnuke.fusion.chat.ContextualAnswerActionSuggestion.RESEARCH_REFRESH -> {
                if (contextReceipt?.interactionMode == "RESEARCH") onResearchRefresh?.invoke(visibleAnswer)
            }
            com.projectnuke.fusion.chat.ContextualAnswerActionSuggestion.EXTRACT_STRUCTURED -> {
                if (com.projectnuke.fusion.chat.AnswerSecondaryActionPolicy.canExtractStructured(secondaryContext) &&
                    (evidenceMessage?.id ?: 0L) > 0L
                ) {
                    structuredExtractionSource = StructuredExtractionSource(
                        title = if (contextReceipt?.interactionMode == "RESEARCH") "조사 답변" else "답변",
                        kind = if (contextReceipt?.interactionMode == "RESEARCH") "Research 결과" else "대화 답변",
                        reference = "conversation:$conversationId/message:${evidenceMessage?.id}",
                        text = visibleAnswer.take(STRUCTURED_EXTRACTION_MAX_SOURCE_CHARS),
                        durableIdentity = com.projectnuke.fusion.chat.SelectionSourceIdentity(
                            com.projectnuke.fusion.chat.SelectionSourceKind.CONVERSATION,
                            conversationId = conversationId,
                        ),
                    )
                }
            }
            com.projectnuke.fusion.chat.ContextualAnswerActionSuggestion.COMPARE_RESPONSES -> {
                if (com.projectnuke.fusion.ui.AnswerSurfaceIdentityFence.canOpenCompareSurface(
                        liveIdentity = answerSurfaceIdentity,
                        expectedIdentity = expectedIdentity,
                        liveResponseVersionGroupId = compareGroup?.groupId,
                        expectedResponseVersionGroupId = expectedGroupId,
                        responseVersionCount = versionCount,
                        liveSnapshotAvailable = compareLive != null,
                    )
                ) compareFrozen = compareLive
            }
            com.projectnuke.fusion.chat.ContextualAnswerActionSuggestion.SAVE_TO_KNOWLEDGE -> {
                if (expectedIdentity == answerSurfaceIdentity && conversationId > 0L && visibleAnswer.isNotBlank() &&
                    com.projectnuke.fusion.chat.AnswerSecondaryActionPolicy.canSaveKnowledge(secondaryContext)
                ) onSaveKnowledge(visibleAnswer)
            }
            null -> Unit
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        parsed.thinking?.takeIf { it.isNotBlank() }?.let { thinking ->
            ReasoningPanel(
                thinking = thinking,
                isLive = isStreaming,
                identity = reasoningIdentity ?: answerSurfaceIdentity,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (evidenceMessage != null && evidenceStore != null && evidenceVerifier != null &&
            evidenceMessage.role == "assistant" && evidenceMessage.id > 0L &&
            !isStreaming && !isRegenerating
        ) {
            SelectedAnswerTextVerificationSurface(
                message = evidenceMessage,
                visibleAnswer = visibleAnswer,
                store = evidenceStore,
                verifier = evidenceVerifier,
                revision = evidenceRevision,
                expectedSurfaceIdentity = answerSurfaceIdentity,
                style = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 29.sp),
                color = semanticColors.onSurface,
                onOpenUrl = { url -> if (isEvidenceWebUrl(url)) openModelLink(context, url) },
            )
        } else if (evidenceMessage != null && evidenceStore != null) {
            EvidenceAnswerText(
                message = evidenceMessage,
                visibleAnswer = visibleAnswer,
                store = evidenceStore,
                revision = evidenceRevision,
                style = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 29.sp),
                color = semanticColors.onSurface,
                onOpenUrl = { url -> if (isEvidenceWebUrl(url)) openModelLink(context, url) },
            )
        } else {
            AssistantMarkdownContent(
                visibleAnswer = visibleAnswer,
                modifier = Modifier.fillMaxWidth(),
                style = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 29.sp),
                color = semanticColors.onSurface,
                onOpenLink = { url ->
                    if (isSafeLinkUrl(url)) {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }.onFailure {
                            // Ignore unsafe/dangerous link launches
                        }
                    }
                }
            )
        }

        if (routingPresentation.isVisible &&
            routingPresentation.kind != TrustRoutingPresentationKind.ABSTAIN &&
            routingPresentation.label != null
        ) {
            TrustRoutingExplanation(presentation = routingPresentation)
        }

        val boundResearchInterventionAudit = bindResearchInterventionAudit(
            record = researchInterventionAudit,
            message = evidenceMessage,
            conversationId = conversationId,
            visibleAnswer = visibleAnswer,
        )
        if (boundResearchInterventionAudit != null) {
            Spacer(modifier = Modifier.height(4.dp))
            ResearchInterventionAuditSurface(
                record = boundResearchInterventionAudit,
                onOpenUrl = { url -> if (isEvidenceWebUrl(url)) openModelLink(context, url) },
            )
        }

        // C2-D small secondary action for a visible CLOUD_SUGGEST presentation:
        // local answer untouched, escalation is an explicit separate action.
        val cloudOffer = if (routingPresentation.kind == TrustRoutingPresentationKind.CLOUD_SUGGESTION &&
            routingPresentation.isVisible
        ) cloudEscalationOffer else null
        if (cloudOffer is CloudEscalationOffer.Shown) {
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                onClick = onCloudSuggestAction,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text(
                    text = cloudOffer.label,
                    color = semanticColors.primary,
                    fontSize = 12.sp,
                )
            }
        } else if (cloudOffer is CloudEscalationOffer.Blocked) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = cloudOffer.notice,
                color = semanticColors.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }

        // C2-E explicit bounded retry affordance bound to the failed SEARCH
        // attempt. No automatic fallback generation is offered.
        if (searchRecovery is com.projectnuke.fusion.trust.TrustRecoveryAction.RetrySearch) {
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                onClick = onSearchRetry,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text(
                    text = searchRetryLabel,
                    color = semanticColors.primary,
                    fontSize = 12.sp,
                )
            }
        }

        if (searchSources.isNotEmpty() && !sourcesHiddenByRail) {
            Spacer(modifier = Modifier.height(10.dp))
            SearchSourceCapsules(
                sources = searchSources,
                onClick = { showSources = true }
            )
        }

        // C4 local attachment provenance: filename/chunk identity separate
        // from web URLs, never a verification claim. When the v1A rail owns
        // the "사용한 자료" reveal, the already-parsed capsules stay hidden
        // until the user reveals them (pure presentation toggle, zero IO).
        if (localRagSources.isNotEmpty() && !sourcesHiddenByRail) {
            Spacer(modifier = Modifier.height(10.dp))
            LocalRagSourceCapsules(
                sources = localRagSources,
                onClick = { showLocalSources = true }
            )
        }
        if (showLocalSources) {
            LocalRagSourcesDialog(
                sources = localRagSources,
                onDismiss = { showLocalSources = false }
            )
        }

        if (isRegenerating) {
            Spacer(modifier = Modifier.height(8.dp))
            if (regenerationStatus != null) {
                Text(
                    text = regenerationStatus,
                    color = semanticColors.primary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            } else {
            Text(
                text = "답변을 다시 생성하는 중입니다.",
                color = semanticColors.primary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            }
        }

        if (showActions) {
        if (versionCount > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ActionIcon(
                    icon = Icons.Rounded.ChevronLeft,
                    contentDescription = "이전 답변",
                    enabled = versionIndex > 0,
                    onClick = onPreviousVersion
                )
                Text(
                    text = "${versionIndex + 1} / $versionCount",
                    color = semanticColors.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                ActionIcon(
                    icon = Icons.Rounded.ChevronRight,
                    contentDescription = "다음 답변",
                    enabled = versionIndex < versionCount - 1,
                    onClick = onNextVersion
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        metricsSplit.metricsLine?.let { metricsLine ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = metricsLine,
                color = semanticColors.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Evidence-aware answer share: computed only from the already-owned
        // visible answer and displayed provenance. Opening the menu triggers
        // no search, verification, embedding, parsing or generation.
        val moreAnchor = rememberFusionPopupAnchor()
        val shareWebInputs = remember(searchSources) {
            searchSources.map { it.toAssistantShareInput() }
        }
        val shareLocalInputs = remember(localRagSources) {
            localRagSources.map { it.toAssistantShareInput() }
        }
        val shareAvailability = remember(visibleAnswer, shareWebInputs, shareLocalInputs) {
            AssistantSharePayloadPolicy.resolveAvailability(
                visibleAnswer,
                shareWebInputs,
                shareLocalInputs,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionIcon(
                icon = Icons.Rounded.ContentCopy,
                contentDescription = "복사",
                onClick = {
                    clipboardManager.setText(AnnotatedString(visibleAnswer))
                    Toast.makeText(context, "복사했습니다.", Toast.LENGTH_SHORT).show()
                }
            )

            // Conditional, speaking-only stop control. It reuses the existing
            // TTS state (isSpeaking) and the existing read-aloud callback, adds
            // no new playback owner, and vanishes the moment speech stops. The
            // regular read-aloud action still lives in the overflow (⋮).
            SpeakingStopAction(isSpeaking = isSpeaking, onStop = onReadAloud)

            // Read-aloud is owned by the single message overflow (⋮); no
            // duplicate inline control is rendered for the same answer.
            Box(Modifier.fusionPopupAnchor(moreAnchor)) {
                ActionIcon(
                    icon = Icons.Rounded.MoreVert,
                    contentDescription = RAIL_MORE_LABEL,
                    onClick = {
                        openSecondarySheet()
                        moreExpanded = true
                    }
                )

                AssistantMoreMenu(
                    expanded = moreExpanded,
                    anchor = moreAnchor,
                    onDismiss = { moreExpanded = false; secondarySheetOpen = false; secondarySheetExpectedIdentity = null; secondarySheetExpectedGroupId = null },
                    onReadAloud = { moreExpanded = false; onReadAloud() },
                    isSpeaking = isSpeaking,
                    onShareAnswer = if (shareAvailability.canShareAnswer) ({ moreExpanded = false; shareAssistantAnswerOnly(context, visibleAnswer) }) else null,
                    onShareSources = if (shareAvailability.canShareWithSources) ({ moreExpanded = false; shareAssistantAnswerWithSources(context, visibleAnswer, shareWebInputs, shareLocalInputs) }) else null,
                    onResearchPack = if (contextReceipt?.interactionMode == "RESEARCH") ({
                        moreExpanded = false
                        onResearchPack?.invoke(visibleAnswer)
                    }) else null,
                    onUseAsWorkPlan = onUseAsWorkPlan?.let { action ->
                        {
                            moreExpanded = false
                            action(visibleAnswer)
                        }
                    },
                    onOpenSavedClaims = if (savedClaimsAvailable) {
                        {
                            moreExpanded = false
                            savedClaimsOpenTrigger++
                        }
                    } else {
                        null
                    },
                    extraActions = {
                        AnswerSecondaryActionSheet(
                            expanded = secondarySheetOpen,
                            inline = true,
                            onDismiss = {
                                moreExpanded = false
                                secondarySheetOpen = false
                                secondarySheetExpectedIdentity = null
                                secondarySheetExpectedGroupId = null
                            },
                            actions = displaySecondaryActions,
                            context = secondaryContext,
                            compareLiveAvailable = compareLive != null,
                            onProject = {
                                moreExpanded = false
                                secondarySheetOpen = false
                                val expectedIdentity = secondarySheetExpectedIdentity
                                secondarySheetExpectedIdentity = null
                                secondarySheetExpectedGroupId = null
                                // The expected identity was frozen when this sheet
                                // opened. Stale -> close and mutate nothing; the
                                // existing picker remains the only project owner.
                                if (canExecuteProjectPickerAction(
                                        liveIdentity = answerSurfaceIdentity,
                                        expectedIdentity = expectedIdentity,
                                        conversationPersisted = conversationId > 0L,
                                    )
                                ) {
                                    onOpenProjectPicker()
                                }
                            },
                            onCompare = {
                                moreExpanded = false
                                secondarySheetOpen = false
                                val expectedIdentity = secondarySheetExpectedIdentity
                                val expectedGroupId = secondarySheetExpectedGroupId
                                secondarySheetExpectedIdentity = null
                                secondarySheetExpectedGroupId = null
                                if (AnswerSurfaceIdentityFence.canOpenCompareSurface(
                                        liveIdentity = answerSurfaceIdentity,
                                        expectedIdentity = expectedIdentity,
                                        liveResponseVersionGroupId = compareGroup?.groupId,
                                        expectedResponseVersionGroupId = expectedGroupId,
                                        responseVersionCount = versionCount,
                                        liveSnapshotAvailable = compareLive != null,
                                    )
                                ) {
                                    compareFrozen = compareLive
                                }
                            },
                            onTranslation = {
                                moreExpanded = false
                                secondarySheetOpen = false
                                onOpenInTranslation()
                            },
                            onRelatedMaterials = {
                                moreExpanded = false
                                secondarySheetOpen = false
                                onNextActionAppend(
                                    com.projectnuke.fusion.chat.AnswerSecondaryActionPolicy.relatedMaterialsPrompt(),
                                )
                            },
                            onStructuredExtraction = {
                                moreExpanded = false
                                secondarySheetOpen = false
                                val expectedIdentity = secondarySheetExpectedIdentity
                                secondarySheetExpectedIdentity = null
                                secondarySheetExpectedGroupId = null
                                if (expectedIdentity == answerSurfaceIdentity &&
                                    com.projectnuke.fusion.chat.AnswerSecondaryActionPolicy.canExtractStructured(secondaryContext) &&
                                    (evidenceMessage?.id ?: 0L) > 0L
                                ) {
                                    val isResearchResult = contextReceipt?.interactionMode == "RESEARCH"
                                    structuredExtractionSource = StructuredExtractionSource(
                                        title = if (isResearchResult) "조사 답변" else "답변",
                                        kind = if (isResearchResult) "Research 결과" else "대화 답변",
                                        reference = "conversation:$conversationId/message:${evidenceMessage?.id}",
                                        text = visibleAnswer.take(STRUCTURED_EXTRACTION_MAX_SOURCE_CHARS),
                                        durableIdentity = com.projectnuke.fusion.chat.SelectionSourceIdentity(
                                            com.projectnuke.fusion.chat.SelectionSourceKind.CONVERSATION,
                                            conversationId = conversationId,
                                        ),
                                    )
                                }
                            },
                            onViewContext = {
                                moreExpanded = false
                                secondarySheetOpen = false
                                secondarySheetExpectedIdentity = null
                                secondarySheetExpectedGroupId = null
                                if (contextReceipt != null) {
                                    contextInspectorIdentity = answerSurfaceIdentity
                                    showContextInspector = true
                                }
                            },
                            onSaveKnowledge = {
                                moreExpanded = false
                                secondarySheetOpen = false
                                val expectedIdentity = secondarySheetExpectedIdentity
                                secondarySheetExpectedIdentity = null
                                secondarySheetExpectedGroupId = null
                                if (expectedIdentity == answerSurfaceIdentity && conversationId > 0L && visibleAnswer.isNotBlank()) {
                                    onSaveKnowledge(visibleAnswer)
                                }
                            },
                            onCreateProjectTask = {
                                moreExpanded = false
                                secondarySheetOpen = false
                                val expectedIdentity = secondarySheetExpectedIdentity
                                secondarySheetExpectedIdentity = null
                                secondarySheetExpectedGroupId = null
                                if (expectedIdentity == answerSurfaceIdentity && conversationId > 0L && visibleAnswer.isNotBlank()) {
                                    onCreateProjectTask(visibleAnswer)
                                }
                            },
                            onCreateReviewCard = {
                                moreExpanded = false
                                secondarySheetOpen = false
                                val expectedIdentity = secondarySheetExpectedIdentity
                                secondarySheetExpectedIdentity = null
                                secondarySheetExpectedGroupId = null
                                if (expectedIdentity == answerSurfaceIdentity && conversationId > 0L && visibleAnswer.isNotBlank()) {
                                    onCreateReviewCard(visibleAnswer)
                                }
                            },
                            onSaveAsDecision = {
                                moreExpanded = false
                                secondarySheetOpen = false
                                val expectedIdentity = secondarySheetExpectedIdentity
                                secondarySheetExpectedIdentity = null
                                secondarySheetExpectedGroupId = null
                                if (expectedIdentity == answerSurfaceIdentity && conversationId > 0L && visibleAnswer.isNotBlank()) {
                                    onSaveAsDecision(visibleAnswer)
                                }
                            },
                            onSaveCheckpoint = {
                                moreExpanded = false
                                secondarySheetOpen = false
                                val expectedIdentity = secondarySheetExpectedIdentity
                                secondarySheetExpectedIdentity = null
                                secondarySheetExpectedGroupId = null
                                if (expectedIdentity == answerSurfaceIdentity &&
                                    com.projectnuke.fusion.chat.AnswerSecondaryActionPolicy.canSaveCheckpoint(secondaryContext)
                                ) {
                                    evidenceMessage?.takeIf { it.id > 0L && it.conversationId == conversationId }
                                        ?.let {
                                            creationDrafts.openCheckpointCapture(
                                                it,
                                                com.projectnuke.fusion.chat.defaultCheckpointTitle(visibleAnswer),
                                            )
                                        }
                                }
                            },
                        )
                        // Study is OVERFLOW-tier (this single message overflow
                        // owns it) and the research-refresh row is owned by the
                        // promoted rail suggestion when present. Both decisions
                        // are pure policies; the items render in a dedicated
                        // composable so this giant surface adds no inline
                        // branching of its own.
                        AssistantOverflowExtraItems(
                            showStudy = com.projectnuke.fusion.chat.AnswerUtilityAction.STUDY in overflowRailActions,
                            enabled = nextActionsEnabled,
                            onStudy = {
                                moreExpanded = false
                                onEnterStudyMode()
                            },
                            showResearchRefresh = com.projectnuke.fusion.chat.ContextualAnswerActionSuggestionPolicy
                                .researchRefreshOwnedByOverflow(contextualActionSuggestion, onResearchRefresh != null),
                            onResearchRefresh = {
                                moreExpanded = false
                                secondarySheetOpen = false
                                onResearchRefresh?.invoke(visibleAnswer)
                            },
                        )
                    },
                    createdAt = createdAt,
                    historicalModel = historicalAnswerModel(content),
                    webSearchEnabled = webSearchEnabled,
                    isRegenerating = isRegenerating,
                    onRetry = {
                        moreExpanded = false
                        onRetry()
                    },
                    onBranch = {
                        moreExpanded = false
                        onBranch()
                    },
                    onOpenInTranslation = {
                        moreExpanded = false
                        onOpenInTranslation()
                    },
                    onRegenerate = { action ->
                        moreExpanded = false
                        onRegenerate(action)
                    },
                    onToggleWebSearch = {
                        moreExpanded = false
                        onToggleWebSearch()
                    },
                    showCompare = compareAvailable && !suppressLegacyCompare,
                    onCompare = {
                        moreExpanded = false
                        compareFrozen = compareLive
                    },
                    hideOpenInTranslation = suppressLegacyTranslation,
                )
            }
            // Exactly ONE Evidence ownership host per message. In NORMAL mode
            // the v1A rail owns the "사실 확인" trigger (see below); the legacy
            // standalone action-row trigger is hidden then so two Evidence hosts
            // are never composed for the same message. Everywhere the rail is
            // absent (non-leader bubbles, STUDY, etc.) the standalone trigger
            // stays, preserving Evidence outside the NORMAL rail.
            if (canOfferPostAnswerFactVerification(
                    message = evidenceMessage,
                    visibleAnswerAvailable = parsed.answer.isNotBlank(),
                    evidenceStoreAvailable = evidenceStore != null,
                    verifierAvailable = evidenceVerifier != null,
                    translationSurfaceAvailable = translationCoordinator != null,
                    isStreaming = isStreaming,
                    isRegenerating = isRegenerating,
                ) &&
                !railShowsEvidence
            ) {
                val stableMessage = evidenceMessage
                val stableStore = evidenceStore
                val stableVerifier = evidenceVerifier
                val coordinator = translationCoordinator
                if (stableMessage != null && stableStore != null && stableVerifier != null && coordinator != null) {
                    EvidenceMessageAction(
                        message = stableMessage,
                        store = stableStore,
                        verifier = stableVerifier,
                        translationCoordinator = coordinator,
                        onOpenUrl = { url -> if (isEvidenceWebUrl(url)) openModelLink(context, url) },
                        conversationId = conversationId,
                        openTrigger = evidenceOpenTrigger,
                        expectedSurfaceIdentity = answerSurfaceIdentity,
                        isCurrentAnswer = { currentAnswerSurfaceIdentity == answerSurfaceIdentity },
                    )
                }
            }
        }

        // Next Action Flow v1A - Layer 1: the answer utility rail. Direct
        // actions for the current visible answer, shown for eligible ordinary
        // answers only and strictly separate from the follow-up question layer.
        if (railActions.isNotEmpty() || contextualActionSuggestion != null) {
            Spacer(modifier = Modifier.height(10.dp))
            // Direct provenance actions share one Evidence owner.
            Box {
                if (railShowsEvidence && evidenceMessage?.role == "assistant" &&
                    evidenceStore != null && evidenceVerifier != null && translationCoordinator != null
                ) {
                    EvidenceMessageActionHost(
                        message = evidenceMessage,
                        store = evidenceStore,
                        verifier = evidenceVerifier,
                        translationCoordinator = translationCoordinator,
                        onOpenUrl = { url -> if (isEvidenceWebUrl(url)) openModelLink(context, url) },
                        conversationId = conversationId,
                        openTrigger = evidenceOpenTrigger,
                        expectedSurfaceIdentity = answerSurfaceIdentity,
                        isCurrentAnswer = { currentAnswerSurfaceIdentity == answerSurfaceIdentity },
                    ) { onOpenEvidence ->
                        AnswerUtilityRail(
                            actions = primaryRailActions,
                            enabled = nextActionsEnabled,
                            sourcesRevealed = sourcesRevealed,
                            onToggleSources = { sourcesRevealed = !sourcesRevealed },
                            onStudy = onEnterStudyMode,
                            suggestedAction = contextualActionSuggestion,
                            onSuggestedAction = runContextualActionSuggestion,
                            evidenceClick = onOpenEvidence,
                        )
                    }
                } else {
                    AnswerUtilityRail(
                        actions = primaryRailActions,
                        enabled = nextActionsEnabled,
                        sourcesRevealed = sourcesRevealed,
                        onToggleSources = { sourcesRevealed = !sourcesRevealed },
                        onStudy = onEnterStudyMode,
                        suggestedAction = contextualActionSuggestion,
                        onSuggestedAction = runContextualActionSuggestion,
                        evidenceClick = null,
                    )
                }

            }
        }

        // UX-P2A/P2B: exactly ONE saved-claims explorer host per message. The
        // read-only explorer owner is unchanged; when a verifier is available the
        // host also composes a SEPARATE selected-claim reverification owner for
        // the explicit "이 주장 다시 확인" action (one claim, no persistence).
        val savedClaimsMessage = evidenceMessage
        val savedClaimsStore = evidenceStore
        if (savedClaimsAvailable && savedClaimsMessage != null && savedClaimsStore != null) {
            SavedClaimEvidenceExplorerHost(
                message = savedClaimsMessage,
                store = savedClaimsStore,
                onOpenUrl = { url -> if (isEvidenceWebUrl(url)) openModelLink(context, url) },
                conversationId = conversationId,
                openTrigger = savedClaimsOpenTrigger,
                expectedSurfaceIdentity = answerSurfaceIdentity,
                isCurrentAnswer = { currentAnswerSurfaceIdentity == answerSurfaceIdentity },
                verifier = evidenceVerifier,
                privateTextsProvider = {
                    com.projectnuke.fusion.privacy.DataEgressPrivateTextLedger.currentTexts()
                },
            )
        }

        // v1C Answer Context Inspector: rendered only when THIS answer has a
        // valid immutable receipt; legacy answers simply have no inspector.
        if (showContextInspector && contextInspectorIdentity == answerSurfaceIdentity) {
            contextReceipt?.let { receipt ->
                AnswerContextInspectorDialog(
                    receipt = receipt,
                    onDismiss = {
                        showContextInspector = false
                        contextInspectorIdentity = null
                    },
                )
            }
        }

        // Contextual next actions: derived only from the already-owned
        // canonical visible answer, decoded provenance and project surface
        // state. Shown only for the latest active assistant answer; a tap
        // only seeds composer text via the durable Quick Prompt path.
        // Suppressed for trust abstentions, failed-search recovery and STUDY
        // interaction mode (typed eligibility, never string matching).
        val nextActionsEligible = showNextActions &&
            com.projectnuke.fusion.chat.NextActionEligibilityPolicy.isEligible(
                routingKind = routingPresentation.kind,
                isRetrySearchRecovery =
                    searchRecovery is com.projectnuke.fusion.trust.TrustRecoveryAction.RetrySearch,
                isStudyMode = isStudyMode,
            )
        if (nextActionsEligible && visibleAnswer.isNotBlank()) {
            val nextActionContext = remember(
                visibleAnswer,
                searchSources,
                localRagSources,
                isProjectConversation,
                projectFileCount,
            ) {
                val safeWebCount = searchSources.count { source ->
                    com.projectnuke.fusion.chat.AssistantSharePayloadPolicy.safeWebUrl(source.url) != null
                }
                // Document identity prefers the existing attachmentId (two
                // managed documents may share a basename) and fails safe to
                // the normalized file name. Counts only; no identity enters
                // the pure suggestion context.
                val distinctDocCount =
                    com.projectnuke.fusion.chat.NextActionSuggestionPolicy
                        .countDistinctLocalDocuments(localRagSources)
                com.projectnuke.fusion.chat.NextActionContext(
                    visibleAnswer = visibleAnswer,
                    webSourceCount = safeWebCount,
                    localSourceCount = localRagSources.size,
                    localDocumentCount = distinctDocCount,
                    isProjectConversation = isProjectConversation,
                    projectFileCount = projectFileCount,
                )
            }
            val nextActionSuggestions = remember(nextActionContext) {
                com.projectnuke.fusion.chat.NextActionSuggestionPolicy.suggestions(nextActionContext)
            }
            // Bounded partial composition: dynamic semantic questions first,
            // then the deterministic contextual row fills the remaining
            // slots (max 3 total, deduplicated). Zero dynamic questions
            // leaves the deterministic row unchanged; both kinds tap through
            // the same composer-seeding seam and never auto-send.
            val shownSuggestions = remember(dynamicFollowUps, nextActionSuggestions) {
                com.projectnuke.fusion.chat.DynamicFollowUpPolicy.composeRow(
                    dynamicFollowUps,
                    nextActionSuggestions,
                )
            }
            if (shownSuggestions.isNotEmpty()) {
                NextActionSuggestionsRow(
                    suggestions = shownSuggestions,
                    enabled = nextActionsEnabled,
                    onSelect = { suggestion ->
                        when (val target = suggestion.target) {
                            is com.projectnuke.fusion.chat.NextActionTarget.ComposerPrompt ->
                                onNextActionAppend(target.text)
                            is com.projectnuke.fusion.chat.NextActionTarget.EnterStudyMode ->
                                onEnterStudyMode()
                        }
                    },
                )
            }
        }
        }
    }

    if (showSources) {
        SearchSourcesDialog(
            sources = searchSources,
            onDismiss = { showSources = false },
            translationCoordinator = translationCoordinator,
        )
    }

    // Frozen on open: later Room/version navigation changes never rewrite
    // the visible comparison. Closing discards the snapshot; opening again
    // captures the current group. Selection, copy and dismiss perform zero
    // durable response-version writes.
    compareFrozen?.let { frozen ->
        ResponseVersionCompareDialog(
            snapshot = frozen,
            onSelectComparison = { targetId ->
                com.projectnuke.fusion.chat.ResponseVersionComparePolicy.select(
                    frozen,
                    targetId,
                )?.let { compareFrozen = it }
            },
            onCopySection = { text ->
                clipboardManager.setText(AnnotatedString(text))
                Toast.makeText(context, "복사했습니다.", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { compareFrozen = null },
        )
    }
    StructuredExtractionSurface(
        source = structuredExtractionSource,
        onDismiss = { structuredExtractionSource = null },
        onSaveAsNote = { result ->
            val draft = result.toNoteDraft()
            if (draft == null) {
                Toast.makeText(context, "출처를 보존할 수 없어 노트를 만들지 않았습니다.", Toast.LENGTH_SHORT).show()
            } else {
                structuredExtractionSource = null
                creationDrafts.researchNoteDraft = draft
                extractionNoteError = null
            }
        },
        onAsk = { prompt ->
            structuredExtractionSource = null
            onNextActionAppend(prompt)
        },
        onAddToProject = if (isProjectConversation && extractionProjectId != null) ({ result ->
            val draft = result.toNoteDraft(extractionProjectId, extractionProjectName)
            if (draft == null) {
                Toast.makeText(context, "출처를 보존할 수 없어 노트를 만들지 않았습니다.", Toast.LENGTH_SHORT).show()
            } else {
                structuredExtractionSource = null
                creationDrafts.researchNoteDraft = draft
                extractionNoteError = null
            }
        }        ) else null,
    )
    extractionNoteDraft?.let { draft ->
        SavedKnowledgeNoteEditorSurface(
            draft = draft,
            saving = extractionNoteSaving,
            error = extractionNoteError,
            onTitleChange = { value -> creationDrafts.researchNoteDraft = draft.copy(title = value.take(com.projectnuke.fusion.knowledge.MAX_SAVED_NOTE_TITLE_CHARS)) },
            onBodyChange = { value -> creationDrafts.researchNoteDraft = draft.copy(body = value.take(com.projectnuke.fusion.knowledge.MAX_SAVED_NOTE_BODY_CHARS)) },
            onAddSource = {},
            onRemoveSource = {},
            allowSourceEditing = false,
            onSave = {
                if (extractionNoteSaving) return@SavedKnowledgeNoteEditorSurface
                extractionNoteSaving = true
                extractionNoteError = null
                extractionScope.launch {
                    val saved = try {
                        extractionNoteRepository.save(
                            noteId = draft.id,
                            title = draft.title,
                            body = draft.body,
                            references = draft.references,
                            now = System.currentTimeMillis(),
                            includeArchived = archiveAccessIncluded,
                            projectId = draft.projectId,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    } finally {
                        extractionNoteSaving = false
                    }
                    when (saved) {
                        is com.projectnuke.fusion.knowledge.SavedKnowledgeNoteSaveResult.Saved -> {
                            creationDrafts.researchNoteDraft = null
                            extractionNoteError = null
                            Toast.makeText(context, "연구 노트를 저장했습니다.", Toast.LENGTH_SHORT).show()
                        }
                        com.projectnuke.fusion.knowledge.SavedKnowledgeNoteSaveResult.SourceUnavailable ->
                            extractionNoteError = "출처를 더 이상 열 수 없어 저장하지 않았습니다."
                        com.projectnuke.fusion.knowledge.SavedKnowledgeNoteSaveResult.ProjectUnavailable ->
                            extractionNoteError = "프로젝트가 바뀌어 저장하지 않았습니다."
                        com.projectnuke.fusion.knowledge.SavedKnowledgeNoteSaveResult.NoteUnavailable ->
                            extractionNoteError = "노트가 바뀌어 저장하지 않았습니다."
                        com.projectnuke.fusion.knowledge.SavedKnowledgeNoteSaveResult.InvalidDraft ->
                            extractionNoteError = "제목, 본문, 출처를 확인해 주세요."
                        null -> extractionNoteError = "노트를 저장하지 못했습니다. 다시 시도해 주세요."
                    }
                }
            },
            onDismiss = { if (!extractionNoteSaving) creationDrafts.researchNoteDraft = null },
        )
    }
    creationDrafts.checkpointMessage?.let { checkpointMessage ->
        val checkpointKey = "message:${checkpointMessage.id}"
        if (creationDrafts.checkpointKey == checkpointKey && evidenceMessage != null && evidenceMessage.id == checkpointMessage.id) {
            ConversationCheckpointCaptureDialog(
                initialTitle = com.projectnuke.fusion.chat.defaultCheckpointTitle(visibleAnswer),
                onDismiss = { creationDrafts.clearCheckpoint() },
                onSave = { title, note, onResult ->
                    extractionScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            checkpointRepository.save(conversationId, checkpointMessage.id, title, note)
                        }
                        val success = result is com.projectnuke.fusion.data.CheckpointWriteResult.Saved
                        if (success) creationDrafts.clearCheckpoint()
                        when (result) {
                            is com.projectnuke.fusion.data.CheckpointWriteResult.Saved ->
                                Toast.makeText(context, "체크포인트를 저장했습니다.", Toast.LENGTH_SHORT).show()
                            com.projectnuke.fusion.data.CheckpointWriteResult.InvalidText ->
                                Toast.makeText(context, "제목과 메모 길이를 확인해 주세요.", Toast.LENGTH_SHORT).show()
                            com.projectnuke.fusion.data.CheckpointWriteResult.MissingSource ->
                                Toast.makeText(context, "대화 지점을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                            com.projectnuke.fusion.data.CheckpointWriteResult.LimitReached ->
                                Toast.makeText(context, "이 대화의 체크포인트 수가 한도에 도달했습니다.", Toast.LENGTH_SHORT).show()
                            com.projectnuke.fusion.data.CheckpointWriteResult.Failed ->
                                Toast.makeText(context, "체크포인트를 저장하지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                        }
                        onResult(success)
                    }
                },
            )
        }
    }
}
@Composable
private fun ReasoningPanel(
    thinking: String,
    isLive: Boolean = false,
    identity: Any? = null,
) {
    var expanded by remember(identity) { mutableStateOf(false) }
    val semantic = LocalFusionSemanticColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = semantic.panel
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isLive) "생각 중..." else "모델 추론 기록",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = if (expanded) "⌃" else "⌄",
                    color = TextSecondary,
                    fontSize = 18.sp
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(semantic.divider)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "모델이 생성 중 출력한 중간 추론입니다. 답변의 정확성을 보장하는 근거는 아닙니다.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = thinking,
                    color = semantic.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }

}
/**
 * Overflow-only trailing items (Study and research refresh), rendered inside the
 * single message overflow. Extracted so the message surface keeps no additional
 * inline branching: both visibility decisions are made by the pure policies and
 * passed in as booleans. Study is OVERFLOW-tier and never appears on the rail.
 */
@Composable
internal fun AssistantOverflowExtraItems(
    showStudy: Boolean,
    enabled: Boolean,
    onStudy: () -> Unit,
    showResearchRefresh: Boolean,
    onResearchRefresh: () -> Unit,
) {
    if (showStudy) {
        DropdownMenuItem(
            text = { Text("이 주제로 공부") },
            enabled = enabled,
            onClick = onStudy,
        )
    }
    if (showResearchRefresh) {
        DropdownMenuItem(
            text = { Text("현재 정보로 다시 확인") },
            enabled = enabled,
            onClick = onResearchRefresh,
        )
    }
}

/**
 * Speaking-only stop affordance. Rendered directly in the message action row
 * ONLY while this exact answer is being spoken. It reuses the existing TTS
 * state and the existing read-aloud callback (no new playback owner) and
 * disappears when speech stops, so it never adds permanent toolbar chrome.
 * The hit target is the shared 48dp [ActionIcon] floor and the accessible label
 * names the stop action explicitly.
 */
@Composable
internal fun SpeakingStopAction(isSpeaking: Boolean, onStop: () -> Unit) {
    if (isSpeaking) {
        ActionIcon(
            icon = Icons.Rounded.Stop,
            contentDescription = SPEAKING_STOP_LABEL,
            onClick = onStop,
        )
    }
}

/** Canonical accessible label of the speaking-only stop control, pinned for tests. */
internal const val SPEAKING_STOP_LABEL = "음성으로 읽기 중지"

@Composable
internal fun AssistantMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchor: FusionPopupAnchor,
    onReadAloud: () -> Unit,
    isSpeaking: Boolean,
    onShareAnswer: (() -> Unit)?,
    onShareSources: (() -> Unit)?,
    onResearchPack: (() -> Unit)?,
    onUseAsWorkPlan: (() -> Unit)?,
    onOpenSavedClaims: (() -> Unit)? = null,
    extraActions: @Composable () -> Unit,
    createdAt: Long,
    historicalModel: String?,
    webSearchEnabled: Boolean,
    isRegenerating: Boolean,
    onRetry: () -> Unit,
    onBranch: () -> Unit = {},
    onOpenInTranslation: () -> Unit = {},
    onRegenerate: (ResponseRegenerationAction) -> Unit,
    onToggleWebSearch: () -> Unit,
    showCompare: Boolean = false,
    onCompare: () -> Unit = {},
    hideOpenInTranslation: Boolean = false,
) {
    val semanticColors = LocalFusionSemanticColors.current
    val timeText = remember(createdAt) {
        historicalAnswerDate(createdAt)
    }
    FusionDropdownMenu(
        expanded = expanded,
        anchor = anchor,
        onDismissRequest = onDismiss,
        containerColor = semanticColors.menu
    ) {
        onShareAnswer?.let { action -> DropdownMenuItem(text = { Text("답변만 공유") }, onClick = action) }
        onShareSources?.let { action -> DropdownMenuItem(text = { Text("답변과 출처 공유") }, onClick = action) }
        onResearchPack?.let { action -> DropdownMenuItem(text = { Text("연구 자료 내보내기") }, onClick = action) }
        onUseAsWorkPlan?.let { action -> DropdownMenuItem(text = { Text("계획 제안으로 가져오기") }, onClick = action) }
        // UX-P2A: single contextual READ-ONLY entry. Distinct from the "사실 확인"
        // verification action; opens stored claims only and runs no verification.
        onOpenSavedClaims?.let { action ->
            DropdownMenuItem(
                text = { Text(SAVED_CLAIMS_OPEN_LABEL, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = action,
            )
        }
        DropdownMenuItem(text = { Text(if (isSpeaking) "음성으로 읽기 중지" else "음성으로 읽기") }, onClick = onReadAloud)
        extraActions()
        DropdownMenuItem(
            text = {
                Text(
                    text = timeText,
                    color = semanticColors.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            onClick = {},
            enabled = false
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(semanticColors.divider)
        )

        DropdownMenuItem(
            text = { Text("↗  여기서 새 흐름 시작", color = semanticColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = onBranch,
            enabled = !isRegenerating
        )

        // Duplicate-affordance gate: when the contextual answer-action layer
        // (답변 활용) owns "번역에서 열기" for this exact answer, the legacy ⋮
        // menu must not show it again for the same answer.
        if (!hideOpenInTranslation) {
            DropdownMenuItem(
                text = { Text("번역에서 열기", color = semanticColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = onOpenInTranslation,
                enabled = !isRegenerating
            )
        }

        if (showCompare) {
            DropdownMenuItem(
                text = { Text("답변 비교", color = semanticColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = onCompare,
                enabled = !isRegenerating
            )
        }

        DropdownMenuItem(
            text = { Text(historicalModel?.let { "$it 사용함" } ?: "생성 모델 정보 없음", color = semanticColors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = {},
            enabled = false
        )

        DropdownMenuItem(
            text = { Text("답변 다시 생성", color = semanticColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = onRetry,
            enabled = !isRegenerating
        )

        DropdownMenuItem(
            text = { Text("더 짧게", color = semanticColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = { onRegenerate(ResponseRegenerationAction.Shorter) },
            enabled = !isRegenerating
        )

        DropdownMenuItem(
            text = { Text("더 자세히", color = semanticColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = { onRegenerate(ResponseRegenerationAction.MoreDetailed) },
            enabled = !isRegenerating
        )

        DropdownMenuItem(
            text = { Text("표로 정리", color = semanticColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = { onRegenerate(ResponseRegenerationAction.Table) },
            enabled = !isRegenerating
        )

        DropdownMenuItem(
            text = { Text("전문가 톤", color = semanticColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = { onRegenerate(ResponseRegenerationAction.ExpertTone) },
            enabled = !isRegenerating
        )

        DropdownMenuItem(
            text = {
                Text(
                    text = if (webSearchEnabled) "🌐  웹 검색 끄기" else "🌐  웹 검색",
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            onClick = onToggleWebSearch
        )
    }

}

/**
 * Evidence-aware answer share launch seam.
 *
 * The payload is built synchronously from the already-visible answer and its
 * displayed provenance; no generation, retrieval or persistence runs here.
 * The Android chooser opens only after the explicit Share tap, with no
 * package targeting. If no compatible activity exists, a short Korean
 * message is shown and nothing crashes.
 */
private fun shareAssistantText(context: Context, text: String) {
    try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "답변 공유"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "공유할 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, "공유할 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
    }
}

private fun shareAssistantAnswerOnly(context: Context, visibleAnswer: String) {
    when (val result = AssistantSharePayloadPolicy.buildAnswerOnly(visibleAnswer)) {
        is AssistantShareBuildResult.Ready ->
            shareAssistantText(context, result.text)
        is AssistantShareBuildResult.EmptyAnswer ->
            Toast.makeText(context, "공유할 답변이 없습니다.", Toast.LENGTH_SHORT).show()
        is AssistantShareBuildResult.TooLarge ->
            Toast.makeText(
                context,
                "답변이 너무 길어 공유할 수 없습니다. 복사를 이용해 주세요.",
                Toast.LENGTH_SHORT,
            ).show()
    }
}

private fun shareAssistantAnswerWithSources(
    context: Context,
    visibleAnswer: String,
    webSources: List<AssistantShareWebSource>,
    localSources: List<AssistantShareLocalSource>,
) {
    when (
        val result = AssistantSharePayloadPolicy.buildAnswerWithSources(
            visibleAnswer,
            webSources,
            localSources,
        )
    ) {
        is AssistantShareBuildResult.Ready ->
            shareAssistantText(context, result.text)
        is AssistantShareBuildResult.EmptyAnswer ->
            Toast.makeText(context, "공유할 답변이 없습니다.", Toast.LENGTH_SHORT).show()
        is AssistantShareBuildResult.TooLarge ->
            Toast.makeText(
                context,
                "답변이 너무 길어 공유할 수 없습니다. 복사를 이용해 주세요.",
                Toast.LENGTH_SHORT,
            ).show()
    }
}
