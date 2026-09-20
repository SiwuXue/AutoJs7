package org.autojs.autojs.mcp.tools

import com.google.gson.JsonObject
import org.autojs.autojs.core.accessibility.UiSelector
import org.autojs.autojs.core.automator.GlobalActionAutomator
import org.autojs.autojs.core.automator.UiObject
import org.autojs.autojs.mcp.McpArgumentException
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpNode
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpSelector
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.mcp.McpUi

/**
 * Tools that manipulate the screen: node actions and global navigation.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Design
 *  ! One `ui_action` tool with an `action` parameter is preferred over dozens of
 *  ! near-identical tools. It keeps the advertised tool list small, which
 *  ! measurably improves tool selection, and it lets every action share the same
 *  ! selector handling.
 *  ! zh-CN: 采用"单个 ui_action + action 参数"而非几十个近乎相同的工具.
 *  ! 这样既能让对外暴露的工具列表保持精简 (经验上能明显提升模型的工具选择准确率),
 *  ! 又能让所有动作复用同一套选择器处理逻辑.
 */
internal object McpUiActionTools {

    /** A tap long enough to register, with a non-degenerate path. zh-CN: 可被识别且路径非退化的点击. */
    private const val TAP_DURATION_MS = 80L

    /**
     * Parameters owned by `ui_action` rather than by the selector. Every one of
     * them has to be listed, or the selector builder rejects it as an unknown
     * field and the whole call fails -- which is what used to happen to `action`.
     * zh-CN: 属于 `ui_action` 而非选择器的参数. 必须全部列出, 否则选择器构造器会
     * 把它当成未知字段拒绝, 整个调用随之失败 —— `action` 此前正是如此.
     */
    private val ACTION_TOOL_KEYS = setOf("action", "inputText", "index", "fallbackTap")

    /**
     * Actions expressed through the accessibility node API. The second argument
     * is the `inputText` parameter, required only by `setText`.
     * zh-CN: 通过无障碍节点 API 表达的动作. 第二个参数为 `inputText`, 仅 `setText` 需要.
     */
    private val NODE_ACTIONS: Map<String, (UiObject, String?) -> Boolean> = mapOf(
        "click" to { node, _ -> node.click() },
        "longClick" to { node, _ -> node.longClick() },
        "setText" to { node, text ->
            node.setText(
                text ?: throw McpArgumentException("`inputText` is required when `action` is `setText`")
            )
        },
        "focus" to { node, _ -> node.focus() },
        "clearFocus" to { node, _ -> node.clearFocus() },
        "select" to { node, _ -> node.select() },
        "copy" to { node, _ -> node.copy() },
        "cut" to { node, _ -> node.cut() },
        "paste" to { node, _ -> node.paste() },
        "expand" to { node, _ -> node.expand() },
        "collapse" to { node, _ -> node.collapse() },
        "dismiss" to { node, _ -> node.dismiss() },
        "show" to { node, _ -> node.show() },
        "scrollForward" to { node, _ -> node.scrollForward() },
        "scrollBackward" to { node, _ -> node.scrollBackward() },
        "scrollUp" to { node, _ -> node.scrollUp() },
        "scrollDown" to { node, _ -> node.scrollDown() },
        "scrollLeft" to { node, _ -> node.scrollLeft() },
        "scrollRight" to { node, _ -> node.scrollRight() },
        "pageUp" to { node, _ -> node.pageUp() },
        "pageDown" to { node, _ -> node.pageDown() },
        "pageLeft" to { node, _ -> node.pageLeft() },
        "pageRight" to { node, _ -> node.pageRight() },
        "pressAndHold" to { node, _ -> node.pressAndHold() },
        "contextClick" to { node, _ -> node.contextClick() },
        "imeEnter" to { node, _ -> node.imeEnter() },
        "accessibilityFocus" to { node, _ -> node.accessibilityFocus() },
        "clearAccessibilityFocus" to { node, _ -> node.clearAccessibilityFocus() },
    )

    /** Device level navigation actions. zh-CN: 设备级导航动作. */
    private val GLOBAL_ACTIONS: Map<String, (GlobalActionAutomator) -> Boolean> = mapOf(
        "back" to { it.back() },
        "home" to { it.home() },
        "recents" to { it.recents() },
        "notifications" to { it.notifications() },
        "quickSettings" to { it.quickSettings() },
        "powerDialog" to { it.powerDialog() },
        "splitScreen" to { it.splitScreen() },
        "lockScreen" to { it.lockScreen() },
    )

    /**
     * @Note
     *  ! Declared after the action tables on purpose. Object properties are
     *  ! initialised in textual order, and building these tools reads
     *  ! `NODE_ACTIONS`, so declaring `tools` above it would observe a null map
     *  ! and fail class initialisation outright.
     *  ! zh-CN: 刻意声明在动作表之后. 对象属性按文本顺序初始化, 而构建这些工具时会读取
     *  ! `NODE_ACTIONS`, 若把 `tools` 声明在其上方, 读到的将是 null map,
     *  ! 进而直接导致类初始化失败.
     */
    val tools: List<McpTool> = listOf(uiActionTool(), globalActionTool())

    // --------------------------------------------------------------- ui_action

    private fun uiActionTool(): McpTool = McpTool(
        name = "ui_action",
        title = "Act on a node",
        description = buildString {
            append("Finds a node with a selector and performs one accessibility action on it. ")
            append("The selector uses exactly the same fields as `ui_find`, and a non-empty filter is required. ")
            append("Typical use: {\"action\": \"click\", \"textContains\": \"Sign in\"}. ")
            append("To type into a field, first click the field, then call again with ")
            append("{\"action\": \"setText\", \"inputText\": \"hello\", \"editable\": true}. ")
            append("`inputText` carries the text to type; for matching a node by its exact text use `text`. ")
            append("When the node rejects a click, the tool falls back to a real gesture tap on the node center ")
            append("unless `fallbackTap` is false.")
        },
        risk = McpToolRisk.SENSITIVE,
        inputSchema = McpUiQueryTools.selectorSchema(
            extra = mapOf(
                "action" to McpSchema.string(
                    "The accessibility action to perform.",
                    NODE_ACTIONS.keys.sorted(),
                ),
                "inputText" to McpSchema.string(
                    "Text to type. Required by `setText`, ignored by every other action. " +
                            "Not to be confused with the selector's `text`, which matches a node by its exact text.",
                ),
                "index" to McpSchema.integer(
                    "Which match to act on, ordered by the traversal algorithm.",
                    0, 0, 100,
                ),
                "fallbackTap" to McpSchema.boolean(
                    "When a `click` is rejected by the node, retry with a gesture tap on its center.",
                    true,
                ),
            ),
            requiredExtra = listOf("action"),
        ),
    ) { args -> invokeUiAction(args) }

    private fun invokeUiAction(args: McpArgs): McpToolResult {
        val spec = args.rawObject()
        if (McpSelector.isEmpty(spec, ACTION_TOOL_KEYS)) {
            throw McpArgumentException(
                "A non-empty selector is required, otherwise the action could hit an arbitrary node. " +
                        "Try `{\"textContains\": \"...\"}`."
            )
        }

        val action = args.requireString("action")
        val performer = NODE_ACTIONS[action]
            ?: throw McpArgumentException(
                "Unknown action `$action`. Supported actions: ${NODE_ACTIONS.keys.sorted().joinToString(", ")}"
            )

        val index = args.optInt("index", 0).coerceIn(0, 100)
        val inputText = args.optString("inputText")
        val fallbackTap = args.optBoolean("fallbackTap", true)

        val selector: UiSelector = McpSelector.build(spec, ACTION_TOOL_KEYS)

        // Guard after the request checks, before the first service call.
        // zh-CN: 守卫放在请求检查之后, 首次调用服务之前.
        McpUi.requireAccessibilityService()

        val node = selector.findOnce(index)
        if (node == null) {
            // A read that fails because accessibility is unavailable and a read
            // that finds nothing both land here as null. Blaming the selector for
            // the first case is what makes a caller discard a filter that was
            // never wrong, so the two are separated before anything is reported.
            // zh-CN: 因无障碍不可用而失败的读取, 与什么都找不到的读取, 在此都得到 null.
            // 把前一种归咎于选择器, 会让调用方丢掉一个从未出错的过滤条件,
            // 因此先区分两者, 再向外报告.
            McpUi.requireReadableScreen()
            return McpToolResult.error(
                "No node matched the selector, so `$action` was not performed. " +
                        "Call `ui_find` with the same filter to see what is available."
            )
        }

        val summary = McpNode.summary(node)
        val performed = runCatching { performer(node, inputText) }.getOrElse { error ->
            return McpToolResult.error(
                "`$action` threw ${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        }

        val result = McpJson.obj().apply {
            addProperty("action", action)
            add("matched", summary)
        }

        if (performed) {
            result.addProperty("ok", true)
            return McpToolResult.json(result)
        }

        // The node rejected the action. For `click` a real touch gesture on the
        // node center usually still works, because plenty of widgets render a
        // clickable area without exposing an accessibility click action.
        // zh-CN: 节点拒绝了该动作. 对于 `click`, 在节点中心执行真实触摸手势通常仍能生效,
        // 因为不少控件绘制了可点击区域, 却没有暴露无障碍点击动作.
        if (action == "click" && fallbackTap) {
            val tapped = tapCenterOf(node)
            result.addProperty("ok", tapped)
            result.addProperty("fallback", if (tapped) "gestureTap" else "none")
            if (!tapped) addActionFailureHint(result, node)
        } else {
            result.addProperty("ok", false)
            addActionFailureHint(result, node)
        }

        return McpToolResult.json(result)
    }

    private fun addActionFailureHint(target: JsonObject, node: UiObject) {
        target.addProperty(
            "hint",
            "The node rejected the action and the gesture fallback did not help. " +
                    "It is likely not interactive" +
                    (if (node.clickable()) "" else " (clickable = false)") +
                    ". Call `ui_find` with `clickable: true` to locate an ancestor that is."
        )
    }

    /**
     * Falls back to a real touch gesture at the node center, which works for
     * widgets that expose no click action.
     * zh-CN: 退化为在节点中心执行真实触摸手势, 用于那些未暴露点击动作的控件.
     */
    private fun tapCenterOf(node: UiObject): Boolean = runCatching {
        val x = node.exactCenterX().toInt()
        val y = node.exactCenterY().toInt()
        // A degenerate path can be rejected by the framework, so nudge the
        // second point by one pixel.
        // zh-CN: 退化的路径可能被框架拒绝, 因此将第二个点偏移一个像素.
        McpUi.automator().gesture(0, TAP_DURATION_MS, intArrayOf(x, y, x + 1, y + 1))
    }.getOrDefault(false)

    // ----------------------------------------------------------- global_action

    private fun globalActionTool(): McpTool = McpTool(
        name = "global_action",
        title = "Perform a global action",
        description = buildString {
            append("Performs a device level navigation action that needs no node: ")
            append("go back, go home, open recents, pull down the notification shade, ")
            append("open quick settings, lock the screen and so on. ")
            append("Use this instead of hunting for the system navigation buttons in the tree.")
        },
        risk = McpToolRisk.SENSITIVE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "action" to McpSchema.string(
                    "The global action to perform.",
                    GLOBAL_ACTIONS.keys.sorted(),
                ),
            ),
            required = listOf("action"),
        ),
    ) { args -> invokeGlobalAction(args) }

    private fun invokeGlobalAction(args: McpArgs): McpToolResult {
        val action = args.requireString("action")
        val performer = GLOBAL_ACTIONS[action]
            ?: throw McpArgumentException(
                "Unknown global action `$action`. Supported actions: ${GLOBAL_ACTIONS.keys.sorted().joinToString(", ")}"
            )

        // Guard after the request checks, before the first service call.
        // zh-CN: 守卫放在请求检查之后, 首次调用服务之前.
        McpUi.requireAccessibilityService()

        val performed = runCatching { performer(McpUi.automator()) }.getOrDefault(false)

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("action", action)
            addProperty("ok", performed)
            if (!performed) {
                addProperty(
                    "hint",
                    "The framework refused the global action on this Android version, " +
                            "or the AutoJs6 accessibility service lost its connection.",
                )
            }
        })
    }

}
