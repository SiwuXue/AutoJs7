package org.autojs.autojs.mcp

import android.util.Log
import org.autojs.autojs.core.pref.Pref
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Restarts the MCP foreground service when an aggressive power manager kills it.
 *
 * @Created by fork author on Sep 23, 2026.
 *
 * @Why
 *  ! `START_STICKY` is not enough on EMUI/HarmonyOS. Its background manager stops
 *  ! a foreground service outright -- observed as `AppBgModeMgr ... stop` a few
 *  ! hundred milliseconds after start -- and a service stopped that way is not
 *  ! always restarted by the platform. Because this object lives in the
 *  ! application process, which the accessibility service and the notification
 *  ! listener keep bound, it outlives the service and can bring it back.
 *  ! zh-CN: 在 EMUI/HarmonyOS 上 `START_STICKY` 并不够用. 其后台管控系统会直接
 *  ! 停止前台服务 —— 实测表现为启动数百毫秒后出现 `AppBgModeMgr ... stop` ——
 *  ! 而被这样停止的服务并不总能被系统重新拉起. 由于本对象位于应用进程内,
 *  ! 而该进程被无障碍服务与通知监听服务绑定而存活, 因此它能比服务活得更久,
 *  ! 从而把服务重新带起来.
 *
 * @Note
 *  ! A watchdog can only ever be a second line of defence. If the whole process is
 *  ! reclaimed, this goes with it, and only the platform can restore the service.
 *  ! The first line remains the user's battery settings plus the visible
 *  ! notification -- see [McpServerService].
 *  ! zh-CN: 看门狗只能是第二道防线. 若整个进程被回收, 它也随之消失,
 *  ! 此时只有系统能恢复服务. 第一道防线始终是用户的电池设置加上可见通知 ——
 *  ! 见 [McpServerService].
 */
internal object McpWatchdog {

    private const val TAG = "McpWatchdog"

    /**
     * Long enough that a restart is never mistaken for a loop, short enough that
     * a client notices the interruption only as a brief pause.
     * zh-CN: 足够长以免把重启误判为死循环, 又足够短以使用户仅感觉到短暂中断.
     */
    private const val CHECK_INTERVAL_MS = 30_000L

    @Volatile
    private var executor: ScheduledExecutorService? = null

    /** Starts watching, or does nothing when already watching. zh-CN: 开始看守; 已在看守时不做任何事. */
    @Synchronized
    fun start() {
        if (executor != null) return

        val service = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mcp-watchdog").apply { isDaemon = true }
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

    /** Stops watching. Called when the user turns the server off. zh-CN: 停止看守, 在用户关闭服务时调用. */
    @Synchronized
    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    /**
     * One liveness check.
     *
     * @Note
     *  ! Both the preference and the running state are consulted. The preference
     *  ! alone would restart a server the user deliberately stopped, and the state
     *  ! alone would resurrect one that was never enabled.
     *  ! zh-CN: 同时检查偏好与运行状态. 只看偏好会把用户刻意停止的服务重新拉起,
     *  ! 只看状态则会把从未启用的服务复活.
     */
    private fun tick() {
        runCatching {
            if (!Pref.isMcpServerEnabled) {
                // The user turned it off while the watchdog was running, so there
                // is nothing left to guard.
                // zh-CN: 用户在看守期间关闭了服务, 因此已无可看守之物.
                stop()
                return
            }

            val context = McpUi.context
            if (McpServer.isRunning || McpServerService.isRunning(context)) return

            Log.i(TAG, "MCP service is not running while enabled; restarting it")
            McpServerService.start(context)
        }.onFailure {
            // Never let a failure escape: an exception thrown out of a
            // scheduleWithFixedDelay task cancels every later run, which would
            // silently disable the watchdog for the rest of the process lifetime.
            // zh-CN: 绝不让异常逃逸: 从 scheduleWithFixedDelay 任务中抛出的异常会
            // 取消后续所有执行, 从而在进程余下的生命周期里静默停用看门狗.
            Log.w(TAG, "liveness check failed: ${it.message}")
        }
    }

}
