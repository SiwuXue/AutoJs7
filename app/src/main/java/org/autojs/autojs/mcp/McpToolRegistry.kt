package org.autojs.autojs.mcp

import org.autojs.autojs.core.pref.Pref
import org.autojs.autojs.mcp.tools.McpAppTools
import org.autojs.autojs.mcp.tools.McpColorTools
import org.autojs.autojs.mcp.tools.McpDatabaseTools
import org.autojs.autojs.mcp.tools.McpDeviceTools
import org.autojs.autojs.mcp.tools.McpFileTools
import org.autojs.autojs.mcp.tools.McpFindImageTools
import org.autojs.autojs.mcp.tools.McpInputTools
import org.autojs.autojs.mcp.tools.McpInstallTools
import org.autojs.autojs.mcp.tools.McpLogTools
import org.autojs.autojs.mcp.tools.McpNetworkTools
import org.autojs.autojs.mcp.tools.McpNotificationTools
import org.autojs.autojs.mcp.tools.McpOcrTools
import org.autojs.autojs.mcp.tools.McpRecordTools
import org.autojs.autojs.mcp.tools.McpScreenTools
import org.autojs.autojs.mcp.tools.McpScriptTools
import org.autojs.autojs.mcp.tools.McpShellTools
import org.autojs.autojs.mcp.tools.McpSystemTools
import org.autojs.autojs.mcp.tools.McpUiActionTools
import org.autojs.autojs.mcp.tools.McpUiQueryTools
import org.autojs.autojs.mcp.tools.McpUserDialogTools
import org.autojs.autojs.mcp.tools.McpWaitTools

/**
 * The set of tools this server exposes.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Design
 *  ! Risk level, not the registry, decides visibility. Adding a tool is a single
 *  ! registration line and inherits the correct gating automatically.
 *  ! zh-CN: 可见性由风险等级决定, 而不是由注册表决定.
 *  ! 新增工具只需一行注册, 并自动继承正确的分级拦截逻辑.
 */
object McpToolRegistry {

    private val tools: Map<String, McpTool> = buildMap {
        (
                McpUiQueryTools.tools +
                        McpUiActionTools.tools +
                        McpWaitTools.tools +
                        McpInputTools.tools +
                        McpAppTools.tools +
                        McpOcrTools.tools +
                        McpFindImageTools.tools +
                        McpColorTools.tools +
                        McpNotificationTools.tools +
                        McpLogTools.tools +
                        McpSystemTools.tools +
                        McpDeviceTools.tools +
                        McpScreenTools.tools +
                        McpRecordTools.tools +
                        McpNetworkTools.tools +
                        McpUserDialogTools.tools +
                        // Grouped together at the end so the dangerous surface is
                        // easy to eyeball in one place.
                        // zh-CN: 集中在末尾, 便于一眼看清高危能力面.
                        McpScriptTools.tools +
                        McpShellTools.tools +
                        McpFileTools.tools +
                        McpDatabaseTools.tools +
                        McpInstallTools.tools
                ).forEach { put(it.name, it) }
    }

    fun all(): List<McpTool> = tools.values.toList()

    /** Tools the client is allowed to see right now. zh-CN: 当前允许客户端看到的工具. */
    fun exposed(): List<McpTool> = all().filter { isExposed(it) }

    fun find(name: String): McpTool? = tools[name]

    /**
     * @Security
     *  ! Dangerous tools stay hidden until the user opts in, and the same check
     *  ! guards `tools/call` so a remembered name cannot bypass the setting.
     *  ! zh-CN: 高危工具在用户显式开启前保持隐藏; 同一判断也用于拦截 `tools/call`,
     *  ! 以防客户端用记住的工具名绕过该设置.
     */
    fun isExposed(tool: McpTool): Boolean = when (tool.risk) {
        McpToolRisk.DANGEROUS -> Pref.isMcpServerDangerousToolsExposed
        McpToolRisk.SAFE, McpToolRisk.SENSITIVE -> true
    }

}
