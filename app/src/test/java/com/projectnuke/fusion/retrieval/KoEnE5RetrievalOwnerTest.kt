package com.projectnuke.fusion.retrieval

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lazy proxy + release/recreate lifecycle of the production owner (fakes). */
class KoEnE5RetrievalOwnerTest {

    private val provisionCalls = AtomicInteger(0)
    private val createdEngines = AtomicInteger(0)
    private var lastThreadName: String? = null

    @After
    fun reset() {
        KoEnE5RetrievalOwner.resetForTests()
    }

    private fun installFakeProvision() {
        provisionCalls.set(0)
        KoEnE5RetrievalOwner.provision = { _, _ ->
            provisionCalls.incrementAndGet()
            lastThreadName = Thread.currentThread().name
            KoEnE5AssetProvisioner.ProvisionResult.Ready(
                KoEnE5AssetProvisioner.Provisioned(
                    File(System.getProperty("java.io.tmpdir"), "m"),
                    File(System.getProperty("java.io.tmpdir"), "t"),
                ),
            )
        }
    }

    private fun installFakeFactory(vectors: Map<String, FloatArray>) {
        createdEngines.set(0)
        KoEnE5RetrievalOwner.engineFactory = { _ ->
            createdEngines.incrementAndGet()
            FakeEmbeddingEngine(vectors)
        }
    }

    private fun vectors() = mapOf(
        E5InputContract.query("c") to floatArrayOf(1f, 0f, 0f, 0f),
        E5InputContract.passage("s1") to floatArrayOf(1f, 0f, 0f, 0f),
        E5InputContract.passage("s2") to floatArrayOf(0f, 1f, 0f, 0f),
    )

    private fun segments() = listOf(
        SemanticSegment("S1", "s1"),
        SemanticSegment("S2", "s2"),
    )

    @Test
    fun `constructing the production proxy performs no provisioning`() {
        installFakeProvision()
        installFakeFactory(vectors())
        val dir = File(System.getProperty("java.io.tmpdir"))
        KoEnE5RetrievalOwner.proxy(null, dir)
        KoEnE5RetrievalOwner.symmetricProxy(null, dir)
        assertEquals(0, provisionCalls.get())
        assertEquals(0, createdEngines.get())
    }

    @Test
    fun `first rank provisions lazily off the calling thread and succeeds`() = runTest {
        installFakeProvision()
        installFakeFactory(vectors())
        val proxy = KoEnE5RetrievalOwner.proxy(null, File(System.getProperty("java.io.tmpdir")))
        val result = proxy.rank("c", segments(), 2)
        assertEquals(null, result.unavailableReason)
        assertEquals(listOf("S1", "S2"), result.matches.map { it.segmentId })
        assertEquals(1, provisionCalls.get())
        assertEquals(1, createdEngines.get())
        assertTrue(
            "heavy IO must not run on the test caller thread: $lastThreadName",
            lastThreadName != Thread.currentThread().name,
        )
    }

    @Test
    fun `symmetric source embedding uses query feature prefix and reuses the same owner engine`() = runTest {
        installFakeProvision()
        installFakeFactory(vectors() + (E5InputContract.query("source") to floatArrayOf(0f, 0f, 1f, 0f)))
        val dir = File(System.getProperty("java.io.tmpdir"))
        val ranked = KoEnE5RetrievalOwner.proxy(null, dir).rank("c", segments(), 2)
        val embedded = KoEnE5RetrievalOwner.symmetricProxy(null, dir).embedSources(listOf("source"))

        assertEquals(null, ranked.unavailableReason)
        assertEquals(listOf(0f, 0f, 1f, 0f), (embedded as EmbeddingResult.Success).vectors.single().toList())
        assertEquals(1, provisionCalls.get())
        assertEquals(1, createdEngines.get())
    }

    @Test
    fun `null assets fall back to rule-only without creating an engine`() = runTest {
        // Production provision seam untouched: null assets fail closed.
        val proxy = KoEnE5RetrievalOwner.proxy(null, File(System.getProperty("java.io.tmpdir")))
        val result = proxy.rank("c", segments(), 2)
        assertEquals(EmbeddingFailureReason.ASSET_MISSING, result.unavailableReason)
        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun `close releases and rank after close recreates and succeeds`() = runTest {
        installFakeProvision()
        installFakeFactory(vectors())
        val dir = File(System.getProperty("java.io.tmpdir"))
        val proxy = KoEnE5RetrievalOwner.proxy(null, dir)
        val first = proxy.rank("c", segments(), 2)
        assertEquals(null, first.unavailableReason)
        KoEnE5RetrievalOwner.close()
        KoEnE5RetrievalOwner.close() // idempotent
        val second = proxy.rank("c", segments(), 2)
        assertEquals(null, second.unavailableReason)
        assertEquals(listOf("S1", "S2"), second.matches.map { it.segmentId })
        assertEquals(2, createdEngines.get())
    }

    @Test
    fun `concurrent ranks create exactly one engine`() = runTest {
        installFakeProvision()
        installFakeFactory(vectors())
        val proxy = KoEnE5RetrievalOwner.proxy(null, File(System.getProperty("java.io.tmpdir")))
        val results = (1..16).map {
            async { proxy.rank("c", segments(), 2) }
        }.awaitAll()
        for (result in results) {
            assertEquals(null, result.unavailableReason)
            assertEquals(listOf("S1", "S2"), result.matches.map { it.segmentId })
        }
        assertEquals(1, createdEngines.get())
        assertEquals(1, provisionCalls.get())
    }

    @Test
    fun `close after rank allows future inference`() = runTest {
        installFakeProvision()
        installFakeFactory(vectors())
        val proxy = KoEnE5RetrievalOwner.proxy(null, File(System.getProperty("java.io.tmpdir")))
        // First rank succeeds
        val first = proxy.rank("c", segments(), 2)
        assertEquals(null, first.unavailableReason)
        assertEquals(listOf("S1", "S2"), first.matches.map { it.segmentId })
        // Close the engine
        KoEnE5RetrievalOwner.close()
        // Future inference recreates cleanly
        val second = proxy.rank("c", segments(), 2)
        assertEquals(null, second.unavailableReason)
        assertEquals(listOf("S1", "S2"), second.matches.map { it.segmentId })
        assertEquals(2, createdEngines.get())
    }

    @Test
    fun `close during engine creation discards new engine and returns closed`() = runTest {
        val creationLatch = CountDownLatch(1)
        var engineClosed = false
        installFakeProvision()
        KoEnE5RetrievalOwner.engineFactory = { _ ->
            createdEngines.incrementAndGet()
            // Delay engine creation to allow close() to race
            creationLatch.await()
            object : FusionEmbeddingEngine {
                private var closed = false
                override val diagnostics: EmbeddingDiagnostics
                    get() = EmbeddingDiagnostics(0, 0L, null, false, null)
                override val isClosed: Boolean get() = closed

                override suspend fun embed(texts: List<String>): EmbeddingResult =
                    EmbeddingResult.Success(texts.map { floatArrayOf(1f, 0f) }, 2)

                override fun close() {
                    closed = true
                    engineClosed = true
                }
            }
        }
        val proxy = KoEnE5RetrievalOwner.proxy(null, File(System.getProperty("java.io.tmpdir")))
        val inFlight = async { proxy.rank("c", segments(), 2) }
        // Let creation start but not complete, then close.
        kotlinx.coroutines.delay(10)
        KoEnE5RetrievalOwner.close()
        // Now allow creation to complete - it should be discarded.
        creationLatch.countDown()
        val raced = inFlight.await()
        assertEquals(EmbeddingFailureReason.CLOSED, raced.unavailableReason)
        assertTrue(engineClosed) // engine was closed by generation fence
        // createdEngines will be 1 (factory was called) but engine was discarded
        // Future inference recreates cleanly.
        installFakeFactory(vectors())
        val after = proxy.rank("c", segments(), 2)
        assertEquals(null, after.unavailableReason)
        assertEquals(listOf("S1", "S2"), after.matches.map { it.segmentId })
    }

    @Test
    fun `semantic Top-K budget never exceeded and lexical never removed`() = runTest {
        val engine = FakeEmbeddingEngine(
            mapOf(E5InputContract.query("c") to floatArrayOf(1f, 0f, 0f, 0f)) +
                (1..8).associate { E5InputContract.passage("p$it") to floatArrayOf(1f, 0f, 0f, 0f) },
        )
        val matcher = EmbeddingSemanticCandidateMatcher(engine)
        val lexical = listOf("E1", "E2")
        val ranked = matcher.rank(
            "c",
            (1..8).map { SemanticSegment("S$it", "p$it") },
            SemanticEvidenceCandidates.MAX_SEMANTIC_CANDIDATES_PER_CLAIM,
        )
        assertEquals(
            SemanticEvidenceCandidates.MAX_SEMANTIC_CANDIDATES_PER_CLAIM,
            ranked.matches.size,
        )
        val expanded = SemanticEvidenceCandidates.expand(lexical, ranked)
        assertEquals(listOf("E1", "E2", "S1", "S2", "S3"), expanded)
    }

    @Test
    fun `low-ranked source outside Top-K is not added`() = runTest {
        val engine = FakeEmbeddingEngine(
            mapOf(
                E5InputContract.query("c") to floatArrayOf(1f, 0f, 0f, 0f),
                E5InputContract.passage("near") to floatArrayOf(1f, 0f, 0f, 0f),
                E5InputContract.passage("far") to floatArrayOf(0f, 1f, 0f, 0f),
            ),
        )
        val matcher = EmbeddingSemanticCandidateMatcher(engine)
        val ranked = matcher.rank(
            "c",
            listOf(
                SemanticSegment("NEAR", "near"),
                SemanticSegment("FAR", "far"),
            ),
            limit = 1,
        )
        assertEquals(listOf("NEAR"), ranked.matches.map { it.segmentId })
        assertEquals(
            listOf("E1", "NEAR"),
            SemanticEvidenceCandidates.expand(listOf("E1"), ranked),
        )
    }

    @Test
    fun `recoverable factory failure maps to model load failure not asset missing`() = runTest {
        installFakeProvision()
        KoEnE5RetrievalOwner.engineFactory = { _ -> throw IllegalStateException("native init failed") }
        val proxy = KoEnE5RetrievalOwner.proxy(null, File(System.getProperty("java.io.tmpdir")))
        val result = proxy.rank("c", segments(), 2)
        assertEquals(EmbeddingFailureReason.MODEL_LOAD_FAILURE, result.unavailableReason)
        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun `fatal factory error propagates instead of asset missing`() = runTest {
        installFakeProvision()
        KoEnE5RetrievalOwner.engineFactory = { _ -> throw OutOfMemoryError("native heap exhausted") }
        val proxy = KoEnE5RetrievalOwner.proxy(null, File(System.getProperty("java.io.tmpdir")))
        try {
            proxy.rank("c", segments(), 2)
            org.junit.Assert.fail("expected fatal Error")
        } catch (error: OutOfMemoryError) {
            // Coroutine stack-trace recovery may copy the instance across
            // the IO dispatch boundary, so identity is class + message:
            // the fatal must never surface as ASSET_MISSING/unavailable.
            assertEquals("native heap exhausted", error.message)
        }
    }

    @Test
    fun `factory cancellation propagates instead of unavailable`() = runTest {
        installFakeProvision()
        KoEnE5RetrievalOwner.engineFactory = { _ ->
            throw kotlinx.coroutines.CancellationException("factory cancelled")
        }
        val proxy = KoEnE5RetrievalOwner.proxy(null, File(System.getProperty("java.io.tmpdir")))
        try {
            proxy.rank("c", segments(), 2)
            org.junit.Assert.fail("expected CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
    }

    @Test
    fun `fatal error from native close propagates and owner reference is cleared`() = runTest {
        installFakeProvision()
        val expected = AssertionError("native close invariant")
        var firstClose = true
        KoEnE5RetrievalOwner.engineFactory = { _ ->
            object : FusionEmbeddingEngine {
                private var closed = false
                override val diagnostics = EmbeddingDiagnostics(0, 0L, null, false, null)
                override val isClosed: Boolean get() = closed
                override suspend fun embed(texts: List<String>) =
                    EmbeddingResult.Success(texts.map { floatArrayOf(1f, 0f) }, 2)
                override fun close() {
                    closed = true
                    if (firstClose) {
                        firstClose = false
                        throw expected
                    }
                }
            }
        }
        val proxy = KoEnE5RetrievalOwner.proxy(null, File(System.getProperty("java.io.tmpdir")))
        assertEquals(null, proxy.rank("c", segments(), 2).unavailableReason)

        try {
            KoEnE5RetrievalOwner.close()
            org.junit.Assert.fail("expected fatal Error from close")
        } catch (error: AssertionError) {
            assertSame(expected, error)
        }

        // The owner drops the detached engine before invoking native close.
        KoEnE5RetrievalOwner.close()
    }

    @Test
    fun `cancellation from native close propagates`() = runTest {
        installFakeProvision()
        val expected = kotlinx.coroutines.CancellationException("native close cancelled")
        KoEnE5RetrievalOwner.engineFactory = { _ ->
            object : FusionEmbeddingEngine {
                override val diagnostics = EmbeddingDiagnostics(0, 0L, null, false, null)
                override val isClosed: Boolean get() = false
                override suspend fun embed(texts: List<String>) =
                    EmbeddingResult.Success(texts.map { floatArrayOf(1f, 0f) }, 2)
                override fun close() = throw expected
            }
        }
        val proxy = KoEnE5RetrievalOwner.proxy(null, File(System.getProperty("java.io.tmpdir")))
        assertEquals(null, proxy.rank("c", segments(), 2).unavailableReason)

        try {
            KoEnE5RetrievalOwner.close()
            org.junit.Assert.fail("expected CancellationException from close")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            assertSame(expected, cancelled)
        }
    }
}
