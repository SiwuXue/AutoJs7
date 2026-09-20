package org.autojs.autojs.mcp.tools

import org.autojs.autojs.mcp.McpArgumentException
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.runtime.api.AbstractShell
import org.autojs.autojs.runtime.api.ProcessShell
import java.util.concurrent.atomic.AtomicReference

/**
 * Shell command execution.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Security
 *  ! `DANGEROUS` by definition: a shell can do anything the app's uid can, and
 *  ! with `withRoot` anything at all. Hidden until the user opts in.
 *  ! zh-CN: 按定义即为 `DANGEROUS`: shell 能做到应用 uid 能做的任何事,
 *  ! 而 `withRoot` 则几乎无所不能. 在用户显式开启前保持隐藏.
 */
internal object McpShellTools {

    val tools: List<McpTool> = listOf(runShellTool())

    private const val DEFAULT_TIMEOUT_MS = 30_000

    private const val MAX_TIMEOUT_MS = 300_000

    private fun runShellTool(): McpTool = McpTool(
        name = "run_shell",
        title = "Run a shell command",
        description = buildString {
            append("Runs a shell command and returns its exit code, standard output and standard error. ")
            append("Set `withRoot` to route the command through `su`, which needs a rooted device. ")
            append("Commands are given a `timeoutMs` budget: on expiry the call returns with `timedOut: true` while ")
            append("the process keeps running in the background, so a long or interactive command cannot wedge the server. ")
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

        // ProcessShell.exec() blocks until the process exits and offers no
        // timeout, so it runs on a throwaway thread that the call site can stop
        // waiting on. The thread is a daemon so an abandoned command can never
        // hold the process open.
        // zh-CN: ProcessShell.exec() 会阻塞到进程退出且不提供超时, 因此放在一个
        // 可被调用方放弃等待的一次性线程上执行. 该线程为 daemon,
        // 避免被遗弃的命令阻止进程退出.
        val resultRef = AtomicReference<AbstractShell.Result?>()
        val failureRef = AtomicReference<Throwable?>()

        val worker = Thread({
            runCatching { ProcessShell.exec(command, withRoot) }
                .onSuccess { resultRef.set(it) }
                .onFailure { failureRef.set(it) }
        }, "mcp-shell")
        worker.isDaemon = true
        worker.start()

        try {
            worker.join(timeoutMs.toLong())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return McpToolResult.error("Waiting for the command was interrupted.")
        }

        val result = resultRef.get()
            ?: return McpToolResult.json(McpJson.obj().apply {
                addProperty("ok", false)
                addProperty("timedOut", true)
                addProperty("timeoutMs", timeoutMs)
                addProperty("command", command)
                failureRef.get()?.let { failure ->
                    addProperty("error", "${failure::class.java.simpleName}: ${failure.message ?: "no message"}")
                }
                addProperty(
                    "hint",
                    "The command did not finish in time and is still running in the background. " +
                            "Its output will not be captured. Avoid interactive commands and long sleeps."
                )
            })

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("ok", result.code == 0)
            addProperty("code", result.code)
            addProperty("command", command)
            addProperty("withRoot", withRoot)
            addProperty("stdout", result.result)
            addProperty("stderr", result.error)
        })
    }

}
