package com.projectnuke.fusion.chat

import org.json.JSONObject
import com.projectnuke.fusion.search.FusionWebSearch
import com.projectnuke.fusion.search.SearchEvidenceClipSafety

/**
 * Compacts only complete model-facing search result records in the final user
 * message. The explicit user request and the external-evidence envelope are
 * preserved byte-for-byte; malformed evidence is never partially rewritten.
 */
internal object SearchEvidencePromptCompactor {
    private const val FUSION_EVIDENCE_HEADER = "[FUSION_WEB_SEARCH_EVIDENCE]"
    private const val EXTERNAL_BEGIN = "[UNTRUSTED_EXTERNAL_EVIDENCE_BEGIN]"
    private const val EXTERNAL_END = "[UNTRUSTED_EXTERNAL_EVIDENCE_END]"
    private const val BASE_SNIPPET_CHARS = 320
    private val BROAD_NEWS_SNIPPET_LIMITS = listOf(160, 96, 48)

    private data class ResultRecord(val index: Int, val json: JSONObject)
    private data class ParsedEvidence(
        val content: String,
        val bodyStart: Int,
        val bodyEnd: Int,
        val headerLines: List<String>,
        val records: List<ResultRecord>,
        val trailingLines: List<String>,
    )

    /** Largest-to-smallest complete evidence variants that remain answer-sufficient. */
    fun compactedUserContents(
        content: String,
        evidenceStatusByCount: Map<Int, String> = emptyMap(),
    ): List<String> {
        val parsed = parse(content) ?: return listOf(content)
        if (parsed.records.isEmpty()) return listOf(content)

        val variants = linkedSetOf<String>()
        if (isGenericBroadNewsRequest(content)) {
            BROAD_NEWS_SNIPPET_LIMITS.forEach { snippetLimit ->
                if (!isSnippetReductionSafe(parsed.records, snippetLimit)) return@forEach
                variants += render(parsed, parsed.records, snippetLimit, evidenceStatusByCount)
            }
        }
        for (count in parsed.records.size - 1 downTo 1) {
            if (evidenceStatusByCount[count] == "ANSWER_INSUFFICIENT") continue
            variants += render(parsed, parsed.records.take(count), BASE_SNIPPET_CHARS, evidenceStatusByCount)
        }
        return listOf(content) + variants.filter { it != content }
    }

    private fun isSnippetReductionSafe(records: List<ResultRecord>, limit: Int): Boolean = records.all { record ->
        val snippet = record.json.optString("snippet", "")
        val reduced = takeCodePoints(snippet, limit)
        !SearchEvidenceClipSafety.dropsMeaningChangingContext(snippet, reduced)
    }

    private fun isGenericBroadNewsRequest(content: String): Boolean {
        val requestMarker = "Current user request:\n"
        val contractMarker = "\n\n[FUSION_SEARCH_ANSWER_CONTRACT]"
        val requestStart = content.indexOf(requestMarker)
        if (requestStart < 0) return false
        val bodyStart = requestStart + requestMarker.length
        val contractStart = content.indexOf(contractMarker, bodyStart)
        if (contractStart < 0) return false
        val request = content.substring(bodyStart, contractStart).trim()
        return FusionWebSearch.isBroadNewsQuery(request)
    }

    /** Null means this message has no well-formed Fusion search evidence envelope. */
    fun admittedResultCount(content: String): Int? = parse(content)?.records?.size

    private fun render(
        parsed: ParsedEvidence,
        selected: List<ResultRecord>,
        snippetLimit: Int,
        evidenceStatusByCount: Map<Int, String>,
    ): String {
        val status = evidenceStatusByCount[selected.size]
            ?.takeIf { it == "USABLE" || it == "LIMITED" || it == "ANSWER_INSUFFICIENT" }
            ?: if (selected.size == parsed.records.size) {
                parsed.headerLines.firstOrNull { it.startsWith("Evidence status:") }
                    ?.substringAfter(':')?.trim()?.takeIf { it == "USABLE" || it == "LIMITED" || it == "ANSWER_INSUFFICIENT" }
                    ?: "LIMITED"
            } else {
                "LIMITED"
            }
        val header = parsed.headerLines.map { line ->
            when {
                line.startsWith("Result count:") -> "Result count: ${selected.size}"
                line.startsWith("Evidence status:") -> "Evidence status: $status"
                line.startsWith("RETRIEVAL_USABLE=") -> "RETRIEVAL_USABLE=${status != "LIMITED"}"
                line.startsWith("ANSWER_SUFFICIENCY=") -> "ANSWER_SUFFICIENCY=${if (status == "USABLE") "SUFFICIENT_OR_NOT_REQUIRED" else "INSUFFICIENT"}"
                else -> line
            }
        }
        val records = selected.mapIndexed { index, record ->
            val snippet = record.json.optString("snippet", "")
            val boundedSnippet = takeCodePoints(snippet, snippetLimit)
            val json = JSONObject(record.json.toString()).put("snippet", boundedSnippet)
            "UNTRUSTED_RESULT_${index + 1}_BEGIN\n" +
                json.toString() +
                "\nUNTRUSTED_RESULT_${index + 1}_END"
        }
        val evidenceBody = (header + records + parsed.trailingLines).joinToString("\n")
        var rewritten = parsed.content.substring(0, parsed.bodyStart) +
            evidenceBody + "\n" +
            parsed.content.substring(parsed.bodyEnd)
        rewritten = rewritten.replaceFirst(Regex("EVIDENCE_STATUS=(?:USABLE|LIMITED|ANSWER_INSUFFICIENT)"), "EVIDENCE_STATUS=$status")
        return rewritten
    }

    private fun parse(content: String): ParsedEvidence? {
        val evidenceHeader = content.indexOf(FUSION_EVIDENCE_HEADER)
        if (evidenceHeader < 0) return null
        val beginIndex = content.indexOf(EXTERNAL_BEGIN, evidenceHeader + FUSION_EVIDENCE_HEADER.length)
        if (beginIndex < 0) return null
        val beginLineEnd = content.indexOf('\n', beginIndex).takeIf { it >= 0 } ?: return null
        val endIndex = content.indexOf(EXTERNAL_END, beginLineEnd + 1)
        if (endIndex < 0) return null
        val endLineStart = content.lastIndexOf('\n', endIndex).let { if (it < 0) 0 else it + 1 }
        if (content.substring(endLineStart, endIndex).isNotBlank()) return null
        val bodyStart = beginLineEnd + 1
        val bodyEnd = endLineStart
        if (bodyEnd < bodyStart) return null

        val lines = content.substring(bodyStart, bodyEnd).trimEnd('\n', '\r').split('\n')
        val markerRegex = Regex("^UNTRUSTED_RESULT_(\\d+)_BEGIN$")
        val headerLines = mutableListOf<String>()
        val records = mutableListOf<ResultRecord>()
        var trailingStart = lines.size
        var cursor = 0
        var foundRecord = false
        while (cursor < lines.size) {
            val match = markerRegex.matchEntire(lines[cursor])
            if (match == null) {
                if (foundRecord) {
                    trailingStart = cursor
                    break
                }
                headerLines += lines[cursor]
                cursor++
                continue
            }
            foundRecord = true
            val index = match.groupValues[1].toIntOrNull() ?: return null
            if (cursor + 2 >= lines.size ||
                !lines[cursor + 2].equals("UNTRUSTED_RESULT_${index}_END")
            ) return null
            val json = runCatching { JSONObject(lines[cursor + 1]) }.getOrNull() ?: return null
            if (!setOf("title", "source", "published", "snippet").all(json::has)) return null
            records += ResultRecord(index, json)
            cursor += 3
        }
        if (!foundRecord || headerLines.none { it.startsWith("Result count:") }) return null
        val trailing = lines.drop(trailingStart).filter(String::isNotBlank)
        return ParsedEvidence(content, bodyStart, bodyEnd, headerLines, records, trailing)
    }

    private fun takeCodePoints(value: String, maxCodePoints: Int): String {
        val count = value.codePointCount(0, value.length)
        if (count <= maxCodePoints) return value
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints.coerceAtLeast(0)))
    }
}
