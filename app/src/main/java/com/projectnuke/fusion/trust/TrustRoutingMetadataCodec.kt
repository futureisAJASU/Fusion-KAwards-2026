package com.projectnuke.fusion.trust

import org.json.JSONObject

/** Bounds for the hidden route/provenance marker stored with assistant text. */
object TrustRoutingMetadataLimits {
    const val SCHEMA_VERSION = 1
    const val MAX_RAW_JSON_CHARS = 256
    const val MAX_BLOCK_CHARS = 512
}

/**
 * Strict codec for the terminal hidden trust-route marker. Only route and
 * reason are persisted; classifier output and signals never enter message
 * content.
 */
object TrustRoutingMetadataCodec {
    const val START_MARKER = "[FUSION_TRUST_ROUTE_JSON]"
    const val END_MARKER = "[/FUSION_TRUST_ROUTE_JSON]"

    private val terminalBlockRegex = Regex(
        """(?s)\s*\Q$START_MARKER\E.*?\Q$END_MARKER\E\s*$""",
    )

    fun encode(decision: TrustRoutingDecision): String? {
        val json = JSONObject()
            .put("schemaVersion", TrustRoutingMetadataLimits.SCHEMA_VERSION)
            .put("route", decision.route.name)
            .put("reason", decision.reason.name)
            .toString()
        if (json.length > TrustRoutingMetadataLimits.MAX_RAW_JSON_CHARS) return null
        val block = "$START_MARKER\n$json\n$END_MARKER"
        if (block.length > TrustRoutingMetadataLimits.MAX_BLOCK_CHARS) return null
        return block
    }

    fun append(content: String, decision: TrustRoutingDecision): String {
        val encoded = encode(decision) ?: return content.trimEnd()
        val visibleBase = strip(content).trimEnd()
        return if (visibleBase.isEmpty()) encoded else "$visibleBase\n\n$encoded"
    }

    /** Returns null for absent, malformed, duplicate, future, or ambiguous metadata. */
    fun decode(content: String): TrustRoutingDecision? {
        val start = content.indexOf(START_MARKER)
        if (start < 0) return null
        if (countOccurrences(content, START_MARKER) != 1 || countOccurrences(content, END_MARKER) != 1) {
            return null
        }
        val bodyStart = start + START_MARKER.length
        val end = content.indexOf(END_MARKER, bodyStart)
        if (end < 0 || !content.substring(end + END_MARKER.length).isBlank()) return null
        if (end - bodyStart > TrustRoutingMetadataLimits.MAX_RAW_JSON_CHARS) return null
        if (start > 0 && isInsideMarkdownFence(content.substring(0, start))) return null

        val rawJson = content.substring(bodyStart, end).trim()
        if (rawJson.length > TrustRoutingMetadataLimits.MAX_RAW_JSON_CHARS) return null

        return runCatching {
            val json = JSONObject(rawJson)
            val expectedKeys = setOf("schemaVersion", "route", "reason")
            val actualKeys = json.keys().asSequence().toSet()
            require(actualKeys == expectedKeys)

            val schema = json.get("schemaVersion")
            require(schema is Int || schema is Long)
            require(schema.toLong() == TrustRoutingMetadataLimits.SCHEMA_VERSION.toLong())

            val routeName = json.get("route")
            val reasonName = json.get("reason")
            require(routeName is String && reasonName is String)
            TrustRoutingDecision(
                route = TrustRoute.valueOf(routeName),
                reason = TrustRoutingReason.valueOf(reasonName),
                signals = emptySet(),
            )
        }.getOrNull()
    }

    /** Old or unparseable messages retain existing regeneration behavior. */
    fun allowsRegeneration(content: String): Boolean = decode(content)?.route != TrustRoute.ABSTAIN

    /** Removes only terminal metadata blocks, preserving marker-like prose elsewhere. */
    fun strip(content: String): String {
        var current = content
        while (true) {
            val match = terminalBlockRegex.find(current) ?: return current.trimEnd()
            val prefix = current.substring(0, match.range.first)
            if (prefix.isNotEmpty() && isInsideMarkdownFence(prefix)) return current.trimEnd()
            current = prefix.trimEnd()
        }
    }

    private fun countOccurrences(content: String, needle: String): Int {
        var count = 0
        var offset = 0
        while (true) {
            val found = content.indexOf(needle, offset)
            if (found < 0) return count
            count++
            offset = found + needle.length
        }
    }

    private fun isInsideMarkdownFence(prefix: String): Boolean =
        prefix.windowed(3, 1).count { it == "```" } % 2 == 1
}
