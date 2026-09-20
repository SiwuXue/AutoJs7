package org.autojs.autojs.mcp.tools

import android.os.Build
import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive
import org.autojs.autojs.core.accessibility.AccessibilityService
import org.autojs.autojs.core.pref.Pref
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpServer
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.mcp.McpUi
import org.autojs.autojs6.BuildConfig

/**
 * Environment introspection tool.
 *
 * @Created by fork author on Sep 16, 2026.
 */
internal object McpSystemTools {

    val tools: List<McpTool> = listOf(deviceInfoTool())

    private fun deviceInfoTool(): McpTool = McpTool(
        name = "get_device_info",
        title = "Get device info",
        description = buildString {
            append("Reports the device model, Android version, screen geometry, the app version, ")
            append("whether the accessibility service is connected, which app is in the foreground, ")
            append("and how this MCP server is currently configured. ")
            append("Call it once at the start of a session to learn the screen size before using coordinates.")
        },
        risk = McpToolRisk.SAFE,
        inputSchema = McpSchema.emptyObject(),
    ) { _ -> McpToolResult.json(buildDeviceInfo()) }

    private fun buildDeviceInfo() = McpJson.obj().apply {
        add("app", McpJson.obj().apply {
            addProperty("packageName", McpUi.context.packageName)
            addProperty("versionName", BuildConfig.VERSION_NAME)
            addProperty("versionCode", BuildConfig.VERSION_CODE)
            addProperty("versionDate", BuildConfig.VERSION_DATE)
            addProperty("flavor", BuildConfig.CHANNEL)
        })

        add("device", McpJson.obj().apply {
            addProperty("manufacturer", Build.MANUFACTURER)
            addProperty("brand", Build.BRAND)
            addProperty("model", Build.MODEL)
            addProperty("device", Build.DEVICE)
            addProperty("product", Build.PRODUCT)
            addProperty("hardware", Build.HARDWARE)
            add("supportedAbis", JsonArray().apply {
                Build.SUPPORTED_ABIS.forEach { add(JsonPrimitive(it)) }
            })
        })

        add("android", McpJson.obj().apply {
            addProperty("release", Build.VERSION.RELEASE)
            addProperty("sdkInt", Build.VERSION.SDK_INT)
            addProperty("codename", Build.VERSION.CODENAME)
        })

        val metrics = McpUi.context.resources.displayMetrics
        val screen = McpUiQueryTools.screenSize()
        add("screen", McpJson.obj().apply {
            addProperty("width", screen.get("width").asInt)
            addProperty("height", screen.get("height").asInt)
            addProperty("rotation", screen.get("rotation").asInt)
            addProperty("densityDpi", metrics.densityDpi)
            addProperty("density", metrics.density.toDouble())
        })

        val service = AccessibilityService.instance
        add("accessibility", McpJson.obj().apply {
            addProperty("connected", service != null)
            addProperty("operational", AccessibilityService.hasOperationalState)
        })

        add("foregroundApp", McpJson.obj().apply {
            val packageName = McpUi.rootOrNull()?.packageName()
            addProperty("packageName", packageName)
            if (packageName == null) {
                addProperty("hint", "No active window root, so the foreground package is unknown.")
            }
        })

        add("mcpServer", McpJson.obj().apply {
            addProperty("enabled", Pref.isMcpServerEnabled)
            addProperty("running", McpServer.isRunning)
            addProperty("endpoint", McpServer.endpointDescription())
            addProperty("lanAccessEnabled", Pref.isMcpServerLanAccessEnabled)
            addProperty("dangerousToolsExposed", Pref.isMcpServerDangerousToolsExposed)
            addProperty("connectedClients", McpServer.connectedClientCount())
        })
    }

}
