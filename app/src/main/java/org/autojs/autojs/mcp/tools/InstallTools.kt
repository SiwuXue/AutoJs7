package org.autojs.autojs.mcp.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.google.gson.JsonObject
import org.autojs.autojs.mcp.McpArgumentException
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpStorage
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.mcp.McpUi
import org.autojs.autojs.util.IntentUtils
import org.autojs.autojs.util.WorkingDirectoryUtils
import java.io.File

/**
 * Installing a locally stored APK.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Why
 *  ! The interesting part of this tool is not the launch, it is the inspection
 *  ! that happens first. A caller that has just downloaded an APK wants to know
 *  ! what it contains and whether it will install, and both answers are available
 *  ! before anything is started -- so the tool reports the package name, the
 *  ! version and the comparison against what is already installed, and only then
 *  ! hands off to the system installer.
 *  ! zh-CN: 本工具真正有价值的部分不是拉起安装器, 而是之前的检查. 刚下载完 APK 的
 *  ! 调用方想知道里面是什么, 以及能否装上, 而这两个答案在启动任何东西之前就能得到 ——
 *  ! 因此本工具先报告包名, 版本号以及与已安装版本的对比, 然后才交给系统安装器.
 *
 * @Limitation
 *  ! The install always ends at a system dialog that only a human can confirm.
 *  ! This tool opens that dialog and stops; completing the flow means driving the
 *  ! dialog with `dump_ui_tree` and `ui_action`, or asking the user. That is a
 *  ! platform rule, not something another implementation could avoid.
 *  ! zh-CN: 安装最终必然会停在一个只有真人能确认的系统对话框上. 本工具打开该对话框后
 *  ! 即停止; 要走完整个流程, 需要用 `dump_ui_tree` 与 `ui_action` 驱动它, 或者请用户确认.
 *  ! 这是平台规则, 换一种实现方式也无法绕开.
 */
internal object McpInstallTools {

    val tools: List<McpTool> = listOf(installAppTool())

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    private fun installAppTool(): McpTool = McpTool(
        name = "install_app",
        title = "Install an APK",
        description = buildString {
            append("Inspects an APK stored on the device and opens the system installer for it. ")
            append("The result always carries what the file contains -- package name, version, label -- and how that ")
            append("compares with the copy already installed, because Android refuses a downgrade and it is better ")
            append("to learn that here than from a failed install. ")
            append("Pass `launchInstaller: false` to get the analysis on its own, which is the safe way to inspect ")
            append("a file without touching the device. ")
            append("The installer dialog still needs a human to confirm it: finish with `dump_ui_tree` and ")
            append("`ui_action`, or ask the user. ")
            append("The APK path is confined to the working directory; pair this with `file_write` after fetching ")
            append("the file with `http_request`.")
        },
        risk = McpToolRisk.DANGEROUS,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "path" to McpSchema.string("APK file, relative to the working directory."),
                "launchInstaller" to McpSchema.boolean(
                    "Open the system installer. Set to false to only inspect the file.", true,
                ),
                "outsideWorkingDirectory" to McpSchema.boolean(
                    "Allow an APK path outside the working directory.", false,
                ),
            ),
            required = listOf("path"),
        ),
    ) { args -> invokeInstallApp(args) }

    private fun invokeInstallApp(args: McpArgs): McpToolResult {
        val apkFile = resolve(
            args.requireString("path"),
            args.optBoolean("outsideWorkingDirectory", false),
        )
        val launchInstaller = args.optBoolean("launchInstaller", true)

        if (!apkFile.exists()) {
            return McpToolResult.error(
                McpStorage.explain("`${apkFile.path}` does not exist.", apkFile)
            )
        }
        if (apkFile.isDirectory) {
            return McpToolResult.error("`${apkFile.path}` is a directory, not an APK.")
        }
        if (!apkFile.name.lowercase().endsWith(".apk")) {
            return McpToolResult.error(
                "`${apkFile.path}` does not look like an APK. The installer only accepts files ending in `.apk`."
            )
        }
        if (!apkFile.canRead()) {
            // An APK downloaded into Downloads is the single most likely file to
            // hit shared storage filtering, and `canRead()` is where it shows up.
            // zh-CN: 下载到 Download 目录的 APK 是最容易撞上共享存储过滤的文件,
            // 而 `canRead()` 正是它显露出来的地方.
            return McpToolResult.error(
                McpStorage.explain("`${apkFile.path}` is not readable.", apkFile)
            )
        }

        val context = McpUi.context
        val packageManager = context.packageManager

        val archived = archiveInfo(packageManager, apkFile)
            ?: return McpToolResult.error(
                "`${apkFile.path}` could not be parsed as an APK. It may be truncated, or not an APK at all."
            )

        val installed = archived.packageName?.let { installedInfo(packageManager, it) }
        val verdict = compare(archived.versionCode, installed)

        if (!launchInstaller) {
            return McpToolResult.json(McpJson.obj().apply {
                addProperty("launched", false)
                addProperty("path", apkFile.path)
                add("apk", apkObject(archived, apkFile))
                add("installed", installedObject(installed, verdict))
                addProperty("hint", "Analysis only. Pass `launchInstaller: true` to open the system installer.")
                addVerdictWarning(this, verdict)
            })
        }

        // Android 8 and up gate sideloading behind a per-app user grant that no
        // permission declaration can satisfy. Checking it first turns an opaque
        // failure into the exact setting the user has to change.
        // zh-CN: Android 8 起, 侧载被置于一项按应用授予的用户许可之后, 任何权限声明都无法满足它.
        // 先行检查可以把一次难以理解的失败变成用户确切需要改动的那个设置项.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            return McpToolResult.error(
                "AutoJs6 is not allowed to install unknown apps here, so the installer would be refused. " +
                        "Ask the user to enable Settings > Apps > AutoJs6 > Install unknown apps, then retry. " +
                        "This cannot be changed from the MCP side."
            )
        }

        val launched = launch(packageManager, context, apkFile)

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("launched", launched)
            addProperty("path", apkFile.path)
            add("apk", apkObject(archived, apkFile))
            add("installed", installedObject(installed, verdict))
            addVerdictWarning(this, verdict)
            if (launched) {
                addProperty(
                    "nextStep",
                    "The system installer is on screen and is waiting for a human. Use `dump_ui_tree` then " +
                            "`ui_action` to confirm it, or ask the user to.",
                )
            } else {
                addProperty(
                    "hint",
                    "No activity accepted the install intent. The device may have no package installer available.",
                )
            }
        })
    }

    /**
     * @return true when an activity accepted the intent.
     *
     * @Note
     *  ! The existing `IntentUtils.installApk` helper is not reused because it
     *  ! routes through `startSafely`, which reports a failure as a toast. A
     *  ! toast reaches the user but not the caller, so the AI would be told
     *  ! nothing and would have to guess whether the dialog had appeared.
     *  ! zh-CN: 这里没有复用已有的 `IntentUtils.installApk`, 因为它经由 `startSafely`,
     *  ! 把失败表现为一条 toast. toast 只到达用户而不到达调用方, 于是 AI 什么也得不到,
     *  ! 只能去猜对话框到底有没有出现.
     */
    private fun launch(packageManager: PackageManager, context: Context, apkFile: File): Boolean {
        // The authority is derived from the package name rather than taken from
        // AppFileProvider.AUTHORITY: the manifest declares it through an
        // `${authorities}` placeholder that follows the applicationId, so
        // deriving it keeps the two in step even if the applicationId is renamed.
        // zh-CN: authority 由包名推导而非取自 AppFileProvider.AUTHORITY:
        // 清单通过 `${authorities}` 占位符声明它, 而该占位符跟随 applicationId,
        // 因此推导可以保证两者始终一致, 即使 applicationId 被改名.
        val uri = runCatching {
            IntentUtils.getUriOfFile(context, apkFile.path, "${context.packageName}.fileprovider")
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // Resolving before starting separates "nothing here can install an APK"
        // from "the installer crashed", which are very different problems.
        // zh-CN: 先解析再启动, 可以把"这台设备上没有任何东西能安装 APK"与"安装器崩溃了"
        // 区分开, 这是两个完全不同的问题.
        val resolvable: List<ResolveInfo> = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
            }
        }.getOrDefault(emptyList())

        if (resolvable.isEmpty()) return false

        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** What the APK file itself declares. */
    private class ArchivedInfo(
        val packageName: String?,
        val versionName: String?,
        val versionCode: Long,
        val label: String?,
    )

    /** What the device currently has installed, or null when the package is absent. */
    private class InstalledInfo(
        val versionName: String?,
        val versionCode: Long,
    )

    private fun archiveInfo(packageManager: PackageManager, apkFile: File): ArchivedInfo? {
        val info: PackageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageArchiveInfo(apkFile.path, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(apkFile.path, 0)
            }
        }.getOrNull() ?: return null

        // An archived package is not installed, so PackageManager has no path to
        // resolve its resources from. Pointing sourceDir at the file is the
        // documented way to make loadLabel() work on an APK on disk.
        // zh-CN: 归档中的包并未安装, 因此 PackageManager 没有路径去解析它的资源.
        // 把 sourceDir 指向该文件, 是让 loadLabel() 能作用于磁盘上 APK 的官方做法.
        val applicationInfo = info.applicationInfo
        val label = applicationInfo?.let {
            it.sourceDir = apkFile.path
            it.publicSourceDir = apkFile.path
            runCatching { it.loadLabel(packageManager).toString() }.getOrNull()
        }

        return ArchivedInfo(
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = versionCodeOf(info),
            label = label,
        )
    }

    private fun installedInfo(packageManager: PackageManager, packageName: String): InstalledInfo? {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        }.getOrNull() ?: return null

        return InstalledInfo(info.versionName, versionCodeOf(info))
    }

    /**
     * @Note
     *  ! `longVersionCode` exists from Android 9, whereas `versionCode` is capped
     *  ! at an `Int` and goes negative for any app that genuinely uses the high
     *  ! bits. Reading the wide field where it exists avoids reporting a nonsense
     *  ! negative version for a legitimate app.
     *  ! zh-CN: `longVersionCode` 自 Android 9 起存在, 而 `versionCode` 受 `Int` 上限约束,
     *  ! 对真正使用高位版本号的应用会变成负数. 在可用时读取宽字段, 可以避免为合法应用
     *  ! 报出毫无意义的负数版本号.
     */
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun compare(apkVersionCode: Long, installed: InstalledInfo?): String = when {
        installed == null -> "notInstalled"
        apkVersionCode > installed.versionCode -> "upgrade"
        apkVersionCode == installed.versionCode -> "reinstall"
        else -> "downgrade"
    }

    private fun apkObject(archived: ArchivedInfo, apkFile: File): JsonObject = McpJson.obj().apply {
        addProperty("packageName", archived.packageName)
        addProperty("versionName", archived.versionName)
        addProperty("versionCode", archived.versionCode)
        addProperty("label", archived.label)
        addProperty("sizeBytes", apkFile.length())
    }

    private fun installedObject(installed: InstalledInfo?, verdict: String): JsonObject = McpJson.obj().apply {
        addProperty("present", installed != null)
        installed?.let {
            addProperty("versionName", it.versionName)
            addProperty("versionCode", it.versionCode)
        }
        addProperty("verdict", verdict)
    }

    /**
     * Appends the downgrade warning when it applies.
     *
     * @Note
     *  ! Written as a plain function taking the target rather than as an
     *  ! extension on `JsonObject`. An extension would need both its dispatch and
     *  ! extension receivers to be in scope at the call site, which inside a
     *  ! nested `apply` is exactly the kind of resolution that silently picks the
     *  ! wrong receiver.
     *  ! zh-CN: 这里写成接收目标的普通函数, 而不是 `JsonObject` 的扩展函数.
     *  ! 扩展函数需要分发接收者与扩展接收者同时在调用处可见, 而在嵌套的 `apply` 里,
     *  ! 这正是那种会静默选中错误接收者的解析场景.
     */
    private fun addVerdictWarning(target: JsonObject, verdict: String) {
        if (verdict != "downgrade") return
        target.addProperty(
            "warning",
            "The APK is older than the installed version. Android refuses a downgrade, so the installed copy " +
                    "has to be removed first -- which also erases its data.",
        )
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

}
