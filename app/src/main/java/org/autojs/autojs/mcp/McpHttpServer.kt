package org.autojs.autojs.mcp

import android.util.Log
import com.google.gson.JsonObject
import org.autojs.autojs6.R
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal HTTP/1.1 transport carrying MCP JSON-RPC messages.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Why
 *  ! The project ships no HTTP server library (no NanoHTTPD, no Ktor), and MCP
 *  ! requests are small JSON documents, so a purpose-built reader is smaller and
 *  ! auditable than pulling in a framework.
 *  ! zh-CN: 项目未引入任何 HTTP 服务端库 (既没有 NanoHTTPD 也没有 Ktor),
 *  ! 而 MCP 请求都是小型 JSON 文档, 因此自建解析比引入框架更精简也更易审计.
 *
 * @Design
 *  ! Every response carries `Connection: close`. MCP clients treat each request
 *  ! as an independent exchange, so closing removes a whole class of
 *  ! keep-alive state bugs at no practical cost. The only long-lived connection
 *  ! is the legacy SSE stream, which is long-lived by definition.
 *  ! zh-CN: 所有响应都带 `Connection: close`. MCP 客户端把每次请求视为独立交互,
 *  ! 因此关闭连接可以消除一整类 keep-alive 状态错误, 且几乎没有实际代价.
 *  ! 唯一的长连接是按定义就该长期存活的旧版 SSE 流.
 *
 *  ! Two transports are served: the current Streamable HTTP endpoint at `/mcp`,
 *  ! and the legacy HTTP+SSE pair at `/sse` + `/messages`.
 *  ! zh-CN: 同时提供两种传输: 当前标准的 Streamable HTTP 端点 `/mcp`,
 *  ! 以及旧版 HTTP+SSE 组合 `/sse` + `/messages`.
 */
internal class McpHttpServer(
    private val requestedPort: Int,
    private val bindAddress: InetAddress,
    private val onClientCountChanged: (Int) -> Unit = {},
) {

    private var serverSocket: ServerSocket? = null
    private var acceptor: Thread? = null
    private var workers: ExecutorService? = null

    @Volatile
    private var running = false

    private val sessions = ConcurrentHashMap<String, McpSession>()
    private val sseStreams = ConcurrentHashMap<String, SseStream>()
    private val clientCount = AtomicInteger(0)

    /** The port actually bound, which matters when [requestedPort] is 0. */
    val localPort: Int
        get() = serverSocket?.localPort ?: requestedPort

    val isRunning: Boolean
        get() = running

    fun connectedClientCount(): Int = clientCount.get()

    @Throws(IOException::class)
    fun start() {
        if (running) return

        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindAddress, requestedPort))
        serverSocket = socket
        running = true

        workers = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "mcp-conn").apply { isDaemon = true }
        }

        acceptor = Thread({ acceptLoop(socket) }, "mcp-acceptor").apply {
            isDaemon = true
            start()
        }

        Log.d(TAG, "MCP server listening on ${bindAddress.hostAddress}:${socket.localPort}")
    }

    fun stop() {
        running = false

        sseStreams.values.forEach { it.close() }
        sseStreams.clear()

        runCatching { serverSocket?.close() }
        serverSocket = null

        acceptor?.interrupt()
        acceptor = null

        workers?.shutdownNow()
        workers = null

        sessions.clear()
        clientCount.set(0)
        onClientCountChanged(0)
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                // Closing the ServerSocket is how stop() interrupts this loop,
                // so an IOException here is expected during shutdown.
                // zh-CN: stop() 通过关闭 ServerSocket 来中断此循环,
                // 因此这里的 IOException 在关闭期间属于预期情况.
                if (running) Log.w(TAG, "accept failed: ${e.message}")
                break
            }

            val total = clientCount.incrementAndGet()
            onClientCountChanged(total)

            val executor = workers
            if (executor == null) {
                closeQuietly(client)
                break
            }
            executor.execute { handleConnection(client) }
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = READ_TIMEOUT_MS

            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val request = readRequest(input)
            if (request == null) {
                writeJson(output, 400, "Bad Request", errorBody("Malformed HTTP request"))
                return
            }

            dispatch(request, input, output, socket)
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "connection timed out before a complete request arrived")
        } catch (e: IOException) {
            Log.d(TAG, "connection error: ${e.message}")
        } catch (e: Throwable) {
            Log.w(TAG, "unexpected connection failure", e)
        } finally {
            closeQuietly(socket)
            val remaining = clientCount.decrementAndGet()
            onClientCountChanged(remaining.coerceAtLeast(0))
        }
    }

    // --------------------------------------------------------------- routing

    private fun dispatch(
        request: HttpRequest,
        input: InputStream,
        output: BufferedOutputStream,
        socket: Socket,
    ) {
        // CORS preflight must be answerable without credentials, otherwise
        // browser based clients can never reach the token-protected endpoints.
        // zh-CN: CORS 预检必须无需凭据即可应答,
        // 否则基于浏览器的客户端永远无法访问需要令牌的端点.
        if (request.method == "OPTIONS") {
            writeEmpty(output, 204, "No Content")
            return
        }

        if (!McpSecurity.isAuthorizationValid(request.headers["authorization"])) {
            writeJson(
                output, 401, "Unauthorized",
                errorBody(McpUi.context.getString(R.string.mcp_error_unauthorized)),
                mapOf("WWW-Authenticate" to "Bearer realm=\"autojs6-mcp\""),
            )
            return
        }

        when {
            request.path == ENDPOINT_MCP && request.method == "POST" -> handleStreamableHttp(request, output)
            request.path == ENDPOINT_MCP && request.method == "GET" ->
                writeEmpty(output, 405, "Method Not Allowed", mapOf("Allow" to "POST, DELETE"))
            request.path == ENDPOINT_MCP && request.method == "DELETE" -> handleSessionDelete(request, output)
            request.path == ENDPOINT_SSE && request.method == "GET" -> handleLegacySse(output, socket)
            request.path == ENDPOINT_MESSAGES && request.method == "POST" -> handleLegacyMessage(request, input, output)
            else -> writeJson(
                output, 404, "Not Found",
                errorBody(
                    "Unknown endpoint. Use POST $ENDPOINT_MCP for the current transport, " +
                            "or GET $ENDPOINT_SSE with POST $ENDPOINT_MESSAGES for the legacy transport."
                ),
            )
        }
    }

    // -------------------------------------------------- Streamable HTTP /mcp

    private fun handleStreamableHttp(request: HttpRequest, output: BufferedOutputStream) {
        val body = String(request.body, Charsets.UTF_8)
        if (body.isBlank()) {
            writeJson(output, 400, "Bad Request", McpProtocol.parseError("empty body"))
            return
        }

        val message = McpJson.parseOrNull(body)
        if (message == null) {
            writeJson(output, 400, "Bad Request", McpProtocol.parseError("not valid JSON"))
            return
        }

        val sessionHeader = request.headers[HEADER_SESSION_ID]
        val session = when {
            sessionHeader == null -> newSession()
            else -> sessions[sessionHeader] ?: run {
                // Per spec, an unknown session id means the client must start over.
                // zh-CN: 按规范, 未知的会话 id 意味着客户端必须重新开始.
                writeJson(
                    output, 404, "Not Found",
                    errorBody("Unknown session. Re-run initialize to obtain a new session id."),
                )
                return
            }
        }
        session.touch()

        val response = McpProtocol.handle(message, session)
        val sessionHeaders = mapOf(HEADER_SESSION_ID to session.id)

        if (response == null) {
            // Notification only: nothing to return, but the session id still
            // has to be exposed so the client can continue the conversation.
            // zh-CN: 仅通知: 无需返回内容, 但仍需暴露会话 id, 以便客户端继续对话.
            writeEmpty(output, 202, "Accepted", sessionHeaders)
            return
        }

        writeJson(output, 200, "OK", response, sessionHeaders)
    }

    private fun handleSessionDelete(request: HttpRequest, output: BufferedOutputStream) {
        request.headers[HEADER_SESSION_ID]?.let { sessions.remove(it) }
        writeEmpty(output, 204, "No Content")
    }

    private fun newSession(): McpSession = McpSession().also {
        sessions[it.id] = it
        pruneSessions()
    }

    /**
     * Sessions are cheap, but a long-running server should not accumulate them
     * forever, so anything idle for an hour is dropped.
     * zh-CN: 会话本身开销很小, 但长期运行的服务不应无限累积,
     * 因此空闲超过一小时的会话会被清理.
     */
    private fun pruneSessions() {
        val cutoff = System.currentTimeMillis() - SESSION_IDLE_TIMEOUT_MS
        // Collect first, then remove: mutating a ConcurrentHashMap entry set
        // during iteration is easy to get subtly wrong.
        // zh-CN: 先收集再删除: 在迭代过程中修改 ConcurrentHashMap 的条目集合很容易出错.
        val stale = sessions.filterValues { it.lastSeenAt < cutoff }.keys.toList()
        stale.forEach { sessions.remove(it) }
    }

    // ------------------------------------------------------- legacy SSE pair

    private fun handleLegacySse(output: BufferedOutputStream, socket: Socket) {
        val session = newSession()
        val stream = SseStream(output)

        // The SSE connection is a long-lived stream, so the idle read timeout
        // used for ordinary requests has to be lifted.
        // zh-CN: SSE 是长期存活的流, 因此必须取消普通请求使用的空闲读超时.
        runCatching { socket.soTimeout = 0 }

        writeSseHeaders(output)
        sseStreams[session.id] = stream

        try {
            stream.sendEvent("endpoint", "$ENDPOINT_MESSAGES?sessionId=${session.id}")
            while (running && !stream.isClosed) {
                Thread.sleep(SSE_KEEP_ALIVE_MS)
                if (!stream.sendComment("keep-alive")) break
                stream.flush()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: IOException) {
            Log.d(TAG, "legacy SSE stream closed: ${e.message}")
        } finally {
            sseStreams.remove(session.id)
            sessions.remove(session.id)
            stream.close()
        }
    }

    private fun handleLegacyMessage(
        request: HttpRequest,
        input: InputStream,
        output: BufferedOutputStream,
    ) {
        val sessionId = request.query["sessionId"]
            ?: run {
                writeJson(output, 400, "Bad Request", errorBody("Missing `sessionId` query parameter"))
                return
            }

        val stream = sseStreams[sessionId]
            ?: run {
                writeJson(
                    output, 404, "Not Found",
                    errorBody("No open SSE stream for session `$sessionId`. Open GET $ENDPOINT_SSE first."),
                )
                return
            }

        val session = sessions[sessionId] ?: newSession()
        val message = McpJson.parseOrNull(String(request.body, Charsets.UTF_8))
        if (message == null) {
            writeJson(output, 400, "Bad Request", McpProtocol.parseError("not valid JSON"))
            return
        }

        val response = McpProtocol.handle(message, session)
        if (response != null) {
            try {
                stream.sendEvent("message", McpJson.stringify(response))
                stream.flush()
            } catch (e: IOException) {
                sseStreams.remove(sessionId)
                writeJson(output, 410, "Gone", errorBody("The SSE stream has been closed."))
                return
            }
        }

        writeEmpty(output, 202, "Accepted")
    }

    // ------------------------------------------------------------ HTTP reads

    /**
     * Reads exactly one request.
     * @return null when the peer closed the connection or the request is malformed
     * beyond recovery.
     */
    private fun readRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null

        val parts = requestLine.split(' ')
        if (parts.size < 3) return null

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }

        val declaredLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (declaredLength > MAX_BODY_BYTES) {
            throw IOException("Request body of $declaredLength bytes exceeds the $MAX_BODY_BYTES byte limit")
        }
        val body = if (declaredLength > 0) readFully(input, declaredLength) else ByteArray(0)

        val (path, query) = splitTarget(parts[1])
        return HttpRequest(parts[0].uppercase(), path, query, headers, body)
    }

    /**
     * Reads a CRLF terminated line byte by byte. A Reader cannot be used here
     * because the body that follows must be read as raw bytes from the very
     * next position.
     * zh-CN: 逐字节读取以 CRLF 结尾的行. 此处不能使用 Reader,
     * 因为随后的请求体必须从紧接的位置按原始字节读取.
     */
    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var current = input.read()
        if (current == -1) return null

        while (current != -1) {
            if (current == LF_CHAR) break
            if (current != CR_CHAR) {
                buffer.write(current)
                if (buffer.size() > MAX_LINE_BYTES) {
                    throw IOException("HTTP header line exceeds the $MAX_LINE_BYTES byte limit")
                }
            }
            current = input.read()
        }

        return String(buffer.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(data, offset, length - offset)
            if (count == -1) throw IOException("Connection closed after $offset of $length body bytes")
            offset += count
        }
        return data
    }

    private fun splitTarget(target: String): Pair<String, Map<String, String>> {
        val separator = target.indexOf('?')
        if (separator < 0) return target to emptyMap()

        val path = target.substring(0, separator)
        val query = target.substring(separator + 1)
            .split('&')
            .filter { it.isNotBlank() }
            .associate { pair ->
                val equals = pair.indexOf('=')
                when {
                    equals < 0 -> decode(pair) to ""
                    else -> decode(pair.substring(0, equals)) to decode(pair.substring(equals + 1))
                }
            }
        return path to query
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    // ---------------------------------------------------------- HTTP writes

    private fun writeJson(
        output: BufferedOutputStream,
        status: Int,
        statusText: String,
        body: JsonObject,
        extraHeaders: Map<String, String> = emptyMap(),
    ) = writeBytes(
        output, status, statusText, CONTENT_TYPE_JSON,
        McpJson.stringify(body).toByteArray(Charsets.UTF_8), extraHeaders,
    )

    private fun writeEmpty(
        output: BufferedOutputStream,
        status: Int,
        statusText: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) = writeBytes(output, status, statusText, null, ByteArray(0), extraHeaders)

    private fun writeBytes(
        output: BufferedOutputStream,
        status: Int,
        statusText: String,
        contentType: String?,
        body: ByteArray,
        extraHeaders: Map<String, String>,
    ) {
        val header = StringBuilder()
        header.append("HTTP/1.1 ").append(status).append(' ').append(statusText).append(CRLF)
        if (contentType != null) header.append("Content-Type: ").append(contentType).append(CRLF)
        header.append("Content-Length: ").append(body.size).append(CRLF)
        header.append("Connection: close").append(CRLF)
        header.append(CORS_ORIGIN_HEADER)
        header.append(CORS_METHODS_HEADER)
        header.append(CORS_HEADERS_HEADER)
        extraHeaders.forEach { (name, value) ->
            header.append(name).append(": ").append(value).append(CRLF)
        }
        header.append(CRLF)

        output.write(header.toString().toByteArray(Charsets.ISO_8859_1))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun writeSseHeaders(output: BufferedOutputStream) {
        val header = buildString {
            append("HTTP/1.1 200 OK").append(CRLF)
            append("Content-Type: text/event-stream").append(CRLF)
            append("Cache-Control: no-cache").append(CRLF)
            append("Connection: keep-alive").append(CRLF)
            append(CORS_ORIGIN_HEADER)
        }
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.write(CRLF.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun errorBody(message: String): JsonObject = McpJson.obj().apply {
        addProperty("error", message)
    }

    private fun closeQuietly(socket: Socket) = runCatching { socket.close() }

    // ----------------------------------------------------------------- types

    private class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    /**
     * Server side of the legacy SSE transport.
     * zh-CN: 旧版 SSE 传输的服务端.
     */
    private class SseStream(private val output: OutputStream) {

        @Volatile
        var isClosed = false
            private set

        @Synchronized
        @Throws(IOException::class)
        fun sendEvent(event: String, data: String) {
            if (isClosed) throw IOException("SSE stream is closed")
            output.write("event: $event$EOL".toByteArray(Charsets.UTF_8))
            // Every payload line is prefixed so multi-line JSON survives framing.
            // zh-CN: 每一行载荷都加前缀, 以保证多行 JSON 在分帧后仍然完整.
            data.split('\n').forEach { line ->
                output.write("data: $line$EOL".toByteArray(Charsets.UTF_8))
            }
            output.write(EOL.toByteArray(Charsets.UTF_8))
        }

        @Synchronized
        fun sendComment(comment: String): Boolean = runCatching {
            output.write(": $comment$EOL$EOL".toByteArray(Charsets.UTF_8))
            true
        }.getOrDefault(false).also { ok -> if (!ok) isClosed = true }

        @Synchronized
        fun flush() {
            runCatching { output.flush() }.onFailure { isClosed = true }
        }

        @Synchronized
        fun close() {
            isClosed = true
            runCatching { output.close() }
        }

        private companion object {
            const val EOL = "\r\n"
        }

    }

    companion object {

        private const val TAG = "McpHttpServer"

        const val ENDPOINT_MCP = "/mcp"
        const val ENDPOINT_SSE = "/sse"
        const val ENDPOINT_MESSAGES = "/messages"

        const val HEADER_SESSION_ID = "Mcp-Session-Id"

        private const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"

        private const val READ_TIMEOUT_MS = 60_000
        private const val SESSION_IDLE_TIMEOUT_MS = 3_600_000L
        private const val SSE_KEEP_ALIVE_MS = 15_000L
        private const val MAX_LINE_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024

        private const val CRLF = "\r\n"

        /** Compared as Int so a plain `const val` literal can be used. */
        private const val LF_CHAR = 0x0A
        private const val CR_CHAR = 0x0D

        private const val CORS_ORIGIN_HEADER = "Access-Control-Allow-Origin: *\r\n"
        private const val CORS_METHODS_HEADER = "Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n"
        private const val CORS_HEADERS_HEADER =
            "Access-Control-Allow-Headers: Content-Type, Authorization, Mcp-Session-Id, MCP-Protocol-Version\r\n"

    }

}
