package com.projectnuke.fusion.validation

import com.projectnuke.fusion.ai.model.AiChatRequest
import com.projectnuke.fusion.ai.model.AiMessage
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.AiRole
import com.projectnuke.fusion.ai.network.AiProviderClientException
import com.projectnuke.fusion.ai.network.ExternalRequestDeadlinePolicy
import com.projectnuke.fusion.ai.network.OpenAiCompatibleClient
import com.projectnuke.fusion.ai.secure.SecretStore
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.AutoRoutedSettingsPolicy
import com.projectnuke.fusion.model.EffectiveLocalModelProfile
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.model.LocalContextMode
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractPhase1Test {

    private class StaticSecret : SecretStore {
        override suspend fun putSecret(id: String, value: String) = true
        override suspend fun getSecret(id: String) = "k"
        override suspend fun deleteSecret(id: String) = true
    }

    private fun config() = AiProviderConfig(
        id = "t", type = AiProviderType.OPENAI, displayName = "T",
        baseUrl = "https://example.com/", modelId = "m", apiKeySecretId = "k",
    )

    private fun request() = AiChatRequest(listOf(AiMessage(AiRole.USER, "hi")))

    private open class DripConnection(
        url: URL,
        var dripResponseCode: Int = 200,
        var dripBytes: Int = -1,
        var dripDelayMs: Long = 0L,
        var okJson: String = """{"id":"1","model":"m","choices":[{"message":{"content":"hi"}}]}""",
    ) : HttpURLConnection(url) {
        val disconnects = AtomicInteger(0)
        @Volatile var disconnected = false
        private val sent = ByteArrayOutputStream()
        override fun disconnect() { disconnects.incrementAndGet(); disconnected = true }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getOutputStream(): OutputStream = sent
        override fun getResponseCode(): Int = dripResponseCode
        override fun getInputStream(): InputStream {
            if (dripResponseCode !in 200..299) throw java.io.IOException("no input for error")
            if (dripBytes < 0) return okJson.toByteArray(Charsets.UTF_8).inputStream()
            val payload = buildString {
                append("""{"id":"d","model":"m","choices":[{"message":{"content":"""")
                repeat(dripBytes) { append('x') }
                append("\"}}]}")
            }.toByteArray(Charsets.UTF_8)
            var pos = 0
            return object : InputStream() {
                override fun read(): Int {
                    if (disconnected) throw java.io.IOException("disconnected")
                    if (pos >= payload.size) return -1
                    // Slow drip in interruptible chunks: each byte arrives
                    // well below readTimeout, but disconnect aborts promptly.
                    var waited = 0L
                    while (waited < dripDelayMs) {
                        if (disconnected) throw java.io.IOException("disconnected")
                        try { Thread.sleep(5L) } catch (_: InterruptedException) {
                            throw java.io.IOException("interrupted drip")
                        }
                        waited += 5L
                    }
                    if (disconnected) throw java.io.IOException("disconnected")
                    return payload[pos++].toInt() and 0xFF
                }
            }
        }
        override fun getErrorStream(): InputStream? = null
    }

    private fun okBody(json: String): InputStream = json.toByteArray(Charsets.UTF_8).inputStream()

    @Test
    fun h14_policyConstantIsTenMinutes() {
        assertEquals(10 * 60 * 1000L, ExternalRequestDeadlinePolicy.TOTAL_WALL_CLOCK_TIMEOUT_MS)
    }

    @Test
    fun h14_normalResponseBelowDeadline_succeeds() = runTest {
        val conn = DripConnection(URL("https://example.com/"))
        val client = OpenAiCompatibleClient(StaticSecret(), connectionFactory = { conn }, totalTimeoutMs = 10_000L)
        val res = client.chatCompletion(config(), request())
        assertEquals("hi", res.content)
    }

    @Test
    fun h14_inactivityTimeoutsPreserved() {
        val holder = mutableListOf<HttpURLConnection>()
        val client = OpenAiCompatibleClient(StaticSecret(), connectionFactory = { url ->
            DripConnection(url).also { holder += it }
        })
        runBlocking { client.chatCompletion(config(), request()) }
        assertEquals(30_000, holder.single().connectTimeout)
        assertEquals(120_000, holder.single().readTimeout)
    }

    @Test
    fun h14_slowDripBeyondTotalDeadline_terminatesAtDeadline() = runTest {
        // ~40 content bytes x 50ms = ~2s of dripping, each byte well under readTimeout.
        val conn = DripConnection(URL("https://example.com/"), dripBytes = 40, dripDelayMs = 50L)
        val client = OpenAiCompatibleClient(StaticSecret(), connectionFactory = { conn }, totalTimeoutMs = 400L)
        val start = System.currentTimeMillis()
        var failure: AiProviderClientException? = null
        try {
            client.chatCompletion(config(), request())
        } catch (e: AiProviderClientException) {
            failure = e
        }
        val elapsed = System.currentTimeMillis() - start
        assertTrue("slow drip must terminate at total deadline, elapsed=$elapsed", elapsed < 1_500L)
        assertTrue("slow drip must surface as provider failure, not cancellation", failure != null)
        assertEquals(
            ExternalRequestDeadlinePolicy.TOTAL_TIMEOUT_MESSAGE,
            failure!!.message,
        )
        assertTrue("connection must disconnect on deadline", conn.disconnects.get() > 0)
    }

    @Test
    fun h14_explicitCancellationBeforeDeadline_preservesCancellation() = runTest {
        val conn = DripConnection(URL("https://example.com/"), dripBytes = 200, dripDelayMs = 50L)
        val client = OpenAiCompatibleClient(StaticSecret(), connectionFactory = { conn }, totalTimeoutMs = 30_000L)
        val job = async { client.chatCompletion(config(), request()) }
        // Let the request start, then cancel explicitly before any deadline.
        launch {
            delay(150L)
            job.cancel(CancellationException("user stop"))
        }
        var cancelled = false
        try {
            job.await()
        } catch (e: CancellationException) {
            cancelled = e.message == "user stop" || e is CancellationException
        } catch (_: AiProviderClientException) {
            cancelled = false
        }
        assertTrue("explicit cancellation must propagate as CancellationException", cancelled)
    }

    @Test
    fun h14_responseJustUnderDeadline_succeeds() = runTest {
        // ~5 bytes x 20ms = ~100ms, deadline 5s: comfortably under.
        val conn = DripConnection(URL("https://example.com/"), dripBytes = 5, dripDelayMs = 20L)
        val client = OpenAiCompatibleClient(StaticSecret(), connectionFactory = { conn }, totalTimeoutMs = 5_000L)
        val res = client.chatCompletion(config(), request())
        // Drip body content is synthetic; only success (no timeout) matters here.
        assertTrue(res.content.isNotEmpty())
    }

    // ---------- H17 ----------

    private fun profileB(): EffectiveLocalModelProfile {
        return com.projectnuke.fusion.model.LocalModelSettingsResolver.resolve(
            modelId = "model-b",
            perModelOverride = com.projectnuke.fusion.model.LocalModelSettingsOverride(
                temperature = 0.9f, topK = 10, topP = 0.5f,
                manualKvCapacityTokens = 8192,
                accelerator = AcceleratorMode.CPU,
                speculativeDecodingEnabled = true,
                contextMode = LocalContextMode.MANUAL,
            ),
            deviceIsLowRam = false,
        )
    }

    @Test
    fun h17_routedRequest_preservesSemantics_appliesBProfile() {
        val requestSettings = GenerationSettings(
            maxTokens = 777, kvCacheCapacityTokens = 1024,
            topK = 5, topP = 0.11f, temperature = 0.22f,
            accelerator = AcceleratorMode.GPU, reasoningBudgetTokens = 99,
            speculativeDecodingEnabled = false,
        )
        val routed = AutoRoutedSettingsPolicy.resolve(
            requestSettings = requestSettings,
            requestReasoningEnabled = true,
            finalModelProfile = profileB(),
            decisionAccelerator = AcceleratorMode.GPU,
            finalReasoningCapable = true,
        )
        // Final model B runtime applied.
        assertEquals(8192, routed.settings.kvCacheCapacityTokens)
        assertEquals(true, routed.settings.speculativeDecodingEnabled)
        assertEquals(LocalContextMode.MANUAL, routed.contextMode)
        assertEquals(AcceleratorMode.GPU, routed.settings.accelerator)
        // Request semantics preserved; B stored sampling (0.9/10/0.5) must not leak.
        assertEquals(0.22f, routed.settings.temperature)
        assertEquals(0.11f, routed.settings.topP)
        assertEquals(5, routed.settings.topK)
        assertEquals(777, routed.settings.maxTokens)
        assertEquals(99, routed.settings.reasoningBudgetTokens)
        assertTrue(routed.reasoningEnabled)
    }

    @Test
    fun h17_noLeakOfAModelSpecificOverrides_reasoningGatedByCapability() {
        val requestSettings = GenerationSettings(
            maxTokens = 500, kvCacheCapacityTokens = 2048,
            topK = 7, topP = 0.3f, temperature = 0.4f,
            accelerator = AcceleratorMode.CPU, reasoningBudgetTokens = 64,
            speculativeDecodingEnabled = true,
        )
        val routedNoReason = AutoRoutedSettingsPolicy.resolve(
            requestSettings = requestSettings,
            requestReasoningEnabled = true,
            finalModelProfile = profileB(),
            decisionAccelerator = AcceleratorMode.CPU,
            finalReasoningCapable = false,
        )
        // B incapable: reasoning off despite request intent.
        assertTrue(!routedNoReason.reasoningEnabled)
        // A's KV (2048) and A's speculative (true->B's true here) must not leak:
        // KV comes from B (8192), speculative from B (true).
        assertEquals(8192, routedNoReason.settings.kvCacheCapacityTokens)
        assertEquals(AcceleratorMode.CPU, routedNoReason.settings.accelerator)
        assertEquals(0.4f, routedNoReason.settings.temperature)
    }
}
