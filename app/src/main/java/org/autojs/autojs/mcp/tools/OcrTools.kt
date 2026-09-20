package org.autojs.autojs.mcp.tools

import android.graphics.Bitmap
import com.benjaminwan.ocrlibrary.TextBlock
import com.google.gson.JsonArray
import org.autojs.autojs.AutoJs
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpScreenCapture
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.mcp.McpUi
import kotlin.math.roundToInt

/**
 * Optical character recognition on the current screen.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Why
 *  ! Reaching for OCR when a view tree exists is usually a mistake -- those tools
 *  ! cannot see the text sitting on a canvas, a video surface or a game. But for
 *  ! exactly those cases it is the only way in, and it also gives the model a way
 *  ! to read text that the tree exposes only as an opaque glyph run.
 *  ! zh-CN: 在有视图树可用的场景下动用 OCR 通常是个错误 —— 这类工具看不见绘制在
 *  ! 画布, 视频层或游戏里的文字. 但恰恰在这些场景里它是唯一的入口,
 *  ! 同时也能让模型读到视图树只以不透明字形暴露的文本.
 */
internal object McpOcrTools {

    val tools: List<McpTool> = listOf(ocrTool())

    private fun ocrTool(): McpTool = McpTool(
        name = "ocr_recognize",
        title = "Recognize text on screen",
        description = buildString {
            append("Captures the screen and returns the text the recognizer can find, with a bounding box per line. ")
            append("Results are merged into reading order, so `text` is a convenient whole-screen transcript while ")
            append("`blocks` keeps the geometry for tapping. ")
            append("Reach for this only when `dump_ui_tree` cannot help: games, canvases, video surfaces and images. ")
            append("The first call initialises the recognition models and can take a few seconds.")
        },
        risk = McpToolRisk.SAFE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "region" to McpSchema.arrayOf(
                    "Restrict recognition to a screen rectangle as [left, top, right, bottom]. " +
                            "Faster and more accurate than recognizing the whole screen. " +
                            "Returned bounds are always translated back to screen coordinates.",
                    McpSchema.integer("Coordinate in screen pixels."),
                ),
                "minConfidencePercent" to McpSchema.integer(
                    "Drop results below this confidence, as a percentage.",
                    0, 0, 100,
                ),
                "maxSideLen" to McpSchema.integer(
                    "Longest side the image is resized to before recognition. Lower is faster and suits small text; " +
                            "raise it for dense screens.",
                    1024, 64, 4096,
                ),
                "includeText" to McpSchema.boolean(
                    "Include a `text` field joining every line in reading order.",
                    true,
                ),
            ),
        ),
    ) { args -> invokeOcr(args) }

    private fun invokeOcr(args: McpArgs): McpToolResult {
        val region = args.optIntList("region")
        val minConfidencePercent = args.optInt("minConfidencePercent", 0).coerceIn(0, 100)
        val maxSideLen = args.optInt("maxSideLen", 1024).coerceIn(64, 4096)
        val includeText = args.optBoolean("includeText", true)

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

        // Read the geometry before the bitmap is recycled at the end of the
        // block below, so the reported size never depends on recycled state.
        // zh-CN: 在下方代码块结束时位图会被回收, 因此提前读取尺寸,
        // 避免上报的尺寸依赖于已回收对象的状态.
        val screenWidth = screen.width
        val screenHeight = screen.height

        var target: Bitmap = screen
        var originX = 0
        var originY = 0

        if (region != null) {
            when (val cropped = McpScreenCapture.crop(screen, region)) {
                is McpScreenCapture.Outcome.Failed -> {
                    screen.recycle()
                    return McpToolResult.error("Region crop failed: ${cropped.reason}")
                }
                is McpScreenCapture.Outcome.Captured -> {
                    target = cropped.bitmap
                    originX = region[0].coerceAtLeast(0)
                    originY = region[1].coerceAtLeast(0)
                }
            }
        }

        val blocks = try {
            recognize(target, maxSideLen)
        } catch (e: Throwable) {
            return McpToolResult.error(
                "Text recognition failed: ${e::class.java.simpleName}: ${e.message ?: "no message"}"
            )
        } finally {
            if (target !== screen) target.recycle()
            screen.recycle()
        }

        val threshold = minConfidencePercent / 100f
        val kept = blocks.filter { it.boxScore >= threshold }

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("count", kept.size)
            addProperty("screenWidth", screenWidth)
            addProperty("screenHeight", screenHeight)
            if (region != null) {
                add("region", JsonArray().apply { region.forEach { add(it) } })
            }
            if (includeText) {
                // The engine already returns blocks in reading order, so a plain
                // join reproduces the visible layout closely enough.
                // zh-CN: 引擎已按阅读顺序返回文本块, 因此直接拼接即可较贴近可见排版.
                addProperty("text", kept.joinToString("\n") { it.text })
            }
            if (kept.isEmpty()) {
                addProperty(
                    "hint",
                    "Nothing was recognized. Try `region` to zoom into the area of interest, or raise `maxSideLen`."
                )
            }
            add("blocks", JsonArray().apply {
                kept.forEach { block ->
                    add(McpJson.obj().apply {
                        addProperty("text", block.text)
                        addProperty("confidence", (block.boxScore * 100).roundToInt() / 100.0)
                        // boxPoint is ordered top-left, top-right, bottom-right, bottom-left.
                        // zh-CN: boxPoint 的顺序为 左上, 右上, 右下, 左下.
                        val topLeft = block.boxPoint.firstOrNull()
                        val bottomRight = block.boxPoint.getOrNull(2)
                        if (topLeft != null && bottomRight != null) {
                            val left = topLeft.x + originX
                            val top = topLeft.y + originY
                            val right = bottomRight.x + originX
                            val bottom = bottomRight.y + originY
                            add("bounds", JsonArray().apply {
                                add(left); add(top); add(right); add(bottom)
                            })
                            add("center", JsonArray().apply {
                                add((left + right) / 2); add((top + bottom) / 2)
                            })
                        }
                    })
                }
            })
        })
    }

    /**
     * Runs the bundled RapidOCR engine over the bitmap.
     *
     * @Note
     *  ! The thresholds mirror what the script facing `ocr` module uses, so a
     *  ! result obtained over MCP matches what the same script would see.
     *  ! zh-CN: 阈值与脚本可见的 `ocr` 模块保持一致,
     *  ! 使通过 MCP 得到的结果与同一脚本所见一致.
     */
    private fun recognize(bitmap: Bitmap, maxSideLen: Int): List<TextBlock> {
        val output = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return try {
            AutoJs.instance.rapidOcrEngine.detect(
                input = bitmap,
                output = output,
                maxSideLen = maxSideLen,
                padding = 50,
                boxScoreThresh = 0.5f,
                boxThresh = 0.3f,
                unClipRatio = 2.0f,
                doAngle = false,
                mostAngle = false,
            ).textBlocks.toList()
        } finally {
            output.recycle()
        }
    }

}
