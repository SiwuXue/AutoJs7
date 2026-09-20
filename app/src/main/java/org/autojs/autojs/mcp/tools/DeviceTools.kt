package org.autojs.autojs.mcp.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.app.ActivityManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.google.gson.JsonObject
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
 * Device level navigation, state and settings.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Why
 *  ! `launch_app` answers "open the app", but a lot of what an AI needs is "open
 *  ! the Wi-Fi settings page" or "open this deep link", which is an Intent, not a
 *  ! package. And knowing which exact activity the user is on turns "open settings"
 *  ! from a guess into a check. Both are thin wrappers over platform APIs.
 *  ! zh-CN: `launch_app` 回答的是"打开这个应用", 但 AI 需要的往往是
 *  ! "打开 Wi-Fi 设置页"或"打开这个深链" —— 那是 Intent, 不是包名.
 *  ! 而知道用户当前处于哪个具体 Activity, 能让"打开设置"从猜测变成核对.
 *  ! 两者都是平台 API 的薄封装.
 *
 * @ImplementationNote
 *  ! The `Device` script API is used only for reading. Its setters are avoided on
 *  ! purpose: `Device.setBrightness` and friends call `manageWriteSettings`,
 *  ! which silently *navigates the device to the settings page* as a side effect
 *  ! of the permission check. A tool call that yanks the user out of whatever
 *  ! they were doing, without the AI knowing why, is the wrong shape -- so the
 *  ! setters here check the grant themselves and report back instead of jumping.
 *  ! zh-CN: `Device` 脚本 API 仅用于读取. 刻意不用它的 setter:
 *  ! `Device.setBrightness` 等方法在权限检查里会调用 `manageWriteSettings`,
 *  ! 也就是**悄悄把设备导航到设置页**. 一次会把用户从手头事情里拽走、
 *  ! 而 AI 却毫不知情的工具调用, 形状上是错的 —— 因此这里的 setter 自行检查授权,
 *  ! 并以报错代替跳转.
 */
internal object McpDeviceTools {

    // Declared before `tools`: the device_control schema reads VOLUME_STREAMS
    // eagerly while the tools list is constructed. Object properties initialize
    // in declaration order, so a later declaration would still be null here and
    // crash the whole registry with a NPE inside ExceptionInInitializerError.
    // zh-CN: 必须声明在 `tools` 之前 -- device_control 的 schema 在构建工具列表
    // 时就会读取 VOLUME_STREAMS, object 属性按声明顺序初始化, 声明在后会
    // 因"未初始化即访问"抛出 NPE, 进而让整个注册表初始化失败.
    private const val MAX_BRIGHTNESS = 255

    private val VOLUME_STREAMS = mapOf(
        "music" to AudioManager.STREAM_MUSIC,
        "notification" to AudioManager.STREAM_NOTIFICATION,
        "alarm" to AudioManager.STREAM_ALARM,
    )

    val tools: List<McpTool> = listOf(
        getCurrentActivityTool(), startIntentTool(), deviceControlTool(), deviceStateTool(),
    )

    // ---------------------------------------------------- get_current_activity

    private fun getCurrentActivityTool(): McpTool = McpTool(
        name = "get_current_activity",
        title = "Get the foreground activity",
        description = buildString {
            append("Returns the package and activity class currently in the foreground, as seen through the ")
            append("window state change events the accessibility service receives. ")
            append("Finer than `get_device_info.foregroundApp`: it tells you *where inside* the app the user is, ")
            append("e.g. which Settings page, before you try to navigate or read a screen.")
        },
        risk = McpToolRisk.SAFE,
        inputSchema = McpSchema.emptyObject(),
    ) { _ -> invokeGetCurrentActivity() }

    private fun invokeGetCurrentActivity(): McpToolResult {
        McpUi.requireAccessibilityService()

        val infoProvider = AutoJs.instance.infoProvider
        val packageName = infoProvider.latestPackage
        val activity = infoProvider.latestActivity

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("packageName", packageName)
            addProperty("activity", activity)
            if (packageName.isNotEmpty() && activity.isNotEmpty()) {
                addProperty("component", "$packageName/$activity")
            }
            if (packageName.isEmpty() || activity.isEmpty()) {
                addProperty(
                    "hint",
                    "No window change has been observed yet. The accessibility service may have just " +
                            "connected -- interact with the device once, or call `get_device_info` for the " +
                            "foreground package instead.",
                )
            }
        })
    }

    // ------------------------------------------------------------ start_intent

    private fun startIntentTool(): McpTool = McpTool(
        name = "start_intent",
        title = "Start an activity by intent",
        description = buildString {
            append("Launches an activity by intent rather than by package: a system settings page, a deep link, ")
            append("a share sheet target. Provide any combination of `action`, `dataUri`, `packageName` and ")
            append("`className` -- at least one is required. ")
            append("Useful actions include `android.settings.WIFI_SETTINGS`, `android.settings.BLUETOOTH_SETTINGS`, ")
            append("`android.settings.APPLICATION_DETAILS_SETTINGS` (with a `package:` data URI), and ")
            append("`android.intent.action.VIEW` with an app's deep link URI. ")
            append("App deep links are safer than package launches when several apps can handle the same link.")
        },
        risk = McpToolRisk.SENSITIVE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "action" to McpSchema.string(
                    "The intent action, e.g. `android.settings.WIFI_SETTINGS` or `android.intent.action.VIEW`.",
                ),
                "dataUri" to McpSchema.string(
                    "The data URI, e.g. `package:com.example.app` for application details, or an app deep link.",
                ),
                "packageName" to McpSchema.string(
                    "Restrict handling to this package, or together with `className` target one component.",
                ),
                "className" to McpSchema.string(
                    "Fully qualified activity class; requires `packageName` alongside.",
                ),
                "extras" to McpSchema.freeObject(
                    "String valued extras to attach, e.g. {\"android.intent.extra.TEXT\": \"hello\"}.",
                ),
            ),
        ),
    ) { args -> invokeStartIntent(args) }

    private fun invokeStartIntent(args: McpArgs): McpToolResult {
        val action = args.optString("action")?.takeIf { it.isNotBlank() }
        val dataUri = args.optString("dataUri")?.takeIf { it.isNotBlank() }
        val packageName = args.optString("packageName")?.takeIf { it.isNotBlank() }
        val className = args.optString("className")?.takeIf { it.isNotBlank() }

        if (action == null && dataUri == null && packageName == null) {
            throw McpArgumentException(
                "At least one of `action`, `dataUri` or `packageName` is required, otherwise there is " +
                        "nothing to launch."
            )
        }
        if (className != null && packageName == null) {
            throw McpArgumentException("`className` needs `packageName` alongside; a class name alone is ambiguous.")
        }

        val intent = Intent().apply {
            action?.let { this.action = it }
            dataUri?.let { this.data = Uri.parse(it) }
            if (packageName != null && className != null) {
                setClassName(packageName, className)
            } else {
                packageName?.let { setPackage(it) }
            }
            // Started from an application context, so a task is required: without
            // this flag the system throws rather than choosing a task for it.
            // zh-CN: 从应用上下文启动, 因此需要任务栈标记:
            // 缺少它时系统会抛异常, 而不会代为选择任务栈.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        args.optObject("extras")?.entrySet()?.forEach { (name, value) ->
            if (!value.isJsonPrimitive) {
                throw McpArgumentException(
                    "`extras.$name` must be a string; richer types are not accepted so that a " +
                            "malformed value cannot smuggle an object past the intent."
                )
            }
            intent.putExtra(name, value.asString)
        }

        return try {
            McpUi.context.startActivity(intent)
            McpToolResult.json(McpJson.obj().apply {
                addProperty("ok", true)
                action?.let { addProperty("action", it) }
                dataUri?.let { addProperty("dataUri", it) }
                packageName?.let { addProperty("packageName", it) }
                className?.let { addProperty("className", it) }
            })
        } catch (e: ActivityNotFoundException) {
            McpToolResult.error(
                "No activity on this device handles that intent" +
                        (action?.let { " (action `$it`)" } ?: "") +
                        ". Check the action spelling and whether an app provides the deep link."
            )
        } catch (e: SecurityException) {
            McpToolResult.error(
                "Starting the intent was refused: ${e.message ?: "security exception"}. " +
                        "Some actions need a matching permission the app does not hold."
            )
        }
    }

    // ----------------------------------------------------------- device_control

    private fun deviceControlTool(): McpTool = McpTool(
        name = "device_control",
        title = "Read or set device state",
        description = buildString {
            append("Reads or changes basic device state: media, notification and alarm volume, and screen ")
            append("brightness. ")
            append("`set_volume` takes `stream` (default `music`) and `value` from 0 to that stream's maximum, ")
            append("which the error reports when exceeded. `set_brightness` takes `value` from 0 to 255. ")
            append("Both writes require the modify-system-settings grant for AutoJs6; the error explains how ")
            append("to grant it when missing. Nothing here can be undone by the tool -- read the current value ")
            append("with the matching `get_*` action first if the previous state matters.")
        },
        risk = McpToolRisk.SENSITIVE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "action" to McpSchema.string(
                    "Which state to read or change.",
                    listOf(
                        "get_volume", "set_volume",
                        "get_brightness", "set_brightness",
                    ),
                ),
                "stream" to McpSchema.string(
                    "Which volume stream, for the volume actions.",
                    VOLUME_STREAMS.keys.sorted(),
                    "music",
                ),
                "value" to McpSchema.integer(
                    "The value to set: volume units for `set_volume` (stream specific maximum), " +
                            "0-255 for `set_brightness`.",
                ),
            ),
            required = listOf("action"),
        ),
    ) { args -> invokeDeviceControl(args) }

    private fun invokeDeviceControl(args: McpArgs): McpToolResult {
        val action = args.requireString("action")
        val context = McpUi.context

        return when (action) {
            "get_volume" -> {
                val audio = audioManager()
                val stream = volumeStream(args)
                McpToolResult.json(volumeBody(action, args, audio.getStreamVolume(stream), audio.getStreamMaxVolume(stream)))
            }

            "set_volume" -> {
                val audio = audioManager()
                val stream = volumeStream(args)
                val value = args.optInt("value", Int.MIN_VALUE)
                val max = audio.getStreamMaxVolume(stream)
                if (value == Int.MIN_VALUE) {
                    throw McpArgumentException("`value` is required for `set_volume`, from 0 to $max.")
                }
                requireWriteSettings(context)
                if (value < 0 || value > max) {
                    throw McpArgumentException("`value` $value is outside 0..$max for this stream.")
                }
                runCatching { audio.setStreamVolume(stream, value, 0) }.getOrElse {
                    return McpToolResult.error(
                        "Setting the volume failed: ${it::class.java.simpleName}: ${it.message ?: "no message"}. " +
                                "Some streams refuse changes while a do-not-disturb mode is active."
                    )
                }
                McpToolResult.json(volumeBody(action, args, value, max))
            }

            "get_brightness" -> {
                val current = runCatching {
                    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                }.getOrElse {
                    return McpToolResult.error("Reading the brightness failed: ${it.message ?: "no message"}.")
                }
                McpToolResult.json(McpJson.obj().apply {
                    addProperty("action", action)
                    addProperty("brightness", current)
                    addProperty("max", MAX_BRIGHTNESS)
                })
            }

            "set_brightness" -> {
                val value = args.optInt("value", Int.MIN_VALUE)
                if (value == Int.MIN_VALUE) {
                    throw McpArgumentException("`value` is required for `set_brightness`, from 0 to $MAX_BRIGHTNESS.")
                }
                if (value < 0 || value > MAX_BRIGHTNESS) {
                    throw McpArgumentException("`value` $value is outside 0..$MAX_BRIGHTNESS.")
                }
                requireWriteSettings(context)
                val changed = runCatching {
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
                }.getOrElse {
                    return McpToolResult.error(
                        "Setting the brightness failed: ${it::class.java.simpleName}: ${it.message ?: "no message"}."
                    )
                }
                McpToolResult.json(McpJson.obj().apply {
                    addProperty("ok", changed)
                    addProperty("action", action)
                    addProperty("brightness", value)
                })
            }

            else -> throw McpArgumentException(
                "Unknown action `$action`. Supported actions: get_volume, set_volume, get_brightness, set_brightness."
            )
        }
    }

    // ------------------------------------------------------------ device_state

    private const val BATTERY_MAX = 100

    private fun batteryStatusName(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> "unknown"
        else -> "discharging"
    }

    /**
     * One call that answers "is the device in a state to do work right now": battery
     * headroom, memory pressure and whether the screen is even on.
     *
     * @Note
     *  ! Everything here reads permission-free APIs on purpose. `BatteryManager`
     *  ! properties instead of the sticky battery broadcast avoid the receiver
     *  ! export flags that `targetSdkVersion >= 34` started demanding, and
     *  ! `PowerManager.isInteractive` needs no grant at all.
     *  ! zh-CN: 此处刻意全部读取免权限 API. 用 `BatteryManager` 属性而非电池粘性广播,
     *  ! 避开 `targetSdkVersion >= 34` 开始强制要求的 receiver 导出标志;
     *  ! `PowerManager.isInteractive` 则完全无需授权.
     */
    private fun deviceStateTool(): McpTool = McpTool(
        name = "device_state",
        title = "Read battery, memory and screen state",
        description = buildString {
            append("Reads device state in one call: battery level and charging status, memory total and ")
            append("available, and whether the screen is interactive. ")
            append("Use it before a long or heavy task to judge whether the device can take one, and when ")
            append("a gesture or screenshot mysteriously fails -- a screen that has dozed off explains a lot. ")
            append("Volume and brightness live in `device_control`; this tool is read only.")
        },
        risk = McpToolRisk.SAFE,
        inputSchema = McpSchema.emptyObject(),
    ) { _ -> invokeDeviceState() }

    private fun invokeDeviceState(): McpToolResult {
        val context = McpUi.context

        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 1..BATTERY_MAX }
        val status = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN

        val memory = runCatching {
            // Output-parameter style: getMemoryInfo fills a caller-supplied object
            // rather than returning one, so the property-access syntax does not apply.
            // zh-CN: 输出参数风格: getMemoryInfo 填充调用方提供的对象而不返回新对象,
            // 因此不能使用属性访问语法.
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        }.getOrNull()

        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val brightness = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()
        val brightnessMode = runCatching {
            when (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> "auto"
                else -> "manual"
            }
        }.getOrNull()

        return McpToolResult.json(McpJson.obj().apply {
            add("battery", McpJson.obj().apply {
                level?.let { addProperty("levelPercent", it) }
                addProperty("status", batteryStatusName(status))
            })
            add("memory", McpJson.obj().apply {
                memory?.let {
                    addProperty("totalBytes", it.totalMem)
                    addProperty("availableBytes", it.availMem)
                    addProperty("low", it.lowMemory)
                } ?: addProperty("hint", "Memory info was unavailable on this device.")
            })
            add("screen", McpJson.obj().apply {
                addProperty("interactive", power?.isInteractive ?: false)
                brightness?.let { addProperty("brightness", it) }
                brightnessMode?.let { addProperty("brightnessMode", it) }
                addProperty("maxBrightness", MAX_BRIGHTNESS)
            })
        })
    }

    private fun volumeStream(args: McpArgs): Int {
        val name = args.optString("stream")?.takeIf { it.isNotBlank() } ?: "music"
        return VOLUME_STREAMS[name]
            ?: throw McpArgumentException(
                "Unknown stream `$name`. Supported streams: ${VOLUME_STREAMS.keys.sorted().joinToString(", ")}."
            )
    }

    private fun volumeBody(action: String, args: McpArgs, value: Int, max: Int): JsonObject {
        val streamName = args.optString("stream")?.takeIf { it.isNotBlank() } ?: "music"
        return McpJson.obj().apply {
            addProperty("action", action)
            addProperty("stream", streamName)
            addProperty("volume", value)
            addProperty("max", max)
        }
    }

    private fun audioManager(): AudioManager =
        McpUi.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Reports a missing grant instead of triggering the settings page the shared
     * `Device` helpers would open behind the caller's back.
     * zh-CN: 缺少授权时直接报错, 而不是像共享的 `Device` 辅助方法那样
     * 在调用方不知情时打开设置页.
     */
    private fun requireWriteSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
            throw McpArgumentException(
                "AutoJs6 has not been granted modify system settings. " +
                        "Open `Settings > Apps > AutoJs6 > Advanced > Modify system settings`, enable it, " +
                        "or `start_intent` the action `android.settings.MANAGE_WRITE_SETTINGS` for the user, " +
                        "then retry."
            )
        }
    }

}
