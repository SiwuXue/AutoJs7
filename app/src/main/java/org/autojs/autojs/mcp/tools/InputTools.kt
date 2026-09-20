package org.autojs.autojs.mcp.tools

import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import com.google.gson.JsonArray
import org.autojs.autojs.mcp.McpArgumentException
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.mcp.McpUi
import org.autojs.autojs6.R

/**
 * Raw input tools: touch gestures and the clipboard.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Design
 *  ! Gestures are intentionally low level -- a list of strokes, each a list of
 *  ! points -- rather than a menu of `swipeUp` / `swipeDown` presets. Every
 *  ! preset eventually needs an escape hatch, and one general primitive covers
 *  ! swipes, drags, flings, multi-finger pinches and long presses with a single
 *  ! schema.
 *  ! zh-CN: 手势刻意保持底层形态 —— 一组笔画, 每个笔画是一串点 —— 而不是
 *  ! `swipeUp` / `swipeDown` 这类预设. 任何预设最终都需要逃生舱, 而一个通用原语
 *  ! 用同一套 schema 就能覆盖滑动, 拖拽, 快速甩动, 多指缩放与长按.
 */
internal object McpInputTools {

    val tools: List<McpTool> = listOf(gestureTool(), setClipboardTool(), readClipboardTool())

    // ----------------------------------------------------------------- gesture

    private fun gestureTool(): McpTool = McpTool(
        name = "gesture",
        title = "Perform a touch gesture",
        description = buildString {
            append("Sends raw touch input described by one or more strokes. ")
            append("A swipe is `{\"points\": [[x1, y1], [x2, y2]], \"durationMs\": 300}` and ")
            append("a tap is the same with a short duration. ")
            append("Use `strokes` instead of `points` to press several fingers at once, for example a pinch. ")
            append("Prefer `ui_action` with `click` when a node is clickable: it is more reliable than synthesised touch, ")
            append("and it survives layout changes that would move the coordinates. ")
            append("Call `get_device_info` for the screen size so coordinates can be derived from the node tree.")
        },
        risk = McpToolRisk.SENSITIVE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "points" to McpSchema.arrayOf(
                    "A single stroke as a list of [x, y] screen coordinates, at least 2 points. " +
                            "Intermediate points make the gesture a curve.",
                    McpSchema.arrayOf("One [x, y] pair.", McpSchema.integer("Coordinate in screen pixels.")),
                ),
                "strokes" to McpSchema.arrayOf(
                    "Multiple simultaneous strokes for multi-touch, each stroke being a list of [x, y] points. " +
                            "Takes precedence over `points`.",
                    McpSchema.arrayOf(
                        "One stroke as a list of [x, y] points.",
                        McpSchema.arrayOf("One [x, y] pair.", McpSchema.integer("Coordinate in screen pixels.")),
                    ),
                ),
                "durationMs" to McpSchema.integer(
                    "How long the gesture takes. Short values fling, long values drag.",
                    300, 1, 60_000,
                ),
                "startDelayMs" to McpSchema.integer(
                    "Delay before the gesture begins, in milliseconds.",
                    0, 0, 60_000,
                ),
            ),
        ),
    ) { args -> invokeGesture(args) }

    private fun invokeGesture(args: McpArgs): McpToolResult {
        val strokes = args.optStrokes("strokes")?.takeIf { it.isNotEmpty() }
        val points = args.optPoints("points")?.takeIf { it.isNotEmpty() }
        val durationMs = args.optInt("durationMs", 300).coerceIn(1, 60_000)
        val startDelayMs = args.optInt("startDelayMs", 0).coerceIn(0, 60_000)

        val resolved = strokes ?: points?.let { listOf(it) }
        ?: throw McpArgumentException(
            "Provide `points` for a single stroke, or `strokes` for multi-touch."
        )

        resolved.forEachIndexed { index, stroke ->
            if (stroke.size < 2) {
                throw McpArgumentException(
                    "Stroke $index has ${stroke.size} point(s) but at least 2 are required, " +
                            "because Android rejects a zero-length path."
                )
            }
        }

        // Guard after the request checks, before the first service call.
        // zh-CN: 守卫放在请求检查之后, 首次调用服务之前.
        McpUi.requireAccessibilityService()

        val descriptions = resolved.mapIndexed { index, stroke ->
            GestureDescription.StrokeDescription(
                toPath(stroke),
                // Total delay so every stroke of a multi-touch gesture still
                // begins together rather than in sequence.
                // zh-CN: 使用总延迟让多指手势的各笔画同时开始, 而不是依次开始.
                startDelayMs.toLong() + index * 0L,
                durationMs.toLong(),
            )
        }

        val performed = runCatching {
            McpUi.automator().gestures(*descriptions.toTypedArray())
        }.getOrDefault(false)

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("ok", performed)
            addProperty("durationMs", durationMs)
            addProperty("startDelayMs", startDelayMs)
            add("strokes", JsonArray().apply {
                resolved.forEach { stroke ->
                    add(JsonArray().apply {
                        stroke.forEach { point ->
                            add(JsonArray().apply {
                                add(point[0])
                                add(point[1])
                            })
                        }
                    })
                }
            })
            if (!performed) {
                addProperty(
                    "hint",
                    "The framework rejected the gesture. Coordinates outside the screen or a stroke shorter " +
                            "than the minimum swipe distance are the usual causes."
                )
            }
        })
    }

    private fun toPath(points: List<IntArray>): Path = Path().apply {
        moveTo(points.first()[0].toFloat(), points.first()[1].toFloat())
        for (index in 1 until points.size) {
            lineTo(points[index][0].toFloat(), points[index][1].toFloat())
        }
    }

    // ---------------------------------------------------------- set_clipboard

    private fun setClipboardTool(): McpTool = McpTool(
        name = "set_clipboard",
        title = "Set the clipboard",
        description = buildString {
            append("Writes text to the system clipboard. ")
            append("The usual sequence for typing into a stubborn input field is to set the clipboard here, ")
            append("then call `ui_action` with `paste` on the field. ")
            append("Note that from Android 10 onwards a background app can write the clipboard but only the ")
            append("focused app can read it, so there is no matching `get_clipboard`.")
        },
        risk = McpToolRisk.SENSITIVE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "text" to McpSchema.string("Text to place on the clipboard. An empty string clears it."),
                "label" to McpSchema.string("Clip description shown by the system. Defaults to the app name."),
            ),
            required = listOf("text"),
        ),
    ) { args -> invokeSetClipboard(args) }

    private fun invokeSetClipboard(args: McpArgs): McpToolResult {
        val text = args.optString("text")
            ?: throw McpArgumentException("`text` is required; pass an empty string to clear the clipboard.")
        val label = args.optString("label")?.takeIf { it.isNotBlank() }
            ?: McpUi.context.getString(R.string.text_mcp_server_name)

        val clipboard = McpUi.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return McpToolResult.error("This device exposes no clipboard service.")

        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        }.fold(
            onSuccess = {
                McpToolResult.json(McpJson.obj().apply {
                    addProperty("ok", true)
                    addProperty("length", text.length)
                })
            },
            onFailure = {
                McpToolResult.error(
                    "Writing to the clipboard failed: ${it.message ?: it::class.java.simpleName}"
                )
            },
        )
    }

    // ---------------------------------------------------------- read_clipboard

    /**
     * @Note
     *  ! `primaryClip` returns null both when the clipboard is empty and when the
     *  ! system denies the read, so the two are indistinguishable through the API.
     *  ! The result therefore reports `readable: false` with an explanation instead
     *  ! of fabricating an empty string -- an empty answer would be read by the AI
     *  ! client as "the clipboard is empty", which is exactly the false fact this
     *  ! tool exists to avoid.
     *  ! zh-CN: 剪贴板为空与系统拒绝读取在 API 上都表现为 null, 二者无法区分.
     *  ! 因此结果以 `readable: false` 加解释返回, 而不是编造一个空字符串 ——
     *  ! 空答案会被 AI 客户端读成"剪贴板是空的", 这恰恰是本工具要避免的错误事实.
     */
    private fun readClipboardTool(): McpTool = McpTool(
        name = "clipboard_read",
        title = "Read the clipboard",
        description = buildString {
            append("Reads the current clipboard text. ")
            append("From Android 10 onwards only an app holding window focus can read, so this succeeds ")
            append("while the user is inside AutoJs6 (the typical flow: they copy something, open AutoJs6, ")
            append("and ask) and reports `readable: false` otherwise. ")
            append("A `readable: false` answer means either an empty clipboard or a denied read -- the two ")
            append("look identical to the API; ask the user to copy something and retry to tell them apart.")
        },
        risk = McpToolRisk.SENSITIVE,
        inputSchema = McpSchema.emptyObject(),
    ) { _ -> invokeReadClipboard() }

    private fun invokeReadClipboard(): McpToolResult {
        val clipboard = McpUi.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return McpToolResult.error("This device exposes no clipboard service.")

        val clip = runCatching { clipboard.primaryClip }.getOrElse {
            return McpToolResult.error(
                "Reading the clipboard failed: ${it::class.java.simpleName}: ${it.message ?: "no message"}"
            )
        }

        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
        if (text == null) {
            // Not an error: an empty clipboard and a focus-denied read land here
            // alike, and reporting one of them as the other sends the client off
            // with a fact nobody verified.
            // zh-CN: 这不是错误: 剪贴板为空与读取被拒在此处表现相同,
            // 把其中一个说成另一个, 会让客户端带着一个未经核实的事实离开.
            return McpToolResult.json(McpJson.obj().apply {
                addProperty("readable", false)
                addProperty(
                    "hint",
                    "Either the clipboard is empty or Android denied the read: from Android 10 onwards " +
                            "only the app holding window focus can read it. This call succeeds while the " +
                            "user is inside AutoJs6; ask them to copy something and retry to distinguish " +
                            "the two cases.",
                )
            })
        }

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("readable", true)
            addProperty("text", text.toString())
            addProperty("length", text.length)
            // `label` is a CharSequence; JsonProperty accepts String/Number/Boolean/Character only.
            // zh-CN: `label` 是 CharSequence, 而 JsonProperty 只接受 String/Number/Boolean/Character.
            clip.description?.label?.takeIf { it.isNotBlank() }?.let { addProperty("label", it.toString()) }
        })
    }

}
