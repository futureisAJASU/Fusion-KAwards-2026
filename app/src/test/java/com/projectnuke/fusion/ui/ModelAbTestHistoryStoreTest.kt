package com.projectnuke.fusion.ui

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAbTestHistoryStoreTest {
    private class TestContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root
        override fun getApplicationContext(): Context = this
    }

    private fun historyFile(dir: File) = File(dir, "fusion_ab_test_history.json")

    private fun session(id: String, createdAt: Long = 1L): StoredAbTestSession =
        StoredAbTestSession(
            id = id,
            fullPrompt = "prompt $id",
            createdAt = createdAt,
            results = listOf(
                StoredAbTestResult(
                    targetLabel = "target",
                    modelName = "model",
                    modelId = null,
                    accelerator = "CPU",
                    maxTokens = 128,
                    temperature = 1.0f,
                    topK = 64,
                    topP = 0.95f,
                    mtpEnabled = false,
                    reasoningEnabled = false,
                    memoryEnabled = true,
                    answer = "answer",
                    success = true,
                    errorSummary = null,
                    firstTokenLatencyMs = 5L,
                    totalGenerationTimeMs = 10L,
                    estimatedTokens = 3,
                    totalTokensPerSecond = 30.0,
                    decodeTokensPerSecond = null,
                ),
            ),
        )

    private fun historicalResult(): JSONObject = JSONObject()
        .put("targetLabel", "legacy-target")
        .put("modelName", "legacy-model")
        .put("modelId", JSONObject.NULL)
        .put("accelerator", "CPU")
        .put("maxTokens", 128)
        .put("temperature", 0.5)
        .put("topK", 32)
        .put("topP", 0.8)
        .put("mtpEnabled", false)
        .put("reasoningEnabled", false)
        .put("memoryEnabled", true)
        .put("answer", "legacy-answer")
        .put("success", true)
        .put("errorSummary", JSONObject.NULL)
        .put("firstTokenLatencyMs", JSONObject.NULL)
        .put("totalGenerationTimeMs", 120)
        .put("estimatedTokens", 12)
        .put("totalTokensPerSecond", 100.0)
        .put("decodeTokensPerSecond", JSONObject.NULL)
        .put("rating", "NONE")

    private fun historicalSession(result: JSONObject = historicalResult()): String = JSONArray().put(
        JSONObject()
            .put("id", "legacy-session")
            .put("fullPrompt", "legacy prompt")
            .put("createdAt", 1234L)
            .put("results", JSONArray().put(result))
    ).toString()

    @Test
    fun `corrupt history renders empty and save quarantines before replacing`() = runTest {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val context = TestContext(dir)
        historyFile(dir).writeText("[{broken")
        assertTrue(ModelAbTestHistoryStore.load(context).isEmpty())

        assertTrue(ModelAbTestHistoryStore.save(context, session("fresh")))
        assertEquals("[{broken", File(dir, "fusion_ab_test_history.json.corrupt.bak").readText())
        assertEquals(listOf("fresh"), ModelAbTestHistoryStore.load(context).map { it.id })
        dir.deleteRecursively()
    }

    @Test
    fun `delete and rating update fail closed on corrupt history`() = runTest {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val context = TestContext(dir)
        historyFile(dir).writeText("[{broken")

        assertFalse(ModelAbTestHistoryStore.delete(context, "any"))
        assertFalse(ModelAbTestHistoryStore.updateRating(context, "any", "target", AbResultRating.PREFERRED))
        assertEquals("[{broken", historyFile(dir).readText())
        dir.deleteRecursively()
    }

    @Test
    fun `explicit clear removes corrupt history and its quarantine`() = runTest {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val context = TestContext(dir)
        val marker = "private-prompt-${System.nanoTime()}-private-answer"
        historyFile(dir).writeText("[{\"prompt\":\"$marker\"}")
        val quarantine = File(dir, "fusion_ab_test_history.json.corrupt.bak")
        quarantine.writeText("older-$marker")

        assertTrue(ModelAbTestHistoryStore.clear(context))
        assertFalse(historyFile(dir).exists())
        assertFalse(quarantine.exists())
        assertFalse(dir.listFiles().orEmpty().any { it.readText().contains(marker) })
        dir.deleteRecursively()
    }

    @Test
    fun `one malformed session among valid sessions corrupts the whole file`() = runTest {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val context = TestContext(dir)
        val goodSessionJson = """{"id":"good","fullPrompt":"p","createdAt":1,"results":[{"targetLabel":"target"}]}"""
        val array = JSONArray()
            .put(JSONObject(goodSessionJson))
            .put(JSONObject().put("id", "").put("fullPrompt", "no id").put("createdAt", 1L).put("results", JSONArray()))
        historyFile(dir).writeText(array.toString())

        // R5: a malformed top-level session produced outside the writer
        // contract must not be silently dropped while the file reads Valid.
        assertTrue(ModelAbTestHistoryStore.load(context).isEmpty())
        assertTrue(ModelAbTestHistoryStore.loadResult(historyFile(dir)) is AbHistoryLoad.Corrupt)
        // Mutations fail closed against the malformed artifact.
        assertFalse(ModelAbTestHistoryStore.delete(context, "good"))
        assertFalse(ModelAbTestHistoryStore.updateRating(context, "good", "target", AbResultRating.PREFERRED))
        dir.deleteRecursively()
    }

    @Test
    fun `one malformed result inside a session corrupts the whole file`() = runTest {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val context = TestContext(dir)
        val malformedResult = JSONObject()
            .put("targetLabel", "") // required label missing: out-of-writer-contract
            .put("modelName", "model")
            .put("accelerator", "CPU")
            .put("maxTokens", 128)
            .put("temperature", 1.0)
            .put("topK", 64)
            .put("topP", 0.95)
            .put("success", true)
            .put("totalGenerationTimeMs", 10L)
            .put("estimatedTokens", 3)
            .put("totalTokensPerSecond", 30.0)
        val array = JSONArray().put(
            JSONObject()
                .put("id", "s1")
                .put("fullPrompt", "p")
                .put("createdAt", 1L)
                .put("results", JSONArray().put(JSONObject("""{"targetLabel":"target"}""")).put(malformedResult)),
        )
        historyFile(dir).writeText(array.toString())

        assertTrue(ModelAbTestHistoryStore.load(context).isEmpty())
        assertTrue(ModelAbTestHistoryStore.loadResult(historyFile(dir)) is AbHistoryLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `save after malformed corruption quarantines before replacing`() = runTest {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val context = TestContext(dir)
        historyFile(dir).writeText("""[{"id":"","fullPrompt":"broken","createdAt":1,"results":[]}]""")

        assertTrue(ModelAbTestHistoryStore.save(context, session("fresh")))
        assertEquals(
            """[{"id":"","fullPrompt":"broken","createdAt":1,"results":[]}]""",
            File(dir, "fusion_ab_test_history.json.corrupt.bak").readText(),
        )
        assertEquals(listOf("fresh"), ModelAbTestHistoryStore.load(context).map { it.id })
        dir.deleteRecursively()
    }

    @Test
    fun `present but non-array results field is corrupt`() = runTest {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val context = TestContext(dir)
        historyFile(dir).writeText("""[{"id":"s1","fullPrompt":"p","createdAt":1,"results":"nope"}]""")
        assertTrue(ModelAbTestHistoryStore.loadResult(historyFile(dir)) is AbHistoryLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `valid round trip keeps intentional session bound`() = runTest {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val context = TestContext(dir)
        repeat(21) { i ->
            assertTrue(ModelAbTestHistoryStore.save(context, session("s$i", createdAt = i.toLong())))
        }
        val loaded = ModelAbTestHistoryStore.load(context)
        assertEquals(20, loaded.size)
        assertTrue(loaded.none { it.id == "s0" })
        assertTrue(loaded.any { it.id == "s20" })
        dir.deleteRecursively()
    }

    @Test
    fun `over-cap file is corrupt, not truncated`() = runTest {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val context = TestContext(dir)
        val array = JSONArray()
        repeat(21) { i ->
            array.put(JSONObject().put("id", "s$i").put("fullPrompt", "p").put("createdAt", i).put("results", JSONArray()))
        }
        historyFile(dir).writeText(array.toString())
        assertTrue(ModelAbTestHistoryStore.load(context).isEmpty())
        // A delete must not silently complete the over-cap artifact either.
        assertFalse(ModelAbTestHistoryStore.delete(context, "s0"))
        dir.deleteRecursively()
    }

    @Test
    fun `over-cap session result history is corrupt, not truncated`() = runTest {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val context = TestContext(dir)
        val results = JSONArray()
        repeat(ModelAbTestHistoryStore.MaxResultsPerSession + 1) { results.put(JSONObject().put("targetLabel", "target-$it")) }
        historyFile(dir).writeText(
            JSONArray().put(
                JSONObject()
                    .put("id", "s1")
                    .put("fullPrompt", "p")
                    .put("createdAt", 1L)
                    .put("results", results),
            ).toString(),
        )
        assertTrue(ModelAbTestHistoryStore.load(context).isEmpty())
        assertTrue(ModelAbTestHistoryStore.loadResult(historyFile(dir)) is AbHistoryLoad.Corrupt)
        assertFalse(ModelAbTestHistoryStore.delete(context, "s1"))
        dir.deleteRecursively()
    }

    @Test
    fun `current writer round trip preserves the complete result snapshot`() = runTest {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val context = TestContext(dir)
        val base = session("round-trip")
        val expected = base.copy(
            results = listOf(
                base.results.single().copy(
                    modelId = "model-id",
                    selectedTextBackend = "CPU",
                    selectedVisionBackend = "NONE",
                    mtpRuntimeStatus = "DISABLED",
                    fallbackEventCodes = "[]",
                    nativeTtftSeconds = 0.5,
                    nativePrefillTokensPerSecond = 12.0,
                    nativeDecodeTokensPerSecond = 10.0,
                    modelFingerprintPath = "/models/model.bin",
                    modelFingerprintSize = 100L,
                    modelFingerprintModifiedAt = 200L,
                    modelFingerprintValidationVersion = 3,
                    modelFingerprintMtpSupported = false,
                    generationFailureKind = null,
                    rating = AbResultRating.PREFERRED,
                )
            )
        )
        assertTrue(ModelAbTestHistoryStore.save(context, expected))
        assertEquals(expected, ModelAbTestHistoryStore.load(context).single())
        dir.deleteRecursively()
    }

    @Test
    fun `modern bounded fields round trip at their declared maximum`() = runTest {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val context = TestContext(dir)
        val expected = session("bounded").copy(results = listOf(session("bounded").results.single().copy(
            selectedTextBackend = "t".repeat(ModelAbTestHistoryStore.MaxSelectedTextBackendChars),
            selectedVisionBackend = "v".repeat(ModelAbTestHistoryStore.MaxSelectedVisionBackendChars),
            mtpRuntimeStatus = "m".repeat(ModelAbTestHistoryStore.MaxMtpRuntimeStatusChars),
            fallbackEventCodes = "f".repeat(ModelAbTestHistoryStore.MaxFallbackEventCodesChars),
            modelFingerprintPath = "p".repeat(ModelAbTestHistoryStore.MaxModelFingerprintPathChars),
            generationFailureKind = "g".repeat(ModelAbTestHistoryStore.MaxGenerationFailureKindChars),
        )))

        assertTrue(ModelAbTestHistoryStore.save(context, expected))
        assertEquals(expected, ModelAbTestHistoryStore.load(context).single())
        assertTrue(historyFile(dir).length() <= ModelAbTestHistoryStore.MAX_HISTORY_FILE_BYTES)
        dir.deleteRecursively()
    }

    @Test
    fun `writer canonicalizes overlong modern fields and reader rejects external overlong fields`() = runTest {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val context = TestContext(dir)
        val overlong = "x".repeat(ModelAbTestHistoryStore.MaxGenerationFailureKindChars + 1)
        val input = session("truncate").copy(results = listOf(session("truncate").results.single().copy(
            generationFailureKind = overlong,
        )))
        assertTrue(ModelAbTestHistoryStore.save(context, input))
        assertEquals(ModelAbTestHistoryStore.MaxGenerationFailureKindChars,
            ModelAbTestHistoryStore.load(context).single().results.single().generationFailureKind!!.length)

        historyFile(dir).writeText(historicalSession(historicalResult().put("generationFailureKind", overlong)))
        assertTrue(ModelAbTestHistoryStore.loadResult(historyFile(dir)) is AbHistoryLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `bounded loader reports oversized without materializing a large fixture`() {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val file = historyFile(dir).apply { writeText("[]") }
        assertTrue(ModelAbTestHistoryStore.loadResult(file, maxBytes = 1) is AbHistoryLoad.Oversized)
        assertTrue(ModelAbTestHistoryStore.maximumLegalEncodedBytes() < ModelAbTestHistoryStore.MAX_HISTORY_FILE_BYTES)
        dir.deleteRecursively()
    }

    @Test
    fun `historical fixture preserves nullable fields and omits modern snapshot fields`() = runTest {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val context = TestContext(dir)
        historyFile(dir).writeText(historicalSession())

        val loaded = ModelAbTestHistoryStore.load(context).single()
        val result = loaded.results.single()
        assertEquals("legacy-session", loaded.id)
        assertEquals(1234L, loaded.createdAt)
        assertEquals(128, result.maxTokens)
        assertEquals(0.5f, result.temperature)
        assertEquals(32, result.topK)
        assertEquals(0.8f, result.topP)
        assertNull(result.modelId)
        assertNull(result.firstTokenLatencyMs)
        assertNull(result.decodeTokensPerSecond)
        assertNull(result.selectedTextBackend)
        assertEquals(AbResultRating.NONE, result.rating)
        dir.deleteRecursively()
    }

    @Test
    fun `absent legacy result fields keep old zero defaults`() = runTest {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val context = TestContext(dir)
        val sparseResult = JSONObject().put("targetLabel", "sparse")
        historyFile(dir).writeText(historicalSession(sparseResult))

        val result = ModelAbTestHistoryStore.load(context).single().results.single()
        assertEquals(0, result.maxTokens)
        assertEquals(0.0f, result.temperature)
        assertEquals(0, result.topK)
        assertEquals(0.0f, result.topP)
        assertFalse(result.mtpEnabled)
        assertFalse(result.success)
        assertEquals(0L, result.totalGenerationTimeMs)
        assertEquals(0.0, result.totalTokensPerSecond, 0.0)
        assertEquals(AbResultRating.NONE, result.rating)
        dir.deleteRecursively()
    }

    @Test
    fun `present wrong primitive type makes the whole history corrupt`() = runTest {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val context = TestContext(dir)
        historyFile(dir).writeText(historicalSession(historicalResult().put("maxTokens", "128")))
        assertTrue(ModelAbTestHistoryStore.loadResult(historyFile(dir)) is AbHistoryLoad.Corrupt)

        val badSession = JSONObject()
            .put("id", "legacy-session")
            .put("fullPrompt", 123)
            .put("createdAt", 1234L)
            .put("results", JSONArray())
        historyFile(dir).writeText(JSONArray().put(badSession).toString())
        assertTrue(ModelAbTestHistoryStore.loadResult(historyFile(dir)) is AbHistoryLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `invalid rating enum makes the whole history corrupt`() = runTest {
        val dir = Files.createTempDirectory("fusion-ab-history").toFile()
        val context = TestContext(dir)
        historyFile(dir).writeText(historicalSession(historicalResult().put("rating", "MAYBE")))
        assertTrue(ModelAbTestHistoryStore.loadResult(historyFile(dir)) is AbHistoryLoad.Corrupt)
        assertTrue(ModelAbTestHistoryStore.load(context).isEmpty())
        dir.deleteRecursively()
    }
}
