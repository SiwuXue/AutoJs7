package org.autojs.autojs.mcp.tools

import com.google.gson.JsonArray
import org.autojs.autojs.mcp.McpArgumentException
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpLogBuffer
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Console output retrieval.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Design
 *  ! The pull model is deliberate. A script the model started may still be
 *  ! running, so `read_log` returns the newest matching entries plus a cursor
 *  ! (`latestSequence`) that the caller passes back next time to get only what
 *  ! is new. That turns "is my script still logging?" into a cheap incremental
 *  ! poll instead of re-downloading the whole buffer.
 *  ! zh-CN: 刻意采用拉取模型. 模型启动的脚本可能仍在运行, 因此 `read_log` 返回最新的
 *  ! 匹配条目以及一个游标 (`latestSequence`), 调用方下次传回该游标即可只获取新增内容.
 *  ! 这把"我的脚本还在输出吗"变成了廉价的增量轮询, 而无需反复下载整个缓冲区.
 */
internal object McpLogTools {

    private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Ordered from least to most severe. zh-CN: 按严重程度从低到高排列. */
    private val LEVELS = listOf("V", "D", "I", "W", "E", "A")

    /**
     * @Note
     *  ! Declared after `LEVELS` on purpose. Object properties are initialised in
     *  ! textual order, so declaring `tools` above it would read a null list and
     *  ! silently drop the `minLevel` enum from the emitted schema rather than
     *  ! reporting anything.
     *  ! zh-CN: 刻意声明在 `LEVELS` 之后. 对象属性按文本顺序初始化,
     *  ! 若把 `tools` 声明在其上方, 读到的将是 null 列表, 结果是 `minLevel` 的枚举
     *  ! 被静默地从输出的 schema 中丢掉, 而不会有任何报错.
     */
    val tools: List<McpTool> = listOf(readLogTool())

    private fun readLogTool(): McpTool = McpTool(
        name = "read_log",
        title = "Read console output",
        description = buildString {
            append("Returns recent script console output. ")
            append("Call it after starting a script to check whether it worked, or while it runs to see progress and errors. ")
            append("Pass the `latestSequence` from the previous call as `sinceSequence` to receive only new lines. ")
            append("Filter with `minLevel` to skip noise and with `contains` to look for one specific failure.")
        },
        risk = McpToolRisk.SAFE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "sinceSequence" to McpSchema.integer(
                    "Only return entries newer than this cursor. Pass the `latestSequence` from the previous call.",
                    0, 0, Int.MAX_VALUE,
                ),
                "limit" to McpSchema.integer(
                    "Keep at most this many entries, counted from the newest end.",
                    100, 1, McpLogBuffer.capacity,
                ),
                "minLevel" to McpSchema.string(
                    "Minimum severity to include. `W` therefore returns warnings, errors and assertions.",
                    LEVELS,
                    "V",
                ),
                "contains" to McpSchema.string(
                    "Case insensitive substring the message must contain."
                ),
            ),
        ),
    ) { args -> invokeReadLog(args) }

    private fun invokeReadLog(args: McpArgs): McpToolResult {
        val sinceSequence = args.rawObject()?.get("sinceSequence")
            ?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asLong }.getOrNull() }

        val limit = args.optInt("limit", 100).coerceIn(1, McpLogBuffer.capacity)
        val minLevel = args.optString("minLevel", "V")?.uppercase() ?: "V"
        val contains = args.optString("contains")?.takeIf { it.isNotBlank() }?.lowercase()

        if (minLevel !in LEVELS) {
            throw McpArgumentException(
                "Unknown `minLevel` `$minLevel`. Supported values: ${LEVELS.joinToString(", ")}"
            )
        }

        val minimumRank = LEVELS.indexOf(minLevel)
        val snapshot = McpLogBuffer.snapshot(sinceSequence, limit)
            .filter { LEVELS.indexOf(it.level).let { rank -> rank < 0 || rank >= minimumRank } }
            .filter { contains == null || it.message.lowercase().contains(contains) }

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("count", snapshot.size)
            addProperty("latestSequence", McpLogBuffer.latestSequence())
            addProperty("capacity", McpLogBuffer.capacity)
            if (snapshot.isEmpty()) {
                addProperty(
                    "hint",
                    "No matching output. If a script was just started, give it a moment and read again " +
                            "with the same `sinceSequence`."
                )
            }
            add("entries", JsonArray().apply {
                snapshot.forEach { entry ->
                    add(McpJson.obj().apply {
                        addProperty("seq", entry.sequence)
                        addProperty("time", TIME_FORMAT.format(Date(entry.timestamp)))
                        addProperty("level", entry.level)
                        addProperty("message", entry.message)
                    })
                }
            })
        })
    }

}
