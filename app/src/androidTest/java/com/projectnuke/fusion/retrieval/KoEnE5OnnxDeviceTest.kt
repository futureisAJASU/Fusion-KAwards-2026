package com.projectnuke.fusion.retrieval

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof for the S1 Ko/En backbone (runs under connectedAndroidTest
 * only; never on the JVM host). Uses the packaged APK assets end to end:
 * provision -> hash verify -> path-based ORT session -> real inference.
 *
 * Ranking set was pre-verified on the host with the same pinned INT8 model
 * (positive 0.9118 vs best negative 0.8108, margin ~0.10, robust across
 * INT8 CPU backends).
 */
@RunWith(AndroidJUnit4::class)
class KoEnE5OnnxDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var privateDir: File

    @Before
    fun setUp() {
        privateDir = File(context.filesDir, "koen-e5")
    }

    @After
    fun tearDown() {
        KoEnE5RetrievalOwner.close()
    }

    private fun memSnapshot(): JSONObject {
        val runtime = Runtime.getRuntime()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return JSONObject()
            .put("managedUsedMB", (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576L)
            .put("nativeHeapMB", Debug.getNativeHeapAllocatedSize() / 1_048_576L)
            .put("availMemMB", memInfo.availMem / 1_048_576L)
            .put("lowMemory", memInfo.lowMemory)
    }

    @Test
    fun `provision reuses verified copy`() {
        val first = KoEnE5AssetProvisioner.provision(context.assets, privateDir)
        assertTrue(first is KoEnE5AssetProvisioner.ProvisionResult.Ready)
        val files = (first as KoEnE5AssetProvisioner.ProvisionResult.Ready).files
        assertEquals(KoEnE5ModelPolicy.MODEL_QINT8_ARM64_SIZE_BYTES, files.modelFile.length())
        assertEquals(KoEnE5ModelPolicy.TOKENIZER_JSON_SIZE_BYTES, files.tokenizerFile.length())
        val modelStamp = files.modelFile.lastModified()
        val second = KoEnE5AssetProvisioner.provision(context.assets, privateDir)
        assertTrue(second is KoEnE5AssetProvisioner.ProvisionResult.Ready)
        // Valid copy reused untouched (no rewrite).
        assertEquals(modelStamp, files.modelFile.lastModified())
    }

    @Test
    fun `real model loads and embeds a Korean query`() {
        val ready = KoEnE5AssetProvisioner.provision(context.assets, privateDir)
        assertTrue(ready is KoEnE5AssetProvisioner.ProvisionResult.Ready)
        val files = (ready as KoEnE5AssetProvisioner.ProvisionResult.Ready).files
        val engine = KoEnE5OnnxEmbeddingEngine(VerifiedFileKoEnE5AssetProvider(files.modelFile, files.tokenizerFile))
        try {
            val result = runBlocking {
                engine.embed(listOf(E5InputContract.query("세종은 1443년에 한글을 창제했다")))
            }
            assertTrue(result is EmbeddingResult.Success)
            val vectors = (result as EmbeddingResult.Success).vectors
            assertEquals(1, vectors.size)
            assertEquals(KoEnE5ModelPolicy.EMBEDDING_DIM, vectors.single().size)
            assertTrue(vectors.single().all { it.isFinite() })
            val norm = SemanticSimilarity.l2Norm(vectors.single())
            assertTrue(norm > 0.999 && norm < 1.001)
            assertTrue(engine.diagnostics.isLoaded)
            assertNotNull(engine.diagnostics.loadLatencyMs)
        } finally {
            engine.close()
            engine.close() // idempotent
            assertTrue(engine.isClosed)
        }
        val afterClose = runBlocking { engine.embed(listOf("hello")) }
        assertTrue(afterClose is EmbeddingResult.Failure)
        assertEquals(
            EmbeddingFailureReason.CLOSED,
            (afterClose as EmbeddingResult.Failure).reason,
        )
    }

    @Test
    fun `real ranking puts the paraphrase first and repeats deterministically`() {
        val ready = KoEnE5AssetProvisioner.provision(context.assets, privateDir)
        assertTrue(ready is KoEnE5AssetProvisioner.ProvisionResult.Ready)
        val files = (ready as KoEnE5AssetProvisioner.ProvisionResult.Ready).files
        val engine = KoEnE5OnnxEmbeddingEngine(VerifiedFileKoEnE5AssetProvider(files.modelFile, files.tokenizerFile))
        try {
            val matcher = EmbeddingSemanticCandidateMatcher(engine)
            val claim = "세종은 1443년에 한글을 창제했다"
            val segments = listOf(
                SemanticSegment("POS", "1443년 세종에 의해 한글이 만들어졌다"),
                SemanticSegment("N1", "세종은 1443년에 측우기를 발명했다"),
                SemanticSegment("N2", "태종은 1443년에 한글을 창제했다"),
                SemanticSegment("N3", "세종은 1446년에 한글을 반포했다"),
                SemanticSegment("N4", "장영실은 1443년에 측우기를 발명했다"),
            )
            val first = runBlocking { matcher.rank(claim, segments, 5) }
            assertEquals(null, first.unavailableReason)
            assertEquals("POS", first.matches.first().segmentId)
            // Scores are ranking signals only; assert order, never a threshold.
            assertTrue(first.matches.first().score > first.matches[1].score)
            val second = runBlocking { matcher.rank(claim, segments, 5) }
            assertEquals(
                first.matches.map { it.segmentId },
                second.matches.map { it.segmentId },
            )
            // Representative batch + repeated inference through one session.
            val batch = runBlocking {
                engine.embed(segments.map { E5InputContract.passage(it.text) })
            }
            assertTrue(batch is EmbeddingResult.Success)
            assertEquals(5, (batch as EmbeddingResult.Success).vectors.size)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `owner proxies share one engine and concurrent inference stays healthy`() {
        val first = KoEnE5RetrievalOwner.proxy(context.assets, privateDir)
        val second = KoEnE5RetrievalOwner.proxy(context.assets, privateDir)
        val results = runBlocking {
            (1..4).map {
                async {
                    first.rank(
                        "세종은 1443년에 한글을 창제했다",
                        listOf(
                            SemanticSegment("POS", "1443년 세종에 의해 한글이 만들어졌다"),
                            SemanticSegment("N1", "세종은 1443년에 측우기를 발명했다"),
                        ),
                        2,
                    )
                }
            }.awaitAll()
        }
        for (result in results) {
            assertEquals(null, result.unavailableReason)
            assertEquals("POS", result.matches.first().segmentId)
        }
        // The second proxy resolves the same shared engine: identical order.
        val check = runBlocking {
            second.rank(
                "세종은 1443년에 한글을 창제했다",
                listOf(
                    SemanticSegment("POS", "1443년 세종에 의해 한글이 만들어졌다"),
                    SemanticSegment("N1", "세종은 1443년에 측우기를 발명했다"),
                ),
                2,
            )
        }
        assertEquals("POS", check.matches.first().segmentId)
    }

    @Test
    fun `proxy construction provisions nothing and first rank loads then close releases and rank recreates`() {
        val lazyDir = File(privateDir, "lazy-probe")
        // Fresh dir: construction alone must not copy, hash, or load anything.
        val proxy = KoEnE5RetrievalOwner.proxy(context.assets, lazyDir)
        assertTrue(!lazyDir.exists() || (lazyDir.listFiles()?.isEmpty() ?: true))
        val memBefore = Debug.getNativeHeapAllocatedSize()

        val claim = "세종은 1443년에 한글을 창제했다"
        val segments = listOf(
            SemanticSegment("POS", "1443년 세종에 의해 한글이 만들어졌다"),
            SemanticSegment("N1", "세종은 1443년에 측우기를 발명했다"),
        )
        // First actual use provisions + loads lazily and succeeds.
        val first = runBlocking { proxy.rank(claim, segments, 2) }
        assertEquals(null, first.unavailableReason)
        assertEquals("POS", first.matches.first().segmentId)
        assertTrue(lazyDir.resolve("model_qint8_arm64.onnx").isFile)
        val memLoaded = Debug.getNativeHeapAllocatedSize()
        assertTrue(memLoaded > memBefore)

        // Close releases native memory; the stable proxy stays usable.
        KoEnE5RetrievalOwner.close()
        KoEnE5RetrievalOwner.close() // idempotent
        // Allow the allocator a beat, then require a real drop (was 104->12 MB).
        Thread.sleep(500)
        val memAfterClose = Debug.getNativeHeapAllocatedSize()
        assertTrue(
            "close must release native allocation (loaded=$memLoaded afterClose=$memAfterClose)",
            memAfterClose < memLoaded,
        )

        // Rank after close transparently provisions/recreates and succeeds.
        val recreated = runBlocking { proxy.rank(claim, segments, 2) }
        assertEquals(null, recreated.unavailableReason)
        assertEquals("POS", recreated.matches.first().segmentId)
        Log.i(
            "KoEnE5Device",
            "lazy memBefore=$memBefore memLoaded=$memLoaded memAfterClose=$memAfterClose",
        )
    }

    @Test
    fun `device metrics are recorded`() {
        val metrics = JSONObject()
        metrics.put("device", android.os.Build.MODEL)
        metrics.put("androidRelease", android.os.Build.VERSION.RELEASE)
        metrics.put("sdk", android.os.Build.VERSION.SDK_INT)
        metrics.put("abi", android.os.Build.SUPPORTED_ABIS.toList())
        metrics.put("modelBytes", KoEnE5ModelPolicy.MODEL_QINT8_ARM64_SIZE_BYTES)
        metrics.put("tokenizerBytes", KoEnE5ModelPolicy.TOKENIZER_JSON_SIZE_BYTES)
        metrics.put("memBeforeLoad", memSnapshot())

        val ready = KoEnE5AssetProvisioner.provision(context.assets, privateDir)
        assertTrue(ready is KoEnE5AssetProvisioner.ProvisionResult.Ready)
        val files = (ready as KoEnE5AssetProvisioner.ProvisionResult.Ready).files
        val engine = KoEnE5OnnxEmbeddingEngine(VerifiedFileKoEnE5AssetProvider(files.modelFile, files.tokenizerFile))
        try {
            val query = E5InputContract.query("세종은 1443년에 한글을 창제했다")
            val batch = (1..8).map { E5InputContract.passage("1443년 세종에 의해 한글이 만들어졌다 ($it)") }
            var loadMs = -1L
            var queryMs = -1L
            var batchMs = -1L
            runBlocking {
                var start = System.nanoTime()
                val warm = engine.embed(listOf(query))
                assertTrue(warm is EmbeddingResult.Success)
                loadMs = (System.nanoTime() - start) / 1_000_000L
                metrics.put("memAfterLoad", memSnapshot())

                start = System.nanoTime()
                repeat(3) { engine.embed(listOf(query)) }
                val peak = memSnapshot()
                queryMs = (System.nanoTime() - start) / 3_000_000L
                metrics.put("memPeakInference", peak)

                start = System.nanoTime()
                val batchResult = engine.embed(batch)
                assertTrue(batchResult is EmbeddingResult.Success)
                batchMs = (System.nanoTime() - start) / 1_000_000L
            }
            metrics.put("loadLatencyMs", loadMs)
            metrics.put("loadLatencyPureMs", engine.diagnostics.loadLatencyMs)
            metrics.put("koreanQueryLatencyMs", queryMs)
            metrics.put("batch8LatencyMs", batchMs)
            metrics.put("memAfterInference", memSnapshot())
        } finally {
            engine.close()
        }
        metrics.put("memAfterClose", memSnapshot())
        File(privateDir, "koen-e5-device-metrics.json")
            .writeText(metrics.toString(1))
        Log.i("KoEnE5Device", metrics.toString())
        println("KOEN_E5_DEVICE_METRICS=" + metrics.toString())
    }
}
