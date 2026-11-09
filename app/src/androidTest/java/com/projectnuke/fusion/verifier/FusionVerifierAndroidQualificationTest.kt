package com.projectnuke.fusion.verifier

import ai.onnxruntime.OrtEnvironment
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.audit.VerificationPairRequest
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Debug instrumentation only: assets arrive through app-private test staging, never the base APK. */
@RunWith(AndroidJUnit4::class)
class FusionVerifierAndroidQualificationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test
    fun missingVerifierAssetReturnsUnavailable() = runBlocking {
        val dir = File(context.cacheDir, "v0_9c-missing-assets").apply { deleteRecursively(); mkdirs() }
        val backend = OnnxEvidenceVerifierBackend(FusionVerifierAssetProvider(dir))
        val result = backend.verify(VerificationPairRequest("claim", "evidence", evidenceIdentity = "missing"))
        assertEquals(com.projectnuke.fusion.audit.VerifierBackendResult.Unavailable, result)
        backend.close()
    }

    @Test
    fun truncatedModelAndWrongTokenizerHashFailClosed() = runBlocking {
        val dir = File(context.cacheDir, "v0_9c-corrupt-assets").apply { deleteRecursively(); mkdirs() }
        val model = File(dir, "candidate.onnx").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val manifest = manifestJson(model.name, sha256(model), model.length())
        File(dir, FusionVerifierModelPolicy.MANIFEST_FILE).writeText(manifest.toString(), Charsets.UTF_8)
        // The binary identity and size match the manifest, but tokenizer bytes do not match the pinned tokenizer.
        File(dir, FusionVerifierModelPolicy.TOKENIZER_FILE).writeText("{}", Charsets.UTF_8)
        File(dir, FusionVerifierModelPolicy.TOKENIZER_CONFIG_FILE).writeBytes(byteArrayOf(1))
        val result = OnnxEvidenceVerifierBackend(FusionVerifierAssetProvider(dir)).verify(
            VerificationPairRequest("claim", "evidence", evidenceIdentity = "wrong-tokenizer"),
        )
        assertEquals(com.projectnuke.fusion.audit.VerifierBackendResult.Unavailable, result)
        // A declared size mismatch is rejected before ONNX Runtime sees a path.
        val wrongSize = manifestJson(model.name, sha256(model), model.length() + 1)
        File(dir, FusionVerifierModelPolicy.MANIFEST_FILE).writeText(wrongSize.toString(), Charsets.UTF_8)
        assertTrue(FusionVerifierAssetProvider(dir).verify() is FusionVerifierAssetResult.Missing)
    }

    @Test
    fun missingModelWrongModelHashMalformedManifestAndMissingTokenizerFailClosed() = runBlocking {
        val dir = File(context.cacheDir, "v0_9c-invalid-asset-paths").apply { deleteRecursively(); mkdirs() }
        val model = File(dir, "candidate.onnx").apply { writeBytes(byteArrayOf(4, 5, 6, 7)) }
        val manifestFile = File(dir, FusionVerifierModelPolicy.MANIFEST_FILE)
        val provider = FusionVerifierAssetProvider(dir)

        // Model path missing.
        manifestFile.writeText(manifestJson(model.name, sha256(model), model.length()).toString(), Charsets.UTF_8)
        model.delete()
        assertTrue(provider.verify() is FusionVerifierAssetResult.Missing)

        // A present model with a manifest-pinned but incorrect digest must not advance to ORT.
        model.writeBytes(byteArrayOf(4, 5, 6, 7))
        manifestFile.writeText(manifestJson(model.name, "0".repeat(64), model.length()).toString(), Charsets.UTF_8)
        assertTrue(provider.verify() is FusionVerifierAssetResult.Missing)

        // Matching small test model passes its own check, then missing tokenizer fails closed.
        manifestFile.writeText(manifestJson(model.name, sha256(model), model.length()).toString(), Charsets.UTF_8)
        assertTrue(provider.verify() is FusionVerifierAssetResult.Missing)

        manifestFile.writeText("{malformed", Charsets.UTF_8)
        assertTrue(provider.verify() is FusionVerifierAssetResult.Missing)
    }

    @Test
    fun truncatedOnnxWithVerifiedTokenizerReturnsTypedExecutionFailure() = runBlocking {
        val stage = File(requireNotNull(context.getExternalFilesDir(null)), "verifier-stage")
        val dir = File(context.cacheDir, "v0_9c-truncated-onnx").apply { deleteRecursively(); mkdirs() }
        val model = File(dir, "truncated.onnx").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        File(stage, FusionVerifierModelPolicy.TOKENIZER_FILE)
            .copyTo(File(dir, FusionVerifierModelPolicy.TOKENIZER_FILE), overwrite = true)
        File(stage, FusionVerifierModelPolicy.TOKENIZER_CONFIG_FILE)
            .copyTo(File(dir, FusionVerifierModelPolicy.TOKENIZER_CONFIG_FILE), overwrite = true)
        File(dir, FusionVerifierModelPolicy.MANIFEST_FILE).writeText(
            manifestJson(model.name, sha256(model), model.length()).toString(), Charsets.UTF_8,
        )
        val backend = OnnxEvidenceVerifierBackend(FusionVerifierAssetProvider(dir))
        try {
            val result = backend.verify(VerificationPairRequest("claim", "evidence", evidenceIdentity = "truncated-onnx"))
            assertEquals(com.projectnuke.fusion.audit.VerifierBackendResult.Failure(
                com.projectnuke.fusion.audit.AuditBackendFailureReason.EXECUTION_FAILED,
            ), result)
        } finally {
            backend.close()
        }
    }

    @Test
    fun pinnedTokenizerColdLoadIsMeasuredSeparately() {
        val tokenizerFile = File(context.filesDir, "verifier/current/${FusionVerifierModelPolicy.TOKENIZER_FILE}")
        assertTrue("run the verified C2 qualification setup first", tokenizerFile.isFile)
        assertEquals(FusionVerifierModelPolicy.TOKENIZER_SIZE_BYTES, tokenizerFile.length())
        assertEquals(FusionVerifierModelPolicy.TOKENIZER_SHA256, sha256(tokenizerFile))
        val started = System.nanoTime()
        val tokenizer = XlmRPairTokenizer.load(tokenizerFile)
        val loadMs = (System.nanoTime() - started) / 1_000_000.0
        assertTrue(tokenizer.encode("한국어 근거", "English claim").inputIds.isNotEmpty())
        val result = JSONObject().put("tokenizer_sha256", FusionVerifierModelPolicy.TOKENIZER_SHA256)
            .put("tokenizer_size_bytes", tokenizerFile.length()).put("cold_parse_ms", loadMs)
            .put("onnxruntime_version", "1.22.0")
            .put("available_providers", JSONArray(OrtEnvironment.getAvailableProviders().map { it.name }))
            .put("status", "PASS")
        File(requireNotNull(context.getExternalFilesDir(null)), "v0_9c_s20_tokenizer_load.json")
            .writeText(result.toString(2), Charsets.UTF_8)
    }

    @Test
    fun measureSequentialPairsThatFinishInsideFiveSecondDeadline() = runBlocking {
        val dir = File(context.filesDir, "verifier/current")
        val fixture = JSONObject(instrumentation.context.assets.open("v0_9c/parity_fixture.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
        val rows = fixture.getJSONArray("rows")
        val gold = (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.getString("kind") == "gold" }
        assertTrue(gold.isNotEmpty())
        val proxy = FusionVerifierRuntimeOwner.proxy(dir)
        val first = gold.first()
        val warm = proxy.verify(VerificationPairRequest(first.getString("claim"), first.getString("evidence"), evidenceIdentity = "deadline-warm"))
        assertTrue(warm is com.projectnuke.fusion.audit.VerifierBackendResult.Verified)
        val started = System.nanoTime()
        var completedInsideDeadline = 0
        var firstOverrunMs: Double? = null
        while (true) {
            if ((System.nanoTime() - started) / 1_000_000.0 >= 5_000.0) break
            val index = completedInsideDeadline
            val row = gold[index % gold.size]
            val result = proxy.verify(VerificationPairRequest(
                row.getString("claim"), row.getString("evidence"), evidenceIdentity = "deadline-$index",
            ))
            assertTrue(result is com.projectnuke.fusion.audit.VerifierBackendResult.Verified)
            val finishedMs = (System.nanoTime() - started) / 1_000_000.0
            if (finishedMs > 5_000.0) {
                firstOverrunMs = finishedMs - 5_000.0
                break
            }
            completedInsideDeadline++
        }
        val deadlineElapsedMs = (System.nanoTime() - started) / 1_000_000.0
        val worstStarted = System.nanoTime()
        repeat(48) { index ->
            val row = gold[index % gold.size]
            val result = proxy.verify(VerificationPairRequest(
                row.getString("claim"), row.getString("evidence"), evidenceIdentity = "deadline-48-$index",
            ))
            assertTrue(result is com.projectnuke.fusion.audit.VerifierBackendResult.Verified)
        }
        val result = JSONObject().put("schema_version", 1).put("production_deadline_ms", 5000)
            .put("thread_policy", JSONObject().put("intra_op", FusionVerifierExecutionPolicy.INTRA_OP_THREADS)
                .put("inter_op", FusionVerifierExecutionPolicy.INTER_OP_THREADS).put("provider", "CPUExecutionProvider"))
            .put("method", "Warm stable runtime, heterogeneous human-gold pairs in frozen order; count only calls that returned by 5000ms.")
            .put("completed_pairs_within_deadline", completedInsideDeadline)
            .put("elapsed_until_boundary_ms", deadlineElapsedMs)
            .put("first_overrun_call_ms", firstOverrunMs)
            .put("six_claims_x_eight_pairs_ms", (System.nanoTime() - worstStarted) / 1_000_000.0)
            .put("deadline_modified", false)
            .put("status", if (completedInsideDeadline >= 1) "MEASURED" else "FAIL")
        File(requireNotNull(context.getExternalFilesDir(null)), "v0_9c_s20_backend_deadline.json")
            .writeText(result.toString(2), Charsets.UTF_8)
    }

    @Test
    fun exactTokenizerGoldParityLifecycleAndCpuQualification() = runBlocking {
        val stage = File(context.getExternalFilesDir(null), "verifier-stage")
        val modelManifestFile = File(stage, FusionVerifierModelPolicy.MANIFEST_FILE)
        assertTrue("push model_manifest.json and pinned assets to app external verifier-stage", modelManifestFile.isFile)
        val modelManifest = FusionVerifierModelManifest.parse(modelManifestFile)
        assertNotNull(modelManifest)
        modelManifest!!
        val privateDir = File(context.filesDir, "verifier/current")
        assertTrue(privateDir.isDirectory || privateDir.mkdirs())
        val modelSource = File(stage, modelManifest.modelFile)
        val tokenizerSource = File(stage, FusionVerifierModelPolicy.TOKENIZER_FILE)
        val configSource = File(stage, FusionVerifierModelPolicy.TOKENIZER_CONFIG_FILE)
        assertTrue(FusionVerifierAssetProvider.copyVerified(modelSource, File(privateDir, modelManifest.modelFile),
            modelManifest.modelSha256, modelManifest.modelSizeBytes))
        assertTrue(FusionVerifierAssetProvider.copyVerified(tokenizerSource,
            File(privateDir, FusionVerifierModelPolicy.TOKENIZER_FILE), FusionVerifierModelPolicy.TOKENIZER_SHA256,
            FusionVerifierModelPolicy.TOKENIZER_SIZE_BYTES))
        assertTrue(FusionVerifierAssetProvider.copyVerified(configSource,
            File(privateDir, FusionVerifierModelPolicy.TOKENIZER_CONFIG_FILE),
            FusionVerifierModelPolicy.TOKENIZER_CONFIG_SHA256, FusionVerifierModelPolicy.TOKENIZER_CONFIG_SIZE_BYTES))
        // Publish manifest last; runtime cannot see a valid candidate before every payload passes verification.
        File(privateDir, FusionVerifierModelPolicy.MANIFEST_FILE).writeBytes(modelManifestFile.readBytes())
        val beforeLoad = memorySnapshot()
        val fixture = JSONObject(instrumentation.context.assets.open("v0_9c/parity_fixture.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
        val rows = fixture.getJSONArray("rows")
        val c2Reference = JSONObject(instrumentation.context.assets.open("v0_9c/c2_host_gold_predictions.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
        assertEquals("C2_INT8_BLOCK32", c2Reference.getString("candidate_id"))
        assertEquals(modelManifest.modelSha256, c2Reference.getString("model_sha256"))
        val c2Rows = c2Reference.getJSONArray("rows")
        assertEquals(160, c2Rows.length())
        val c2ById = (0 until c2Rows.length()).associate { i ->
            c2Rows.getJSONObject(i).getString("item_id") to c2Rows.getJSONObject(i)
        }
        assertEquals(160, c2ById.size)
        val proxy = FusionVerifierRuntimeOwner.proxy(privateDir)
        assertEquals("fusion-me5-koen-manifest", proxy.descriptor.id)
        val tokenizer = XlmRPairTokenizer.load(File(privateDir, FusionVerifierModelPolicy.TOKENIZER_FILE))
        val afterTokenizerLoad = memorySnapshot()
        FusionVerifierRuntimeOwner.tokenizerOverrideForTests = tokenizer
        val first = rows.getJSONObject(0)
        assertTrue(proxy.verify(VerificationPairRequest(first.getString("claim"), first.getString("evidence"),
            evidenceIdentity = first.getString("item_id"))) is com.projectnuke.fusion.audit.VerifierBackendResult.Verified)
        val engine = FusionVerifierRuntimeOwner.currentBackendForTests()!!
        val afterFirstInference = memorySnapshot()
        val hostGoldFs = mutableSetOf<String>()
        val deviceGoldFs = mutableSetOf<String>()
        val confusion = Array(3) { IntArray(3) }
        var tokenizerParity = 0
        var labelParity = 0
        var maxAbsLogitDelta = 0.0
        var maxProbabilityDelta = 0.0
        val goldRows = mutableListOf<JSONObject>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val actualEncoding = tokenizer.encode(row.getString("evidence"), row.getString("claim"))
            assertArrayEquals("token ids ${row.optString("item_id", row.optString("probe_id"))}",
                longs(row.getJSONArray("input_ids")), actualEncoding.inputIds)
            assertArrayEquals(longs(row.getJSONArray("attention_mask")), actualEncoding.attentionMask)
            tokenizerParity++
            if (row.getString("kind") != "gold") continue
            goldRows += row
            val itemId = row.getString("item_id")
            val result = proxy.verify(VerificationPairRequest(
                claimText = row.getString("claim"),
                evidenceText = row.getString("evidence"),
                evidenceIdentity = itemId,
            ))
            assertTrue("backend unavailable/failed on $itemId: $result", result is com.projectnuke.fusion.audit.VerifierBackendResult.Verified)
            result as com.projectnuke.fusion.audit.VerifierBackendResult.Verified
            assertEquals(3, result.scores.size)
            val probabilities = result.scores.map { it.value }
            assertTrue(probabilities.all { it.isFinite() && it in 0.0..1.0 })
            assertTrue(kotlin.math.abs(probabilities.sum() - 1.0) < 1e-6)
            val host = requireNotNull(c2ById[itemId])
            val hostLabel = host.getString("predicted_label")
            assertEquals("C2 host and frozen FP16 labels differ for $itemId", row.getString("reference_label"), hostLabel)
            if (result.label.name == hostLabel) labelParity++
            assertEquals("FP16 host argmax mismatch $itemId", hostLabel, result.label.name)
            val actualLogits = requireNotNull(engine.inferForBenchmark(actualEncoding))
            val actualLogitMax = actualLogits.maxOrNull()!!.toDouble()
            val hostLogits = host.getJSONArray("logits")
            for (c in 0..2) maxAbsLogitDelta = maxOf(maxAbsLogitDelta,
                kotlin.math.abs((actualLogits[c].toDouble() - actualLogitMax) - hostLogits.getDouble(c)))
            val hostP = host.getJSONArray("probabilities")
            for (c in 0..2) maxProbabilityDelta = maxOf(maxProbabilityDelta,
                kotlin.math.abs(probabilities[c] - hostP.getDouble(c)))
            assertTrue("probability diagnostic delta > .01 for $itemId", (0..2).all {
                kotlin.math.abs(probabilities[it] - hostP.getDouble(it)) <= 0.01
            })
            val goldIndex = LABELS.indexOf(row.getString("gold_label"))
            val predIndex = LABELS.indexOf(result.label.name)
            confusion[goldIndex][predIndex]++
            if (row.getString("gold_label") != "SUPPORTED" && hostLabel == "SUPPORTED") hostGoldFs += itemId
            if (row.getString("gold_label") != "SUPPORTED" && result.label.name == "SUPPORTED") deviceGoldFs += itemId
        }
        assertEquals(173, tokenizerParity)
        assertEquals(160, goldRows.size)
        assertEquals(160, labelParity)
        assertEquals(hostGoldFs, deviceGoldFs)
        assertEquals(4, deviceGoldFs.size)
        assertArrayEquals(intArrayOf(25, 4, 10), confusion[0])
        assertArrayEquals(intArrayOf(4, 31, 5), confusion[1])
        assertArrayEquals(intArrayOf(0, 11, 70), confusion[2])
        assertTrue("numerical diagnostic tolerance exceeded", maxProbabilityDelta <= 0.01)

        val longProbe = (0 until rows.length()).map { rows.getJSONObject(it) }
            .first { it.optString("probe_id") == "near_256" }
        val latencyByLength = JSONObject()
        val samplesByLength = LinkedHashMap<String, List<Double>>()
        for (length in listOf(64, 128, 256)) {
            val encoded = tokenizer.encode(longProbe.getString("evidence"), longProbe.getString("claim"), length)
            assertEquals(length, encoded.inputIds.size)
            repeat(20) { assertNotNull(engine.inferForBenchmark(encoded)) }
            val samples = ArrayList<Double>(200)
            repeat(200) {
                val started = System.nanoTime()
                assertNotNull(engine.inferForBenchmark(encoded))
                samples += (System.nanoTime() - started) / 1_000_000.0
            }
            samplesByLength[length.toString()] = samples
            latencyByLength.put(length.toString(), summarize(samples))
        }

        val auditRows = goldRows.take(8)
        val workload = JSONObject()
        for (count in listOf(1, 4, 8)) workload.put("${count}_pair_ms", measureWorkload(proxy, auditRows, count))
        val started48 = System.nanoTime()
        repeat(48) { index ->
            val row = auditRows[index % auditRows.size]
            val result = proxy.verify(VerificationPairRequest(row.getString("claim"), row.getString("evidence"),
                evidenceIdentity = "audit-48-$index"))
            assertTrue(result is com.projectnuke.fusion.audit.VerifierBackendResult.Verified)
        }
        val multiClaim48Ms = (System.nanoTime() - started48) / 1_000_000.0
        workload.put("6_claims_x_8_pairs_ms", multiClaim48Ms)
        // This older-device prequalification records budget pressure without treating it as
        // semantic correctness failure. The production 5-second deadline remains unchanged.
        workload.put("6_claims_x_8_pairs_within_5s", multiClaim48Ms <= 5_000.0)

        val diagnostics = engine.diagnostics
        val afterSession = afterFirstInference
        val ownerStableProxy = FusionVerifierRuntimeOwner.proxy(privateDir)
        assertTrue(ownerStableProxy.verify(VerificationPairRequest("claim", "evidence", evidenceIdentity = "lifecycle-1"))
            is com.projectnuke.fusion.audit.VerifierBackendResult.Verified)
        FusionVerifierRuntimeOwner.close()
        FusionVerifierRuntimeOwner.close()
        assertTrue(ownerStableProxy.verify(VerificationPairRequest("claim", "evidence", evidenceIdentity = "lifecycle-recreate"))
            is com.projectnuke.fusion.audit.VerifierBackendResult.Verified)
        val afterRecreate = memorySnapshot()
        val thermalBefore = thermalStatus()
        val firstWindow = mutableListOf<Double>()
        val lastWindow = mutableListOf<Double>()
        val sustainedStarted = System.nanoTime()
        var sustainedRuns = 0
        while ((System.nanoTime() - sustainedStarted) < 30_000_000_000L) {
            val index = sustainedRuns++
            val row = goldRows[index % goldRows.size]
            val t = System.nanoTime()
            val result = ownerStableProxy.verify(VerificationPairRequest(row.getString("claim"), row.getString("evidence"),
                evidenceIdentity = "thermal-$index"))
            assertTrue(result is com.projectnuke.fusion.audit.VerifierBackendResult.Verified)
            val ms = (System.nanoTime() - t) / 1_000_000.0
            if (index < 20) firstWindow += ms
            if ((System.nanoTime() - sustainedStarted) >= 28_000_000_000L) lastWindow += ms
        }
        val thermalAfter = thermalStatus()
        val peak = memorySnapshot()
        FusionVerifierRuntimeOwner.close()
        System.gc()
        val afterClose = memorySnapshot()
        FusionVerifierRuntimeOwner.tokenizerOverrideForTests = null

        val output = JSONObject()
            .put("schema_version", 1)
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("android_release", Build.VERSION.RELEASE)
                .put("api_level", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                .put("soc_manufacturer", Build.SOC_MANUFACTURER)
                .put("soc_model", Build.SOC_MODEL)
                .put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown")
                .put("onnxruntime_version", "1.22.0")
                .put("execution_provider", "CPUExecutionProvider"))
            .put("model_sha256", modelManifest.modelSha256)
            .put("model_size_bytes", modelManifest.modelSizeBytes)
            .put("candidate_id", modelManifest.candidateId)
            .put("host_prediction_artifact_sha256", c2Reference.getString("source_artifact_sha256"))
            .put("tokenizer_sha256", FusionVerifierModelPolicy.TOKENIZER_SHA256)
            .put("tokenizer_parity", JSONObject().put("items", tokenizerParity).put("status", "PASS"))
            .put("gold_parity", JSONObject().put("items", labelParity).put("status", "PASS")
                .put("new_false_support_count", (deviceGoldFs - hostGoldFs).size)
                .put("false_support_count", deviceGoldFs.size).put("false_support_denominator", 121)
                .put("max_abs_logit_delta", maxAbsLogitDelta)
                .put("max_abs_probability_delta", maxProbabilityDelta)
                .put("confusion_rows_gold_cols_pred", JSONArray().put(JSONArray(confusion[0])).put(JSONArray(confusion[1])).put(JSONArray(confusion[2]))))
            .put("load_diagnostics", JSONObject()
                .put("verified_asset_check_ms", diagnostics.assetVerificationMs)
                .put("tokenizer_load_ms", diagnostics.tokenizerLoadMs)
                .put("session_create_ms", diagnostics.sessionCreateMs)
                .put("first_end_to_end_ms", diagnostics.firstVerificationMs))
            .put("memory", JSONObject().put("before_load", beforeLoad).put("after_session", afterSession)
                .put("after_tokenizer_load", afterTokenizerLoad).put("after_first_inference", afterFirstInference)
                .put("peak_after_sustained", peak).put("after_owner_close_gc", afterClose)
                .put("session_pss_delta_kb", afterSession.getLong("total_pss_kb") - beforeLoad.getLong("total_pss_kb"))
                .put("peak_pss_delta_kb", peak.getLong("total_pss_kb") - beforeLoad.getLong("total_pss_kb")))
            .put("latency_model_only_ms", latencyByLength)
            .put("audit_workloads", workload)
            .put("lifecycle", JSONObject().put("close_twice", "PASS").put("stable_proxy_recreate", "PASS"))
            .put("thermal", JSONObject().put("duration_runs", sustainedRuns)
                .put("duration_ms", (System.nanoTime() - sustainedStarted) / 1_000_000.0)
                .put("first_window_p50_ms", percentile(firstWindow, 0.5))
                .put("last_window_p50_ms", percentile(lastWindow, 0.5)).put("drift_ratio", percentile(lastWindow, 0.5) / percentile(firstWindow, 0.5))
                .put("status_before", thermalBefore).put("status_after", thermalAfter))
        val resultDir = requireNotNull(context.getExternalFilesDir(null))
        File(resultDir, "v0_9c_device_results.json").writeText(output.toString(2), Charsets.UTF_8)
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("v0_9c_result", output.toString()) })
    }

    private suspend fun measureWorkload(
        proxy: com.projectnuke.fusion.audit.VerifierBackend,
        rows: List<JSONObject>,
        count: Int,
    ): Double {
        val started = System.nanoTime()
        repeat(count) { index ->
            val row = rows[index % rows.size]
            assertTrue(proxy.verify(VerificationPairRequest(row.getString("claim"), row.getString("evidence"),
                evidenceIdentity = "audit-$count-$index")) is com.projectnuke.fusion.audit.VerifierBackendResult.Verified)
        }
        return (System.nanoTime() - started) / 1_000_000.0
    }

    private fun memorySnapshot(): JSONObject {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val amInfo = ActivityManager.MemoryInfo()
        activity.getMemoryInfo(amInfo)
        return JSONObject().put("total_pss_kb", info.totalPss).put("native_pss_kb", info.nativePss)
            .put("dalvik_pss_kb", info.dalvikPss).put("native_heap_bytes", Debug.getNativeHeapAllocatedSize())
            .put("java_heap_used_bytes", runtime.totalMemory() - runtime.freeMemory())
            .put("available_system_ram_bytes", amInfo.availMem).put("low_memory", amInfo.lowMemory)
    }

    private fun thermalStatus(): Int = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus

    private fun summarize(values: List<Double>): JSONObject = JSONObject().put("count", values.size)
        .put("mean_ms", values.average()).put("stddev_ms", sqrt(values.sumOf { (it - values.average()).let { d -> d * d } } / values.size))
        .put("min_ms", values.minOrNull()).put("max_ms", values.maxOrNull())
        .put("p50_ms", percentile(values, .50)).put("p90_ms", percentile(values, .90))
        .put("p95_ms", percentile(values, .95)).put("p99_ms", percentile(values, .99))

    private fun percentile(values: List<Double>, p: Double): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    private fun longs(array: JSONArray) = LongArray(array.length()) { array.getLong(it) }

    private fun manifestJson(name: String, sha: String, bytes: Long) = JSONObject()
        .put("schema_version", 1).put("candidate_id", "instrumentation-invalid-asset")
        .put("format", "ONNX_FP16").put("model_file", name).put("model_sha256", sha)
        .put("model_size_bytes", bytes).put("parent_fp32_sha256", FusionVerifierModelPolicy.DEFAULT_PARENT_FP32_SHA256)
        .put("quantization_method", "test").put("base_model", FusionVerifierModelPolicy.MODEL_ID)
        .put("base_revision", FusionVerifierModelPolicy.BASE_REVISION)
        .put("training_condition", FusionVerifierModelPolicy.CONDITION).put("seed", FusionVerifierModelPolicy.SEED)
        .put("pair_order", "evidence=premise,claim=hypothesis").put("max_sequence_length", 256)
        .put("label_order", JSONArray(FusionVerifierModelPolicy.LABELS)).put("android_qualified", false)

    private fun sha256(file: File): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private val LABELS = FusionVerifierModelPolicy.LABELS
}
