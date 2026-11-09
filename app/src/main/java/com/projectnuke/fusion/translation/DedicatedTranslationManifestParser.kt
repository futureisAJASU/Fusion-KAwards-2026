package com.projectnuke.fusion.translation

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parser/adapter contract for the FUTURE frozen research output.
 *
 * Research delivers a machine-readable manifest; this parser converts it into
 * the production capability + binding pair. Missing identity fields reject
 * the input outright. The parser adds no semantics of its own: language
 * capability, detection, context and style claims are taken verbatim and are
 * validated later by the adoption gate against the acceptance contract.
 */
object DedicatedTranslationManifestParser {

    fun parse(json: JSONObject): ParsedDedicatedManifest? {
        val backendIdVal = requiredString(json, "backendId") ?: return null
        val backendId = backendIdVal.trim()
        val implementationVersionVal = requiredString(json, "implementationVersion") ?: return null
        val implementationVersion = implementationVersionVal.trim()
        val modelArtifactIdVal = requiredString(json, "modelArtifactId") ?: return null
        val modelArtifactId = modelArtifactIdVal.trim()
        val artifactRevisionVal = requiredString(json, "artifactRevision") ?: return null
        val artifactRevision = artifactRevisionVal.trim()
        val licenseVal = requiredString(json, "license") ?: return null
        val license = licenseVal.trim()
        val identityObj = json.optJSONObject("artifactIdentity") ?: return null
        val identity = DedicatedTranslationArtifactIdentity(
            researchModelName = requiredString(identityObj, "researchModelName") ?: return null,
            sourceRepository = requiredString(identityObj, "sourceRepository") ?: return null,
            conversionProvenance = requiredString(identityObj, "conversionProvenance") ?: return null,
        )

        val runtimeDescriptorObj = json.optJSONObject("runtimeDescriptor") ?: return null
        val runtimeDescriptor = DedicatedTranslationRuntimeBinding(
            runtimeImplementation = requiredString(runtimeDescriptorObj, "runtimeImplementation") ?: return null,
            tensorFormat = requiredString(runtimeDescriptorObj, "tensorFormat") ?: return null,
            quantization = requiredString(runtimeDescriptorObj, "quantization") ?: return null,
            tokenizerIdentity = requiredString(runtimeDescriptorObj, "tokenizerIdentity") ?: return null,
            requiredLibraries = if (runtimeDescriptorObj.has("requiredLibraries")) {
                val libsArray = runtimeDescriptorObj.optJSONArray("requiredLibraries") ?: return null
                val libs = mutableSetOf<String>()
                for (i in 0 until libsArray.length()) {
                    val value = libsArray.opt(i)
                    if (value == null || value is JSONObject || value is JSONArray || value is Boolean || value is Int || value is Long || value is Double || value !is String) return null
                    val trimmed = value.trim()
                    if (trimmed.isBlank() || trimmed == "null") return null
                    libs.add(trimmed)
                }
                libs
            } else {
                return null
            },
            supportedAbis = if (runtimeDescriptorObj.has("supportedAbis")) {
                val abisArray = runtimeDescriptorObj.optJSONArray("supportedAbis") ?: return null
                val abis = mutableSetOf<String>()
                for (i in 0 until abisArray.length()) {
                    val value = abisArray.opt(i)
                    if (value == null || value is JSONObject || value is JSONArray || value is Boolean || value is Int || value is Long || value is Double || value !is String) return null
                    val trimmed = value.trim().lowercase()
                    if (trimmed.isBlank() || trimmed == "null") return null
                    abis.add(trimmed)
                }
                if (abis.isEmpty()) return null
                abis
            } else {
                return null
            },
            acceleratorSupport = if (runtimeDescriptorObj.has("acceleratorSupport")) {
                val valueStr = requiredString(runtimeDescriptorObj, "acceleratorSupport") ?: return null
                try {
                    TranslationAcceleratorSupport.valueOf(valueStr.uppercase())
                } catch (_: Exception) { return null }
            } else {
                return null
            },
            cpuFallbackSupported = if (runtimeDescriptorObj.has("cpuFallbackSupported")) {
                val raw = runtimeDescriptorObj.get("cpuFallbackSupported")
                if (raw is Boolean) {
                    raw
                } else {
                    return null // string/number/null is invalid
                }
            } else {
                return null
            },
        )
        val targetsArray = json.optJSONArray("supportedTargetLanguages")
        if (targetsArray == null) return null
        val targets = mutableSetOf<String>()
        for (i in 0 until targetsArray.length()) {
            val value = targetsArray.opt(i)
            if (value == null || value is JSONObject || value is JSONArray || value is Boolean || value is Int || value is Double || value !is String) return null
            val trimmed = value.trim()
            if (trimmed.isBlank() || trimmed == "null") return null
            targets.add(trimmed.lowercase())
        }
        if (targets.isEmpty()) return null

        val sourcesArray = json.optJSONArray("supportedSourceLanguages")
        if (sourcesArray == null) return null
        val sources = mutableSetOf<String>()
        for (i in 0 until sourcesArray.length()) {
            val value = sourcesArray.opt(i)
            if (value == null || value is JSONObject || value is JSONArray || value is Boolean || value is Int || value is Double || value !is String) return null
            val trimmed = value.trim()
            if (trimmed.isBlank() || trimmed == "null") return null
            sources.add(trimmed.lowercase())
        }
        if (sources.isEmpty()) return null

        val pairsArray = json.optJSONArray("validatedLanguagePairs")
        val pairEntries = mutableListOf<TranslationLanguagePair>()
        if (pairsArray != null) {
            for (i in 0 until pairsArray.length()) {
                val obj = pairsArray.optJSONObject(i)
                if (obj == null) return null // malformed entry
                val rawSource = obj.get("source")
                val rawTarget = obj.get("target")
                if (rawSource !is String || rawTarget !is String) return null
                val src = rawSource.trim()
                val tgt = rawTarget.trim()
                if (src.isBlank() || tgt.isBlank() || src.lowercase() == tgt.lowercase()) return null
                pairEntries.add(TranslationLanguagePair(src.lowercase(), tgt.lowercase()).normalized())
            }
        }
        val pairs = pairEntries.toSet()
        val manifest = DedicatedTranslationBackendManifest(
            backendId = backendId,
            implementationVersion = implementationVersion,
            modelArtifactId = modelArtifactId,
            supportedTargetLanguages = targets,
            supportedSourceLanguages = sources,
            validatedLanguagePairs = pairs,
            supportsAutomaticSourceDetection = if (json.has("supportsAutomaticSourceDetection")) {
                val raw = json.get("supportsAutomaticSourceDetection")
                if (raw is Boolean) raw else return null
            } else return null,
            supportsContext = if (json.has("supportsContext")) {
                val raw = json.get("supportsContext")
                if (raw is Boolean) raw else return null
            } else return null,
            supportsStyle = if (json.has("supportsStyle")) {
                val raw = json.get("supportsStyle")
                if (raw is Boolean) raw else return null
            } else return null,
        )

        return ParsedDedicatedManifest(
            manifest = manifest,
            binding = DedicatedTranslationArtifactBinding(
                artifactRevisionId = artifactRevision,
                license = license,
                artifactIdentity = identity,
                runtimeDescriptor = runtimeDescriptor,
        declaredFileHashes = if (json.has("fileHashes")) {
            val hashesObj = json.optJSONObject("fileHashes") ?: return null
            val hashMap = mutableMapOf<String, String>()
            val originalKeys = mutableSetOf<String>()
            for (key in hashesObj.keys()) {
                val path = key.trim()
                if (path.isBlank()) return null
                val trimmedKey = key.trim()
                if (trimmedKey in originalKeys) return null // collision after normalization
                originalKeys.add(trimmedKey)
                val rawValue = hashesObj.get(key)
                if (rawValue !is String) return null
                val hash = rawValue.trim()
                if (hash.isBlank()) return null
                hashMap[path] = hash
            }
            if (hashMap.isEmpty()) return null
            hashMap
        } else {
            return null
        },
        declaredFileSizesBytes = if (json.has("fileSizesBytes")) {
            val sizesObj = json.optJSONObject("fileSizesBytes") ?: return null
            val sizeMap = mutableMapOf<String, Long>()
            val originalSizeKeys = mutableSetOf<String>()
            for (key in sizesObj.keys()) {
                val path = key.trim()
                if (path.isBlank()) return null
                val trimmedKey = key.trim()
                if (trimmedKey in originalSizeKeys) return null
                originalSizeKeys.add(trimmedKey)
                val rawValue = sizesObj.get(key)
                val size = when (rawValue) {
                    is Int -> if (rawValue > 0) rawValue.toLong() else return null
                    is Long -> if (rawValue > 0) rawValue else return null
                    else -> return null
                }
                sizeMap[path] = size
            }
            if (sizeMap.isEmpty()) return null
            sizeMap
        } else {
            return null
        },
                declaredMeasurements = if (json.has("deviceMeasurements")) {
                    val measurementsObj = json.optJSONObject("deviceMeasurements") ?: return null
                    val measurements = mutableMapOf<String, String>()
                    for (key in measurementsObj.keys()) {
                        val trimmedKey = key.trim()
                        if (trimmedKey.isBlank()) return null
                        val rawValue = measurementsObj.get(key)
                        if (rawValue !is String) return null
                        val value = rawValue.trim()
                        if (value.isBlank()) return null
                        measurements[trimmedKey] = value
                    }
                    measurements
                } else {
                    emptyMap()
                },
                unavailableReasons = if (json.has("unavailableReasons")) {
                    val reasonsObj = json.optJSONObject("unavailableReasons") ?: return null
                    val reasons = mutableMapOf<String, String>()
                    for (key in reasonsObj.keys()) {
                        val trimmedKey = key.trim()
                        if (trimmedKey.isBlank()) return null
                        val rawValue = reasonsObj.get(key)
                        if (rawValue !is String) return null
                        val value = rawValue.trim()
                        if (value.isBlank()) return null
                        reasons[trimmedKey] = value
                    }
                    reasons
                } else {
                    emptyMap()
                },
            ),
        )
    }

    private fun requiredString(obj: JSONObject, key: String): String? {
        if (!obj.has(key)) return null
        val value = obj.get(key)
        if (value !is String) return null
        val trimmed = value.trim()
        return if (trimmed.isNotBlank() && trimmed != "null") trimmed else null
    }

    private fun requiredPositiveLong(obj: JSONObject, key: String): Long? {
        if (!obj.has(key)) return null
        val value = obj.get(key)
        if (value is Int && value > 0) return value.toLong()
        if (value is Long && value > 0) return value
        // Float/double values are explicitly invalid for frozen schema.
        return null
    }

    private fun requiredStringArray(obj: JSONObject, key: String, normalizeLowercase: Boolean = true): Set<String>? {
        if (!obj.has(key)) return null
        val array = obj.optJSONArray(key) ?: return null
        val result = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val value = array.opt(i)
            if (value == null || value is JSONObject || value is JSONArray || value is Boolean || value is Int || value is Long || value is Double || value !is String) return null
            val trimmed = value.trim()
            if (trimmed.isBlank() || trimmed == "null") return null
            result.add(if (normalizeLowercase) trimmed.lowercase() else trimmed)
        }
        return if (result.isEmpty()) null else result
    }

    private fun requiredStringMap(obj: JSONObject, key: String): Map<String, String>? {
        if (!obj.has(key)) return null
        val mapObj = obj.optJSONObject(key) ?: return null
        val result = mutableMapOf<String, String>()
        for (k in mapObj.keys()) {
            val trimmedKey = k.trim()
            if (trimmedKey.isBlank()) return null
            val value = mapObj.optString(k).trim()
            if (value.isBlank()) return null
            result[trimmedKey] = value
        }
        return if (result.isEmpty()) null else result
    }

    private fun JSONObject.toStringMap(): Map<String, String> = buildMap {
        keys().forEach { key ->
            optString(key).takeIf { it.isNotBlank() && it != "null" }?.let { put(key, it) }
        }
    }

}

data class ParsedDedicatedManifest(
    val manifest: DedicatedTranslationBackendManifest,
    val binding: DedicatedTranslationArtifactBinding,
)