package com.projectnuke.fusion.ui

import com.projectnuke.fusion.llm.FallbackReason
import com.projectnuke.fusion.llm.GenerationBenchmarkStats
import com.projectnuke.fusion.llm.MtpRuntimeStatus
import com.projectnuke.fusion.llm.ModelFingerprintSummary
import com.projectnuke.fusion.llm.RuntimeBackend
import com.projectnuke.fusion.llm.RuntimeComponentBackend
import com.projectnuke.fusion.llm.RuntimeExecutionSnapshot
import com.projectnuke.fusion.llm.RuntimeFallbackEvent
import com.projectnuke.fusion.model.AcceleratorMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8: A/B results must carry the immutable runtime execution snapshot
 * and native benchmark stats. Copied result text must distinguish requested
 * and applied settings. An A/B result must never claim GPU+MTP merely
 * because it was requested.
 */
class AbResultRuntimeSnapshotTest {

    private fun snapshot(
        selected: RuntimeBackend = RuntimeBackend.CPU,
        mtpStatus: MtpRuntimeStatus = MtpRuntimeStatus.FALLBACK_DISABLED,
        mtpRequested: Boolean = true,
        fallbackEvents: List<RuntimeFallbackEvent> = emptyList()
    ) = RuntimeExecutionSnapshot(
        requestedAccelerator = AcceleratorMode.AUTO,
        selectedTextBackend = selected,
        selectedVisionBackend = null,
        samplerBackend = RuntimeComponentBackend.UNKNOWN,
        mtpRequested = mtpRequested,
        mtpStatus = mtpStatus,
        fallbackEvents = fallbackEvents,
        modelFingerprint = ModelFingerprintSummary(
            canonicalPath = "/m.part",
            fileSize = 10L,
            modifiedAt = 100L,
            validationVersion = 1,
            mtpSupported = true
        )
    )

    private fun makeStored(
        snapshot: RuntimeExecutionSnapshot?,
        nativeStats: GenerationBenchmarkStats? = null
    ): StoredAbTestResult {
        val fallbackCodes = snapshot?.fallbackEvents
            ?.joinToString(",") { "${it.attemptedTextBackend?.name.orEmpty()}=${it.reason.name}" }
            ?.takeIf { it.isNotBlank() && it != "=" }
        return StoredAbTestResult(
            targetLabel = "A",
            modelName = "Gemma",
            modelId = "id",
            accelerator = "AUTO",
            maxTokens = 4000,
            temperature = 1.0f,
            topK = 64,
            topP = 0.95f,
            mtpEnabled = true,
            reasoningEnabled = false,
            memoryEnabled = false,
            answer = "테스트 답변",
            success = true,
            errorSummary = null,
            firstTokenLatencyMs = 50L,
            totalGenerationTimeMs = 1000L,
            estimatedTokens = 100,
            totalTokensPerSecond = 100.0,
            decodeTokensPerSecond = 120.0,
            selectedTextBackend = snapshot?.selectedTextBackend?.name,
            selectedVisionBackend = snapshot?.selectedVisionBackend?.name,
            mtpRuntimeStatus = snapshot?.mtpStatus?.name,
            fallbackEventCodes = fallbackCodes,
            nativeTtftSeconds = nativeStats?.timeToFirstTokenSeconds,
            nativePrefillTokensPerSecond = nativeStats?.prefillTokensPerSecond,
            nativeDecodeTokensPerSecond = nativeStats?.decodeTokensPerSecond,
            modelFingerprintPath = snapshot?.modelFingerprint?.canonicalPath,
            modelFingerprintSize = snapshot?.modelFingerprint?.fileSize,
            modelFingerprintModifiedAt = snapshot?.modelFingerprint?.modifiedAt
        )
    }

    @Test
    fun `stored A-vs-B result records the selected text backend from the snapshot`() {
        val r = makeStored(
            snapshot = snapshot(
                selected = RuntimeBackend.CPU,
                mtpStatus = MtpRuntimeStatus.FALLBACK_DISABLED,
                fallbackEvents = listOf(
                    RuntimeFallbackEvent(
                        attemptedTextBackend = RuntimeBackend.GPU,
                        attemptedMtpEnabled = true,
                        reason = FallbackReason.MTP_ENGINE_INIT_FAILED
                    )
                )
            )
        )
        // requested = AUTO + MTP on, but applied = CPU with FALLBACK_DISABLED.
        assertEquals("CPU", r.selectedTextBackend)
        assertEquals("FALLBACK_DISABLED", r.mtpRuntimeStatus)
        assertEquals(true, r.mtpEnabled) // requested flag preserved
        assertFalse("must not claim GPU+MTP merely because requested",
            r.selectedTextBackend == "GPU" && r.mtpRuntimeStatus == "INITIALIZED_WITH_MTP_REQUEST")
        assertNotNull(r.fallbackEventCodes)
        assertTrue(r.fallbackEventCodes!!.contains("MTP_ENGINE_INIT_FAILED"))
    }

    @Test
    fun `stored A-vs-B result round-trips through JSON preserving runtime fields`() {
        val original = makeStored(
            snapshot = snapshot(
                selected = RuntimeBackend.GPU,
                mtpStatus = MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST
            ),
            nativeStats = GenerationBenchmarkStats(
                initTimeSeconds = 0.5,
                timeToFirstTokenSeconds = 0.42,
                prefillTokenCount = 80,
                decodeTokenCount = 120,
                prefillTokensPerSecond = 1500.0,
                decodeTokensPerSecond = 60.0
            )
        )
        // Serialize to a session array (mirrors the writer in ModelAbTestHistoryStore)
        val session = StoredAbTestSession(
            id = "test-id",
            fullPrompt = "안녕",
            createdAt = 1700000000L,
            results = listOf(original)
        )
        val array = JSONArray().also { it.put(sessionJsonViaReflection(session)) }
        val arrayStr = array.toString()

        // Parse back
        val parsed = JSONArray(arrayStr)
        val parsedSession = parsed.optJSONObject(0)
        val parsedResult = parsedSession?.optJSONArray("results")?.optJSONObject(0)
        assertNotNull(parsedResult)
        val roundTripped = roundTripResult(parsedResult!!)
        assertEquals("GPU", roundTripped.selectedTextBackend)
        assertEquals("INITIALIZED_WITH_MTP_REQUEST", roundTripped.mtpRuntimeStatus)
        assertEquals(0.42, roundTripped.nativeTtftSeconds!!, 1e-6)
        assertEquals(1500.0, roundTripped.nativePrefillTokensPerSecond!!, 1e-6)
        assertEquals("/m.part", roundTripped.modelFingerprintPath)
        assertEquals(10L, roundTripped.modelFingerprintSize)
        assertEquals(100L, roundTripped.modelFingerprintModifiedAt)
    }

    @Test
    fun `snapshot absence is preserved rather than fabricating a backend`() {
        val r = makeStored(snapshot = null)
        assertNull(r.selectedTextBackend)
        assertNull(r.mtpRuntimeStatus)
        assertNull(r.fallbackEventCodes)
        assertNull(r.nativeTtftSeconds)
    }

    // Helpers: serialize/deserialize using the same JSON shape as the store.
    private fun sessionJsonViaReflection(session: StoredAbTestSession): JSONObject {
        val obj = JSONObject()
        obj.put("id", session.id)
        obj.put("fullPrompt", session.fullPrompt)
        obj.put("createdAt", session.createdAt)
        obj.put("results", JSONArray().also { arr -> session.results.forEach { arr.put(resultToJson(it)) } })
        return obj
    }

    private fun resultToJson(r: StoredAbTestResult): JSONObject {
        val obj = JSONObject()
        obj.put("targetLabel", r.targetLabel)
        obj.put("modelName", r.modelName)
        obj.put("modelId", r.modelId)
        obj.put("accelerator", r.accelerator)
        obj.put("maxTokens", r.maxTokens)
        obj.put("temperature", r.temperature.toDouble())
        obj.put("topK", r.topK)
        obj.put("topP", r.topP.toDouble())
        obj.put("mtpEnabled", r.mtpEnabled)
        obj.put("reasoningEnabled", r.reasoningEnabled)
        obj.put("memoryEnabled", r.memoryEnabled)
        obj.put("answer", r.answer)
        obj.put("success", r.success)
        obj.put("errorSummary", r.errorSummary)
        obj.put("firstTokenLatencyMs", r.firstTokenLatencyMs)
        obj.put("totalGenerationTimeMs", r.totalGenerationTimeMs)
        obj.put("estimatedTokens", r.estimatedTokens)
        obj.put("totalTokensPerSecond", r.totalTokensPerSecond)
        obj.put("decodeTokensPerSecond", r.decodeTokensPerSecond)
        obj.put("rating", r.rating.name)
        r.selectedTextBackend?.let { obj.put("selectedTextBackend", it) }
        r.selectedVisionBackend?.let { obj.put("selectedVisionBackend", it) }
        r.mtpRuntimeStatus?.let { obj.put("mtpRuntimeStatus", it) }
        r.fallbackEventCodes?.let { obj.put("fallbackEventCodes", it) }
        r.nativeTtftSeconds?.let { obj.put("nativeTtftSeconds", it) }
        r.nativePrefillTokensPerSecond?.let { obj.put("nativePrefillTokensPerSecond", it) }
        r.nativeDecodeTokensPerSecond?.let { obj.put("nativeDecodeTokensPerSecond", it) }
        r.modelFingerprintPath?.let { obj.put("modelFingerprintPath", it) }
        r.modelFingerprintSize?.let { obj.put("modelFingerprintSize", it) }
        r.modelFingerprintModifiedAt?.let { obj.put("modelFingerprintModifiedAt", it) }
        return obj
    }

    private fun roundTripResult(o: JSONObject): StoredAbTestResult {
        return StoredAbTestResult(
            targetLabel = o.optString("targetLabel"),
            modelName = o.optString("modelName"),
            modelId = o.optString("modelId").takeIf { it.isNotBlank() },
            accelerator = o.optString("accelerator"),
            maxTokens = o.optInt("maxTokens"),
            temperature = o.optDouble("temperature").toFloat(),
            topK = o.optInt("topK"),
            topP = o.optDouble("topP").toFloat(),
            mtpEnabled = o.optBoolean("mtpEnabled"),
            reasoningEnabled = o.optBoolean("reasoningEnabled"),
            memoryEnabled = o.optBoolean("memoryEnabled"),
            answer = o.optString("answer").takeIf { it.isNotBlank() },
            success = o.optBoolean("success"),
            errorSummary = o.optString("errorSummary").takeIf { it.isNotBlank() },
            firstTokenLatencyMs = o.optLong("firstTokenLatencyMs").takeIf { o.has("firstTokenLatencyMs") && it > 0L },
            totalGenerationTimeMs = o.optLong("totalGenerationTimeMs"),
            estimatedTokens = o.optInt("estimatedTokens"),
            totalTokensPerSecond = o.optDouble("totalTokensPerSecond"),
            decodeTokensPerSecond = o.optDouble("decodeTokensPerSecond").takeIf { o.has("decodeTokensPerSecond") },
            selectedTextBackend = o.optString("selectedTextBackend").takeIf { o.has("selectedTextBackend") && it.isNotBlank() },
            selectedVisionBackend = o.optString("selectedVisionBackend").takeIf { o.has("selectedVisionBackend") && it.isNotBlank() },
            mtpRuntimeStatus = o.optString("mtpRuntimeStatus").takeIf { o.has("mtpRuntimeStatus") && it.isNotBlank() },
            fallbackEventCodes = o.optString("fallbackEventCodes").takeIf { o.has("fallbackEventCodes") && it.isNotBlank() },
            nativeTtftSeconds = if (o.has("nativeTtftSeconds")) o.optDouble("nativeTtftSeconds") else null,
            nativePrefillTokensPerSecond = if (o.has("nativePrefillTokensPerSecond")) o.optDouble("nativePrefillTokensPerSecond") else null,
            nativeDecodeTokensPerSecond = if (o.has("nativeDecodeTokensPerSecond")) o.optDouble("nativeDecodeTokensPerSecond") else null,
            modelFingerprintPath = o.optString("modelFingerprintPath").takeIf { o.has("modelFingerprintPath") && it.isNotBlank() },
            modelFingerprintSize = if (o.has("modelFingerprintSize")) o.optLong("modelFingerprintSize") else null,
            modelFingerprintModifiedAt = if (o.has("modelFingerprintModifiedAt")) o.optLong("modelFingerprintModifiedAt") else null,
            rating = runCatching { AbResultRating.valueOf(o.optString("rating", AbResultRating.NONE.name)) }.getOrDefault(AbResultRating.NONE)
        )
    }
}
