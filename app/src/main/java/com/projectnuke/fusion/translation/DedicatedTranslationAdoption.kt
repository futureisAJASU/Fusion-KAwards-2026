package com.projectnuke.fusion.translation

/**
 * Focused runtime-binding descriptor for a FROZEN dedicated translation
 * artifact. This is the minimal production binding data (identity, license,
 * load binding, declared integrity, mandated device measurements). Research
 * prose, evaluation matrices and benchmark notes live in research docs, not
 * in production Kotlin.
 */
/**
 * Focused artifact identity descriptor for a frozen dedicated translation
 * artifact. This holds the research/model identity, provenance, and
 * repository evidence required by the acceptance contract. It does not
 * duplicate backend capability claims; the authoritative model identity
 * remains in [DedicatedTranslationBackendManifest.modelArtifactId].
 */
data class DedicatedTranslationArtifactIdentity(
    val researchModelName: String,
    val sourceRepository: String,
    val conversionProvenance: String,
)

enum class TranslationAcceleratorSupport {
    NONE,
    CPU_ONLY,
    GPU_VALIDATED,
    NPU_VALIDATED,
}

data class DedicatedTranslationRuntimeBinding(
    val runtimeImplementation: String,
    val tensorFormat: String,
    val quantization: String,
    val tokenizerIdentity: String,
    val requiredLibraries: Set<String>,
    val supportedAbis: Set<String>,
    val acceleratorSupport: TranslationAcceleratorSupport,
    val cpuFallbackSupported: Boolean,
)

data class DedicatedTranslationArtifactBinding(
    val artifactRevisionId: String,
    val license: String,
    val artifactIdentity: DedicatedTranslationArtifactIdentity? = null,
    val runtimeDescriptor: DedicatedTranslationRuntimeBinding? = null,
    val declaredFileHashes: Map<String, String>,
    val declaredFileSizesBytes: Map<String, Long> = emptyMap(),
    val declaredMeasurements: Map<String, String> = emptyMap(),
    val unavailableReasons: Map<String, String> = emptyMap(),
)

/**
 * Pure evidence gate for adopting a dedicated translation backend.
 *
 * This is NOT a quality-scoring algorithm. It only checks that the frozen
 * artifact carries the acceptance contract's required identity/runtime/
 * integrity/device evidence and that capability claims stay inside the
 * validated research language scope. Final model selection remains a
 * research/product decision; no production backend switches here.
 */
object DedicatedTranslationAdoptionGate {

    /** Research/evaluation language scope. Languages outside this set (e.g.
     *  zh) cannot be advertised until research validates them. */
    val RESEARCH_LANGUAGE_SCOPE: Set<String> = setOf("ko", "en", "ja")

    /** Measurement keys the acceptance contract marks mandatory. They must be
     *  present with non-blank measured values; thresholds are a research/
     *  product decision, never invented here. */
    val REQUIRED_DEVICE_MEASUREMENTS: Set<String> = setOf(
        "disk_size_bytes",
        "peak_rss_mb",
        "load_latency_ms",
        "first_translation_latency_ms",
        "warm_translation_latency_ms",
    )

    val OPTIONAL_EVIDENCE_KEYS = setOf(
        "representative_throughput",
        "steady_state_rss",
    )

    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    fun evidenceFor(
        manifest: DedicatedTranslationBackendManifest,
        binding: DedicatedTranslationArtifactBinding,
    ): DedicatedAdoptionCheck {
        val missing = mutableListOf<String>()
        if (manifest.backendId.isBlank()) missing += "backendId"
        if (manifest.implementationVersion.isBlank()) missing += "implementationVersion"
        if (manifest.modelArtifactId.isBlank()) missing += "modelArtifactId"
        if (binding.artifactIdentity == null) {
            missing += "artifact-identity"
        } else {
            val id = binding.artifactIdentity
            if (id.researchModelName.isBlank()) missing += "artifact-identity:researchModelName"
            if (id.sourceRepository.isBlank()) missing += "artifact-identity:sourceRepository"
            if (id.conversionProvenance.isBlank()) missing += "artifact-identity:conversionProvenance"
        }
        if (binding.artifactRevisionId.isBlank()) missing += "artifact-revision"
        if (binding.license.isBlank()) missing += "license"
        if (binding.runtimeDescriptor == null) {
            missing += "runtime-descriptor"
        } else {
            val rt = binding.runtimeDescriptor
            if (rt.runtimeImplementation.isBlank()) missing += "runtime-descriptor:runtimeImplementation"
            if (rt.tensorFormat.isBlank()) missing += "runtime-descriptor:tensorFormat"
            if (rt.quantization.isBlank()) missing += "runtime-descriptor:quantization"
            if (rt.tokenizerIdentity.isBlank()) missing += "runtime-descriptor:tokenizerIdentity"
            if (rt.supportedAbis.isEmpty()) missing += "runtime-descriptor:supportedAbis"
            if (rt.requiredLibraries.any { it.isBlank() }) missing += "runtime-descriptor:requiredLibraries"
        }


        val targets = manifest.supportedTargetLanguages.map { it.lowercase() }.toSet()
        val sources = manifest.supportedSourceLanguages.map { it.lowercase() }.toSet()
        val pairs = manifest.validatedLanguagePairs
        val normalizedPairs = pairs.map { it.normalized() }.toSet()
        val hasExplicitPair = normalizedPairs.isNotEmpty()
        val pairSourcesValid = normalizedPairs.all { it.source in sources }
        val pairTargetsValid = normalizedPairs.all { it.target in targets }
        val pairScopeValid = normalizedPairs.all {
            it.source in RESEARCH_LANGUAGE_SCOPE && it.target in RESEARCH_LANGUAGE_SCOPE
        }
        val pairIdentitySafe = normalizedPairs.all { it.source != it.target }
        val pairEvidenceComplete = hasExplicitPair && pairSourcesValid && pairTargetsValid && pairScopeValid && pairIdentitySafe
        if (!pairEvidenceComplete) missing += "language-pair"
        if (targets.any { it !in RESEARCH_LANGUAGE_SCOPE } ||
            sources.any { it !in RESEARCH_LANGUAGE_SCOPE }
        ) {
            missing += "language-scope"
        }

        val hashes = binding.declaredFileHashes
        val sizes = binding.declaredFileSizesBytes
        val fileEntries = hashes.entries.mapNotNull { (path, hash) ->
            val trimmedPath = path.trim()
            if (trimmedPath.isBlank()) null
            else trimmedPath to hash.trim()
        }.toMap()
        val sizeEntries = sizes.entries.mapNotNull { (path, size) ->
            val trimmedPath = path.trim()
            if (trimmedPath.isBlank()) null
            else trimmedPath to size
        }.toMap()
        val fileIntegrityComplete = fileEntries.isNotEmpty() &&
            fileEntries.keys == sizeEntries.keys &&
            fileEntries.all { (path, hash) ->
                val size = sizeEntries[path]
                size != null && path.isNotBlank() &&
                    SHA256_HEX.matches(hash.lowercase()) &&
                    size > 0
            }
        if (!fileIntegrityComplete) missing += "file-integrity"

        val missingMeasurements = REQUIRED_DEVICE_MEASUREMENTS.filter { key ->
            val raw = binding.declaredMeasurements[key]
            raw.isNullOrBlank()
        }
        if (missingMeasurements.isNotEmpty()) {
            missing += "device-measurements:$missingMeasurements"
        }
        val invalidNumericMeasurements = REQUIRED_DEVICE_MEASUREMENTS.filter { key ->
            val raw = binding.declaredMeasurements[key] ?: return@filter false
            if (raw.isBlank()) return@filter false
            val value = raw.trim().toDoubleOrNull()
            value == null || !value.isFinite() || value <= 0
        }
        if (invalidNumericMeasurements.isNotEmpty()) {
            missing += "device-measurements:invalid-positive:$invalidNumericMeasurements"
        }

        // Optional machine evidence: either a positive finite numeric value,
        // or an explicit nonblank unavailable reason. Silent omission of both
        // is not permitted when the contract requires explicit evidence state.
        val optionalMissing = OPTIONAL_EVIDENCE_KEYS.filter { key ->
            val raw = binding.declaredMeasurements[key]
            val reasonRaw = binding.unavailableReasons[key]
            raw.isNullOrBlank() && (reasonRaw == null || reasonRaw.isBlank())
        }
        if (optionalMissing.isNotEmpty()) {
            missing += "optional-evidence-missing:$optionalMissing"
        }
        val optionalInvalid = OPTIONAL_EVIDENCE_KEYS.filter { key ->
            val raw = binding.declaredMeasurements[key]
            if (!raw.isNullOrBlank()) {
                val value = raw.trim().toDoubleOrNull()
                value == null || !value.isFinite() || value <= 0
            } else false
        }
        if (optionalInvalid.isNotEmpty()) {
            missing += "optional-evidence:invalid-positive:$optionalInvalid"
        }
        val optionalReasonInvalid = OPTIONAL_EVIDENCE_KEYS.filter { key ->
            val reasonRaw = binding.unavailableReasons[key]
            if (reasonRaw != null && reasonRaw.isNotBlank()) {
                val measuredRaw = binding.declaredMeasurements[key]
                // If a measured value exists, the unavailable reason must not
                // also exist; contradictory evidence state is invalid.
                !measuredRaw.isNullOrBlank()
            } else false
        }
        if (optionalReasonInvalid.isNotEmpty()) {
            missing += "optional-evidence:contradictory:$optionalReasonInvalid"
        }

        return DedicatedAdoptionCheck(
            verdict = if (missing.isEmpty()) {
                DedicatedAdoptionVerdict.EVIDENCE_COMPLETE
            } else {
                DedicatedAdoptionVerdict.MISSING_EVIDENCE
            },
            missing = missing,
        )
    }
}

enum class DedicatedAdoptionVerdict {
    EVIDENCE_COMPLETE,
    MISSING_EVIDENCE,
}

data class DedicatedAdoptionCheck(
    val verdict: DedicatedAdoptionVerdict,
    val missing: List<String>,
)