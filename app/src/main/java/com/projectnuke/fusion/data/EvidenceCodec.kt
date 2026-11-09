package com.projectnuke.fusion.data

import com.projectnuke.fusion.util.exactIntOrNull
import com.projectnuke.fusion.util.exactLongOrNull
import org.json.JSONArray
import org.json.JSONObject

sealed class EvidenceCodecResult {
    object Missing : EvidenceCodecResult()
    data class Valid(val report: EvidenceReport) : EvidenceCodecResult()
    object Corrupt : EvidenceCodecResult()
}

sealed interface EvidenceValidation {
    data object Valid : EvidenceValidation
    data class Invalid(val reason: String) : EvidenceValidation
}

/**
 * Centralized durable bounds for persisted evidence reports.
 *
 * Large free-text fields (claim text, snippet) allow up to 10k/4k chars;
 * IDs and version strings use tighter bounds so typos/bloat fail closed.
 *
 * Raw JSON bound rationale: worst-case legal report ~= 500 sources *
 * ~9k chars (~4.5MB) + 200 claims * ~6k chars (~1.2MB) + envelope
 * overhead ~= ~6MB before JSON escaping. [MAX_RAW_JSON_CHARS] = 8,000,000
 * is the hard encoded representation and parser boundary. Semantic bounds
 * alone do not guarantee that a report fits this encoded bound because JSON
 * escaping can expand legal Kotlin strings.
 */
object EvidenceLimits {
    const val MAX_CLAIMS = 200
    const val MAX_SOURCES = 500
    const val MAX_SOURCE_REFS_PER_CLAIM = 16

    const val MAX_ID_LENGTH = 128
    const val MAX_VERIFIER_VERSION_LENGTH = 128
    const val MAX_REASON_CODE_LENGTH = 256
    const val MAX_CLAIM_TEXT_LENGTH = 10_000
    const val MAX_TITLE_LENGTH = 1_000
    const val MAX_SOURCE_NAME_LENGTH = 500
    const val MAX_URL_LENGTH = 2_000
    const val MAX_SNIPPET_LENGTH = 4_000
    const val MAX_PUBLISHED_AT_LENGTH = 128
    const val MAX_PROVIDER_TYPE_LENGTH = 64
    const val MAX_PROVIDER_DISPLAY_NAME_LENGTH = 256
    const val MAX_QUERY_USED_LENGTH = 1_000

    const val MAX_RAW_JSON_CHARS = 8_000_000

    val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
}

object EvidenceCodec {

    private const val CURRENT_SCHEMA_VERSION = 1
    private const val MIN_SCHEMA_VERSION = 1

    fun validate(report: EvidenceReport): EvidenceValidation {
        if (report.schemaVersion < MIN_SCHEMA_VERSION || report.schemaVersion > CURRENT_SCHEMA_VERSION) {
            return EvidenceValidation.Invalid("unsupported schemaVersion")
        }
        if (!EvidenceLimits.SHA256_REGEX.matches(report.answerSha256)) {
            return EvidenceValidation.Invalid("invalid answerSha256")
        }
        if (report.checkedAt < 0) return EvidenceValidation.Invalid("invalid checkedAt")
        if (report.verifierVersion.isBlank() ||
            report.verifierVersion.length > EvidenceLimits.MAX_VERIFIER_VERSION_LENGTH
        ) {
            return EvidenceValidation.Invalid("invalid verifierVersion")
        }
        if (report.claims.size > EvidenceLimits.MAX_CLAIMS) return EvidenceValidation.Invalid("too many claims")
        if (report.sources.size > EvidenceLimits.MAX_SOURCES) return EvidenceValidation.Invalid("too many sources")

        val claimIds = mutableSetOf<String>()
        for (claim in report.claims) {
            if (claim.id.isBlank() || claim.id.length > EvidenceLimits.MAX_ID_LENGTH) {
                return EvidenceValidation.Invalid("invalid claim.id")
            }
            if (!claimIds.add(claim.id)) return EvidenceValidation.Invalid("duplicate claim.id")
            if (claim.text.length > EvidenceLimits.MAX_CLAIM_TEXT_LENGTH) {
                return EvidenceValidation.Invalid("oversized claim.text")
            }
            if (claim.sourceIds.size > EvidenceLimits.MAX_SOURCE_REFS_PER_CLAIM) {
                return EvidenceValidation.Invalid("too many source refs")
            }
            val seenRefs = mutableSetOf<String>()
            for (sid in claim.sourceIds) {
                if (sid.isBlank() || sid.length > EvidenceLimits.MAX_ID_LENGTH) {
                    return EvidenceValidation.Invalid("invalid claim.sourceId")
                }
                if (!seenRefs.add(sid)) return EvidenceValidation.Invalid("duplicate source ref")
            }
            val rc = claim.reasonCode
            if (rc != null && rc.length > EvidenceLimits.MAX_REASON_CODE_LENGTH) {
                return EvidenceValidation.Invalid("oversized reasonCode")
            }
        }

        val sourceIds = mutableSetOf<String>()
        for (source in report.sources) {
            if (source.id.isBlank() || source.id.length > EvidenceLimits.MAX_ID_LENGTH) {
                return EvidenceValidation.Invalid("invalid source.id")
            }
            if (!sourceIds.add(source.id)) return EvidenceValidation.Invalid("duplicate source.id")
            if (source.title.length > EvidenceLimits.MAX_TITLE_LENGTH) {
                return EvidenceValidation.Invalid("oversized source.title")
            }
            if (source.source.length > EvidenceLimits.MAX_SOURCE_NAME_LENGTH) {
                return EvidenceValidation.Invalid("oversized source.source")
            }
            if (source.url.length > EvidenceLimits.MAX_URL_LENGTH) {
                return EvidenceValidation.Invalid("oversized source.url")
            }
            if (source.snippet.length > EvidenceLimits.MAX_SNIPPET_LENGTH) {
                return EvidenceValidation.Invalid("oversized source.snippet")
            }
            val publishedAt = source.publishedAt
            if (publishedAt != null && publishedAt.length > EvidenceLimits.MAX_PUBLISHED_AT_LENGTH) {
                return EvidenceValidation.Invalid("oversized source.publishedAt")
            }
            if (source.providerType.length > EvidenceLimits.MAX_PROVIDER_TYPE_LENGTH) {
                return EvidenceValidation.Invalid("oversized source.providerType")
            }
            if (source.providerDisplayName.length > EvidenceLimits.MAX_PROVIDER_DISPLAY_NAME_LENGTH) {
                return EvidenceValidation.Invalid("oversized source.providerDisplayName")
            }
            if (source.queryUsed.length > EvidenceLimits.MAX_QUERY_USED_LENGTH) {
                return EvidenceValidation.Invalid("oversized source.queryUsed")
            }
        }

        for (claim in report.claims) {
            for (sid in claim.sourceIds) {
                if (sid !in sourceIds) return EvidenceValidation.Invalid("dangling source reference")
            }
        }
        return EvidenceValidation.Valid
    }

    fun isValid(report: EvidenceReport): Boolean = validate(report) is EvidenceValidation.Valid

    /**
     * Validates semantic/domain bounds and the final encoded JSON size.
     * [validate] itself checks only semantic/domain bounds; successful
     * serialization additionally guarantees that [load] will accept the
     * returned representation under [EvidenceLimits.MAX_RAW_JSON_CHARS].
     */
    fun serialize(report: EvidenceReport): String {
        val validation = validate(report)
        if (validation is EvidenceValidation.Invalid) {
            throw IllegalArgumentException("invalid EvidenceReport: ${validation.reason}")
        }
        val json = JSONObject().apply {
            put("schemaVersion", report.schemaVersion)
            put("answerSha256", report.answerSha256)
            put("checkedAt", report.checkedAt)
            put("verifierVersion", report.verifierVersion)
            put("candidateWindowTruncated", report.candidateWindowTruncated)
            put("overlengthSegmentsExcluded", report.overlengthSegmentsExcluded)
            val claimsArray = JSONArray()
            for (claim in report.claims) {
                claimsArray.put(claimToJson(claim))
            }
            put("claims", claimsArray)
            val sourcesArray = JSONArray()
            for (source in report.sources) {
                sourcesArray.put(sourceToJson(source))
            }
            put("sources", sourcesArray)
        }
        val encoded = json.toString()
        if (encoded.length > EvidenceLimits.MAX_RAW_JSON_CHARS) {
            throw IllegalArgumentException("encoded EvidenceReport exceeds MAX_RAW_JSON_CHARS")
        }
        return encoded
    }

    fun load(raw: String?): EvidenceCodecResult {
        // Missing means no persisted artifact exists. A present-but-invalid
        // payload (empty, whitespace, malformed, oversized) is Corrupt.
        if (raw == null) return EvidenceCodecResult.Missing
        if (raw.isEmpty() || raw.isBlank()) return EvidenceCodecResult.Corrupt
        if (raw.length > EvidenceLimits.MAX_RAW_JSON_CHARS) return EvidenceCodecResult.Corrupt
        return try {
            val json = JSONObject(raw)
            validateAndParse(json)
        } catch (_: Exception) {
            EvidenceCodecResult.Corrupt
        }
    }

    private fun validateAndParse(json: JSONObject): EvidenceCodecResult {
        // Required exact-typed report fields; absence or wrong type => Corrupt.
        if (!json.has("schemaVersion") || json.isNull("schemaVersion")) return EvidenceCodecResult.Corrupt
        val schemaVersion = try {
            exactIntOrNull(json.get("schemaVersion"))
        } catch (_: Exception) {
            null
        } ?: return EvidenceCodecResult.Corrupt
        if (schemaVersion < MIN_SCHEMA_VERSION || schemaVersion > CURRENT_SCHEMA_VERSION) {
            return EvidenceCodecResult.Corrupt
        }

        val answerSha256 = requiredString(json, "answerSha256", Int.MAX_VALUE) ?: return EvidenceCodecResult.Corrupt
        if (!EvidenceLimits.SHA256_REGEX.matches(answerSha256)) return EvidenceCodecResult.Corrupt

        if (!json.has("checkedAt") || json.isNull("checkedAt")) return EvidenceCodecResult.Corrupt
        val checkedAt = try {
            exactLongOrNull(json.get("checkedAt"))
        } catch (_: Exception) {
            null
        } ?: return EvidenceCodecResult.Corrupt
        if (checkedAt < 0) return EvidenceCodecResult.Corrupt

        val verifierVersion = requiredString(json, "verifierVersion", EvidenceLimits.MAX_VERIFIER_VERSION_LENGTH)
            ?: return EvidenceCodecResult.Corrupt
        if (verifierVersion.isBlank()) return EvidenceCodecResult.Corrupt

        val candidateWindowTruncated = if (json.has("candidateWindowTruncated")) {
            json.opt("candidateWindowTruncated") as? Boolean ?: return EvidenceCodecResult.Corrupt
        } else {
            false // Backward compatible with schema-v1 reports written before this coverage bit existed.
        }
        val overlengthSegmentsExcluded = if (json.has("overlengthSegmentsExcluded")) {
            json.opt("overlengthSegmentsExcluded") as? Boolean ?: return EvidenceCodecResult.Corrupt
        } else {
            false // Older schema-v1 rows are marked outdated by the verifier rules version.
        }

        if (!json.has("claims") || json.isNull("claims")) return EvidenceCodecResult.Corrupt
        if (!json.has("sources") || json.isNull("sources")) return EvidenceCodecResult.Corrupt
        val claimsRaw: Any = try { json.get("claims") } catch (_: Exception) { return EvidenceCodecResult.Corrupt }
        val sourcesRaw: Any = try { json.get("sources") } catch (_: Exception) { return EvidenceCodecResult.Corrupt }
        if (claimsRaw !is JSONArray || sourcesRaw !is JSONArray) return EvidenceCodecResult.Corrupt
        val claimsArray = claimsRaw
        val sourcesArray = sourcesRaw

        if (claimsArray.length() > EvidenceLimits.MAX_CLAIMS || sourcesArray.length() > EvidenceLimits.MAX_SOURCES) {
            return EvidenceCodecResult.Corrupt
        }

        val claims = mutableListOf<EvidenceClaim>()
        val claimIds = mutableSetOf<String>()
        for (i in 0 until claimsArray.length()) {
            val obj = claimsArray.opt(i)
            if (obj !is JSONObject) return EvidenceCodecResult.Corrupt
            val claim = parseClaim(obj) ?: return EvidenceCodecResult.Corrupt
            if (!claimIds.add(claim.id)) return EvidenceCodecResult.Corrupt
            claims.add(claim)
        }

        val sources = mutableListOf<EvidenceSource>()
        val sourceIds = mutableSetOf<String>()
        for (i in 0 until sourcesArray.length()) {
            val obj = sourcesArray.opt(i)
            if (obj !is JSONObject) return EvidenceCodecResult.Corrupt
            val source = parseSource(obj) ?: return EvidenceCodecResult.Corrupt
            if (!sourceIds.add(source.id)) return EvidenceCodecResult.Corrupt
            sources.add(source)
        }

        val sourceIdSet = sourceIds.toSet()
        for (claim in claims) {
            for (sid in claim.sourceIds) {
                if (sid !in sourceIdSet) return EvidenceCodecResult.Corrupt
            }
        }

        val report = EvidenceReport(
            schemaVersion = schemaVersion,
            answerSha256 = answerSha256,
            checkedAt = checkedAt,
            verifierVersion = verifierVersion,
            claims = claims,
            sources = sources,
            candidateWindowTruncated = candidateWindowTruncated,
            overlengthSegmentsExcluded = overlengthSegmentsExcluded,
        )
        // Central semantic validation also guards in-memory invariants
        // (blank IDs, bounds, duplicate refs, SHA syntax) on the parse path.
        if (validate(report) is EvidenceValidation.Invalid) return EvidenceCodecResult.Corrupt
        return EvidenceCodecResult.Valid(report)
    }

    /** Returns the string value if present with correct type and within [maxLength], else null. */
    private fun requiredString(json: JSONObject, field: String, maxLength: Int): String? {
        if (!json.has(field) || json.isNull(field)) return null
        val raw: Any = try { json.get(field) } catch (_: Exception) { return null }
        if (raw !is String) return null
        if (raw.length > maxLength) return null
        return raw
    }

    private fun optionalNullableString(json: JSONObject, field: String, maxLength: Int): Any? {
        // Absent or JSON null -> Kotlin null (valid). Present non-string or
        // oversized -> NullSentinel (signals corrupt to the caller).
        if (!json.has(field) || json.isNull(field)) return null
        val raw: Any = try { json.get(field) } catch (_: Exception) { return NullSentinel }
        if (raw !is String) return NullSentinel
        if (raw.length > maxLength) return NullSentinel
        return raw
    }

    private object NullSentinel

    private fun parseClaim(obj: JSONObject): EvidenceClaim? {
        val id = requiredString(obj, "id", EvidenceLimits.MAX_ID_LENGTH)
            ?.takeIf { it.isNotBlank() } ?: return null
        val text = requiredString(obj, "text", EvidenceLimits.MAX_CLAIM_TEXT_LENGTH) ?: return null

        if (!obj.has("verdict") || obj.isNull("verdict")) return null
        val verdictRaw: Any = try { obj.get("verdict") } catch (_: Exception) { return null }
        if (verdictRaw !is String) return null
        val verdict = try {
            EvidenceVerdict.valueOf(verdictRaw)
        } catch (_: IllegalArgumentException) {
            return null
        }

        if (!obj.has("sourceIds") || obj.isNull("sourceIds")) return null
        val refsRaw: Any = try { obj.get("sourceIds") } catch (_: Exception) { return null }
        if (refsRaw !is JSONArray) return null
        if (refsRaw.length() > EvidenceLimits.MAX_SOURCE_REFS_PER_CLAIM) return null
        val sourceIds = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (i in 0 until refsRaw.length()) {
            val el = refsRaw.opt(i)
            if (el !is String) return null
            if (el.isBlank() || el.length > EvidenceLimits.MAX_ID_LENGTH) return null
            if (!seen.add(el)) return null
            sourceIds.add(el)
        }

        val reasonCode: String? = when (val v = optionalNullableString(obj, "reasonCode", EvidenceLimits.MAX_REASON_CODE_LENGTH)) {
            NullSentinel -> return null
            else -> v as String?
        }

        return EvidenceClaim(
            id = id,
            text = text,
            verdict = verdict,
            sourceIds = sourceIds,
            reasonCode = reasonCode
        )
    }

    private fun parseSource(obj: JSONObject): EvidenceSource? {
        val id = requiredString(obj, "id", EvidenceLimits.MAX_ID_LENGTH)
            ?.takeIf { it.isNotBlank() } ?: return null
        val title = requiredString(obj, "title", EvidenceLimits.MAX_TITLE_LENGTH) ?: return null
        val source = requiredString(obj, "source", EvidenceLimits.MAX_SOURCE_NAME_LENGTH) ?: return null
        val url = requiredString(obj, "url", EvidenceLimits.MAX_URL_LENGTH) ?: return null
        val snippet = requiredString(obj, "snippet", EvidenceLimits.MAX_SNIPPET_LENGTH) ?: return null

        val publishedAt: String? = when (val v = optionalNullableString(obj, "publishedAt", EvidenceLimits.MAX_PUBLISHED_AT_LENGTH)) {
            NullSentinel -> return null
            else -> v as String?
        }

        val providerType = requiredString(obj, "providerType", EvidenceLimits.MAX_PROVIDER_TYPE_LENGTH) ?: return null
        val providerDisplayName = requiredString(obj, "providerDisplayName", EvidenceLimits.MAX_PROVIDER_DISPLAY_NAME_LENGTH) ?: return null
        val queryUsed = requiredString(obj, "queryUsed", EvidenceLimits.MAX_QUERY_USED_LENGTH) ?: return null

        return EvidenceSource(
            id = id,
            title = title,
            source = source,
            url = url,
            snippet = snippet,
            publishedAt = publishedAt,
            providerType = providerType,
            providerDisplayName = providerDisplayName,
            queryUsed = queryUsed
        )
    }

    private fun claimToJson(claim: EvidenceClaim): JSONObject {
        return JSONObject().apply {
            put("id", claim.id)
            put("text", claim.text)
            put("verdict", claim.verdict.name)
            put("sourceIds", JSONArray(claim.sourceIds))
            claim.reasonCode?.let { put("reasonCode", it) }
        }
    }

    private fun sourceToJson(source: EvidenceSource): JSONObject {
        return JSONObject().apply {
            put("id", source.id)
            put("title", source.title)
            put("source", source.source)
            put("url", source.url)
            put("snippet", source.snippet)
            source.publishedAt?.let { put("publishedAt", it) }
            put("providerType", source.providerType)
            put("providerDisplayName", source.providerDisplayName)
            put("queryUsed", source.queryUsed)
        }
    }
}
