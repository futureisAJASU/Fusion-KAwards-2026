package com.projectnuke.fusion.ai.network

import com.projectnuke.fusion.ai.model.AiChatRequest
import com.projectnuke.fusion.ai.model.AiChatResponse
import com.projectnuke.fusion.ai.model.AiMessage
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.AiRole
import com.projectnuke.fusion.ai.model.ExternalReasoningEffort
import com.projectnuke.fusion.ai.model.ExternalRequestPatch
import com.projectnuke.fusion.ai.secure.SecretStore
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleClientTest {

    private fun ctx() = kotlin.coroutines.EmptyCoroutineContext

    // ── readBoundedBody: hard-cap tests ──────────────────────────────

    @Test
    fun readBoundedBody_asciiExactlyAtByteLimit_succeeds() = runBlocking {
        val data = "A".repeat(100).toByteArray(Charsets.UTF_8)
        val result = OpenAiCompatibleClient.readBoundedBody(ByteArrayInputStream(data), 100, OpenAiCompatibleClient.BodyKind.SUCCESS, ctx())
        assertEquals("A".repeat(100), result)
    }

    @Test
    fun readBoundedBody_asciiOneByteAboveLimit_fails() {
        val data = "A".repeat(101).toByteArray(Charsets.UTF_8)
        val ex = org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
            runBlocking {
                OpenAiCompatibleClient.readBoundedBody(ByteArrayInputStream(data), 100, OpenAiCompatibleClient.BodyKind.SUCCESS, ctx())
            }
        }
        assertEquals("외부 AI API 응답이 너무 큽니다.", ex.message)
    }

    @Test
    fun readBoundedBody_koreanThreeSyllables9Bytes_succeedsWithLimit9() = runBlocking {
        val original = "가나다"
        val data = original.toByteArray(Charsets.UTF_8)
        assertEquals(9, data.size)
        val result = OpenAiCompatibleClient.readBoundedBody(ByteArrayInputStream(data), 9, OpenAiCompatibleClient.BodyKind.SUCCESS, ctx())
        assertEquals(original, result)
    }

    @Test
    fun readBoundedBody_koreanFourSyllables12Bytes_failsWithLimit10() {
        val original = "가나다라"
        val data = original.toByteArray(Charsets.UTF_8)
        assertEquals(12, data.size)
        org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
            runBlocking {
                OpenAiCompatibleClient.readBoundedBody(ByteArrayInputStream(data), 10, OpenAiCompatibleClient.BodyKind.SUCCESS, ctx())
            }
        }
    }

    @Test
    fun readBoundedBody_emojiAndMixedContent_intactBelowLimit() = runBlocking {
        val original = "Hello 안녕하세요 🌍 こんにちは"
        val data = original.toByteArray(Charsets.UTF_8)
        assertTrue(data.size > original.length)
        val result = OpenAiCompatibleClient.readBoundedBody(ByteArrayInputStream(data), data.size + 100, OpenAiCompatibleClient.BodyKind.SUCCESS, ctx())
        assertEquals(original, result)
    }

    @Test
    fun readBoundedBody_oversizedErrorBody_usesErrorMessage() {
        val data = "X".repeat(OpenAiCompatibleClient.MaxErrorBodyBytes + 1).toByteArray(Charsets.UTF_8)
        val ex = org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
            runBlocking {
                OpenAiCompatibleClient.readBoundedBody(ByteArrayInputStream(data), OpenAiCompatibleClient.MaxErrorBodyBytes, OpenAiCompatibleClient.BodyKind.ERROR, ctx())
            }
        }
        assertEquals("외부 AI API 오류 응답이 너무 큽니다.", ex.message)
    }

    @Test
    fun readBoundedBody_emptyStream_succeeds() = runBlocking {
        val result = OpenAiCompatibleClient.readBoundedBody(ByteArrayInputStream(ByteArray(0)), 1024, OpenAiCompatibleClient.BodyKind.SUCCESS, ctx())
        assertEquals("", result)
    }

    @Test
    fun readBoundedBody_belowLimit_succeeds() = runBlocking {
        val data = "Hello, world!".toByteArray(Charsets.UTF_8)
        val result = OpenAiCompatibleClient.readBoundedBody(ByteArrayInputStream(data), 1024, OpenAiCompatibleClient.BodyKind.SUCCESS, ctx())
        assertEquals("Hello, world!", result)
    }

    @Test
    fun readBoundedBody_multibyteAtBoundary_notCorrupted() = runBlocking {
        val original = "가나다라마바사"
        val data = original.toByteArray(Charsets.UTF_8)
        val result = OpenAiCompatibleClient.readBoundedBody(ByteArrayInputStream(data), data.size, OpenAiCompatibleClient.BodyKind.SUCCESS, ctx())
        assertEquals(original, result)
    }

    @Test
    fun readBoundedBody_sequentialChunks_succeeds() = runBlocking {
        val data = "Hello World!".toByteArray(Charsets.UTF_8)
        val stream = object : InputStream() {
            private var pos = 0
            override fun read(): Int = if (pos < data.size) data[pos++].toInt() else -1
        }
        val result = OpenAiCompatibleClient.readBoundedBody(stream, 1024, OpenAiCompatibleClient.BodyKind.SUCCESS, ctx())
        assertEquals("Hello World!", result)
    }

    @Test
    fun readBoundedBody_zeroMaxBytes_emptyInput_succeeds() = runBlocking {
        val result = OpenAiCompatibleClient.readBoundedBody(ByteArrayInputStream(ByteArray(0)), 0, OpenAiCompatibleClient.BodyKind.SUCCESS, ctx())
        assertEquals("", result)
    }

    @Test
    fun readBoundedBody_zeroMaxBytes_nonEmptyInput_fails() {
        org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
            runBlocking {
                OpenAiCompatibleClient.readBoundedBody(ByteArrayInputStream("A".toByteArray()), 0, OpenAiCompatibleClient.BodyKind.SUCCESS, ctx())
            }
        }
    }

    @Test
    fun readBoundedBody_negativeMaxBytes_throws() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                OpenAiCompatibleClient.readBoundedBody(ByteArrayInputStream(ByteArray(0)), -1, OpenAiCompatibleClient.BodyKind.SUCCESS, ctx())
            }
        }
    }

    @Test
    fun readBoundedBody_largeChunkInputStream_overflowFailsWithoutWriting() {
        val largeData = "C".repeat(8192).toByteArray(Charsets.UTF_8)
        var pos = 0
        val readRequests = mutableListOf<Int>()

        val stream = object : InputStream() {
            override fun read(): Int {
                if (pos >= largeData.size) return -1
                return largeData[pos++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                readRequests.add(len)
                if (pos >= largeData.size) return -1
                val available = minOf(len, largeData.size - pos)
                System.arraycopy(largeData, pos, b, off, available)
                pos += available
                return available
            }
        }

        val ex = org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
            runBlocking {
                OpenAiCompatibleClient.readBoundedBody(stream, 100, OpenAiCompatibleClient.BodyKind.SUCCESS, ctx())
            }
        }
        assertEquals("외부 AI API 응답이 너무 큽니다.", ex.message)
        assertTrue("Should have made at least one read request", readRequests.isNotEmpty())
        assertEquals("First read should request remaining+1 = 101 bytes", 101, readRequests[0])
        readRequests.forEach { assertTrue("No read request should exceed 101, got $it", it <= 101) }
    }

    @Test
    fun readBoundedBody_exactMaxBytes_succeeds() {
        val exactData = "D".repeat(100).toByteArray(Charsets.UTF_8)
        var pos = 0

        val stream = object : InputStream() {
            override fun read(): Int {
                if (pos >= exactData.size) return -1
                return exactData[pos++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= exactData.size) return -1
                val available = minOf(len, exactData.size - pos)
                System.arraycopy(exactData, pos, b, off, available)
                pos += available
                return available
            }
        }

        val result = runBlocking {
            OpenAiCompatibleClient.readBoundedBody(stream, 100, OpenAiCompatibleClient.BodyKind.SUCCESS, ctx())
        }
        assertEquals(100, result.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun readBoundedBody_successAndErrorOversizedMessagesDiffer() {
        val data = "X".repeat(101).toByteArray(Charsets.UTF_8)
        val successEx = org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
            runBlocking {
                OpenAiCompatibleClient.readBoundedBody(ByteArrayInputStream(data), 100, OpenAiCompatibleClient.BodyKind.SUCCESS, ctx())
            }
        }
        val errorEx = org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
            runBlocking {
                OpenAiCompatibleClient.readBoundedBody(ByteArrayInputStream(data), 100, OpenAiCompatibleClient.BodyKind.ERROR, ctx())
            }
        }
        assertEquals("외부 AI API 응답이 너무 큽니다.", successEx.message)
        assertEquals("외부 AI API 오류 응답이 너무 큽니다.", errorEx.message)
    }

    // ── Coroutine cancellation tests (deterministic) ─────────────────

    @Test
    fun cancellationDuringResponseRead_disconnects() = runBlocking {
        val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
        fakeConn.configure(responseCode = 200, responseBody = "x".repeat(1000).toByteArray(Charsets.UTF_8))
        fakeConn.blockReads = true

        val config = makeConfig()
        val client = OpenAiCompatibleClient(secretStore = FakeSecretStore("valid-key"), connectionFactory = { fakeConn })

        val workJob = launch(Dispatchers.IO) {
            client.chatCompletion(config, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi")), temperature = 0.7, maxTokens = 100))
        }

        try {
            assertTrue("Read should have started", fakeConn.awaitReadStarted(5_000))
            workJob.cancel()
            assertTrue("disconnect observed before join", fakeConn.awaitDisconnect(5_000))
            withTimeout(5_000) { workJob.join() }
            assertTrue("Job cancelled", workJob.isCancelled)
        } finally {
            fakeConn.releaseAll()
        }
    }

    @Test
    fun cancellationDuringRequestWrite_disconnects() = runBlocking {
        val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
        fakeConn.configure(responseCode = 200)
        fakeConn.blockWrites = true

        val config = makeConfig()
        val client = OpenAiCompatibleClient(secretStore = FakeSecretStore("valid-key"), connectionFactory = { fakeConn })

        val workJob = launch(Dispatchers.IO) {
            client.chatCompletion(config, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi")), temperature = 0.7, maxTokens = 100))
        }

        try {
            assertTrue("Write should have started", fakeConn.awaitWriteStarted(5_000))
            workJob.cancel()
            assertTrue("disconnect observed before join", fakeConn.awaitDisconnect(5_000))
            withTimeout(5_000) { workJob.join() }
            assertTrue("Job cancelled", workJob.isCancelled)
        } finally {
            fakeConn.releaseAll()
        }
    }

    @Test
    fun cancellationDoesNotProduceAiProviderClientException() = runBlocking {
        val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
        fakeConn.configure(responseCode = 200, responseBody = "x".repeat(1000).toByteArray(Charsets.UTF_8))
        fakeConn.blockReads = true

        val config = makeConfig()
        val client = OpenAiCompatibleClient(secretStore = FakeSecretStore("valid-key"), connectionFactory = { fakeConn })

        var caughtException: Throwable? = null
        val workJob = launch(Dispatchers.IO) {
            try {
                client.chatCompletion(config, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi")), temperature = 0.7, maxTokens = 100))
            } catch (e: Throwable) {
                caughtException = e
                throw e
            }
        }

        try {
            assertTrue("Read should have started", fakeConn.awaitReadStarted(5_000))
            workJob.cancel()
            assertTrue("disconnect observed before join", fakeConn.awaitDisconnect(5_000))
            withTimeout(5_000) { workJob.join() }
            assertTrue("Job cancelled", workJob.isCancelled)
            assertFalse("Should not be AiProviderClientException", caughtException is AiProviderClientException)
        } finally {
            fakeConn.releaseAll()
        }
    }

    // ── Cancellation-versus-success race (deterministic handoff) ────

    @Test
    fun cancellationVersusSuccess_race_successCanWin() = runBlocking {
        val body = """{"id":"race-1","model":"test","choices":[{"message":{"content":"win"}}]}"""
        val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
        fakeConn.configure(responseCode = 200, responseBody = body.toByteArray(Charsets.UTF_8))

        val reachedGate = CountDownLatch(1)
        val proceedGate = CountDownLatch(1)
        val config = makeConfig()
        val client = OpenAiCompatibleClient(secretStore = FakeSecretStore("valid-key"), connectionFactory = { fakeConn })
        client.onBeforeCompletion = {
            reachedGate.countDown()
            proceedGate.await()
        }

        var capturedResponse: AiChatResponse? = null
        val workJob = launch(Dispatchers.IO) {
            capturedResponse = client.chatCompletion(config, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi")), temperature = 0.7, maxTokens = 100))
        }

        try {
            assertTrue("reached gate", reachedGate.await(5_000, java.util.concurrent.TimeUnit.MILLISECONDS))
            proceedGate.countDown()
            withTimeout(5_000) { workJob.join() }
            assertFalse("Job should complete successfully", workJob.isCancelled)
            assertEquals("win", capturedResponse!!.content)
            assertTrue("disconnect should be called", fakeConn.disconnectCalled)
        } finally {
            proceedGate.countDown()
            fakeConn.releaseAll()
        }
    }

    @Test
    fun cancellationVersusSuccess_race_cancellationCanWin() = runBlocking {
        val body = """{"id":"race-2","model":"test","choices":[{"message":{"content":"data"}}]}"""
        val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
        fakeConn.configure(responseCode = 200, responseBody = body.toByteArray(Charsets.UTF_8))

        val reachedGate = CountDownLatch(1)
        val proceedGate = CountDownLatch(1)
        val config = makeConfig()
        val client = OpenAiCompatibleClient(secretStore = FakeSecretStore("valid-key"), connectionFactory = { fakeConn })
        client.onBeforeCompletion = {
            reachedGate.countDown()
            proceedGate.await()
        }

        var caughtException: Throwable? = null
        val workJob = launch(Dispatchers.IO) {
            try {
                client.chatCompletion(config, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi")), temperature = 0.7, maxTokens = 100))
            } catch (e: Throwable) {
                caughtException = e
                throw e
            }
        }

        try {
            assertTrue("reached gate", reachedGate.await(5_000, java.util.concurrent.TimeUnit.MILLISECONDS))
            workJob.cancel()
            assertTrue("disconnect observed before join", fakeConn.awaitDisconnect(5_000))
            proceedGate.countDown()
            withTimeout(5_000) { workJob.join() }
            assertTrue("Job cancelled", workJob.isCancelled)
            assertTrue("Should be CancellationException", caughtException is kotlinx.coroutines.CancellationException)
            assertFalse("Should not be AiProviderClientException", caughtException is AiProviderClientException)
        } finally {
            proceedGate.countDown()
            fakeConn.releaseAll()
        }
    }

    @Test
    fun cancellationVersusSuccess_noDoubleResume_noIllegalStateException() = runBlocking {
        val body = """{"id":"race-3","model":"test","choices":[{"message":{"content":"ok"}}]}"""
        val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
        fakeConn.configure(responseCode = 200, responseBody = body.toByteArray(Charsets.UTF_8))

        val config = makeConfig()
        val client = OpenAiCompatibleClient(secretStore = FakeSecretStore("valid-key"), connectionFactory = { fakeConn })

        val job = launch(Dispatchers.IO) {
            client.chatCompletion(config, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi")), temperature = 0.7, maxTokens = 100))
        }

        try {
            job.cancel()
            withTimeout(5_000) { job.join() }
            assertTrue("Job cancelled without IllegalStateException", job.isCancelled)
        } finally {
            fakeConn.releaseAll()
        }
    }

    // ── Error handling tests ──────────────────────────────────────────

    @Test
    fun genuineIOException_mapsToNetworkError() {
        val fakeConn = object : FakeHttpURLConnection(URL("https://example.com/chat/completions")) {
            override fun getInputStream(): InputStream = throw IOException("Genuine network failure")
        }

        val config = makeConfig()
        val client = OpenAiCompatibleClient(secretStore = FakeSecretStore("valid-key"), connectionFactory = { fakeConn })

        val ex = org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
            runBlocking {
                client.chatCompletion(config, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi")), temperature = 0.7, maxTokens = 100))
            }
        }
        assertEquals("네트워크 연결에 실패했습니다. 인터넷 연결과 Base URL을 확인해 주세요.", ex.message)
    }

    @Test
    fun cancellationException_isNeverSwallowed() = runBlocking {
        val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
        fakeConn.configure(responseCode = 200, responseBody = """{"id":"1","model":"test","choices":[{"message":{"content":"hi"}}]}""".toByteArray())

        val reachedGate = CountDownLatch(1)
        val proceedGate = CountDownLatch(1)
        val config = makeConfig()
        val client = OpenAiCompatibleClient(secretStore = FakeSecretStore("valid-key"), connectionFactory = { fakeConn })
        client.onBeforeCompletion = {
            reachedGate.countDown()
            proceedGate.await()
        }

        var caughtException: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                client.chatCompletion(config, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi")), temperature = 0.7, maxTokens = 100))
            } catch (error: Throwable) {
                caughtException = error
                throw error
            }
        }
        try {
            assertTrue("request reached response completion gate", reachedGate.await(5_000, java.util.concurrent.TimeUnit.MILLISECONDS))
            job.cancel()
            assertTrue("cancellation disconnects the active request", fakeConn.awaitDisconnect(5_000))
            proceedGate.countDown()
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue(caughtException is kotlinx.coroutines.CancellationException)
            assertFalse(caughtException is AiProviderClientException)
        } finally {
            proceedGate.countDown()
            fakeConn.releaseAll()
        }
    }

    @Test
    fun validationErrors_throwBeforeConnection() {
        val client = OpenAiCompatibleClient(
            secretStore = FakeSecretStore("key"),
            connectionFactory = { throw AssertionError("Should not create connection") }
        )
        val config = AiProviderConfig(id = "test", type = AiProviderType.OPENAI, displayName = "Test", baseUrl = "", modelId = "test-model", apiKeySecretId = "test-key")
        val ex = org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
            runBlocking {
                client.chatCompletion(config, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi")), temperature = 0.7, maxTokens = 100))
            }
        }
        assertEquals("Base URL을 입력해 주세요.", ex.message)
    }

    @Test
    fun legacyHttpEndpoint_rejectedBeforeCredentialsOrConnection() {
        val countingStore = object : SecretStore {
            var getSecretCalls = 0
            override suspend fun getSecret(id: String): String? {
                getSecretCalls++
                return "legacy-key"
            }
            override suspend fun putSecret(id: String, value: String): Boolean = true
            override suspend fun deleteSecret(id: String): Boolean = true
        }
        val client = OpenAiCompatibleClient(
            secretStore = countingStore,
            connectionFactory = { throw AssertionError("Should not open connection for HTTP endpoint") },
        )
        // Legacy persisted metadata predating save-time validation: plain HTTP.
        val config = AiProviderConfig(
            id = "legacy",
            type = AiProviderType.OPENAI,
            displayName = "Legacy",
            baseUrl = "http://legacy.example.test/v1",
            modelId = "test-model",
            apiKeySecretId = "legacy-key-id",
        )
        val ex = org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
            runBlocking {
                client.chatCompletion(config, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi")), temperature = 0.7, maxTokens = 100))
            }
        }
        assertEquals("보안 전송(HTTPS) 엔드포인트만 지원합니다.", ex.message)
        assertEquals("credentials must never be read for a rejected transport", 0, countingStore.getSecretCalls)
    }

    @Test
    fun successfulResponse_parsesContent() {
        val body = """{"id":"chatcmpl-123","model":"gpt-4","choices":[{"message":{"content":"Hello!"}}]}"""
        val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
        fakeConn.configure(responseCode = 200, responseBody = body.toByteArray(Charsets.UTF_8))

        val config = makeConfig()
        val client = OpenAiCompatibleClient(secretStore = FakeSecretStore("valid-key"), connectionFactory = { fakeConn })

        val response = runBlocking {
            client.chatCompletion(config, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi")), temperature = 0.7, maxTokens = 100))
        }
        assertEquals("Hello!", response.content)
        assertEquals("chatcmpl-123", response.id)
        assertEquals("gpt-4", response.model)
    }

    @Test
    fun reasoningResponse_isKeptSeparateFromFinalContent() {
        val body = """{"id":"r-1","model":"test","choices":[{"message":{"content":"answer","reasoning_content":"private plan"}}]}"""
        val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
        fakeConn.configure(responseCode = 200, responseBody = body.toByteArray(Charsets.UTF_8))

        val response = runBlocking {
            OpenAiCompatibleClient(
                secretStore = FakeSecretStore("valid-key"),
                connectionFactory = { fakeConn },
            ).chatCompletion(
                makeConfig().copy(reasoningContentEnabled = true),
                AiChatRequest(listOf(AiMessage(AiRole.USER, "hi"))),
            )
        }
        assertEquals("answer", response.content)
        assertEquals("private plan", response.reasoningContent)
    }

    @Test
    fun reasoningOnlyResponse_doesNotBecomeFinalAnswer() {
        val body = """{"id":"r-2","model":"test","choices":[{"message":{"reasoningContent":"private plan"}}]}"""
        val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
        fakeConn.configure(responseCode = 200, responseBody = body.toByteArray(Charsets.UTF_8))

        val response = runBlocking {
            OpenAiCompatibleClient(
                secretStore = FakeSecretStore("valid-key"),
                connectionFactory = { fakeConn },
            ).chatCompletion(
                makeConfig().copy(reasoningContentEnabled = true),
                AiChatRequest(listOf(AiMessage(AiRole.USER, "hi"))),
            )
        }
        assertEquals("", response.content)
        assertEquals("private plan", response.reasoningContent)
    }

    @Test
    fun reasoningField_isIgnoredWhenProviderReasoningIsDisabled() {
        val body = """{"id":"r-3","model":"test","choices":[{"message":{"content":"answer","reasoning_content":"must ignore"}}]}"""
        val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
        fakeConn.configure(responseCode = 200, responseBody = body.toByteArray(Charsets.UTF_8))

        val response = runBlocking {
            OpenAiCompatibleClient(
                secretStore = FakeSecretStore("valid-key"),
                connectionFactory = { fakeConn },
            ).chatCompletion(
                makeConfig(),
                AiChatRequest(listOf(AiMessage(AiRole.USER, "hi"))),
            )
        }
        assertEquals("answer", response.content)
        assertEquals(null, response.reasoningContent)
    }

    @Test
    fun malformedJson_whileActive_mapsToProviderError() {
        val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
        fakeConn.configure(responseCode = 200, responseBody = "not json".toByteArray())

        val config = makeConfig()
        val client = OpenAiCompatibleClient(secretStore = FakeSecretStore("valid-key"), connectionFactory = { fakeConn })

        val ex = org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
            runBlocking {
                client.chatCompletion(config, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi")), temperature = 0.7, maxTokens = 100))
            }
        }
        assertEquals("외부 AI API 응답을 처리할 수 없습니다.", ex.message)
    }

    // ── Helpers ───────────────────────────────────────────────────────

    @Test
    fun diagnosticBody_is_bounded_and_reports_truncation() = runBlocking {
        val result = OpenAiCompatibleClient.readDiagnosticBody(
            ByteArrayInputStream("x".repeat(101).toByteArray()),
            100,
            ctx(),
        )
        assertEquals(100, result.text.toByteArray().size)
        assertTrue(result.truncated)
    }

    @Test
    fun oversizedErrorBodies_preserveChatStatusSemantics() {
        val cases = listOf(
            401 to "인증에 실패했습니다.",
            403 to "인증에 실패했습니다.",
            429 to "요청 한도를 초과했습니다.",
            500 to "서버에 일시적인 문제가 있습니다.",
        )
        cases.forEach { (status, expected) ->
            val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
            fakeConn.configure(responseCode = status, errorBody = "x".repeat(OpenAiCompatibleClient.MaxErrorBodyBytes + 1).toByteArray())
            val ex = org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
                runBlocking {
                    OpenAiCompatibleClient(FakeSecretStore("valid-key"), connectionFactory = { fakeConn })
                        .chatCompletion(makeConfig(), AiChatRequest(listOf(AiMessage(AiRole.USER, "hi"))))
                }
            }
            assertTrue("status $status should retain its classification", ex.message.orEmpty().contains(expected))
        }
    }

    @Test
    fun oversizedUnknownErrorBody_preservesStatusCodeFallback() {
        val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
        fakeConn.configure(responseCode = 418, errorBody = "x".repeat(OpenAiCompatibleClient.MaxErrorBodyBytes + 1).toByteArray())
        val ex = org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
            runBlocking {
                OpenAiCompatibleClient(FakeSecretStore("valid-key"), connectionFactory = { fakeConn })
                    .chatCompletion(makeConfig(), AiChatRequest(listOf(AiMessage(AiRole.USER, "hi"))))
            }
        }
        assertTrue(ex.message.orEmpty().contains("상태 코드: 418"))
    }

    @Test
    fun providerErrorExtraction_supportsCommonNonBlankShapes() {
        val bodies = listOf(
            """{"error":{"message":"nested"}}""" to "nested",
            """{"message":"top-level"}""" to "top-level",
            """{"error":"string error"}""" to "string error",
            """{"detail":"detail text"}""" to "detail text",
        )
        bodies.forEach { (body, expected) ->
            val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
            fakeConn.configure(responseCode = 400, errorBody = body.toByteArray())
            val ex = org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
                runBlocking {
                    OpenAiCompatibleClient(FakeSecretStore("valid-key"), connectionFactory = { fakeConn })
                        .chatCompletion(makeConfig(), AiChatRequest(listOf(AiMessage(AiRole.USER, "hi"))))
                }
            }
            assertTrue(ex.message.orEmpty().contains(expected))
        }
    }

    @Test
    fun providerErrorExtraction_blanksMalformedHtmlAndSecrets_fallBackSafely() {
        val cases = listOf(
            """{"error":""}""",
            "not json",
            "<html><body>bad</body></html>",
        )
        cases.forEach { body ->
            val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
            fakeConn.configure(responseCode = 418, errorBody = body.toByteArray())
            val ex = org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
                runBlocking {
                    OpenAiCompatibleClient(FakeSecretStore("valid-key"), connectionFactory = { fakeConn })
                        .chatCompletion(makeConfig(), AiChatRequest(listOf(AiMessage(AiRole.USER, "hi"))))
                }
            }
            assertTrue(ex.message.orEmpty().contains("상태 코드: 418"))
        }

        val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
        fakeConn.configure(
            responseCode = 400,
            errorBody = """{"message":"Bearer secret-key Authorization: top-secret"}""".toByteArray(),
        )
        val ex = org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
            runBlocking {
                OpenAiCompatibleClient(FakeSecretStore("valid-key"), connectionFactory = { fakeConn })
                    .chatCompletion(makeConfig(), AiChatRequest(listOf(AiMessage(AiRole.USER, "hi"))))
            }
        }
        assertFalse(ex.message.orEmpty().contains("secret-key"))
        assertFalse(ex.message.orEmpty().contains("top-secret"))
        assertTrue(ex.message.orEmpty().contains("[redacted]"))
    }

    @Test
    fun providerDiagnostic_isHardBounded() {
        val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
        fakeConn.configure(
            responseCode = 400,
            errorBody = """{"message":"${"x".repeat(500)}"}""".toByteArray(),
        )
        val ex = org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
            runBlocking {
                OpenAiCompatibleClient(FakeSecretStore("valid-key"), connectionFactory = { fakeConn })
                    .chatCompletion(makeConfig(), AiChatRequest(listOf(AiMessage(AiRole.USER, "hi"))))
            }
        }
        assertTrue(ex.message.orEmpty().length < 260)
    }

    @Test
    fun connectionSetupFailures_areNormalizedWithoutRawExceptionText() {
        val ioFailure = org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
            runBlocking {
                OpenAiCompatibleClient(
                    FakeSecretStore("valid-key"),
                    connectionFactory = { throw IOException("secret setup detail") },
                ).chatCompletion(makeConfig(), AiChatRequest(listOf(AiMessage(AiRole.USER, "hi"))))
            }
        }
        assertTrue(ioFailure.message.orEmpty().contains("네트워크 연결에 실패"))
        assertFalse(ioFailure.message.orEmpty().contains("secret setup detail"))

        val setupFailure = org.junit.Assert.assertThrows(AiProviderClientException::class.java) {
            runBlocking {
                OpenAiCompatibleClient(
                    FakeSecretStore("valid-key"),
                    connectionFactory = { throw IllegalStateException("raw setup detail") },
                ).chatCompletion(makeConfig(), AiChatRequest(listOf(AiMessage(AiRole.USER, "hi"))))
            }
        }
        assertTrue(setupFailure.message.orEmpty().contains("연결을 준비하지 못했습니다"))
        assertFalse(setupFailure.message.orEmpty().contains("raw setup detail"))
    }

    @Test
    fun requestJson_usesOnlyMaxCompletionTokensForOpenAiProfile() {
        val config = makeConfig().copy(modelId = "gpt-5.6-sol")
        val snapshot = ExternalProviderRequestSnapshot.from(
            config,
            100,
            compatibilityResolver = { _, _ -> ExternalRequestCompatibility(ExternalOutputTokenField.MAX_COMPLETION_TOKENS) },
        )
        val json = sendAndReadJson(config, snapshot, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi")), maxTokens = 100))

        assertEquals(100, json.getInt("max_completion_tokens"))
        assertFalse(json.has("max_tokens"))
    }

    @Test
    fun requestJson_usesOnlyMaxTokensForGenericFallback() {
        val config = makeConfig().copy(type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE, modelId = "my-gpt-5.6-sol-copy")
        val snapshot = ExternalProviderRequestSnapshot.from(
            config,
            100,
            compatibilityResolver = { _, _ -> ExternalRequestCompatibility.FALLBACK },
        )
        val json = sendAndReadJson(config, snapshot, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi")), maxTokens = 100))

        assertEquals(100, json.getInt("max_tokens"))
        assertFalse(json.has("max_completion_tokens"))
    }

    @Test
    fun requestJson_omitsOutputLimitWhenNoConfiguredMaxTokens() {
        val config = makeConfig().copy(maxTokens = null)
        val snapshot = ExternalProviderRequestSnapshot.from(config, 100, compatibilityResolver = { _, _ -> ExternalRequestCompatibility.FALLBACK })
        val json = sendAndReadJson(config, snapshot, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi"))))

        assertFalse(json.has("max_tokens"))
        assertFalse(json.has("max_completion_tokens"))
    }

    @Test
    fun requestJson_omitsTemperatureForTypedOmitProfile() {
        val config = makeConfig()
        val snapshot = ExternalProviderRequestSnapshot.from(
            config,
            100,
            compatibilityResolver = { _, _ -> ExternalRequestCompatibility(temperaturePolicy = ExternalTemperaturePolicy.OMIT) },
        )
        val json = sendAndReadJson(config, snapshot, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi"))))

        assertFalse(json.has("temperature"))
    }

    @Test
    fun requestJson_preservesReasoningAlongsideTypedOutputField() {
        val config = makeConfig()
        val snapshot = ExternalProviderRequestSnapshot.from(
            config,
            100,
            compatibilityResolver = { _, _ -> ExternalRequestCompatibility(ExternalOutputTokenField.MAX_COMPLETION_TOKENS) },
        ).copy(resolvedRequestPatch = ExternalRequestPatch(reasoningEffort = ExternalReasoningEffort.HIGH))
        val json = sendAndReadJson(config, snapshot, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi")), maxTokens = 100))

        assertEquals(100, json.getInt("max_completion_tokens"))
        assertEquals("high", json.getString("reasoning_effort"))
        assertFalse(json.has("max_tokens"))
    }

    private fun makeConfig() = AiProviderConfig(
        id = "test",
        type = AiProviderType.OPENAI,
        displayName = "Test",
        baseUrl = "https://example.com/",
        modelId = "test-model",
        apiKeySecretId = "test-key"
    )

    private fun sendAndReadJson(
        config: AiProviderConfig,
        snapshot: ExternalProviderRequestSnapshot,
        request: AiChatRequest,
    ): JSONObject {
        val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
        fakeConn.configure(responseBody = """{"id":"1","choices":[{"message":{"content":"ok"}}]}""".toByteArray())
        runBlocking {
            OpenAiCompatibleClient(FakeSecretStore("valid-key"), connectionFactory = { fakeConn })
                .chatCompletion(config, request, requestId = null, providerRequestSnapshot = snapshot)
        }
        return JSONObject(String(fakeConn.sentBytes(), Charsets.UTF_8))
    }

    private class FakeSecretStore(private val key: String?) : SecretStore {
        override suspend fun getSecret(id: String): String? = key
        override suspend fun putSecret(id: String, value: String): Boolean = true
        override suspend fun deleteSecret(id: String): Boolean = true
    }
}
