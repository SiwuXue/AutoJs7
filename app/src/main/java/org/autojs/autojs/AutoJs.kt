package org.autojs.autojs

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.autojs.autojs.core.accessibility.AccessibilityTool
import org.autojs.autojs.core.accessibility.AccessibilityWatchdog
import org.autojs.autojs.core.accessibility.Capture
import org.autojs.autojs.core.accessibility.LayoutInspector.CaptureAvailableListener
import org.autojs.autojs.core.console.GlobalConsole
import org.autojs.autojs.execution.ScriptExecutionGlobalListener
import org.autojs.autojs.external.fileprovider.AppFileProvider
import org.autojs.autojs.ipc.LayoutInspectEvent
import org.autojs.autojs.ipc.LayoutInspectEventBus
import org.autojs.autojs.mcp.McpLogBuffer
import org.autojs.autojs.mcp.McpServer
import org.autojs.autojs.mcp.McpWatchdog
import org.autojs.autojs.pluginclient.DevPluginService
import org.autojs.autojs.runtime.ScriptRuntime
import org.autojs.autojs.runtime.api.AppUtils
import org.autojs.autojs.runtime.api.AppUtils.Companion.ActivityShortForm
import org.autojs.autojs.runtime.api.AppUtils.Companion.BroadcastShortForm
import org.autojs.autojs.ui.floating.FloatyWindowManger
import org.autojs.autojs.ui.floating.FullScreenFloatyWindow
import org.autojs.autojs.ui.floating.layoutinspector.LayoutBoundsFloatyWindow
import org.autojs.autojs.ui.floating.layoutinspector.LayoutHierarchyFloatyWindow
import org.autojs.autojs.util.RhinoUtils.isBackgroundThread
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.autojs.autojs.inrt.autojs.AutoJs as AutoJsInrt

/**
 * Created by Stardust on Apr 2, 2017.
 * Transformed by SuperMonster003 on Oct 10, 2022.
 * Modified by LZX284 (https://github.com/LZX284) as of Sep 30, 2023.
 * Modified by SuperMonster003 as of Jan 17, 2026.
 */
open class AutoJs(appContext: Application) : AbstractAutoJs(appContext) {

    internal val devPluginService by lazy {
        DevPluginService(application)
    }

    // @Thank to Zen2H
    // Use bounded queue to prevent log flooding from blocking the whole channel.
    // zh-CN: 使用有界队列避免日志洪泛导致整个通道被阻塞.
    private val mPrintExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2048),
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    private val mA11yTool = AccessibilityTool(appContext)

    private val mJob = Job()
    private val mScope = CoroutineScope(Dispatchers.Main.immediate + mJob)

    init {
        scriptEngineService.registerGlobalScriptExecutionListener(ScriptExecutionGlobalListener())

        // @Archived by SuperMonster003 on Sep 27, 2025.
        //  ! LocalBroadcastManager is deprecated.
        //  ! zh-CN: LocalBroadcastManager 已被弃用.
        //  # LocalBroadcastManager.getInstance(appContext).registerReceiver(object : BroadcastReceiver() {
        //  #     override fun onReceive(context: Context, intent: Intent) {
        //  #         ... ...
        //  #         val action = intent.action ?: return
        //  #         when {
        //  #             action.equals(LayoutBoundsFloatyWindow::class.java.name, true) -> { ... ... }
        //  #             action.equals(LayoutHierarchyFloatyWindow::class.java.name, true) -> { ... ... }
        //  #         }
        //  #         ... ...
        //  #     }
        //  # }, IntentFilter().apply {
        //  #     addAction(LayoutBoundsFloatyWindow::class.java.name)
        //  #     addAction(LayoutHierarchyFloatyWindow::class.java.name)
        //  # })

        LayoutInspectEventBus.events.onEach { event ->
            try {
                when (event) {
                    LayoutInspectEvent.ShowLayoutBounds -> {
                        mA11yTool.ensureService()
                        capture(object : LayoutInspectFloatyWindow {
                            override fun create(capture: Capture) = LayoutBoundsFloatyWindow(capture, appContext, true)
                        })
                    }
                    LayoutInspectEvent.ShowLayoutHierarchy -> {
                        mA11yTool.ensureService()
                        capture(object : LayoutInspectFloatyWindow {
                            override fun create(capture: Capture) = LayoutHierarchyFloatyWindow(capture, appContext, true)
                        })
                    }
                }
            } catch (e: Exception) {
                if (isBackgroundThread()) {
                    throw e
                }
            }
        }.launchIn(mScope)

        // @Added by fork author on Sep 16, 2026.
        //  ! Restore the opt-in MCP server if the user had left it enabled.
        //  ! Binding a listening socket can block, so it must stay off the main
        //  ! thread that is still finishing application start-up.
        //  ! zh-CN: 若用户此前保持 MCP 服务开启, 则在此恢复.
        //  ! 绑定监听 socket 可能阻塞, 因此必须避开仍在执行应用启动的主线程.
        Thread({
            McpServer.applyPreference()
            // @Added by fork author on Sep 23, 2026.
            //  ! Arm the watchdog next to the restore rather than relying on the
            //  ! service to do it: on Android 12+ a background start of a
            //  ! foreground service can be refused outright, in which case the
            //  ! service never runs and could never have armed its own guard.
            //  ! zh-CN: 在恢复之后紧接着武装看门狗, 而不是依赖服务自行完成:
            //  ! 在 Android 12+ 上后台启动前台服务可能被直接拒绝, 那样服务从未运行,
            //  ! 也就永远无法为自己装上守卫.
            if (org.autojs.autojs.core.pref.Pref.isMcpServerEnabled) {
                McpWatchdog.start()
            }
        }, "mcp-restore").apply { isDaemon = true }.start()

        // @Added by fork author on Sep 23, 2026.
        //  ! Independent of the MCP server: every UI tool needs accessibility, and
        //  ! an app update disables the service on the system's own initiative.
        //  ! zh-CN: 与 MCP 服务无关: 所有 UI 工具都依赖无障碍,
        //  ! 而应用更新会让系统主动禁用该服务.
        AccessibilityWatchdog.start()
    }

    private interface LayoutInspectFloatyWindow {
        fun create(capture: Capture): FullScreenFloatyWindow
    }

    private fun capture(window: LayoutInspectFloatyWindow) {
        val inspector = layoutInspector
        val listener: CaptureAvailableListener = object : CaptureAvailableListener {
            override fun onCaptureAvailable(capture: Capture, context: Context) {
                inspector.removeCaptureAvailableListener(this)
                uiHandler.post { FloatyWindowManger.addWindow(context, window.create(capture)) }
            }
        }
        inspector.addCaptureAvailableListener(listener)
        if (!inspector.captureCurrentWindow()) {
            inspector.removeCaptureAvailableListener(listener)
        }
    }

    override fun createAppUtils(context: Context) = AppUtils(context, AppFileProvider.AUTHORITY)

    override fun createGlobalConsole(): GlobalConsole {
        return object : GlobalConsole(uiHandler) {
            override fun println(level: Int, charSequence: CharSequence): String {
                return super.println(level, charSequence).also {
                    // FIXME by SuperMonster003 as of Feb 2, 2022.
                    //  ! When running in "ui" thread (ui.run() or ui.post()),
                    //  ! android.os.NetworkOnMainThreadException may happen.
                    //  ! Furthermore, dunno if a thread executor is a good idea.
                    //  ! 当在 "ui" 线程执行时 (如 ui.run() 或 ui.post()),
                    //  ! 可能会发生 android.os.NetworkOnMainThreadException 异常.
                    //  ! 而且, 我不确定使用线程执行器是否是个好主意.
                    mPrintExecutor.submit { devPluginService.print(it) }
                    // @Added by fork author on Sep 16, 2026.
                    //  ! Mirror console output for the MCP server, so an AI client can
                    //  ! pull script logs instead of polling the console view.
                    //  ! zh-CN: 为 MCP 服务镜像控制台输出,
                    //  ! 使 AI 客户端能以拉取方式获取脚本日志, 而不必轮询控制台视图.
                    McpLogBuffer.append(level, it)
                }
            }
        }
    }

    override fun createRuntime(): ScriptRuntime = super.createRuntime().apply {

        /* Activities. */

        listOf(
            ActivityShortForm.SETTINGS, ActivityShortForm.PREFERENCES, ActivityShortForm.PREF,
            ActivityShortForm.DOCUMENTATION, ActivityShortForm.DOC, ActivityShortForm.DOCS,
            ActivityShortForm.HOMEPAGE, ActivityShortForm.HOME,
            ActivityShortForm.CONSOLE, ActivityShortForm.LOG,
            ActivityShortForm.ABOUT,
            ActivityShortForm.BUILD,
        ).forEach { putProperty(it.fullName, it.classType) }

        /* Broadcasts. */

        listOf(
            BroadcastShortForm.INSPECT_LAYOUT_BOUNDS, BroadcastShortForm.LAYOUT_BOUNDS, BroadcastShortForm.BOUNDS,
            BroadcastShortForm.INSPECT_LAYOUT_HIERARCHY, BroadcastShortForm.LAYOUT_HIERARCHY, BroadcastShortForm.HIERARCHY,
        ).forEach { putProperty(it.fullName, it.className) }
    }

    fun clear() {
        mJob.cancel()
    }

    companion object {
        private var isInitialized = false
        private val TAG = AutoJs::class.java.simpleName

        @SuppressLint("StaticFieldLeak")
        @JvmStatic
        lateinit var instance: AutoJs
            private set

        @Synchronized
        @JvmStatic
        fun initInstance(application: Application) {
            Log.d(TAG, "AutoJs isInitialized: $isInitialized")
            if (!isInitialized) {
                instance = when (isInrt) {
                    true -> AutoJsInrt(application)
                    else -> AutoJs(application)
                }
                isInitialized = true
            }
        }
    }

}