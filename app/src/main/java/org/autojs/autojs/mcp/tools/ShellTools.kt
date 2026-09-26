package org.autojs.autojs.mcp.tools

import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.runtime.api.AbstractShell
import org.autojs.autojs.runtime.api.WrappedShizuku
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Shell command execution.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Security
 *  ! `DANGEROUS` by definition: a shell can do anything the app's uid can, and
 *  ! with `withRoot` anything at all. The risk label is metadata for clients;
 *  ! the tool itself is exposed unconditionally like every other tool.
 *  ! zh-CN: 按定义即为 `DANGEROUS`: shell 能做到应用 uid 能做的任何事,
 *  ! 而 `withRoot` 则几乎无所不能. 风险标签仅作为元数据供客户端使用,
 *  ! 工具本身与其他工具一样无条件暴露.
 */
internal object McpShellTools {

    val tools: List<McpTool> = listOf(runShellTool(), shizukuTool())

    private const val DEFAULT_TIMEOUT_MS = 30_000

    private const val MAX_TIMEOUT_MS = 300_000

    private const val DEFAULT_SHIZUKU_TIMEOUT_MS = 15_000

    private const val MAX_SHIZUKU_TIMEOUT_MS = 60_000
    private val shizukuBusy = AtomicBoolean(false)

    private fun runShellTool(): McpTool = McpTool(
        name = "run_shell",
        title = "Run a shell command",
        description = buildString {
            append("Runs a shell command and returns its exit code, standard output and standard error. ")
            append("Set `withRoot` to route the command through `su`, which needs a rooted device. ")
            append("Commands have a `timeoutMs` budget. On expiry the server attempts to terminate the process tree ")
            append("and returns `timedOut: true`; independently detached processes may survive. ")
            append("Output is drained continuously and capped at 1 MiB per stream. ")
            append("Prefer a purpose built tool whenever one exists; reach for the shell only when nothing else fits.")
        },
        risk = McpToolRisk.DANGEROUS,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "command" to McpSchema.string("Shell command line, executed through `sh -c`."),
                "withRoot" to McpSchema.boolean(
                    "Run through `su`. Fails on devices without root.",
                    false,
                ),
                "timeoutMs" to McpSchema.integer(
                    "How long to wait before reporting a timeout.",
                    DEFAULT_TIMEOUT_MS, 100, MAX_TIMEOUT_MS,
                ),
            ),
            required = listOf("command"),
        ),
    ) { args -> invokeRunShell(args) }

    private fun invokeRunShell(args: McpArgs): McpToolResult {
        val command = args.requireString("command")
        val withRoot = args.optBoolean("withRoot", false)
        val timeoutMs = args.optInt("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(100, MAX_TIMEOUT_MS)

        val outcome = try {
            ManagedShellProcess.run(command, withRoot, timeoutMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return McpToolResult.error("Waiting for the command was interrupted.")
        } catch (e: Exception) {
            return McpToolResult.error("Shell command failed to start: ${e.message ?: e::class.java.simpleName}")
        }

        if (outcome.timedOut) {
            return McpToolResult.json(McpJson.obj().apply {
                addProperty("ok", false)
                addProperty("timedOut", true)
                addProperty("timeoutMs", timeoutMs)
                addProperty("command", command)
                addProperty(
                    "hint",
                    "The command exceeded its deadline; its process tree was terminated where possible. " +
                            "Independently detached processes may survive."
                )
            })
        }

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("ok", outcome.code == 0)
            addProperty("code", outcome.code)
            addProperty("command", command)
            addProperty("withRoot", withRoot)
            addProperty("stdout", outcome.stdout)
            addProperty("stderr", outcome.stderr)
            addProperty("outputTruncated", outcome.outputTruncated)
        })
    }

    // ---------------------------------------------------------------- shizuku

    private fun shizukuTool(): McpTool = McpTool(
        name = "shizuku",
        title = "Run a command via Shizuku",
        description = buildString {
            append("Runs one shell command through the Shizuku service, which executes with shell-level ")
            append("privileges (uid 2000) without root and without ADB attached. This is the same channel ")
            append("AutoJs6 scripts reach with the `shizuku()` global. ")
            append("Requires the Shizuku server to be running and AutoJs6 to be authorized in the Shizuku app. ")
            append("Typical uses: `am start` / `am force-stop`, `input tap|swipe|keyevent`, `appops set`, ")
            append("`screencap -p /sdcard/x.png`, `dumpsys ... | grep ...`. ")
            append("Keep large-output commands filtered with `grep` when possible. ")
            append("Commands are given a `timeoutMs` budget: on expiry the call returns with `timedOut: true`; ")
            append("the underlying binder call may still be running; another Shizuku call is rejected until it completes. ")
            append("Avoid known-hanging commands such as ")
            append("unfiltered `dumpsys`. Blocked while Shizuku is not ready -- the error message says which side failed.")
        },
        risk = McpToolRisk.DANGEROUS,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "command" to McpSchema.string(
                    "Command line for the Shizuku shell. A leading `adb shell ` is stripped automatically.",
                ),
                "timeoutMs" to McpSchema.integer(
                    "How long to wait before reporting a timeout. The binder call behind a timed-out command " +
                            "cannot be cancelled, so repeated timeouts on the same command mean it should not be used.",
                    DEFAULT_SHIZUKU_TIMEOUT_MS, 100, MAX_SHIZUKU_TIMEOUT_MS,
                ),
            ),
            required = listOf("command"),
        ),
    ) { args -> invokeShizuku(args) }

    internal fun invokeShizuku(
        args: McpArgs,
        execute: (String) -> AbstractShell.Result = WrappedShizuku::execCommand,
    ): McpToolResult {
        val command = args.requireString("command")
        val timeoutMs = args.optInt("timeoutMs", DEFAULT_SHIZUKU_TIMEOUT_MS)
            .coerceIn(100, MAX_SHIZUKU_TIMEOUT_MS)
        if (!shizukuBusy.compareAndSet(false, true)) {
            return McpToolResult.error("SHIZUKU_BUSY: Another Shizuku command is still running.")
        }

        // WrappedShizuku.execCommand() blocks on the binder and offers no timeout,
        // and a wedged binder channel is a known failure mode (large unfiltered
        // `dumpsys` output). The same daemon-thread pattern as `run_shell` keeps a
        // hanging command from wedging the MCP server thread; the binder call
        // itself cannot be cancelled, which the `timedOut` hint makes explicit.
        // zh-CN: WrappedShizuku.execCommand() 在 binder 上阻塞且无超时, 而 binder
        // 通道挂死是已知故障模式(大输出未过滤的 `dumpsys`). 与 `run_shell` 相同的
        // daemon 线程模式防止挂起命令卡死 MCP 服务线程; binder 调用本身不可取消,
        // `timedOut` 提示对此明确说明.
        val resultRef = java.util.concurrent.atomic.AtomicReference<AbstractShell.Result?>()
        val failureRef = java.util.concurrent.atomic.AtomicReference<Throwable?>()

        val worker = Thread({
            try {
                runCatching { execute(command) }
                    .onSuccess { resultRef.set(it) }
                    .onFailure { failureRef.set(it) }
            } finally {
                shizukuBusy.set(false)
            }
        }, "mcp-shizuku")
        worker.isDaemon = true
        try {
            worker.start()
        } catch (e: Throwable) {
            shizukuBusy.set(false)
            return McpToolResult.error("Could not start Shizuku call: ${e.message ?: e::class.java.simpleName}")
        }

        try {
            worker.join(timeoutMs.toLong())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return McpToolResult.error("Waiting for the Shizuku command was interrupted.")
        }

        failureRef.get()?.let { failure ->
            return McpToolResult.error("Shizuku command failed: ${failure::class.java.simpleName}: ${failure.message ?: "no message"}")
        }
        val result = resultRef.get()
            ?: return McpToolResult.json(McpJson.obj().apply {
                addProperty("ok", false)
                addProperty("timedOut", true)
                addProperty("timeoutMs", timeoutMs)
                addProperty("command", command)
                addProperty(
                    "hint",
                    "The Shizuku command did not finish in time. The binder call cannot be cancelled, so " +
                            "avoid repeating commands known to hang, such as unfiltered `dumpsys`."
                )
            })

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("ok", result.code == 0)
            addProperty("code", result.code)
            addProperty("command", command)
            addProperty("result", result.result)
            addProperty("error", result.error)
        })
    }

}
