package com.webviewdp

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Serves the harness UI to the WebView from a loopback origin on this device.
 *
 * Why this exists: the harness classifies a page as the operator by where its
 * document came from. A page the harness serves itself over the tailnet is not
 * loopback, so it gets no durable settings; a page served from 127.0.0.1 is, so
 * it does. This listener is that origin. It relays every request to the real
 * harness over Tailscale, presents the tailnet authority in Host and Origin so
 * the harness's own request fence is satisfied, and attaches the session cookie
 * the app holds. Nothing is cached, rewritten beyond those headers, or exposed
 * to the network: the socket is bound to 127.0.0.1 and exists only for this
 * app's WebView.
 */
class LoopbackProxy(
    private val upstream: HttpUrl,
    private val client: OkHttpClient,
    private val cookieHeader: (HttpUrl) -> String?,
    private val onAuthFailure: () -> Unit,
) {

    private var server: ServerSocket? = null
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "webviewdp-proxy").apply { isDaemon = true }
    }

    var port: Int = 0
        private set

    fun start(): Int {
        val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = socket
        port = socket.localPort
        workers.execute {
            while (true) {
                val connection = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                workers.execute { serve(connection) }
            }
        }
        Log.i(TAG, "serving the harness at http://127.0.0.1:$port/")
        return port
    }

    fun stop() {
        try {
            server?.close()
        } catch (e: IOException) {
            Log.w(TAG, "closing the listener failed: ${e.message}")
        }
        server = null
        workers.shutdownNow()
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = REQUEST_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 3) {
                writeError(output, 400, "bad request line")
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator <= 0) continue
                val name = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim()
                headers[name] = headers[name]?.let { "$it, $value" } ?: value
            }
            if (headers["upgrade"]?.contains("websocket", ignoreCase = true) == true) {
                bridgeWebSocket(socket, method, target, headers)
            } else {
                relay(input, output, method, target, headers)
            }
        } catch (e: Exception) {
            Log.w(TAG, "connection failed: ${e.message}")
        } finally {
            closeQuietly(socket)
        }
    }

    private fun relay(
        input: InputStream,
        output: BufferedOutputStream,
        method: String,
        target: String,
        headers: Map<String, String>,
    ) {
        val url = upstreamUrl(target)
        if (url == null) {
            writeError(output, 400, "bad request target")
            return
        }
        val body = requestBody(input, headers)
        val builder = Request.Builder().url(url)
        for ((name, value) in headers) {
            when {
                // The app is the client here: it supplies the authority markers
                // and the cookie, and never forwards the page's own.
                name == "host" || name == "origin" || name == "cookie" -> Unit
                // Framing belongs to the body OkHttp is about to write.
                name == "content-length" || name == "transfer-encoding" -> Unit
                // Browser markers describe a fetch from the page, not this hop.
                name.startsWith("sec-fetch") || name == "expect" -> Unit
                name in HOP_BY_HOP -> Unit
                else -> builder.header(name, value)
            }
        }
        builder.header("Origin", origin)
        if (!headers.containsKey("accept-encoding")) builder.header("Accept-Encoding", "identity")
        builder.method(
            method,
            body ?: if (method in METHODS_REQUIRING_BODY) EMPTY_BODY else null,
        )
        try {
            client.newCall(builder.build()).execute().use { response ->
                if (response.code == 401) onAuthFailure()
                writeResponse(output, response)
            }
        } catch (e: IOException) {
            Log.w(TAG, "$method $target failed: ${e.message}")
            writeError(output, 502, "the harness is unreachable")
        }
    }

    /** A WebSocket upgrade is relayed byte for byte: handshake, frames, close. */
    private fun bridgeWebSocket(
        socket: Socket,
        method: String,
        target: String,
        headers: Map<String, String>,
    ) {
        val url = upstreamUrl(target) ?: return
        val remote = connect(url) ?: run {
            writeError(BufferedOutputStream(socket.getOutputStream()), 502, "the harness is unreachable")
            return
        }
        try {
            val handshake = StringBuilder()
            val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
            handshake.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            for ((name, value) in headers) {
                when {
                    name == "host" || name == "origin" || name == "cookie" -> Unit
                    name.startsWith("sec-fetch") -> Unit
                    else -> handshake.append(name).append(": ").append(value).append("\r\n")
                }
            }
            handshake.append("Host: ").append(hostHeader(url)).append("\r\n")
            handshake.append("Origin: ").append(origin).append("\r\n")
            cookieHeader(url)?.let { handshake.append("Cookie: ").append(it).append("\r\n") }
            handshake.append("\r\n")
            remote.getOutputStream().write(handshake.toString().toByteArray(Charsets.ISO_8859_1))
            remote.getOutputStream().flush()
            socket.soTimeout = 0
            remote.soTimeout = 0
            val up = pump(socket.getInputStream(), remote.getOutputStream())
            val down = pump(remote.getInputStream(), socket.getOutputStream())
            up.join()
            down.join()
        } finally {
            closeQuietly(remote)
        }
    }

    private fun pump(input: InputStream, output: java.io.OutputStream): Thread {
        val thread = Thread {
            try {
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (e: IOException) {
                Log.d(TAG, "relay ended: ${e.message}")
            } finally {
                closeQuietly(input)
                closeQuietly(output)
            }
        }
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private fun writeResponse(output: BufferedOutputStream, response: Response) {
        val head = StringBuilder()
        val reason = response.message.ifBlank { "Status" }
        head.append("HTTP/1.1 ").append(response.code).append(' ').append(reason).append("\r\n")
        for ((name, value) in response.headers) {
            val lower = name.lowercase()
            // The jar owns the cookies; the page never reads one (checked
            // against the client), so it gets none of the harness's.
            if (lower == "set-cookie" || lower == "content-length" || lower in HOP_BY_HOP) continue
            val rewritten = when (lower) {
                "location", "content-location" -> rewriteLocation(value)
                else -> value
            }
            head.append(name).append(": ").append(rewritten).append("\r\n")
        }
        val body = response.body
        val length = body?.contentLength() ?: 0L
        if (length >= 0) head.append("Content-Length: ").append(length).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
        if (body != null && response.request.method != "HEAD") {
            body.byteStream().use { source -> copyStream(source, output) }
        }
        output.flush()
    }

    /**
     * A redirect the harness issues for its own origin has to stay on the
     * loopback origin, or the WebView would leave the operator page behind.
     */
    private fun rewriteLocation(value: String): String {
        val absolute = value.toHttpUrlOrNull() ?: return value
        if (absolute.scheme != upstream.scheme || absolute.host != upstream.host || absolute.port != upstream.port) {
            return value
        }
        val query = absolute.encodedQuery?.let { "?$it" } ?: ""
        return "http://127.0.0.1:$port${absolute.encodedPath}$query"
    }

    private fun upstreamUrl(target: String): HttpUrl? {
        if (target.startsWith("http://") || target.startsWith("https://")) return target.toHttpUrlOrNull()
        if (!target.startsWith('/')) return null
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        return upstream.newBuilder().encodedPath(path).apply {
            encodedQuery(query.takeIf { it.isNotEmpty() })
        }.build()
    }

    private fun requestBody(input: InputStream, headers: Map<String, String>): RequestBody? {
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return SocketBody(ChunkedInput(input), -1L)
        }
        val length = headers["content-length"]?.trim()?.toLongOrNull()
        return when {
            length == null -> null
            length == 0L -> EMPTY_BODY
            else -> SocketBody(input, length)
        }
    }

    private fun connect(url: HttpUrl): Socket? {
        var plain: Socket? = null
        return try {
            val socket = Socket()
            plain = socket
            socket.connect(InetSocketAddress(url.host, url.port), CONNECT_TIMEOUT_MS)
            if (!url.isHttps) {
                socket
            } else {
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(socket, url.host, url.port, true) as SSLSocket
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.startHandshake()
                tls
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot reach ${url.host}: ${e.message}")
            closeQuietly(plain)
            null
        }
    }

    private fun writeError(output: BufferedOutputStream, code: Int, message: String) {
        try {
            val body = "$message\n".toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $code $message\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            output.write(body)
            output.flush()
        } catch (e: IOException) {
            Log.d(TAG, "cannot report $code: ${e.message}")
        }
    }

    /** The Host the harness's request fence compares against the Origin. */
    private fun hostHeader(url: HttpUrl): String =
        if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"

    private val origin: String
        get() = if (upstream.port == HttpUrl.defaultPort(upstream.scheme)) {
            "${upstream.scheme}://${upstream.host}"
        } else {
            "${upstream.scheme}://${upstream.host}:${upstream.port}"
        }

    private class SocketBody(private val source: InputStream, private val length: Long) : RequestBody() {
        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            val buffer = ByteArray(64 * 1024)
            if (length < 0) {
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                }
                return
            }
            var remaining = length
            while (remaining > 0) {
                val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("the page closed its request body early")
                sink.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    /** Decodes a chunked request body into the bytes OkHttp will forward. */
    private class ChunkedInput(private val input: InputStream) : InputStream() {
        private var remaining = 0L
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (finished) return -1
            if (remaining == 0L) {
                val line = readLine(input) ?: run { finished = true; return -1 }
                val size = line.substringBefore(';').trim().toLongOrNull(16) ?: run { finished = true; return -1 }
                if (size == 0L) {
                    while (true) {
                        val trailer = readLine(input) ?: break
                        if (trailer.isEmpty()) break
                    }
                    finished = true
                    return -1
                }
                remaining = size
            }
            val wanted = minOf(length.toLong(), remaining).toInt()
            val read = input.read(buffer, offset, wanted)
            if (read < 0) {
                finished = true
                return -1
            }
            remaining -= read
            if (remaining == 0L) readLine(input)
            return read
        }
    }

    private companion object {
        const val TAG = "WebViewDP"
        const val REQUEST_TIMEOUT_MS = 60_000
        const val CONNECT_TIMEOUT_MS = 15_000
        const val MAX_LINE = 16 * 1024
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
        val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
        val HOP_BY_HOP = setOf(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade",
        )

        fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte < 0) return if (line.isEmpty()) null else line.toString()
                if (byte == '\n'.code) return line.toString().removeSuffix("\r")
                line.append(byte.toChar())
                if (line.length > MAX_LINE) throw IOException("header line too long")
            }
        }

        fun copyStream(input: InputStream, output: java.io.OutputStream) {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        }

        fun closeQuietly(closeable: java.io.Closeable?) {
            try {
                closeable?.close()
            } catch (e: IOException) {
                Log.d(TAG, "close failed: ${e.message}")
            }
        }
    }
}
