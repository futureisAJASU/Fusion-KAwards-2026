package com.projectnuke.fusion.privacy

import com.projectnuke.fusion.ai.ExternalAiChatRunner
import com.projectnuke.fusion.ai.ExternalAiProviderSource
import com.projectnuke.fusion.ai.model.AiChatRequest
import com.projectnuke.fusion.ai.model.AgenticWebSearchTool
import com.projectnuke.fusion.ai.model.AiChatResponse
import com.projectnuke.fusion.ai.model.AiMessage
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.AiRole
import com.projectnuke.fusion.ai.model.AiToolCall
import com.projectnuke.fusion.ai.model.AiToolChoice
import com.projectnuke.fusion.ai.model.ExternalReasoningEffort
import com.projectnuke.fusion.ai.model.ExternalRequestPatch
import com.projectnuke.fusion.ai.model.ReasoningEffortMapping
import com.projectnuke.fusion.ai.network.AiProviderClientException
import com.projectnuke.fusion.ai.network.ChatClient
import com.projectnuke.fusion.ai.network.ExternalOutputTokenField
import com.projectnuke.fusion.ai.network.ExternalRequestCompatibility
import com.projectnuke.fusion.ai.network.ExternalTemperaturePolicy
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// C5 exact egress enforcement tests: the single PreparedEgressRequest
// authority binds descriptor and transport bytes together, and the REAL
// transport guard (EgressGateChatClient) consumes request-scoped tickets.
// Delegate call counts prove 0/1 calls. Concurrency tests prove tickets
// cannot overwrite, cross-consume, or impersonate each other.
class DataEgressControllerTest {

    private class CountingDelegate(var reply: String = "ok") : ChatClient {
        val calls = AtomicInteger(0)
        override suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest): AiChatResponse {
            calls.incrementAndGet()
            return AiChatResponse("id", config.modelId, reply)
        }
    }

    private fun provider(id: String = "p1") = AiProviderConfig(
        id = id,
        type = AiProviderType.OPENAI,
        displayName = "P",
        baseUrl = "https://api.example.com",
        modelId = "m",
        apiKeySecretId = "s",
    )

    private fun request(vararg texts: String, temperature: Double? = 0.7, maxTokens: Int? = 128) =
        AiChatRequest(texts.map { AiMessage(AiRole.USER, it) }, temperature, maxTokens)

    private fun prepared(
        requestId: String,
        providerId: String,
        transport: AiChatRequest,
        excerpts: List<String> = emptyList(),
        patch: ExternalRequestPatch = ExternalRequestPatch(),
        compatibility: ExternalRequestCompatibility = ExternalRequestCompatibility.FALLBACK,
    ): PreparedEgressRequest = PreparedEgressRequestFactory.prepare(
        requestId = requestId,
        destination = DataEgressDestination.EXTERNAL_AI,
        provider = provider(providerId),
        exactRequest = transport,
        resolvedRequestPatch = patch,
        requestCompatibility = compatibility,
        transmittedUserText = transport.messages.lastOrNull()?.content.orEmpty(),
        transmittedHistoryText = "",
        privateExcerpts = excerpts,
    )

    private fun consentFor(prepared: PreparedEgressRequest) = DataEgressPolicy.makeConsent(
        "c1",
        prepared.requestId,
        prepared.providerId,
        prepared.payloadHashHex,
        true,
        DataEgressScope.USER_QUERY_AND_HISTORY,
    )

    @Test
    fun `private payload without consent transmits nothing`() {
        val delegate = CountingDelegate()
        val controller = DataEgressController()
        val transport = request("user text")
        val payload = prepared("req-1", "p1", transport, listOf("private excerpt A"))
        assertFalse(controller.arm(payload, null))
        val auth = controller.authorize(provider("p1"), transport, "req-1")
        assertTrue(auth is DataEgressController.Authorization.Refused)
        assertEquals(0, delegate.calls.get())
    }

    @Test
    fun `request revoked after arming is rejected at the transport boundary`() = runTest {
        val delegate = CountingDelegate()
        val controller = DataEgressController()
        val transport = request("public search planner query")
        val payload = prepared("revoked-before-transport", "p1", transport)
        var authorized = true
        val gated = EgressGateChatClient(delegate, controller)

        assertTrue(controller.arm(payload, null) { authorized })
        authorized = false
        val failure = runCatching {
            gated.chatCompletion(provider("p1"), transport, payload.requestId)
        }.exceptionOrNull()

        assertTrue(failure is AiProviderClientException)
        assertTrue((failure as AiProviderClientException).egressAuthorizationDenied)
        assertEquals(0, delegate.calls.get())
    }

    @Test
    fun `already revoked request cannot arm an egress ticket`() {
        val controller = DataEgressController()
        val payload = prepared("revoked-at-admission", "p1", request("public search planner query"))

        assertFalse(controller.arm(payload, null) { false })
        assertEquals(0, controller.pendingTicketCountForTest())
    }

    @Test
    fun `native tool definitions calls and results are bound to exact egress identity`() {
        val base = request("public query")
        val native = base.copy(
            tools = listOf(AgenticWebSearchTool.definition()),
            toolChoice = AiToolChoice.AUTO,
        )
        val call = AiToolCall("call-1", "web_search", """{"queries":["public query"]}""")
        val withToolTranscript = native.copy(messages = listOf(
            AiMessage(AiRole.ASSISTANT, "", toolCalls = listOf(call)),
            AiMessage(AiRole.TOOL, "bounded public results", toolCallId = "call-1"),
        ))
        val plainPrepared = prepared("tool-req", "p1", base)
        val nativePrepared = prepared("tool-req", "p1", native)
        val transcriptPrepared = prepared("tool-req", "p1", withToolTranscript)

        assertNotEquals(plainPrepared.transportHashHex, nativePrepared.transportHashHex)
        assertNotEquals(nativePrepared.transportHashHex, transcriptPrepared.transportHashHex)
        assertNotEquals(
            transcriptPrepared.transportHashHex,
            prepared("tool-req", "p1", withToolTranscript.copy(messages = listOf(
                AiMessage(AiRole.ASSISTANT, "", toolCalls = listOf(call)),
                AiMessage(AiRole.TOOL, "changed public result", toolCallId = "call-1"),
            ))).transportHashHex,
        )

        val controller = DataEgressController()
        assertTrue(controller.arm(nativePrepared, null))
        assertTrue(controller.authorize(provider("p1"), native, "tool-req") is DataEgressController.Authorization.Granted)
        assertTrue(controller.arm(nativePrepared, null))
        assertTrue(controller.authorize(provider("p1"), base, "tool-req") is DataEgressController.Authorization.Refused)
    }

    private fun snapshot(
        modelId: String,
        patch: ExternalRequestPatch,
        compatibility: ExternalRequestCompatibility = ExternalRequestCompatibility.FALLBACK,
    ) = ExternalProviderRequestSnapshot(
        id = "p1",
        type = AiProviderType.OPENAI,
        baseUrl = "https://api.example.com/v1",
        modelId = modelId,
        temperature = 0.7,
        maxTokens = 128,
        authMode = com.projectnuke.fusion.ai.model.AiProviderAuthMode.BEARER_API_KEY,
        authHeaderName = null,
        reasoningContentEnabled = false,
        apiKeySecretId = "s",
        displayName = "P",
        selectedVariantId = "test",
        resolvedRequestPatch = patch,
        requestCompatibility = compatibility,
    )

    @Test
    fun `excerpt swap invalidates old consent`() {
        val delegate = CountingDelegate()
        val controller = DataEgressController()
        val transport = request("user text")
        val payloadA = prepared("req-1", "p1", transport, listOf("private excerpt A"))
        val payloadB = prepared("req-1", "p1", transport, listOf("private excerpt B"))
        assertTrue(payloadA.payloadHashHex != payloadB.payloadHashHex)
        val consentA = consentFor(payloadA)
        // Same request, same provider, mutated private excerpt: the old
        // consent must not authorize the new prepared payload.
        assertFalse(controller.arm(payloadB, consentA))
        assertEquals(0, delegate.calls.get())
    }

    @Test
    fun `provider swap invalidates consent`() {
        val delegate = CountingDelegate()
        val controller = DataEgressController()
        val transport = request("user text")
        val payload = prepared("req-1", "p1", transport, listOf("private excerpt A"))
        assertTrue(controller.arm(payload, consentFor(payload)))
        val auth = controller.authorize(provider("p2"), transport, "req-1")
        assertTrue(auth is DataEgressController.Authorization.Refused)
        assertEquals(0, delegate.calls.get())
    }

    @Test
    fun `valid private consent reaches delegate exactly once`() = runTest {
        val delegate = CountingDelegate()
        val controller = DataEgressController()
        val gate = EgressGateChatClient(delegate, controller)
        val transport = request("user text")
        val payload = prepared("req-1", "p1", transport, listOf("private excerpt A"))
        assertTrue(controller.arm(payload, consentFor(payload)))
        val response = gate.chatCompletion(provider("p1"), transport, "req-1")
        assertEquals("ok", response.content)
        var refused = false
        try {
            gate.chatCompletion(provider("p1"), transport, "req-1")
        } catch (_: AiProviderClientException) {
            refused = true
        }
        assertTrue(refused)
        assertEquals(1, delegate.calls.get())
    }

    @Test
    fun `payload mutation after approval transmits nothing`() = runTest {
        val delegate = CountingDelegate()
        val controller = DataEgressController()
        val gate = EgressGateChatClient(delegate, controller)
        val payload = prepared("req-3", "p1", request("hello"))
        assertTrue(controller.arm(payload, null))
        var refused = false
        try {
            gate.chatCompletion(provider("p1"), request("hello mutated"), "req-3")
        } catch (_: AiProviderClientException) {
            refused = true
        }
        assertTrue(refused)
        assertEquals(0, delegate.calls.get())
    }

    @Test
    fun `model and frozen patch mutations cannot consume exact ticket`() {
        val controller = DataEgressController()
        val transport = request("hello")
        val high = ExternalRequestPatch(ExternalReasoningEffort.HIGH)
        assertTrue(controller.arm(prepared("model", "p1", transport, patch = high), null))
        assertTrue(controller.authorize(provider("p1").copy(modelId = "other"), transport, "model", snapshot("other", high)) is DataEgressController.Authorization.Refused)
        assertTrue(controller.authorize(provider("p1"), transport, "model", snapshot("m", ExternalRequestPatch())) is DataEgressController.Authorization.Refused)
        assertTrue(controller.authorize(provider("p1"), transport, "model", snapshot("m", high)) is DataEgressController.Authorization.Granted)
    }

    @Test
    fun `temperature and max tokens mutations cannot consume exact ticket`() {
        val controller = DataEgressController()
        val transport = request("hello", temperature = 0.7, maxTokens = 128)
        assertTrue(controller.arm(prepared("fields", "p1", transport), null))
        assertTrue(controller.authorize(provider("p1"), request("hello", temperature = 0.8, maxTokens = 128), "fields") is DataEgressController.Authorization.Refused)
        assertTrue(controller.arm(prepared("fields-2", "p1", transport), null))
        assertTrue(controller.authorize(provider("p1"), request("hello", temperature = 0.7, maxTokens = 129), "fields-2") is DataEgressController.Authorization.Refused)
    }

    @Test
    fun `typed output token field changes transport identity and authorization`() {
        val controller = DataEgressController()
        val transport = request("hello")
        val maxTokens = ExternalRequestCompatibility(
            outputTokenField = ExternalOutputTokenField.MAX_TOKENS,
        )
        val maxCompletionTokens = ExternalRequestCompatibility(
            outputTokenField = ExternalOutputTokenField.MAX_COMPLETION_TOKENS,
        )
        val maxTokensPrepared = prepared("dialect", "p1", transport, compatibility = maxTokens)
        val maxCompletionPrepared = prepared("dialect-2", "p1", transport, compatibility = maxCompletionTokens)

        assertTrue(maxTokensPrepared.transportHashHex != maxCompletionPrepared.transportHashHex)
        assertTrue(controller.arm(maxTokensPrepared, null))
        assertTrue(
            controller.authorize(
                provider("p1"),
                transport,
                "dialect",
                snapshot("m", ExternalRequestPatch(), maxCompletionTokens),
            ) is DataEgressController.Authorization.Refused,
        )
        assertTrue(
            controller.authorize(
                provider("p1"),
                transport,
                "dialect",
                snapshot("m", ExternalRequestPatch(), maxTokens),
            ) is DataEgressController.Authorization.Granted,
        )
    }

    @Test
    fun `temperature omission changes identity but ignores stored temperature`() {
        val emit = ExternalRequestCompatibility(temperaturePolicy = ExternalTemperaturePolicy.EMIT)
        val omit = ExternalRequestCompatibility(temperaturePolicy = ExternalTemperaturePolicy.OMIT)
        val emitted = prepared("emit", "p1", request("hello", temperature = 0.7), compatibility = emit)
        val omitted = prepared("omit", "p1", request("hello", temperature = 0.7), compatibility = omit)
        val omittedDifferentValue = prepared("omit-2", "p1", request("hello", temperature = 0.2), compatibility = omit)

        assertTrue(emitted.transportHashHex != omitted.transportHashHex)
        assertEquals(omitted.transportHashHex, omittedDifferentValue.transportHashHex)
    }

    @Test
    fun `unused output field does not change identity without a serialized limit`() {
        val noLimit = request("hello", maxTokens = null)
        val maxTokens = prepared(
            "no-limit-a",
            "p1",
            noLimit,
            compatibility = ExternalRequestCompatibility(ExternalOutputTokenField.MAX_TOKENS),
        )
        val maxCompletionTokens = prepared(
            "no-limit-b",
            "p1",
            noLimit,
            compatibility = ExternalRequestCompatibility(ExternalOutputTokenField.MAX_COMPLETION_TOKENS),
        )

        assertEquals(maxTokens.transportHashHex, maxCompletionTokens.transportHashHex)
    }

    @Test
    fun `egress identity distinguishes typed mapping and custom value`() {
        val controller = DataEgressController()
        val transport = request("hello")
        val emptyFlat = prepared("empty-flat", "p1", transport)
        val emptyNested = prepared(
            "empty-nested",
            "p1",
            transport,
            patch = ExternalRequestPatch(reasoningEffortMapping = ReasoningEffortMapping.NESTED_REASONING_EFFORT),
        )
        val flat = ExternalRequestPatch(customReasoningEffort = "high")
        val nested = ExternalRequestPatch(
            customReasoningEffort = "high",
            reasoningEffortMapping = ReasoningEffortMapping.NESTED_REASONING_EFFORT,
        )
        val flatPrepared = prepared("mapping", "p1", transport, patch = flat)
        val nestedPrepared = prepared("mapping-2", "p1", transport, patch = nested)
        val knownPrepared = prepared("known", "p1", transport, patch = ExternalRequestPatch(ExternalReasoningEffort.HIGH))
        assertEquals(emptyFlat.transportHashHex, emptyNested.transportHashHex)
        assertEquals(flatPrepared.transportHashHex, knownPrepared.transportHashHex)
        assertTrue(flatPrepared.transportHashHex != nestedPrepared.transportHashHex)
        assertTrue(controller.arm(flatPrepared, null))
        assertTrue(controller.authorize(provider("p1"), transport, "mapping", snapshot("m", nested)) is DataEgressController.Authorization.Refused)
        assertTrue(controller.arm(nestedPrepared, null))
        assertTrue(controller.authorize(provider("p1"), transport, "mapping-2", snapshot("m", nested)) is DataEgressController.Authorization.Granted)
    }

    @Test
    fun `custom effort value mutation is refused`() {
        val controller = DataEgressController()
        val transport = request("hello")
        val original = ExternalRequestPatch(customReasoningEffort = "high")
        assertTrue(controller.arm(prepared("value", "p1", transport, patch = original), null))
        assertTrue(
            controller.authorize(
                provider("p1"),
                transport,
                "value",
                snapshot("m", ExternalRequestPatch(customReasoningEffort = "low")),
            ) is DataEgressController.Authorization.Refused,
        )
    }

    @Test
    fun `exact identity never carries secrets`() {
        val payload = prepared("req-9", "p1", request("x"), listOf("sk-secret-value secret-text"))
        assertFalse(payload.canonicalIdentity().contains("sk-secret-value"))
        assertFalse(payload.canonicalIdentity().contains("secret-text"))
        assertTrue(payload.canonicalIdentity().contains("exact-egress-v1"))
    }

    @Test
    fun `ordinary text request arms without extra consent`() = runTest {
        val delegate = CountingDelegate()
        val controller = DataEgressController()
        val gate = EgressGateChatClient(delegate, controller)
        // Ordinary text-only EXTERNAL_AI_API request: non-private prepared
        // payload arms with no consent and transmits exactly once.
        val payload = prepared("req-ord", "p1", request("hello"))
        assertFalse(payload.includesPrivateMaterial)
        assertTrue(controller.arm(payload, null))
        gate.chatCompletion(provider("p1"), request("hello"), "req-ord")
        assertEquals(1, delegate.calls.get())
    }

    @Test
    fun `concurrent tickets stay isolated`() {
        val controller = DataEgressController()
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        val results = ConcurrentHashMap<String, Boolean>()
        repeat(4) { i ->
            thread {
                try {
                    start.await(10, TimeUnit.SECONDS)
                    val transport = request("text-$i")
                    val payload = prepared("req-$i", "p$i", transport)
                    controller.arm(payload, null)
                    val auth = controller.authorize(provider("p$i"), transport, "req-$i")
                    results["req-$i"] = auth is DataEgressController.Authorization.Granted
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(4, results.size)
        assertTrue(results.values.all { it })
        assertEquals(0, controller.pendingTicketCountForTest())
    }

    @Test
    fun `cross ticket consume is impossible`() {
        val controller = DataEgressController()
        val transportA = request("text-a")
        val transportB = request("text-b")
        assertTrue(controller.arm(prepared("req-a", "p1", transportA), null))
        // B's bytes cannot consume A's ticket, and the failed attempt leaves
        // A's own ticket intact for its exact request.
        assertTrue(
            controller.authorize(provider("p1"), transportB, "req-a") is DataEgressController.Authorization.Refused,
        )
        assertTrue(
            controller.authorize(provider("p1"), transportA, "req-a") is DataEgressController.Authorization.Granted,
        )
        assertEquals(0, controller.pendingTicketCountForTest())
    }

    @Test
    fun `exact request cleanup leaves unrelated ticket authorized`() {
        val controller = DataEgressController()
        val transportA = request("A")
        val transportB = request("B")
        assertTrue(controller.arm(prepared("request-a", "p1", transportA), null))
        assertTrue(controller.arm(prepared("request-b", "p1", transportB), null))
        controller.disarm("request-a")
        assertTrue(
            controller.authorize(provider("p1"), transportB, "request-b") is
                DataEgressController.Authorization.Granted,
        )
    }

    @Test
    fun `capacity rejects a new ticket without evicting existing owners`() {
        val controller = DataEgressController()
        val existing = (0 until DataEgressController.MAX_TICKETS).map { index ->
            val transport = request("existing-$index")
            prepared("existing-$index", "p1", transport) to transport
        }
        existing.forEach { (payload, _) -> assertTrue(controller.arm(payload, null)) }
        val newcomerTransport = request("newcomer")
        assertFalse(controller.arm(prepared("newcomer", "p1", newcomerTransport), null))
        assertEquals(DataEgressController.MAX_TICKETS, controller.pendingTicketCountForTest())
        existing.forEach { (payload, transport) ->
            assertTrue(
                controller.authorize(provider("p1"), transport, payload.requestId) is
                    DataEgressController.Authorization.Granted,
            )
        }
    }

    @Test
    fun `same exact identity may be rearmed at capacity`() {
        val controller = DataEgressController()
        val firstTransport = request("same")
        val first = prepared("same-request", "p1", firstTransport)
        assertTrue(controller.arm(first, null))
        repeat(DataEgressController.MAX_TICKETS - 1) { index ->
            assertTrue(controller.arm(prepared("other-$index", "p1", request("other-$index")), null))
        }
        assertTrue(controller.arm(first, null))
        assertEquals(DataEgressController.MAX_TICKETS, controller.pendingTicketCountForTest())
        assertTrue(
            controller.authorize(provider("p1"), firstTransport, "same-request") is
                DataEgressController.Authorization.Granted,
        )
    }

    @Test
    fun `same provider and bytes require the matching request identity`() {
        val controller = DataEgressController()
        val transport = request("identical")
        val payloadA = prepared("request-a", "p1", transport, listOf("private A"))
        val payloadB = prepared("request-b", "p1", transport, listOf("private B"))
        assertTrue(controller.arm(payloadA, consentFor(payloadA)))
        assertTrue(controller.arm(payloadB, consentFor(payloadB)))

        assertTrue(
            controller.authorize(provider("p1"), transport, "request-b") is
                DataEgressController.Authorization.Granted,
        )
        assertTrue(
            controller.authorize(provider("p1"), transport, "request-a") is
                DataEgressController.Authorization.Granted,
        )
    }

    @Test
    fun `failed private arm cannot consume an existing request permit`() {
        val controller = DataEgressController()
        val transport = request("identical")
        val payloadA = prepared("request-a", "p1", transport, listOf("private A"))
        val payloadB = prepared("request-b", "p1", transport, listOf("private B"))
        assertTrue(controller.arm(payloadA, consentFor(payloadA)))
        assertFalse(controller.arm(payloadB, consentFor(payloadA)))

        assertTrue(
            controller.authorize(provider("p1"), transport, "request-b") is
                DataEgressController.Authorization.Refused,
        )
        assertTrue(
            controller.authorize(provider("p1"), transport, "request-a") is
                DataEgressController.Authorization.Granted,
        )
    }

    @Test
    fun `failed rearm clears any stale permit for the same request`() {
        val controller = DataEgressController()
        val transport = request("same bytes")
        val payload = prepared("request-a", "p1", transport, listOf("private A"))
        assertTrue(controller.arm(payload, consentFor(payload)))
        assertFalse(controller.arm(payload, null))
        assertTrue(
            controller.authorize(provider("p1"), transport, "request-a") is
                DataEgressController.Authorization.Refused,
        )
    }

    @Test
    fun `concurrent identical private payloads remain isolated`() {
        val controller = DataEgressController()
        val transport = request("same bytes")
        val results = ConcurrentHashMap<String, Boolean>()
        val done = CountDownLatch(2)
        listOf("request-a", "request-b").forEach { requestId ->
            thread {
                try {
                    val payload = prepared(requestId, "p1", transport, listOf(requestId))
                    val armed = controller.arm(payload, consentFor(payload))
                    val authorized = armed && controller.authorize(provider("p1"), transport, requestId) is
                        DataEgressController.Authorization.Granted
                    results[requestId] = authorized
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(mapOf("request-a" to true, "request-b" to true), results)
    }

    @Test
    fun `external runner carries request identity to the final gate`() = runTest {
        val config = provider("p1").copy(maxTokens = 128)
        val delegate = CountingDelegate()
        val controller = DataEgressController()
        val gate = EgressGateChatClient(delegate, controller)
        val runner = ExternalAiChatRunner(
            object : ExternalAiProviderSource {
                override suspend fun getSelectedRunnableProvider(): AiProviderConfig = config
                override suspend fun getRunnableProviderById(id: String): AiProviderConfig? =
                    config.takeIf { it.id == id }
            },
            gate,
        )
        val transport = request("runner bytes")
        val prepared = PreparedEgressRequestFactory.prepare(
            requestId = "runner-request",
            destination = DataEgressDestination.EXTERNAL_AI,
            provider = config,
            exactRequest = transport,
            transmittedUserText = "runner bytes",
            transmittedHistoryText = "",
        )
        assertTrue(controller.arm(prepared, null))
        val result = runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "runner bytes")),
            providerId = "p1",
            providerSelectionFrozen = true,
            requestId = "runner-request",
        )
        assertTrue(result is com.projectnuke.fusion.ai.ExternalAiChatResult.Success)
        assertEquals(1, delegate.calls.get())
    }

    @Test
    fun `request scoped authorization approves planner queries and refuses private material`() {
        // Immutable per-search contexts: no global mutable set remains.
        val authA = com.projectnuke.fusion.search.SearchQueryAuthorization(
            "tok-a",
            setOf("오늘 뉴스", "오늘 주요 뉴스"),
        )
        val authB = com.projectnuke.fusion.search.SearchQueryAuthorization("tok-b", setOf("다른 질문"))
        authA.requireApproved("오늘 뉴스")
        var refused = false
        try {
            authA.requireApproved("비밀 보고서 본문 발췌")
        } catch (_: IllegalArgumentException) {
            refused = true
        }
        assertTrue(refused)
        // B's context is independent of A's.
        var refusedB = false
        try {
            authB.requireApproved("오늘 뉴스")
        } catch (_: IllegalArgumentException) {
            refusedB = true
        }
        assertTrue(refusedB)
        authB.requireApproved("다른 질문")
        assertTrue(DataEgressPolicy.isApprovedWebQuery("오늘 뉴스", setOf("오늘 뉴스")))
        assertFalse(DataEgressPolicy.isApprovedWebQuery("비밀 보고서", setOf("오늘 뉴스")))
    }

    @Test
    fun `production logging path redacts registered private text`() {
        DataEgressPrivateTextLedger.clear()
        try {
            DataEgressPrivateTextLedger.register(listOf("비밀 보고서 본문 12345678"))
            val (message, summary) = com.projectnuke.fusion.ui.DeveloperLogStore.sanitizeForRecord(
                "검색 성공: 비밀 보고서 본문 12345678 포함",
                "trace 비밀 보고서 본문 12345678 end",
            )
            assertFalse(message.contains("비밀 보고서 본문 12345678"))
            assertFalse((summary ?: "").contains("비밀 보고서 본문 12345678"))
            assertFalse(DataEgressPolicy.diagnosticsLeaksPrivate(message, listOf("x")))
        } finally {
            DataEgressPrivateTextLedger.clear()
        }
    }

    @Test
    fun `private text ledger rejects candidates that exceed aggregate bound`() {
        DataEgressPrivateTextLedger.clear()
        try {
            DataEgressPrivateTextLedger.register(listOf("x".repeat(65_537)))
            assertTrue(DataEgressPrivateTextLedger.currentTexts().isEmpty())

            DataEgressPrivateTextLedger.register((0 until 64).map { "candidate-$it" + "y".repeat(2_048) })
            val retained = DataEgressPrivateTextLedger.currentTexts()
            assertTrue(retained.isNotEmpty())
            assertTrue(retained.sumOf { it.length } <= 65_536)

            val before = retained.toSet()
            DataEgressPrivateTextLedger.register(listOf("z".repeat(65_536)))
            assertEquals(before, DataEgressPrivateTextLedger.currentTexts().toSet())
        } finally {
            DataEgressPrivateTextLedger.clear()
        }
    }
}
