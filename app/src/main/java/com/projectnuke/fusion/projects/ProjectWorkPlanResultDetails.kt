package com.projectnuke.fusion.projects

import com.projectnuke.fusion.chat.SelectionSourceIdentity
import com.projectnuke.fusion.chat.SelectionSourceKind
import java.security.MessageDigest

/**
 * Findings D + G: durable SELECTION/EXTRACTION provenance.
 *
 * A process-local [SelectionContextSnapshot.id] UUID is never persisted as
 * provenance. Instead the bounded set of actual selected source identities
 * (+ user-facing titles) is encoded into [ProjectWorkPlanResultSourceEntity]
 * and referenced by a collision-resistant, fixed-length result id:
 *
 * - EXTRACTION: `x:<sha256(stableKey + 0x00 + TYPE)>#<TYPE>`
 * - SELECTION:  `s:<sha256(ordered stableKeys joined by 0x01)>`
 *
 * The full identity + type are always hashed (never lossy prefix
 * truncation), so long identities sharing a prefix and multiple extraction
 * types stay distinct. The details row retains the metadata needed to
 * resolve/display sources and to detect deleted/inaccessible sources on
 * reopen. Titles are display-only; identities are authority. Pure encode,
 * decode, and id routines so the contract is unit-testable without Room.
 */
object ProjectWorkPlanResultDetails {
    const val MAX_SOURCES = 8
    const val MAX_TITLE_CHARS = 160
    const val MAX_JSON_CHARS = 8_000

    data class SourceEntry(
        val identity: SelectionSourceIdentity,
        val title: String,
    )

    fun extractionResultId(identity: SelectionSourceIdentity, type: String): String? {
        if (!identity.isWellFormed()) return null
        val normalized = type.trim().take(32)
        if (normalized.isEmpty()) return null
        val digest = sha256Hex(identity.stableKey() + "\u0000" + normalized)
        return "x:$digest#$normalized"
    }

    fun selectionResultId(identities: List<SelectionSourceIdentity>): String? {
        if (identities.isEmpty() || identities.size > MAX_SOURCES) return null
        if (identities.any { !it.isWellFormed() }) return null
        if (identities.map { it.stableKey() }.distinct().size != identities.size) return null
        val digest = sha256Hex(identities.joinToString("\u0001") { it.stableKey() })
        return "s:$digest"
    }

    fun entriesValid(entries: List<SourceEntry>): Boolean {
        if (entries.isEmpty() || entries.size > MAX_SOURCES) return false
        if (entries.map { it.identity.stableKey() }.distinct().size != entries.size) return false
        return entries.all { it.identity.isWellFormed() && it.title.isNotBlank() }
    }

    fun encodeSources(entries: List<SourceEntry>): String? {
        if (!entriesValid(entries)) return null
        val json = buildString {
            append('[')
            entries.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                append('{')
                appendField("kind", entry.identity.kind.name)
                appendFieldOpt("conversationId", entry.identity.conversationId, first = false)
                appendFieldOpt("projectId", entry.identity.projectId, first = false)
                appendFieldOpt("fileId", entry.identity.fileId, first = false)
                appendFieldOpt("managedPath", entry.identity.managedPath, first = false)
                appendFieldOpt("memoryId", entry.identity.memoryId, first = false)
                appendFieldOpt("savedKnowledgeId", entry.identity.savedKnowledgeId, first = false)
                append(',')
                append("\"title\":")
                appendQuoted(entry.identity.let { entry.title.trim().take(MAX_TITLE_CHARS) })
                append('}')
            }
            append(']')
        }
        if (json.length > MAX_JSON_CHARS) return null
        return json
    }

    fun decodeSources(json: String): List<SourceEntry>? {
        if (json.isBlank() || json.length > MAX_JSON_CHARS) return null
        return try {
            decodeArray(json.trim())?.takeIf(::entriesValid)
        } catch (_: Exception) {
            null
        }
    }

    // ── minimal strict JSON writer/reader for the fixed schema ──

    private fun StringBuilder.appendField(name: String, value: String) {
        append('"').append(name).append("\":").appendQuoted(value)
    }

    private fun StringBuilder.appendFieldOpt(name: String, value: Long?, first: Boolean) {
        if (!first) append(',')
        append('"').append(name).append("\":")
        if (value == null) append("null") else append(value.toString())
    }

    private fun StringBuilder.appendFieldOpt(name: String, value: String?, first: Boolean) {
        if (!first) append(',')
        append('"').append(name).append("\":")
        if (value == null) append("null") else appendQuoted(value)
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private fun decodeArray(text: String): List<SourceEntry>? {
        if (!text.startsWith('[') || !text.endsWith(']')) return null
        val inner = text.substring(1, text.length - 1)
        if (inner.isBlank()) return null
        val items = splitObjects(inner) ?: return null
        if (items.isEmpty() || items.size > MAX_SOURCES) return null
        return items.map { decodeObject(it) ?: return null }
    }

    private fun splitObjects(inner: String): List<String>? {
        val out = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escaped = false
        var start = -1
        for (index in inner.indices) {
            val char = inner[index]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> {
                        if (depth == 0) start = index
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth < 0) return null
                        if (depth == 0) {
                            if (start < 0) return null
                            out.add(inner.substring(start, index + 1))
                            start = -1
                        }
                    }
                }
            }
        }
        if (depth != 0 || inString) return null
        return out
    }

    private fun decodeObject(text: String): SourceEntry? {
        val map = mutableMapOf<String, String?>()
        var index = 1
        val end = text.length - 1
        while (index < end) {
            while (index < end && (text[index].isWhitespace() || text[index] == ',')) index++
            if (index >= end) break
            if (text[index] != '"') return null
            val key = readString(text, index) ?: return null
            index = key.second
            while (index < end && text[index].isWhitespace()) index++
            if (index >= end || text[index] != ':') return null
            index++
            while (index < end && text[index].isWhitespace()) index++
            if (index >= end) return null
            if (text.startsWith("null", index)) {
                map[key.first] = null
                index += 4
            } else if (text[index] == '"') {
                val value = readString(text, index) ?: return null
                map[key.first] = value.first
                index = value.second
            } else {
                val start = index
                while (index < end && (text[index].isDigit() || text[index] == '-')) index++
                if (start == index) return null
                map[key.first] = text.substring(start, index)
            }
        }
        val kind = map["kind"]?.let { raw ->
            SelectionSourceKind.entries.firstOrNull { it.name == raw }
        } ?: return null
        val title = map["title"]?.takeIf { it.isNotBlank() } ?: return null
        val identity = SelectionSourceIdentity(
            kind = kind,
            conversationId = map["conversationId"]?.toLongOrNull(),
            projectId = map["projectId"]?.toLongOrNull(),
            fileId = map["fileId"]?.toLongOrNull(),
            managedPath = map["managedPath"]?.takeIf { it.isNotBlank() },
            memoryId = map["memoryId"]?.takeIf { it.isNotBlank() },
            savedKnowledgeId = map["savedKnowledgeId"]?.takeIf { it.isNotBlank() },
        )
        if (!identity.isWellFormed()) return null
        return SourceEntry(identity, title.trim().take(MAX_TITLE_CHARS))
    }

    private fun readString(text: String, start: Int): Pair<String, Int>? {
        if (text[start] != '"') return null
        val out = StringBuilder()
        var index = start + 1
        while (index < text.length) {
            val char = text[index]
            if (char == '"') return out.toString() to index + 1
            if (char == '\\') {
                index++
                if (index >= text.length) return null
                when (text[index]) {
                    '"', '\\', '/' -> out.append(text[index])
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        if (index + 4 >= text.length) return null
                        val code = text.substring(index + 1, index + 5).toIntOrNull(16) ?: return null
                        out.append(code.toChar())
                        index += 4
                    }
                    else -> return null
                }
            } else {
                out.append(char)
            }
            index++
        }
        return null
    }

    private fun sha256Hex(canonical: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(canonical.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (byte in bytes) append("%02x".format(byte))
        }
    }
}
