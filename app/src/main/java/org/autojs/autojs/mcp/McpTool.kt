package org.autojs.autojs.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * MCP tool model, argument accessors and JSON Schema helpers.
 *
 * @Created by fork author on Sep 16, 2026.
 */

/**
 * How dangerous a tool is, which decides whether it is exposed at all.
 *
 * @Security
 *  ! `DANGEROUS` tools are filtered out of `tools/list` unless the user has
 *  ! explicitly flipped the corresponding switch in developer options. A tool
 *  ! that is not listed must also not be callable, so the filter is applied on
 *  ! both paths.
 *  ! zh-CN: 除非用户在开发者选项中显式开启对应开关, 否则 `DANGEROUS` 工具不会
 *  ! 出现在 `tools/list` 中. 未列出的工具同样不可调用, 因此两条路径都要过滤.
 */
enum class McpToolRisk {
    /** Read-only, no side effect on the device. zh-CN: 只读, 对设备无副作用. */
    SAFE,

    /** Manipulates the UI. Reversible but visible. zh-CN: 操作界面, 可逆但可见. */
    SENSITIVE,

    /** Can execute arbitrary code or write data. zh-CN: 可执行任意代码或写入数据. */
    DANGEROUS,
}

class McpTool(
    val name: String,
    val title: String,
    val description: String,
    val risk: McpToolRisk = McpToolRisk.SAFE,
    val inputSchema: JsonObject = McpSchema.emptyObject(),
    val invoke: (McpArgs) -> McpToolResult,
) {

    fun toListEntry(): JsonObject = McpJson.obj().apply {
        addProperty("name", name)
        addProperty("title", title)
        addProperty("description", description)
        add("inputSchema", inputSchema)
    }

}

/** Raised when the arguments supplied by the client do not fit the schema. */
class McpArgumentException(message: String) : IllegalArgumentException(message)

/**
 * Thin, validating wrapper over the `arguments` object of a `tools/call`.
 * zh-CN: 对 `tools/call` 中 `arguments` 对象的轻量校验包装.
 */
class McpArgs(private val raw: JsonObject?) {

    fun isEmpty(): Boolean = raw == null || raw.size() == 0

    fun optObject(name: String): JsonObject? {
        val element = raw?.get(name) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonObject) {
            throw McpArgumentException("`$name` must be an object")
        }
        return element.asJsonObject
    }

    fun optString(name: String, defaultValue: String? = null): String? {
        val element = raw?.get(name) ?: return defaultValue
        if (element.isJsonNull) return defaultValue
        if (!element.isJsonPrimitive) {
            throw McpArgumentException("`$name` must be a string")
        }
        return element.asString
    }

    fun requireString(name: String): String =
        optString(name)?.takeIf { it.isNotBlank() }
            ?: throw McpArgumentException("`$name` is required and must be a non-empty string")

    fun optInt(name: String, defaultValue: Int): Int {
        val element = raw?.get(name) ?: return defaultValue
        if (element.isJsonNull) return defaultValue
        if (!element.isJsonPrimitive) {
            throw McpArgumentException("`$name` must be an integer")
        }
        return runCatching { element.asInt }
            .getOrElse { throw McpArgumentException("`$name` must be an integer") }
    }

    /**
     * Reads a fractional number.
     *
     * @Note
     *  ! Integers are accepted as well, so a client that sends `1` instead of
     *  ! `1.0` for a threshold still works. That is worth the two extra lines
     *  ! because JSON has a single number type and clients differ in how they
     *  ! serialize it.
     *  ! zh-CN: 同样接受整数, 因此客户端把阈值写成 `1` 而不是 `1.0` 时依然可用.
     *  ! 这值得多写两行, 因为 JSON 只有一种数字类型, 而各客户端的序列化方式并不一致.
     */
    fun optDouble(name: String, defaultValue: Double): Double {
        val element = raw?.get(name) ?: return defaultValue
        if (element.isJsonNull) return defaultValue
        if (!element.isJsonPrimitive) {
            throw McpArgumentException("`$name` must be a number")
        }
        return runCatching { element.asDouble }
            .getOrElse { throw McpArgumentException("`$name` must be a number") }
    }

    fun optBoolean(name: String, defaultValue: Boolean): Boolean {
        val element = raw?.get(name) ?: return defaultValue
        if (element.isJsonNull) return defaultValue
        if (!element.isJsonPrimitive) {
            throw McpArgumentException("`$name` must be a boolean")
        }
        return runCatching { element.asBoolean }
            .getOrElse { throw McpArgumentException("`$name` must be a boolean") }
    }

    fun optIntList(name: String): List<Int>? {
        val element = raw?.get(name) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonArray) {
            throw McpArgumentException("`$name` must be an array of numbers")
        }
        return element.asJsonArray.map {
            runCatching { it.asInt }
                .getOrElse { throw McpArgumentException("`$name` must be an array of numbers") }
        }
    }

    /**
     * Returns the raw array so a tool can inspect arbitrary element types.
     *
     * @Note
     *  ! The typed accessors above (`optIntList`, `optPoints`, ...) only cover
     *  ! shapes that were foreseen. When a tool needs whatever a client sent --
     *  ! SQL parameters, for instance, which may be strings, numbers or nulls --
     *  ! it inspects the elements itself rather than being forced to invent
     *  ! another narrow accessor for each case.
     *  ! zh-CN: 上面的类型化访问器 (`optIntList`, `optPoints` 等) 只覆盖了预先设想到的
     *  ! 形状. 当工具需要"客户端发来什么就用什么"时 —— 例如 SQL 参数, 可能是字符串,
     *  ! 数字或空值 —— 它自行检查元素, 而不必为每种情况再发明一个窄访问器.
     */
    fun optArray(name: String): JsonArray? {
        val element = raw?.get(name) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonArray) {
            throw McpArgumentException("`$name` must be an array")
        }
        return element.asJsonArray
    }

    /**
     * Returns the raw object so a selector builder can iterate its keys.
     * zh-CN: 返回原始对象, 以便选择器构造器遍历其键.
     */
    fun rawObject(): JsonObject? = raw

    /**
     * Parses `[[x, y], ...]` into coordinate pairs.
     * zh-CN: 将 `[[x, y], ...]` 解析为坐标对.
     */
    fun optPoints(name: String): List<IntArray>? {
        val element = raw?.get(name) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonArray) {
            throw McpArgumentException("`$name` must be an array of [x, y] pairs")
        }
        return element.asJsonArray.mapIndexed { index, point -> parsePoint(name, index, point) }
    }

    /**
     * Parses `[[[x, y], ...], ...]` into strokes, used for multi-touch gestures.
     * zh-CN: 将 `[[[x, y], ...], ...]` 解析为笔画列表, 用于多指手势.
     */
    fun optStrokes(name: String): List<List<IntArray>>? {
        val element = raw?.get(name) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonArray) {
            throw McpArgumentException("`$name` must be an array of strokes")
        }
        return element.asJsonArray.mapIndexed { strokeIndex, stroke ->
            if (!stroke.isJsonArray) {
                throw McpArgumentException("`$name[$strokeIndex]` must be an array of [x, y] pairs")
            }
            stroke.asJsonArray.mapIndexed { pointIndex, point ->
                parsePoint("$name[$strokeIndex]", pointIndex, point)
            }
        }
    }

    private fun parsePoint(
        name: String,
        index: Int,
        element: com.google.gson.JsonElement,
    ): IntArray {
        if (!element.isJsonArray) {
            throw McpArgumentException("`$name[$index]` must be a pair of integers")
        }
        val pair = element.asJsonArray
        if (pair.size() != 2) {
            throw McpArgumentException("`$name[$index]` must contain exactly 2 integers: [x, y]")
        }
        val x = runCatching { pair[0].asInt }
            .getOrElse { throw McpArgumentException("`$name[$index][0]` must be an integer") }
        val y = runCatching { pair[1].asInt }
            .getOrElse { throw McpArgumentException("`$name[$index][1]` must be an integer") }
        return intArrayOf(x, y)
    }

}

/** Result of a tool call, already shaped as MCP content blocks. */
class McpToolResult private constructor(
    val content: JsonArray,
    val isError: Boolean,
) {

    fun toJson(): JsonObject = McpJson.obj().apply {
        add("content", content)
        // Only emit `isError` when true: some clients are strict about
        // unexpected keys, and the spec treats absence as false.
        // zh-CN: 仅在为 true 时输出 `isError`: 部分客户端对意外键较严格,
        // 且规范中缺省即视为 false.
        if (isError) {
            addProperty("isError", true)
        }
    }

    companion object {

        fun text(text: String): McpToolResult = of(
            McpJson.obj().apply {
                addProperty("type", "text")
                addProperty("text", text)
            },
            isError = false,
        )

        /** Serializes [payload] as pretty JSON inside a text block. */
        fun json(payload: Any): McpToolResult = text(McpJson.gson.toJson(payload))

        fun error(message: String): McpToolResult = of(
            McpJson.obj().apply {
                addProperty("type", "text")
                addProperty("text", message)
            },
            isError = true,
        )

        fun image(base64Data: String, mimeType: String, caption: String? = null): McpToolResult {
            val blocks = JsonArray()
            if (!caption.isNullOrBlank()) {
                blocks.add(McpJson.obj().apply {
                    addProperty("type", "text")
                    addProperty("text", caption)
                })
            }
            blocks.add(McpJson.obj().apply {
                addProperty("type", "image")
                addProperty("data", base64Data)
                addProperty("mimeType", mimeType)
            })
            return McpToolResult(blocks, isError = false)
        }

        private fun of(block: JsonObject, isError: Boolean): McpToolResult =
            McpToolResult(JsonArray().apply { add(block) }, isError)

    }

}

/** Minimal JSON Schema builders, kept small on purpose. */
object McpSchema {

    fun emptyObject(): JsonObject = McpJson.obj().apply {
        addProperty("type", "object")
        add("properties", JsonObject())
    }

    fun objectOf(
        properties: Map<String, JsonObject>,
        required: List<String> = emptyList(),
        additionalProperties: Boolean = false,
    ): JsonObject = McpJson.obj().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            properties.forEach { (name, schema) -> add(name, schema) }
        })
        if (required.isNotEmpty()) {
            add("required", JsonArray().apply { required.forEach { add(JsonPrimitive(it)) } })
        }
        addProperty("additionalProperties", additionalProperties)
    }

    fun string(
        description: String,
        enumValues: List<String>? = null,
        default: String? = null,
    ): JsonObject = McpJson.obj().apply {
        addProperty("type", "string")
        addProperty("description", description)
        if (enumValues != null) {
            add("enum", JsonArray().apply { enumValues.forEach { add(JsonPrimitive(it)) } })
        }
        if (default != null) {
            addProperty("default", default)
        }
    }

    /**
     * An object whose keys are not known in advance, such as HTTP headers.
     *
     * @Note
     *  ! Plain JSON Schema expresses this with `additionalProperties`, not with a
     *  ! `"*"` property name. A `"*"` entry would look plausible in the emitted
     *  ! schema but no client honours it, so the caller would silently receive no
     *  ! guidance at all. zh-CN: 标准 JSON Schema 用 `additionalProperties`
     *  ! 表达这一点, 而不是用 `"*"` 作为属性名. `"*"` 写在输出的 schema 里看似合理,
     *  ! 但没有任何客户端会理会它, 调用方因此会静默地得不到任何提示.
     */
    fun freeObject(
        description: String,
        valueSchema: JsonObject = string("Value, coerced to a string."),
    ): JsonObject = McpJson.obj().apply {
        addProperty("type", "object")
        addProperty("description", description)
        add("additionalProperties", valueSchema)
    }

    fun integer(
        description: String,
        default: Int? = null,
        minimum: Int? = null,
        maximum: Int? = null,
    ): JsonObject = McpJson.obj().apply {
        addProperty("type", "integer")
        addProperty("description", description)
        if (default != null) addProperty("default", default)
        if (minimum != null) addProperty("minimum", minimum)
        if (maximum != null) addProperty("maximum", maximum)
    }

    fun boolean(description: String, default: Boolean? = null): JsonObject = McpJson.obj().apply {
        addProperty("type", "boolean")
        addProperty("description", description)
        if (default != null) addProperty("default", default)
    }

    /**
     * Emits a fractional number.
     *
     * @Note
     *  ! The default is written as a `Double` rather than an `Int` even when the
     *  ! value happens to be whole, so the emitted schema never disagrees with
     *  ! the declared `type`.
     *  ! zh-CN: 即使默认值恰好是整数也按 `Double` 写出, 以免输出的 schema 与
     *  ! 声明的 `type` 自相矛盾.
     */
    fun number(
        description: String,
        default: Double? = null,
        minimum: Double? = null,
        maximum: Double? = null,
    ): JsonObject = McpJson.obj().apply {
        addProperty("type", "number")
        addProperty("description", description)
        if (default != null) addProperty("default", default)
        if (minimum != null) addProperty("minimum", minimum)
        if (maximum != null) addProperty("maximum", maximum)
    }

    fun arrayOf(description: String, items: JsonObject): JsonObject = McpJson.obj().apply {
        addProperty("type", "array")
        addProperty("description", description)
        add("items", items)
    }

}
