package com.projectnuke.fusion.ai

import com.projectnuke.fusion.ai.model.AiChatRequest
import com.projectnuke.fusion.ai.model.AiChatResponse
import com.projectnuke.fusion.ai.model.AiChatCompletionStatus
import com.projectnuke.fusion.ai.model.AiMessage
import com.projectnuke.fusion.ai.model.AiToolChoice
import com.projectnuke.fusion.ai.model.AiToolDefinition
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.ai.network.AiProviderClientException
import com.projectnuke.fusion.ai.network.ChatClient
import com.projectnuke.fusion.search.ExternalSearchToolCallingSupport
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace

internal interface ExternalAiProviderSource {
    suspend fun getSelectedRunnableProvider(): AiProviderConfig?
    suspend fun getRunnableProviderById(id: String): AiProviderConfig?
}

internal sealed interface ExternalAiChatResult {
    data class Success(
        /** Canonical final answer only. */
        val content: String,
        val providerDisplayName: String?,
        /** Optional provider artifact, kept separate from [content]. */
        val reasoningContent: String? = null,
    ) : ExternalAiChatResult
    data class Truncated(
        /** Canonical partial answer only; presentation adds the durable notice. */
        val content: String,
        val providerDisplayName: String?,
        /** Optional provider artifact, kept separate from [content]. */
        val reasoningContent: String? = null,
    ) : ExternalAiChatResult
    data class BlockedAttachment(val message: String) : ExternalAiChatResult
    data class NoProvider(val message: String) : ExternalAiChatResult
    data class Empty(
        val providerDisplayName: String?,
        val reasoningContent: String? = null,
    ) : ExternalAiChatResult
    data class Error(val message: String) : ExternalAiChatResult
}

internal sealed interface ExternalSearchPlannerPreparation {
    data class Ready(
        val provider: AiProviderConfig,
        val providerRequestSnapshot: ExternalProviderRequestSnapshot?,
        val transportRequest: AiChatRequest,
        val nativeToolCalling: Boolean,
    ) : ExternalSearchPlannerPreparation

    data class Unavailable(val reasonCode: String) : ExternalSearchPlannerPreparation
}

internal class ExternalAiChatRunner(
    private val providerRepository: ExternalAiProviderSource,
    private val client: ChatClient
) {
    companion object {
        const val COMPATIBILITY_MAX_OUTPUT_TOKENS: Int = 2048
    }
    suspend fun generate(
        history: List<ChatMessage>,
        userInput: String,
        hasAttachments: Boolean,
        stripAttachments: (String) -> String
    ): ExternalAiChatResult {
        val messages = buildExternalAiMessages(
            history = history,
            userInput = userInput,
            stripAttachments = stripAttachments
        )

        return generateFromMessages(
            messages = messages,
            hasAttachments = hasAttachments
        )
    }

    suspend fun generateFromMessages(
        messages: List<AiMessage>,
        hasAttachments: Boolean = false,
        providerId: String? = null,
        providerSelectionFrozen: Boolean = false,
        maxTokensOverride: Int? = null,
        providerRequestSnapshot: ExternalProviderRequestSnapshot? = null,
        requestId: String? = null,
    ): ExternalAiChatResult {
        if (hasAttachments) {
            return ExternalAiChatResult.BlockedAttachment(
                "현재 외부 AI API 모드에서는 첨부 파일을 전송할 수 없습니다."
            )
        }

        val providersLookup = resolveTargetProvider(providerId, providerSelectionFrozen, providerRequestSnapshot)
        val provider = providersLookup.provider ?: return providersLookup.noProvider
        return chatCompletionThrough(
            provider = provider,
            messages = messages,
            maxTokensOverride = maxTokensOverride,
            requestId = requestId,
            providerRequestSnapshot = providerRequestSnapshot,
        )
    }

    /**
     * Shared provider resolution with the FROZEN semantics. Returns the
     * resolved provider together with the [NoProvider] failure the caller
     * must report when resolution fails. Single-flight callers (egress
     * arming) and the transport path share this exact resolution so the
     * expectation/transport binding cannot silently diverge.
     */
    internal data class ResolvedExternalProvider(
        val provider: AiProviderConfig?,
        val noProvider: ExternalAiChatResult,
    )

    internal suspend fun resolveTargetProvider(
        providerId: String?,
        providerSelectionFrozen: Boolean,
        providerRequestSnapshot: ExternalProviderRequestSnapshot?,
    ): ResolvedExternalProvider {
        val provider = when {
            providerRequestSnapshot != null -> {
                // Availability is checked at execution time, but semantic
                // fields remain those captured for this request. Deletion or
                // disablement rejects the queued request; it never falls back
                // to another provider.
                providerRepository.getRunnableProviderById(providerRequestSnapshot.id)
                    ?: return ResolvedExternalProvider(
                        null,
                        ExternalAiChatResult.NoProvider(
                            "캡처된 AI API 제공자를 더 이상 사용할 수 없습니다. 제공자 설정을 확인해 주세요."
                        )
                    )
                AiProviderConfig(
                    id = providerRequestSnapshot.id,
                    type = providerRequestSnapshot.type,
                    displayName = providerRequestSnapshot.displayName,
                    baseUrl = providerRequestSnapshot.baseUrl,
                    modelId = providerRequestSnapshot.modelId,
                    apiKeySecretId = providerRequestSnapshot.apiKeySecretId,
                    isEnabled = true,
                    temperature = providerRequestSnapshot.temperature,
                    maxTokens = providerRequestSnapshot.maxTokens,
                    authMode = providerRequestSnapshot.authMode,
                    authHeaderName = providerRequestSnapshot.authHeaderName,
                    reasoningContentEnabled = providerRequestSnapshot.reasoningContentEnabled,
                    selectedVariantId = providerRequestSnapshot.selectedVariantId,
                )
            }
            providerSelectionFrozen && providerId == null -> null
            providerSelectionFrozen -> providerRepository.getRunnableProviderById(providerId!!)
            providerId != null -> providerRepository.getRunnableProviderById(providerId)
            else -> providerRepository.getSelectedRunnableProvider()
        }
        if (provider == null) {
            return ResolvedExternalProvider(
                null,
                ExternalAiChatResult.NoProvider(
                    "사용 가능한 외부 AI API 제공자가 없습니다. AI API 설정에서 필수 항목을 확인해 주세요."
                )
            )
        }
        return ResolvedExternalProvider(provider, ExternalAiChatResult.Error(""))
    }

    /**
     * Single construction authority for the exact transport request. The
     * egress-arming site builds the armed expectation through this same
     * function, so the armed bytes and the transmitted bytes cannot diverge.
     */
    internal fun buildTransportRequest(
        provider: AiProviderConfig,
        messages: List<AiMessage>,
        maxTokensOverride: Int?,
    ): AiChatRequest = AiChatRequest(
        messages = messages,
        temperature = provider.temperature,
        maxTokens = maxTokensOverride ?: provider.maxTokens ?: COMPATIBILITY_MAX_OUTPUT_TOKENS
    )

    /**
     * Builds the exact per-round search-planner request through the same
     * frozen provider resolver and request builder as ordinary external chat.
     * Callers must prepare and arm this exact payload with DataEgressController
     * before [completeSearchPlannerTurn].
     */
    internal suspend fun prepareSearchPlannerTurn(
        messages: List<AiMessage>,
        toolDefinitions: List<AiToolDefinition>,
        providerId: String?,
        providerSelectionFrozen: Boolean,
        maxTokensOverride: Int?,
        providerRequestSnapshot: ExternalProviderRequestSnapshot?,
        allowNativeToolCalling: Boolean,
    ): ExternalSearchPlannerPreparation {
        val target = resolveTargetProvider(providerId, providerSelectionFrozen, providerRequestSnapshot)
        val provider = target.provider ?: return ExternalSearchPlannerPreparation.Unavailable("provider_unavailable")
        val native = allowNativeToolCalling &&
            ExternalSearchToolCallingSupport.supports(provider.type, provider.baseUrl) &&
            toolDefinitions.isNotEmpty()
        val request = buildTransportRequest(provider, messages, maxTokensOverride).copy(
            tools = if (native) toolDefinitions else emptyList(),
            toolChoice = if (native) AiToolChoice.AUTO else null,
        )
        return ExternalSearchPlannerPreparation.Ready(
            provider = provider,
            providerRequestSnapshot = providerRequestSnapshot,
            transportRequest = request,
            nativeToolCalling = native,
        )
    }

    /** Executes only a previously prepared, exact request through the gated client. */
    internal suspend fun completeSearchPlannerTurn(
        prepared: ExternalSearchPlannerPreparation.Ready,
        requestId: String,
    ): AiChatResponse = client.chatCompletion(
        config = prepared.provider,
        request = prepared.transportRequest,
        requestId = requestId,
        providerRequestSnapshot = prepared.providerRequestSnapshot,
    )

    internal suspend fun chatCompletionThrough(
        provider: AiProviderConfig,
        messages: List<AiMessage>,
        maxTokensOverride: Int?,
        requestId: String?,
        providerRequestSnapshot: ExternalProviderRequestSnapshot? = null,
    ): ExternalAiChatResult {
        return try {
            val response = client.chatCompletion(
                config = provider,
                request = buildTransportRequest(provider, messages, maxTokensOverride),
                requestId = requestId,
                providerRequestSnapshot = providerRequestSnapshot,
            )
            val content = visibleMessageContentPreservingWhitespace(response.content)
            val reasoningContent = response.reasoningContent
                ?.let(::visibleMessageContentPreservingWhitespace)
                ?.takeIf(String::isNotBlank)
            if (response.completionStatus == AiChatCompletionStatus.OUTPUT_LIMIT_REACHED || response.truncated) {
                ExternalAiChatResult.Truncated(
                    content = content,
                    providerDisplayName = provider.displayName,
                    reasoningContent = reasoningContent,
                )
            } else if (content.isBlank()) {
                ExternalAiChatResult.Empty(
                    providerDisplayName = provider.displayName,
                    reasoningContent = reasoningContent,
                )
            } else {
                ExternalAiChatResult.Success(
                    content = content,
                    providerDisplayName = provider.displayName,
                    reasoningContent = reasoningContent,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: AiProviderClientException) {
            ExternalAiChatResult.Error(e.message ?: "외부 AI API 요청에 실패했습니다.")
        }
    }
}
