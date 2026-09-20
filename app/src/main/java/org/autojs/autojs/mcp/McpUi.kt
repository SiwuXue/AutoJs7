package org.autojs.autojs.mcp

import android.accessibilityservice.AccessibilityService as PlatformAccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.autojs.autojs.AutoJs
import org.autojs.autojs.app.GlobalAppContext
import org.autojs.autojs.core.accessibility.AccessibilityBridge
import org.autojs.autojs.core.accessibility.AccessibilityBridgeImpl
import org.autojs.autojs.core.accessibility.AccessibilityService
import org.autojs.autojs.core.automator.GlobalActionAutomator
import org.autojs.autojs.core.automator.UiObject
import org.autojs.autojs6.R

/**
 * Shared accessibility helpers used by the MCP tools.
 *
 * @Created by fork author on Sep 16, 2026.
 */
internal object McpUi {

    val context: Context get() = GlobalAppContext.get()

    /** Main thread handler, shared by tools that must touch views. zh-CN: 主线程 Handler, 供必须触碰视图的工具共用. */
    internal val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun hasAccessibilityService(): Boolean = AccessibilityService.hasInstance()

    /**
     * @throws IllegalStateException carrying a client-facing message when the
     * accessibility service is not connected, so the AI receives an actionable
     * hint instead of a null pointer crash.
     * zh-CN: 当无障碍服务未连接时抛出携带面向客户端消息的 IllegalStateException,
     * 使 AI 得到可操作的提示而非空指针崩溃.
     *
     * @Contract
     *  ! Call this *after* the arguments have been parsed and validated, and
     *  ! before the first use of the service. Every tool follows that order.
     *  ! It is the one failure here that is not about the caller's own input, so
     *  ! reporting it for what is really a typo or an out-of-range value sends
     *  ! the caller to fix the wrong thing -- and it costs a human, because
     *  ! re-enabling accessibility is a Settings trip the model cannot make. The
     *  ! reverse order also hides real answers: `find_image` could not report a
     *  ! missing template, nor append its storage hint, until the service
     *  ! happened to be connected.
     *  ! zh-CN: 请在参数解析与校验**之后**、首次使用服务**之前**调用它, 所有工具都遵循此顺序.
     *  ! 这是唯一与调用方自身输入无关的失败, 因此若为一个实际上只是拼写错误或越界值的问题
     *  ! 报出它, 会让调用方去修错的地方 —— 而且代价由人承担, 因为重新启用无障碍
     *  ! 是一次模型无法代劳的设置页操作. 反过来还会掩盖真实答案: `find_image`
     *  ! 在服务恰好连着之前, 既报不出模板缺失, 也附不上存储提示.
     */
    fun requireAccessibilityService(): PlatformAccessibilityService =
        AccessibilityService.instance
            ?: throw IllegalStateException(context.getString(R.string.mcp_error_accessibility_unavailable))

    /** Root of the currently active window, or null when unavailable. */
    fun rootOrNull(): UiObject? {
        val service = AccessibilityService.instance ?: return null
        val root = service.rootInActiveWindow ?: service.fastRootInActiveWindow ?: return null
        return UiObject.createRoot(root)
    }

    /**
     * Fails when the screen cannot be read, so a query that came back empty is
     * not mistaken for a screen that has no match.
     *
     * @Design
     *  ! [org.autojs.autojs.core.accessibility.UiSelector] collapses every way
     *  ! accessibility can be unavailable into a single answer: an empty
     *  ! collection. It caches the bridge in its constructor, and `find` returns
     *  ! nothing both when that cached bridge is null and when the bridge itself
     *  ! has no live service -- `Companion.bridge` is only `instance?.bridge`, so
     *  ! a service that has not connected yet is indistinguishable from one that
     *  ! was never published. An AI client cannot tell either case apart from a
     *  ! screen that genuinely lacks the element, so it concludes the element is
     *  ! absent and acts on that conclusion.
     *  ! Calling this only after a query returned empty turns the guess into a
     *  ! stated fact, which is the difference between a client that retries and a
     *  ! client that rewrites its whole approach around a phantom.
     *  ! The test is the read itself rather than the service state, because
     *  ! [rootOrNull] is the same probe `dump_ui_tree` already trusts: a screen
     *  ! that can be dumped is never accused of being unreadable.
     *  ! zh-CN: [org.autojs.autojs.core.accessibility.UiSelector] 把无障碍不可用的每种成因
     *  ! 都塌缩成同一个答案: 空集合. 它在构造时缓存 bridge, 而当这个缓存下来的
     *  ! bridge 为 null 时, 或 bridge 自身没有存活的服务时, `find` 都什么也不返回 ——
     *  ! `Companion.bridge` 只是 `instance?.bridge`, 因此"服务尚未连接"与
     *  ! "服务从未发布过"对调用方毫无区别. AI 客户端同样无从把它与
     *  ! "屏幕上确实不存在该元素"区分开, 于是认定元素不存在并据此行动.
     *  ! 仅在查询返回空之后调用本函数, 可以把猜测变成明说的事实 ——
     *  ! 这正是"会重试的客户端"与"围绕幻影重写整套策略的客户端"之间的差别.
     *  ! 判据用的是读取本身而非服务状态, 因为 [rootOrNull] 与 `dump_ui_tree`
     *  ! 所信任的是同一个探针: 一个能 dump 出来的屏幕绝不会被判定为不可读.
     */
    fun requireReadableScreen() {
        if (rootOrNull() != null) return
        throw IllegalStateException(context.getString(R.string.mcp_error_ui_not_readable))
    }

    /**
     * A global action automator.
     *
     * @Note
     *  ! GlobalActionAutomator accepts a lambda rather than a service reference,
     *  ! so it always resolves the live service instead of a stale one.
     *  ! zh-CN: GlobalActionAutomator 接收 lambda 而非服务引用,
     *  ! 以确保每次取到的都是当前存活的 Service, 而不是已失效的旧引用.
     */
    fun automator(): GlobalActionAutomator =
        GlobalActionAutomator(context, mainHandler) { requireAccessibilityService() }

    /**
     * A bridge for [org.autojs.autojs.core.accessibility.UiSelector] lookups,
     * independent of whether a script engine happens to exist.
     *
     * @Design
     *  ! `UiSelector()` fetches `AccessibilityService.bridge`, but that property
     *  ! is only ever assigned when a script engine builds its
     *  ! [AccessibilityBridgeImpl] -- it stays null for the entire life of the
     *  ! process until then. On a device where no script has ever run, every
     *  ! `ui_find` / `ui_action` selector silently matched zero nodes while
     *  ! `dump_ui_tree` (which reads the root directly) worked fine, and a
     *  ! selector run inside `run_script` worked too -- the engine had just
     *  ! published the bridge. This helper keeps MCP queries on the selector
     *  ! path regardless of engine lifecycle: reuse the published bridge when
     *  ! one exists, otherwise publish our own once. The fallback bridge is
     *  ! stateless for lookups -- `windowRoots()` resolves the live service
     *  ! through `getService()` on every call -- so a stale instance cannot
     *  ! outlive the service it points at.
     *  ! zh-CN: `UiSelector()` 拿的是 `AccessibilityService.bridge`, 但该属性
     *  ! 只有在脚本引擎构造自己的 [AccessibilityBridgeImpl] 时才被赋值 ——
     *  ! 在那之前整个进程生命周期里都是 null. 在从未运行过脚本的设备上,
     *  ! `ui_find` / `ui_action` 的所有选择器都静默返回 0 结果, 而
     *  ! `dump_ui_tree`(直接读 root) 与 `run_script` 里的原生选择器都正常 ——
     *  ! 后者刚把 bridge 发布出来. 本函数让 MCP 的查询不再依赖脚本引擎的
     *  ! 生命周期: 已有 bridge 就复用, 没有就发布自己的. 兜底 bridge 对查询
     *  ! 而言是无状态的 —— `windowRoots()` 每次都经 `getService()` 解析存活
     *  ! 服务 —— 因此不会持有比服务本身更久的失效引用.
     */
    fun selectorBridge(): AccessibilityBridge =
        AccessibilityService.bridge ?: mcpOwnedBridge

    private val mcpOwnedBridge: AccessibilityBridge by lazy {
        AccessibilityBridgeImpl(AutoJs.instance)
    }

}
