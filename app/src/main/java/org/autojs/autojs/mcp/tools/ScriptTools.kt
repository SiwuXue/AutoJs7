package org.autojs.autojs.mcp.tools

import com.google.gson.JsonArray
import org.autojs.autojs.AutoJs
import org.autojs.autojs.mcp.McpArgumentException
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.mcp.McpUi
import org.autojs.autojs.model.script.Scripts
import org.autojs.autojs.script.StringScriptSource

/**
 * Script execution tools.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Security
 *  ! These are `DANGEROUS`: running arbitrary code is the whole point of the
 *  ! feature, so there is no way to make it "a bit safe". They stay hidden from
 *  ! `tools/list` and are refused by `tools/call` until the user turns on the
 *  ! dangerous tools switch, and they are grouped here so that the single
 *  ! boundary is easy to audit.
 *  ! zh-CN: 这些工具属于 `DANGEROUS`: 执行任意代码正是该功能的意义所在,
 *  ! 因此无法把它做成"相对安全". 在用户开启高危工具开关之前, 它们既不会出现在
 *  ! `tools/list` 中, 也会被 `tools/call` 拒绝. 集中放在这里,
 *  ! 是为了让这条唯一的边界易于审计.
 */
internal object McpScriptTools {

    val tools: List<McpTool> = listOf(runScriptTool(), stopScriptTool(), listScriptsTool())

    /**
     * Console prefix for scripts started over MCP, so their output is
     * distinguishable from scripts the user started.
     * zh-CN: 通过 MCP 启动的脚本的控制台前缀, 用于与用户手动启动的脚本区分开.
     */
    private const val CONSOLE_PREFIX = "\$mcp/"

    // --------------------------------------------------------------- run_script

    private fun runScriptTool(): McpTool = McpTool(
        name = "run_script",
        title = "Run a script",
        description = buildString {
            append("Starts a JavaScript snippet in the AutoJs6 engine and returns immediately with an execution id. ")
            append("This is the escape hatch when no structured tool covers what you need. The script runs with full ")
            append("AutoJs6 privileges, so prefer the purpose built tools where they exist. ")
            append("Read what it printed with `read_log`, check whether it is still alive with `list_scripts`, ")
            append("and stop it with `stop_script`. ")
            append("Console output is prefixed with `${CONSOLE_PREFIX}` to tell it apart from other activity.")
        },
        risk = McpToolRisk.DANGEROUS,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "script" to McpSchema.string("JavaScript source to execute."),
                "name" to McpSchema.string(
                    "Label shown in the console and in `list_scripts`. Defaults to a generated name."
                ),
            ),
            required = listOf("script"),
        ),
    ) { args -> invokeRunScript(args) }

    private fun invokeRunScript(args: McpArgs): McpToolResult {
        val script = args.optString("script")
            ?: throw McpArgumentException("`script` is required.")
        if (script.isBlank()) {
            throw McpArgumentException("`script` is empty, so there is nothing to run.")
        }
        val name = args.optString("name")?.takeIf { it.isNotBlank() }
            ?: "mcp-${System.currentTimeMillis()}"

        val source = StringScriptSource(name, script)
        // `prefix` is a Kotlin property on ScriptSource, so it is assigned
        // directly; the Java facing `setPrefix()` is not visible from Kotlin.
        // The prefix is pure ASCII with a leading `$` on purpose: AutoJs6 uses
        // `$` to mark non-user sources in the console.
        // zh-CN: `prefix` 是 ScriptSource 上的 Kotlin 属性, 因此直接赋值;
        // 面向 Java 的 `setPrefix()` 在 Kotlin 侧不可见.
        // 前缀刻意以 `$` 开头且为纯 ASCII: AutoJs6 用 `$` 标识非用户来源.
        source.prefix = CONSOLE_PREFIX

        val execution = runCatching { Scripts.run(McpUi.context, source) }.getOrElse {
            return McpToolResult.error(
                "Starting the script failed: ${it::class.java.simpleName}: ${it.message ?: "no message"}"
            )
        } ?: return McpToolResult.error(
            "The script engine refused to start. It may be unavailable while the app is shutting down."
        )

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("ok", true)
            addProperty("id", execution.id)
            addProperty("name", name)
            addProperty(
                "hint",
                "Use `read_log` to see output and `stop_script` with this id to stop it."
            )
        })
    }

    // -------------------------------------------------------------- stop_script

    private fun stopScriptTool(): McpTool = McpTool(
        name = "stop_script",
        title = "Stop a running script",
        description = buildString {
            append("Force stops one running script by its execution id. ")
            append("Use `list_scripts` to discover ids. Stopping every script at once is deliberately not offered: ")
            append("it would also kill scripts the user started, and an accidental stop-all is not recoverable.")
        },
        risk = McpToolRisk.DANGEROUS,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "id" to McpSchema.integer("Execution id returned by `run_script`, or found via `list_scripts`."),
            ),
            required = listOf("id"),
        ),
    ) { args -> invokeStopScript(args) }

    private fun invokeStopScript(args: McpArgs): McpToolResult {
        val id = args.rawObject()?.get("id")
            ?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asInt }.getOrNull() }
            ?: throw McpArgumentException("`id` is required. Use `list_scripts` to find it.")

        val execution = AutoJs.instance.scriptEngineService.getScriptExecution(id)
            ?: return McpToolResult.error(
                "No running script has id $id. It may have already finished; call `list_scripts` to check."
            )

        val name = runCatching { execution.source.name }.getOrNull()
        val stopped = runCatching { execution.engine.forceStop() }.isSuccess

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("ok", stopped)
            addProperty("id", id)
            addProperty("name", name)
        })
    }

    // ------------------------------------------------------------- list_scripts

    private fun listScriptsTool(): McpTool = McpTool(
        name = "list_scripts",
        title = "List running scripts",
        description = buildString {
            append("Lists scripts whose engine is still alive, with the id needed by `stop_script`. ")
            append("Finished scripts disappear from this list, so an empty result means nothing is running. ")
            append("This is readable even when the dangerous tools are disabled, so a client can always see ")
            append("what this device is busy with.")
        },
        // Read-only, so it stays available even with the dangerous switch off:
        // seeing what is running should never require the ability to start things.
        // zh-CN: 只读, 因此在关闭高危开关时依然可用:
        // 查看正在运行的内容, 不应以"能启动东西"为前提.
        risk = McpToolRisk.SAFE,
        inputSchema = McpSchema.emptyObject(),
    ) { _ -> invokeListScripts() }

    private fun invokeListScripts(): McpToolResult {
        val executions = runCatching {
            AutoJs.instance.scriptEngineService.scriptExecutions.toList()
        }.getOrElse {
            return McpToolResult.error(
                "Reading the script engine state failed: ${it.message ?: it::class.java.simpleName}"
            )
        }

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("count", executions.size)
            add("scripts", JsonArray().apply {
                executions.forEach { execution ->
                    add(McpJson.obj().apply {
                        addProperty("id", execution.id)
                        addProperty("name", runCatching { execution.source.name }.getOrNull())
                    })
                }
            })
        })
    }

}
