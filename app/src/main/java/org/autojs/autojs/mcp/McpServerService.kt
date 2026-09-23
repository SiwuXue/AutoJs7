package org.autojs.autojs.mcp

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.autojs.autojs.core.pref.Pref
import org.autojs.autojs.tool.ForegroundServiceCreator
import org.autojs.autojs.ui.main.MainActivity
import org.autojs.autojs.ui.settings.DeveloperOptionsActivity
import org.autojs.autojs.util.ForegroundServiceUtils.FOREGROUND_SERVICE_TYPE_UNKNOWN
import org.autojs.autojs6.R

/**
 * Keeps the MCP server alive while it is enabled.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Why
 *  ! A plain started service is liable to be killed once the app leaves the
 *  ! foreground, which would silently drop the connection of an AI client that
 *  ! is mid-task. Hosting the server in a foreground service with a visible
 *  ! notification keeps it alive and, just as importantly, keeps the user aware
 *  ! that a remote control channel is open.
 *  ! zh-CN: 普通启动型服务在应用退到后台后很容易被回收, 这会让正在执行任务的 AI 客户端
 *  ! 连接被静默断开. 把服务放在前台服务中并显示常驻通知, 既能保活,
 *  ! 更重要的是让用户始终知道有一条远程控制通道处于开启状态.
 *
 * @Note
 *  ! The notification is the user's only always-visible evidence that the channel
 *  ! is open, so it carries everything needed to act on that: both endpoints, the
 *  ! number of connected clients, and buttons to stop the server or open its
 *  ! settings without going through the app first.
 *  ! zh-CN: 通知是用户唯一始终可见的"通道已开启"证据, 因此它承载了据此行动所需的一切:
 *  ! 两个端点地址, 已连接客户端数量, 以及无需先进入应用即可停止服务或打开设置的按钮.
 */
class McpServerService : Service() {

    private lateinit var foregroundCreator: ForegroundServiceCreator

    private val foregroundServiceType = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else -> FOREGROUND_SERVICE_TYPE_UNKNOWN
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        val label = packageManager.getApplicationLabel(applicationInfo).toString()

        // startForeground() has to happen inside onCreate rather than in a
        // static helper, otherwise the system may kill the service before the
        // notification exists.
        // zh-CN: startForeground() 必须在 onCreate 内调用而非静态方法中,
        // 否则系统可能在通知存在之前就回收该服务.
        foregroundCreator = ForegroundServiceCreator.Builder(this)
            .setClassName(McpServerService::class.java)
            .setIntent(Intent(this, MainActivity::class.java))
            .setNotificationId(NOTIFICATION_ID)
            .setServiceName(getString(R.string.mcp_notification_channel_name, label))
            .setServiceDescription(getString(R.string.mcp_notification_channel_name, label))
            .setNotificationTitle(getString(R.string.mcp_notification_title))
            .setNotificationContent(notificationContent())
            .setActions(notificationActions())
            .create()
            .apply { startForeground(foregroundServiceType) }

        // Registered before the server starts so the very first client to connect
        // is already counted in the notification.
        // zh-CN: 在服务端启动前注册, 使第一个连接的客户端就已计入通知.
        McpServer.onClientCountChanged = { refreshNotification() }

        McpServer.start()

        // Second line of defence for aggressive power managers that stop the
        // service without the platform restarting it. See McpWatchdog.
        // zh-CN: 针对"被激进电源管理停止且系统不再拉起"的第二道防线. 见 McpWatchdog.
        McpWatchdog.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The stop button must also clear the stored preference, otherwise the
            // watchdog would read "enabled but not running" and immediately start
            // the service again -- turning a deliberate stop into a restart loop.
            // zh-CN: 停止按钮必须同时清除存储的偏好, 否则看门狗会读到
            // "已启用但未运行" 并立刻重新启动服务 —— 把一次刻意停止变成重启死循环.
            Pref.putBoolean(R.string.key_mcp_server_enabled, false)
            McpWatchdog.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        // Idempotent: McpServer.start() returns early when already listening.
        // zh-CN: 幂等: 若已在监听, McpServer.start() 会提前返回.
        McpServer.start()
        return START_STICKY
    }

    override fun onDestroy() {
        McpServer.onClientCountChanged = null

        // Only a user-initiated stop should silence the watchdog. A stop issued by
        // the system is exactly the case the watchdog exists for, so it keeps
        // running and brings the service back.
        // zh-CN: 只有用户主动停止才应让看门狗静默. 系统发起的停止正是看门狗存在的意义,
        // 因此它继续运行并把服务带回来.
        if (!Pref.isMcpServerEnabled) {
            McpWatchdog.stop()
        }

        McpServer.stop()
        foregroundCreator.stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * Body of the persistent notification: both endpoints plus the live client
     * count, one per line.
     * zh-CN: 常驻通知的内容: 两个端点地址加上实时客户端数量, 每行一项.
     */
    private fun notificationContent(): String = buildString {
        append(getString(R.string.mcp_endpoint_local, McpServer.localEndpoint()))
        append('\n')
        val lan = McpServer.lanEndpoint()
        if (lan != null) {
            append(getString(R.string.mcp_endpoint_lan, lan))
        } else {
            // Shown even when unreachable, so the address to enable is visible
            // rather than merely absent.
            // zh-CN: 即使不可达也显示, 让"待启用的地址"可见, 而不是干脆消失.
            append(getString(R.string.mcp_endpoint_lan_disabled))
        }
        append('\n')
        append(getString(R.string.mcp_notification_clients, McpServer.connectedClientCount()))
    }

    /** Stop and settings buttons on the notification itself. zh-CN: 通知自带的停止与设置按钮. */
    private fun notificationActions(): List<NotificationCompat.Action> = listOf(
        NotificationCompat.Action(
            0,
            getString(R.string.mcp_notification_action_stop),
            PendingIntent.getService(
                this,
                REQUEST_STOP,
                Intent(this, McpServerService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ),
        NotificationCompat.Action(
            0,
            getString(R.string.mcp_notification_action_settings),
            PendingIntent.getActivity(
                this,
                REQUEST_SETTINGS,
                Intent(this, DeveloperOptionsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ),
    )

    /**
     * Rewrites the notification after the client count changed.
     *
     * @Note
     *  ! Called from an HTTP worker thread. Nothing here touches the main thread,
     *  ! and `NotificationManager.notify` is safe from any thread, so no handler
     *  ! hop is needed. Failures are swallowed on purpose: a notification that
     *  ! could not be refreshed must never break the connection that triggered it.
     *  ! zh-CN: 由 HTTP 工作线程调用. 此处不涉及主线程, 且 `NotificationManager.notify`
     *  ! 可从任意线程安全调用, 因此无需切回 handler. 失败被刻意吞掉:
     *  ! 一次未能刷新的通知绝不能弄断触发它的那条连接.
     */
    private fun refreshNotification() {
        runCatching { foregroundCreator.updateNotification(notificationContent()) }
    }

    companion object {

        private const val NOTIFICATION_ID = 0xC0

        /**
         * Action consumed by [onStartCommand]. Namespaced so it cannot collide with
         * an action some other component sends to this service.
         * zh-CN: 由 [onStartCommand] 消费的动作. 加了命名空间, 以免与其他组件发来的动作冲突.
         */
        private const val ACTION_STOP = "org.autojs.autojs.mcp.action.STOP"

        private const val REQUEST_STOP = 1

        private const val REQUEST_SETTINGS = 2

        /**
         * Starts the service, preferring the foreground variant on API 26+.
         * zh-CN: 启动服务, 在 API 26 及以上使用前台服务方式.
         */
        fun start(context: Context) {
            val intent = Intent(context, McpServerService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, McpServerService::class.java)) }
        }

        fun isRunning(context: Context): Boolean =
            org.autojs.autojs.util.ForegroundServiceUtils.isRunning(
                context, McpServerService::class.java,
            )

    }

}
