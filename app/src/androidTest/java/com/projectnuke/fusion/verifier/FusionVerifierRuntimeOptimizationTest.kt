package com.projectnuke.fusion.verifier

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.fusion.MainActivity
import com.projectnuke.fusion.audit.VerificationPairRequest
import com.projectnuke.fusion.audit.VerifierBackendResult
import com.projectnuke.fusion.model.RuntimeCpuDeviceSignals
import com.projectnuke.fusion.model.RuntimeCpuPolicyInput
import com.projectnuke.fusion.model.RuntimeCpuThreadPolicy
import com.projectnuke.fusion.model.RuntimePerformancePreference
import com.projectnuke.fusion.model.RuntimePerformancePreferenceStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded, debug-only S20+ runtime comparison. Does not change production runtime options. */
@RunWith(AndroidJUnit4::class)
class FusionVerifierRuntimeOptimizationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test
    fun provisionPinnedC2ForRemainingDiagnosticsThroughVerifiedAssetPath() {
        val stage = File(requireNotNull(context.getExternalFilesDir(null)), "verifier-stage")
        val manifestFile = File(stage, FusionVerifierModelPolicy.MANIFEST_FILE)
        val manifest = requireNotNull(FusionVerifierModelManifest.parse(manifestFile))
        assertEquals("C2_INT8_BLOCK32", manifest.candidateId)
        assertEquals(C2_SHA256, manifest.modelSha256)
        assertEquals(C2_SIZE_BYTES, manifest.modelSizeBytes)

        val privateDir = File(context.filesDir, "verifier/current")
        assertTrue(privateDir.isDirectory || privateDir.mkdirs())
        assertTrue(FusionVerifierAssetProvider.copyVerified(
            File(stage, manifest.modelFile), File(privateDir, manifest.modelFile), manifest.modelSha256, manifest.modelSizeBytes,
        ))
        assertTrue(FusionVerifierAssetProvider.copyVerified(
            File(stage, FusionVerifierModelPolicy.TOKENIZER_FILE), File(privateDir, FusionVerifierModelPolicy.TOKENIZER_FILE),
            FusionVerifierModelPolicy.TOKENIZER_SHA256, FusionVerifierModelPolicy.TOKENIZER_SIZE_BYTES,
        ))
        assertTrue(FusionVerifierAssetProvider.copyVerified(
            File(stage, FusionVerifierModelPolicy.TOKENIZER_CONFIG_FILE), File(privateDir, FusionVerifierModelPolicy.TOKENIZER_CONFIG_FILE),
            FusionVerifierModelPolicy.TOKENIZER_CONFIG_SHA256, FusionVerifierModelPolicy.TOKENIZER_CONFIG_SIZE_BYTES,
        ))
        File(privateDir, FusionVerifierModelPolicy.MANIFEST_FILE).writeBytes(manifestFile.readBytes())
        val verified = FusionVerifierAssetProvider(privateDir).verify()
        assertTrue("C2 and pinned tokenizer must pass production asset verification", verified is FusionVerifierAssetResult.Ready)
    }

    @Test
    fun closeCancellationBackgroundAndMemoryTrimRecreateWithoutStaleRuntime() = runBlocking {
        val verifierDir = File(context.filesDir, "verifier/current")
        val preferences = context.getSharedPreferences(RuntimePerformancePreferenceStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val oldRawPreference = preferences.all[RuntimePerformancePreferenceStore.KEY]
        val store = RuntimePerformancePreferenceStore(preferences)
        assertTrue(store.write(RuntimePerformancePreference()))
        val initialSignals = RuntimeCpuDeviceSignals.observe(context.applicationContext)
        val expectedThreads = RuntimeCpuThreadPolicy.resolve(store.read(), initialSignals).threadCount
        val proxy = FusionVerifierRuntimeOwner.proxy(verifierDir) {
            RuntimeCpuPolicyInput(store.read(), RuntimeCpuDeviceSignals.observe(context.applicationContext))
        }
        val fixture = JSONObject(instrumentation.context.assets.open("v0_9c/parity_fixture.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
        val fixtureRows = fixture.getJSONArray("rows")
        val row = (0 until fixtureRows.length()).map { fixtureRows.getJSONObject(it) }
            .first { it.optString("probe_id") == "near_256" }
        val request = VerificationPairRequest(row.getString("claim"), row.getString("evidence"), evidenceIdentity = "s24-lifecycle")
        val tokenizer = XlmRPairTokenizer.load(File(verifierDir, FusionVerifierModelPolicy.TOKENIZER_FILE))
        val encoded = tokenizer.encode(row.getString("evidence"), row.getString("claim"), 256)
        val events = JSONArray()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            assertTrue(proxy.verify(request) is VerifierBackendResult.Verified)
            val activeBackend = requireNotNull(FusionVerifierRuntimeOwner.currentBackendForTests())
            assertEquals(expectedThreads, activeBackend.loadedIntraOpThreads)

            val closeStarted = System.nanoTime()
            val closeInference = async(Dispatchers.Default) { activeBackend.inferForBenchmark(encoded) }
            delay(20)
            FusionVerifierRuntimeOwner.close()
            val closeInferenceResult = withTimeout(15_000) { closeInference.await() }
            val closeInferenceMs = (System.nanoTime() - closeStarted) / 1_000_000.0
            assertNotNull("in-flight native lease must finish safely after owner close", closeInferenceResult)
            assertTrue("close must overlap a substantive inference", closeInferenceMs >= 50.0)
            assertNull(FusionVerifierRuntimeOwner.currentBackendForTests())
            events.put(JSONObject().put("case", "close_during_active_inference").put("status", "PASS")
                .put("inference_elapsed_ms", closeInferenceMs))

            assertTrue("stable proxy must lazily recreate after close", proxy.verify(request) is VerifierBackendResult.Verified)
            val afterCloseBackend = requireNotNull(FusionVerifierRuntimeOwner.currentBackendForTests())
            assertTrue(afterCloseBackend !== activeBackend)

            val cancelStarted = System.nanoTime()
            val cancelledInference = async(Dispatchers.Default) { afterCloseBackend.inferForBenchmark(encoded) }
            delay(20)
            cancelledInference.cancelAndJoin()
            val cancelJoinMs = (System.nanoTime() - cancelStarted) / 1_000_000.0
            assertTrue(cancelledInference.isCancelled)
            assertTrue("cancellation must settle through the in-flight call", cancelJoinMs >= 50.0)
            assertTrue("runtime remains usable after settled cancellation", proxy.verify(request) is VerifierBackendResult.Verified)
            events.put(JSONObject().put("case", "cancellation_during_active_inference").put("status", "PASS")
                .put("cancel_join_ms", cancelJoinMs))

            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.moveToState(Lifecycle.State.CREATED)
            withTimeout(5_000) {
                while (FusionVerifierRuntimeOwner.currentBackendForTests() != null) delay(25)
            }
            events.put(JSONObject().put("case", "background_release").put("status", "PASS"))
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue("foreground must recreate verifier on demand", proxy.verify(request) is VerifierBackendResult.Verified)
            events.put(JSONObject().put("case", "foreground_recreate").put("status", "PASS"))

            val trimPipe = instrumentation.uiAutomation.executeShellCommand(
                "am send-trim-memory com.projectnuke.fusion RUNNING_LOW",
            )
            val trimOutput = ParcelFileDescriptor.AutoCloseInputStream(trimPipe).bufferedReader().use { it.readText() }
            withTimeout(5_000) {
                while (FusionVerifierRuntimeOwner.currentBackendForTests() != null) delay(25)
            }
            events.put(JSONObject().put("case", "memory_pressure_release").put("status", "PASS")
                .put("shell_result", trimOutput.trim()))
            assertTrue("memory-pressure release must recreate cleanly", proxy.verify(request) is VerifierBackendResult.Verified)

            repeat(2) { index ->
                scenario.moveToState(Lifecycle.State.CREATED)
                withTimeout(5_000) {
                    while (FusionVerifierRuntimeOwner.currentBackendForTests() != null) delay(25)
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertTrue(proxy.verify(request) is VerifierBackendResult.Verified)
                events.put(JSONObject().put("case", "background_foreground_cycle_${index + 1}").put("status", "PASS"))
            }
            val lifecycleArtifact = if (android.os.Build.DEVICE == "y2q") {
                "s20_continuation_lifecycle.json"
            } else {
                "s24_continuation_lifecycle.json"
            }
            File(requireNotNull(context.getExternalFilesDir(null)), lifecycleArtifact)
                .writeText(JSONObject().put("cases", events).put("no_stale_reuse", true)
                    .put("no_use_after_close", true).put("no_deadlock", true)
                    .put("resolved_threads", expectedThreads)
                    .put("resolver_signals", JSONObject().put("available_processors", initialSignals.availableProcessors)
                        .put("low_ram", initialSignals.isLowRamDevice).put("power_saver", initialSignals.powerSaveMode)
                        .put("thermal_status", initialSignals.thermalStatus))
                    .toString(2), Charsets.UTF_8)
        } finally {
            scenario?.close()
            FusionVerifierRuntimeOwner.close()
            when (oldRawPreference) {
                is String -> preferences.edit().putString(RuntimePerformancePreferenceStore.KEY, oldRawPreference).apply()
                else -> preferences.edit().remove(RuntimePerformancePreferenceStore.KEY).apply()
            }
        }
    }

    @Test
    fun profilePinnedTokenizerHotPathWithoutChangingPairSemantics() {
        val tokenizerFile = File(context.filesDir, "verifier/current/${FusionVerifierModelPolicy.TOKENIZER_FILE}")
        assertTrue("run verified C2 qualification setup first", tokenizerFile.isFile)
        val coldStarted = System.nanoTime()
        val tokenizer = XlmRPairTokenizer.load(tokenizerFile)
        val coldLoadMs = elapsed(coldStarted)
        val fixture = JSONObject(instrumentation.context.assets.open("v0_9c/parity_fixture.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
        val rowsArray = fixture.getJSONArray("rows")
        val rows = (0 until rowsArray.length()).map { rowsArray.getJSONObject(it) }
            .filter { it.getString("kind") == "gold" }
        assertEquals(160, rows.size)
        val varied = measureEncodes(200) { i ->
            val row = rows[i % rows.size]
            tokenizer.encode(row.getString("evidence"), row.getString("claim"))
        }
        val first = rows.first()
        val repeatedPair = measureEncodes(200) { _ ->
            tokenizer.encode(first.getString("evidence"), first.getString("claim"))
        }
        val sameClaimDifferentEvidence = measureEncodes(200) { i ->
            val evidence = rows[i % rows.size].getString("evidence")
            tokenizer.encode(evidence, first.getString("claim"))
        }
        val output = JSONObject().put("schema_version", 1)
            .put("tokenizer_sha256", FusionVerifierModelPolicy.TOKENIZER_SHA256)
            .put("tokenizer_size_bytes", tokenizerFile.length())
            .put("cold_tokenizer_parse_ms", coldLoadMs)
            .put("warm_pair_tokenization_ms", JSONObject().put("varied_gold_pairs", summarize(varied))
                .put("same_pair_repeated", summarize(repeatedPair))
                .put("same_claim_different_evidence", summarize(sameClaimDifferentEvidence)))
            .put("claim_side_token_cache_adopted", false)
            .put("reason", "Pair longest-first truncation depends on both full inputs; no separate-side caching change was made without an exact final-pair equivalence proof.")
        File(requireNotNull(context.getExternalFilesDir(null)), "v0_9c_s20_tokenizer_hot_path.json")
            .writeText(output.toString(2), Charsets.UTF_8)
    }

    @Test
    fun compareCpuTwoThreadBatch4And8WithEquivalentSequentialCalls() {
        val modelDir = File(context.filesDir, "verifier/current")
        val manifest = requireNotNull(FusionVerifierModelManifest.parse(File(modelDir, FusionVerifierModelPolicy.MANIFEST_FILE)))
        assertEquals(C2_SHA256, manifest.modelSha256)
        val tokenizer = XlmRPairTokenizer.load(File(modelDir, FusionVerifierModelPolicy.TOKENIZER_FILE))
        val fixture = JSONObject(instrumentation.context.assets.open("v0_9c/parity_fixture.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
        val rowsJson = fixture.getJSONArray("rows")
        val rows = (0 until rowsJson.length()).map { rowsJson.getJSONObject(it) }
            .filter { it.getString("kind") == "gold" }
        val reference = JSONObject(instrumentation.context.assets.open("v0_9c/c2_host_gold_predictions.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }).getJSONArray("rows")
        val expected = (0 until reference.length()).associate { i ->
            reference.getJSONObject(i).getString("item_id") to reference.getJSONObject(i).getString("predicted_label")
        }
        val encodedGold = rows.map { tokenizer.encode(it.getString("evidence"), it.getString("claim")) }
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        options.addCPU(true)
        val environment = OrtEnvironment.getEnvironment()
        val session = environment.createSession(File(modelDir, manifest.modelFile).absolutePath, options)
        try {
            val correctness = JSONArray()
            for (batchSize in listOf(4, 8)) {
                val predictions = mutableListOf<String>()
                encodedGold.chunked(batchSize).forEach { batch -> predictions += runBatch(environment, session, batch) }
                assertEquals(160, predictions.size)
                var agreements = 0
                rows.forEachIndexed { index, row ->
                    if (predictions[index] == expected[row.getString("item_id")]) agreements++
                }
                assertEquals("batch $batchSize must preserve C2 host labels", 160, agreements)
                correctness.put(JSONObject().put("batch_size", batchSize).put("gold_items", predictions.size)
                    .put("host_label_agreements", agreements).put("status", "PASS"))
            }
            val longProbe = (0 until rowsJson.length()).map { rowsJson.getJSONObject(it) }
                .first { it.optString("probe_id") == "near_256" }
            val encoded128 = tokenizer.encode(longProbe.getString("evidence"), longProbe.getString("claim"), 128)
            val batchResults = JSONArray()
            for (batchSize in listOf(4, 8)) {
                val encodedBatch = List(batchSize) { encoded128 }
                repeat(20) {
                    runBatch(environment, session, encodedBatch)
                    repeat(batchSize) { runOnce(environment, session, encoded128) }
                }
                val batchMs = ArrayList<Double>(50)
                val singlesMs = ArrayList<Double>(50)
                repeat(50) {
                    val batchStarted = System.nanoTime()
                    runBatch(environment, session, encodedBatch)
                    batchMs += elapsed(batchStarted)
                    val singlesStarted = System.nanoTime()
                    repeat(batchSize) { runOnce(environment, session, encoded128) }
                    singlesMs += elapsed(singlesStarted)
                }
                val batchStats = summarize(batchMs)
                val sequentialStats = summarize(singlesMs)
                val batchP50 = batchStats.getDouble("p50_ms")
                val sequentialP50 = sequentialStats.getDouble("p50_ms")
                batchResults.put(JSONObject().put("batch_size", batchSize).put("sequence_length", 128)
                    .put("warmups", 20).put("measured_groups", 50).put("batch_group_ms", batchStats)
                    .put("equivalent_sequential_group_ms", sequentialStats)
                    .put("batch_speedup_vs_sequential", sequentialP50 / batchP50)
                    .put("interpretation", if (batchP50 < sequentialP50) "BATCH_FASTER" else "BATCH_NOT_FASTER"))
            }
            File(requireNotNull(context.getExternalFilesDir(null)), "v0_9c_s20_batching_diagnostic.json")
                .writeText(JSONObject().put("schema_version", 1).put("model_sha256", C2_SHA256)
                    .put("provider", "CPUExecutionProvider").put("intra_op_threads", 2).put("inter_op_threads", 1)
                    .put("execution_mode", "ORT_SEQUENTIAL").put("gold_batch_correctness", correctness)
                    .put("latency_128", batchResults).put("production_batching_adopted", false)
                    .toString(2), Charsets.UTF_8)
        } finally {
            session.close()
            options.close()
        }
    }

    @Test
    fun compareBoundedCpuThreadsAndAvailableAndroidProviders() {
        val modelDir = File(context.filesDir, "verifier/current")
        val modelManifest = requireNotNull(FusionVerifierModelManifest.parse(
            File(modelDir, FusionVerifierModelPolicy.MANIFEST_FILE),
        ))
        assertEquals("C2_INT8_BLOCK32", modelManifest.candidateId)
        assertEquals(C2_SHA256, modelManifest.modelSha256)
        assertEquals(C2_SIZE_BYTES, modelManifest.modelSizeBytes)
        val model = File(modelDir, modelManifest.modelFile)
        val tokenizerFile = File(modelDir, FusionVerifierModelPolicy.TOKENIZER_FILE)
        assertEquals(C2_SIZE_BYTES, model.length())
        assertEquals(FusionVerifierModelPolicy.TOKENIZER_SIZE_BYTES, tokenizerFile.length())

        val tokenizer = XlmRPairTokenizer.load(tokenizerFile)
        val fixture = JSONObject(instrumentation.context.assets.open("v0_9c/parity_fixture.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
        val fixtureRows = fixture.getJSONArray("rows")
        val allGold = (0 until fixtureRows.length()).map { fixtureRows.getJSONObject(it) }
            .filter { it.getString("kind") == "gold" }
        assertEquals(160, allGold.size)
        val requestedGoldSamples = InstrumentationRegistry.getArguments().getString("gold_sample_count")?.toIntOrNull() ?: 160
        require(requestedGoldSamples in 1..allGold.size) { "gold_sample_count must be between 1 and ${allGold.size}" }
        val gold = allGold.take(requestedGoldSamples)
        val reference = JSONObject(instrumentation.context.assets.open("v0_9c/c2_host_gold_predictions.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
        assertEquals(C2_SHA256, reference.getString("model_sha256"))
        val references = reference.getJSONArray("rows").let { rows ->
            (0 until rows.length()).associate { rows.getJSONObject(it).getString("item_id") to rows.getJSONObject(it) }
        }
        assertEquals(160, references.size)

        val longPair = (0 until fixtureRows.length()).map { fixtureRows.getJSONObject(it) }
            .first { it.optString("probe_id") == "near_256" }
        val arguments = InstrumentationRegistry.getArguments()
        val sequenceLengths = arguments.getString("sequence_lengths")?.split(',')?.mapNotNull(String::toIntOrNull)?.distinct()
            ?: listOf(64, 128, 256)
        assertTrue("invalid sequence lengths: $sequenceLengths", sequenceLengths.isNotEmpty() && sequenceLengths.all { it in setOf(64, 128, 256) })
        val warmups = arguments.getString("warmups")?.toIntOrNull() ?: 20
        val measuredRuns = arguments.getString("measured_runs")?.toIntOrNull() ?: 200
        require(warmups >= 0 && measuredRuns >= 50) { "warmups must be nonnegative and measured_runs at least 50" }
        val lengthPairs = sequenceLengths.associateWith { length ->
            tokenizer.encode(longPair.getString("evidence"), longPair.getString("claim"), length).also {
                assertEquals(length, it.inputIds.size)
            }
        }
        val configs = listOf(
            RuntimeCase("CPU_SEQUENTIAL_1", "CPU", 1),
            RuntimeCase("CPU_SEQUENTIAL_2", "CPU", 2),
            RuntimeCase("CPU_SEQUENTIAL_4", "CPU", 4),
            RuntimeCase("XNNPACK_2", "XNNPACK", 2),
            RuntimeCase("NNAPI_2", "NNAPI", 2),
        )
        val requestedConfigIds = arguments.getString("config_ids")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
        val selectedConfigs = configs.filter { requestedConfigIds == null || it.id in requestedConfigIds }
        assertTrue("unknown or empty config selection: $requestedConfigIds", selectedConfigs.isNotEmpty())
        val sustainedMillis = arguments.getString("sustained_ms")?.toLongOrNull() ?: 30_000L
        require(sustainedMillis >= 1_000L) { "sustained_ms must be at least 1000" }
        val measureCapacity = arguments.getString("measure_capacity")?.toBooleanStrictOrNull() ?: true
        val environment = OrtEnvironment.getEnvironment()
        val providerNames = OrtEnvironment.getAvailableProviders().map { it.name }
        val results = JSONArray()
        val acceptedConfigurations = mutableListOf<String>()
        for (config in selectedConfigs) {
            if (config.provider != "CPU" && providerNames.none { it.equals(config.provider, ignoreCase = true) }) {
                results.put(JSONObject().put("id", config.id).put("provider_requested", config.provider)
                    .put("status", "UNSUPPORTED").put("reason", "provider not listed by ORT Android"))
                continue
            }
            val result = runCatching {
                measureConfiguration(environment, model, tokenizer, gold, references, lengthPairs, longPair, config,
                    measureCapacity, sustainedMillis, warmups, measuredRuns)
            }
                .getOrElse { error ->
                    JSONObject().put("id", config.id).put("provider_requested", config.provider)
                        .put("threads", JSONObject().put("intra_op", config.intraThreads).put("inter_op", 1))
                        .put("status", "UNSUPPORTED").put("error", error.javaClass.name + ": " + error.message)
                }
            results.put(result)
            if (result.optString("correctness") == "PASS") acceptedConfigurations += config.id
        }
        val output = JSONObject().put("schema_version", 1)
            .put("device", JSONObject().put("manufacturer", android.os.Build.MANUFACTURER)
                .put("model", android.os.Build.MODEL).put("device", android.os.Build.DEVICE)
                .put("android_release", android.os.Build.VERSION.RELEASE).put("api_level", android.os.Build.VERSION.SDK_INT)
                .put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                .put("soc_manufacturer", android.os.Build.SOC_MANUFACTURER).put("soc_model", android.os.Build.SOC_MODEL))
            .put("model_sha256", C2_SHA256).put("model_size_bytes", C2_SIZE_BYTES)
            .put("tokenizer_sha256", FusionVerifierModelPolicy.TOKENIZER_SHA256)
            .put("ort_version", "1.22.0").put("available_providers", JSONArray(providerNames))
            .put("baseline", JSONObject().put("id", "CPU_SEQUENTIAL_1").put("source", "untouched V0.9C canonical run"))
            .put("method", JSONObject().put("warmups_per_length", warmups).put("measured_runs_per_length", measuredRuns)
                .put("sequence_lengths", JSONArray(sequenceLengths))
                .put("execution_mode", "ORT_SEQUENTIAL").put("inter_op_threads", 1)
                .put("optimization", "ORT_ENABLE_ALL")
                .put("stage_timing", "tokenizer, input tensor construction, ORT session.run, stable softmax/argmax, model-only total and end-to-end pair total timed separately")
                .put("selected_config_ids", JSONArray(selectedConfigs.map { it.id }))
                .put("sustained_ms", sustainedMillis).put("measure_capacity", measureCapacity))
            .put("configurations", results).put("correctness_passing_configurations", JSONArray(acceptedConfigurations))
            .put("production_deadline_ms", 5000).put("deadline_changed", false)
        val target = File(requireNotNull(context.getExternalFilesDir(null)), "v0_9c_s20_runtime_optimization.json")
        target.writeText(output.toString(2), Charsets.UTF_8)
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("v0_9c_optimization", output.toString()) })
        if (selectedConfigs.any { it.provider == "CPU" }) {
            assertTrue("selected CPU configuration did not preserve exact gold semantics", selectedConfigs
                .filter { it.provider == "CPU" }.all { it.id in acceptedConfigurations })
        }
    }

    private fun measureConfiguration(
        environment: OrtEnvironment,
        model: File,
        tokenizer: XlmRPairTokenizer,
        gold: List<JSONObject>,
        references: Map<String, JSONObject>,
        lengthPairs: Map<Int, XlmRPairEncoding>,
        longPair: JSONObject,
        config: RuntimeCase,
        measureCapacity: Boolean,
        sustainedMillis: Long,
        warmups: Int,
        measuredRuns: Int,
    ): JSONObject {
        val before = memorySnapshot()
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(config.intraThreads)
        options.setInterOpNumThreads(1)
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        when (config.provider) {
            "CPU" -> options.addCPU(true)
            "XNNPACK" -> { options.addXnnpack(emptyMap()); options.addCPU(true) }
            "NNAPI" -> { options.addNnapi(); options.addCPU(true) }
            else -> error("unrecognized provider ${config.provider}")
        }
        val loadStarted = System.nanoTime()
        val session = try { environment.createSession(model.absolutePath, options) } catch (error: Throwable) {
            options.close()
            throw error
        }
        val sessionLoadMs = elapsed(loadStarted)
        try {
            assertEquals(setOf("input_ids", "attention_mask"), session.inputNames)
            assertEquals(setOf("logits"), session.outputNames)
            val afterSession = memorySnapshot()
            val goldStarted = System.nanoTime()
            val hostFalseSupports = mutableSetOf<String>()
            val deviceFalseSupports = mutableSetOf<String>()
            val confusion = Array(3) { IntArray(3) }
            var agreement = 0
            var maxProbabilityDelta = 0.0
            val perItemLatency = ArrayList<Double>(gold.size)
            for (row in gold) {
                val itemId = row.getString("item_id")
                val encoded = tokenizer.encode(row.getString("evidence"), row.getString("claim"))
                val sampleStarted = System.nanoTime()
                val output = runOnce(environment, session, encoded)
                perItemLatency += elapsed(sampleStarted)
                val prediction = output.label
                val expected = requireNotNull(references[itemId])
                if (prediction == expected.getString("predicted_label")) agreement++
                val hostProbabilities = expected.getJSONArray("probabilities")
                output.probabilities.forEachIndexed { i, p -> maxProbabilityDelta = maxOf(maxProbabilityDelta, kotlin.math.abs(p - hostProbabilities.getDouble(i))) }
                if (row.getString("gold_label") != "SUPPORTED" && expected.getString("predicted_label") == "SUPPORTED") hostFalseSupports += itemId
                if (row.getString("gold_label") != "SUPPORTED" && prediction == "SUPPORTED") deviceFalseSupports += itemId
                val g = FusionVerifierModelPolicy.LABELS.indexOf(row.getString("gold_label"))
                val p = FusionVerifierModelPolicy.LABELS.indexOf(prediction)
                confusion[g][p]++
            }
            val goldElapsed = elapsed(goldStarted)
            val afterGold = memorySnapshot()
            val latency = JSONObject()
            val componentLatency = JSONObject()
            val fullPairLatency = JSONObject()
            for ((length, encoded) in lengthPairs) {
                repeat(warmups) {
                    val warm = tokenizer.encode(longPair.getString("evidence"), longPair.getString("claim"), length)
                    runOnce(environment, session, warm)
                }
                val samples = ArrayList<Double>(measuredRuns)
                val tokenSamples = ArrayList<Double>(measuredRuns)
                val tensorSamples = ArrayList<Double>(measuredRuns)
                val ortSamples = ArrayList<Double>(measuredRuns)
                val postSamples = ArrayList<Double>(measuredRuns)
                val fullPairSamples = ArrayList<Double>(measuredRuns)
                repeat(measuredRuns) {
                    val pairStarted = System.nanoTime()
                    val tokenStarted = System.nanoTime()
                    val sampleEncoding = tokenizer.encode(longPair.getString("evidence"), longPair.getString("claim"), length)
                    tokenSamples += elapsed(tokenStarted)
                    val measured = runOnce(environment, session, sampleEncoding)
                    fullPairSamples += elapsed(pairStarted)
                    samples += measured.totalMs
                    tensorSamples += measured.tensorMs
                    ortSamples += measured.ortMs
                    postSamples += measured.postprocessMs
                }
                latency.put(length.toString(), summarize(samples))
                componentLatency.put(length.toString(), JSONObject().put("tokenization", summarize(tokenSamples))
                    .put("tensor_construction", summarize(tensorSamples)).put("ort_inference", summarize(ortSamples))
                    .put("postprocess", summarize(postSamples)))
                fullPairLatency.put(length.toString(), summarize(fullPairSamples))
            }
            val workloads = JSONObject()
            for (count in listOf(1, 4, 8)) {
                val started = System.nanoTime()
                repeat(count) { i ->
                    val row = gold[i % gold.size]
                    val encoded = tokenizer.encode(row.getString("evidence"), row.getString("claim"))
                    runOnce(environment, session, encoded)
                }
                workloads.put("${count}_pair_ms", elapsed(started))
            }
            var completedPairs = 0
            var maxPairsElapsedMs = 0.0
            if (measureCapacity) {
                val maxPairsStarted = System.nanoTime()
                while (System.nanoTime() - maxPairsStarted < 5_000_000_000L) {
                    val row = gold[completedPairs % gold.size]
                    val encoded = tokenizer.encode(row.getString("evidence"), row.getString("claim"))
                    runOnce(environment, session, encoded)
                    if (System.nanoTime() - maxPairsStarted <= 5_000_000_000L) completedPairs++ else break
                }
                maxPairsElapsedMs = elapsed(maxPairsStarted)
                workloads.put("pairs_completed_within_five_seconds", completedPairs)
                    .put("capacity_measurement_window_ms", maxPairsElapsedMs)
                val worstCaseStarted = System.nanoTime()
                repeat(48) { index ->
                    val row = gold[index % gold.size]
                    runOnce(environment, session, tokenizer.encode(row.getString("evidence"), row.getString("claim")))
                }
                workloads.put("48_pair_worst_case_ms", elapsed(worstCaseStarted))
            }
            val sustainedStarted = System.nanoTime()
            val thermalStatusBefore = currentThermalStatus()
            val batteryTemperatureBefore = batteryTemperatureCelsius()
            val thermalFirst = ArrayList<Double>()
            val thermalLast = ArrayList<Double>()
            var runs = 0
            while (System.nanoTime() - sustainedStarted < sustainedMillis * 1_000_000L) {
                val row = gold[runs % gold.size]
                val encoded = tokenizer.encode(row.getString("evidence"), row.getString("claim"))
                val started = System.nanoTime()
                runOnce(environment, session, encoded)
                val ms = elapsed(started)
                if (runs < 20) thermalFirst += ms
                if (System.nanoTime() - sustainedStarted >= sustainedMillis * 800_000L) thermalLast += ms
                runs++
            }
            val afterSustained = memorySnapshot()
            val thermalStatusAfter = currentThermalStatus()
            val batteryTemperatureAfter = batteryTemperatureCelsius()
            val thermal = JSONObject().put("duration_ms", elapsed(sustainedStarted)).put("runs", runs)
                .put("first_window_p50_ms", percentile(thermalFirst, .5)).put("last_window_p50_ms", percentile(thermalLast, .5))
                .put("p50_drift_ratio", percentile(thermalLast, .5) / percentile(thermalFirst, .5))
                .put("first_window_p95_ms", percentile(thermalFirst, .95)).put("last_window_p95_ms", percentile(thermalLast, .95))
                .put("p95_drift_ratio", percentile(thermalLast, .95) / percentile(thermalFirst, .95))
                .put("thermal_status_before", thermalStatusBefore).put("thermal_status_after", thermalStatusAfter)
                .put("battery_temperature_before_c", batteryTemperatureBefore ?: JSONObject.NULL)
                .put("battery_temperature_after_c", batteryTemperatureAfter ?: JSONObject.NULL)
            val expectedConfusion = arrayOf(intArrayOf(25, 4, 10), intArrayOf(4, 31, 5), intArrayOf(0, 11, 70))
            val exactConfusion = if (gold.size == 160) {
                confusion.indices.all { confusion[it].contentEquals(expectedConfusion[it]) }
            } else {
                confusion.sumOf { row -> row.sum() } == gold.size
            }
            val exactSafety = hostFalseSupports == deviceFalseSupports && (gold.size != 160 || deviceFalseSupports.size == 4)
            val parityPassed = agreement == gold.size && exactConfusion && exactSafety
            return JSONObject().put("id", config.id).put("provider_requested", config.provider)
                .put("provider_session_result", "PASS")
                .put("provider_assignment_detail", "ORT Java API does not expose per-node partition assignment; requested EP registered with CPU fallback")
                .put("threads", JSONObject().put("intra_op", config.intraThreads).put("inter_op", 1))
                .put("execution_mode", "ORT_SEQUENTIAL").put("optimization", "ORT_ENABLE_ALL")
                .put("session_create_ms", sessionLoadMs).put("model_io_contract", "input_ids/attention_mask int64; logits [batch,3]")
                .put("host_gold_parity", JSONObject().put("agreements", agreement).put("items", gold.size)
                    .put("max_probability_delta", maxProbabilityDelta)
                    .put("false_support_count", deviceFalseSupports.size).put("new_false_support_count", (deviceFalseSupports - hostFalseSupports).size)
                    .put("comparison_scope", if (gold.size == 160) "full_sealed_gold" else "first_${gold.size}_sealed_gold_items")
                    .put("false_support_denominator", if (gold.size == 160) 121 else gold.size)
                    .put("confusion_rows_gold_cols_pred", JSONArray().put(JSONArray(confusion[0])).put(JSONArray(confusion[1])).put(JSONArray(confusion[2])))
                    .put("elapsed_ms", goldElapsed).put("mean_pair_verify_ms", perItemLatency.average()))
                .put("correctness", if (parityPassed) "PASS" else "FAIL")
                .put("memory", JSONObject().put("before_session", before).put("after_session", afterSession)
                    .put("after_gold", afterGold).put("after_sustained", afterSustained)
                    .put("session_pss_delta_kb", afterSession.getInt("total_pss_kb") - before.getInt("total_pss_kb"))
                    .put("peak_pss_delta_kb", afterSustained.getInt("total_pss_kb") - before.getInt("total_pss_kb")))
                .put("latency_model_only_total_ms", latency).put("latency_components_ms", componentLatency)
                .put("latency_full_pair_total_ms", fullPairLatency)
                .put("audit_workloads", workloads).put("sustained", thermal)
        } finally {
            session.close()
            options.close()
        }
    }

    private fun runOnce(environment: OrtEnvironment, session: OrtSession, encoded: XlmRPairEncoding): RunMetrics {
        val tensorStarted = System.nanoTime()
        val ids = OnnxTensor.createTensor(environment, arrayOf(encoded.inputIds) as Any)
        val mask = try { OnnxTensor.createTensor(environment, arrayOf(encoded.attentionMask) as Any) } catch (error: Throwable) {
            ids.close()
            throw error
        }
        val tensorMs = elapsed(tensorStarted)
        val ortStarted = System.nanoTime()
        val logits = try {
            session.run(linkedMapOf("input_ids" to ids, "attention_mask" to mask)).use { result ->
                val batch = result[0].value as? Array<*> ?: error("wrong logits rank")
                require(batch.size == 1)
                (batch[0] as? FloatArray)?.copyOf() ?: error("wrong logits dtype")
            }
        } finally {
            mask.close()
            ids.close()
        }
        val ortMs = elapsed(ortStarted)
        val postStarted = System.nanoTime()
        val probs = requireNotNull(OnnxEvidenceVerifierBackend.stableSoftmax(logits))
        val label = FusionVerifierModelPolicy.LABELS[probs.indices.maxByOrNull { probs[it] }!!]
        val postMs = elapsed(postStarted)
        return RunMetrics(logits, probs.toList(), label, tensorMs, ortMs, postMs, tensorMs + ortMs + postMs)
    }

    private fun runBatch(environment: OrtEnvironment, session: OrtSession, batch: List<XlmRPairEncoding>): List<String> {
        require(batch.isNotEmpty())
        val maxLength = batch.maxOf { it.inputIds.size }
        val idsData = Array(batch.size) { row -> LongArray(maxLength) { 1L }.also { target ->
            batch[row].inputIds.copyInto(target, endIndex = batch[row].inputIds.size)
        } }
        val maskData = Array(batch.size) { row -> LongArray(maxLength).also { target ->
            batch[row].attentionMask.copyInto(target, endIndex = batch[row].attentionMask.size)
        } }
        val ids = OnnxTensor.createTensor(environment, idsData as Any)
        val mask = try { OnnxTensor.createTensor(environment, maskData as Any) } catch (error: Throwable) {
            ids.close()
            throw error
        }
        try {
            return session.run(linkedMapOf("input_ids" to ids, "attention_mask" to mask)).use { result ->
                val rows = result[0].value as? Array<*> ?: error("wrong batch logits rank")
                require(rows.size == batch.size)
                rows.map { output ->
                    val logits = output as? FloatArray ?: error("wrong logits dtype")
                    val probabilities = requireNotNull(OnnxEvidenceVerifierBackend.stableSoftmax(logits))
                    FusionVerifierModelPolicy.LABELS[probabilities.indices.maxByOrNull { probabilities[it] }!!]
                }
            }
        } finally {
            mask.close()
            ids.close()
        }
    }

    private fun memorySnapshot(): JSONObject {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return JSONObject().put("total_pss_kb", info.totalPss).put("native_pss_kb", info.nativePss)
            .put("dalvik_pss_kb", info.dalvikPss).put("native_heap_bytes", Debug.getNativeHeapAllocatedSize())
            .put("java_heap_used_bytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
    }

    private fun currentThermalStatus(): Int =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus

    private fun batteryTemperatureCelsius(): Double? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val deciCelsius = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return deciCelsius.takeIf { it != Int.MIN_VALUE }?.div(10.0)
    }

    private fun summarize(values: List<Double>): JSONObject = JSONObject().put("count", values.size)
        .put("mean_ms", values.average()).put("stddev_ms", kotlin.math.sqrt(values.sumOf { (it - values.average()).let { d -> d * d } } / values.size))
        .put("min_ms", values.minOrNull()).put("max_ms", values.maxOrNull())
        .put("p50_ms", percentile(values, .50)).put("p90_ms", percentile(values, .90))
        .put("p95_ms", percentile(values, .95)).put("p99_ms", percentile(values, .99))

    private fun measureEncodes(count: Int, encode: (Int) -> XlmRPairEncoding): List<Double> {
        repeat(20) { encode(it) }
        return List(count) { index ->
            val started = System.nanoTime()
            encode(index)
            elapsed(started)
        }
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    private fun elapsed(start: Long): Double = (System.nanoTime() - start) / 1_000_000.0

    private data class RuntimeCase(val id: String, val provider: String, val intraThreads: Int)
    private data class RunMetrics(
        val logits: FloatArray,
        val probabilities: List<Double>,
        val label: String,
        val tensorMs: Double,
        val ortMs: Double,
        val postprocessMs: Double,
        val totalMs: Double,
    )

    private companion object {
        const val C2_SHA256 = "a92852d669e0f475d5732fb2f48fc502e6523a6da41676c09a5821b099cbec14"
        const val C2_SIZE_BYTES = 145_631_064L
    }
}
