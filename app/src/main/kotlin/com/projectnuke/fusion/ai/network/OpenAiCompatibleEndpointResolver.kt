package com.projectnuke.fusion.ai.network

import java.net.URI

data class OpenAiCompatibleEndpoints(
    val apiRoot: String,
    val chatCompletions: String,
    val models: String,
)

/** Single semantic authority for legacy and current OpenAI-compatible URLs. */
object OpenAiCompatibleEndpointResolver {
    fun resolve(value: String): OpenAiCompatibleEndpoints {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "Endpoint is blank" }
        val uri = runCatching { URI(trimmed) }.getOrElse { throw IllegalArgumentException("Endpoint is malformed", it) }
        require(!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) { "Endpoint must include scheme and host" }
        require(uri.rawQuery == null) { "Endpoint query is not supported" }
        require(uri.rawFragment == null) { "Endpoint fragment is not supported" }

        val authority = "${uri.scheme}://${uri.rawAuthority}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val rootPath = if (path.endsWith("/chat/completions")) {
            path.removeSuffix("/chat/completions").trimEnd('/')
        } else {
            path
        }
        val apiRoot = authority + rootPath
        require(apiRoot.isNotBlank()) { "Endpoint root is blank" }
        return OpenAiCompatibleEndpoints(
            apiRoot = apiRoot,
            chatCompletions = "$apiRoot/chat/completions",
            models = "$apiRoot/models",
        )
    }

    fun normalizeForChat(configUrl: String): String = resolve(configUrl).chatCompletions

    fun resolveModelsEndpoint(configUrl: String): String = resolve(configUrl).models
}
