package org.autojs.autojs.mcp.tools

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
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

/**
 * Tools that deal with other apps.
 *
 * @Created by fork author on Sep 16, 2026.
 */
internal object McpAppTools {

    val tools: List<McpTool> = listOf(launchAppTool(), listAppsTool())

    // -------------------------------------------------------------- launch_app

    private fun launchAppTool(): McpTool = McpTool(
        name = "launch_app",
        title = "Launch an app",
        description = buildString {
            append("Brings an app to the foreground, given either its package name or a display name. ")
            append("Use `list_apps` first when the package name is unknown. ")
            append("After launching, allow a moment for the app to draw before calling `dump_ui_tree`, ")
            append("otherwise the tree may still describe the previous screen.")
        },
        risk = McpToolRisk.SENSITIVE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "packageName" to McpSchema.string("Exact package name, e.g. `com.tencent.mm`. Preferred."),
                "appName" to McpSchema.string(
                    "Display name to resolve, e.g. `微信`. Slower and may match the wrong app, so prefer `packageName`."
                ),
            ),
        ),
    ) { args -> invokeLaunchApp(args) }

    private fun invokeLaunchApp(args: McpArgs): McpToolResult {
        val requestedPackage = args.optString("packageName")?.takeIf { it.isNotBlank() }
        val requestedName = args.optString("appName")?.takeIf { it.isNotBlank() }

        if (requestedPackage == null && requestedName == null) {
            throw McpArgumentException("Provide either `packageName` or `appName`.")
        }

        val appUtils = AutoJs.instance.appUtils
        val packageName = requestedPackage
            ?: appUtils.getPackageName(requestedName!!)
            ?: return McpToolResult.error(
                "No installed app is named `$requestedName`. Call `list_apps` to see the available names."
            )

        if (!appUtils.isInstalled(packageName)) {
            return McpToolResult.error("`$packageName` is not installed on this device.")
        }

        val launched = runCatching { appUtils.launchPackage(packageName) }.getOrDefault(false)

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("ok", launched)
            addProperty("packageName", packageName)
            addProperty("appName", runCatching { appUtils.getAppName(packageName) }.getOrNull())
            if (!launched) {
                addProperty(
                    "hint",
                    "The launch intent was rejected. The package may have no launchable activity, " +
                            "or the system blocked a background activity start."
                )
            }
        })
    }

    // ---------------------------------------------------------------- list_apps

    private fun listAppsTool(): McpTool = McpTool(
        name = "list_apps",
        title = "List installed apps",
        description = buildString {
            append("Lists apps that have a launcher entry, so the result is the set of things a user can actually open. ")
            append("Filter with `query` to keep the output small: the full list on a real device is long and ")
            append("burns tokens for no benefit. ")
            append("System apps are hidden unless `includeSystem` is set.")
        },
        risk = McpToolRisk.SAFE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "query" to McpSchema.string(
                    "Case insensitive substring matched against both the display name and the package name."
                ),
                "includeSystem" to McpSchema.boolean(
                    "Include apps that ship with the system image.",
                    false,
                ),
                "limit" to McpSchema.integer(
                    "Maximum number of apps to return.",
                    50, 1, 300,
                ),
            ),
        ),
    ) { args -> invokeListApps(args) }

    private fun invokeListApps(args: McpArgs): McpToolResult {
        val query = args.optString("query")?.takeIf { it.isNotBlank() }?.lowercase()
        val includeSystem = args.optBoolean("includeSystem", false)
        val limit = args.optInt("limit", 50).coerceIn(1, 300)

        val packageManager = McpUi.context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    launcherIntent, PackageManager.ResolveInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(launcherIntent, 0)
            }
        }.getOrElse {
            return McpToolResult.error(
                "Querying launcher activities failed: ${it.message ?: it::class.java.simpleName}"
            )
        }

        val apps = resolved
            .asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                val applicationInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
                val isSystem = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                Triple(packageName, info.loadLabel(packageManager).toString(), isSystem)
            }
            // A package may expose several launcher activities; listing it once
            // is what the caller expects.
            // zh-CN: 一个包可能暴露多个启动入口, 但调用方期望每个包只出现一次.
            .distinctBy { it.first }
            .filter { (packageName, label, isSystem) ->
                (includeSystem || !isSystem) &&
                        (query == null ||
                                label.lowercase().contains(query) ||
                                packageName.lowercase().contains(query))
            }
            .sortedBy { it.second.lowercase() }
            .toList()

        val limited = apps.take(limit)

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("count", limited.size)
            addProperty("totalMatched", apps.size)
            if (apps.size > limited.size) {
                addProperty("truncated", true)
            }
            add("apps", JsonArray().apply {
                limited.forEach { (packageName, label, _) ->
                    add(McpJson.obj().apply {
                        addProperty("packageName", packageName)
                        addProperty("label", label)
                    })
                }
            })
        })
    }

}
