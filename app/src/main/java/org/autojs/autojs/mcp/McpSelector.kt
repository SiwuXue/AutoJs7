package org.autojs.autojs.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.autojs.autojs.core.accessibility.UiSelector

/**
 * Builds a [UiSelector] from the JSON object an AI client supplies.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Design
 *  ! Only a curated subset of the (very large) UiSelector surface is exposed.
 *  ! Each field maps to an explicit applier, so an unsupported field or match
 *  ! mode fails loudly with a useful message instead of being silently ignored
 *  ! and then producing confusing wrong matches.
 *  ! zh-CN: 仅暴露 UiSelector 庞大接口中的精选子集.
 *  ! 每个字段对应一个显式应用函数, 因此不支持的字段或匹配模式会立即报出明确错误,
 *  ! 而不是被静默忽略后产生令人困惑的错误匹配.
 */
internal object McpSelector {

    private const val MODE_EQUALS = "equals"

    private const val MODE_MATCH = "match"

    /**
     * Match modes other than `equals`, in the order they are tested as suffixes.
     * `matches` is deliberately absent: `UiSelector.<field>Matches()` is
     * deprecated in favour of `<field>Match()`.
     * zh-CN: 除 `equals` 之外的匹配模式, 按其后缀被检测的顺序排列.
     * 此处刻意不含 `matches`: `UiSelector.<field>Matches()` 已被
     * `<field>Match()` 取代.
     */
    private val MATCH_MODES = listOf("startsWith", "endsWith", "contains", MODE_MATCH)

    /**
     * Historical spelling still accepted as an alias, so a client that learned
     * `textMatches` does not silently lose its filter.
     * zh-CN: 仍然接受的历史拼写别名, 以免记住了 `textMatches` 的客户端静默丢掉过滤条件.
     */
    private const val MODE_MATCHES_ALIAS = "matches"

    /** Query fields accepting a match-mode suffix. zh-CN: 支持匹配模式后缀的查询字段. */
    private val TEXT_FIELD_APPLIERS: Map<String, Map<String, (UiSelector, String) -> UiSelector>> = mapOf(
        "id" to modes(
            { s, v -> s.id(v) },
            { s, v -> s.idStartsWith(v) },
            { s, v -> s.idEndsWith(v) },
            { s, v -> s.idContains(v) },
            { s, v -> s.idMatch(v) },
        ),
        "text" to modes(
            { s, v -> s.text(v) },
            { s, v -> s.textStartsWith(v) },
            { s, v -> s.textEndsWith(v) },
            { s, v -> s.textContains(v) },
            { s, v -> s.textMatch(v) },
        ),
        "desc" to modes(
            { s, v -> s.desc(v) },
            { s, v -> s.descStartsWith(v) },
            { s, v -> s.descEndsWith(v) },
            { s, v -> s.descContains(v) },
            { s, v -> s.descMatch(v) },
        ),
        "content" to modes(
            { s, v -> s.content(v) },
            { s, v -> s.contentStartsWith(v) },
            { s, v -> s.contentEndsWith(v) },
            { s, v -> s.contentContains(v) },
            { s, v -> s.contentMatch(v) },
        ),
        "className" to modes(
            { s, v -> s.className(v) },
            { s, v -> s.classNameStartsWith(v) },
            { s, v -> s.classNameEndsWith(v) },
            { s, v -> s.classNameContains(v) },
            { s, v -> s.classNameMatch(v) },
        ),
        "packageName" to modes(
            { s, v -> s.packageName(v) },
            { s, v -> s.packageNameStartsWith(v) },
            { s, v -> s.packageNameEndsWith(v) },
            { s, v -> s.packageNameContains(v) },
            { s, v -> s.packageNameMatch(v) },
        ),
    )

    /** Boolean state filters. zh-CN: 布尔状态过滤条件. */
    private val BOOLEAN_FILTERS: Map<String, (UiSelector, Boolean) -> UiSelector> = mapOf(
        "clickable" to { s, v -> s.clickable(v) },
        "longClickable" to { s, v -> s.longClickable(v) },
        "contextClickable" to { s, v -> s.contextClickable(v) },
        "checkable" to { s, v -> s.checkable(v) },
        "checked" to { s, v -> s.checked(v) },
        "enabled" to { s, v -> s.enabled(v) },
        "scrollable" to { s, v -> s.scrollable(v) },
        "editable" to { s, v -> s.editable(v) },
        "selected" to { s, v -> s.selected(v) },
        "focusable" to { s, v -> s.focusable(v) },
        "visibleToUser" to { s, v -> s.visibleToUser(v) },
        "dismissable" to { s, v -> s.dismissable(v) },
        "accessibilityFocused" to { s, v -> s.accessibilityFocused(v) },
    )

    /**
     * Keys owned by the query tools rather than by the selector. A tool with
     * parameters of its own passes its own set instead.
     *
     * @Note
     *  ! The set has to come from the caller. A key that is not listed here is
     *  ! rejected as an unknown selector field, so an incomplete list makes the
     *  ! tool reject its own advertised parameters -- every `ui_action` call used
     *  ! to fail on its own `action` key for exactly this reason, and `ui_find`
     *  ! failed as soon as `includeActions` was passed.
     *  ! The set is also kept to exactly what the tool declares: listing a key the
     *  ! tool does not have would silently swallow it instead of reporting it.
     *  ! zh-CN: 这份集合必须由调用方提供. 未列出的键会被当成未知选择器字段拒绝,
     *  ! 因此集合不全会让工具拒绝自己对外声明的参数 —— `ui_action` 的每次调用
     *  ! 都因自己的 `action` 键而失败, `ui_find` 一传 `includeActions` 就失败,
     *  ! 原因都在这里.
     *  ! 同时这份集合要与工具实际声明的参数严格一致: 多列一个工具并不存在的键,
     *  ! 就会把它静默吞掉而不是报错.
     */
    val QUERY_TOOL_KEYS = setOf("maxResults", "timeoutMs", "includeActions")

    /**
     * Every field the selector understands, for the error message.
     *
     * @Note
     *  ! This list is deliberately filters only. Naming a tool's own parameters
     *  ! here would defeat the purpose of taking `toolKeys` from the caller, and
     *  ! the message says so rather than leaving a caller to guess why a field it
     *  ! sent is absent.
     *  ! zh-CN: 这份列表刻意只含过滤字段. 把工具自己的参数列在这里会架空由调用方
     *  ! 提供 `toolKeys` 的意义; 而文案会把这一点说明白, 而不是让调用方去猜
     *  ! 自己发来的字段为何不在列表里.
     */
    val supportedFields: List<String>
        get() = buildList {
            TEXT_FIELD_APPLIERS.keys.forEach { field ->
                add(field)
                MATCH_MODES.forEach { mode -> add(field + mode.capitalizeAscii()) }
                add(field + MODE_MATCHES_ALIAS.capitalizeAscii())
            }
            addAll(BOOLEAN_FILTERS.keys)
            addAll(listOf("idHex", "boundsInside", "depth", "algorithm"))
        }.sorted()

    /**
     * Keys that configure the search without filtering nodes, so a spec holding
     * only these still counts as empty.
     * zh-CN: 仅配置搜索而不过滤节点的键, 因此只含这些键的 spec 仍算空.
     */
    private val SEARCH_ONLY_KEYS = setOf("algorithm")

    /**
     * True when [spec] carries no node filter at all.
     *
     * @param toolKeys keys that belong to the tool call, not to the selector.
     * zh-CN: [spec] 不携带任何节点过滤条件时返回 true.
     */
    fun isEmpty(spec: JsonObject?, toolKeys: Set<String> = QUERY_TOOL_KEYS): Boolean =
        spec == null || spec.entrySet().none { it.key !in toolKeys && it.key !in SEARCH_ONLY_KEYS }

    /**
     * Translates [spec] into a UiSelector.
     *
     * @param toolKeys keys that belong to the tool call, not to the selector.
     * @throws McpArgumentException when a field or match mode is unknown.
     */
    fun build(spec: JsonObject?, toolKeys: Set<String> = QUERY_TOOL_KEYS): UiSelector {
        // Always pass an explicit bridge: the no-arg UiSelector constructor
        // grabs `AccessibilityService.bridge`, which is null until some script
        // engine happens to publish one, silently yielding zero matches.
        // zh-CN: 必须显式传 bridge: 无参 UiSelector 构造抓取的
        // `AccessibilityService.bridge` 在某个脚本引擎碰巧发布它之前恒为 null,
        // 会让查询静默返回 0 结果.
        val selector = UiSelector(McpUi.selectorBridge())
        if (spec == null) return selector

        for ((key, element) in spec.entrySet()) {
            if (key in toolKeys || element.isJsonNull) continue

            val queryField = resolveQueryField(key)
            if (queryField != null) {
                val (field, mode) = queryField
                requireString(key, element)
                TEXT_FIELD_APPLIERS.getValue(field).getValue(mode)(selector, element.asString)
                continue
            }

            BOOLEAN_FILTERS[key]?.let { applier ->
                requireBoolean(key, element)
                applier(selector, element.asBoolean)
                continue
            }

            applySimple(selector, key, element)
        }

        return selector
    }

    /**
     * Resolves `text`, `textContains`, `textStartsWith`, ... into a field/mode pair.
     * zh-CN: 将 `text`, `textContains`, `textStartsWith` 等解析为字段与模式组合.
     */
    private fun resolveQueryField(key: String): Pair<String, String>? {
        if (key in TEXT_FIELD_APPLIERS) return key to MODE_EQUALS

        for (mode in MATCH_MODES) {
            val suffix = mode.capitalizeAscii()
            if (!key.endsWith(suffix)) continue
            val field = key.removeSuffix(suffix)
            if (field in TEXT_FIELD_APPLIERS) return field to mode
        }

        val aliasSuffix = MODE_MATCHES_ALIAS.capitalizeAscii()
        if (key.endsWith(aliasSuffix)) {
            val field = key.removeSuffix(aliasSuffix)
            if (field in TEXT_FIELD_APPLIERS) return field to MODE_MATCH
        }

        return null
    }

    private fun applySimple(selector: UiSelector, key: String, element: JsonElement) {
        when (key) {
            "algorithm" -> {
                requireString(key, element)
                selector.algorithm(element.asString)
            }

            "idHex" -> {
                requireString(key, element)
                selector.idHex(element.asString)
            }

            "depth" -> {
                requireInteger(key, element)
                selector.depth(element.asInt)
            }

            "boundsInside" -> {
                if (!element.isJsonArray) {
                    throw McpArgumentException("`boundsInside` must be an array of 4 numbers: [left, top, right, bottom]")
                }
                val values = element.asJsonArray
                if (values.size() != 4) {
                    throw McpArgumentException("`boundsInside` must contain exactly 4 numbers: [left, top, right, bottom]")
                }
                selector.boundsInside(
                    values[0].asDouble,
                    values[1].asDouble,
                    values[2].asDouble,
                    values[3].asDouble,
                )
            }

            else -> throw McpArgumentException(
                "Unsupported selector field `$key`. Supported fields: ${supportedFields.joinToString(", ")}. " +
                        "This list covers filters only -- a parameter of the surrounding tool call is passed " +
                        "alongside the selector, not inside it."
            )
        }
    }

    private fun requireString(name: String, element: JsonElement) {
        if (!element.isJsonPrimitive) throw McpArgumentException("`$name` must be a string")
    }

    private fun requireBoolean(name: String, element: JsonElement) {
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
            throw McpArgumentException("`$name` must be a boolean")
        }
    }

    private fun requireInteger(name: String, element: JsonElement) {
        val primitive = element.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        if (primitive == null || !primitive.isNumber) {
            throw McpArgumentException("`$name` must be an integer")
        }
    }

    private fun modes(
        equals: (UiSelector, String) -> UiSelector,
        startsWith: (UiSelector, String) -> UiSelector,
        endsWith: (UiSelector, String) -> UiSelector,
        contains: (UiSelector, String) -> UiSelector,
        match: (UiSelector, String) -> UiSelector,
    ): Map<String, (UiSelector, String) -> UiSelector> = mapOf(
        MODE_EQUALS to equals,
        "startsWith" to startsWith,
        "endsWith" to endsWith,
        "contains" to contains,
        MODE_MATCH to match,
    )

    private fun String.capitalizeAscii(): String =
        if (isEmpty()) this else this[0].uppercaseChar() + substring(1)

}
