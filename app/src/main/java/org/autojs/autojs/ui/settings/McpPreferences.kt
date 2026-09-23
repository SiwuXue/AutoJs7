package org.autojs.autojs.ui.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.widget.Toast
import com.afollestad.materialdialogs.MaterialDialog
import org.autojs.autojs.core.pref.Pref
import org.autojs.autojs.mcp.McpSecurity
import org.autojs.autojs.mcp.McpServer
import org.autojs.autojs.mcp.McpServerService
import org.autojs.autojs.mcp.McpUi
import org.autojs.autojs.theme.preference.MaterialPreference
import org.autojs.autojs.theme.preference.ThemeColorServiceSwitchPreference
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
 *  ! `isChecked` mirrors the live socket rather than a stored boolean, so the
 *  ! screen never claims the server is up when the port was actually taken by
 *  ! another app. Informed consent is requested once, before the port first
 *  ! opens, and the long-click description remains as the permanent reference.
 *  ! zh-CN: `isChecked` 反映真实 socket 状态而非存储的布尔值,
 *  ! 因此界面不会在端口实际已被其他应用占用时仍声称服务正在运行.
 *  ! 端口首次开放前会请求一次知情同意, 长按说明则作为长期可查的依据.
 */
class McpServerSwitchPreference : ThemeColorServiceSwitchPreference {

    private var riskDialog: MaterialDialog? = null

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context) : super(context)

    init {
        summaryProvider = SummaryProvider<McpServerSwitchPreference> { describeState() }
    }

    override fun onAttached() {
        super.onAttached()
        sync()
    }

    override fun isRunning(): Boolean = McpServer.isRunning || McpServerService.isRunning(prefContext)

    /**
     * The foreground service owns the socket lifecycle, so starting the service
     * is enough: its `onCreate` brings the server up, and its `onDestroy` tears
     * it down again.
     * zh-CN: 前台服务持有 socket 生命周期, 因此只需启动服务:
     * 其 `onCreate` 会拉起服务端, `onDestroy` 会将其关闭.
     */
    override fun start(): Boolean {
        McpServerService.start(prefContext)
        return true
    }

    override fun stop(): Boolean {
        McpServerService.stop(prefContext)
        return true
    }

    override fun onClick() {
        if (requiresConsent()) {
            showRiskDialog()
            return
        }
        proceedWithToggle()
    }

    /**
     * Split out from [onClick] because the base `onClick` is protected: calling
     * it from inside a callback lambda is not guaranteed to be accessible, while
     * a private method of the same class always is.
     * zh-CN: 从 [onClick] 中拆分出来, 因为基类的 `onClick` 是 protected:
     * 在回调 lambda 中调用它无法保证可访问性, 而同类的私有方法总是可访问的.
     */
    private fun proceedWithToggle() {
        super.onClick()
    }

    override fun onDetached() {
        riskDialog?.dismiss()
        riskDialog = null
        super.onDetached()
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
                Pref.putBoolean(R.string.key_mcp_server_risk_acknowledged, true)
                riskDialog = null
                // Re-run the toggle now that consent has been recorded.
                // zh-CN: 同意已记录, 重新执行切换.
                proceedWithToggle()
            }
            .onNegative { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun describeState(): CharSequence {
        if (!isRunning()) {
            val failure = McpServer.lastFailure
            return when (failure) {
                null -> prefContext.getString(R.string.summary_mcp_server_stopped)
                else -> prefContext.getString(R.string.summary_mcp_server_failed, failure)
            }
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
