package org.autojs.autojs.mcp.tools

import com.google.gson.JsonArray
import org.autojs.autojs.AutoJs
import org.autojs.autojs.execution.ExecutionConfig
import org.autojs.autojs.mcp.McpArgumentException
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpStorage
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.mcp.McpUi
import org.autojs.autojs.model.script.ScriptFile
import org.autojs.autojs.model.script.Scripts
import org.autojs.autojs.script.StringScriptSource
import org.autojs.autojs.util.WorkingDirectoryUtils
import java.io.File

/**
 * Script execution tools.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Security
 *  ! These are `DANGEROUS` by risk label: running arbitrary code is the whole
 *  ! point of the feature, so there is no way to make it "a bit safe". The label
 *  ! is metadata for clients that want to warn their user -- every registered
 *  ! tool is exposed unconditionally -- and they are grouped here so that the
 *  ! single capability surface is easy to audit.
 *  ! zh-CN: 这些工具的风险标签为 `DANGEROUS`: 执行任意代码正是该功能的意义所在,
 *  ! 因此无法把它做成"相对安全". 该标签仅作为元数据供客户端在需要时向用户提示
 *  ! —— 所有注册的工具一律无条件暴露. 集中放在这里, 是为了让这条唯一的能力面易于审计.
 */
internal object McpScriptTools {

    val tools: List<McpTool> = listOf(runScriptTool(), runFileTool(), stopScriptTool(), listScriptsTool())

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

    // ---------------------------------------------------------------- run_file

    private fun runFileTool(): McpTool = McpTool(
        name = "run_file",
        title = "Run a script file",
        description = buildString {
            append("Runs a script file that already exists on the device, given its path relative to the ")
            append("working directory (for example `xainyu/main.js`). ")
            append("This is the way to execute reusable, multi-file projects: the directory of the file becomes ")
            append("the engine working directory, so `require('./modules/...')` resolves normally. ")
            append("Create or update scripts with `file_write` first, then run the entry file here. ")
            append("Read what it printed with `read_log`, check whether it is still alive with `list_scripts`, ")
            append("and stop it with `stop_script`. ")
            append("Console output is prefixed with `${CONSOLE_PREFIX}` to tell it apart from other activity.")
        },
        risk = McpToolRisk.DANGEROUS,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "path" to McpSchema.string(
                    "Script file to execute, relative to the working directory. " +
                            "Point at the entry script (such as `main.js`), not the project directory."
                ),
                "outsideWorkingDirectory" to McpSchema.boolean(
                    "Allow a path outside the working directory.", false,
                ),
            ),
            required = listOf("path"),
        ),
    ) { args -> invokeRunFile(args) }

    private fun invokeRunFile(args: McpArgs): McpToolResult {
        val requested = args.requireString("path")
        val outsideWorkingDirectory = args.optBoolean("outsideWorkingDirectory", false)

        val scriptFile = try {
            resolve(requested, outsideWorkingDirectory)
        } catch (e: McpArgumentException) {
            return McpToolResult.error(e.message ?: "Invalid `path`.")
        }
        if (!scriptFile.exists()) {
            return McpToolResult.error(McpStorage.explain("`${scriptFile.path}` does not exist.", scriptFile))
        }
        if (scriptFile.isDirectory) {
            return McpToolResult.error(
                "`${scriptFile.path}` is a directory. Point at the entry script, such as `main.js`."
            )
        }

        val source = ScriptFile(scriptFile).toSource().apply { prefix = CONSOLE_PREFIX }
        // The parent directory is passed as the engine working directory, which is
        // what makes `require('./modules/...')` resolve relative to the script --
        // the whole reason this tool exists next to `run_script`.
        // zh-CN: 把父目录作为引擎工作目录传入, `require('./modules/...')` 因此能
        // 相对脚本文件解析 —— 这正是本工具与 `run_script` 并存的原因.
        val execution = runCatching {
            AutoJs.instance.scriptEngineService.execute(
                source,
                ExecutionConfig(workingDirectory = scriptFile.parent),
            )
        }.getOrElse {
            return McpToolResult.error(
                "Starting the script failed: ${it::class.java.simpleName}: ${it.message ?: "no message"}"
            )
        } ?: return McpToolResult.error(
            "The script engine refused to start. It may be unavailable while the app is shutting down."
        )

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("ok", true)
            addProperty("id", execution.id)
            addProperty("name", source.name)
            addProperty("path", scriptFile.path)
            addProperty("workingDirectory", scriptFile.parent)
            addProperty(
                "hint",
                "Use `read_log` to see output and `stop_script` with this id to stop it."
            )
        })
    }

    /**
     * @throws McpArgumentException when the path escapes the working directory.
     * zh-CN: 当路径越出工作目录时抛出.
     */
    private fun resolve(requested: String, outsideWorkingDirectory: Boolean): File {
        val root = File(WorkingDirectoryUtils.path).canonicalFile
        val candidate = File(requested)
            .let { if (it.isAbsolute) it else File(root, requested) }
            .canonicalFile

        val insideRoot = candidate == root || candidate.path.startsWith(root.path + File.separator)
        if (!insideRoot && !outsideWorkingDirectory) {
            throw McpArgumentException(
                "`$requested` resolves to `$candidate`, which is outside the AutoJs6 working directory " +
                        "`$root`. Pass `outsideWorkingDirectory: true` if that is really intended."
            )
        }
        return candidate
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
        },
        // Read-only: seeing what is running should never require the ability to
        // start things.
        // zh-CN: 只读: 查看正在运行的内容, 不应以"能启动东西"为前提.
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
