package org.autojs.autojs.mcp

import android.util.Log
import io.reactivex.subjects.BehaviorSubject
import org.autojs.autojs.core.pref.Pref
import org.autojs.autojs6.R
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Server state as observed by the settings UI.
 * zh-CN: 设置界面所观察到的服务状态.
 */
sealed class McpServerState {

    /** Not listening. zh-CN: 未监听. */
    object Stopped : McpServerState()

    data class Running(val port: Int, val lanAccess: Boolean) : McpServerState()

    data class Failed(val reason: String) : McpServerState()

}

/**
 * Facade over the MCP server lifecycle.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Design
 *  ! The switch in settings stores the user's intent, while this object owns the
 *  ! actual socket. Keeping the two apart is what allows the UI to show the real
 *  ! state instead of blindly echoing a boolean that may no longer be true, for
 *  ! example after the port was taken by another app.
 *  ! zh-CN: 设置中的开关保存的是用户意图, 而实际 socket 由本对象持有.
 *  ! 把两者分开, 界面才能展示真实状态, 而不是盲目回显一个可能已不成立的布尔值
 *  ! (例如端口被其他应用占用之后).
 */
object McpServer {

    private const val TAG = "McpServer"

    const val LOOPBACK_HOST = "127.0.0.1"

    /** Wildcard address, used when local network access is allowed. */
    private const val WILDCARD_HOST = "0.0.0.0"

    val state: BehaviorSubject<McpServerState> = BehaviorSubject.createDefault(McpServerState.Stopped)

    @Volatile
    private var server: McpHttpServer? = null

    @Volatile
    private var lastFailureReason: String? = null

    val isRunning: Boolean
        get() = server?.isRunning == true

    val lastFailure: String?
        get() = lastFailureReason

    /**
     * Starts the server using the current preferences.
     *
     * @return the resulting state, which is [McpServerState.Failed] when the
     * port is taken or the address cannot be bound.
     */
    @Synchronized
    fun start(): McpServerState {
        server?.takeIf { it.isRunning }?.let { return currentRunningState() }

        // Make sure a token exists before the port opens, so there is never a
        // window in which the server accepts unauthenticated connections.
        // zh-CN: 在端口开放前确保令牌已存在,
        // 从而不存在服务接受未鉴权连接的时间窗口.
        McpSecurity.accessToken()

        val lanAccess = Pref.isMcpServerLanAccessEnabled
        val port = Pref.mcpServerPort

        val instance = McpHttpServer(
            requestedPort = port,
            bindAddress = InetAddress.getByName(if (lanAccess) WILDCARD_HOST else LOOPBACK_HOST),
            // Forwarded rather than consumed here: the notification that shows the
            // count belongs to the foreground service, not to this facade.
            // zh-CN: 这里只做转发而非自行消费: 展示连接数的通知属于前台服务, 不属于本门面.
            onClientCountChanged = { count -> onClientCountChanged?.invoke(count) },
        )

        return try {
            instance.start()
            server = instance
            lastFailureReason = null
            McpServerState.Running(instance.localPort, lanAccess).also {
                Log.i(TAG, "MCP server started at ${endpointDescription()}")
                state.onNext(it)
            }
        } catch (e: Throwable) {
            runCatching { instance.stop() }
            server = null
            val reason = describeStartFailure(e, port)
            lastFailureReason = reason
            Log.w(TAG, "MCP server failed to start: $reason")
            McpServerState.Failed(reason).also { state.onNext(it) }
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        lastFailureReason = null
        state.onNext(McpServerState.Stopped)
        Log.i(TAG, "MCP server stopped")
    }

    /**
     * Applies the stored preference to the running state.
     *
     * @Note
     *  ! The foreground service, not this object, is the single entry point for
     *  ! turning the server on and off. Routing every start through the service
     *  ! is what guarantees the notification exists, so the user can always see
     *  ! that a remote control channel is open.
     *  ! zh-CN: 开关服务的唯一入口是前台服务, 而不是本对象.
     *  ! 所有启动都经由服务, 可以保证常驻通知始终存在,
     *  ! 从而让用户随时看到有一条远程控制通道处于开启状态.
     */
    @Synchronized
    fun applyPreference() {
        when (Pref.isMcpServerEnabled) {
            true -> McpServerService.start(McpUi.context)
            false -> McpServerService.stop(McpUi.context)
        }
    }

    /**
     * Restarts the server so a changed port or bind address takes effect.
     * zh-CN: 重启服务, 使修改后的端口或绑定地址生效.
     */
    @Synchronized
    fun restartIfRunning(): McpServerState = when (isRunning) {
        true -> {
            stop()
            start()
        }
        false -> McpServerState.Stopped
    }

    fun connectedClientCount(): Int = server?.connectedClientCount() ?: 0

    /**
     * Notified whenever a client connects or disconnects, so the foreground
     * service can keep its notification in step. Set by the service and cleared
     * when it goes away.
     * zh-CN: 每当有客户端连接或断开时回调, 使前台服务能同步刷新通知.
     * 由服务设置, 服务销毁时清空.
     */
    @Volatile
    var onClientCountChanged: ((Int) -> Unit)? = null

    /** Loopback endpoint, always reachable from this device. zh-CN: 本机回环端点. */
    fun localEndpoint(): String =
        "http://$LOOPBACK_HOST:${boundPort()}${McpHttpServer.ENDPOINT_MCP}"

    /**
     * LAN endpoint, or null when local network access is off or no site-local
     * address could be resolved.
     * zh-CN: 局域网端点; 未开启局域网访问或解析不到站点本地地址时为 null.
     */
    fun lanEndpoint(): String? {
        if (!Pref.isMcpServerLanAccessEnabled) return null
        val host = localNetworkAddress() ?: return null
        return "http://$host:${boundPort()}${McpHttpServer.ENDPOINT_MCP}"
    }

    /**
     * The single URL used in logs: the LAN one when it exists, since that is the
     * address a remote client actually needs, otherwise the loopback one.
     * zh-CN: 日志中使用的单一 URL: 存在局域网地址时优先使用它 ——
     * 那才是远程客户端真正需要的地址 —— 否则回退到回环地址.
     */
    fun endpointDescription(): String = lanEndpoint() ?: localEndpoint()

    private fun boundPort(): Int = server?.localPort ?: Pref.mcpServerPort

    private fun currentRunningState(): McpServerState = McpServerState.Running(
        port = server?.localPort ?: Pref.mcpServerPort,
        lanAccess = Pref.isMcpServerLanAccessEnabled,
    )

    private fun describeStartFailure(error: Throwable, port: Int): String = when (error) {
        is BindException -> "Port $port is already in use, or binding it was refused."
        else -> "${error::class.java.simpleName}: ${error.message ?: "no message"}"
    }

    /**
     * Best-effort lookup of the device's local IPv4 address, used only to show a
     * usable URL when local network access is enabled.
     * zh-CN: 尽力查找本机局域网 IPv4 地址, 仅在开启局域网访问时用于展示可用 URL.
     */
    private fun localNetworkAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList().asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

}
