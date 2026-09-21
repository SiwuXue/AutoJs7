package org.autojs.autojs.mcp.tools

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.autojs.autojs.mcp.McpArgumentException
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Outbound HTTP requests.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @RiskAssessment
 *  ! This tool is graded SENSITIVE rather than DANGEROUS, and the reasoning is
 *  ! worth writing down because the call is not obvious. A request cannot read
 *  ! anything on the device: whatever it sends is data the model already has in
 *  ! its context, and obtaining local data in the first place requires one of
 *  ! the DANGEROUS file or shell tools. So this tool alone is not an
 *  ! exfiltration channel.
 *  ! What remains is that it is an unrestricted egress primitive -- it can reach
 *  ! any host the device can route to, including services on the local network.
 *  ! Grading it DANGEROUS would not remove that risk, so the honest placement is
 *  ! SENSITIVE with the risk stated plainly in the description where the model
 *  ! will read it.
 *  ! zh-CN: 本工具被评为 SENSITIVE 而非 DANGEROUS, 理由值得写下来, 因为它并不显然.
 *  ! 一次请求无法读取设备上的任何东西: 它发送的数据都是模型上下文中已有的,
 *  ! 而获取本地数据本身必须先用到 DANGEROUS 的文件或 Shell 工具.
 *  ! 因此单靠本工具并不构成数据外泄通道.
 *  ! 剩下的风险是它是一条不受限的出网原语 —— 设备能路由到的主机它都能访问,
 *  ! 包括本地网络中的服务. 但把它评为 DANGEROUS 并不能消除该风险,
 *  ! 因此诚实的定位是 SENSITIVE, 并在描述中直接写明风险供模型阅读.
 */
internal object McpNetworkTools {

    private const val DEFAULT_TIMEOUT_MS = 15_000

    private const val MAX_TIMEOUT_MS = 120_000

    private const val DEFAULT_MAX_BYTES = 256 * 1024

    private const val MAX_MAX_BYTES = 4 * 1024 * 1024

    private const val MAX_HEADERS = 60

    /**
     * OkHttp only permits a request body for these methods.
     *
     * @Note
     *  ! This list is deliberately narrower than "everything except GET and
     *  ! HEAD": OkHttp rejects a body on DELETE too, and discovering that at
     *  ! runtime would surface as an opaque crash rather than an explanation.
     *  ! zh-CN: 该列表刻意比"除 GET/HEAD 之外的一切"更窄: OkHttp 对 DELETE 同样
     *  ! 拒绝请求体, 若在运行时才发现就只会表现为难以理解的崩溃而非解释.
     */
    private val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH")

    private val ALLOWED_METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

    private val TEXT_MEDIA_TYPE = "text/plain; charset=utf-8".toMediaTypeOrNull()

    /**
     * @Note
     *  ! Declared after `ALLOWED_METHODS` on purpose. Object properties are
     *  ! initialised in textual order, so declaring `tools` above it would read a
     *  ! null list and silently drop the `method` enum from the emitted schema
     *  ! rather than reporting anything.
     *  ! zh-CN: 刻意声明在 `ALLOWED_METHODS` 之后. 对象属性按文本顺序初始化,
     *  ! 若把 `tools` 声明在其上方, 读到的将是 null 列表, 结果是 `method` 的枚举
     *  ! 被静默地从输出的 schema 中丢掉, 而不会有任何报错.
     */
    val tools: List<McpTool> = listOf(httpRequestTool())

    private fun httpRequestTool(): McpTool = McpTool(
        name = "http_request",
        title = "Send an HTTP request",
        description = buildString {
            append("Performs one HTTP request and returns the status, headers and body. ")
            append("JSON responses come back already parsed when `parseJson` is left on, which saves the caller ")
            append("a round of unescaping. ")
            append("A request body is only accepted for POST, PUT and PATCH -- that restriction comes from the ")
            append("underlying client, so send DELETE parameters in the URL. ")
            append("Note that this reaches any host the device can route to, including services on the local network, ")
            append("and the request is made from the phone's own network identity.")
        },
        risk = McpToolRisk.SENSITIVE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "url" to McpSchema.string("Absolute `http://` or `https://` URL."),
                "method" to McpSchema.string("HTTP method.", ALLOWED_METHODS, "GET"),
                "headers" to McpSchema.freeObject(
                    "Request headers. Values are coerced to strings.",
                ),
                "body" to McpSchema.string(
                    "Raw request body, sent as `text/plain`. Mutually exclusive with `json` and `form`.",
                ),
                "json" to McpSchema.freeObject(
                    "Body serialized as `application/json`. Mutually exclusive with `body` and `form`.",
                ),
                "form" to McpSchema.freeObject(
                    "Body encoded as `application/x-www-form-urlencoded`. " +
                            "Mutually exclusive with `body` and `json`.",
                ),
                "timeoutMs" to McpSchema.integer(
                    "Applied to connect, read and the whole call.",
                    DEFAULT_TIMEOUT_MS, 1_000, MAX_TIMEOUT_MS,
                ),
                "followRedirects" to McpSchema.boolean(
                    "Follow 3xx responses. When off, the redirect is returned as-is so its `location` can be inspected.",
                    true,
                ),
                "maxBytes" to McpSchema.integer(
                    "Stop reading the body after this many bytes. `truncated` reports whether that happened.",
                    DEFAULT_MAX_BYTES, 1024, MAX_MAX_BYTES,
                ),
                "parseJson" to McpSchema.boolean(
                    "Return a parsed `json` field instead of raw `text` when the content type is JSON.",
                    true,
                ),
            ),
            required = listOf("url"),
        ),
    ) { args -> invokeHttpRequest(args) }

    private fun invokeHttpRequest(args: McpArgs): McpToolResult {
        val rawUrl = args.requireString("url")
        val url = rawUrl.toHttpUrlOrNull()
            ?: throw McpArgumentException(
                "`url` must be an absolute http:// or https:// URL, but got `$rawUrl`."
            )

        val httpMethod = (args.optString("method", "GET") ?: "GET").trim().uppercase()
        if (httpMethod !in ALLOWED_METHODS) {
            throw McpArgumentException(
                "`method` must be one of ${ALLOWED_METHODS.joinToString(", ")}, but got `$httpMethod`."
            )
        }

        val timeoutMs = args.optInt("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(1_000, MAX_TIMEOUT_MS)
        val followRedirects = args.optBoolean("followRedirects", true)
        val maxBytes = args.optInt("maxBytes", DEFAULT_MAX_BYTES).coerceIn(1024, MAX_MAX_BYTES)
        val parseJson = args.optBoolean("parseJson", true)

        val body = try {
            resolveBody(args, httpMethod)
        } catch (e: McpArgumentException) {
            return McpToolResult.error(e.message ?: "Invalid body arguments.")
        }

        // A header the builder rejects is recorded rather than dropped silently:
        // a missing Authorization header produces a confusing 401 much later.
        // zh-CN: 构造器拒绝的请求头会被记录下来而不是静默丢弃:
        // 缺失的 Authorization 头会在很久之后表现为令人困惑的 401.
        val rejectedHeaders = mutableListOf<String>()

        val request = Request.Builder().url(url).apply {
            args.optObject("headers")?.entrySet()?.forEach { (name, value) ->
                if (value.isJsonNull) return@forEach
                runCatching { header(name, value.asString) }
                    .onFailure { rejectedHeaders.add(name) }
            }
            method(httpMethod, body)
        }.build()

        val client = newClient(timeoutMs, followRedirects)

        val startedAt = System.currentTimeMillis()
        val response = try {
            client.newCall(request).execute()
        } catch (e: Throwable) {
            return McpToolResult.error(
                "The request failed before a response arrived: " +
                        "${e::class.java.simpleName}: ${e.message ?: "no message"}"
            )
        }

        return response.use { resp ->
            val elapsedMs = System.currentTimeMillis() - startedAt
            val read = readBodyCapped(resp, maxBytes)
            val contentType = resp.body?.contentType()

            McpToolResult.json(McpJson.obj().apply {
                addProperty("status", resp.code)
                addProperty("statusText", resp.message)
                addProperty("successful", resp.isSuccessful)
                addProperty("method", httpMethod)
                addProperty("url", url.toString())
                // Differs from `url` when redirects were followed, which is the
                // quickest way for the caller to notice that one happened.
                // zh-CN: 跟随重定向后会与 `url` 不同, 这是调用方察觉发生了重定向的最快方式.
                addProperty("finalUrl", resp.request.url.toString())
                addProperty("elapsedMs", elapsedMs)
                addProperty("bodyBytes", read.totalBytes)
                addProperty("truncated", read.truncated)

                add("headers", JsonArray().apply {
                    resp.headers.forEachIndexed { index, header ->
                        if (index < MAX_HEADERS) {
                            add(McpJson.obj().apply {
                                addProperty("name", header.first)
                                addProperty("value", header.second)
                            })
                        }
                    }
                })

                contentType?.let { addProperty("contentType", it.toString()) }

                val text = read.text
                // A truncated body is never parsed: it is not valid JSON by
                // construction, and reporting a parse failure would point the
                // caller at the wrong problem.
                // zh-CN: 被截断的响应体绝不解析: 它按定义就不是合法 JSON,
                // 若上报解析失败会把调用方引向错误的问题.
                val parsed = if (
                    parseJson && !read.truncated && contentType?.subtype?.contains("json") == true
                ) {
                    runCatching { JsonParser.parseString(text) }.getOrNull()
                } else {
                    null
                }

                when {
                    parsed != null -> add("json", parsed)
                    text.isNotEmpty() -> addProperty("text", text)
                }

                if (rejectedHeaders.isNotEmpty()) {
                    addProperty(
                        "rejectedHeaders",
                        rejectedHeaders.joinToString(", ") { "`$it`" },
                    )
                }
                if (read.truncated) {
                    addProperty(
                        "hint",
                        "The body was cut off at $maxBytes bytes, so it is not parseable. " +
                                "Raise `maxBytes` if the whole payload is needed.",
                    )
                }
                if (resp.code == 401 || resp.code == 403) {
                    addProperty(
                        "authHint",
                        "The endpoint rejected the credentials. The token belongs in `headers`, " +
                                "for example `Authorization: Bearer ...`.",
                    )
                }
            })
        }
    }

    private fun newClient(timeoutMs: Int, followRedirects: Boolean): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            // The call timeout covers connect, write, read and redirects together,
            // which is the bound the caller actually cares about.
            // zh-CN: callTimeout 覆盖连接, 写入, 读取与重定向的总和,
            // 这才是调用方真正关心的那个上限.
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(followRedirects)
            .build()

    /**
     * @throws McpArgumentException when the body arguments conflict or when the
     * method cannot carry a body at all.
     * zh-CN: 当请求体参数冲突, 或该方法根本无法携带请求体时抛出.
     */
    private fun resolveBody(args: McpArgs, httpMethod: String): RequestBody? {
        val json = args.optObject("json")
        val form = args.optObject("form")
        val raw = args.optString("body")

        val provided = listOfNotNull(json, form, raw).size
        if (provided > 1) {
            throw McpArgumentException("Provide only one of `json`, `form` or `body`.")
        }
        if (provided == 0) return null

        if (httpMethod !in METHODS_WITH_BODY) {
            throw McpArgumentException(
                "The underlying client only accepts a request body for " +
                        "${METHODS_WITH_BODY.joinToString(", ")}, not for $httpMethod. " +
                        "Pass the values in the URL instead.",
            )
        }

        return when {
            json != null -> McpJson.gson.toJson(json).toRequestBody(JSON_MEDIA_TYPE)
            form != null -> FormBody.Builder().apply {
                form.entrySet().forEach { (name, value) ->
                    add(name, if (value.isJsonNull) "" else value.asString)
                }
            }.build()
            else -> (raw ?: "").toRequestBody(TEXT_MEDIA_TYPE)
        }
    }

    private class ReadOutcome(
        val text: String,
        val totalBytes: Long,
        val truncated: Boolean,
    )

    /**
     * Reads at most [maxBytes] of the body.
     *
     * @Note
     *  ! Reading through an explicit chunk loop rather than `body.string()` is
     *  ! what makes the cap real: `string()` buffers the whole payload first, so
     *  ! a hostile or careless endpoint could exhaust memory before any limit
     *  ! was applied. `totalBytes` reports what the endpoint actually sent.
     *  ! zh-CN: 通过显式分块循环读取而非 `body.string()`, 才让上限真正生效:
     *  ! `string()` 会先把整个响应体缓冲下来, 恶意或不谨慎的端点因此可以在任何限制
     *  ! 生效前耗尽内存. `totalBytes` 报告端点实际发送的字节数.
     */
    private fun readBodyCapped(response: Response, maxBytes: Int): ReadOutcome {
        val body = response.body ?: return ReadOutcome("", 0L, false)

        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0L
        var truncated = false

        body.byteStream().use { stream ->
            while (true) {
                val read = stream.read(chunk)
                // A non-positive count means the endpoint is done, so a body that
                // ends exactly on the limit is not mislabelled as truncated.
                // zh-CN: 非正数表示端点已结束, 因此恰好在上限处结束的响应体
                // 不会被误判为已截断.
                if (read <= 0) break
                total += read
                val remaining = maxBytes - buffer.size()
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                buffer.write(chunk, 0, minOf(read, remaining))
                if (read > remaining) {
                    truncated = true
                    break
                }
            }
        }

        return ReadOutcome(String(buffer.toByteArray(), charset), total, truncated)
    }

}
