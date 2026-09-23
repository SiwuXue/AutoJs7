package org.autojs.autojs.mcp.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpNode
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpSelector
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.mcp.McpUi
import org.autojs.autojs.core.automator.UiObject
import org.autojs.autojs.runtime.api.ScreenMetrics
import org.autojs.autojs6.R

/**
 * Read-only tools that inspect the current screen.
 *
 * @Created by fork author on Sep 16, 2026.
 */
internal object McpUiQueryTools {

    // ------------------------------------------------------------------ schema

    private val QUERY_FIELD_DESCRIPTIONS = mapOf(
        "id" to "View id such as `com.android.settings:id/search` or just `search`.",
        "text" to "Visible text of the node.",
        "desc" to "Content description, i.e. the accessibility label.",
        "content" to "Either the content description or the text.",
        "className" to "Fully qualified widget class such as `android.widget.Button`.",
        "packageName" to "Package that owns the node.",
    )

    private val MATCH_MODE_SUFFIXES = mapOf(
        "" to "",
        "StartsWith" to " Prefix match.",
        "EndsWith" to " Suffix match.",
        "Contains" to " Substring match.",
        "Match" to " Java regular expression match. The historical spelling `Matches` is accepted as an alias.",
    )

    /**
     * @Note
     *  ! Declared after the description tables on purpose. Object properties are
     *  ! initialised in textual order, and building these tools walks both maps
     *  ! through [selectorProperties], so declaring `tools` above them would
     *  ! observe nulls and fail class initialisation outright.
     *  ! zh-CN: 刻意声明在描述表之后. 对象属性按文本顺序初始化, 而构建这些工具会经由
     *  ! [selectorProperties] 遍历这两个 map, 若把 `tools` 声明在其上方,
     *  ! 读到的将是 null, 进而直接导致类初始化失败.
     */
    val tools: List<McpTool> = listOf(dumpUiTreeTool(), uiFindTool())

    /**
     * Builds the selector schema once, so `ui_find` and `ui_action` describe
     * exactly the same query surface.
     * zh-CN: 统一构建选择器 schema, 使 `ui_find` 与 `ui_action` 暴露完全一致的查询面.
     */
    internal fun selectorProperties(): Map<String, JsonObject> {
        val properties = LinkedHashMap<String, JsonObject>()

        QUERY_FIELD_DESCRIPTIONS.forEach { (field, description) ->
            MATCH_MODE_SUFFIXES.forEach { (suffix, modeDescription) ->
                properties[field + suffix] = McpSchema.string(description + modeDescription)
            }
        }

        properties["boundsInside"] = McpSchema.arrayOf(
            "Restrict matches to a screen rectangle given as [left, top, right, bottom].",
            McpSchema.integer("Coordinate in screen pixels."),
        )
        properties["depth"] = McpSchema.integer("Exact depth of the node inside the tree.")
        properties["idHex"] = McpSchema.string("Hex resource id such as `0x7f0a0123`.")
        properties["algorithm"] = McpSchema.string(
            "Traversal order. DFS returns the visually outermost match first and is usually correct.",
            listOf("DFS", "BFS"),
            "DFS",
        )
        properties["clickable"] = McpSchema.boolean("Only match nodes that can be clicked.")
        properties["longClickable"] = McpSchema.boolean("Only match nodes that support a long click.")
        properties["contextClickable"] = McpSchema.boolean("Only match nodes that support a context click.")
        properties["checkable"] = McpSchema.boolean("Only match nodes that can be checked.")
        properties["checked"] = McpSchema.boolean("Only match currently checked nodes.")
        properties["enabled"] = McpSchema.boolean("Only match enabled nodes.")
        properties["scrollable"] = McpSchema.boolean("Only match scrollable containers.")
        properties["editable"] = McpSchema.boolean("Only match text inputs.")
        properties["selected"] = McpSchema.boolean("Only match selected nodes.")
        properties["focusable"] = McpSchema.boolean("Only match focusable nodes.")
        properties["visibleToUser"] = McpSchema.boolean("Only match nodes visible to the user.")
        properties["dismissable"] = McpSchema.boolean("Only match dismissable nodes.")
        properties["accessibilityFocused"] = McpSchema.boolean("Only match the accessibility-focused node.")

        return properties
    }

    internal fun selectorSchema(
        extra: Map<String, JsonObject> = emptyMap(),
        requiredExtra: List<String> = emptyList(),
    ): JsonObject {
        val properties = LinkedHashMap(selectorProperties())
        properties.putAll(extra)
        return McpSchema.objectOf(
            properties = properties,
            required = requiredExtra,
        )
    }

    internal fun screenSize(): JsonObject = McpJson.obj().apply {
        addProperty("width", ScreenMetrics.deviceScreenWidth)
        addProperty("height", ScreenMetrics.deviceScreenHeight)
        addProperty("rotation", ScreenMetrics.rotation)
    }

    // ------------------------------------------------------------- dump_ui_tree

    private fun dumpUiTreeTool(): McpTool = McpTool(
        name = "dump_ui_tree",
        title = "Dump UI tree",
        description = buildString {
            append("Returns the accessibility node tree of the current screen as nested JSON. ")
            append("Use it once to understand an unfamiliar screen, then prefer `ui_find` for follow-up lookups: ")
            append("a full dump is expensive in tokens, while `ui_find` returns only what matches. ")
            append("Prefer `ui_action` over coordinate guessing whenever a node is clickable.")
        },
        risk = McpToolRisk.SAFE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "maxDepth" to McpSchema.integer(
                    "Maximum tree depth to descend. Deeper nodes are replaced by a `childrenOmitted` count.",
                    McpNode.DEFAULT_MAX_DEPTH, 1, 40,
                ),
                "maxNodes" to McpSchema.integer(
                    "Hard cap on the number of emitted nodes.",
                    McpNode.DEFAULT_MAX_NODES, 1, McpNode.HARD_MAX_NODES,
                ),
                "packageName" to McpSchema.string(
                    "Drop nodes whose package does not contain this substring, e.g. `com.tencent.mm`. " +
                            "Useful to hide system overlays.",
                ),
                "includeInvisible" to McpSchema.boolean(
                    "Include nodes that are not visible to the user. Off by default.",
                    false,
                ),
                "includeActions" to McpSchema.boolean(
                    "Include the list of accessibility actions each node supports. Increases output size.",
                    false,
                ),
                "textLimit" to McpSchema.integer(
                    "Truncate `text` and `desc` to this many characters. 0 disables truncation.",
                    McpNode.DEFAULT_TEXT_LIMIT, 0, 500,
                ),
            ),
        ),
    ) { args -> invokeDumpUiTree(args) }

    private fun invokeDumpUiTree(args: McpArgs): McpToolResult {
        val maxDepth = args.optInt("maxDepth", McpNode.DEFAULT_MAX_DEPTH).coerceIn(1, 40)
        val maxNodes = args.optInt("maxNodes", McpNode.DEFAULT_MAX_NODES)
            .coerceIn(1, McpNode.HARD_MAX_NODES)
        val packageFilter = args.optString("packageName")?.takeIf { it.isNotBlank() }
        val includeInvisible = args.optBoolean("includeInvisible", false)
        val includeActions = args.optBoolean("includeActions", false)
        val textLimit = args.optInt("textLimit", McpNode.DEFAULT_TEXT_LIMIT).coerceIn(0, 500)

        // Guard after the request checks, before the first service call.
        // zh-CN: 守卫放在请求检查之后, 首次调用服务之前.
        McpUi.requireAccessibilityService()

        val candidates = McpUi.dumpRootCandidates()
        if (candidates.isEmpty()) {
            return McpToolResult.error(
                "No active window is available. Make sure some app is in the foreground and the AutoJs6 accessibility service is connected. " +
                        McpUi.context.getString(R.string.mcp_dump_no_window_hint) +
                        " Windows: " + McpUi.describeWindows()
            )
        }

        fun build(entry: McpUi.DumpRoot, includeInvisible: Boolean): McpNode.TreeResult = McpNode.tree(
            root = entry.root,
            maxDepth = maxDepth,
            maxNodes = maxNodes,
            packageFilter = packageFilter,
            includeInvisible = includeInvisible,
            includeActions = includeActions,
            textLimit = textLimit,
        )

        // A tree that carries nothing but its own root is not an answer. WeChat
        // is the reference case: its window root is a placeholder (empty class,
        // zero bounds, no children, invisible), so the count is 1 -- insisting
        // on "non-zero" would stop right there and report a screen that looks
        // empty without saying why. The remaining windows get their turn, then
        // the retry that re-includes invisible nodes.
        // zh-CN: 除根节点外什么都没有的树不算答案. 微信正是参照案例:
        // 它的窗口根是一个占位节点 (class 为空, 边界全零, 无子节点, 不可见),
        // 计数为 1 —— 若只判断"非零"就会停在这里, 报出一个既空又不说明原因的屏幕.
        // 因此先让其余窗口依次获得机会, 再用"包含不可见节点"重试.
        fun usable(result: McpNode.TreeResult): Boolean = result.nodeCount > 1

        var selected = candidates.first()
        var tree = build(selected, includeInvisible)
        var fallback: String? = null

        if (!usable(tree)) {
            val attempts = ArrayList<Triple<McpUi.DumpRoot, McpNode.TreeResult, String>>()
            candidates.drop(1).forEach { attempts += Triple(it, build(it, includeInvisible), "windowFallback") }
            if (!includeInvisible) {
                candidates.forEach { attempts += Triple(it, build(it, true), "includeInvisible") }
            }

            val hit = attempts.firstOrNull { usable(it.second) }
            if (hit != null) {
                selected = hit.first
                tree = hit.second
                fallback = hit.third
            } else {
                // Nothing was usable; keep the most informative attempt anyway,
                // because `diagnostics` is what explains the situation.
                // zh-CN: 没有一个可用; 仍然保留信息量最大的那次尝试,
                // 因为解释情况的是 `diagnostics`.
                attempts.maxByOrNull { it.second.nodeCount }?.let {
                    if (it.second.nodeCount > tree.nodeCount) {
                        selected = it.first
                        tree = it.second
                        fallback = it.third
                    }
                }
            }
        }

        return McpToolResult.json(McpJson.obj().apply {
            add("screen", screenSize())
            add("window", describeWindow(selected))
            fallback?.let { addProperty("fallback", it) }
            add("root", tree.json)
            if (!usable(tree)) {
                add("diagnostics", describeEmptyTree(selected, candidates, tree))
                addProperty("hint", McpUi.context.getString(R.string.mcp_dump_empty_tree_hint))
            }
        })
    }

    /**
     * Metadata of the window whose root was dumped, so the caller can tell
     * which window the tree belongs to instead of assuming it is the whole
     * screen.
     * zh-CN: 被 dump 的根节点所属窗口的元信息, 使调用方知道这棵树属于哪个窗口,
     * 而不是想当然地认为它就是整块屏幕.
     */
    private fun describeWindow(entry: McpUi.DumpRoot): JsonObject = McpJson.obj().apply {
        addProperty("active", entry.fromActiveWindow)
        addProperty("windowIndex", entry.windowIndex)
        entry.packageName?.let { addProperty("packageName", it) }
        entry.title?.let { addProperty("title", it) }
        addProperty("type", entry.type)
        addProperty("layer", entry.layer)
    }

    /**
     * Machine readable explanation of a dump that produced no nodes, so the
     * caller can tell "the screen really is empty" apart from "this app does
     * not expose anything to accessibility".
     * zh-CN: 空树时给出的机器可读解释, 使调用方能区分"屏幕本来就是空的"
     * 与"这个应用没有向无障碍暴露任何内容".
     */
    private fun describeEmptyTree(
        selected: McpUi.DumpRoot,
        candidates: List<McpUi.DumpRoot>,
        tree: McpNode.TreeResult,
    ): JsonObject = McpJson.obj().apply {
        addProperty("empty", true)
        addProperty("prunedInvisible", tree.prunedInvisible)
        selected.root.className()?.let { addProperty("rootClassName", it) }
        addProperty("rootVisible", selected.root.visibleToUser())
        addProperty("rootChildCount", selected.root.childCount())
        add("candidates", JsonArray().apply { candidates.forEach { add(describeWindow(it)) } })
    }

    // ------------------------------------------------------------------ ui_find

    private fun uiFindTool(): McpTool = McpTool(
        name = "ui_find",
        title = "Find nodes",
        description = buildString {
            append("Finds accessibility nodes matching a selector and returns their bounds and centers. ")
            append("This is the cheap alternative to `dump_ui_tree`. ")
            append("Field names accept a match-mode suffix, so `text` is equality while ")
            append("`textContains`, `textStartsWith`, `textEndsWith` and `textMatch` are the other modes. ")
            append("The same suffixes work for `id`, `desc`, `content`, `className` and `packageName`. ")
            append("Set `timeoutMs` to wait for a node that has not appeared yet instead of polling repeatedly.")
        },
        risk = McpToolRisk.SAFE,
        inputSchema = selectorSchema(
            extra = mapOf(
                "maxResults" to McpSchema.integer(
                    "Maximum number of matches to return.",
                    10, 1, 100,
                ),
                "timeoutMs" to McpSchema.integer(
                    "Keep re-querying until at least one match appears or this budget elapses.",
                    0, 0, 30_000,
                ),
                "includeActions" to McpSchema.boolean(
                    "Include the supported accessibility actions of each match.",
                    false,
                ),
            ),
        ),
    ) { args -> invokeUiFind(args) }

    private fun invokeUiFind(args: McpArgs): McpToolResult {
        val spec = args.rawObject()
        val selector = McpSelector.build(spec)
        val maxResults = args.optInt("maxResults", 10).coerceIn(1, 100)
        val timeoutMs = args.optInt("timeoutMs", 0).coerceIn(0, 30_000)
        val includeActions = args.optBoolean("includeActions", false)

        if (McpSelector.isEmpty(spec)) {
            // An empty selector matches everything, which is almost never what the
            // caller wants and would silently return arbitrary nodes.
            // zh-CN: 空选择器会匹配所有节点, 这几乎从不是调用方的本意,
            // 且会静默返回任意节点.
            throw org.autojs.autojs.mcp.McpArgumentException(
                "At least one filter is required, otherwise every node matches. " +
                        "Try `{\"textContains\": \"...\"}` or `{\"className\": \"android.widget.Button\"}`."
            )
        }

        // Guard after the request checks, before the first service call.
        // zh-CN: 守卫放在请求检查之后, 首次调用服务之前.
        McpUi.requireAccessibilityService()

        val matches = collectMatches(selector, maxResults, timeoutMs)

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("count", matches.size)
            if (matches.isEmpty()) {
                addProperty(
                    "hint",
                    "No node matched. Call `dump_ui_tree` to inspect the real structure, " +
                            "or retry with a looser filter such as `textContains`.",
                )
            }
            add("results", JsonArray().apply {
                matches.forEach { add(McpNode.summary(it, includeActions)) }
            })
        })
    }

    /**
     * Runs the query, optionally re-running it until the deadline so callers do
     * not have to implement their own polling loop.
     * zh-CN: 执行查询, 必要时在截止时间前重复执行, 省去调用方自行轮询的麻烦.
     *
     * @throws IllegalStateException when every attempt ran while the app could not
     * read a window. An empty collection is only an answer about the screen when
     * the screen was readable while it was produced, so that case is reported
     * rather than handed out as "nothing matched".
     * zh-CN: 当每次尝试都发生在读不到窗口时为抛出 IllegalStateException.
     * 只有在产生结果时屏幕可读, 空集合才算一个关于屏幕的答案,
     * 因此该情形会被上报, 而不是作为"没有匹配"交出去.
     */
    internal fun collectMatches(
        selector: org.autojs.autojs.core.accessibility.UiSelector,
        maxResults: Int,
        timeoutMs: Int,
    ): List<UiObject> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var matches = snapshot(selector, maxResults)
        // An unreadable attempt does not settle the question either, so the
        // polling continues for both kinds of empty result.
        // zh-CN: 读不到的那次尝试同样没有给出答案, 因此两类空结果都会继续轮询.
        while ((matches == null || matches.isEmpty()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            matches = snapshot(selector, maxResults)
        }
        return matches ?: run {
            // Unreadable for the whole budget, so the empty answer was never about
            // the screen. `requireReadableScreen` always throws here; the empty
            // list is only there to give the expression a type.
            // zh-CN: 整个时间预算内都读不到, 因此这个空结果从来不是关于屏幕的答案.
            // `requireReadableScreen` 在此必然抛出; 空列表只是为了让表达式有类型.
            McpUi.requireReadableScreen()
            emptyList()
        }
    }

    /**
     * One query attempt.
     *
     * @return the matches, or null when the app could not read a window at this
     * instant -- a different answer from "nothing matched", and one the caller
     * must not pass on as an empty result.
     * zh-CN: 返回匹配结果; 若此刻读不到窗口则返回 null —— 这与"没有匹配"是不同的答案,
     * 调用方不得把它当作空结果向外传递.
     */
    private fun snapshot(
        selector: org.autojs.autojs.core.accessibility.UiSelector,
        maxResults: Int,
    ): List<UiObject>? {
        val matches = selector.find(maxResults).toList().filterNotNull()
        if (matches.isNotEmpty()) return matches
        // The readability read sits next to the query on purpose. The service can
        // drop between two statements, and every statement in between widens the
        // window in which an empty result gets misread as a fact about the screen.
        // zh-CN: 可读性判断刻意紧贴查询执行. 服务可能在两条语句之间掉线,
        // 中间多一条语句, 就多一分"把空结果误读成屏幕事实"的窗口.
        return if (McpUi.rootOrNull() != null) matches else null
    }

    private const val POLL_INTERVAL_MS = 120L

}
