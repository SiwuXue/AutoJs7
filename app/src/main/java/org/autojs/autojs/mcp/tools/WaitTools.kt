package org.autojs.autojs.mcp.tools

import org.autojs.autojs.core.automator.UiObject
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.mcp.McpUi

/**
 * Tools that wait for the screen to settle.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Why
 *  ! The most common way an automation script goes wrong is acting while a
 *  ! transition is still running: the tap lands on the outgoing screen and
 *  ! silently does nothing. Waiting for a *specific* node is already covered by
 *  ! `ui_find` with a `timeoutMs`, so this tool instead covers the case where the
 *  ! caller knows a transition started but not what will appear at the end of it.
 *  ! zh-CN: 自动化脚本出错最常见的方式, 就是在过渡动画尚未结束时就开始操作:
 *  ! 点击落在正在退出的界面上, 然后静默失效. "等待某个具体节点出现"已由带 `timeoutMs`
 *  ! 的 `ui_find` 覆盖, 因此本工具处理的是另一种情形: 调用方知道过渡已经开始,
 *  ! 但不知道最终会出现什么.
 */
internal object McpWaitTools {

    val tools: List<McpTool> = listOf(waitForIdleTool())

    /** Nodes hashed per sample. Enough to notice change, cheap enough to repeat. */
    private const val SIGNATURE_NODE_LIMIT = 600

    private const val MAX_SIGNATURE_DEPTH = 25

    private const val POLL_INTERVAL_MS = 120L

    private fun waitForIdleTool(): McpTool = McpTool(
        name = "wait_for_idle",
        title = "Wait for the screen to settle",
        description = buildString {
            append("Blocks until the view hierarchy stops changing, then returns. ")
            append("Call it right after an action that triggers a transition -- launching an app, submitting a form, ")
            append("opening a menu -- so the next step does not race the animation. ")
            append("If you know what should appear, `ui_find` with `timeoutMs` is more precise and cheaper. ")
            append("Reports `idle: false` when the budget runs out, which means the screen never settled; ")
            append("that is normal for anything continuously animating, such as a video or a live progress spinner.")
        },
        risk = McpToolRisk.SAFE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "timeoutMs" to McpSchema.integer(
                    "Give up after this long and report `idle: false`.",
                    5_000, 100, 30_000,
                ),
                "stableMs" to McpSchema.integer(
                    "How long the hierarchy must stay unchanged before it counts as settled. " +
                            "Raise it for slow networks, lower it for snappy local screens.",
                    500, 100, 5_000,
                ),
            ),
        ),
    ) { args -> invokeWaitForIdle(args) }

    private fun invokeWaitForIdle(args: McpArgs): McpToolResult {
        val timeoutMs = args.optInt("timeoutMs", 5_000).coerceIn(100, 30_000)
        val stableMs = args.optInt("stableMs", 500).coerceIn(100, 5_000)

        // Guard after the request checks, before the first service call.
        // zh-CN: 守卫放在请求检查之后, 首次调用服务之前.
        McpUi.requireAccessibilityService()

        val deadline = System.currentTimeMillis() + timeoutMs
        val startedAt = System.currentTimeMillis()

        var previousSignature: Long? = null
        var unchangedSince = System.currentTimeMillis()
        var samples = 0

        while (true) {
            samples++
            val signature = treeSignature()
            val now = System.currentTimeMillis()

            if (signature == previousSignature) {
                if (now - unchangedSince >= stableMs) {
                    return report(
                        idle = true, waitedMs = now - startedAt, samples = samples,
                        stableMs = stableMs, timeoutMs = timeoutMs,
                    )
                }
            } else {
                previousSignature = signature
                unchangedSince = now
            }

            if (now >= deadline) {
                return report(
                    idle = false, waitedMs = now - startedAt, samples = samples,
                    stableMs = stableMs, timeoutMs = timeoutMs,
                )
            }

            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun report(
        idle: Boolean,
        waitedMs: Long,
        samples: Int,
        stableMs: Int,
        timeoutMs: Int,
    ): McpToolResult = McpToolResult.json(McpJson.obj().apply {
        addProperty("idle", idle)
        addProperty("waitedMs", waitedMs)
        addProperty("samples", samples)
        addProperty("stableMs", stableMs)
        addProperty("timeoutMs", timeoutMs)
        if (!idle) {
            addProperty(
                "hint",
                "The hierarchy never stopped changing. Something on screen is continuously animating. " +
                        "Prefer `ui_find` with a `timeoutMs` and an explicit target instead."
            )
        }
    })

    /**
     * Hashes class name, text and bounds of the first
     * [SIGNATURE_NODE_LIMIT] nodes. Deliberately ignores every other attribute:
     * a changing scroll offset or a ticking clock would otherwise keep the
     * signature unstable forever.
     * zh-CN: 对前 [SIGNATURE_NODE_LIMIT] 个节点的类名, 文本与边界求哈希.
     * 刻意忽略其他属性: 否则滚动偏移或走动的时钟会让签名永远不稳定.
     */
    private fun treeSignature(): Long {
        val root = McpUi.rootOrNull() ?: return 0L

        var hash = 17L
        var visited = 0

        fun walk(node: UiObject, depth: Int) {
            if (visited >= SIGNATURE_NODE_LIMIT) return
            visited++
            hash = hash * 31 + node.className().hashCode()
            hash = hash * 31 + node.text().hashCode()
            hash = hash * 31 + node.bounds().hashCode()
            if (depth >= MAX_SIGNATURE_DEPTH) return
            for (index in 0 until node.childCount()) {
                val child = node.child(index) ?: continue
                walk(child, depth + 1)
            }
        }

        walk(root, 0)
        return hash * 31 + visited
    }

}
