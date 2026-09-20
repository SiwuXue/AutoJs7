package org.autojs.autojs.mcp

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Shared JSON entry points for the MCP package.
 *
 * @Created by fork author on Sep 16, 2026.
 */
internal object McpJson {

    /**
     * A single Gson instance is safe to share: both `toJson` and `fromJson` are
     * thread-safe and Gson keeps no mutable state between calls.
     * zh-CN: 共享单个 Gson 实例是安全的: `toJson` 与 `fromJson` 均为线程安全,
     * 且 Gson 在调用之间不保留可变状态.
     */
    val gson = Gson()

    fun obj(): JsonObject = JsonObject()

    fun parse(text: String): JsonElement = gson.fromJson(text, JsonElement::class.java)

    fun parseOrNull(text: String): JsonElement? = runCatching { parse(text) }.getOrNull()

    fun stringify(element: JsonElement): String = gson.toJson(element)

}
