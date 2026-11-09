package com.projectnuke.fusion.search

import com.projectnuke.fusion.ai.ExternalAiChatRunner
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.ManualSearchQueryValidation
import com.projectnuke.fusion.chat.SearchStrategy
import com.projectnuke.fusion.llm.LiteRtLlmEngine
import com.projectnuke.fusion.llm.runNonFatal
import com.projectnuke.fusion.model.toConversationOptions
import com.projectnuke.fusion.model.toRequestedEngineProfile
import com.projectnuke.fusion.privacy.DataEgressController
import com.projectnuke.fusion.privacy.DataEgressPolicy
import com.projectnuke.fusion.privacy.DataEgressPrivateTextLedger
import com.projectnuke.fusion.util.MtpPolicyProduction
import kotlinx.coroutines.CancellationException

/** Request-snapshot router shared by ChatScreen's normal, retry and regeneration search paths. */
internal class ApplicationSearchStrategyExecutor(
    private val localEngine: LiteRtLlmEngine,
    private val externalRunner: ExternalAiChatRunner,
    private val egressController: DataEgressController,
    private val providerRepository: WebSearchProviderRepository,
    private val diagnosticsSink: SearchStrategyDiagnosticsSink = AndroidSearchStrategyDiagnosticsSink,
) {
    suspend fun execute(
        request: GenerationRequestSnapshot,
        previousUserMessage: String?,
        ownerStillActive: () -> Boolean,
    ): SearchStrategyRouteResult {
        val ledgerSnapshot = DataEgressPrivateTextLedger.snapshot()
        val permissionStillValid = {
            ownerStillActive() && request.webSearchPolicy != GenerationRequestSnapshot.WebSearchPolicy.DISABLED &&
                DataEgressPrivateTextLedger.snapshot().generation == ledgerSnapshot.generation
        }
        val privateFileNames = buildList {
            request.attachmentIds.forEach { path -> FileName.from(path)?.let(::add) }
            request.projectContext?.files?.forEach { file ->
                add(file.displayName)
                FileName.from(file.localPath)?.let(::add)
            }
            request.selectionContext?.sources?.forEach { source ->
                add(source.title)
                source.identity.managedPath?.let { FileName.from(it)?.let(::add) }
            }
        }.filter(String::isNotBlank).distinct().take(32)
        val privateLedger = ledgerSnapshot.texts
        val knownPrivateExcerpts = buildList {
            request.projectContext?.instructions?.takeIf(String::isNotBlank)?.let(::add)
            request.selectionContext?.sources?.forEach { source ->
                add(source.title)
                add(source.excerpt)
            }
            request.history.takeLast(32).map { it.content }.filter { content ->
                DataEgressPolicy.webQueryContainsPrivate(content, privateLedger, privateFileNames)
            }.forEach(::add)
        }.filter(String::isNotBlank).map { it.take(MAX_PRIVATE_EXCERPT_CHARS) }
        val hasLocalDocuments = request.attachmentIds.isNotEmpty() ||
            request.localRag?.chunkKeys?.isNotEmpty() == true ||
            request.projectContext != null ||
            request.selectionContext != null
        // Persisted user text has no trustworthy origin or outbound-consent
        // marker. An empty process-local ledger cannot establish that prior
        // turns are public after restart (or when registration was missed).
        val restrictPriorPlanning = request.externalProviderSelectionFrozen ||
            hasLocalDocuments || ledgerSnapshot.hasPrivateMaterial
        // A multi-turn conversation is not private merely because history
        // exists. Ordinary public history must stay classified public; only
        // history that actually carries private or sensitive material is
        // treated as private history here.
        val hasPrivateHistoryContent = request.history.isNotEmpty() &&
            request.history.any { message ->
                message.content.isNotBlank() &&
                    DataEgressPolicy.webQueryContainsPrivate(message.content, privateLedger, privateFileNames)
            }
        val approvedPreviousUserMessage = previousUserMessage
            ?.takeIf(String::isNotBlank)
            ?.takeUnless {
                restrictPriorPlanning || DataEgressPolicy.webQueryContainsPrivate(
                    it,
                    knownPrivateExcerpts + privateLedger,
                    privateFileNames,
                )
            }
        val approvedSafePriorTurns = if (restrictPriorPlanning) emptyList() else request.safeWebSearchPriorUserTurns
            .filterNot {
                DataEgressPolicy.webQueryContainsPrivate(
                    it,
                    knownPrivateExcerpts + privateLedger,
                    privateFileNames,
                )
            }
            .take(MAX_SAFE_PRIOR_USER_TURNS)
        val explicitManualQuery = (request.searchStrategy.manualQuery as? ManualSearchQueryValidation.Valid)
            ?.query?.value
        val privacySnapshot = AgenticSearchPrivacySnapshot(
            requestId = request.requestId,
            typedUserQuery = request.rawUserText,
            forbiddenPrivateExcerpts = knownPrivateExcerpts + privateLedger,
            privateFileNames = privateFileNames,
            approvedLegacyQueries = approvedLegacyQueriesFromTypedInput(request.rawUserText),
            explicitManualQuery = explicitManualQuery,
            hasPrivateHistory = hasPrivateHistoryContent,
            hasAttachments = request.attachmentIds.isNotEmpty(),
            hasLocalRagExcerpts = request.localRag?.chunkKeys?.isNotEmpty() == true,
            hasProjectContext = request.projectContext != null,
            hasSelectionContext = request.selectionContext != null,
            approvedPriorUserTurns = approvedSafePriorTurns,
            requestStillAuthorized = permissionStillValid,
        )

        val localPlan = if (request.searchStrategy.strategy == SearchStrategy.AGENT_HARNESS &&
            !request.externalProviderSelectionFrozen
        ) {
            createLocalAgentModel(request, permissionStillValid)
        } else {
            null
        }
        val agentModel = when {
            request.searchStrategy.strategy != SearchStrategy.AGENT_HARNESS -> null
            request.externalProviderSelectionFrozen -> ExternalSearchAgentModel.create(
                runner = externalRunner,
                egressController = egressController,
                requestId = request.requestId,
                originalUserQuery = request.rawUserText,
                providerId = request.externalProviderId,
                providerSelectionFrozen = request.externalProviderSelectionFrozen,
                providerRequestSnapshot = request.externalProviderRequest,
                requestStillAuthorized = permissionStillValid,
            )
            localPlan?.model != null -> localPlan.model
            else -> null
        }
        val limits = if (request.externalProviderSelectionFrozen) {
            AgenticSearchLimits.EXTERNAL_DEFAULT
        } else {
            AgenticSearchLimits.LOCAL_DEFAULT
        }
        val startedAtNanos = System.nanoTime()
        val route = try {
            ResearchSearchOwner.searchWithStrategy(
                request = request.copy(safeWebSearchPriorUserTurns = approvedSafePriorTurns),
                previousUserMessage = approvedPreviousUserMessage,
                providerRepository = providerRepository,
                privacySnapshot = privacySnapshot,
                agentModel = agentModel,
                ownerStillActive = ownerStillActive,
                limits = limits,
                agentUnavailableReasonCode = localPlan?.unavailableReasonCode,
            )
        } catch (cancelled: CancellationException) {
            val elapsedMs = ((System.nanoTime() - startedAtNanos).coerceAtLeast(0) / 1_000_000L)
            runNonFatal {
                diagnosticsSink.record(
                    SearchStrategyDiagnosticEvent(
                        selectedStrategy = request.searchStrategy.strategy,
                        executedStrategy = null,
                        adapter = null,
                        planningCalls = null,
                        searchQueriesIssued = null,
                        searchRounds = null,
                        fallbackUsed = false,
                        fallbackReason = null,
                        sourceCount = null,
                        deadlineMs = limits.overallDeadlineMs,
                        elapsedMs = elapsedMs.coerceAtMost(180_000),
                        outcome = SearchRouteOutcome.CANCELLED,
                    ),
                )
            }
            throw cancelled
        }
        val elapsedMs = ((System.nanoTime() - startedAtNanos).coerceAtLeast(0) / 1_000_000L)
        runNonFatal {
            diagnosticsSink.record(
                SearchStrategyDiagnosticEvent.from(
                    route = route,
                    deadlineMs = limits.overallDeadlineMs,
                    elapsedMs = elapsedMs,
                ),
            )
        }
        return route
    }

    /**
     * Resolves the local planning adapter. A cold runtime, a switched model or
     * an engine-incompatible profile returns [LocalAgentPlan] with no adapter
     * and an explicit reason code. The planner is never allowed to load a
     * second model instance or force a large allocation just to keep the
     * AGENT_HARNESS label, so the controlled Classic fallback owns that
     * request and the reason code records why the agent did not plan.
     */
    private fun createLocalAgentModel(
        request: GenerationRequestSnapshot,
        permissionStillValid: () -> Boolean,
    ): LocalAgentPlan {
        val modelPath = request.selectedModelPath?.takeIf(String::isNotBlank)
            ?: return LocalAgentPlan.none("no_local_model_selected")
        val plannerSettings = request.settings.copy(
            speculativeDecodingEnabled = MtpPolicyProduction.resolveEffectiveMtpSetting(
                modelPath = modelPath,
                settings = request.settings,
            ),
        )
        val profile = plannerSettings.toRequestedEngineProfile(
            modelPath = modelPath,
            enableVisionBackend = request.multimodalImagePaths.isNotEmpty(),
            modelIdentity = request.selectedModelStableId,
        )
        if (!localEngine.isRuntimeReusableFor(profile)) {
            return LocalAgentPlan.none(
                localAgentUnavailableReason(
                    selectedModelPath = modelPath,
                    runtimeReusable = false,
                    hasLoadedRuntime = localEngine.lastRuntimeSelection != null,
                )!!,
            )
        }
        return LocalAgentPlan.ready(
            LocalStructuredSearchAgentModel(
                engine = localEngine,
                profile = profile,
                baseOptions = plannerSettings.toConversationOptions(estimatedPromptTokens = LOCAL_ESTIMATED_PROMPT_TOKENS),
                engineStillReusable = { localEngine.isRuntimeReusableFor(profile) },
                requestStillAuthorized = permissionStillValid,
                conversationId = request.conversationId,
                lifecycleRequestId = request.requestId,
            ),
        )
    }

    private class LocalAgentPlan private constructor(
        val model: SearchAgentModel?,
        val unavailableReasonCode: String?,
    ) {
        companion object {
            fun ready(model: SearchAgentModel) = LocalAgentPlan(model, null)
            fun none(reasonCode: String) = LocalAgentPlan(null, reasonCode)
        }
    }

    private object FileName {
        fun from(path: String): String? = path.substringAfterLast('/', path.substringAfterLast('\\'))
            .takeIf(String::isNotBlank)
    }

    companion object {
        private const val MAX_PRIVATE_EXCERPT_CHARS = 4_096
        private const val LOCAL_ESTIMATED_PROMPT_TOKENS = 2_000
    }
}

/**
 * Reason code for a local search planner that must not be created. A cold
 * runtime is a different operational fact from a runtime that is active for
 * another model or profile, and neither is a planning failure. The planner is
 * never allowed to load a second model or force a large allocation just to
 * keep the AGENT_HARNESS label, so callers fall back to Classic with this code.
 */
internal fun localAgentUnavailableReason(
    selectedModelPath: String?,
    runtimeReusable: Boolean,
    hasLoadedRuntime: Boolean,
): String? = when {
    selectedModelPath.isNullOrBlank() -> "no_local_model_selected"
    !runtimeReusable -> if (hasLoadedRuntime) "local_runtime_not_reusable" else "local_model_cold_start"
    else -> null
}

/** Context can inform planning, but only the current typed input can seed query authority. */
internal fun approvedLegacyQueriesFromTypedInput(typedUserQuery: String): List<String> {
    val plan = RuleBasedWebSearchPlanner.plan(RuleBasedWebSearchPlannerInput(currentUserInput = typedUserQuery))
    return (listOf(plan.primaryQuery) + plan.alternateQueries).distinct()
}
