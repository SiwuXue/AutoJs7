package org.autojs.autojs.mcp.tools

import com.google.gson.JsonArray
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpRecorder
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk

/**
 * Macro recording: watch the user act, then hand back a script that repeats it.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Design
 *  ! `record_start` and `record_stop` carry different risk labels. Starting is
 *  ! what escalates -- it begins observing the user's input, typed text
 *  ! included -- while stopping only reduces exposure. The same principle is
 *  ! why `list_scripts` is readable while `run_script` is not: operations that
 *  ! de-escalate should never require the ability to escalate. The labels are
 *  ! metadata for clients; every registered tool is exposed unconditionally.
 *  ! zh-CN: `record_start` 与 `record_stop` 分属不同风险档.
 *  ! 升级风险的是"开始" —— 它开始观察用户输入, 包括键入的文本;
 *  ! 而"停止"只会降低暴露面. 同理, `list_scripts` 可读而 `run_script` 不可以:
 *  ! 降低风险的操作, 不应以"具备升级风险的能力"为前提.
 *  ! 风险标签仅作为元数据供客户端使用, 所有注册的工具一律无条件暴露.
 */
internal object McpRecordTools {

    val tools: List<McpTool> = listOf(recordStartTool(), recordStopTool())

    /**
     * Mirrors the guard inside AccessibilityActionRecorder, which stops itself
     * after this long. Surfaced to the caller so a long session is not a surprise.
     * zh-CN: 与 AccessibilityActionRecorder 内置的保护一致, 超过该时长会自动停止.
     * 提前告知调用方, 避免长时间会话变成意外.
     */
    private const val MAX_RECORDING_MS = 10 * 60 * 1000

    // --------------------------------------------------------------- start

    private fun recordStartTool(): McpTool = McpTool(
        name = "record_start",
        title = "Start recording a macro",
        description = buildString {
            append("Begins recording the user's interactions so they can be replayed as a script. ")
            append("After calling this, ask the user to perform the actions now, then call `record_stop` to get the script. ")
            append("Captured: clicks, long clicks, scrolls and text changes. ")
            append("Not captured: global navigation such as Home or Back, and raw touch gestures on ")
            append("non-clickable surfaces. ")
            append("Recording stops by itself after ${MAX_RECORDING_MS / 60_000} minutes. ")
            append("Typed text is captured verbatim, so avoid recording while a password field is focused.")
        },
        // Observing the user's input, including what they type, is an escalation.
        // zh-CN: 观察用户输入(含其键入内容)属于风险升级.
        risk = McpToolRisk.DANGEROUS,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "ignoreFirstAction" to McpSchema.boolean(
                    "Skip the very first captured action. Useful because the first tap is often the one " +
                            "used to get back to the app after starting the recording.",
                    true,
                ),
            ),
        ),
    ) { args -> invokeRecordStart(args) }

    private fun invokeRecordStart(args: McpArgs): McpToolResult {
        val ignoreFirstAction = args.optBoolean("ignoreFirstAction", true)

        val outcome = try {
            McpRecorder.start(ignoreFirstAction)
        } catch (e: McpRecorder.RecorderException) {
            return McpToolResult.error(e.message ?: "The recorder could not be started.")
        }

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("ok", true)
            addProperty("state", McpRecorder.stateName())
            addProperty("ignoreFirstAction", ignoreFirstAction)
            addProperty("maxRecordingMs", MAX_RECORDING_MS)
            when (outcome) {
                McpRecorder.StartOutcome.AlreadyRecording -> addProperty(
                    "alreadyRecording", true
                )
                McpRecorder.StartOutcome.Started -> Unit
            }
            addProperty(
                "hint",
                "Ask the user to perform the actions, then call `record_stop`. " +
                        "The recording is discarded if nothing was captured."
            )
        })
    }

    // ---------------------------------------------------------------- stop

    private fun recordStopTool(): McpTool = McpTool(
        name = "record_stop",
        title = "Stop recording and get the script",
        description = buildString {
            append("Ends the current recording and returns the generated JavaScript. ")
            append("The script is returned as text only; persist it with `file_write` if it should be kept. ")
            append("An empty script means nothing captureable happened: the interactions were of an untracked kind, ")
            append("or the recording had already timed out with no captured actions.")
        },
        risk = McpToolRisk.SENSITIVE,
        inputSchema = McpSchema.emptyObject(),
    ) { _ -> invokeRecordStop() }

    private fun invokeRecordStop(): McpToolResult {
        val outcome = McpRecorder.stop()
        val script = outcome.script

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("ok", true)
            // Reported as `stopped` when this call ended the recording: the live
            // state after a stop reads `not_started` (AccessibilityActionRecorder
            // resets to that state), which would tell the caller the recording
            // never happened.
            // zh-CN: 本次调用结束录制时报告为 `stopped`: 停止后的实时状态读作
            // `not_started`(AccessibilityActionRecorder 会重置为该状态),
            // 这会让调用方误以为录制从未发生过.
            addProperty(
                "state",
                if (outcome.stoppedByThisCall) "stopped" else McpRecorder.stateName()
            )
            addProperty("stoppedByThisCall", outcome.stoppedByThisCall)
            addProperty("script", script)
            addProperty("lineCount", script.lines().size)
            if (script.isBlank()) {
                addProperty(
                    "hint",
                    "Nothing was captured. Only clicks, long clicks, scrolls and text changes are recorded, " +
                            "so gestures on non-clickable surfaces and global navigation are missing by design. " +
                            "Consider writing the script directly with `run_script` instead."
                )
            } else {
                add("preview", JsonArray().apply {
                    script.lines().filter { it.isNotBlank() }.take(10).forEach { add(it) }
                })
            }
        })
    }

}
