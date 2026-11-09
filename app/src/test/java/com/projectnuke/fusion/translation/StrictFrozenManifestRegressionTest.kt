package com.projectnuke.fusion.translation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Strict frozen-manifest evidence regression tests.
 * Every RED case proves a malformed or contradictory entry causes
 * explicit parse or gate rejection; every GREEN case proves a
 * truthful complete manifest passes.
 */
class StrictFrozenManifestRegressionTest {

    private val baseManifestString = """
        {"backendId":"b","implementationVersion":"v","modelArtifactId":"m","artifactRevision":"r","license":"L","runtimeDescriptor":{"runtimeImplementation":"t","tensorFormat":"t","quantization":"q","tokenizerIdentity":"tk","requiredLibraries":[],"supportedAbis":["arm64-v8a"],"acceleratorSupport":"NONE","cpuFallbackSupported":true},"artifactIdentity":{"researchModelName":"m","sourceRepository":"r","conversionProvenance":"c"},"supportedTargetLanguages":["ko","en"],"supportedSourceLanguages":["ko","en"],"validatedLanguagePairs":[{"source":"ko","target":"en"}],"supportsAutomaticSourceDetection":false,"supportsContext":false,"supportsStyle":false,"fileHashes":{"model.tflite":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},"fileSizesBytes":{"model.tflite":1200000000},"deviceMeasurements":{"disk_size_bytes":"1200000000","peak_rss_mb":"1800","load_latency_ms":"1200","first_translation_latency_ms":"400","warm_translation_latency_ms":"120"}}
    """.trimIndent()

    // ---- RED: malformed array entries reject manifest ----

    @Test
    fun `language array with null entry rejects manifest`() {
        assertNull(
            DedicatedTranslationManifestParser.parse(
                JSONObject("""{"supportedTargetLanguages":["ko",null]}"""),
            ),
        )
    }

    @Test
    fun `ABI array with numeric entry rejects manifest`() {
        val manifest = JSONObject(baseManifestString)
        val descriptor = manifest.optJSONObject("runtimeDescriptor")!!
        descriptor.put("supportedAbis", org.json.JSONArray("[\"arm64-v8a\", 123]"))
        assertNull(DedicatedTranslationManifestParser.parse(manifest))
    }

    @Test
    fun `library array with null entry rejects manifest`() {
        val manifest = JSONObject(baseManifestString)
        val descriptor = manifest.optJSONObject("runtimeDescriptor")!!
        descriptor.put("requiredLibraries", org.json.JSONArray("[\"lib.so\", null]"))
        assertNull(DedicatedTranslationManifestParser.parse(manifest))
    }

    // ---- RED: malformed pair entries reject manifest ----

    @Test
    fun `pair with numeric source rejects manifest`() {
        val manifest = JSONObject(baseManifestString)
        manifest.put("validatedLanguagePairs", org.json.JSONArray("[{\"source\":123,\"target\":\"en\"}]"))
        assertNull(DedicatedTranslationManifestParser.parse(manifest))
    }

    // ---- RED: malformed hash/size type rejects manifest ----

    @Test
    fun `numeric hash value rejects manifest`() {
        val manifest = JSONObject(baseManifestString)
        val hashes = manifest.optJSONObject("fileHashes")!!
        hashes.put("model.bin", 123)
        assertNull(DedicatedTranslationManifestParser.parse(manifest))
    }

    @Test
    fun `floating-point size rejects manifest`() {
        val manifest = JSONObject(baseManifestString)
        val sizes = manifest.optJSONObject("fileSizesBytes")!!
        sizes.put("model.tflite", 1200.5)
        assertNull(DedicatedTranslationManifestParser.parse(manifest))
    }

    // ---- GREEN: truthful complete manifest passes ----

    @Test
    fun `complete truthful manifest passes parser`() {
        val parsed = DedicatedTranslationManifestParser.parse(JSONObject(baseManifestString))
        assertNotNull(parsed)
        assertEquals("b", parsed!!.manifest.backendId)
    }

    // ---- RED: boolean string coercion rejected ----

    @Test
    fun `boolean string false for capability rejected`() {
        val manifest = JSONObject(baseManifestString)
        manifest.put("supportsAutomaticSourceDetection", "false")
        assertNull(DedicatedTranslationManifestParser.parse(manifest))
    }

    // ---- RED: file path collision rejected ----

    @Test
    fun `normalized file hash collision rejects manifest`() {
        val manifest = JSONObject(baseManifestString)
        val hashes = manifest.optJSONObject("fileHashes")!!
        hashes.put(" model.tflite ", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        assertNull(DedicatedTranslationManifestParser.parse(manifest))
    }

    // ---- PRODUCTION INVARIANT ----

    @Test
    fun `current backend remains litert-shared-local even with complete fake evidence`() {
        val parsed = DedicatedTranslationManifestParser.parse(JSONObject(baseManifestString))
        assertNotNull(parsed)
        assertEquals("litert-shared-local", TranslationBackends.CURRENT_BACKEND_ID)
        assertFalse(TranslationBackends.CURRENT.supportsTarget("ja"))
    }
}
