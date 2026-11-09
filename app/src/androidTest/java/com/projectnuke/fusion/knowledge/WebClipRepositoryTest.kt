package com.projectnuke.fusion.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ProjectEntity
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.SocketFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebClipRepositoryTest {
    private lateinit var database: AppDatabase
    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun frozenClipPreservesUrlTitleAndRetrievalTimeAcrossDestinations() = runBlocking {
        val projectId = database.projectDao().insertProject(
            ProjectEntity(name = "Research", instructions = "", createdAt = 1, updatedAt = 1),
        )
        val capture = WebClipCapture.fromGuardedFetch(WebClipDocument(
            originalUrl = "https://example.com/paper",
            pageTitle = "Example paper",
            displayTitle = "Example paper",
            text = "Captured page text",
            textTruncated = false,
            retrievedAt = 77,
            effectiveUrl = "https://content.example.test/final-paper",
        ))
        val repository = WebClipRepository(database)

        val saved = repository.saveToKnowledge(capture, projectId) as WebClipSaveResult.Stored
        val knowledge = database.savedKnowledgeDao().getById(saved.id)
        assertEquals("WEB_CLIP", knowledge?.provenanceType)
        assertEquals("https://example.com/paper", knowledge?.sourceUrl)
        assertEquals("https://content.example.test/final-paper", knowledge?.effectiveSourceUrl)
        assertEquals("Example paper", knowledge?.sourcePageTitle)
        assertEquals("Captured page text", knowledge?.savedText)
        assertEquals(77L, knowledge?.retrievedAt)
        assertEquals(projectId, knowledge?.projectId)

        val inboxResult = repository.saveToInbox(capture) as WebClipSaveResult.Stored
        val inbox = database.inboxItemDao().get(inboxResult.id)
        assertNotNull(inbox)
        assertEquals("WEB_CLIP", inbox?.captureType)
        assertEquals("https://example.com/paper", inbox?.originalUri)
        assertEquals("https://content.example.test/final-paper", inbox?.effectiveSourceUri)
        assertEquals(77L, inbox?.createdAt)
        assertTrue(inbox?.content == "Captured page text")
    }

    @Test
    fun completedGuardedCaptureSavesToBothDestinationsAfterDnsOutage() = runBlocking {
        val resolutions = AtomicInteger()
        val resolver = WebClipAddressResolver {
            if (resolutions.incrementAndGet() > 2) throw java.net.UnknownHostException("DNS outage after response")
            arrayOf(InetAddress.getByName("93.184.216.34"))
        }
        val held = HeldCertificate.Builder().addSubjectAlternativeName("capture.example").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val server = serverCertificates.sslContext().serverSocketFactory.createServerSocket() as SSLServerSocket
        server.bind(InetSocketAddress("127.0.0.1", 0))
        val serverPort = server.localPort
        assertTrue("synthetic HTTPS server must bind an ephemeral loopback port", serverPort in 1..65_535)
        val serverThread = Thread {
            try {
                (server.accept() as SSLSocket).use { socket ->
                    socket.startHandshake()
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    while (reader.readLine().isNotEmpty()) Unit
                    val body = "<html><head><title>Captured</title></head><body><p>Frozen public snapshot</p></body></html>"
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    val output: OutputStream = socket.getOutputStream()
                    output.write(response.toByteArray(StandardCharsets.US_ASCII))
                    output.write(bytes)
                    output.flush()
                }
            } catch (closed: java.net.SocketException) {
                if (!server.isClosed) throw closed
            }
        }.apply { isDaemon = true; start() }
        try {
            val transport = GuardedWebClipHttpTransport.forTest(
                addressResolver = resolver,
                socketFactory = LoopbackRoutingSocketFactory(serverPort),
                allowNonDefaultPorts = true,
                sslSocketFactory = clientCertificates.sslSocketFactory(),
                trustManager = clientCertificates.trustManager,
            )
            val fetcher = WebClipFetcher(transport, allowNonDefaultPorts = true)
            val result = fetcher.fetch("https://capture.example/article")
            assertTrue("guarded transport should finish before DNS outage: $result", result is WebClipFetchResult.Captured)
            assertEquals(2, resolutions.get())
            val capture = (result as WebClipFetchResult.Captured).capture
            val repository = WebClipRepository(database)
            val saved = repository.saveToKnowledge(capture) as WebClipSaveResult.Stored
            val knowledge = database.savedKnowledgeDao().getById(saved.id)
            assertEquals(capture.document.originalUrl, knowledge?.sourceUrl)
            assertEquals(capture.document.effectiveUrl, knowledge?.effectiveSourceUrl)
            assertEquals("Frozen public snapshot", knowledge?.savedText)

            val inboxResult = repository.saveToInbox(capture) as WebClipSaveResult.Stored
            val inbox = database.inboxItemDao().get(inboxResult.id)
            assertEquals(capture.document.originalUrl, inbox?.originalUri)
            assertEquals(capture.document.effectiveUrl, inbox?.effectiveSourceUri)
            assertEquals("Frozen public snapshot", inbox?.content)
            assertEquals("Persistence must not resolve DNS again", 2, resolutions.get())
        } finally {
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun missingProjectTransactionFailsWithoutPersistingSnapshot() = runBlocking {
        val capture = WebClipCapture.fromGuardedFetch(
            WebClipDocument(
                originalUrl = "https://capture.example/article",
                pageTitle = "Captured",
                displayTitle = "Captured",
                text = "Frozen text",
                textTruncated = false,
                retrievedAt = 7L,
                effectiveUrl = "https://capture.example/article",
            ),
        )
        assertEquals(WebClipSaveResult.Failed, WebClipRepository(database).saveToKnowledge(capture, Long.MAX_VALUE))
        assertTrue(database.savedKnowledgeDao().latest(10).isEmpty())
    }

    @Test
    fun forgedCaptureWithPrivateOriginOrEffectiveUrlIsRejectedWithoutPersistence() = runBlocking {
        val repository = WebClipRepository(database)
        val forgedCaptures = listOf(
            WebClipCapture.fromGuardedFetch(
                WebClipDocument(
                    originalUrl = "https://127.0.0.1/private",
                    pageTitle = "Synthetic private source",
                    displayTitle = "Synthetic private source",
                    text = "Synthetic text",
                    textTruncated = false,
                    retrievedAt = 7L,
                ),
            ),
            WebClipCapture.fromGuardedFetch(
                WebClipDocument(
                    originalUrl = "https://public.example/article",
                    pageTitle = "Synthetic private redirect",
                    displayTitle = "Synthetic private redirect",
                    text = "Synthetic text",
                    textTruncated = false,
                    retrievedAt = 8L,
                    effectiveUrl = "https://10.0.0.1/private",
                ),
            ),
        )

        forgedCaptures.forEach { capture ->
            assertEquals(WebClipSaveResult.Invalid, repository.saveToInbox(capture))
            assertEquals(WebClipSaveResult.Invalid, repository.saveToKnowledge(capture))
        }
        assertEquals(0, database.inboxItemDao().count())
        assertTrue(database.savedKnowledgeDao().latest(10).isEmpty())
    }

    private class LoopbackRoutingSocketFactory(private val targetPort: Int) : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                super.connect(InetSocketAddress("127.0.0.1", targetPort), timeout)
            }
        }
        override fun createSocket(host: String, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) = createSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int) = createSocket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
    }
}
