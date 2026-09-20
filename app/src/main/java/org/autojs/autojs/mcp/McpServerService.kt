package org.autojs.autojs.mcp

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import android.os.IBinder
import org.autojs.autojs.tool.ForegroundServiceCreator
import org.autojs.autojs.ui.main.MainActivity
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
            .setNotificationContent(getString(R.string.mcp_notification_text, McpServer.endpointDescription()))
            .create()
            .apply { startForeground(foregroundServiceType) }

        McpServer.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Idempotent: McpServer.start() returns early when already listening.
        // zh-CN: 幂等: 若已在监听, McpServer.start() 会提前返回.
        McpServer.start()
        return START_STICKY
    }

    override fun onDestroy() {
        McpServer.stop()
        foregroundCreator.stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {

        private const val NOTIFICATION_ID = 0xC0

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
