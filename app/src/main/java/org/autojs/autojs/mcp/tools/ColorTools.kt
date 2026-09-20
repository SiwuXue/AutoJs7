package org.autojs.autojs.mcp.tools

import android.graphics.Color
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.autojs.autojs.core.opencv.OpenCVHelper
import org.autojs.autojs.mcp.McpArgumentException
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpScreenCapture
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.mcp.McpUi
import org.autojs.autojs.runtime.api.Images
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar

/**
 * Pixel level colour lookup: the lighter sibling of `find_image`.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Why
 *  ! Games, canvases and video expose no view hierarchy, and a full template match
 *  ! is heavier than the question being asked. "Is this health bar still green?",
 *  ! "is the login button on screen?" are single colour questions, and AutoJs6's
 *  ! scripting answer to them -- `findColor` and `findMultiColors` -- is the one
 *  ! feature long time users reach for first. Exposing the same semantics keeps
 *  ! the vocabulary a script author already has.
 *  ! zh-CN: 游戏, 画布与视频不暴露视图层级, 而完整的模板匹配比所问的问题更重.
 *  ! "血条还是绿色吗?", "登录按钮在屏幕上吗?" 都是单色问题,
 *  ! 而 AutoJs6 脚本对此的答案 —— `findColor` 与 `findMultiColors` ——
 *  ! 正是老用户最先想到的功能. 暴露相同语义, 让脚本作者沿用已有的词汇.
 *
 * @ImplementationNote
 *  ! `ColorFinder` is deliberately not reused: it takes an `ImageWrapper`, and
 *  ! every `ImageWrapper` constructor requires a `ScriptRuntime` an MCP request
 *  ! does not have. The inRange-and-scan logic is instead carried over from
 *  ! `ColorFinder.findColorInner` verbatim, so threshold and region semantics
 *  ! stay identical to the script facing API. The Mat here comes from
 *  ! `bitmapToMat`, whose channel order is R, G, B, A -- the same order the
 *  ! `Scalar` bounds below assume.
 *  ! zh-CN: 刻意不复用 `ColorFinder`: 它接收 `ImageWrapper`,
 *  ! 而 `ImageWrapper` 的所有构造函数都需要 MCP 请求所不具备的 `ScriptRuntime`.
 *  ! 这里逐行沿用 `ColorFinder.findColorInner` 的 inRange 加扫描逻辑,
 *  ! 使 threshold 与 region 语义与脚本可见的 API 完全一致.
 *  ! 此处的 Mat 来自 `bitmapToMat`, 通道顺序为 R, G, B, A —— 与下方 `Scalar`
 *  ! 边界假定的顺序相同.
 */
internal object McpColorTools {

    val tools: List<McpTool> = listOf(
        findColorTool(), findMultiColorsTool(), getPixelColorTool(),
    )

    /** Matches the script facing default, so results agree with a user's scripts. */
    private const val DEFAULT_THRESHOLD = 4

    private const val MAX_THRESHOLD = 255

    /**
     * How many candidate pixels of the first colour get their full path checked
     * before the search gives up. A first colour that matches tens of thousands
     * of pixels (a flat background) would otherwise stall the request.
     * zh-CN: 首色候选像素在被放弃前最多检查完整路径的数量.
     * 若首色匹配了数万个像素 (一片纯色背景), 否则会把请求拖死.
     */
    private const val MAX_CANDIDATES = 2000

    private fun colorDesc() = "Color as `#RRGGBB` or `#AARRGGBB`, e.g. `#FF5722`."

    private fun thresholdDesc() = "Per channel tolerance, 0 to 255. A pixel matches when its red, green and " +
            "blue components are each within this distance of the target. The default matches the script API."

    // ---------------------------------------------------------------- find_color

    private fun findColorTool(): McpTool = McpTool(
        name = "find_color",
        title = "Find a color on screen",
        description = buildString {
            append("Captures the screen and returns the positions of pixels whose colour is within `threshold` ")
            append("of the requested one, in screen pixels. ")
            append("This is the cheap, robust alternative to `find_image` for flat coloured UI: status lights, ")
            append("health bars, toggles, solid buttons. ")
            append("Pick a pixel colour from a `screenshot` first; screen colours rarely match a design file ")
            append("exactly, so a small `threshold` (8 to 32) is usually wiser than 0. ")
            append("Android rate limits screen capture to about one per second, so space out repeated calls.")
        },
        risk = McpToolRisk.SAFE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "color" to McpSchema.string(colorDesc()),
                "threshold" to McpSchema.integer(thresholdDesc(), DEFAULT_THRESHOLD, 0, MAX_THRESHOLD),
                "region" to McpSchema.arrayOf(
                    "Restrict the search to a screen rectangle as [left, top, right, bottom]. " +
                            "Faster, and prevents a common colour elsewhere on screen from dominating the result.",
                    McpSchema.integer("Coordinate in screen pixels."),
                ),
                "limit" to McpSchema.integer(
                    "Maximum points to return, scanning top to bottom. Keep this small; a common colour " +
                            "can match thousands of pixels.", 1, 1, 100,
                ),
            ),
            required = listOf("color"),
        ),
    ) { args -> invokeFindColor(args) }

    private fun invokeFindColor(args: McpArgs): McpToolResult {
        val color = parseColor(args.requireString("color"), "color")
        val threshold = args.optInt("threshold", DEFAULT_THRESHOLD).coerceIn(0, MAX_THRESHOLD)
        val region = args.optIntList("region")
        val limit = args.optInt("limit", 1).coerceIn(1, 100)

        val screen = captureAsMat()
        try {
            val (points, total) = scanColor(screen, color, threshold, region, limit)
            return McpToolResult.json(resultBody("find_color", color, threshold, region, total) {
                add("points", JsonArray().apply {
                    points.take(limit).forEach { (x, y) ->
                        add(JsonArray().apply { add(x); add(y) })
                    }
                })
            })
        } finally {
            OpenCVHelper.release(screen)
        }
    }

    // --------------------------------------------------------- find_multi_colors

    private fun findMultiColorsTool(): McpTool = McpTool(
        name = "find_multi_colors",
        title = "Find a color pattern on screen",
        description = buildString {
            append("Captures the screen and locates the origin of a colour pattern: one anchor colour plus a ")
            append("list of relative offsets that must each show their own colour. This is AutoJs6's signature ")
            append("`findMultiColors`, and it is the most reliable way to locate a UI element whose layout is ")
            append("known but whose template image you do not have: the anchor pins the position and the path ")
            append("verifies the shape, which survives minor colour jitter far better than template matching. ")
            append("Offsets are relative to the anchor in screen pixels, e.g. ")
            append("[[0, 12, \"#FFFFFF\"], [24, 0, \"#111111\"]]. ")
            append("Pick colours from a `screenshot`; a small `threshold` (8 to 32) absorbs compression noise. ")
            append("Android rate limits screen capture to about one per second.")
        },
        risk = McpToolRisk.SAFE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "firstColor" to McpSchema.string("Anchor color. ${colorDesc()}"),
                "paths" to McpSchema.arrayOf(
                    "Offsets to verify, each [dx, dy, color]. dx and dy are relative to the anchor point; " +
                            "at least one offset is required, and more make the match stricter.",
                    McpSchema.integer("dx, dy in screen pixels, or a colour channel of the offset colour."),
                ),
                "threshold" to McpSchema.integer(thresholdDesc(), DEFAULT_THRESHOLD, 0, MAX_THRESHOLD),
                "region" to McpSchema.arrayOf(
                    "Restrict the anchor search to a screen rectangle as [left, top, right, bottom]. " +
                            "The path is still checked in full screen coordinates.",
                    McpSchema.integer("Coordinate in screen pixels."),
                ),
                "limit" to McpSchema.integer(
                    "Maximum anchor points to return.", 1, 1, 50,
                ),
            ),
            required = listOf("firstColor", "paths"),
        ),
    ) { args -> invokeFindMultiColors(args) }

    private fun invokeFindMultiColors(args: McpArgs): McpToolResult {
        val firstColor = parseColor(args.requireString("firstColor"), "firstColor")
        val paths = parsePaths(args)
        val threshold = args.optInt("threshold", DEFAULT_THRESHOLD).coerceIn(0, MAX_THRESHOLD)
        val region = args.optIntList("region")
        val limit = args.optInt("limit", 1).coerceIn(1, 50)

        val screen = captureAsMat()
        try {
            val (candidates, total) = scanColor(screen, firstColor, threshold, region, MAX_CANDIDATES)
            val hits = candidates.asSequence()
                .filter { checksPath(screen, it, threshold, paths) }
                .take(limit)
                .toList()

            return McpToolResult.json(resultBody("find_multi_colors", firstColor, threshold, region, total) {
                addProperty("candidatesScanned", minOf(candidates.size, MAX_CANDIDATES))
                add("points", JsonArray().apply {
                    hits.forEach { (x, y) ->
                        add(JsonArray().apply { add(x); add(y) })
                    }
                })
                if (hits.isEmpty() && total > MAX_CANDIDATES) {
                    addProperty(
                        "hint",
                        "The anchor colour matches $total pixels, far too common to test every one; only the " +
                                "first $MAX_CANDIDATES were checked. Choose an anchor colour that is rare on " +
                                "screen -- a distinctive border or icon pixel -- and let `paths` carry the rest.",
                    )
                }
            })
        } finally {
            OpenCVHelper.release(screen)
        }
    }

    // ---------------------------------------------------------- get_pixel_color

    private fun getPixelColorTool(): McpTool = McpTool(
        name = "get_pixel_color",
        title = "Read one pixel's color",
        description = buildString {
            append("Captures the screen and returns the colour of the pixel at [x, y], both as a ")
            append("`#RRGGBB` string and as separate components. ")
            append("Use it to sample a colour before a `find_color` call, or to check a single known pixel -- ")
            append("the cheapest possible screen question. ")
            append("Android rate limits screen capture to about one per second.")
        },
        risk = McpToolRisk.SAFE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "x" to McpSchema.integer("Horizontal coordinate in screen pixels."),
                "y" to McpSchema.integer("Vertical coordinate in screen pixels."),
            ),
            required = listOf("x", "y"),
        ),
    ) { args -> invokeGetPixelColor(args) }

    private fun invokeGetPixelColor(args: McpArgs): McpToolResult {
        val x = args.optInt("x", Int.MIN_VALUE)
        val y = args.optInt("y", Int.MIN_VALUE)
        if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) {
            throw McpArgumentException("Both `x` and `y` are required, in screen pixels.")
        }

        val screen = captureAsMat()
        try {
            if (x < 0 || y < 0 || x >= screen.cols() || y >= screen.rows()) {
                throw McpArgumentException(
                    "`x`/`y` ($x, $y) is outside the ${screen.cols()}x${screen.rows()} screen."
                )
            }
            val channels = screen.get(y, x)
                ?: throw IllegalStateException("Reading pixel ($x, $y) returned no data.")
            val r = channels[0].toInt().and(0xFF)
            val g = channels[1].toInt().and(0xFF)
            val b = channels[2].toInt().and(0xFF)
            val a = channels.getOrNull(3)?.toInt()?.and(0xFF) ?: 255

            return McpToolResult.json(McpJson.obj().apply {
                addProperty("x", x)
                addProperty("y", y)
                addProperty("color", String.format("#%02X%02X%02X", r, g, b))
                addProperty("alpha", a)
                addProperty("argb", String.format("#%02X%02X%02X%02X", a, r, g, b))
                addProperty("screenWidth", screen.cols())
                addProperty("screenHeight", screen.rows())
            })
        } finally {
            OpenCVHelper.release(screen)
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Captures the screen and converts it to an RGBA Mat the caller must release.
     * zh-CN: 截取屏幕并转换为 RGBA Mat, 调用方负责释放.
     */
    private fun captureAsMat(): Mat {
        // Guard after the request checks, before the first service call -- see
        // the contract on `McpUi.requireAccessibilityService`.
        // zh-CN: 守卫放在请求检查之后, 首次调用服务之前 ——
        // 见 `McpUi.requireAccessibilityService` 上的约定.
        McpUi.requireAccessibilityService()
        Images.initOpenCvIfNeeded()

        val bitmap = when (val outcome = McpScreenCapture.capture()) {
            is McpScreenCapture.Outcome.Failed -> throw IllegalStateException(
                "Screenshot failed: ${outcome.reason}."
            )
            is McpScreenCapture.Outcome.Captured -> outcome.bitmap
        }
        try {
            return Mat().also { Utils.bitmapToMat(bitmap, it) }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Finds every pixel whose colour lies within [threshold] of [color], carrying
     * over `ColorFinder.findColorInner` semantics: an inRange pass with per channel
     * bounds, then the surviving positions read back in scan order.
     *
     * @return the points in full screen coordinates (region offset added back)
     * plus the total number of matching pixels, which the caller uses to warn
     * about an over-common colour.
     * zh-CN: 找出颜色与 [color] 之差在 [threshold] 内的所有像素, 语义沿用
     * `ColorFinder.findColorInner`: 先做逐通道边界的 inRange, 再按扫描顺序读回位置.
     *
     * @return 全屏坐标下的点集 (region 偏移已加回), 以及匹配像素总数,
     * 供调用方就"颜色过于常见"给出提示.
     */
    private fun scanColor(
        screen: Mat,
        color: Int,
        threshold: Int,
        region: List<Int>?,
        cap: Int,
    ): Pair<List<Pair<Int, Int>>, Int> {
        val regionRect = region?.let { cropRect(it, screen.cols(), screen.rows()) }
        val search = if (regionRect != null) Mat(screen, regionRect) else screen
        val mask = Mat()
        try {
            val lower = Scalar(
                (Color.red(color) - threshold).toDouble(),
                (Color.green(color) - threshold).toDouble(),
                (Color.blue(color) - threshold).toDouble(),
                255.0,
            )
            val upper = Scalar(
                (Color.red(color) + threshold).toDouble(),
                (Color.green(color) + threshold).toDouble(),
                (Color.blue(color) + threshold).toDouble(),
                255.0,
            )
            Core.inRange(search, lower, upper, mask)

            val originX = regionRect?.x ?: 0
            val originY = regionRect?.y ?: 0
            val points = mutableListOf<Pair<Int, Int>>()
            var total = 0

            // Rows are scanned directly instead of findNonZero + MatOfPoint, so an
            // over-common colour can be capped mid scan rather than materialising
            // every match first. Screen sized images make that difference real:
            // a background colour can match millions of pixels.
            // zh-CN: 直接逐行扫描而非 findNonZero 加 MatOfPoint, 使"过于常见的颜色"
            // 可以在扫描中途封顶, 而不是先把每个匹配点都物化出来.
            // 对屏幕大小的图像, 这个差别是实打实的: 背景色可能匹配上百万个像素.
            val rowBuffer = ByteArray(mask.cols())
            for (row in 0 until mask.rows()) {
                mask.get(row, 0, rowBuffer)
                for (col in 0 until mask.cols()) {
                    if (rowBuffer[col].toInt() != 0) {
                        total++
                        if (points.size < cap) {
                            points.add(col + originX to row + originY)
                        }
                    }
                }
            }
            return points to total
        } finally {
            OpenCVHelper.release(mask)
            if (regionRect != null) OpenCVHelper.release(search)
        }
    }

    /**
     * Verifies the offset colours of a multi colour pattern against [anchor],
     * mirroring `ColorFinder.checksPath`: every offset must land inside the image
     * and match its colour within the threshold.
     * zh-CN: 按锚点校验多点图案的偏移颜色, 与 `ColorFinder.checksPath` 一致:
     * 每个偏移都必须落在图像内, 且其颜色在阈值内匹配.
     */
    private fun checksPath(
        screen: Mat,
        anchor: Pair<Int, Int>,
        threshold: Int,
        paths: List<Triple<Int, Int, Int>>,
    ): Boolean {
        for ((dx, dy, color) in paths) {
            val x = anchor.first + dx
            val y = anchor.second + dy
            if (x < 0 || y < 0 || x >= screen.cols() || y >= screen.rows()) return false
            val pixel = screen.get(y, x) ?: return false
            val dr = (pixel[0].toInt().and(0xFF)) - Color.red(color)
            val dg = (pixel[1].toInt().and(0xFF)) - Color.green(color)
            val db = (pixel[2].toInt().and(0xFF)) - Color.blue(color)
            if (dr < -threshold || dr > threshold) return false
            if (dg < -threshold || dg > threshold) return false
            if (db < -threshold || db > threshold) return false
        }
        return true
    }

    private fun parsePaths(args: McpArgs): List<Triple<Int, Int, Int>> {
        val array = args.optArray("paths")
            ?: throw McpArgumentException("`paths` is required: an array of [dx, dy, color] triples.")
        if (array.size() == 0) {
            throw McpArgumentException(
                "`paths` must contain at least one [dx, dy, color] offset, otherwise the pattern is just " +
                        "the anchor colour and `find_color` should be used instead."
            )
        }
        return array.mapIndexed { index, element ->
            if (!element.isJsonArray || element.asJsonArray.size() != 3) {
                throw McpArgumentException(
                    "`paths[$index]` must be a [dx, dy, color] triple of two integers and a colour string."
                )
            }
            val triple = element.asJsonArray
            val dx = triple[0].takeIf { it.isJsonPrimitive }?.asInt
                ?: throw McpArgumentException("`paths[$index][0]` (dx) must be an integer.")
            val dy = triple[1].takeIf { it.isJsonPrimitive }?.asInt
                ?: throw McpArgumentException("`paths[$index][1]` (dy) must be an integer.")
            val color = parseColor(
                triple[2].takeIf { it.isJsonPrimitive }?.asString
                    ?: throw McpArgumentException("`paths[$index][2]` must be a colour string."),
                "paths[$index][2]",
            )
            Triple(dx, dy, color)
        }
    }

    /**
     * Parses `#RRGGBB` or `#AARRGGBB`. `Color.parseColor` also accepts colour
     * names, which stays available so a caller's habits are not broken.
     * zh-CN: 解析 `#RRGGBB` 或 `#AARRGGBB`. `Color.parseColor` 也接受颜色名,
     * 保留该能力以免破坏调用方的使用习惯.
     */
    private fun parseColor(value: String, param: String): Int = try {
        Color.parseColor(value.trim())
    } catch (_: IllegalArgumentException) {
        throw McpArgumentException(
            "`$param` (`$value`) is not a colour. Use `#RRGGBB` or `#AARRGGBB`, e.g. `#FF5722`."
        )
    }

    private fun resultBody(
        tool: String,
        color: Int,
        threshold: Int,
        region: List<Int>?,
        totalMatches: Int,
        extra: JsonObject.() -> Unit,
    ): JsonObject = McpJson.obj().apply {
        addProperty("tool", tool)
        addProperty("color", String.format("#%06X", color and 0xFFFFFF))
        addProperty("threshold", threshold)
        if (region != null) {
            add("region", JsonArray().apply { region.forEach { add(it) } })
        }
        addProperty("totalMatchingPixels", totalMatches)
        extra()
    }

    /**
     * Validates and clamps `[left, top, right, bottom]`, mirroring the geometry
     * check the `find_image` tools apply.
     * zh-CN: 校验并裁剪 `[left, top, right, bottom]`, 与 `find_image` 系列工具
     * 应用的几何检查保持一致.
     */
    private fun cropRect(region: List<Int>, screenWidth: Int, screenHeight: Int): Rect {
        if (region.size != 4) {
            throw McpArgumentException("`region` must contain exactly 4 integers: [left, top, right, bottom]")
        }
        val left = region[0].coerceIn(0, screenWidth)
        val top = region[1].coerceIn(0, screenHeight)
        val right = region[2].coerceIn(0, screenWidth)
        val bottom = region[3].coerceIn(0, screenHeight)
        if (right - left <= 0 || bottom - top <= 0) {
            throw McpArgumentException(
                "`region` $region is empty once clamped to the ${screenWidth}x$screenHeight screen."
            )
        }
        return Rect(left, top, right - left, bottom - top)
    }

}
