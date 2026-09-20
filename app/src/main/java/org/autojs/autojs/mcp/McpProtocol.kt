package org.autojs.autojs.mcp

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.autojs.autojs6.BuildConfig
import org.autojs.autojs6.R
import java.util.UUID

/**
 * JSON-RPC 2.0 / MCP protocol handling.
 *
 * @Created by fork author on Sep 16, 2026.
 */

/**
 * Per-connection state. MCP is stateful during `initialize`: a client must
 * complete the handshake before it may list or call tools.
 * zh-CN: 单连接状态. MCP 在 `initialize` 阶段是有状态的:
 * 客户端必须完成握手后才能列出或调用工具.
 */
class McpSession {

    val id: String = UUID.randomUUID().toString()

    @Volatile
    var protocolVersion: String? = null

    @Volatile
    var initialised: Boolean = false

    @Volatile
    var lastSeenAt: Long = System.currentTimeMillis()

    fun touch() {
        lastSeenAt = System.currentTimeMillis()
    }

}

internal object McpProtocol {

    /**
     * Version we advertise. Clients negotiate down, so this is the newest one
     * we actually implement.
     * zh-CN: 我们对外宣告的版本. 客户端会向下协商, 因此这里填我们真正实现的最新版本.
     */
    const val PROTOCOL_VERSION = "2025-06-18"

    /** Versions we accept from clients, newest first. zh-CN: 接受来自客户端的版本, 由新到旧. */
    val SUPPORTED_PROTOCOL_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

    private const val TAG = "McpProtocol"

    /**
     * Upper bound on how far the cause chain is walked. A throwable whose cause
     * points back at itself would otherwise loop forever.
     * zh-CN: 遍历 cause 链的最大深度.
     * 否则一个 cause 指回自身的异常会导致死循环.
     */
    private const val MAX_CAUSE_DEPTH = 6

    object ErrorCode {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }

    /**
     * Handles one decoded JSON-RPC message.
     *
     * @return the response object, or null when the message was a notification
     * and therefore must not be answered.
     */
    fun handle(message: JsonElement, session: McpSession): JsonObject? {
        if (message.isJsonArray) {
            // Batching was removed from MCP, and answering with a shaped error
            // is more useful to the caller than silently ignoring the frame.
            // zh-CN: MCP 已移除批处理; 返回一个格式正确的错误比静默忽略该帧更有用.
            return errorResponse(null, ErrorCode.INVALID_REQUEST, "JSON-RPC batching is not supported")
        }

        if (!message.isJsonObject) {
            return errorResponse(null, ErrorCode.INVALID_REQUEST, "Request must be a JSON object")
        }

        val request = message.asJsonObject
        session.touch()

        val id = request.get("id")?.takeIf { !it.isJsonNull }
        val method = request.get("method")?.takeIf { it.isJsonPrimitive }?.asString

        if (method == null) {
            return errorResponse(id, ErrorCode.INVALID_REQUEST, "Missing `method`")
        }

        // A message without an id is a notification: act on it, answer nothing.
        // zh-CN: 不带 id 的消息是通知: 执行其副作用, 但不作应答.
        val isNotification = id == null

        return try {
            when (method) {
                "initialize" -> respond(id, isNotification, handleInitialize(request, session))
                "notifications/initialized" -> {
                    session.initialised = true
                    null
                }
                "ping" -> respond(id, isNotification, McpJson.obj())
                "tools/list" -> respond(id, isNotification, handleToolsList())
                "tools/call" -> respond(id, isNotification, handleToolsCall(request))
                else -> when {
                    // Per JSON-RPC, a notification must never be answered, not even
                    // with an error. Clients legitimately send notifications this
                    // server does not implement, such as `notifications/cancelled`
                    // or `notifications/progress`.
                    // zh-CN: 按 JSON-RPC 规范, 通知绝不应当被应答, 即使出错也不行.
                    // 客户端会合法地发送本服务未实现的通知, 例如
                    // `notifications/cancelled` 或 `notifications/progress`.
                    isNotification -> null
                    else -> errorResponse(id, ErrorCode.METHOD_NOT_FOUND, "Unknown method `$method`")
                }
            }
        } catch (e: McpArgumentException) {
            if (isNotification) null
            else errorResponse(id, ErrorCode.INVALID_PARAMS, e.message ?: "Invalid params")
        } catch (e: Throwable) {
            if (isNotification) null
            else {
                // A bare class name tells a client nothing it can act on, so the
                // whole cause chain is unwrapped here. This matters most for
                // ExceptionInInitializerError, whose own message is null while
                // the real reason sits in its cause.
                // zh-CN: 只给出一个类名, 客户端无从判断该如何处理, 因此这里展开完整的
                // cause 链. 对 ExceptionInInitializerError 尤其重要:
                // 它自身的 message 为 null, 真正的原因在它的 cause 中.
                Log.e(TAG, "Request `$method` failed", e)
                errorResponse(id, ErrorCode.INTERNAL_ERROR, describeFailure(e))
            }
        }
    }

    /**
     * Flattens a throwable and its causes into one readable line.
     * zh-CN: 将异常及其 cause 链展平为一行可读文本.
     */
    private fun describeFailure(e: Throwable): String =
        generateSequence(e) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .joinToString(" <- ") { cause ->
                val name = cause::class.java.simpleName
                cause.message?.takeIf { it.isNotBlank() }?.let { "$name: $it" } ?: name
            }

    private fun respond(id: JsonElement?, isNotification: Boolean, result: JsonObject): JsonObject? =
        if (isNotification) null else successResponse(id, result)

    private fun handleInitialize(request: JsonObject, session: McpSession): JsonObject {
        val params = request.get("params")?.takeIf { it.isJsonObject }?.asJsonObject
        val requested = params?.get("protocolVersion")?.takeIf { it.isJsonPrimitive }?.asString

        // Negotiate down to a version we both understand. Echoing the requested
        // version back when we do not support it would leave the client
        // believing in capabilities that do not exist.
        // zh-CN: 协商到双方都理解的版本. 若请求的版本我们并不支持却原样回显,
        // 会让客户端误以为存在实际并不具备的能力.
        val negotiated = when {
            requested == null -> PROTOCOL_VERSION
            requested in SUPPORTED_PROTOCOL_VERSIONS -> requested
            else -> PROTOCOL_VERSION
        }
        session.protocolVersion = negotiated

        return McpJson.obj().apply {
            addProperty("protocolVersion", negotiated)
            add("capabilities", McpJson.obj().apply {
                add("tools", McpJson.obj().apply {
                    // The tool set only changes when the user flips a setting,
                    // so we never push list-changed notifications.
                    // zh-CN: 工具集合仅在用户切换设置时变化, 因此从不推送列表变更通知.
                    addProperty("listChanged", false)
                })
            })
            add("serverInfo", McpJson.obj().apply {
                addProperty("name", McpUi.context.getString(R.string.text_mcp_server_name))
                addProperty("title", McpUi.context.getString(R.string.text_mcp_server))
                addProperty("version", BuildConfig.VERSION_NAME)
            })
            addProperty("instructions", McpUi.context.getString(R.string.text_mcp_server_instructions))
        }
    }

    private fun handleToolsList(): JsonObject = McpJson.obj().apply {
        add("tools", JsonArray().apply {
            McpToolRegistry.exposed().forEach { add(it.toListEntry()) }
        })
    }

    private fun handleToolsCall(request: JsonObject): JsonObject {
        val params = request.get("params")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw McpArgumentException("`tools/call` requires a `params` object")

        val name = params.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw McpArgumentException("`tools/call` requires a `name`")

        val rawArguments = params.get("arguments")?.takeIf { it.isJsonObject }?.asJsonObject

        val tool = McpToolRegistry.find(name)
            ?: return McpToolResult.error("Unknown tool `$name`.").toJson()

        if (!McpToolRegistry.isExposed(tool)) {
            // Guard the call path as well as the listing path: a client may
            // remember a tool name from a previous session.
            // zh-CN: 调用路径同样需要拦截: 客户端可能记住了上次会话中的工具名.
            return McpToolResult.error(
                McpUi.context.getString(R.string.mcp_error_tool_disabled) +
                        " Enable \"${McpUi.context.getString(R.string.text_mcp_server_expose_dangerous_tools)}\" in AutoJs6 developer options to use `$name`."
            ).toJson()
        }

        return try {
            tool.invoke(McpArgs(rawArguments)).toJson()
        } catch (e: McpArgumentException) {
            // Argument problems are the caller's fault and are reported as such,
            // with the offending detail preserved.
            // zh-CN: 参数问题属于调用方的错误, 按此上报并保留具体细节.
            McpToolResult.error(
                McpUi.context.getString(R.string.mcp_error_invalid_args, e.message ?: "")
            ).toJson()
        } catch (e: Throwable) {
            McpToolResult.error(
                McpUi.context.getString(
                    R.string.mcp_error_tool_failed,
                    "${e::class.java.simpleName}: ${e.message ?: "no message"}",
                )
            ).toJson()
        }
    }

    fun successResponse(id: JsonElement?, result: JsonObject): JsonObject = McpJson.obj().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id ?: com.google.gson.JsonNull.INSTANCE)
        add("result", result)
    }

    fun errorResponse(id: JsonElement?, code: Int, message: String): JsonObject = McpJson.obj().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id ?: com.google.gson.JsonNull.INSTANCE)
        add("error", McpJson.obj().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
    }

    fun parseError(detail: String): JsonObject =
        errorResponse(null, ErrorCode.PARSE_ERROR, "Invalid JSON: $detail")

}
