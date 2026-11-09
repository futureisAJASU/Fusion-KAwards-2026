package com.projectnuke.fusion.ai.model

/**
 * Bounded neutral discovered model from an OpenAI-compatible /models endpoint.
 */
data class ExternalDiscoveredModel(
    val id: String,
    val displayName: String = id,
    val ownedBy: String? = null,
)
