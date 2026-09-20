package org.autojs.autojs.mcp.tools

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.autojs.autojs.mcp.McpArgumentException
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.mcp.McpUi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Asks the person holding the device a question, and waits for their answer.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Why
 *  ! Every other tool acts on the device as if the person next to it did not
 *  ! exist. Some flows genuinely need them: "delete the whole folder?" and
 *  ! "which of these two accounts?" are the user's call, not the model's. This
 *  ! tool closes that loop -- the model asks, the person taps, the model
 *  ! continues with an actual answer instead of an assumption.
 *  ! zh-CN: 其余工具在操作设备时都当作设备旁没有人存在. 但有些流程确实需要那个人:
 *  ! "整个目录都删吗?", "这两个账号用哪个?" 是用户的决定, 不是模型的.
 *  ! 本工具闭合这个环 —— 模型提问, 人点按, 模型带着真实答案继续,
 *  ! 而不是带着一个假设继续.
 *
 * @ImplementationNote
 *  ! The dialog is drawn through the accessibility service's
 *  ! `TYPE_ACCESSIBILITY_OVERLAY` window type, which needs no floating-window
 *  ! permission and no activity: it can be shown while any app is in the
 *  ! foreground. Views must be built and touched on the main thread, so the
 *  ! whole show/teardown dance is posted there while the MCP request thread
 *  ! waits on a latch -- the same suspend-and-timeout shape the other blocking
 *  ! tools use.
 *  ! zh-CN: 对话框通过无障碍服务的 `TYPE_ACCESSIBILITY_OVERLAY` 窗口类型绘制,
 *  ! 既不需要悬浮窗权限也不需要 Activity: 任何应用在前台时都能显示.
 *  ! 视图的构建与触碰必须在主线程完成, 因此整个显示与拆除的流程都被投递到主线程,
 *  ! 而 MCP 请求线程在闩锁上等待 —— 与其他阻塞型工具相同的"挂起加超时"形状.
 */
internal object McpUserDialogTools {

    val tools: List<McpTool> = listOf(userDialogTool())

    private const val DEFAULT_TIMEOUT_MS = 120_000

    private const val MAX_TIMEOUT_MS = 600_000

    private const val MAX_TEXT_CHARS = 2_000

    /**
     * One dialog at a time. Two overlapping questions are a race for the same
     * pair of eyeballs, and the second answer would land in whichever dialog
     * happened to be on top -- so the second request is refused instead.
     * zh-CN: 同一时间只允许一个对话框. 两个重叠的问题是在争夺同一双眼睛,
     * 第二个答案会落进碰巧在上层的那个对话框里 —— 因此直接拒绝第二个请求.
     */
    private val dialogShowing = AtomicBoolean(false)

    private fun userDialogTool(): McpTool = McpTool(
        name = "user_dialog",
        title = "Ask the device's user a question",
        description = buildString {
            append("Shows a dialog on the device screen and waits for the person there to tap an answer. ")
            append("Use it when a decision genuinely belongs to the human: confirming an irreversible action, ")
            append("picking between options, asking for a value only they know. ")
            append("For anything the model can decide itself, deciding it is faster and kinder than interrupting ")
            append("someone. The result is `confirm`, `cancel`, or `timeout` when nobody answered in time -- ")
            append("a timeout means the person may not be there, so do not keep retrying.")
        },
        risk = McpToolRisk.SENSITIVE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "title" to McpSchema.string("The question, kept short: it is a heading, not a paragraph."),
                "text" to McpSchema.string("Optional longer explanation shown under the title."),
                "confirmText" to McpSchema.string("Label of the confirming button.", null, "OK"),
                "cancelText" to McpSchema.string(
                    "Label of the cancelling button. Omit it to show only the confirm button.",
                ),
                "timeoutMs" to McpSchema.integer(
                    "Give up and report `timeout` after this long, so a request cannot hang forever on an "
                            + "absent person.",
                    DEFAULT_TIMEOUT_MS, 1_000, MAX_TIMEOUT_MS,
                ),
            ),
            required = listOf("title"),
        ),
    ) { args -> invokeUserDialog(args) }

    private fun invokeUserDialog(args: McpArgs): McpToolResult {
        val title = args.requireString("title")
        if (title.length > 200) {
            throw McpArgumentException(
                "`title` is ${title.length} characters; a heading of at most 200 is readable on a phone."
            )
        }
        val text = args.optString("text")?.takeIf { it.isNotBlank() }
        val confirmText = args.optString("confirmText")?.takeIf { it.isNotBlank() } ?: "OK"
        val cancelText = args.optString("cancelText")?.takeIf { it.isNotBlank() }
        val timeoutMs = args.optInt("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(1_000, MAX_TIMEOUT_MS)

        if (!dialogShowing.compareAndSet(false, true)) {
            throw McpArgumentException(
                "Another dialog is already on screen. Wait for it to finish before asking a second question."
            )
        }

        // The guard is the first service use: the overlay window type exists only
        // on a live accessibility service, and the check belongs before any view
        // is built for the same reason every other tool guards after its argument
        // checks -- a typo should not be reported as a missing service.
        // zh-CN: 守卫是首次对服务的使用: 覆盖窗口类型只在无障碍服务存活时存在,
        // 且检查放在任何视图构建之前, 理由与其他工具"参数检查之后守卫"的约定一致 ——
        // 不应把一个拼写错误报告成服务缺失.
        val service = McpUi.requireAccessibilityService()
        val windowManager = service.getSystemService(android.content.Context.WINDOW_SERVICE)
            as WindowManager

        val answer = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        var view: LinearLayout? = null

        McpUi.mainHandler.post {
            try {
                view = buildDialogView(service, title, text, confirmText, cancelText) { choice ->
                    answer.compareAndSet(null, choice)
                    latch.countDown()
                }
                windowManager.addView(view, overlayLayoutParams())
            } catch (e: Exception) {
                // A failed addView must not leave the request hanging until the
                // timeout: release the latch so the caller learns immediately.
                // zh-CN: addView 失败不能让请求一直挂到超时:
                // 释放闩锁, 让调用方立即得知失败.
                answer.compareAndSet(null, "error: ${e.message ?: e::class.java.simpleName}")
                latch.countDown()
            }
        }

        try {
            latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            val toRemove = view
            if (toRemove != null) {
                McpUi.mainHandler.post {
                    try {
                        windowManager.removeView(toRemove)
                    } catch (_: Exception) {
                        // The view may never have been attached or may already
                        // be gone; teardown failure must not mask the answer.
                        // zh-CN: 视图可能从未附加成功或已被移除;
                        // 拆除失败不能掩盖真正的答案.
                    }
                }
            }
            dialogShowing.set(false)
        }

        val result = answer.get() ?: "timeout"
        if (result.startsWith("error:")) {
            return McpToolResult.error("Showing the dialog failed: ${result.removePrefix("error:")}")
        }

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("result", result)
            addProperty("timeoutMs", timeoutMs)
            if (result == "timeout") {
                addProperty(
                    "hint",
                    "Nobody answered within ${timeoutMs}ms. The person may be away -- do not loop on this tool; " +
                            "act on the safe default or ask again later in plain text.",
                )
            }
        })
    }

    private fun overlayLayoutParams() = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        format = PixelFormat.TRANSLUCENT
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.CENTER
    }

    /**
     * Builds the whole dialog in code rather than in a layout resource, so the
     * tool carries no XML and cannot drift from its own description.
     * zh-CN: 整个对话框用代码构建而非布局资源, 这样工具不携带 XML,
     * 也不会与自身描述脱节.
     */
    private fun buildDialogView(
        context: android.content.Context,
        title: String,
        text: String?,
        confirmText: String,
        cancelText: String?,
        onAnswer: (String) -> Unit,
    ): LinearLayout {
        fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

        val card = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = dp(16).toFloat()
        }
        // A plain white card with dark text reads on both light and dark screens,
        // which matters because the overlay is shown over whatever is behind it.
        // zh-CN: 白色卡片配深色文字在浅色与深色屏幕上都可读,
        // 这一点很重要, 因为覆盖层会显示在任意背景之上.

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = card
        }

        column.addView(TextView(context).apply {
            this.text = title
            textSize = 17f
            setTextColor(Color.rgb(17, 17, 17))
            setTypeface(typeface, Typeface.BOLD)
        })

        if (text != null) {
            column.addView(TextView(context).apply {
                this.text = text.take(MAX_TEXT_CHARS)
                textSize = 14f
                setTextColor(Color.rgb(80, 80, 80))
                setPadding(0, dp(8), 0, 0)
            })
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }

        fun button(label: String, choice: String): Button = Button(context).apply {
            this.text = label
            isAllCaps = false
            setOnClickListener {
                it.isEnabled = false
                onAnswer(choice)
            }
        }

        // The cancel button is laid out first so the confirm sits in the bottom
        // right, the position thumbs reach most naturally.
        // zh-CN: 取消按钮排在前面, 让确认按钮落在右下角 —— 拇指最自然的位置.
        cancelText?.let { buttonRow.addView(button(it, "cancel")) }
        buttonRow.addView(button(confirmText, "confirm"))

        column.addView(buttonRow)
        return column
    }

}
