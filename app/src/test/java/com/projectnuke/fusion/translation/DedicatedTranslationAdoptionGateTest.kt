package com.projectnuke.fusion.translation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dedicated-model adoption gate tests.
 *
 * The gate is an evidence-consistency check, NOT a quality scorer. It only
 * verifies that a frozen research artifact carries the acceptance contract's
 * required identity/runtime/integrity/device-evidence fields and that its
 * capability claims stay inside the validated research language scope.
 */
class DedicatedTranslationAdoptionGateTest {

    private fun binding(
        artifactRevisionId: String = "rev-hash-abc123",
        license: String = "Apache-2.0",
    ) = DedicatedTranslationArtifactBinding(
        artifactRevisionId = artifactRevisionId,
        license = license,
        artifactIdentity = DedicatedTranslationArtifactIdentity(
            researchModelName = "Dedicated Translation Research Model",
            sourceRepository = "https://github.com/research/translation-model",
            conversionProvenance = "checkpoint-v3 -> tflite-converted",
        ),
        runtimeDescriptor = DedicatedTranslationRuntimeBinding(
            runtimeImplementation = "dedicated-tflite-v1",
            tensorFormat = "tflite",
            quantization = "int8",
            tokenizerIdentity = "tokenizer-v2",
            requiredLibraries = emptySet(),
            supportedAbis = setOf("arm64-v8a"),
            acceleratorSupport = TranslationAcceleratorSupport.CPU_ONLY,
            cpuFallbackSupported = true,
        ),
        declaredFileHashes = mapOf("model.tflite" to "a".repeat(64)),
        declaredFileSizesBytes = mapOf("model.tflite" to 1200000000L),
        declaredMeasurements = mapOf(
            "disk_size_bytes" to "1200000000",
            "peak_rss_mb" to "1800",
            "load_latency_ms" to "1200",
            "first_translation_latency_ms" to "400",
            "warm_translation_latency_ms" to "120",
            "representative_throughput" to "250",
            "steady_state_rss" to "1750",
        ),
    )

    private fun manifest() = DedicatedTranslationBackendManifest(
        backendId = "dedicated-translation-v1",
        implementationVersion = "dedicated-0.1",
        modelArtifactId = "research-approved-dedicated-model-id",
        supportedTargetLanguages = setOf("ko", "en", "ja"),
        supportedSourceLanguages = setOf("ko", "en", "ja"),
        validatedLanguagePairs = setOf(
            TranslationLanguagePair("ko", "en"),
            TranslationLanguagePair("en", "ko"),
            TranslationLanguagePair("ko", "ja"),
            TranslationLanguagePair("ja", "ko"),
        ),
        supportsAutomaticSourceDetection = true,
    )

    @Test
    fun `complete frozen evidence is evidence complete`() {
        val check = DedicatedTranslationAdoptionGate.evidenceFor(manifest(), binding())
        assertEquals(DedicatedAdoptionVerdict.EVIDENCE_COMPLETE, check.verdict)
        assertTrue(check.missing.isEmpty())
    }

    @Test
    fun `missing artifact identity blocks adoption`() {
        val missing = listOf(
            manifest().copy(backendId = ""),
            manifest().copy(implementationVersion = ""),
            manifest().copy(modelArtifactId = ""),
        )
        missing.forEach { m ->
            val check = DedicatedTranslationAdoptionGate.evidenceFor(m, binding())
            assertEquals(DedicatedAdoptionVerdict.MISSING_EVIDENCE, check.verdict)
        }
    }

    @Test
    fun `missing revision license or descriptor blocks adoption`() {
        val checks = listOf(
            DedicatedTranslationAdoptionGate.evidenceFor(manifest(), binding(artifactRevisionId = "")),
            DedicatedTranslationAdoptionGate.evidenceFor(manifest(), binding(license = "")),
            DedicatedTranslationAdoptionGate.evidenceFor(manifest(), binding().copy(runtimeDescriptor = null)),
        )
        checks.forEach { check ->
            assertEquals(DedicatedAdoptionVerdict.MISSING_EVIDENCE, check.verdict)
            assertTrue(check.missing.isNotEmpty())
        }
    }

    @Test
    fun `no validated language pair blocks adoption`() {
        val noTargets = manifest().copy(supportedTargetLanguages = emptySet())
        val disjoint = manifest().copy(supportedSourceLanguages = setOf("fr"))
        listOf(noTargets, disjoint).forEach { m ->
            val check = DedicatedTranslationAdoptionGate.evidenceFor(m, binding())
            assertEquals(DedicatedAdoptionVerdict.MISSING_EVIDENCE, check.verdict)
            assertTrue(check.missing.any { it.contains("pair") })
        }
    }

    @Test
    fun `capabilities outside the validated language scope are rejected`() {
        val outOfScopeTarget = manifest().copy(supportedTargetLanguages = setOf("zh"))
        val outOfScopeSource = manifest().copy(supportedSourceLanguages = setOf("ko", "fr"))
        listOf(outOfScopeTarget, outOfScopeSource).forEach { m ->
            val check = DedicatedTranslationAdoptionGate.evidenceFor(m, binding())
            assertEquals(DedicatedAdoptionVerdict.MISSING_EVIDENCE, check.verdict)
            assertTrue(check.missing.any { it.contains("scope") })
        }
    }

    @Test
    fun `declared artifact hashes must be present and well formed`() {
        val noHashes = binding().copy(declaredFileHashes = emptyMap())
        val malformedHash = binding().copy(declaredFileHashes = mapOf("m.bin" to "not-a-sha256"))
        listOf(noHashes, malformedHash).forEach { b ->
            val check = DedicatedTranslationAdoptionGate.evidenceFor(manifest(), b)
            assertEquals(DedicatedAdoptionVerdict.MISSING_EVIDENCE, check.verdict)
            assertTrue(check.missing.any { it.contains("integrity") || it.contains("hash") })
        }
    }

    @Test
    fun `required device measurements must be present`() {
        val partial = binding().copy(
            declaredMeasurements = mapOf("disk_size_bytes" to "1200000000"),
        )
        val check = DedicatedTranslationAdoptionGate.evidenceFor(manifest(), partial)
        assertEquals(DedicatedAdoptionVerdict.MISSING_EVIDENCE, check.verdict)
        assertTrue(
            check.missing.any { it.contains("measurement") || it.contains("device") },
        )
    }

    @Test
    fun `evidence completeness does not imply production approval`() {
        val check = DedicatedTranslationAdoptionGate.evidenceFor(manifest(), binding())
        assertEquals(DedicatedAdoptionVerdict.EVIDENCE_COMPLETE, check.verdict)
        // The machine gate answers evidence consistency only; it never switches
        // the production backend, which remains the shared LiteRT adapter.
        assertEquals("litert-shared-local", TranslationBackends.CURRENT_BACKEND_ID)
    }

    @Test
    fun `wildly different measurement values with complete evidence yield same verdict`() {
        val high = binding().copy(
            declaredMeasurements = mapOf(
                "disk_size_bytes" to "5000000000",
                "peak_rss_mb" to "3000",
                "load_latency_ms" to "5000",
                "first_translation_latency_ms" to "2000",
                "warm_translation_latency_ms" to "800",
                "representative_throughput" to "500",
                "steady_state_rss" to "2800",
            ),
        )
        val low = binding().copy(
            declaredMeasurements = mapOf(
                "disk_size_bytes" to "100000000",
                "peak_rss_mb" to "200",
                "load_latency_ms" to "50",
                "first_translation_latency_ms" to "10",
                "warm_translation_latency_ms" to "5",
                "representative_throughput" to "30",
                "steady_state_rss" to "180",
            ),
        )
        val highCheck = DedicatedTranslationAdoptionGate.evidenceFor(manifest(), high)
        val lowCheck = DedicatedTranslationAdoptionGate.evidenceFor(manifest(), low)
        assertEquals(DedicatedAdoptionVerdict.EVIDENCE_COMPLETE, highCheck.verdict)
        assertEquals(DedicatedAdoptionVerdict.EVIDENCE_COMPLETE, lowCheck.verdict)
        assertTrue(highCheck.missing.isEmpty())
        assertTrue(lowCheck.missing.isEmpty())
    }

    @Test
    fun `no quality score passed into gate API`() {
        val api = DedicatedTranslationAdoptionGate::class.java.declaredMethods
        assertTrue(
            api.none {
                it.name.contains("score", ignoreCase = true) ||
                    it.name.contains("threshold", ignoreCase = true) ||
                    it.name.contains("quality", ignoreCase = true)
            },
        )
    }

    // ---- machine-readable parser / adapter contract ----

    @Test
    fun `parser reads a frozen research manifest fixture`() {
        val json = JSONObject(
            """
            {
              "backendId": "dedicated-translation-v1",
              "implementationVersion": "dedicated-0.1",
              "modelArtifactId": "research-approved-dedicated-model-id",
              "artifactRevision": "rev-hash-abc123",
              "license": "Apache-2.0",
              "runtimeBinding": "litert-tflite-v1",
              "supportedTargetLanguages": ["ko", "en", "ja"],
              "supportedSourceLanguages": ["ko", "en", "ja"],
              "validatedLanguagePairs": [
                {"source":"ko","target":"en"},
                {"source":"en","target":"ko"},
                {"source":"ko","target":"ja"},
                {"source":"ja","target":"ko"}
              ],
              "supportsAutomaticSourceDetection": true,
              "supportsContext": true,
              "supportsStyle": false,
              "runtimeDescriptor": {
                "runtimeImplementation": "dedicated-tflite-v1",
                "tensorFormat": "tflite",
                "quantization": "int8",
                "tokenizerIdentity": "tokenizer-v2",
                "requiredLibraries": [],
                "supportedAbis": ["arm64-v8a"],
                "acceleratorSupport": "CPU_ONLY",
                "cpuFallbackSupported": true
              },
              "artifactIdentity": {
                "researchModelName": "Dedicated Translation Research Model",
                "sourceRepository": "https://github.com/research/translation-model",
                "conversionProvenance": "checkpoint-v3 -> tflite-converted"
              },
              "fileHashes": {"model.tflite": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
              "fileSizesBytes": {"model.tflite": 1200000000},
              "deviceMeasurements": {
                "disk_size_bytes": "1200000000",
                "peak_rss_mb": "1800",
                "load_latency_ms": "1200",
                "first_translation_latency_ms": "400",
                "warm_translation_latency_ms": "120",
                "representative_throughput": "250",
                "steady_state_rss": "1750"
              }
            }
            """.trimIndent(),
        )
        val parsed = DedicatedTranslationManifestParser.parse(json)
        assertNotNull(parsed)
        assertEquals("dedicated-translation-v1", parsed!!.manifest.backendId)
        assertTrue(parsed.manifest.toCapabilities().supportsTarget("ja"))
        assertTrue(parsed.manifest.toCapabilities().supportsAutomaticSourceDetection)
        assertEquals("rev-hash-abc123", parsed.binding.artifactRevisionId)
        assertEquals(1, parsed.binding.declaredFileHashes.size)
        assertEquals(7, parsed.binding.declaredMeasurements.size)

        val gate = DedicatedTranslationAdoptionGate.evidenceFor(
            parsed.manifest,
            parsed.binding,
        )
        assertEquals(DedicatedAdoptionVerdict.EVIDENCE_COMPLETE, gate.verdict)
    }

    @Test
    fun `parser rejects manifests with missing identity fields`() {
        assertNull(
            DedicatedTranslationManifestParser.parse(
                JSONObject(
                    """
                    {"backendId": "", "modelArtifactId": "m"}
                    """.trimIndent(),
                ),
            ),
        )
        assertNull(
            DedicatedTranslationManifestParser.parse(
                JSONObject().put("backendId", "b").put("implementationVersion", ""),
            ),
        )
    }

    @Test
    fun `strict language array rejects non-string entries`() {
        // RED: malformed array entry must cause parse failure, not silent skip.
        assertNull(
            DedicatedTranslationManifestParser.parse(
                JSONObject(
                    """
                    {"backendId":"b","implementationVersion":"v","modelArtifactId":"m","artifactRevision":"r","license":"L","runtimeDescriptor":{"runtimeImplementation":"t","tensorFormat":"t","quantization":"q","tokenizerIdentity":"tk","requiredLibraries":[],"supportedAbis":["a"],"acceleratorSupport":"NONE","cpuFallbackSupported":true},"artifactIdentity":{"researchModelName":"m","sourceRepository":"r","conversionProvenance":"c"},"supportedTargetLanguages":["ko",null],"supportedSourceLanguages":["ko","en"],"validatedLanguagePairs":[{"source":"ko","target":"en"}],"supportsAutomaticSourceDetection":false,"supportsContext":false,"supportsStyle":false,"fileHashes":{"m":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},"fileSizesBytes":{"m":1},"deviceMeasurements":{"disk_size_bytes":"1","peak_rss_mb":"1","load_latency_ms":"1","first_translation_latency_ms":"1","warm_translation_latency_ms":"1"}}
                    """.trimIndent(),
                ),
            ),
        )
    }

    @Test
    fun `strict boolean fields reject string and numeric coercion`() {
        // GREEN: actual boolean false/true must be accepted.
        val validTrue = JSONObject(
            """
            {"backendId":"b","implementationVersion":"v","modelArtifactId":"m","artifactRevision":"r","license":"L","runtimeDescriptor":{"runtimeImplementation":"t","tensorFormat":"t","quantization":"q","tokenizerIdentity":"tk","requiredLibraries":[],"supportedAbis":["a"],"acceleratorSupport":"NONE","cpuFallbackSupported":true},"artifactIdentity":{"researchModelName":"m","sourceRepository":"r","conversionProvenance":"c"},"supportedTargetLanguages":["ko","en"],"supportedSourceLanguages":["ko","en"],"validatedLanguagePairs":[{"source":"ko","target":"en"}],"supportsAutomaticSourceDetection":true,"supportsContext":true,"supportsStyle":false,"fileHashes":{"m":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},"fileSizesBytes":{"m":1},"deviceMeasurements":{"disk_size_bytes":"1","peak_rss_mb":"1","load_latency_ms":"1","first_translation_latency_ms":"1","warm_translation_latency_ms":"1"}}
            """.trimIndent(),
        )
        assertNotNull(DedicatedTranslationManifestParser.parse(validTrue))

        // RED: string "false" must be rejected.
        val invalidStringFalse = JSONObject(
            """
            {"backendId":"b","implementationVersion":"v","modelArtifactId":"m","artifactRevision":"r","license":"L","runtimeDescriptor":{"runtimeImplementation":"t","tensorFormat":"t","quantization":"q","tokenizerIdentity":"tk","requiredLibraries":[],"supportedAbis":["a"],"acceleratorSupport":"NONE","cpuFallbackSupported":"false"},"artifactIdentity":{"researchModelName":"m","sourceRepository":"r","conversionProvenance":"c"},"supportedTargetLanguages":["ko","en"],"supportedSourceLanguages":["ko","en"],"validatedLanguagePairs":[{"source":"ko","target":"en"}],"supportsAutomaticSourceDetection":"false","supportsContext":"false","supportsStyle":"false","fileHashes":{"m":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},"fileSizesBytes":{"m":1},"deviceMeasurements":{"disk_size_bytes":"1","peak_rss_mb":"1","load_latency_ms":"1","first_translation_latency_ms":"1","warm_translation_latency_ms":"1"}}
            """.trimIndent(),
        )
        assertNull(DedicatedTranslationManifestParser.parse(invalidStringFalse))
    }

    @Test
    fun `library array preserves identity casing exactly`() {
        val fixture = JSONObject(
            """
            {"backendId":"b","implementationVersion":"v","modelArtifactId":"m","artifactRevision":"r","license":"L","runtimeDescriptor":{"runtimeImplementation":"t","tensorFormat":"t","quantization":"q","tokenizerIdentity":"tk","requiredLibraries":["libFusionRuntime.so","CustomTensorPool"],"supportedAbis":["arm64-v8a"],"acceleratorSupport":"NONE","cpuFallbackSupported":true},"artifactIdentity":{"researchModelName":"m","sourceRepository":"r","conversionProvenance":"c"},"supportedTargetLanguages":["ko","en"],"supportedSourceLanguages":["ko","en"],"validatedLanguagePairs":[{"source":"ko","target":"en"}],"supportsAutomaticSourceDetection":false,"supportsContext":false,"supportsStyle":false,"fileHashes":{"m":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},"fileSizesBytes":{"m":1},"deviceMeasurements":{"disk_size_bytes":"1","peak_rss_mb":"1","load_latency_ms":"1","first_translation_latency_ms":"1","warm_translation_latency_ms":"1"}}
            """.trimIndent(),
        )
        val parsed = DedicatedTranslationManifestParser.parse(fixture)
        assertNotNull(parsed)
        val libs = parsed!!.binding.runtimeDescriptor!!.requiredLibraries
        assertTrue(libs.contains("libFusionRuntime.so"))
        assertTrue(libs.contains("CustomTensorPool"))
    }

    @Test
    fun `optional device evidence requires value or explicit reason`() {
        val withValue = binding().copy(
            declaredMeasurements = mapOf(
                "disk_size_bytes" to "1200000000",
                "peak_rss_mb" to "1800",
                "load_latency_ms" to "1200",
                "first_translation_latency_ms" to "400",
                "warm_translation_latency_ms" to "120",
                "representative_throughput" to "150",
                "steady_state_rss" to "1600",
            ),
        )
        val withReason = binding().copy(
            declaredMeasurements = mapOf(
                "disk_size_bytes" to "1200000000",
                "peak_rss_mb" to "1800",
                "load_latency_ms" to "1200",
                "first_translation_latency_ms" to "400",
                "warm_translation_latency_ms" to "120",
            ),
            unavailableReasons = mapOf(
                "representative_throughput" to "not measured on this device class",
                "steady_state_rss" to "not available for this measurement path",
            ),
        )
        val neither = binding().copy(
            declaredMeasurements = mapOf(
                "disk_size_bytes" to "1200000000",
                "peak_rss_mb" to "1800",
                "load_latency_ms" to "1200",
                "first_translation_latency_ms" to "400",
                "warm_translation_latency_ms" to "120",
            ),
        )
        val contradictory = binding().copy(
            declaredMeasurements = mapOf(
                "disk_size_bytes" to "1200000000",
                "peak_rss_mb" to "1800",
                "load_latency_ms" to "1200",
                "first_translation_latency_ms" to "400",
                "warm_translation_latency_ms" to "120",
                "representative_throughput" to "150",
            ),
            unavailableReasons = mapOf(
                "representative_throughput" to "conflicting state",
            ),
        )
        assertEquals(DedicatedAdoptionVerdict.EVIDENCE_COMPLETE, DedicatedTranslationAdoptionGate.evidenceFor(manifest(), withValue).verdict)
        assertEquals(DedicatedAdoptionVerdict.EVIDENCE_COMPLETE, DedicatedTranslationAdoptionGate.evidenceFor(manifest(), withReason).verdict)
        assertEquals(DedicatedAdoptionVerdict.MISSING_EVIDENCE, DedicatedTranslationAdoptionGate.evidenceFor(manifest(), neither).verdict)
        assertEquals(DedicatedAdoptionVerdict.MISSING_EVIDENCE, DedicatedTranslationAdoptionGate.evidenceFor(manifest(), contradictory).verdict)
    }

    @Test
    fun `production never exposes a fake dedicated backend id`() {
        // The adoption seam is inert in production: backend selection remains
        // the shared-LiteRT adapter which carries no fake dedicated id.
        assertFalse(TranslationBackends.CURRENT_BACKEND_ID.startsWith("dedicated"))
    }
}
