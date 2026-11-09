package com.projectnuke.fusion.retrieval

import org.json.JSONArray
import org.json.JSONObject

// Local attachment provenance metadata, rendered separately from web search
// sources. Never fabricates URLs, never claims verification, never mixes into
// web Evidence semantics. New PDF entries may carry a real 1-based
// pageNumber; old entries without it remain fully readable (backward
// compatible). Offsets for PDF entries are page-local.
data class LocalRagSourceEntry(
    val fileName: String,
    val attachmentId: String,
    val chunkIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val contentHashHex: String,
    val excerpt: String,
    val rank: Int,
    val lexicalScore: Int,
    val semanticScore: Double?,
    val pageNumber: Int? = null,
    val ocrDerived: Boolean = false,
)

object LocalRagSourceMetadataLimits {
    const val MAX_SOURCES = LocalRagBounds.FINAL_TOP_K
    const val MAX_FILE_NAME_LENGTH = 180
    const val MAX_ATTACHMENT_ID_LENGTH = 128
    const val MAX_EXCERPT_LENGTH = 1_400
    const val MAX_HASH_LENGTH = 64
    const val MAX_RAW_JSON_CHARS = 64_000
}

object LocalRagSourceMetadata {
    private const val START = "[FUSION_LOCAL_RAG_SOURCES_JSON]"
    private const val END = "[/FUSION_LOCAL_RAG_SOURCES_JSON]"

    /** True when the content carries local-RAG provenance (private-derived). */
    fun hasProvenance(content: String): Boolean = content.contains(START)

    fun append(content: String, sources: List<LocalRagSourceEntry>): String {
        if (sources.isEmpty()) return content
        val array = JSONArray()
        sources.take(LocalRagSourceMetadataLimits.MAX_SOURCES).forEach { entry ->
            val json = JSONObject()
                .put("fileName", entry.fileName.take(LocalRagSourceMetadataLimits.MAX_FILE_NAME_LENGTH))
                .put("attachmentId", entry.attachmentId.take(LocalRagSourceMetadataLimits.MAX_ATTACHMENT_ID_LENGTH))
                .put("chunkIndex", entry.chunkIndex)
                .put("startOffset", entry.startOffset)
                .put("endOffset", entry.endOffset)
                .put("contentHashHex", entry.contentHashHex.take(LocalRagSourceMetadataLimits.MAX_HASH_LENGTH))
                .put("excerpt", entry.excerpt.take(LocalRagSourceMetadataLimits.MAX_EXCERPT_LENGTH))
                .put("rank", entry.rank)
                .put("lexicalScore", entry.lexicalScore)
                .put("semanticScore", entry.semanticScore)
                .put("ocrDerived", entry.ocrDerived)
            // Real 1-based PDF page; omitted for plain-text documents so old
            // persisted envelopes stay semantically identical.
            if (entry.pageNumber != null) {
                json.put("pageNumber", entry.pageNumber)
            }
            array.put(json)
        }
        val encoded = array.toString()
        if (encoded.length > LocalRagSourceMetadataLimits.MAX_RAW_JSON_CHARS) return content.trimEnd()
        return content.trimEnd() + "\n\n$START\n$encoded\n$END"
    }

    fun strip(content: String): String =
        Regex("""(?s)\Q$START\E.*?\Q$END\E""").replace(content, "").trimEnd()

    fun parse(content: String): List<LocalRagSourceEntry> {
        val start = content.indexOf(START)
        if (start < 0) return emptyList()
        val bodyStart = start + START.length
        val end = content.indexOf(END, bodyStart)
        if (end < 0 || end - bodyStart > LocalRagSourceMetadataLimits.MAX_RAW_JSON_CHARS) return emptyList()
        val rawJson = content.substring(bodyStart, end).trim()
        if (rawJson.length > LocalRagSourceMetadataLimits.MAX_RAW_JSON_CHARS) return emptyList()
        return runCatching {
            val array = JSONArray(rawJson)
            if (array.length() > LocalRagSourceMetadataLimits.MAX_SOURCES) return@runCatching emptyList()
            buildList<LocalRagSourceEntry> {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: return@runCatching emptyList()
                    val lexical = obj.optInt("lexicalScore", 0)
                    val semantic = obj.optDouble("semanticScore")
                    val pageNumber = if (obj.has("pageNumber") && !obj.isNull("pageNumber")) {
                        obj.optInt("pageNumber", 0).takeIf { it > 0 }
                    } else {
                        null
                    }
                    add(
                        LocalRagSourceEntry(
                            fileName = boundedString(obj, "fileName", LocalRagSourceMetadataLimits.MAX_FILE_NAME_LENGTH) ?: "",
                            attachmentId = boundedString(obj, "attachmentId", LocalRagSourceMetadataLimits.MAX_ATTACHMENT_ID_LENGTH) ?: "",
                            chunkIndex = obj.optInt("chunkIndex", 0),
                            startOffset = obj.optInt("startOffset", 0),
                            endOffset = obj.optInt("endOffset", 0),
                            contentHashHex = boundedString(obj, "contentHashHex", LocalRagSourceMetadataLimits.MAX_HASH_LENGTH) ?: "",
                            excerpt = boundedString(obj, "excerpt", LocalRagSourceMetadataLimits.MAX_EXCERPT_LENGTH) ?: "",
                            rank = obj.optInt("rank", i + 1),
                            lexicalScore = lexical,
                            semanticScore = if (!obj.isNull("semanticScore") && obj.has("semanticScore")) obj.optDouble("semanticScore") else null,
                            pageNumber = pageNumber,
                            ocrDerived = obj.optBoolean("ocrDerived", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun boundedString(obj: JSONObject, field: String, maxLength: Int): String? {
        if (!obj.has(field) || obj.isNull(field)) return null
        val value = obj.get(field)
        return if (value is String && value.length <= maxLength) value else null
    }
}
