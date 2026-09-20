package org.autojs.autojs.mcp

import android.accessibilityservice.AccessibilityService as PlatformAccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Screen capture shared by every tool that needs to look at the screen.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Note
 *  ! This deliberately uses AccessibilityService.takeScreenshot() instead of the
 *  ! MediaProjection path the rest of the app uses. MediaProjection needs a user
 *  ! consent dialog per session, which an unattended MCP client cannot satisfy,
 *  ! whereas takeScreenshot only needs the accessibility capability flag.
 *  ! The trade-off is an Android 11 (API 30) floor and a system imposed rate
 *  ! limit of roughly one shot per second.
 *  ! zh-CN: 这里刻意使用 AccessibilityService.takeScreenshot() 而非应用其他地方使用的
 *  ! MediaProjection 方案. MediaProjection 每次会话都需要用户授权弹窗, 无人值守的 MCP
 *  ! 客户端无法满足该条件; 而 takeScreenshot 只需要无障碍能力声明.
 *  ! 代价是要求 Android 11 (API 30) 及以上, 且受系统限制约每秒最多截取一次.
 */
internal object McpScreenCapture {

    private const val TIMEOUT_MS = 8_000L

    /**
     * A capture refused as "too soon" is retried once after this delay, which is
     * slightly above the system's ~1s throttle window. Sequential MCP calls such
     * as `screenshot` followed by `ocr_recognize` would otherwise fail for a
     * reason the caller cannot see from the tool surface.
     * zh-CN: 被判定"过快"的截屏会在该延迟后重试一次, 略高于系统约 1 秒的节流窗口.
     * 否则 `screenshot` 之后紧跟 `ocr_recognize` 这类连续调用会因调用方
     * 在工具层面看不到的原因而失败.
     */
    private const val RATE_LIMIT_RETRY_DELAY_MS = 1_100L

    /**
     * [describeFailure] words the rate-limit case with this fragment, so the
     * retry decision does not depend on the raw error code leaking through the
     * message. Keep it in sync with describeFailure(3).
     * zh-CN: [describeFailure] 对节流情形使用该片段措辞, 使重试判定不依赖
     * 裸错误码直接透传. 与 describeFailure(3) 保持一致.
     */
    private const val RATE_LIMIT_REASON_PART = "rate limit"

    sealed class Outcome {

        class Captured(val bitmap: Bitmap) : Outcome()

        class Failed(val reason: String) : Outcome()

    }

    /** True when this device can capture through the accessibility service. */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Captures the current screen.
     *
     * @Security
     *  ! Requires `android:canTakeScreenshot="true"` in the accessibility service
     *  ! configuration. Without it Android rejects the call, which surfaces here
     *  ! as a failure with an actionable message rather than a silent null.
     *  ! zh-CN: 需要无障碍服务配置中声明 `android:canTakeScreenshot="true"`.
     *  ! 缺少该声明时 Android 会拒绝调用, 此处将其转化为带可操作提示的失败,
     *  ! 而不是静默返回空值.
     */
    fun capture(): Outcome {
        if (!isSupported) {
            return Outcome.Failed(
                "Screen capture requires Android 11 (API 30) or newer. " +
                        "Use `dump_ui_tree` on this device instead."
            )
        }
        val service = McpUi.requireAccessibilityService()
        val first = captureOnR(service)
        if (first is Outcome.Captured) return first
        // The throttle is invisible at the tool boundary: a client that chains two
        // capture-backed calls back to back gets an error for a race it cannot
        // control. Waiting out the window once turns that into a success without
        // masking a genuinely broken capture (that path reports a different reason).
        // zh-CN: 节流在工具边界上不可见: 连续两次基于截屏的调用会因调用方无法控制的
        // 竞态而报错. 等待一个窗口期后重试一次即可变失败为成功,
        // 同时不会掩盖真正损坏的截屏(那条路径会报告其他原因).
        val failure = (first as? Outcome.Failed) ?: return first
        if (failure.reason.contains(RATE_LIMIT_REASON_PART)) {
            try {
                Thread.sleep(RATE_LIMIT_RETRY_DELAY_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return first
            }
            return captureOnR(service)
        }
        return first
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureOnR(service: PlatformAccessibilityService): Outcome {
        val latch = CountDownLatch(1)
        val bitmapRef = AtomicReference<Bitmap?>()
        val failureRef = AtomicReference<String?>()
        val executor = Executors.newSingleThreadExecutor()

        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : PlatformAccessibilityService.TakeScreenshotCallback {

                    override fun onSuccess(result: PlatformAccessibilityService.ScreenshotResult) {
                        // The hardware buffer is only valid until it is closed, so
                        // the pixels are copied into a software bitmap immediately.
                        // zh-CN: 硬件缓冲在关闭前有效, 因此立刻把像素拷贝到软件位图.
                        val buffer = result.hardwareBuffer
                        try {
                            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            if (hardware == null) {
                                failureRef.set("the system returned an unusable hardware buffer")
                            } else {
                                // @Note
                                //  ! A hardware bitmap cannot be drawn onto a software
                                //  ! Canvas -- that is rejected outright with
                                //  ! "Software rendering doesn't support hardware
                                //  ! bitmaps". `copy` performs the GPU readback
                                //  ! instead and is the supported way to reach the
                                //  ! pixels. The buffer must stay open until the copy
                                //  ! has finished, which is why it is still closed in
                                //  ! the `finally` block below.
                                //  ! zh-CN: 硬件位图无法绘制到软件 Canvas 上, 会直接抛出
                                //  ! "Software rendering doesn't support hardware bitmaps".
                                //  ! 这里改用 `copy` 完成 GPU 回读, 这也是访问其像素的受支持方式.
                                //  ! 缓冲必须保持打开直到拷贝完成, 因此仍在下面的 `finally` 中关闭.
                                val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
                                if (software == null) {
                                    failureRef.set("copying the hardware bitmap returned null")
                                } else {
                                    bitmapRef.set(software)
                                }
                                runCatching { hardware.recycle() }
                            }
                        } catch (e: Throwable) {
                            failureRef.set("converting the screenshot failed: ${e.message ?: e::class.java.simpleName}")
                        } finally {
                            runCatching { buffer.close() }
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        failureRef.set(describeFailure(errorCode))
                        latch.countDown()
                    }

                },
            )
        } catch (e: Throwable) {
            executor.shutdown()
            return Outcome.Failed("takeScreenshot() threw ${e::class.java.simpleName}: ${e.message ?: "no message"}")
        }

        try {
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return Outcome.Failed("no callback arrived within ${TIMEOUT_MS}ms")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Outcome.Failed("the capture was interrupted")
        } finally {
            executor.shutdown()
        }

        val bitmap = bitmapRef.get()
            ?: return Outcome.Failed(failureRef.get() ?: "the capture returned no image")
        return Outcome.Captured(bitmap)
    }

    /**
     * Android reports take-screenshot failures as bare integers. The values mirror
     * `AccessibilityService.ERROR_TAKE_SCREENSHOT_*`, spelled out as literals so
     * they can be referenced below API 30.
     * zh-CN: Android 以裸整数报告截图失败. 取值对应
     * `AccessibilityService.ERROR_TAKE_SCREENSHOT_*`, 此处写成字面量以便在 API 30 以下引用.
     */
    private fun describeFailure(errorCode: Int): String = when (errorCode) {
        1 -> "the system reported an internal error"
        2 -> "the accessibility service is not allowed to take screenshots"
        3 -> "another screenshot was requested too soon; Android rate limits this to about one per second"
        4 -> "the requested display is invalid"
        else -> "unknown error code $errorCode"
    }

    /**
     * Crops [bitmap] to `[left, top, right, bottom]` in screen pixels.
     * Never recycles the source, so callers stay in control of its lifetime.
     * zh-CN: 按屏幕像素 `[left, top, right, bottom]` 裁剪 [bitmap].
     * 不会回收源位图, 其生命周期仍由调用方掌握.
     */
    fun crop(bitmap: Bitmap, region: List<Int>): Outcome {
        if (region.size != 4) {
            return Outcome.Failed("`region` must contain exactly 4 integers: [left, top, right, bottom]")
        }
        val left = region[0].coerceAtLeast(0)
        val top = region[1].coerceAtLeast(0)
        val right = region[2].coerceAtMost(bitmap.width)
        val bottom = region[3].coerceAtMost(bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) {
            return Outcome.Failed(
                "`region` $region is empty once clamped to the ${bitmap.width}x${bitmap.height} screen"
            )
        }
        return runCatching { Outcome.Captured(Bitmap.createBitmap(bitmap, left, top, width, height)) }
            .getOrElse { Outcome.Failed("cropping failed: ${it.message ?: it::class.java.simpleName}") }
    }

    fun downscaleIfNeeded(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (maxWidth <= 0 || bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, maxWidth, targetHeight, true)
    }

    /** @return base64 text, or null when the encoder refused the bitmap. */
    fun encodeBase64(bitmap: Bitmap, format: String, quality: Int): String? {
        val stream = ByteArrayOutputStream()
        val compressFormat = if (format == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val ok = bitmap.compress(compressFormat, quality, stream)
        if (!ok) return null
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

}
