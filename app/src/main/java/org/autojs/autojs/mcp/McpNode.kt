package org.autojs.autojs.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.autojs.autojs.core.automator.UiObject

/**
 * Serializes accessibility nodes for AI consumption.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Design
 *  ! Token budget is the constraint that matters here. Only truthy booleans are
 *  ! emitted, text is truncated, and the tree dump is hard-capped, because a
 *  ! single unrestricted dump can easily exceed a whole context window and bury
 *  ! the information the model actually needs.
 *  ! zh-CN: 这里真正受限的是 token 预算. 因此只输出为真的布尔值, 文本会被截断,
 *  ! 且树的导出有硬上限 —— 一次不受限的导出很容易超出整个上下文窗口,
 *  ! 反而把模型真正需要的信息淹没.
 */
internal object McpNode {

    const val DEFAULT_TEXT_LIMIT = 120
    const val DEFAULT_MAX_DEPTH = 12
    const val DEFAULT_MAX_NODES = 800
    const val HARD_MAX_NODES = 4000

    /** `[left, top, right, bottom]`. */
    fun bounds(node: UiObject): JsonArray = runCatching {
        val rect = node.bounds()
        JsonArray().apply {
            add(rect.left)
            add(rect.top)
            add(rect.right)
            add(rect.bottom)
        }
    }.getOrElse { JsonArray() }

    /** `[x, y]`, the exact center of the node in screen coordinates. */
    fun center(node: UiObject): JsonArray = runCatching {
        JsonArray().apply {
            add(node.exactCenterX().toInt())
            add(node.exactCenterY().toInt())
        }
    }.getOrElse { JsonArray() }

    /**
     * Compact description of a single node, used by `ui_find` and as the
     * `matched` payload of `ui_action`.
     * zh-CN: 单个节点的精简描述, 供 `ui_find` 使用, 并作为 `ui_action` 的
     * `matched` 返回内容.
     */
    fun summary(
        node: UiObject,
        includeActions: Boolean = false,
        textLimit: Int = DEFAULT_TEXT_LIMIT,
    ): JsonObject = McpJson.obj().apply {
        putIfNotBlank("className", node.className())
        putIfNotBlank("id", node.simpleId())
        putIfNotBlank("idFull", node.fullId())
        putIfNotBlank("text", truncate(node.text(), textLimit))
        putIfNotBlank("desc", truncate(node.desc(), textLimit))
        putIfNotBlank("packageName", node.packageName())
        add("bounds", bounds(node))
        add("center", center(node))
        addProperty("depth", node.depth())
        addProperty("indexInParent", node.indexInParent())
        addProperty("childCount", node.childCount())
        if (node.clickable()) addProperty("clickable", true)
        if (node.longClickable()) addProperty("longClickable", true)
        if (node.scrollable()) addProperty("scrollable", true)
        if (node.editable()) addProperty("editable", true)
        if (node.checked()) addProperty("checked", true)
        if (node.selected()) addProperty("selected", true)
        if (node.focusable()) addProperty("focusable", true)
        if (node.password()) addProperty("password", true)
        // Only the false case is interesting: the default assumption is visible.
        // zh-CN: 只有 false 值得输出, 默认假定节点可见.
        if (!node.visibleToUser()) addProperty("visibleToUser", false)
        if (!node.enabled()) addProperty("enabled", false)
        if (includeActions) {
            add("actions", JsonArray().apply {
                runCatching { node.actionNames() }.getOrNull()?.forEach { add(JsonPrimitive(it)) }
            })
        }
    }

    /**
     * Result of [tree]: the serialized tree plus the counters behind it.
     * zh-CN: [tree] 的结果: 序列化后的树, 以及支撑它的统计计数.
     */
    class TreeResult(
        val json: JsonObject,
        val nodeCount: Int,
        /** Nodes dropped for being invisible to the user. zh-CN: 因对用户不可见而被丢弃的节点数. */
        val prunedInvisible: Int,
        val hitNodeLimit: Boolean,
    )

    /**
     * Recursive tree dump honouring [maxDepth] and [maxNodes].
     *
     * @WhyCounters
     *  ! An all-invisible tree used to be indistinguishable from an empty
     *  ! screen: both came back as `nodeCount: 0` with no error, which is how
     *  ! an app could dump as "nothing on screen" while its UI was plainly
     *  ! there. Reporting the prune count is what tells the two apart.
     *  ! zh-CN: 整棵树都不可见与屏幕真的为空过去无法区分: 两者都返回 `nodeCount: 0`
     *  ! 且不报错 —— 这正是"界面明明在, 却 dump 出一片空白"的由来.
     *  ! 上报剪枝计数正是区分二者的关键.
     */
    fun tree(
        root: UiObject,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        maxNodes: Int = DEFAULT_MAX_NODES,
        packageFilter: String? = null,
        includeInvisible: Boolean = false,
        includeActions: Boolean = false,
        textLimit: Int = DEFAULT_TEXT_LIMIT,
    ): TreeResult {
        val counter = intArrayOf(0)
        var hitNodeLimit = false
        var prunedInvisible = 0

        fun visit(node: UiObject, depth: Int): JsonObject? {
            if (counter[0] >= maxNodes) {
                hitNodeLimit = true
                return null
            }
            counter[0]++

            if (packageFilter != null && !node.packageName().orEmpty().contains(packageFilter)) {
                return null
            }
            if (!includeInvisible && !node.visibleToUser()) {
                prunedInvisible++
                return null
            }

            val json = summary(node, includeActions, textLimit)

            if (depth < maxDepth) {
                val children = JsonArray()
                for (i in 0 until node.childCount()) {
                    val child = node.child(i) ?: continue
                    val childJson = visit(child, depth + 1) ?: continue
                    children.add(childJson)
                }
                if (children.size() > 0) {
                    json.add("children", children)
                }
            } else if (node.childCount() > 0) {
                // Mark the cut so the model knows the tree continues.
                // zh-CN: 标记截断点, 让模型知道树的其余部分被省略了.
                json.addProperty("childrenOmitted", node.childCount())
            }

            return json
        }

        val tree = visit(root, 0) ?: McpJson.obj()
        if (hitNodeLimit) {
            tree.addProperty("truncated", true)
        }
        tree.addProperty("nodeCount", counter[0])
        return TreeResult(tree, counter[0], prunedInvisible, hitNodeLimit)
    }

    fun truncate(value: String?, limit: Int): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (limit <= 0 || text.length <= limit) return text
        return text.substring(0, limit) + "…"
    }

    private fun JsonObject.putIfNotBlank(key: String, value: String?) {
        value?.takeIf { it.isNotBlank() }?.let { addProperty(key, it) }
    }

}
