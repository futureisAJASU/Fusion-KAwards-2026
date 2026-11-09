package com.projectnuke.fusion.ai.network

import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.ExternalDiscoveredModel
import com.projectnuke.fusion.ai.model.ExternalModelDiscoveryResult
import com.projectnuke.fusion.ai.data.AiProviderValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URI
import java.net.SocketTimeoutException
import java.net.URL

class ExternalModelDiscoveryTransport(
    private val secretStore: com.projectnuke.fusion.ai.secure.SecretStore,
    private val connectionFactory: ((URL) -> HttpURLConnection)? = null,
) : ExternalModelDiscoverySource {

    companion object {
        const val MaxDiscoveryResponseBytes = 256 * 1024 // 256 KB bounded
        const val MaxDiagnosticBodyBytes = 32 * 1024
        const val MaxDiscoveredEntries = 512
        const val MaxRawEntries = 2048
        const val MaxModelIdLength = 256
        const val MaxDisplayNameLength = 128
        const val MaxOwnedByLength = 128
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.InternalCoroutinesApi::class)
    override suspend fun discover(
        config: AiProviderConfig,
        discoveryIdentity: ExternalDiscoveryIdentity,
        ephemeralApiKey: String?,
    ): ExternalModelDiscoveryResult {
        val endpoint = try {
            OpenAiCompatibleEndpointResolver.resolve(config.baseUrl).models
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return ExternalModelDiscoveryResult.NetworkError("Discovery setup failed")
        }
        val trimmedEndpoint = endpoint.trim()

        // Transport gate BEFORE credential retrieval or connection opening
        val transportUri = runCatching { URI(trimmedEndpoint) }.getOrNull()
        if (transportUri == null ||
            !transportUri.scheme.equals("https", ignoreCase = true) ||
            transportUri.host.isNullOrBlank()
        ) {
            return ExternalModelDiscoveryResult.Unsupported
        }

        // Discovery may use an unsaved key, but it must obey the same bounded
        // credential contract as persisted provider settings. Reject before
        // opening a connection and never echo the key in the result.
        if (ephemeralApiKey != null && ephemeralApiKey.length > AiProviderValidator.MAX_SECRET) {
            return ExternalModelDiscoveryResult.AuthenticationFailed
        }

        // Ephemeral key takes precedence, otherwise use persisted secret.
        // Ephemeral key is NEVER persisted by discovery and never appears in errors/identity/cache.
        val credential = resolveCredential(config, ephemeralApiKey)
        if (credential == null && config.authMode != AiProviderAuthMode.NONE) {
            return ExternalModelDiscoveryResult.AuthenticationFailed
        }

        return withContext(Dispatchers.IO) {
            try {
                val connection = createConnection(trimmedEndpoint, config, credential)
                val ctx = kotlinx.coroutines.currentCoroutineContext()

                kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { connection.disconnect() }
                    try {
                        ctx.ensureActive()
                        val status = connection.responseCode
                        val bodyText = if (status in 200..299) {
                            readBoundedResponseBody(connection.inputStream, MaxDiscoveryResponseBytes, ctx)
                        } else {
                            connection.errorStream?.let { readDiagnosticResponseBodySafely(it, MaxDiagnosticBodyBytes, ctx).text }.orEmpty()
                        }
                        ctx.ensureActive()
                        if (status == 401 || status == 403) {
                            continuation.tryCompleteException(
                                DiscoveryException(ExternalModelDiscoveryResult.AuthenticationFailed)
                            )
                            return@suspendCancellableCoroutine
                        }
                        if (status == 404) {
                            continuation.tryCompleteException(
                                DiscoveryException(ExternalModelDiscoveryResult.Unsupported)
                            )
                            return@suspendCancellableCoroutine
                        }
                        if (status == 429) {
                            continuation.tryCompleteException(
                                DiscoveryException(ExternalModelDiscoveryResult.RateLimited)
                            )
                            return@suspendCancellableCoroutine
                        }
                        if (status in 500..599) {
                            continuation.tryCompleteException(
                                DiscoveryException(ExternalModelDiscoveryResult.NetworkError("Server error: $status"))
                            )
                            return@suspendCancellableCoroutine
                        }
                        if (status !in 200..299) {
                            continuation.tryCompleteException(
                                DiscoveryException(ExternalModelDiscoveryResult.InvalidResponse)
                            )
                            return@suspendCancellableCoroutine
                        }
                        val result = parseDiscoveryResponse(bodyText)
                        continuation.tryComplete(result)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: DiscoveryException) {
                        continuation.tryCompleteException(e)
                    } catch (e: Exception) {
                        continuation.tryCompleteException(
                            DiscoveryException(ExternalModelDiscoveryResult.NetworkError(e.message ?: "Unknown error"))
                        )
                    } finally {
                        connection.disconnect()
                    }
                }
            } catch (e: DiscoveryException) {
                e.result
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                ExternalModelDiscoveryResult.NetworkError("Discovery request timed out")
            } catch (e: MalformedURLException) {
                ExternalModelDiscoveryResult.NetworkError("Discovery setup failed")
            } catch (e: IOException) {
                ExternalModelDiscoveryResult.NetworkError("Discovery network connection failed")
            } catch (e: Exception) {
                ExternalModelDiscoveryResult.NetworkError("Discovery setup failed")
            }
        }
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private fun <T> CancellableContinuation<T>.tryComplete(value: T) {
        val token = tryResume(value)
        if (token != null) completeResume(token)
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private fun <T> CancellableContinuation<T>.tryCompleteException(error: Throwable) {
        val token = tryResumeWithException(error)
        if (token != null) completeResume(token)
    }

    private suspend fun resolveCredential(config: AiProviderConfig, ephemeralApiKey: String?): String? {
        // Ephemeral key takes precedence when provided (never logged, persisted, or exposed)
        if (ephemeralApiKey?.isNotBlank() == true) {
            return ephemeralApiKey
        }
        // Fall back to persisted secret
        if (config.authMode == AiProviderAuthMode.BEARER_API_KEY ||
            config.authMode == AiProviderAuthMode.CUSTOM_HEADER
        ) {
            return config.apiKeySecretId
                ?.takeIf { it.isNotBlank() }
                ?.let { secretStore.getSecret(it)?.takeIf { s ->
                    s.isNotBlank() && s.length <= AiProviderValidator.MAX_SECRET
                } }
        }
        return null
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun createConnection(endpoint: String, config: AiProviderConfig, credential: String?): HttpURLConnection {
        val conn = if (connectionFactory != null) {
            connectionFactory(URL(endpoint))
        } else {
            URL(endpoint).openConnection() as HttpURLConnection
        }
        return conn.apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            doOutput = false
            when (config.authMode) {
                AiProviderAuthMode.BEARER_API_KEY -> {
                    if (credential != null) setRequestProperty("Authorization", "Bearer $credential")
                }
                AiProviderAuthMode.CUSTOM_HEADER -> {
                    val headerName = config.authHeaderName?.takeIf { it.isNotBlank() } ?: "X-API-Key"
                    if (credential != null) setRequestProperty(headerName, credential)
                }
                AiProviderAuthMode.NONE -> Unit
            }
            setRequestProperty("Accept", "application/json")
        }
    }

    private fun readBoundedResponseBody(
        stream: java.io.InputStream,
        maxBytes: Int,
        context: kotlin.coroutines.CoroutineContext,
    ): String {
        val buffer = ByteArray(8192)
        val outputCapacity = maxBytes.coerceAtMost(256 * 1024)
        val output = java.io.ByteArrayOutputStream(outputCapacity)
        var total = 0
        stream.use { input ->
            while (true) {
                context.ensureActive()
                val remaining = maxBytes - total
                val readLimit = if (remaining >= buffer.size) buffer.size else (remaining + 1).coerceAtMost(buffer.size).coerceAtLeast(1)
                val read = input.read(buffer, 0, readLimit)
                if (read < 0) break
                if (read > remaining) {
                    throw DiscoveryException(ExternalModelDiscoveryResult.InvalidResponse)
                }
                output.write(buffer, 0, read)
                total += read
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private data class DiagnosticBody(val text: String, val truncated: Boolean)

    private fun readDiagnosticResponseBodySafely(
        stream: java.io.InputStream,
        maxBytes: Int,
        context: kotlin.coroutines.CoroutineContext,
    ): DiagnosticBody = try {
        val buffer = ByteArray(8192)
        val output = java.io.ByteArrayOutputStream(maxBytes)
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
        DiagnosticBody(output.toString(Charsets.UTF_8.name()), truncated)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        DiagnosticBody(text = "", truncated = false)
    }

    internal fun parseDiscoveryResponseForTest(raw: String): ExternalModelDiscoveryResult =
        runCatching { parseDiscoveryResponse(raw) }
            .getOrElse { ExternalModelDiscoveryResult.InvalidResponse }

    private fun parseDiscoveryResponse(raw: String): ExternalModelDiscoveryResult {
        val json = runCatching { org.json.JSONObject(raw) }.getOrElse { return ExternalModelDiscoveryResult.InvalidResponse }
        val data = json.optJSONArray("data") ?: return ExternalModelDiscoveryResult.InvalidResponse

        val seen = mutableSetOf<String>()
        val result = mutableListOf<ExternalDiscoveredModel>()
        for (i in 0 until minOf(data.length(), MaxRawEntries)) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id", "").trim()
            if (id.isBlank()) continue
            if (id.length > MaxModelIdLength) continue
            if (!seen.add(id)) continue // deduplicate by exact id
            val displayName = item.optString("displayName", id).trim().ifBlank { id }
                .take(MaxDisplayNameLength)
            val ownedBy = item.optString("owned_by", "").trim().ifBlank { null }
                ?.take(MaxOwnedByLength)
            result.add(ExternalDiscoveredModel(
                id = id,
                displayName = displayName,
                ownedBy = ownedBy,
            ))
            if (result.size >= MaxDiscoveredEntries) break
        }
        // Deterministic sort by model id
        val sorted = result.sortedBy { it.id }
        // Empty data array OR no valid models after filtering -> Empty (not Success(emptyList()))
        if (sorted.isEmpty()) {
            return ExternalModelDiscoveryResult.Empty
        }
        return ExternalModelDiscoveryResult.Success(
            models = sorted,
            fetchedAt = System.currentTimeMillis(),
        )
    }
}

/** Frozen identity used to reject stale discovery results. */
data class ExternalDiscoveryIdentity(
    val providerId: String,
    val endpointIdentity: String,
    val authMode: AiProviderAuthMode,
    val authHeaderIdentity: String?,
    val credentialRevision: Long,
    val operationId: String,
)

private class DiscoveryException(val result: ExternalModelDiscoveryResult) : Exception("Discovery failed: $result")
