package com.projectnuke.fusion.modelzoo

import android.content.Context
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Canonical pinned Kanana-2 prompt source and the only K0.5 source transform.
 *
 * The authoritative bytes live in the asset named by [ASSET_PATH]. They are
 * copied from kakaocorp/kanana-2-1.3b-instruct at revision
 * bf4786aa2a1908adce942d53976270132732f720, chat_template.jinja.
 */
internal object KananaCompatibilityTemplateSource {
    const val ASSET_PATH = "kanana-2-1.3b-instruct.bf4786aa.chat_template.jinja"
    const val PINNED_SOURCE_SHA256 =
        "B8EE6B31575EADA17EBBE73D3F1AC65D3EFDE64F0A25FF922031DEC7E1CAE3E3"

    private val wrapperNames = setOf("generation", "endgeneration")

    fun readPinnedSource(context: Context): String =
        context.assets.open(ASSET_PATH).use { stream ->
            pinnedSourceFromBytes(stream.readBytes())
        }

    /**
     * Rejects a missing, truncated, or altered packaged asset before it can be
     * supplied to LiteRT's process-global template override.
     */
    internal fun pinnedSourceFromBytes(bytes: ByteArray): String {
        val actualHash = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02X".format(byte) }
        check(actualHash == PINNED_SOURCE_SHA256) {
            "Pinned Kanana template SHA-256 mismatch"
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    /**
     * Removes only complete Jinja generation/endgeneration statement tags.
     * The scanner recognizes all four whitespace-control spellings without
     * interpreting ordinary text containing the word "generation".
     */
    fun removeUnsupportedGenerationStatements(source: String): String {
        val result = StringBuilder(source.length)
        var cursor = 0
        while (cursor < source.length) {
            val open = source.indexOf("{%", cursor)
            if (open < 0) {
                result.append(source, cursor, source.length)
                break
            }
            result.append(source, cursor, open)
            val close = source.indexOf("%}", open + 2)
            if (close < 0) {
                result.append(source, open, source.length)
                break
            }
            val rawBody = source.substring(open + 2, close)
            val normalizedBody = rawBody
                .removePrefix("-")
                .removeSuffix("-")
                .trim()
            if (normalizedBody in wrapperNames) {
                // Omit only the statement span, including its optional
                // whitespace-control dashes. Everything else is copied.
            } else {
                result.append(source, open, close + 2)
            }
            cursor = close + 2
        }
        return result.toString()
    }

    /**
     * Normalizes LiteRT's historical "model" role to the canonical "assistant"
     * role expected by the pinned Kanana template.
     *
     * The transformation is mechanically bounded to the exact assistant branch:
     * 1. Changes the branch condition from `message.role == "assistant"` to
     *    accept both "assistant" and "model".
     * 2. Replaces every `message.role` reference within that branch with the
     *    literal string "assistant" so the rendered protocol tokens are always
     *    canonical (never "»model").
     *
     * Other role branches (user, system, tool) are untouched.
     */
    fun normalizeLiteRtModelRole(source: String): String {
        // The exact assistant branch start pattern in the canonical template.
        val assistantBranchStart = "{%- elif message.role == \"assistant\" %}"
        val startIdx = source.indexOf(assistantBranchStart)
        if (startIdx < 0) return source

        // Find the end of the assistant branch (next elif/else/endif at same level).
        // We scan forward tracking Jinja block depth.
        val endIdx = findAssistantBranchEnd(source, startIdx)
        if (endIdx < 0) return source

        val beforeBranch = source.substring(0, startIdx)
        val branch = source.substring(startIdx, endIdx)
        val afterBranch = source.substring(endIdx)

        // 1. Change condition to accept both "assistant" and "model"
        val conditionFixed = branch.replaceFirst(
            "message.role == \"assistant\"",
            "message.role == \"assistant\" or message.role == \"model\""
        )

        // 2. Replace all message.role references in the branch with literal "assistant"
        // This must be done carefully to only affect the assistant branch.
        val roleNormalized = replaceMessageRoleInBranch(conditionFixed)

        return beforeBranch + roleNormalized + afterBranch
    }

    private fun findAssistantBranchEnd(source: String, startIdx: Int): Int {
        // Start scanning from AFTER the assistant branch start pattern
        val assistantBranchStartPattern = "{%- elif message.role == \"assistant\" %}"
        var cursor = startIdx + assistantBranchStartPattern.length
        var depth = 0
        while (cursor < source.length) {
            val open = source.indexOf("{%", cursor)
            if (open < 0) return -1
            val close = source.indexOf("%}", open + 2)
            if (close < 0) return -1
            val rawBody = source.substring(open + 2, close)
            val normalizedBody = rawBody.removePrefix("-").removeSuffix("-").trim()
            when {
                normalizedBody.startsWith("elif ") || normalizedBody.startsWith("else") -> {
                    if (depth == 0) {
                        // This is the end of our assistant branch (next elif/else at same level)
                        return open
                    }
                }
                normalizedBody == "endif" -> {
                    if (depth == 0) {
                        // endif at depth 0 ends the entire if/elif/else chain
                        return open
                    } else {
                        // endif closes a nested if
                        depth--
                    }
                }
                normalizedBody.startsWith("if ") || normalizedBody.startsWith("for ") || normalizedBody.startsWith("macro ") -> {
                    depth++
                }
                normalizedBody == "endfor" || normalizedBody == "endmacro" -> {
                    depth--
                }
            }
            cursor = close + 2
        }
        return -1
    }

    private fun replaceMessageRoleInBranch(branch: String): String {
        // Replace message.role with "assistant" ONLY in output contexts ({{ ... }}),
        // not in condition/control contexts ({% ... %}).
        // We scan through the branch, tracking whether we're inside {{ ... }} or {% ... %}.
        val result = StringBuilder(branch.length)
        var cursor = 0
        var inOutputBlock = false  // inside {{ ... }}
        var inControlBlock = false // inside {% ... %}

        while (cursor < branch.length) {
            // Check for block starts/ends
            val outputOpen = branch.indexOf("{{", cursor)
            val outputClose = if (inOutputBlock) branch.indexOf("}}", cursor) else -1
            val controlOpen = branch.indexOf("{%", cursor)
            val controlClose = if (inControlBlock) branch.indexOf("%}", cursor) else -1

            // Find the next event
            val nextEvents = mutableListOf<Pair<Int, String>>()
            if (outputOpen >= 0) nextEvents.add(outputOpen to "outputOpen")
            if (outputClose >= 0) nextEvents.add(outputClose to "outputClose")
            if (controlOpen >= 0) nextEvents.add(controlOpen to "controlOpen")
            if (controlClose >= 0) nextEvents.add(controlClose to "controlClose")

            if (nextEvents.isEmpty()) {
                // No more block markers, check for message.role in remaining text
                val remaining = branch.substring(cursor)
                if (inOutputBlock && !inControlBlock) {
                    // In output context: replace message.role
                    val replaced = remaining.replace("message.role", "\"assistant\"")
                    result.append(replaced)
                } else {
                    // In control context or plain text: keep as-is
                    result.append(remaining)
                }
                break
            }

            // Find the earliest event
            val (nextPos, eventType) = nextEvents.minByOrNull { it.first }!!

            // Append text up to the event
            val segment = branch.substring(cursor, nextPos)
            if (inOutputBlock && !inControlBlock) {
                // In output context: replace message.role in the segment
                result.append(segment.replace("message.role", "\"assistant\""))
            } else {
                // In control context or plain text: keep as-is
                result.append(segment)
            }

            // Handle the event
            when (eventType) {
                "outputOpen" -> {
                    inOutputBlock = true
                    result.append("{{")
                    cursor = nextPos + 2
                }
                "outputClose" -> {
                    inOutputBlock = false
                    result.append("}}")
                    cursor = nextPos + 2
                }
                "controlOpen" -> {
                    inControlBlock = true
                    result.append("{%")
                    cursor = nextPos + 2
                }
                "controlClose" -> {
                    inControlBlock = false
                    result.append("%}")
                    cursor = nextPos + 2
                }
            }
        }
        return result.toString()
    }

    fun compatibilitySource(context: Context): String =
        normalizeLiteRtModelRole(removeUnsupportedGenerationStatements(readPinnedSource(context)))
}
