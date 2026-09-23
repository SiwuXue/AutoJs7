package org.autojs.autojs.core.accessibility

import android.provider.Settings
import android.util.Log
import org.autojs.autojs.app.GlobalAppContext
import org.autojs.autojs.core.pref.Pref
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Re-enables AutoJs6's accessibility service after the system turns it off.
 *
 * @Created by fork author on Sep 23, 2026.
 *
 * @Why
 *  ! Updating an app makes Android disable that app's accessibility service --
 *  ! a security measure so an update cannot silently swap in different
 *  ! accessibility code. The user then has to switch it back on by hand, and on
 *  ! EMUI/HarmonyOS nothing ever switches it back, so the service simply stays
 *  ! off. On this device the setting was observed to be wiped outright:
 *  ! `enabled_accessibility_services` read `null` and `accessibility_enabled`
 *  ! read `0`, which is why the service never came back on its own.
 *  ! zh-CN: 应用更新会让 Android 禁用该应用的无障碍服务 —— 这是安全措施,
 *  ! 防止一次更新悄悄换成另一套无障碍代码. 之后用户必须手动重新打开,
 *  ! 而在 EMUI/HarmonyOS 上没有任何东西会把它打开, 于是服务就一直是关的.
 *  ! 在本机上实测该设置被整个抹掉: `enabled_accessibility_services` 读到 `null`,
 *  ! `accessibility_enabled` 读到 `0`, 这正是它无法自行恢复的原因.
 *
 * @Requirement
 *  ! Needs `WRITE_SECURE_SETTINGS`, which is a development permission and can only
 *  ! be granted over adb: `pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`.
 *  ! Without it every attempt fails with a `SecurityException`, which is logged
 *  ! rather than retried in a loop. The grant survives app updates but not a
 *  ! reinstall, so it has to be repeated after a fresh install.
 *  ! zh-CN: 需要 `WRITE_SECURE_SETTINGS`, 它是开发权限, 只能通过 adb 授予:
 *  ! `pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`.
 *  ! 缺少它时每次尝试都会抛 `SecurityException`, 此处只记录日志而不会循环重试.
 *  ! 该授权在应用更新后仍然有效, 但重装会丢失, 因此全新安装后需要重新授予.
 */
internal object AccessibilityWatchdog {

    private const val TAG = "A11yWatchdog"

    /**
     * A disconnect is not urgent -- the user may be mid-task -- but leaving it for
     * minutes makes every UI tool fail. Thirty seconds mirrors the MCP watchdog.
     * zh-CN: 断开并不紧急 —— 用户可能正在做事 —— 但拖上几分钟会让所有 UI 工具失效.
     * 三十秒与 MCP 看门狗保持一致.
     */
    private const val CHECK_INTERVAL_MS = 30_000L

    /**
     * Stored as a literal key rather than a string resource: this flag is internal
     * bookkeeping with nothing to translate, and `Pref` accepts a plain key.
     * zh-CN: 以字面 key 存储而非字符串资源: 该标记属于内部记账, 没有任何需要翻译的内容,
     * 而 `Pref` 本就接受普通字符串 key.
     */
    private const val KEY_AUTO_RESTORE = "key_\$_accessibility_auto_restore"

    /**
     * Spelled as a literal because only the name is needed, and referencing the
     * class would make this object depend on the service it is watching.
     * zh-CN: 写成字面量, 因为只需要这个名字; 引用该类会让本对象依赖它所看守的服务.
     */
    private const val SERVICE_CLASS = "org.autojs.autojs.core.accessibility.AccessibilityServiceUsher"

    private const val SEPARATOR = ":"

    @Volatile
    private var executor: ScheduledExecutorService? = null

    /** Starts watching, or does nothing when already watching. zh-CN: 开始看守; 已在看守时不做任何事. */
    @Synchronized
    fun start() {
        if (executor != null) return

        val service = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "a11y-watchdog").apply { isDaemon = true }
        }
        service.scheduleWithFixedDelay(
            { tick() },
            CHECK_INTERVAL_MS,
            CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
        executor = service
        Log.i(TAG, "watchdog started, interval=${CHECK_INTERVAL_MS}ms")
    }

    @Synchronized
    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    /**
     * One liveness check.
     *
     * @Note
     *  ! Restoring only happens once the user has had the service on at least once,
     *  ! tracked by [KEY_AUTO_RESTORE]. Without that guard this would switch
     *  ! accessibility on for someone who never asked for it -- a service that can
     *  ! read every screen is not something to enable on a user's behalf.
     *  ! zh-CN: 只有在用户至少开启过一次之后才会恢复, 由 [KEY_AUTO_RESTORE] 记录.
     *  ! 没有这道防线, 本功能会替从未开启过无障碍的用户把它打开 ——
     *  ! 一个能读取全部屏幕的服务, 不该由程序替用户做主启用.
     */
    private fun tick() {
        runCatching {
            if (AccessibilityService.hasInstance()) {
                // Remember the intent, so a later wipe can be undone.
                // zh-CN: 记录用户的意愿, 使之后被抹掉时能够恢复.
                if (!Pref.getBoolean(KEY_AUTO_RESTORE, false)) {
                    Pref.putBoolean(KEY_AUTO_RESTORE, true)
                }
                return
            }

            if (!Pref.getBoolean(KEY_AUTO_RESTORE, false)) return

            restore()
        }.onFailure {
            // Never let a failure escape: an exception thrown out of a
            // scheduleWithFixedDelay task cancels every later run, which would
            // silently disable the watchdog for the rest of the process lifetime.
            // zh-CN: 绝不让异常逃逸: 从 scheduleWithFixedDelay 任务中抛出的异常会
            // 取消后续所有执行, 从而在进程余下的生命周期里静默停用看门狗.
            Log.w(TAG, "liveness check failed: ${it.message}")
        }
    }

    /**
     * Puts the service back into the enabled list and, when it is already listed
     * but not bound, rewrites the list so the system rebinds it.
     * zh-CN: 把服务重新放进已启用列表; 若它已在列表中却没有绑定, 则重写该列表以促使系统重新绑定.
     */
    private fun restore() {
        val context = GlobalAppContext.get()
        val component = "${context.packageName}/$SERVICE_CLASS"
        val resolver = context.contentResolver

        val current = Settings.Secure.getString(
            resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val listed = current.split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        try {
            if (component in listed) {
                // The half-dead case: present in the setting but not bound, which
                // a plain write of the same value would not disturb because the
                // value does not change. Removing it first makes the change real.
                // zh-CN: 半死状态: 设置里还在但没有绑定. 直接写入同样的值不会触发变化,
                // 因为值本身没变. 先移除它, 这次改动才真正发生.
                val without = listed.filter { it != component }
                writeList(resolver, without)
                writeList(resolver, without + component)
                Log.i(TAG, "accessibility service was listed but unbound; rewrote the list to rebind it")
            } else {
                writeList(resolver, listed + component)
                Log.i(TAG, "accessibility service was missing from the enabled list; added it back")
            }
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (e: SecurityException) {
            Log.w(
                TAG,
                "WRITE_SECURE_SETTINGS is not granted, so accessibility cannot be restored. " +
                        "Grant it over adb: pm grant ${context.packageName} " +
                        "android.permission.WRITE_SECURE_SETTINGS",
                e,
            )
        }
    }

    private fun writeList(resolver: android.content.ContentResolver, components: List<String>) {
        Settings.Secure.putString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            components.joinToString(SEPARATOR),
        )
    }

}
