package org.autojs.autojs.mcp

import org.autojs.autojs.core.accessibility.AccessibilityService
import org.autojs.autojs.core.record.Recorder
import org.autojs.autojs.core.record.accessibility.AccessibilityActionRecorder

/**
 * Owns the macro recorder used by the MCP tools.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Why
 *  ! AutoJs6 already keeps a recorder instance, but it is private to the
 *  ! application object. Rather than widening that field, this owns its own
 *  ! instance and registers it as a separate accessibility delegate. The
 *  ! lifecycle then belongs entirely to the MCP layer, and the app's own
 *  ! recorder is left untouched.
 *  ! zh-CN: AutoJs6 已有一个录制器实例, 但它是应用对象的私有字段. 与其放宽该字段的可见性,
 *  ! 这里改为自己持有一个实例并注册为独立的无障碍委托. 这样生命周期完全归 MCP 层所有,
 *  ! 应用自身的录制器则保持原封不动.
 *
 * @Security
 *  ! This captures text the user types, so it is treated as a dangerous
 *  ! capability rather than a convenience. See the tools for how that is surfaced.
 *  ! zh-CN: 它会捕获用户输入的文本, 因此按高危能力对待而非便利功能.
 *  ! 具体如何呈现见对应的工具定义.
 */
internal object McpRecorder {

    /**
     * Distinct from the app's own delegates at 100, 200 and 300. The delegate
     * map is keyed by priority, so this value must not collide.
     * zh-CN: 与应用自身的 100, 200, 300 三个委托区分开.
     * 委托表以优先级为键, 因此该值不得冲突.
     */
    private const val DELEGATE_PRIORITY = 310

    private val recorder = AccessibilityActionRecorder()

    @Volatile
    private var delegateRegistered = false

    /** Raised when a recording cannot be started. zh-CN: 无法开始录制时抛出. */
    class RecorderException(message: String) : IllegalStateException(message)

    sealed class StartOutcome {

        object Started : StartOutcome()

        /** A recording was already running, so the existing one was kept. */
        object AlreadyRecording : StartOutcome()

    }

    class StopOutcome(
        /**
         * True when this call is what ended the recording. False means the
         * recorder had already stopped on its own, which the ten minute guard
         * inside AccessibilityActionRecorder makes possible.
         * zh-CN: 本次调用是否就是结束录制的原因. 为 false 表示录制器已自行停止,
         * 这在 AccessibilityActionRecorder 内置的十分钟保护触发时会发生.
         */
        val stoppedByThisCall: Boolean,
        val script: String,
    )

    @Synchronized
    fun start(ignoreFirstAction: Boolean): StartOutcome {
        if (recorder.state == Recorder.STATE_RECORDING) {
            return StartOutcome.AlreadyRecording
        }

        ensureDelegateRegistered()

        // Must be set before start, because startImpl() builds the converter
        // that reads this flag.
        // zh-CN: 必须在 start 之前设置, 因为 startImpl() 会构建读取该标记的转换器.
        recorder.setShouldIgnoreFirstAction(ignoreFirstAction)

        return try {
            recorder.start()
            StartOutcome.Started
        } catch (e: IllegalStateException) {
            throw RecorderException(
                "The recorder refused to start: ${e.message ?: "unexpected state"}. " +
                        "Another recording may be in progress."
            )
        }
    }

    @Synchronized
    fun stop(): StopOutcome {
        val wasActive = recorder.state == Recorder.STATE_RECORDING ||
                recorder.state == Recorder.STATE_PAUSED

        if (wasActive) {
            runCatching { recorder.stop() }
        }

        // Always hand back whatever was captured, even when the recorder had
        // already stopped by itself: the script is still valuable.
        // zh-CN: 无论录制器是否已自行停止都返回已捕获的内容:
        // 这段脚本仍然是有价值的.
        return StopOutcome(
            stoppedByThisCall = wasActive,
            script = recorder.code.orEmpty(),
        )
    }

    val isRecording: Boolean
        get() = recorder.state == Recorder.STATE_RECORDING

    val state: Int
        get() = recorder.state

    fun stateName(): String = when (state) {
        Recorder.STATE_NOT_START -> "not_started"
        Recorder.STATE_RECORDING -> "recording"
        Recorder.STATE_PAUSED -> "paused"
        Recorder.STATE_STOPPED -> "stopped"
        else -> "unknown($state)"
    }

    /**
     * Registers lazily rather than at start-up. Until a recording begins the
     * delegate has nothing to do, and this keeps the MCP layer from taking a
     * delegate slot in every session.
     * zh-CN: 采用惰性注册而非启动即注册. 未开始录制时该委托无事可做,
     * 这样能避免 MCP 层在每次会话中都占用一个委托槽位.
     */
    private fun ensureDelegateRegistered() {
        if (delegateRegistered) return
        AccessibilityService.addDelegate(DELEGATE_PRIORITY, recorder)
        delegateRegistered = true
    }

}
