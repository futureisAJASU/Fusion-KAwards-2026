package com.projectnuke.fusion.search

import com.projectnuke.fusion.ai.ExternalAiChatRunner
import com.projectnuke.fusion.ai.ExternalSearchPlannerPreparation
import com.projectnuke.fusion.ai.model.AiRole
import com.projectnuke.fusion.ai.model.AgenticWebSearchTool
import com.projectnuke.fusion.ai.model.AiMessage
import com.projectnuke.fusion.ai.network.AiProviderClientException
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.llm.FusionRuntimeLock
import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.llm.LlmEngine
import com.projectnuke.fusion.llm.WarmRuntimeExecution
import com.projectnuke.fusion.llm.executeOnlyWithReusableRuntime
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.RequestedEngineProfile
import com.projectnuke.fusion.privacy.DataEgressController
import com.projectnuke.fusion.privacy.DataEgressDestination
import com.projectnuke.fusion.privacy.DataEgressScope
import com.projectnuke.fusion.privacy.PreparedEgressRequestFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Structured JSON intent over an already active local model; it never loads or switches engines. */
internal class LocalStructuredSearchAgentModel(
    private val engine: LlmEngine,
    private val profile: RequestedEngineProfile,
    baseOptions: ConversationOptions,
    private val engineStillReusable: () -> Boolean,
    private val requestStillAuthorized: () -> Boolean,
    private val conversationId: Long? = null,
    private val lifecycleRequestId: String? = null,
    private val withRuntimeLock: suspend (suspend () -> WarmRuntimeExecution<GenerationOutcome>) -> WarmRuntimeExecution<GenerationOutcome> = { criticalSection ->
        FusionRuntimeLock.withChatGeneration(conversationId, lifecycleRequestId) { criticalSection() }
    },
) : SearchAgentModel {
    override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
    private val options = baseOptions.copy(
        maxOutputToken = LOCAL_PLANNER_MAX_OUTPUT_TOKENS,
        temperature = 0.1f,
        topK = minOf(baseOptions.topK, 24),
    )

    override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply {
        currentCoroutineContext().ensureActive()
        if (!requestStillAuthorized()) return SearchAgentReply.Failed("request_authorization_revoked", recoverable = false)
        if (!engineStillReusable()) return SearchAgentReply.Failed("active_local_model_unavailable")
        val promptMessages = messages.map(::toLocalMessage)
        return try {
            when (
                val execution = executeOnlyWithReusableRuntime(
                    withRuntimeLock = withRuntimeLock,
                    isRuntimeReusable = { requestStillAuthorized() && engineStillReusable() },
                    generate = { engine.generate(promptMessages, profile, options) },
                )
            ) {
                WarmRuntimeExecution.NotReusable -> if (!requestStillAuthorized()) {
                    SearchAgentReply.Failed("request_authorization_revoked", recoverable = false)
                } else {
                    SearchAgentReply.Failed("active_local_model_unavailable")
                }
                is WarmRuntimeExecution.Executed -> when (val outcome = execution.value) {
                is GenerationOutcome.Success -> SearchAgentReply.StructuredIntent(outcome.text, outcome.truncated)
                is GenerationOutcome.Cancelled -> throw CancellationException("local search planning cancelled")
                GenerationOutcome.Empty -> SearchAgentReply.Failed("empty_local_planner_output")
                is GenerationOutcome.Failure -> SearchAgentReply.Failed("local_planner_failure_${outcome.kind.name.lowercase()}")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SearchAgentReply.Failed("local_planner_exception")
        }
    }

    private fun toLocalMessage(message: SearchAgentMessage): ChatMessage = when (message.role) {
        SearchAgentMessageRole.SYSTEM -> ChatMessage("system", message.content)
        SearchAgentMessageRole.USER -> ChatMessage("user", message.content)
        SearchAgentMessageRole.ASSISTANT -> ChatMessage("assistant", message.content)
        SearchAgentMessageRole.TOOL -> ChatMessage(
            "user",
            "<untrusted_web_search_tool_result>\n${message.content}\n</untrusted_web_search_tool_result>",
        )
    }

    companion object {
        const val LOCAL_PLANNER_MAX_OUTPUT_TOKENS = 192
    }
}

/** External tool adapter whose every planning round is bound through the app's exact egress gate. */
internal class ExternalSearchAgentModel private constructor(
    private val runner: ExternalAiChatRunner,
    private val egressController: DataEgressController,
    private val requestId: String,
    private val originalUserQuery: String,
    private val providerId: String?,
    private val providerSelectionFrozen: Boolean,
    private val providerRequestSnapshot: ExternalProviderRequestSnapshot?,
    private val requestStillAuthorized: () -> Boolean,
    initiallySupportsNativeTools: Boolean,
) : SearchAgentModel {
    private var nativeToolsEnabled = initiallySupportsNativeTools
    override val protocol: SearchAgentProtocol
        get() = if (nativeToolsEnabled) SearchAgentProtocol.NATIVE_TOOL_CALLS else SearchAgentProtocol.STRUCTURED_INTENT

    override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply {
        currentCoroutineContext().ensureActive()
        if (!requestStillAuthorized()) return SearchAgentReply.Failed("request_authorization_revoked", recoverable = false)

        val nativeAttempt = nativeToolsEnabled
        val aiMessages = toExternalMessages(messages, nativeAttempt)
        val preparation = runner.prepareSearchPlannerTurn(
            messages = aiMessages,
            toolDefinitions = listOf(AgenticWebSearchTool.definition()),
            providerId = providerId,
            providerSelectionFrozen = providerSelectionFrozen,
            maxTokensOverride = EXTERNAL_PLANNER_MAX_OUTPUT_TOKENS,
            providerRequestSnapshot = providerRequestSnapshot,
            allowNativeToolCalling = nativeAttempt,
        )
        var prepared = preparation as? ExternalSearchPlannerPreparation.Ready
            ?: return SearchAgentReply.Failed("external_provider_unavailable")
        if (nativeAttempt && !prepared.nativeToolCalling) {
            nativeToolsEnabled = false
            prepared = prepared.copy(
                transportRequest = prepared.transportRequest.copy(
                    messages = toExternalMessages(messages, native = false),
                ),
            )
        }
        val usesNativeTools = prepared.nativeToolCalling
        val egressMessages = prepared.transportRequest.messages
        val egressRequest = PreparedEgressRequestFactory.prepare(
            requestId = requestId,
            destination = DataEgressDestination.EXTERNAL_AI,
            provider = prepared.provider,
            exactRequest = prepared.transportRequest,
            resolvedRequestPatch = providerRequestSnapshot?.resolvedRequestPatch
                ?: com.projectnuke.fusion.ai.model.ExternalRequestPatch(),
            requestCompatibility = providerRequestSnapshot?.requestCompatibility
                ?: com.projectnuke.fusion.ai.network.ExternalRequestCompatibility.FALLBACK,
            transmittedUserText = originalUserQuery,
            transmittedHistoryText = egressMessages.joinToString("\n") { "${it.role.name}: ${it.content}" },
            scope = DataEgressScope.USER_QUERY_AND_HISTORY,
        )
        if (!egressController.arm(egressRequest, consent = null, stillAuthorized = requestStillAuthorized)) {
            return SearchAgentReply.Failed("external_egress_denied", recoverable = false)
        }

        return try {
            currentCoroutineContext().ensureActive()
            if (!requestStillAuthorized()) return SearchAgentReply.Failed("request_authorization_revoked", recoverable = false)
            val response = runner.completeSearchPlannerTurn(prepared, requestId)
            if (usesNativeTools) {
                if (response.toolCalls.isNotEmpty()) {
                    SearchAgentReply.NativeToolCalls(
                        calls = response.toolCalls,
                        assistantText = response.content,
                        finishReason = response.finishReason,
                    )
                } else {
                    SearchAgentReply.Finished(response.content.take(1_024))
                }
            } else {
                SearchAgentReply.StructuredIntent(
                    output = response.content,
                    truncated = response.truncated ||
                        response.completionStatus == com.projectnuke.fusion.ai.model.AiChatCompletionStatus.OUTPUT_LIMIT_REACHED,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AiProviderClientException) {
            when {
                failure.egressAuthorizationDenied -> SearchAgentReply.Failed("external_egress_denied", recoverable = false)
                failure.nativeToolProtocolUnsupported && usesNativeTools && nativeToolsEnabled -> {
                    // One exact retry without the rejected schema; all later rounds stay structured.
                    nativeToolsEnabled = false
                    complete(messages)
                }
                else -> SearchAgentReply.Failed("external_planner_transport_failure")
            }
        } catch (_: Exception) {
            SearchAgentReply.Failed("external_planner_failure")
        } finally {
            egressController.disarm(requestId)
        }
    }

    private fun toExternalMessages(messages: List<SearchAgentMessage>, native: Boolean): List<AiMessage> {
        val converted = messages.map { message ->
            when (message.role) {
                SearchAgentMessageRole.SYSTEM -> AiMessage(AiRole.SYSTEM, message.content)
                SearchAgentMessageRole.USER -> AiMessage(AiRole.USER, message.content)
                SearchAgentMessageRole.ASSISTANT -> AiMessage(
                    AiRole.ASSISTANT,
                    message.content.ifBlank { if (native && message.toolCalls.isNotEmpty()) "" else "Prior search action." },
                    toolCalls = message.toolCalls.takeIf { native }.orEmpty(),
                )
                SearchAgentMessageRole.TOOL -> if (native) {
                    AiMessage(AiRole.TOOL, message.content, toolCallId = message.toolCallId)
                } else {
                    AiMessage(
                        AiRole.USER,
                        "<untrusted_web_search_tool_result>\n${message.content}\n</untrusted_web_search_tool_result>",
                    )
                }
            }
        }.toMutableList()
        if (!native) {
            converted.indices.forEach { index ->
                val message = converted[index]
                if (message.role == AiRole.SYSTEM &&
                    message.content.contains(SearchAgentProtocolPrompt.NATIVE_TOOL_CALLS)
                ) {
                    converted[index] = message.copy(
                        content = message.content.replace(
                            SearchAgentProtocolPrompt.NATIVE_TOOL_CALLS,
                            SearchAgentProtocolPrompt.STRUCTURED_INTENT,
                        ),
                    )
                }
            }
            val instruction = AiMessage(
                AiRole.SYSTEM,
                "Structured tool-intent mode: return exactly one JSON action object using the documented schema, with no markdown.",
            )
            val firstNonSystem = converted.indexOfFirst { it.role != AiRole.SYSTEM }
            converted.add(if (firstNonSystem < 0) converted.size else firstNonSystem, instruction)
        }
        return converted
    }

    companion object {
        const val EXTERNAL_PLANNER_MAX_OUTPUT_TOKENS = 256

        suspend fun create(
            runner: ExternalAiChatRunner,
            egressController: DataEgressController,
            requestId: String,
            originalUserQuery: String,
            providerId: String?,
            providerSelectionFrozen: Boolean,
            providerRequestSnapshot: ExternalProviderRequestSnapshot?,
            requestStillAuthorized: () -> Boolean,
        ): ExternalSearchAgentModel? {
            val target = runner.resolveTargetProvider(providerId, providerSelectionFrozen, providerRequestSnapshot)
            val provider = target.provider ?: return null
            return ExternalSearchAgentModel(
                runner = runner,
                egressController = egressController,
                requestId = requestId,
                originalUserQuery = originalUserQuery,
                providerId = providerId,
                providerSelectionFrozen = providerSelectionFrozen,
                providerRequestSnapshot = providerRequestSnapshot,
                requestStillAuthorized = requestStillAuthorized,
                initiallySupportsNativeTools = ExternalSearchToolCallingSupport.supports(provider.type, provider.baseUrl),
            )
        }
    }
}
