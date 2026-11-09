package com.projectnuke.fusion.knowledge

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIMatcher
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedWebClipHttpTransportTest {
    private val publicA = InetAddress.getByName("93.184.216.34")
    private val publicB = InetAddress.getByName("1.1.1.1")
    private val publicV6 = InetAddress.getByName("2606:4700:4700::1111")

    @Test
    fun `real OkHttp client connects using the exact approved DNS answer`() = runBlocking {
        val server = HttpTestServer { request ->
            assertEquals("GET", request.method)
            assertEquals("/article", request.target)
            HttpTestReply(body = "<html><body>guarded body</body></html>".toByteArray())
        }
        server.use {
            val resolver = recordingResolver { arrayOf(publicA) }
            val sockets = RecordingTestSocketFactory(server.port)
            val response = transport(server, resolver, sockets).fetch("http://article.example.test:${server.port}/article")

            assertEquals("<html><body>guarded body</body></html>", response.body)
            assertEquals("article.example.test:${server.port}", server.requests.single().headers["host"])
            assertEquals(listOf(publicA), sockets.connectTargets.map { it.address })
            assertTrue("DNS must be checked before request emission and again by OkHttp's DNS callback", resolver.calls.get() >= 2)
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `real OkHttp client connects using an approved IPv6 answer`() = runBlocking {
        val server = HttpTestServer { HttpTestReply(body = "<p>IPv6 route</p>".toByteArray()) }
        server.use {
            val sockets = RecordingTestSocketFactory(server.port)
            val response = transport(server, recordingResolver { arrayOf(publicV6) }, sockets)
                .fetch("http://ipv6.example.test:${server.port}/")

            assertEquals(200, response.statusCode)
            assertEquals(listOf(publicV6), sockets.connectTargets.map { it.address })
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `public preflight followed by private OkHttp DNS answer emits no socket`() = runBlocking {
        val server = HttpTestServer { HttpTestReply() }
        server.use {
            val resolutions = AtomicInteger()
            val resolver = WebClipAddressResolver {
                if (resolutions.incrementAndGet() == 1) arrayOf(publicA)
                else arrayOf(InetAddress.getByName("10.20.30.40"))
            }
            val sockets = RecordingTestSocketFactory(server.port)

            val failure = runCatching {
                transport(server, resolver, sockets).fetch("http://rebound.example.test:${server.port}/")
            }.exceptionOrNull()

            assertTrue(failure is GuardedWebClipTransportException)
            assertEquals(GuardedWebClipFailure.DISALLOWED_ADDRESS, (failure as GuardedWebClipTransportException).reason)
            assertEquals(0, sockets.connectTargets.size)
            assertEquals(0, server.requests.size)
        }
    }

    @Test
    fun `mixed DNS answers reject the entire destination set before connection`() = runBlocking {
        val server = HttpTestServer { HttpTestReply() }
        server.use {
            val sockets = RecordingTestSocketFactory(server.port)
            val resolver = WebClipAddressResolver {
                arrayOf(publicA, InetAddress.getByName("192.168.9.7"), InetAddress.getByName("fd00::7"))
            }

            val failure = runCatching {
                transport(server, resolver, sockets).fetch("http://mixed.example.test:${server.port}/")
            }.exceptionOrNull()

            assertTrue(failure is GuardedWebClipTransportException)
            assertEquals(GuardedWebClipFailure.DISALLOWED_ADDRESS, (failure as GuardedWebClipTransportException).reason)
            assertTrue(sockets.connectTargets.isEmpty())
        }
    }

    @Test
    fun `reserved IPv6 and Azure platform service destinations are rejected before connection`() = runBlocking {
        val server = HttpTestServer { HttpTestReply() }
        server.use {
            val sockets = RecordingTestSocketFactory(server.port)
            val reservedIpv6 = InetAddress.getByName("5f00::1")
            val azurePlatformService = InetAddress.getByName("168.63.129.16")
            val resolver = WebClipAddressResolver { host ->
                when (host) {
                    "reserved-v6.example.test" -> arrayOf(reservedIpv6)
                    "azure-platform.example.test" -> arrayOf(azurePlatformService)
                    else -> arrayOf(publicA)
                }
            }

            listOf("reserved-v6.example.test", "azure-platform.example.test").forEach { host ->
                val failure = runCatching {
                    transport(server, resolver, sockets).fetch("http://$host:${server.port}/")
                }.exceptionOrNull()

                assertTrue("$host must be denied before connection", failure is GuardedWebClipTransportException)
                assertEquals(GuardedWebClipFailure.DISALLOWED_ADDRESS, (failure as GuardedWebClipTransportException).reason)
            }

            assertTrue(sockets.connectTargets.isEmpty())
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `numeric private mapped and NAT64 literals bypass DNS but are rejected before socket`() = runBlocking {
        val server = HttpTestServer { HttpTestReply() }
        server.use {
            val resolutions = AtomicInteger()
            val resolver = WebClipAddressResolver {
                resolutions.incrementAndGet()
                arrayOf(publicA)
            }
            val sockets = RecordingTestSocketFactory(server.port)
            val client = transport(server, resolver, sockets)
            val port = server.port

            listOf(
                "http://127.0.0.1:$port/private",
                "http://[::ffff:10.0.0.1]:$port/mapped",
                "http://[64:ff9b::a00:1]:$port/nat64",
                "http://[fc00::1]:$port/ula",
                "http://[fe80::1]:$port/link-local",
            ).forEach { url ->
                val failure = runCatching { client.fetch(url) }.exceptionOrNull()
                assertTrue("$url must be blocked before connecting", failure is GuardedWebClipTransportException)
                assertEquals(GuardedWebClipFailure.DISALLOWED_ADDRESS, (failure as GuardedWebClipTransportException).reason)
            }

            assertEquals("numeric literals do not rely on OkHttp's DNS callback", 0, resolutions.get())
            assertTrue(sockets.connectTargets.isEmpty())
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `private redirect is rejected before opening its destination`() = runBlocking {
        val serverRef = AtomicReference<HttpTestServer>()
        val server = HttpTestServer { request ->
            if (request.target == "/start") {
                HttpTestReply(
                    status = 302,
                    headers = mapOf("Location" to "http://127.0.0.1:${serverRef.get().port}/private"),
                    body = byteArrayOf(),
                )
            } else {
                HttpTestReply()
            }
        }
        serverRef.set(server)
        server.use {
            val sockets = RecordingTestSocketFactory(server.port)
            val client = transport(server, recordingResolver { arrayOf(publicA) }, sockets)
            val failure = runCatching {
                client.fetch("http://public.example.test:${server.port}/start")
            }.exceptionOrNull()

            assertTrue(failure is GuardedWebClipTransportException)
            assertEquals(GuardedWebClipFailure.DISALLOWED_ADDRESS, (failure as GuardedWebClipTransportException).reason)
            assertEquals(1, server.requests.size)
            assertEquals(1, sockets.connectTargets.size)
        }
    }

    @Test
    fun `relative and multiple public redirects resolve and authorize each hop`() = runBlocking {
        val serverRef = AtomicReference<HttpTestServer>()
        val server = HttpTestServer { request ->
            when (request.target) {
                "/start" -> HttpTestReply(status = 302, headers = mapOf("Location" to "/relative"), body = byteArrayOf())
                "/relative" -> HttpTestReply(
                    status = 307,
                    headers = mapOf("Location" to "http://second.example.test:${serverRef.get().port}/final"),
                    body = byteArrayOf(),
                )
                else -> HttpTestReply(body = "<html><body>final source</body></html>".toByteArray())
            }
        }
        serverRef.set(server)
        server.use {
            val resolved = mutableListOf<String>()
            val resolver = WebClipAddressResolver { host ->
                resolved += host
                arrayOf(publicA)
            }
            val sockets = RecordingTestSocketFactory(server.port)
            val response = transport(server, resolver, sockets)
                .fetch("http://first.example.test:${server.port}/start")

            assertEquals("http://second.example.test:${server.port}/final", response.finalUrl)
            assertTrue(response.body.contains("final source"))
            assertEquals(listOf("/start", "/relative", "/final"), server.requests.map { it.target })
            assertTrue("each redirect hop must enter the resolver path", resolved.containsAll(listOf("first.example.test", "second.example.test")))
            assertEquals(3, sockets.connectTargets.size)
            assertTrue(sockets.connectTargets.all { it.address == publicA })
            assertTrue(server.requests.all { request ->
                "authorization" !in request.headers && "cookie" !in request.headers &&
                    "proxy-authorization" !in request.headers
            })
        }
    }

    @Test
    fun `DNS failure has no connection attempt`() = runBlocking {
        val server = HttpTestServer { HttpTestReply() }
        server.use {
            val sockets = RecordingTestSocketFactory(server.port)
            val resolver = WebClipAddressResolver { throw UnknownHostException("controlled DNS failure") }
            val failure = runCatching {
                transport(server, resolver, sockets).fetch("http://missing.example.test:${server.port}/")
            }.exceptionOrNull()

            assertTrue(failure is GuardedWebClipTransportException)
            assertEquals(GuardedWebClipFailure.DISALLOWED_ADDRESS, (failure as GuardedWebClipTransportException).reason)
            assertTrue(sockets.connectTargets.isEmpty())
        }
    }

    @Test
    fun `system HTTP HTTPS SOCKS and proxy selector settings cannot divert the direct route`() = runBlocking {
        val server = HttpTestServer { HttpTestReply() }
        server.use {
            val priorSelector = ProxySelector.getDefault()
            val adversarialSelector = object : ProxySelector() {
                override fun select(uri: java.net.URI): MutableList<Proxy> {
                    return mutableListOf(
                        Proxy(Proxy.Type.HTTP, InetSocketAddress("192.0.2.10", 8080)),
                        Proxy(Proxy.Type.SOCKS, InetSocketAddress("192.0.2.11", 1080)),
                    )
                }

                override fun connectFailed(uri: java.net.URI, sa: SocketAddress, ioe: IOException) = Unit
            }
            val propertyNames = listOf("http.proxyHost", "https.proxyHost", "socksProxyHost", "java.net.useSystemProxies")
            val oldProperties = propertyNames.associateWith { name -> System.getProperty(name) }
            try {
                System.setProperty("http.proxyHost", "192.0.2.10")
                System.setProperty("https.proxyHost", "192.0.2.10")
                System.setProperty("socksProxyHost", "192.0.2.11")
                System.setProperty("java.net.useSystemProxies", "true")
                val sockets = RecordingTestSocketFactory(server.port)
                val client = transport(server, recordingResolver { arrayOf(publicA) }, sockets)
                // Replace the VM selector after client construction. A client that consults the
                // mutable system selector at request time would now see only untrusted proxies.
                ProxySelector.setDefault(adversarialSelector)
                val response = client.fetch("http://proxy-check.example.test:${server.port}/")

                assertEquals(200, response.statusCode)
                assertEquals(listOf(publicA), sockets.connectTargets.map { it.address })
                assertEquals(1, server.requests.size)
            } finally {
                ProxySelector.setDefault(priorSelector)
                oldProperties.forEach { (name, value) ->
                    if (value == null) System.clearProperty(name) else System.setProperty(name, value)
                }
            }
        }
    }

    @Test
    fun `connection failure can only try addresses from the validated DNS answer set`() = runBlocking {
        val server = HttpTestServer { HttpTestReply() }
        server.use {
            val sockets = RecordingTestSocketFactory(server.port, failFirstConnect = true)
            val outcome = runCatching {
                transport(server, recordingResolver { arrayOf(publicA, publicB) }, sockets)
                    .fetch("http://validated-routes.example.test:${server.port}/")
            }

            assertTrue(sockets.connectTargets.isNotEmpty())
            assertTrue(sockets.connectTargets.all { it.address == publicA || it.address == publicB })
            assertTrue("no more route attempts than approved answers", sockets.connectTargets.size <= 2)
            if (outcome.isSuccess) {
                assertEquals(200, outcome.getOrThrow().statusCode)
                assertEquals(2, sockets.connectTargets.size)
            } else {
                assertTrue(outcome.exceptionOrNull() is GuardedWebClipTransportException)
                assertTrue(server.requests.isEmpty())
            }
        }
    }

    @Test
    fun `disabled connection pool creates a fresh approved route for every call`() = runBlocking {
        val server = HttpTestServer { HttpTestReply() }
        server.use {
            val sockets = RecordingTestSocketFactory(server.port)
            val client = transport(server, recordingResolver { arrayOf(publicA) }, sockets)
            client.fetch("http://pooled.example.test:${server.port}/one")
            client.fetch("http://pooled.example.test:${server.port}/two")

            assertEquals(listOf("/one", "/two"), server.requests.map { it.target })
            assertEquals("each request must create a newly authorized direct route", 2, sockets.connectTargets.size)
            assertTrue(sockets.connectTargets.all { it.address == publicA })
        }
    }

    @Test
    fun `cancellation while guarded DNS is pending prevents socket creation`() = runBlocking {
        val server = HttpTestServer { HttpTestReply() }
        server.use {
            val secondResolutionStarted = CountDownLatch(1)
            val allowResolverToReturn = CountDownLatch(1)
            val resolverReturned = CountDownLatch(1)
            val calls = AtomicInteger()
            val cancellationRequested = AtomicBoolean(false)
            val resolver = WebClipAddressResolver {
                if (calls.incrementAndGet() == 1) return@WebClipAddressResolver arrayOf(publicA)
                secondResolutionStarted.countDown()
                allowResolverToReturn.await(5, TimeUnit.SECONDS)
                resolverReturned.countDown()
                if (cancellationRequested.get()) throw UnknownHostException("cancelled before route selection")
                arrayOf(publicA)
            }
            val sockets = RecordingTestSocketFactory(server.port)
            val job = async(Dispatchers.Default) {
                transport(server, resolver, sockets).fetch("http://cancel-dns.example.test:${server.port}/")
            }

            assertTrue(secondResolutionStarted.await(5, TimeUnit.SECONDS))
            cancellationRequested.set(true)
            job.cancelAndJoin()
            allowResolverToReturn.countDown()
            assertTrue(resolverReturned.await(5, TimeUnit.SECONDS))
            assertTrue(sockets.connectTargets.isEmpty())
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `cancellation during response I O closes the owned socket`() = runBlocking {
        val requestSeen = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)
        val server = HttpTestServer {
            requestSeen.countDown()
            allowResponse.await(5, TimeUnit.SECONDS)
            HttpTestReply()
        }
        server.use {
            val sockets = RecordingTestSocketFactory(server.port)
            val job = async(Dispatchers.Default) {
                transport(server, recordingResolver { arrayOf(publicA) }, sockets)
                    .fetch("http://cancel-io.example.test:${server.port}/")
            }

            assertTrue(requestSeen.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue("OkHttp cancellation must close the call-owned socket", sockets.firstSocketClosed.await(5, TimeUnit.SECONDS))
            allowResponse.countDown()
        }
    }

    @Test
    fun `TLS keeps original hostname SNI and certificate hostname verification`() = runBlocking {
        val hostname = "tls.example.test"
        val held = HeldCertificate.Builder()
            .commonName(hostname)
            .addSubjectAlternativeName(hostname)
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val serverContext = serverCertificates.sslContext()
        val sslServer = serverContext.serverSocketFactory.createServerSocket(
            0,
            16,
            InetAddress.getByName("127.0.0.1"),
        ) as SSLServerSocket
        val sniReceived = AtomicReference<String?>()
        val parameters = sslServer.sslParameters
        parameters.sniMatchers = setOf(object : SNIMatcher(0) {
            override fun matches(serverName: SNIServerName): Boolean {
                val actual = (serverName as? SNIHostName)?.asciiName
                sniReceived.set(actual)
                return actual == hostname
            }
        })
        sslServer.sslParameters = parameters
        val server = HttpTestServer(sslServer) { HttpTestReply(body = "<p>secure</p>".toByteArray()) }
        server.use {
            val sockets = RecordingTestSocketFactory(server.port)
            val client = GuardedWebClipHttpTransport.forTest(
                addressResolver = recordingResolver { arrayOf(publicA) },
                socketFactory = sockets,
                allowNonDefaultPorts = true,
                sslSocketFactory = clientCertificates.sslSocketFactory(),
                trustManager = clientCertificates.trustManager,
            )
            val response = client.fetch("https://$hostname:${server.port}/")

            assertEquals(200, response.statusCode)
            assertEquals(hostname, sniReceived.get())
            assertEquals(listOf(publicA), sockets.connectTargets.map { it.address })
        }
    }

    @Test
    fun `TLS hostname mismatch remains rejected even when test CA is trusted`() = runBlocking {
        val held = HeldCertificate.Builder()
            .commonName("wrong.example.test")
            .addSubjectAlternativeName("wrong.example.test")
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val sslServer = serverCertificates.sslContext().serverSocketFactory.createServerSocket(
            0,
            16,
            InetAddress.getByName("127.0.0.1"),
        ) as SSLServerSocket
        val sniReceived = AtomicReference<String?>()
        val parameters = sslServer.sslParameters
        parameters.sniMatchers = setOf(object : SNIMatcher(0) {
            override fun matches(serverName: SNIServerName): Boolean {
                val actual = (serverName as? SNIHostName)?.asciiName
                sniReceived.set(actual)
                return actual == "tls.example.test"
            }
        })
        sslServer.sslParameters = parameters
        val server = HttpTestServer(sslServer) { HttpTestReply() }
        server.use {
            val sockets = RecordingTestSocketFactory(server.port)
            val client = GuardedWebClipHttpTransport.forTest(
                addressResolver = recordingResolver { arrayOf(publicA) },
                socketFactory = sockets,
                allowNonDefaultPorts = true,
                sslSocketFactory = clientCertificates.sslSocketFactory(),
                trustManager = clientCertificates.trustManager,
            )
            val failure = runCatching { client.fetch("https://tls.example.test:${server.port}/") }.exceptionOrNull()

            assertTrue("trusted but wrong-host certificate must fail", failure is GuardedWebClipTransportException)
            assertEquals("TLS SNI must still use the original request host", "tls.example.test", sniReceived.get())
            assertEquals(listOf(publicA), sockets.connectTargets.map { it.address })
        }
    }

    @Test
    fun `body limit accepts exact byte budget and rejects one extra byte`() = runBlocking {
        val exactBody = ByteArray(GuardedWebClipHttpTransport.MAX_SUCCESS_BYTES) { 'x'.code.toByte() }
        val exactServer = HttpTestServer { HttpTestReply(body = exactBody) }
        exactServer.use {
            val response = transport(exactServer, recordingResolver { arrayOf(publicA) }, RecordingTestSocketFactory(exactServer.port))
                .fetch("http://size.example.test:${exactServer.port}/")
            assertEquals(GuardedWebClipHttpTransport.MAX_SUCCESS_BYTES, response.body.length)
        }

        val oversizedServer = HttpTestServer {
            HttpTestReply(body = ByteArray(GuardedWebClipHttpTransport.MAX_SUCCESS_BYTES + 1) { 'x'.code.toByte() })
        }
        oversizedServer.use {
            val failure = runCatching {
                transport(oversizedServer, recordingResolver { arrayOf(publicA) }, RecordingTestSocketFactory(oversizedServer.port))
                    .fetch("http://oversized.example.test:${oversizedServer.port}/")
            }.exceptionOrNull()
            assertTrue(failure is GuardedWebClipTransportException)
            assertEquals(GuardedWebClipFailure.RESPONSE_TOO_LARGE, (failure as GuardedWebClipTransportException).reason)
        }
    }

    @Test
    fun `redirect budget accepts five redirects and rejects the sixth`() = runBlocking {
        val allowedServer = HttpTestServer { request ->
            val step = request.target.substringAfter("step", "0").toIntOrNull() ?: 0
            if (step < GuardedWebClipHttpTransport.MAX_REDIRECTS) {
                HttpTestReply(
                    status = 302,
                    headers = mapOf("Location" to "/step${step + 1}"),
                    body = byteArrayOf(),
                )
            } else HttpTestReply(body = "<p>done</p>".toByteArray())
        }
        allowedServer.use {
            val response = transport(allowedServer, recordingResolver { arrayOf(publicA) }, RecordingTestSocketFactory(allowedServer.port))
                .fetch("http://redirects.example.test:${allowedServer.port}/step0")
            assertEquals("<p>done</p>", response.body)
            assertEquals(GuardedWebClipHttpTransport.MAX_REDIRECTS + 1, allowedServer.requests.size)
        }

        val tooManyServer = HttpTestServer {
            HttpTestReply(status = 302, headers = mapOf("Location" to "/again"), body = byteArrayOf())
        }
        tooManyServer.use {
            val sockets = RecordingTestSocketFactory(tooManyServer.port)
            val failure = runCatching {
                transport(tooManyServer, recordingResolver { arrayOf(publicA) }, sockets)
                    .fetch("http://too-many.example.test:${tooManyServer.port}/")
            }.exceptionOrNull()
            assertTrue(failure is GuardedWebClipTransportException)
            assertEquals(GuardedWebClipFailure.TOO_MANY_REDIRECTS, (failure as GuardedWebClipTransportException).reason)
            assertEquals(GuardedWebClipHttpTransport.MAX_REDIRECTS + 1, tooManyServer.requests.size)
            assertEquals(tooManyServer.requests.size, sockets.connectTargets.size)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun transport(
        server: HttpTestServer,
        resolver: WebClipAddressResolver,
        socketFactory: RecordingTestSocketFactory,
    ) = GuardedWebClipHttpTransport.forTest(
        addressResolver = resolver,
        socketFactory = socketFactory,
        allowNonDefaultPorts = true,
        allowHttpForTests = true,
    )

    private fun recordingResolver(lookup: (String) -> Array<InetAddress>): RecordingResolver = RecordingResolver(lookup)

    private class RecordingResolver(private val lookup: (String) -> Array<InetAddress>) : WebClipAddressResolver {
        val calls = AtomicInteger()
        override fun resolve(host: String): Array<InetAddress> {
            calls.incrementAndGet()
            return lookup(host)
        }
    }

}
