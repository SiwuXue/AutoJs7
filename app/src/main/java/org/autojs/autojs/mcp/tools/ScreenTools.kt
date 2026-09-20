package org.autojs.autojs.mcp.tools

import android.util.Base64
import com.google.gson.JsonArray
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpScreenCapture
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.mcp.McpUi

/**
 * Screenshot tool.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Note
 *  ! The capture itself lives in [McpScreenCapture] because `ocr_recognize` needs
 *  ! exactly the same image. Duplicating the take-screenshot plumbing in two
 *  ! places would mean two places to fix when a device misbehaves.
 *  ! zh-CN: 采集逻辑放在 [McpScreenCapture] 中, 因为 `ocr_recognize` 需要完全相同的图像.
 *  ! 若在两处重复截图管线, 将来设备出问题就得改两个地方.
 */
internal object McpScreenTools {

    val tools: List<McpTool> = listOf(screenshotTool())

    private fun screenshotTool(): McpTool = McpTool(
        name = "screenshot",
        title = "Take a screenshot",
        description = buildString {
            append("Captures the screen and returns it as an image. ")
            append("Use it when the view hierarchy is unhelpful, for example with a game, a canvas, ")
            append("a video surface or a rendered chart. ")
            append("For ordinary widget based screens prefer `dump_ui_tree`, which is far cheaper and gives ")
            append("clickable targets directly. Use `region` to send just the part that matters. ")
            append("Android throttles screenshots to about one per second.")
        },
        risk = McpToolRisk.SAFE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "format" to McpSchema.string(
                    "Image encoding.",
                    listOf("png", "jpeg"),
                    "png",
                ),
                "quality" to McpSchema.integer(
                    "JPEG quality, ignored for PNG.",
                    80, 1, 100,
                ),
                "maxWidth" to McpSchema.integer(
                    "Downscale the image so its width does not exceed this value. 0 keeps the original size.",
                    0, 0, 4096,
                ),
                "region" to McpSchema.arrayOf(
                    "Capture only this screen rectangle as [left, top, right, bottom]. " +
                            "Cuts both the token cost and the encoding time.",
                    McpSchema.integer("Coordinate in screen pixels."),
                ),
            ),
        ),
    ) { args -> invokeScreenshot(args) }

    private fun invokeScreenshot(args: McpArgs): McpToolResult {
        val format = args.optString("format", "png")?.lowercase() ?: "png"
        if (format != "png" && format != "jpeg") {
            return McpToolResult.error("Unsupported `format` `$format`. Use `png` or `jpeg`.")
        }
        val quality = args.optInt("quality", 80).coerceIn(1, 100)
        val maxWidth = args.optInt("maxWidth", 0).coerceIn(0, 4096)
        val region = args.optIntList("region")

        // Guard after the request checks, before the first service call.
        // zh-CN: 守卫放在请求检查之后, 首次调用服务之前.
        McpUi.requireAccessibilityService()

        val screen = when (val outcome = McpScreenCapture.capture()) {
            is McpScreenCapture.Outcome.Failed -> return McpToolResult.error(
                "Screenshot failed: ${outcome.reason}. " +
                        "Make sure the AutoJs6 accessibility service is connected and that it declares " +
                        "`android:canTakeScreenshot=\"true\"`."
            )
            is McpScreenCapture.Outcome.Captured -> outcome.bitmap
        }
        val screenWidth = screen.width
        val screenHeight = screen.height

        var target = screen
        if (region != null) {
            when (val cropped = McpScreenCapture.crop(screen, region)) {
                is McpScreenCapture.Outcome.Failed -> {
                    screen.recycle()
                    return McpToolResult.error("Region crop failed: ${cropped.reason}")
                }
                is McpScreenCapture.Outcome.Captured -> target = cropped.bitmap
            }
        }

        val mimeType = if (format == "png") "image/png" else "image/jpeg"
        val metadata = McpJson.obj().apply {
            addProperty("format", format)
            addProperty("screenWidth", screenWidth)
            addProperty("screenHeight", screenHeight)
            if (region != null) {
                add("region", JsonArray().apply { region.forEach { add(it) } })
            }
        }

        try {
            val scaled = McpScreenCapture.downscaleIfNeeded(target, maxWidth)
            try {
                val encoded = McpScreenCapture.encodeBase64(scaled, format, quality)
                    ?: return McpToolResult.error("Failed to encode the captured bitmap as $format.")
                metadata.addProperty("width", scaled.width)
                metadata.addProperty("height", scaled.height)
                metadata.addProperty("bytes", Base64.decode(encoded, Base64.NO_WRAP).size)
                return McpToolResult.image(encoded, mimeType, McpJson.gson.toJson(metadata))
            } finally {
                if (scaled !== target) scaled.recycle()
            }
        } finally {
            if (target !== screen) target.recycle()
            screen.recycle()
        }
    }

}
