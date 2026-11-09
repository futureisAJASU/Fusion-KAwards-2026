package com.projectnuke.fusion.verifier

import android.content.Context
import android.os.Debug
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.fusion.audit.VerificationPairRequest
import com.projectnuke.fusion.audit.VerifierBackendResult
import com.projectnuke.fusion.model.RuntimeCpuDeviceSignals
import com.projectnuke.fusion.model.RuntimeCpuPolicyInput
import com.projectnuke.fusion.model.RuntimeCpuThreadPolicy
import com.projectnuke.fusion.model.RuntimePerformanceMode
import com.projectnuke.fusion.model.RuntimePerformancePreference
import com.projectnuke.fusion.model.RuntimePerformancePreferenceStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Physical-device verification of the global preference through the stable production runtime proxy. */
@RunWith(AndroidJUnit4::class)
class FusionVerifierDynamicPerformanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test
    fun minAutoAndMaxThreadBudgetsRecreateSafelyAndKeepExactC2Predictions() = runBlocking {
        val stage = File(context.getExternalFilesDir(null), "verifier-stage")
        val stagedManifest = File(stage, FusionVerifierModelPolicy.MANIFEST_FILE)
        assertTrue("verified C2 test staging must be installed first", stagedManifest.isFile)
        val manifest = requireNotNull(FusionVerifierModelManifest.parse(stagedManifest))
        assertEquals("C2_INT8_BLOCK32", manifest.candidateId)
        assertEquals(C2_SHA256, manifest.modelSha256)
        assertEquals(C2_SIZE_BYTES, manifest.modelSizeBytes)

        val verifierDir = File(context.filesDir, "verifier/current")
        assertTrue(verifierDir.isDirectory || verifierDir.mkdirs())
        assertTrue(FusionVerifierAssetProvider.copyVerified(
            File(stage, manifest.modelFile), File(verifierDir, manifest.modelFile), manifest.modelSha256, manifest.modelSizeBytes,
        ))
        assertTrue(FusionVerifierAssetProvider.copyVerified(
            File(stage, FusionVerifierModelPolicy.TOKENIZER_FILE),
            File(verifierDir, FusionVerifierModelPolicy.TOKENIZER_FILE),
            FusionVerifierModelPolicy.TOKENIZER_SHA256,
            FusionVerifierModelPolicy.TOKENIZER_SIZE_BYTES,
        ))
        assertTrue(FusionVerifierAssetProvider.copyVerified(
            File(stage, FusionVerifierModelPolicy.TOKENIZER_CONFIG_FILE),
            File(verifierDir, FusionVerifierModelPolicy.TOKENIZER_CONFIG_FILE),
            FusionVerifierModelPolicy.TOKENIZER_CONFIG_SHA256,
            FusionVerifierModelPolicy.TOKENIZER_CONFIG_SIZE_BYTES,
        ))
        File(verifierDir, FusionVerifierModelPolicy.MANIFEST_FILE).writeBytes(stagedManifest.readBytes())

        val preferences = context.getSharedPreferences(
            RuntimePerformancePreferenceStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        val oldRawPreference = preferences.all[RuntimePerformancePreferenceStore.KEY]
        val store = RuntimePerformancePreferenceStore(preferences)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val signals = RuntimeCpuDeviceSignals.observe(context.applicationContext)
        val initialDevice = JSONObject()
            .put("manufacturer", android.os.Build.MANUFACTURER)
            .put("model", android.os.Build.MODEL)
            .put("device", android.os.Build.DEVICE)
            .put("android_release", android.os.Build.VERSION.RELEASE)
            .put("api_level", android.os.Build.VERSION.SDK_INT)
            .put("primary_abi", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            .put("soc_manufacturer", android.os.Build.SOC_MANUFACTURER)
            .put("soc_model", android.os.Build.SOC_MODEL)
            .put("available_processors", signals.availableProcessors)
            .put("low_ram_device", signals.isLowRamDevice)
            .put("power_save_mode", signals.powerSaveMode)
            .put("thermal_status_start", signals.thermalStatus)
            .put("total_ram_bytes_approx", totalRamBytes())

        FusionVerifierRuntimeOwner.resetForTests()
        val proxy = FusionVerifierRuntimeOwner.proxy(verifierDir) {
            RuntimeCpuPolicyInput(
                preference = store.read(),
                deviceSignals = RuntimeCpuDeviceSignals.observe(context.applicationContext),
            )
        }
        val fixture = JSONObject(instrumentation.context.assets.open("v0_9c/parity_fixture.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
        val fixtureRows = fixture.getJSONArray("rows")
        val tokenizer = XlmRPairTokenizer.load(File(verifierDir, FusionVerifierModelPolicy.TOKENIZER_FILE))
        var tokenizerMatches = 0
        for (index in 0 until fixtureRows.length()) {
            val row = fixtureRows.getJSONObject(index)
            val encoded = tokenizer.encode(row.getString("evidence"), row.getString("claim"))
            if (encoded.inputIds.contentEquals(longArray(row.getJSONArray("input_ids"))) &&
                encoded.attentionMask.contentEquals(longArray(row.getJSONArray("attention_mask")))) {
                tokenizerMatches++
            }
        }

        val goldRows = (0 until fixtureRows.length()).map { fixtureRows.getJSONObject(it) }
            .filter { it.getString("kind") == "gold" }
        assertEquals(160, goldRows.size)
        val probe = (0 until fixtureRows.length()).map { fixtureRows.getJSONObject(it) }
            .first { it.optString("probe_id") == "near_256" }
        val expectedConfusion = listOf(listOf(25, 4, 10), listOf(4, 31, 5), listOf(0, 11, 70))
        val modes = listOf(
            ModeSpec("MANUAL_MINIMUM", RuntimePerformancePreference(RuntimePerformanceMode.MANUAL, 0)),
            ModeSpec("AUTO", RuntimePerformancePreference(RuntimePerformanceMode.AUTO, RuntimePerformancePreference.DEFAULT_MANUAL_LEVEL)),
            ModeSpec("MANUAL_MAXIMUM", RuntimePerformancePreference(RuntimePerformanceMode.MANUAL, 100)),
        )
        val requestedMode = InstrumentationRegistry.getArguments().getString("mode")
        val selectedModes = modes.filter { requestedMode == null || it.id == requestedMode }
        assertTrue("unknown or empty mode selection: $requestedMode", selectedModes.isNotEmpty())
        val modeResults = JSONArray()
        val overallFailures = JSONArray()
        val allRunOutputs = mutableListOf<JSONObject>()

        try {
            for (mode in selectedModes) {
                val previousThreads = FusionVerifierRuntimeOwner.currentBackendForTests()?.loadedIntraOpThreads
                assertTrue("could not persist test mode ${mode.id}", store.write(mode.preference))
                val liveSignals = RuntimeCpuDeviceSignals.observe(context.applicationContext)
                val resolved = RuntimeCpuThreadPolicy.resolve(mode.preference, liveSignals)
                val pssBefore = memorySnapshot()
                val firstStarted = System.nanoTime()
                val firstResult = proxy.verify(requestFor(goldRows.first()))
                val firstEndToEndMs = elapsed(firstStarted)
                val backend = requireNotNull(FusionVerifierRuntimeOwner.currentBackendForTests())
                val actualThreads = backend.loadedIntraOpThreads
                val modeFailures = JSONArray()
                var agreement = 0
                var newFalseSupports = 0
                val falseSupportIds = JSONArray()
                var maxProbabilityDelta = 0.0
                val confusion = Array(3) { IntArray(3) }
                goldRows.forEach { row ->
                    val itemId = row.getString("item_id")
                    val result = if (row === goldRows.first()) firstResult else proxy.verify(requestFor(row))
                    if (result !is VerifierBackendResult.Verified) {
                        modeFailures.put("${itemId}:${result::class.simpleName}")
                        return@forEach
                    }
                    val hostLabel = row.getString("reference_label")
                    if (result.label.name == hostLabel) agreement++ else modeFailures.put(itemId)
                    val probabilities = result.scores.map { it.value }
                    val reference = row.getJSONArray("reference_probabilities")
                    probabilities.forEachIndexed { index, value ->
                        maxProbabilityDelta = maxOf(maxProbabilityDelta, kotlin.math.abs(value - reference.getDouble(index)))
                    }
                    val goldIndex = FusionVerifierModelPolicy.LABELS.indexOf(row.getString("gold_label"))
                    val predictedIndex = FusionVerifierModelPolicy.LABELS.indexOf(result.label.name)
                    if (goldIndex >= 0 && predictedIndex >= 0) confusion[goldIndex][predictedIndex]++
                    if (row.getString("gold_label") != "SUPPORTED" && result.label.name == "SUPPORTED") {
                        falseSupportIds.put(itemId)
                        if (hostLabel != "SUPPORTED") newFalseSupports++
                    }
                }

                val measuredBackend = requireNotNull(FusionVerifierRuntimeOwner.currentBackendForTests())
                val sequenceLatency = JSONObject()
                for (length in listOf(64, 128, 256)) {
                    val encoded = tokenizer.encode(probe.getString("evidence"), probe.getString("claim"), length)
                    assertEquals("fixed-length probe $length", length, encoded.inputIds.size)
                    repeat(20) { assertTrue(measuredBackend.inferForBenchmark(encoded) != null) }
                    val samples = ArrayList<Double>(200)
                    repeat(200) {
                        val started = System.nanoTime()
                        checkNotNull(measuredBackend.inferForBenchmark(encoded))
                        samples += elapsed(started)
                    }
                    sequenceLatency.put(length.toString(), summarize(samples))
                }

                val auditWorkloads = JSONObject()
                for (count in listOf(1, 4, 8)) {
                    val started = System.nanoTime()
                    repeat(count) { index -> proxy.verify(requestFor(goldRows[index % goldRows.size])) }
                    auditWorkloads.put("${count}_pair_ms", elapsed(started))
                }
                val pssAfter = memorySnapshot()
                val modeJson = JSONObject()
                    .put("id", mode.id)
                    .put("preference", JSONObject().put("mode", mode.preference.mode.name)
                        .put("manual_level", mode.preference.manualLevel))
                    .put("resolved_threads", resolved.threadCount)
                    .put("resolution_reason", resolved.reason.name)
                    .put("device_ceiling", resolved.deviceCeiling)
                    .put("previous_runtime_threads", previousThreads ?: JSONObject.NULL)
                    .put("actual_session_threads", actualThreads)
                    .put("runtime_recreated_for_thread_change", previousThreads != null && previousThreads != actualThreads)
                    .put("first_end_to_end_verify_ms", firstEndToEndMs)
                    .put("session_creation_ms", backend.diagnostics.sessionCreateMs ?: JSONObject.NULL)
                    .put("warm_model_inference_ms", sequenceLatency)
                    .put("audit_workloads_ms", auditWorkloads)
                    .put("memory", JSONObject().put("before", pssBefore).put("after", pssAfter)
                        .put("pss_delta_kb", pssAfter.getInt("total_pss_kb") - pssBefore.getInt("total_pss_kb")))
                    .put("thermal_status_before", liveSignals.thermalStatus)
                    .put("thermal_status_after", thermalStatus(power))
                    .put("gold", JSONObject().put("items", goldRows.size).put("argmax_agreements", agreement)
                        .put("new_false_support_count", newFalseSupports).put("false_support_item_ids", falseSupportIds)
                        .put("false_support_count", falseSupportIds.length()).put("false_support_denominator", 121)
                        .put("max_probability_delta_vs_c2_host", maxProbabilityDelta)
                        .put("confusion_rows_gold_cols_prediction", confusionJson(confusion))
                        .put("failures", modeFailures))

                if (actualThreads != resolved.threadCount) {
                    overallFailures.put("${mode.id}: expected ${resolved.threadCount}, loaded $actualThreads threads")
                }
                if (agreement != 160 || newFalseSupports != 0 || falseSupportIds.length() != 4 || confusionJson(confusion).toString() != confusionJson(expectedConfusion).toString()) {
                    overallFailures.put("${mode.id}: semantic parity or false-support gate failed")
                }
                modeResults.put(modeJson)
                allRunOutputs += modeJson

                if (mode.id == "AUTO") {
                    val encoded = tokenizer.encode(probe.getString("evidence"), probe.getString("claim"), 128)
                    val firstWindow = ArrayList<Double>(20)
                    val lastWindow = ArrayList<Double>(20)
                    val sustainedStarted = System.nanoTime()
                    var runs = 0
                    while (System.nanoTime() - sustainedStarted < 30_000_000_000L) {
                        val started = System.nanoTime()
                        checkNotNull(measuredBackend.inferForBenchmark(encoded))
                        val latency = elapsed(started)
                        if (runs < 20) firstWindow += latency
                        if (System.nanoTime() - sustainedStarted >= 28_000_000_000L) lastWindow += latency
                        runs++
                    }
                    modeJson.put("sustained_30s", JSONObject().put("runs", runs)
                        .put("first_window_p50_ms", percentile(firstWindow, 0.50))
                        .put("last_window_p50_ms", percentile(lastWindow, 0.50))
                        .put("p50_drift_ratio", percentile(lastWindow, 0.50) / percentile(firstWindow, 0.50))
                        .put("thermal_status_before", liveSignals.thermalStatus)
                        .put("thermal_status_after", thermalStatus(power)))
                }
            }

            // Verify the app's global AUTO default after the manual diagnostic modes and stable-proxy recreation.
            assertTrue(store.write(RuntimePerformancePreference()))
            val autoResult = proxy.verify(requestFor(goldRows.first()))
            val autoBackend = requireNotNull(FusionVerifierRuntimeOwner.currentBackendForTests())
            val autoSignal = RuntimeCpuDeviceSignals.observe(context.applicationContext)
            val autoResolution = RuntimeCpuThreadPolicy.resolve(RuntimePerformancePreference(), autoSignal)
            val lifecycle = JSONObject()
                .put("auto_loaded_threads", autoBackend.loadedIntraOpThreads)
                .put("auto_expected_threads", autoResolution.threadCount)
                .put("first_inference", autoResult is VerifierBackendResult.Verified)
            FusionVerifierRuntimeOwner.close()
            val recreatedResult = proxy.verify(requestFor(goldRows.first()))
            lifecycle.put("stable_proxy_close_recreate", recreatedResult is VerifierBackendResult.Verified)
                .put("recreated_threads", FusionVerifierRuntimeOwner.currentBackendForTests()?.loadedIntraOpThreads ?: JSONObject.NULL)
            val lifecyclePassed = autoBackend.loadedIntraOpThreads == autoResolution.threadCount &&
                recreatedResult is VerifierBackendResult.Verified &&
                FusionVerifierRuntimeOwner.currentBackendForTests()?.loadedIntraOpThreads == autoResolution.threadCount

            val result = JSONObject()
                .put("schema_version", 1)
                .put("phase", "V0.9C S20+ adaptive global CPU performance policy")
                .put("device", initialDevice)
                .put("model", JSONObject().put("candidate_id", manifest.candidateId)
                    .put("format", manifest.format).put("sha256", manifest.modelSha256).put("size_bytes", manifest.modelSizeBytes))
                .put("tokenizer", JSONObject().put("sha256", FusionVerifierModelPolicy.TOKENIZER_SHA256)
                    .put("size_bytes", FusionVerifierModelPolicy.TOKENIZER_SIZE_BYTES)
                    .put("fixture_rows", fixtureRows.length()).put("exact_matches", tokenizerMatches)
                    .put("status", if (tokenizerMatches == 173) "PASS" else "FAIL"))
                .put("method", JSONObject().put("provider", "CPUExecutionProvider")
                    .put("execution_mode", "ORT_SEQUENTIAL").put("inter_op_threads", 1)
                    .put("graph_optimization", "ORT_ENABLE_ALL").put("warmups_per_sequence", 20)
                    .put("measured_runs_per_sequence", 200).put("sequence_lengths", JSONArray(listOf(64, 128, 256)))
                    .put("deadline_ms", 5000).put("deadline_changed", false))
                .put("mode_filter", requestedMode ?: "ALL")
                .put("modes", modeResults)
                .put("lifecycle", lifecycle)
                .put("lifecycle_status", if (lifecyclePassed) "PASS" else "FAIL")
                .put("failures", overallFailures)
                .put("android_qualified", false)
                .put("target_s24_qualified", false)
            val output = File(requireNotNull(context.getExternalFilesDir(null)), "v0_9c_s20_dynamic_performance.json")
            output.writeText(result.toString(2), Charsets.UTF_8)
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("v0_9c_s20_dynamic", result.toString()) })

            assertEquals("pinned tokenizer fixture", 173, tokenizerMatches)
            assertTrue("global AUTO / close-recreate failed", lifecyclePassed)
            assertTrue("mode correctness gates failed: $overallFailures", overallFailures.length() == 0)
        } finally {
            FusionVerifierRuntimeOwner.close()
            when (oldRawPreference) {
                null -> preferences.edit().remove(RuntimePerformancePreferenceStore.KEY).commit()
                is String -> preferences.edit().putString(RuntimePerformancePreferenceStore.KEY, oldRawPreference).commit()
                else -> preferences.edit().remove(RuntimePerformancePreferenceStore.KEY).commit()
            }
        }
    }

    private fun requestFor(row: JSONObject) = VerificationPairRequest(
        claimText = row.getString("claim"),
        evidenceText = row.getString("evidence"),
        evidenceIdentity = row.getString("item_id"),
    )

    private fun totalRamBytes(): Long = File("/proc/meminfo").useLines { lines ->
        (lines.firstOrNull { it.startsWith("MemTotal:") }?.filter(Char::isDigit)?.toLongOrNull() ?: 0L) * 1024L
    }

    private fun memorySnapshot(): JSONObject {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return JSONObject().put("total_pss_kb", info.totalPss).put("native_pss_kb", info.nativePss)
            .put("dalvik_pss_kb", info.dalvikPss).put("native_heap_bytes", Debug.getNativeHeapAllocatedSize())
            .put("java_heap_used_bytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
    }

    private fun thermalStatus(power: PowerManager): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) power.currentThermalStatus else -1

    private fun longArray(array: JSONArray) = LongArray(array.length()) { array.getLong(it) }

    private fun summarize(values: List<Double>) = JSONObject()
        .put("count", values.size).put("mean_ms", values.average())
        .put("min_ms", values.minOrNull()).put("max_ms", values.maxOrNull())
        .put("p50_ms", percentile(values, .50)).put("p95_ms", percentile(values, .95))
        .put("p99_ms", percentile(values, .99))

    private fun percentile(values: List<Double>, p: Double): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    private fun confusionJson(confusion: Array<IntArray>) = JSONArray().apply {
        confusion.forEach { put(JSONArray(it.toList())) }
    }

    private fun confusionJson(confusion: List<List<Int>>) = JSONArray().apply {
        confusion.forEach { put(JSONArray(it)) }
    }

    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1_000_000.0

    private data class ModeSpec(val id: String, val preference: RuntimePerformancePreference)

    private companion object {
        const val C2_SHA256 = "a92852d669e0f475d5732fb2f48fc502e6523a6da41676c09a5821b099cbec14"
        const val C2_SIZE_BYTES = 145_631_064L
    }
}
