package org.autojs.autojs.mcp

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
 *  ! Every registered tool is exposed unconditionally. The risk label carried by
 *  ! each tool remains as metadata for clients that want to warn their user, but
 *  ! it no longer gates visibility or invocation.
 *  ! zh-CN: 所有注册的工具一律无条件暴露. 每个工具携带的风险标签仅作为元数据保留,
 *  ! 供客户端在需要时向用户提示, 不再参与可见性与调用拦截.
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
                        // Grouped together at the end so the powerful surface is
                        // easy to eyeball in one place.
                        // zh-CN: 集中在末尾, 便于一眼看清高能力面.
                        McpScriptTools.tools +
                        McpShellTools.tools +
                        McpFileTools.tools +
                        McpDatabaseTools.tools +
                        McpInstallTools.tools
                ).forEach { put(it.name, it) }
    }

    fun all(): List<McpTool> = tools.values.toList()

    /** Tools the client is allowed to see. zh-CN: 允许客户端看到的工具. */
    fun exposed(): List<McpTool> = all()

    fun find(name: String): McpTool? = tools[name]

}
