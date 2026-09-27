package org.autojs.autojs.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.widget.Toast
import com.afollestad.materialdialogs.MaterialDialog
import io.reactivex.disposables.Disposable
import org.autojs.autojs.core.pref.Pref
import org.autojs.autojs.mcp.McpSecurity
import org.autojs.autojs.mcp.McpServer
import org.autojs.autojs.mcp.McpServerService
import org.autojs.autojs.mcp.McpSwitchState
import org.autojs.autojs.mcp.McpUi
import org.autojs.autojs.theme.preference.MaterialPreference
import org.autojs.autojs.theme.preference.Syncable
import org.autojs.autojs.theme.preference.ThemeColorSwitchPreference
import org.autojs.autojs6.R

/**
 * MCP server preferences.
 *
 * @Created by fork author on Sep 16, 2026.
 */

/**
 * Master switch.
 *
 * @Design
 *  ! The switch mirrors the persisted user intent, while its summary reports
 *  ! whether the service and socket are actually running. The generic service
 *  ! switch toggles from a potentially stale UI value before the asynchronous
 *  ! foreground service finishes starting; this preference must not use it.
 *  ! zh-CN: 开关反映持久化的用户意图, 状态文字反映服务和端口的真实状态.
 *  ! 通用服务开关会根据可能过时的 UI 值切换, 与异步启动前台服务存在竞态,
 *  ! 因此这里不使用通用服务开关的点击逻辑.
 */
class McpServerSwitchPreference : ThemeColorSwitchPreference, Syncable {

    private var riskDialog: MaterialDialog? = null
    private var stateSubscription: Disposable? = null
    private var attached = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val enabledListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == prefContext.getString(R.string.key_mcp_server_enabled)) refreshUi()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context) : super(context)

    init {
        // McpServer is the sole writer of the persisted enabled intent.
        isPersistent = false
        summaryProvider = SummaryProvider<McpServerSwitchPreference> { describeState() }
    }

    override fun onAttached() {
        super.onAttached()
        attached = true
        Pref.get().registerOnSharedPreferenceChangeListener(enabledListener)
        stateSubscription = McpServer.state.subscribe { refreshUi() }
        sync()
    }

    override fun sync() = refreshUi()

    override fun onClick() {
        if (!Pref.isMcpServerEnabled && requiresConsent()) {
            showRiskDialog()
            return
        }
        proceedWithToggle()
    }

    private fun proceedWithToggle() {
        if (McpSwitchState.nextEnabled(Pref.isMcpServerEnabled)) {
            McpServer.enable("developer-options")
        } else {
            McpServer.disable("developer-options")
        }
        refreshUi()
    }

    override fun onDetached() {
        attached = false
        Pref.get().unregisterOnSharedPreferenceChangeListener(enabledListener)
        stateSubscription?.dispose()
        stateSubscription = null
        riskDialog?.dismiss()
        riskDialog = null
        super.onDetached()
    }

    private fun refreshUi() {
        val update = {
            if (attached) {
                isChecked = Pref.isMcpServerEnabled
                notifyChanged()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) update() else mainHandler.post(update)
    }

    /** Stopping needs no consent, and consent is only ever asked once. */
    private fun requiresConsent(): Boolean = McpServer.requiresConsent()

    private fun showRiskDialog() {
        if (riskDialog?.isShowing == true) return
        riskDialog = MaterialDialog.Builder(prefContext)
            .title(R.string.dialog_mcp_server_risk_title)
            .content(R.string.dialog_mcp_server_risk_message)
            .positiveText(R.string.dialog_button_confirm)
            .positiveColorRes(R.color.dialog_button_attraction)
            .negativeText(R.string.dialog_button_cancel)
            .negativeColorRes(R.color.dialog_button_default)
            .onPositive { _, _ ->
                McpServer.acknowledgeConsent()
                riskDialog = null
                // Re-run the toggle now that consent has been recorded.
                // zh-CN: 同意已记录, 重新执行切换.
                proceedWithToggle()
            }
            .onNegative { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun describeState(): CharSequence {
        when (McpSwitchState.summary(
            Pref.isMcpServerEnabled,
            McpServer.isRunning,
            McpServerService.isRunning(prefContext),
            McpServer.lastFailure,
        )) {
            McpSwitchState.Summary.STOPPED -> return prefContext.getString(R.string.summary_mcp_server_stopped)
            McpSwitchState.Summary.STARTING -> return prefContext.getString(R.string.summary_mcp_server_starting)
            McpSwitchState.Summary.FAILED -> return prefContext.getString(
                R.string.summary_mcp_server_failed,
                McpServer.lastFailure ?: "unknown error",
            )
            McpSwitchState.Summary.RUNNING -> Unit
        }

        val base = prefContext.getString(R.string.summary_mcp_server_running, endpointSummary())
        // The server still runs without accessibility, but most tools will fail,
        // which is worth saying up front rather than letting the AI discover it.
        // zh-CN: 无无障碍服务时服务端仍会运行, 但多数工具会失败;
        // 这一点应当提前说明, 而不是等 AI 自己去撞.
        return when {
            McpUi.hasAccessibilityService() -> base
            else -> "$base · ${prefContext.getString(R.string.summary_mcp_server_a11y_required)}"
        }
    }

    /**
     * Both endpoints, one per line, so this screen lists the same pair the
     * notification does rather than only whichever address is currently preferred.
     * zh-CN: 两个端点各占一行, 使本界面与通知列出同一组地址,
     * 而不是只显示当前优先的那一个.
     */
    private fun endpointSummary(): String = buildString {
        append(prefContext.getString(R.string.mcp_endpoint_local, McpServer.localEndpoint()))
        append('\n')
        val lan = McpServer.lanEndpoint()
        if (lan != null) {
            append(prefContext.getString(R.string.mcp_endpoint_lan, lan))
        } else {
            append(prefContext.getString(R.string.mcp_endpoint_lan_disabled))
        }
    }

}

/**
 * Local network access switch.
 *
 * @Security
 *  ! Toggling this changes the bind address, which means the port goes from
 *  ! reachable only from this device to reachable from the whole network. The
 *  ! server therefore has to be restarted, and the restart is deferred until
 *  ! after the new value has been persisted, otherwise the server would read the
 *  ! old value back.
 *  ! zh-CN: 切换该项会改变绑定地址, 意味着该端口从"仅本机可达"变为"整个网络可达".
 *  ! 因此必须重启服务; 重启被延后到新值写入之后再执行,
 *  ! 否则服务重新读取到的仍是旧值.
 */
class McpServerLanSwitchPreference : ThemeColorSwitchPreference {

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context) : super(context)

    init {
        setOnPreferenceChangeListener { _, _ ->
            Handler(Looper.getMainLooper()).post { McpServer.restartIfRunning() }
            true
        }
    }

}

/**
 * Listening port.
 * zh-CN: 监听端口.
 */
class McpServerPortPreference : MaterialPreference {

    private var dialog: MaterialDialog? = null

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context) : super(context)

    init {
        summaryProvider = SummaryProvider<McpServerPortPreference> {
            prefContext.getString(R.string.summary_mcp_server_port, Pref.mcpServerPort)
        }
    }

    override fun onClick() {
        if (dialog?.isShowing == true) return

        dialog = MaterialDialog.Builder(prefContext)
            .title(R.string.dialog_mcp_server_port_title)
            .input(null, Pref.mcpServerPort.toString()) { currentDialog, input ->
                val parsed = input.toString().trim().toIntOrNull()
                if (parsed == null || parsed !in Pref.mcpServerPortRange) {
                    Toast.makeText(
                        prefContext,
                        R.string.dialog_mcp_server_port_invalid,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@input
                }
                Pref.putInt(R.string.key_mcp_server_port, parsed)
                notifyChanged()
                currentDialog.dismiss()
                // Binding a socket is cheap, but the restart tears down and
                // rebuilds the accept loop, so it does not belong on the UI thread.
                // zh-CN: 绑定 socket 本身开销很小, 但重启会拆除并重建接收循环,
                // 因此不应放在 UI 线程上.
                Thread({ McpServer.restartIfRunning() }, "mcp-restart").start()
            }
            .negativeText(R.string.dialog_button_cancel)
            .negativeColorRes(R.color.dialog_button_default)
            .onNegative { d, _ -> d.dismiss() }
            .show()

        super.onClick()
    }

    override fun onDetached() {
        dialog?.dismiss()
        dialog = null
        super.onDetached()
    }

}

/**
 * Access token viewer and reset action.
 * zh-CN: 访问令牌的查看与重置入口.
 */
class McpServerTokenPreference : MaterialPreference {

    private var dialog: MaterialDialog? = null

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context) : super(context)

    init {
        summaryProvider = SummaryProvider<McpServerTokenPreference> {
            prefContext.getString(R.string.summary_mcp_server_token)
        }
    }

    override fun onClick() {
        if (dialog?.isShowing == true) return

        // Reading the token has the side effect of creating one when absent,
        // which is intentional: the user needs to see the value before pointing
        // a client at the server.
        // zh-CN: 读取令牌时若不存在会顺带生成, 这是有意为之:
        // 用户需要在配置客户端之前先看到该值.
        val token = McpSecurity.accessToken()
        val content = buildString {
            append(prefContext.getString(R.string.dialog_mcp_server_token_message))
            append("\n\n")
            append(token)
        }

        dialog = MaterialDialog.Builder(prefContext)
            .title(R.string.dialog_mcp_server_token_title)
            .content(content)
            .positiveText(R.string.dialog_button_dismiss)
            .positiveColorRes(R.color.dialog_button_default)
            .neutralText(R.string.dialog_mcp_server_token_reset)
            .neutralColorRes(R.color.dialog_button_hint)
            .onNeutral { _, _ -> confirmResetToken() }
            .show()

        super.onClick()
    }

    private fun confirmResetToken() {
        MaterialDialog.Builder(prefContext)
            .title(R.string.dialog_mcp_server_token_title)
            .content(R.string.dialog_mcp_server_token_reset_confirm)
            .positiveText(R.string.dialog_button_confirm)
            .positiveColorRes(R.color.dialog_button_attraction)
            .negativeText(R.string.dialog_button_cancel)
            .negativeColorRes(R.color.dialog_button_default)
            .onPositive { confirmDialog, _ ->
                McpSecurity.resetToken()
                confirmDialog.dismiss()
                dialog?.dismiss()
                dialog = null
            }
            .onNegative { confirmDialog, _ -> confirmDialog.dismiss() }
            .show()
    }

    override fun onDetached() {
        dialog?.dismiss()
        dialog = null
        super.onDetached()
    }

}
