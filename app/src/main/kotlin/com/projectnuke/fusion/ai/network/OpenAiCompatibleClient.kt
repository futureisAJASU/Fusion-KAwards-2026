package com.projectnuke.fusion.ai.network

import com.projectnuke.fusion.ai.model.AiChatRequest
import com.projectnuke.fusion.ai.model.AiChatResponse
import com.projectnuke.fusion.ai.model.AiChatCompletionStatus
import com.projectnuke.fusion.ai.model.AiToolCall
import com.projectnuke.fusion.ai.model.AiToolChoice
import com.projectnuke.fusion.ai.model.AiToolParameter
import com.projectnuke.fusion.ai.model.validateToolEnvelope
import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiRole
import com.projectnuke.fusion.ai.model.ReasoningEffortMapping
import com.projectnuke.fusion.ai.secure.SecretStore
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

internal interface ChatClient {
    suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest): AiChatResponse

    /** Optional internal identity carried only to request-scoped egress gates. */
    suspend fun chatCompletion(
        config: AiProviderConfig,
        request: AiChatRequest,
        requestId: String?,
    ): AiChatResponse = chatCompletion(config, request)

    suspend fun chatCompletion(
        config: AiProviderConfig,
        request: AiChatRequest,
        requestId: String?,
        providerRequestSnapshot: ExternalProviderRequestSnapshot?,
    ): AiChatResponse = chatCompletion(config, request, requestId)
}

class OpenAiCompatibleClient(
    private val secretStore: SecretStore,
    private val connectionFactory: ((URL) -> HttpURLConnection)? = null,
    private val totalTimeoutMs: Long = ExternalRequestDeadlinePolicy.TOTAL_WALL_CLOCK_TIMEOUT_MS,
) : ChatClient {
    @Volatile internal var onBeforeCompletion: () -> Unit = {}

    override suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest): AiChatResponse =
        chatCompletion(config, request, requestId = null, providerRequestSnapshot = null)

    override suspend fun chatCompletion(
        config: AiProviderConfig,
        request: AiChatRequest,
        requestId: String?,
        providerRequestSnapshot: ExternalProviderRequestSnapshot?,
    ): AiChatResponse {
        try {
            request.validateToolEnvelope()
        } catch (failure: IllegalArgumentException) {
            throw AiProviderClientException("Native tool request is invalid.", failure)
        }
        if (config.baseUrl.isBlank()) throw AiProviderClientException("Base URL을 입력해 주세요.")
        val endpoints = runCatching { OpenAiCompatibleEndpointResolver.resolve(config.baseUrl) }
            .getOrElse { throw AiProviderClientException("Base URL을 입력해 주세요.", it) }
        // Transport gate BEFORE credential retrieval or connection opening:
        // legacy persisted HTTP config must never reach an authenticated
        // request, independent of save-time validation and platform policy.
        val transportUri = runCatching { java.net.URI(endpoints.apiRoot) }.getOrNull()
        if (transportUri == null ||
            !transportUri.scheme.equals("https", ignoreCase = true) ||
            transportUri.host.isNullOrBlank()
        ) {
            throw AiProviderClientException("보안 전송(HTTPS) 엔드포인트만 지원합니다.")
        }
        if (config.modelId.isBlank()) throw AiProviderClientException("모델 ID를 입력해 주세요.")
        val secret = when (config.authMode) {
            AiProviderAuthMode.NONE -> null
            else -> config.apiKeySecretId?.takeIf { it.isNotBlank() }?.let { secretStore.getSecret(it)?.takeIf(String::isNotBlank) }
                ?: throw AiProviderClientException("API 키를 입력해 주세요.")
        }
        val endpoint = endpoints.chatCompletions
        return withContext(Dispatchers.IO) {
            val connection = try {
                createConnection(endpoint, config, secret)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw normalizeConnectionSetupFailure(e)
            }
            try {
                withTimeout(totalTimeoutMs) {
                    val ctx = coroutineContext
                    suspendCancellableCoroutine { continuation ->
                        continuation.invokeOnCancellation { connection.disconnect() }
                        try {
                            ctx.ensureActive()
                            val body = buildRequestJson(config, request, providerRequestSnapshot).toString().toByteArray(Charsets.UTF_8)
                            connection.outputStream.use { output ->
                                var offset = 0
                                while (offset < body.size) { ctx.ensureActive(); val size = minOf(8192, body.size - offset); output.write(body, offset, size); offset += size }
                            }
                            val status = connection.responseCode
                            val text = if (status in 200..299) readBoundedBody(connection.inputStream, MaxSuccessBodyBytes, BodyKind.SUCCESS, ctx)
                            else connection.errorStream?.let { readDiagnosticBodySafely(it, MaxErrorBodyBytes, ctx).text }.orEmpty()
                            ctx.ensureActive()
                            if (status !in 200..299) throw AiProviderClientException(
                                buildHttpErrorMessage(status, text, listOfNotNull(secret)),
                                nativeToolProtocolUnsupported = request.tools.isNotEmpty() && isNativeToolProtocolRejection(status, text),
                            )
                            val response = parseResponse(text, config.reasoningContentEnabled)
                            onBeforeCompletion()
                            continuation.tryResumeSuccess(response)
                        } catch (e: AiProviderClientException) { continuation.tryResumeFailure(e)
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                        } catch (e: SocketTimeoutException) { ctx.ensureActive(); continuation.tryResumeFailure(AiProviderClientException("외부 AI API 응답 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.", e))
                        } catch (e: IOException) { ctx.ensureActive(); continuation.tryResumeFailure(AiProviderClientException("네트워크 연결에 실패했습니다. 인터넷 연결과 Base URL을 확인해 주세요.", e))
                        } catch (e: Exception) { ctx.ensureActive(); continuation.tryResumeFailure(AiProviderClientException("외부 AI API 응답을 처리할 수 없습니다.", e))
                        } finally { connection.disconnect() }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                try { connection.disconnect() } catch (_: Exception) { }
                throw AiProviderClientException(ExternalRequestDeadlinePolicy.TOTAL_TIMEOUT_MESSAGE, e)
            }
        }
    }

    internal fun createConnection(endpoint: String, apiKey: String): HttpURLConnection =
        createConnection(endpoint, AiProviderConfig("test", com.projectnuke.fusion.ai.model.AiProviderType.CUSTOM_OPENAI_COMPATIBLE, "test", endpoint, "test", "test"), apiKey)

    internal fun createConnection(endpoint: String, config: AiProviderConfig, secret: String?): HttpURLConnection {
        val conn = connectionFactory?.invoke(URL(endpoint)) ?: (URL(endpoint).openConnection() as HttpURLConnection)
        return conn.apply {
            requestMethod = "POST"; connectTimeout = 30_000; readTimeout = 120_000; doOutput = true; instanceFollowRedirects = false
            when (config.authMode) {
                AiProviderAuthMode.BEARER_API_KEY -> secret?.let { setRequestProperty("Authorization", "Bearer $it") }
                AiProviderAuthMode.CUSTOM_HEADER -> config.authHeaderName?.takeIf(String::isNotBlank)?.let { setRequestProperty(it, secret.orEmpty()) }
                AiProviderAuthMode.NONE -> Unit
            }
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json")
        }
    }

    private fun normalizeConnectionSetupFailure(error: Exception): AiProviderClientException = when (error) {
        is SocketTimeoutException -> AiProviderClientException("외부 AI API 연결 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.", error)
        is MalformedURLException -> AiProviderClientException("외부 AI API 설정이 잘못되었습니다. Base URL을 확인해 주세요.", error)
        is IOException -> AiProviderClientException("외부 AI API 네트워크 연결에 실패했습니다. 인터넷 연결과 Base URL을 확인해 주세요.", error)
        else -> AiProviderClientException("외부 AI API 연결을 준비하지 못했습니다. Base URL과 제공자 설정을 확인해 주세요.", error)
    }

    internal fun buildRequestJson(
        config: AiProviderConfig,
        request: AiChatRequest,
        providerRequestSnapshot: ExternalProviderRequestSnapshot? = null,
    ): JSONObject {
        val compatibility = providerRequestSnapshot?.requestCompatibility ?: ExternalRequestCompatibility.FALLBACK
        return JSONObject()
        .put("model", config.modelId)
        .put("messages", JSONArray().also { array -> request.messages.forEach { message ->
            array.put(JSONObject().put("role", message.role.toOpenAiRole()).put("content", message.content).apply {
                message.toolCallId?.let { put("tool_call_id", it) }
                if (message.toolCalls.isNotEmpty()) put("tool_calls", JSONArray().also { calls ->
                    message.toolCalls.forEach { call ->
                        calls.put(JSONObject()
                            .put("id", call.id)
                            .put("type", "function")
                            .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments)))
                    }
                })
            })
        } })
        .apply {
            if (request.tools.isNotEmpty()) {
                put("tools", JSONArray().also { array ->
                    request.tools.forEach { tool ->
                        array.put(JSONObject()
                            .put("type", "function")
                            .put("function", JSONObject()
                                .put("name", tool.name)
                                .put("description", tool.description)
                                .put("parameters", tool.parameters.toJson())
                                .put("strict", tool.strict)))
                    }
                })
                request.toolChoice?.let { choice -> put("tool_choice", choice.toWireValue()) }
            }
            if (compatibility.temperaturePolicy == ExternalTemperaturePolicy.EMIT) {
                put("temperature", request.temperature ?: config.temperature)
            }
            put("stream", false)
        }
        .also { json ->
            (request.maxTokens ?: config.maxTokens)?.let { value ->
                when (compatibility.outputTokenField) {
                    ExternalOutputTokenField.MAX_TOKENS -> json.put("max_tokens", value)
                    ExternalOutputTokenField.MAX_COMPLETION_TOKENS -> json.put("max_completion_tokens", value)
                }
            }
            providerRequestSnapshot?.resolvedRequestPatch?.let { patch ->
                val value = patch.customReasoningEffort ?: patch.reasoningEffort?.name?.lowercase()
                if (value != null) {
                    when (patch.reasoningEffortMapping) {
                        ReasoningEffortMapping.FLAT_REASONING_EFFORT -> json.put("reasoning_effort", value)
                        ReasoningEffortMapping.NESTED_REASONING_EFFORT ->
                            json.put("reasoning", JSONObject().put("effort", value))
                    }
                }
            }
        }
    }

    internal fun parseResponse(raw: String, reasoningEnabled: Boolean): AiChatResponse {
        val json = JSONObject(raw)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
            ?: throw AiProviderClientException("Provider response has no choice")
        val message = choice.optJSONObject("message")
            ?: throw AiProviderClientException("Provider response has no message")
        val rawContent = message.opt("content")
        val content = when (rawContent) {
            is String -> rawContent
            is JSONArray -> buildString { for (i in 0 until minOf(rawContent.length(), 128)) { val part = rawContent.optJSONObject(i) ?: continue; if (part.optString("type") == "text" || part.has("text")) append(part.optString("text")) } }
            else -> ""
        }
        if (message.has("tool_calls") && message.opt("tool_calls") !is JSONArray) {
            throw AiProviderClientException("Provider returned an invalid tool-call list.")
        }
        val toolCallsJson = message.optJSONArray("tool_calls")
        if (toolCallsJson != null && toolCallsJson.length() > 2) {
            throw AiProviderClientException("Provider returned too many tool calls.")
        }
        if (toolCallsJson != null && toolCallsJson.length() == 0) {
            throw AiProviderClientException("Provider returned an empty tool-call list.")
        }
        val toolCalls = buildList {
            if (toolCallsJson != null) for (index in 0 until toolCallsJson.length()) {
                val call = toolCallsJson.optJSONObject(index)
                    ?: throw AiProviderClientException("Provider returned an invalid tool call.")
                if (call.opt("type") != "function") {
                    throw AiProviderClientException("Provider returned an unsupported tool type.")
                }
                val id = (call.opt("id") as? String)?.takeIf { it.isNotBlank() && it.length <= 128 }
                    ?: throw AiProviderClientException("Provider returned a tool call without a valid ID.")
                val function = call.optJSONObject("function")
                    ?: throw AiProviderClientException("Provider returned an invalid tool call.")
                val name = (function.opt("name") as? String)?.takeIf { it.isNotBlank() && it.length <= 64 }
                    ?: throw AiProviderClientException("Provider returned an invalid tool name.")
                val arguments = (function.opt("arguments") as? String)?.takeIf { it.isNotBlank() && it.length <= 2_048 }
                    ?: throw AiProviderClientException("Provider returned invalid or excessive tool arguments.")
                add(AiToolCall(id, name, arguments))
            }
        }
        val reasoningContent = if (reasoningEnabled) {
            message.optString("reasoning_content")
                .ifBlank { message.optString("reasoningContent") }
                .takeIf(String::isNotBlank)
        } else {
            null
        }
        val isTruncated = rawContent is JSONArray && rawContent.length() > 128
        val completionStatus = when (choice.opt("finish_reason")) {
            "length" -> AiChatCompletionStatus.OUTPUT_LIMIT_REACHED
            else -> AiChatCompletionStatus.COMPLETE_OR_UNSPECIFIED
        }
        return AiChatResponse(
            id = json.optString("id").takeIf(String::isNotBlank),
            model = json.optString("model").takeIf(String::isNotBlank),
            content = content,
            reasoningContent = reasoningContent,
            completionStatus = completionStatus,
            truncated = isTruncated,
            toolCalls = toolCalls,
            finishReason = choice.optString("finish_reason").takeIf(String::isNotBlank)?.take(64),
        )
    }

    private fun AiRole.toOpenAiRole() = when (this) { AiRole.SYSTEM -> "system"; AiRole.USER -> "user"; AiRole.ASSISTANT -> "assistant"; AiRole.TOOL -> "tool" }
    private fun AiToolChoice.toWireValue() = when (this) {
        AiToolChoice.AUTO -> "auto"
        AiToolChoice.REQUIRED -> "required"
        AiToolChoice.NONE -> "none"
    }
    private fun AiToolParameter.toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        description?.let { put("description", it) }
        items?.let { put("items", it.toJson()) }
    }
    private fun com.projectnuke.fusion.ai.model.AiToolParameters.toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("properties", JSONObject().also { properties ->
            this@toJson.properties.toSortedMap().forEach { (key, value) -> properties.put(key, value.toJson()) }
        })
        put("required", JSONArray(required))
        put("additionalProperties", additionalProperties)
    }
    internal fun isNativeToolProtocolRejection(status: Int, raw: String): Boolean {
        if (status !in setOf(400, 404, 422)) return false
        val normalized = raw.lowercase()
        val mentionsTool = "tool" in normalized || "function" in normalized
        val rejectsTool = listOf("not supported", "unsupported", "unknown parameter", "unrecognized", "not allowed")
            .any(normalized::contains)
        return mentionsTool && rejectsTool
    }
    private fun buildHttpErrorMessage(status: Int, raw: String, secrets: List<String> = emptyList()): String {
        val providerMessage = extractProviderErrorMessageBounded(raw, secrets)
        return when (status) {
            HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> "인증에 실패했습니다. API 키와 권한 설정을 확인해 주세요."
            429 -> "요청 한도를 초과했습니다. 잠시 후 다시 시도해 주세요."
            in 500..599 -> "외부 AI API 서버에 일시적인 문제가 있습니다. 잠시 후 다시 시도해 주세요."
            else -> providerMessage?.let { "외부 AI API 요청에 실패했습니다. $it" } ?: "외부 AI API 요청에 실패했습니다. 상태 코드: $status"
        }
    }
    internal enum class BodyKind(val oversizedMessage: String) { SUCCESS("외부 AI API 응답이 너무 큽니다."), ERROR("외부 AI API 오류 응답이 너무 큽니다.") }
    private fun extractProviderErrorMessageBounded(raw: String, secrets: List<String> = emptyList()): String? = runCatching {
        val json = JSONObject(raw)
        val error = json.opt("error")
        val candidates = listOfNotNull(
            (error as? JSONObject)?.opt("message"),
            json.opt("message"),
            error,
            json.opt("detail"),
        )
        candidates.asSequence()
            .mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }
            .map { sanitizeProviderDiagnostic(it, secrets) }
            .firstOrNull { it.isNotBlank() && !looksLikeHtml(it) }
    }.getOrNull()

    internal fun sanitizeProviderDiagnostic(value: String, secrets: List<String> = emptyList()): String {
        var out = value.trim().replace(Regex("[\\r\\n\\t]+"), " ")
        for (secret in secrets) {
            if (secret.isNotBlank()) out = out.replace(secret, "[redacted]")
        }
        return out
            .replace(Regex("(?i)\\bbearer\\s+[^\\s,;]+"), "Bearer [redacted]")
            .replace(Regex("(?i)\\bauthorization\\s*[:=]\\s*[^\\s,;]+"), "Authorization: [redacted]")
            .replace(Regex("(?i)\\b(x-api-key|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+"), "[redacted]")
            .take(180)
    }

    private fun looksLikeHtml(value: String): Boolean = Regex(
        "(?i)<\\s*(?:!doctype|html|head|body|script|style|pre)(?:\\s|>)",
    ).containsMatchIn(value)

    internal data class DiagnosticBody(val text: String, val truncated: Boolean)
    internal companion object {
        const val MaxSuccessBodyBytes = 2 * 1024 * 1024; const val MaxErrorBodyBytes = 64 * 1024
        internal fun readDiagnosticBody(stream: InputStream, maxBytes: Int, context: CoroutineContext): DiagnosticBody {
            require(maxBytes >= 0) { "maxBytes must be non-negative" }
            val buffer = ByteArray(8192)
            val output = ByteArrayOutputStream(maxBytes)
            var total = 0
            var truncated = false
            stream.use { input ->
                while (!truncated) {
                    context.ensureActive()
                    val remaining = maxBytes - total
                    val readLimit = if (remaining >= buffer.size) buffer.size else (remaining + 1).coerceAtMost(buffer.size).coerceAtLeast(1)
                    val read = input.read(buffer, 0, readLimit)
                    if (read < 0) break
                    val retained = minOf(read, remaining)
                    if (retained > 0) output.write(buffer, 0, retained)
                    total += retained
                    if (read > remaining) truncated = true
                }
            }
            return DiagnosticBody(output.toString(Charsets.UTF_8.name()), truncated)
        }

        internal fun readDiagnosticBodySafely(stream: InputStream, maxBytes: Int, context: CoroutineContext): DiagnosticBody = try {
            readDiagnosticBody(stream, maxBytes, context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            DiagnosticBody(text = "", truncated = false)
        }

        internal fun readBoundedBody(stream: InputStream, maxBytes: Int, bodyKind: BodyKind, context: CoroutineContext): String {
            val buffer = ByteArray(8192); val output = ByteArrayOutputStream(maxBytes.coerceAtMost(64 * 1024)); var total = 0
            stream.use { input -> while (true) { context.ensureActive(); val remaining = maxBytes - total; val read = input.read(buffer, 0, if (remaining >= buffer.size) buffer.size else remaining + 1); if (read < 0) break; if (read > remaining) throw AiProviderClientException(bodyKind.oversizedMessage); output.write(buffer, 0, read); total += read } }
            return output.toString(Charsets.UTF_8.name())
        }
    }
}

@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
private fun <T> kotlinx.coroutines.CancellableContinuation<T>.tryResumeSuccess(value: T) { tryResume(value)?.also(::completeResume) }
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
private fun kotlinx.coroutines.CancellableContinuation<*>.tryResumeFailure(error: Throwable) { tryResumeWithException(error)?.also(::completeResume) }

/**
 * Hard total wall-clock bound for one external provider request, from the
 * start of the network operation through body processing. Socket
 * connect/read timeouts only bound inactivity; a slow-drip server can defeat
 * them by sending bytes frequently. This deadline cannot be bypassed by
 * dripping and always disconnects the active connection on expiry.
 */
internal object ExternalRequestDeadlinePolicy {
    const val TOTAL_WALL_CLOCK_TIMEOUT_MS: Long = 10 * 60 * 1000L
    const val TOTAL_TIMEOUT_MESSAGE: String = "외부 AI API 요청 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요."
}
class AiProviderClientException(
    message: String,
    cause: Throwable? = null,
    /** True only for a typed provider rejection of the native tool envelope. */
    val nativeToolProtocolUnsupported: Boolean = false,
    /** True when the exact request-bound egress gate denied transport. */
    val egressAuthorizationDenied: Boolean = false,
) : Exception(message, cause)
