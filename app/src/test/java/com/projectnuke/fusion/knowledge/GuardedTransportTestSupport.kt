package com.projectnuke.fusion.knowledge

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

internal data class HttpTestRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
)

internal data class HttpTestReply(
    val status: Int = 200,
    val headers: Map<String, String> = mapOf("Content-Type" to "text/html; charset=utf-8"),
    val body: ByteArray = "<html><body>Readable page text</body></html>".toByteArray(),
)

/** Local test-only HTTP(S) server. Requests are served by the real OkHttp client. */
internal class HttpTestServer internal constructor(
    private val listener: ServerSocket,
    private val handler: (HttpTestRequest) -> HttpTestReply,
) : Closeable {
    constructor(handler: (HttpTestRequest) -> HttpTestReply) : this(
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")),
        handler,
    )

    val port: Int = listener.localPort
    val requests = CopyOnWriteArrayList<HttpTestRequest>()
    private val activeSockets = CopyOnWriteArrayList<Socket>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "web-clip-http-test").apply { isDaemon = true }
    }
    @Volatile
    private var closed = false

    init {
        executor.execute {
            while (!closed) {
                try {
                    val socket = listener.accept()
                    activeSockets += socket
                    executor.execute { handle(socket) }
                } catch (_: IOException) {
                    if (!closed) throw IllegalStateException("local test server accept failed")
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { accepted ->
            try {
                val input = accepted.getInputStream()
                val requestLine = readLine(input) ?: return
                val headerMap = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) headerMap[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
                val tokens = requestLine.split(' ', limit = 3)
                if (tokens.size < 2) throw IOException("malformed test request line")
                val request = HttpTestRequest(tokens[0], tokens[1], headerMap)
                requests += request
                val reply = handler(request)
                val reason = when (reply.status) {
                    200 -> "OK"
                    302 -> "Found"
                    301 -> "Moved Permanently"
                    303 -> "See Other"
                    307 -> "Temporary Redirect"
                    308 -> "Permanent Redirect"
                    404 -> "Not Found"
                    else -> "Test Response"
                }
                val headers = linkedMapOf<String, String>().apply {
                    putAll(reply.headers)
                    put("Content-Length", reply.body.size.toString())
                    put("Connection", "close")
                }
                val output = accepted.getOutputStream()
                output.write("HTTP/1.1 ${reply.status} $reason\r\n".toByteArray())
                headers.forEach { (name, value) -> output.write("$name: $value\r\n".toByteArray()) }
                output.write("\r\n".toByteArray())
                output.write(reply.body)
                output.flush()
            } catch (_: IOException) {
                // Cancellation tests intentionally close the client while the handler is blocked.
            } finally {
                activeSockets.remove(accepted)
            }
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val bytes = ByteArrayOutputStream()
        var previous = -1
        while (true) {
            val next = input.read()
            if (next < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
            if (previous == '\r'.code && next == '\n'.code) {
                val line = bytes.toByteArray()
                return String(line, 0, (line.size - 1).coerceAtLeast(0), Charsets.ISO_8859_1)
            }
            bytes.write(next)
            previous = next
        }
    }

    override fun close() {
        closed = true
        runCatching { listener.close() }
        activeSockets.forEach { runCatching { it.close() } }
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}

/** Records OkHttp's resolved Socket.connect target, then tunnels only to the local test server. */
internal open class RecordingTestSocketFactory(
    private val localServerPort: Int,
    private val failFirstConnect: Boolean = false,
) : SocketFactory() {
    val connectTargets = CopyOnWriteArrayList<InetSocketAddress>()
    val sockets = CopyOnWriteArrayList<RecordingSocket>()
    val firstSocketClosed = CountDownLatch(1)

    override fun createSocket(): Socket = RecordingSocket().also(sockets::add)

    override fun createSocket(host: String, port: Int): Socket = createSocket().apply {
        connect(InetSocketAddress(host, port))
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply {
        connect(InetSocketAddress(host, port))
    }

    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    inner class RecordingSocket : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val requested = endpoint as? InetSocketAddress ?: throw IOException("unexpected unresolved route")
            connectTargets += requested
            if (failFirstConnect && connectTargets.size == 1) throw IOException("injected first-route failure")
            super.connect(InetSocketAddress("127.0.0.1", localServerPort), timeout)
        }

        override fun close() {
            try {
                super.close()
            } finally {
                firstSocketClosed.countDown()
            }
        }
    }
}
