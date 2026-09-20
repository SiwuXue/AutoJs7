package org.autojs.autojs.mcp.tools

import com.google.gson.JsonArray
import org.autojs.autojs.core.image.TemplateMatching
import org.autojs.autojs.core.opencv.Mat as AutoJsMat
import org.autojs.autojs.core.opencv.OpenCVHelper
import org.autojs.autojs.mcp.McpArgumentException
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpScreenCapture
import org.autojs.autojs.mcp.McpStorage
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.mcp.McpUi
import org.autojs.autojs.runtime.api.Images
import org.autojs.autojs.util.WorkingDirectoryUtils
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Template matching against the current screen.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Why
 *  ! This is the visual counterpart to `ui_find`. Where a view hierarchy exists
 *  ! the tree is exact and cheap, but games, canvases and video surfaces expose
 *  ! no nodes at all, and there the only way to locate something is to look at
 *  ! the pixels. `find_image` returns coordinates in the same space `ui_action`
 *  ! and `gesture` expect, so the two halves compose without conversion.
 *  ! zh-CN: 这是 `ui_find` 的视觉对应物. 存在视图树时, 树既精确又廉价;
 *  ! 而游戏, 画布与视频层根本不暴露节点, 此时定位某个东西的唯一办法就是看像素.
 *  ! `find_image` 返回的坐标与 `ui_action` 和 `gesture` 期望的空间一致,
 *  ! 因此这两半可以无缝组合, 无需换算.
 *
 * @ImplementationNote
 *  ! The public `images.findImage` script API is deliberately not reused. It runs
 *  ! through `ImageWrapper`, which requires a `ScriptRuntime` that an MCP request
 *  ! does not have -- and constructing one just to call a matcher would drag the
 *  ! whole script engine into a synchronous tool call. `TemplateMatching` itself
 *  ! is a plain static helper over OpenCV, so it can be driven directly.
 *  ! zh-CN: 这里刻意不复用脚本可见的 `images.findImage`. 它经由 `ImageWrapper`,
 *  ! 而后者需要 MCP 请求并不具备的 `ScriptRuntime`; 为了调用一个匹配器而构造
 *  ! ScriptRuntime, 等于把整个脚本引擎拖进一次同步的工具调用.
 *  ! `TemplateMatching` 本身只是基于 OpenCV 的静态辅助类, 可以直接驱动.
 */
internal object McpFindImageTools {

    val tools: List<McpTool> = listOf(findImageTool())

    private const val MAX_MATCHES = 20

    private const val DEFAULT_THRESHOLD = 0.9

    private const val DEFAULT_WEAK_THRESHOLD = 0.7

    private const val MIN_DOWNSCALE = 160

    private const val MAX_DOWNSCALE = 4096

    /**
     * @Note
     *  ! The thresholds are named after the script facing API rather than the
     *  ! underlying `Options` fields, so a caller who already knows
     *  ! `images.findImage` sees no new vocabulary. `threshold` is the strict
     *  ! one that decides the final result; `weakThreshold` only decides whether
     *  ! the pyramid keeps descending.
     *  ! zh-CN: 阈值命名沿用脚本可见的 API 而非底层 `Options` 字段, 因此已熟悉
     *  ! `images.findImage` 的调用方无需学习新词汇. `threshold` 是决定最终结果的强阈值;
     *  ! `weakThreshold` 只决定金字塔是否继续向下搜索.
     */
    private fun findImageTool(): McpTool = McpTool(
        name = "find_image",
        title = "Locate an image on screen",
        description = buildString {
            append("Captures the screen and searches it for a template image stored on the device, returning the ")
            append("top-left corner and centre of each match in screen pixels. ")
            append("Use this when there is no view hierarchy to query: games, canvases, video and images. ")
            append("Otherwise prefer `ui_find`, which is exact instead of approximate and costs far less. ")
            append("If nothing matches, work through these in order: confirm the template really is on screen by ")
            append("taking a `screenshot`, lower `threshold` a step at a time, then check that the template is ")
            append("not too large a share of what is being searched. Matching descends an image pyramid, and a ")
            append("template that nearly fills the search area leaves the coarse level too few positions to ")
            append("pick the right one -- measured on a still screen, one such search scored 0.35 and landed ")
            append("four pixels off, while the same template over the whole screen scored 1.0. Widening ")
            append("`region`, or cropping a smaller template, fixes that; narrowing `region` makes it worse. ")
            append("A template is matched at the size it was captured, so it will not be found if the screen was ")
            append("captured at a different resolution or the UI scaled between capture and search.")
        },
        risk = McpToolRisk.SAFE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "templatePath" to McpSchema.string(
                    "Path to the template image, relative to the working directory.",
                ),
                "region" to McpSchema.arrayOf(
                    "Restrict the search to a screen rectangle as [left, top, right, bottom]. " +
                            "Much faster, and it prevents matching the same icon in an unrelated part of the screen. " +
                            "Keep it comfortably larger than the template: the matcher descends an image pyramid, " +
                            "and a template that nearly fills the region leaves the coarse level too few positions " +
                            "to pick the right one. " +
                            "Returned coordinates are always translated back to screen space.",
                    McpSchema.integer("Coordinate in screen pixels."),
                ),
                "threshold" to McpSchema.number(
                    "Similarity a match must reach to be accepted, from 0 to 1. Lower it when a match is missed.",
                    DEFAULT_THRESHOLD, 0.0, 1.0,
                ),
                "weakThreshold" to McpSchema.number(
                    "Similarity below which a candidate is abandoned part way through the search, from 0 to 1. " +
                            "Must not exceed `threshold`. Lowering it makes the search more thorough and slower.",
                    DEFAULT_WEAK_THRESHOLD, 0.0, 1.0,
                ),
                "maxLevel" to McpSchema.integer(
                    "Levels of the image pyramid, which trades speed for tolerance of small size differences. " +
                            "-1 selects it automatically.",
                    TemplateMatching.MAX_LEVEL_AUTO, -1, 10,
                ),
                "limit" to McpSchema.integer(
                    "Maximum matches to return, best first.", 1, 1, MAX_MATCHES,
                ),
                "downscaleTo" to McpSchema.integer(
                    "Shrink the screen so its longest side is this many pixels before matching, then scale the " +
                            "results back. Speeds up large screens considerably; 0 disables it.",
                    0, 0, MAX_DOWNSCALE,
                ),
                "useTransparentMask" to McpSchema.boolean(
                    "Match using the template's alpha channel, so transparent pixels are ignored. " +
                            "Only applies when the template actually has an alpha channel.",
                    false,
                ),
                "outsideWorkingDirectory" to McpSchema.boolean(
                    "Allow a template path outside the working directory.", false,
                ),
            ),
            required = listOf("templatePath"),
        ),
    ) { args -> invokeFindImage(args) }

    private fun invokeFindImage(args: McpArgs): McpToolResult {
        val templatePath = args.requireString("templatePath")
        val region = args.optIntList("region")
        val threshold = args.optDouble("threshold", DEFAULT_THRESHOLD).toFloat().coerceIn(0f, 1f)
        val weakThreshold = args.optDouble("weakThreshold", DEFAULT_WEAK_THRESHOLD).toFloat().coerceIn(0f, 1f)
        val maxLevel = args.optInt("maxLevel", TemplateMatching.MAX_LEVEL_AUTO).coerceIn(-1, 10)
        val limit = args.optInt("limit", 1).coerceIn(1, MAX_MATCHES)
        val downscaleTo = args.optInt("downscaleTo", 0).let {
            if (it <= 0) 0 else it.coerceIn(MIN_DOWNSCALE, MAX_DOWNSCALE)
        }
        val wantTransparentMask = args.optBoolean("useTransparentMask", false)
        val outsideWorkingDirectory = args.optBoolean("outsideWorkingDirectory", false)

        if (threshold < weakThreshold) {
            throw McpArgumentException(
                "`threshold` ($threshold) must be greater than or equal to `weakThreshold` ($weakThreshold). " +
                        "The weak threshold is the lower bound that lets the search continue."
            )
        }

        val templateFile = resolve(templatePath, outsideWorkingDirectory)
        if (!templateFile.exists()) {
            // `exists()` returning false for a file the user can see in a file
            // manager is the signature symptom of shared storage filtering, so
            // the storage hint is worth appending here rather than merely
            // stating the absence.
            // zh-CN: 对于用户在文件管理器里明明看得到的文件, `exists()` 返回 false
            // 正是共享存储被过滤的典型症状. 因此这里值得附带存储提示,
            // 而不是仅仅陈述"不存在".
            return McpToolResult.error(
                McpStorage.explain("`${templateFile.path}` does not exist.", templateFile)
            )
        }
        if (templateFile.isDirectory) {
            return McpToolResult.error("`${templateFile.path}` is a directory, not an image file.")
        }

        // Guard after the request checks, before the first service call -- see
        // the contract on `McpUi.requireAccessibilityService`.
        // zh-CN: 守卫放在请求检查之后, 首次调用服务之前 —— 见
        // `McpUi.requireAccessibilityService` 上的约定.
        McpUi.requireAccessibilityService()

        Images.initOpenCvIfNeeded()

        // Every Mat this function creates is registered here and released in one
        // place. OpenCV Mats hold native memory, and a tool that is called in a
        // loop would otherwise leak a screen sized buffer on each call.
        // zh-CN: 本次函数创建的每个 Mat 都登记于此并在一处统一释放. OpenCV 的 Mat
        // 持有本地内存, 否则在循环调用的场景下每次都会泄漏一块屏幕大小的缓冲.
        val owned = mutableListOf<Mat>()

        val screenBitmap = when (val outcome = McpScreenCapture.capture()) {
            is McpScreenCapture.Outcome.Failed -> return McpToolResult.error(
                "Screenshot failed: ${outcome.reason}."
            )
            is McpScreenCapture.Outcome.Captured -> outcome.bitmap
        }

        try {
            val notes = mutableListOf<String>()

            // `bitmapToMat` hands back RGBA, whereas `imread` hands back BGR. Left
            // as-is the two would disagree on channel count and matchTemplate
            // would throw, which is the single most common way this kind of tool
            // breaks. Both sides are therefore normalised to the same layout.
            // zh-CN: `bitmapToMat` 得到 RGBA, 而 `imread` 得到 BGR. 若不加处理,
            // 两者通道数不一致会导致 matchTemplate 抛异常 —— 这正是此类工具最常见的
            // 失效方式. 因此两侧都归一化到同一布局.
            val screenRaw = Mat().also { owned.add(it) }
            Utils.bitmapToMat(screenBitmap, screenRaw)

            val templateRaw = (if (wantTransparentMask) {
                Imgcodecs.imread(templateFile.path, Imgcodecs.IMREAD_UNCHANGED)
            } else {
                Imgcodecs.imread(templateFile.path, Imgcodecs.IMREAD_COLOR)
            }).also { owned.add(it) }

            if (templateRaw.empty()) {
                // OpenCV reports a permission failure and a corrupt file the same
                // way -- an empty Mat. The hint is what separates the two for the
                // caller, and it stays silent when permissions cannot be the cause.
                // zh-CN: 对权限不足与文件损坏, OpenCV 给出的是同一种结果 —— 空 Mat.
                // 这个提示正是为了帮调用方区分二者, 且在权限不可能是原因时保持静默.
                return McpToolResult.error(
                    McpStorage.explain(
                        "`${templateFile.path}` could not be decoded as an image. " +
                                "Supported formats are those OpenCV ships with: PNG, JPEG, BMP, WebP and TIFF.",
                        templateFile,
                    )
                )
            }
            if (templateRaw.depth() != CvType.CV_8U) {
                return McpToolResult.error(
                    "`${templateFile.path}` is not an 8-bit per channel image, which the matcher does not support. " +
                            "Re-save it as a standard 8-bit PNG or JPEG."
                )
            }

            val templateHasAlpha = templateRaw.channels() == 4
            val useTransparentMask = wantTransparentMask && templateHasAlpha
            if (wantTransparentMask && !templateHasAlpha) {
                // Reported rather than silently ignored: a caller who asked for
                // transparent matching would otherwise wonder why a hole in the
                // template still counts as part of the pattern.
                // zh-CN: 这里选择上报而不是静默忽略: 否则请求了透明匹配的调用方会困惑
                // 为什么模板上的镂空区域仍被当作图案的一部分.
                notes.add(
                    "The template has no alpha channel, so `useTransparentMask` had no effect. " +
                            "Re-save it as a 32-bit PNG to exclude transparent pixels."
                )
            }

            val targetChannels = if (useTransparentMask) 4 else 3
            val screenNorm = toChannels(screenRaw, targetChannels).also { owned.addIfNew(it, screenRaw) }
            val templateNorm = toChannels(templateRaw, targetChannels).also { owned.addIfNew(it, templateRaw) }

            val screenWidth = screenBitmap.width
            val screenHeight = screenBitmap.height

            val regionRect = region?.let { cropRect(it, screenWidth, screenHeight) }
            val searched = if (regionRect != null) {
                Mat(screenNorm, regionRect).also { owned.add(it) }
            } else {
                screenNorm
            }

            if (templateNorm.cols() > searched.cols() || templateNorm.rows() > searched.rows()) {
                return McpToolResult.error(
                    "The template is ${templateNorm.cols()}x${templateNorm.rows()} but the search area is only " +
                            "${searched.cols()}x${searched.rows()}, so it can never fit. " +
                            "Widen `region` or use a smaller template."
                )
            }

            val scale = downscaleRatio(searched, downscaleTo)
            val searchedScaled = if (scale < 1.0) {
                resize(searched, scale).also { owned.add(it) }
            } else {
                searched
            }
            val templateScaled = if (scale < 1.0) {
                resize(templateNorm, scale).also { owned.add(it) }
            } else {
                templateNorm
            }

            val originalTemplateWidth = templateNorm.cols()
            val originalTemplateHeight = templateNorm.rows()
            val originX = regionRect?.x ?: 0
            val originY = regionRect?.y ?: 0

            val matches = try {
                TemplateMatching.fastTemplateMatching(
                    AutoJsMat(searchedScaled.nativeObj),
                    AutoJsMat(templateScaled.nativeObj),
                    TemplateMatching.Options(
                        TemplateMatching.MATCHING_METHOD_NONE,
                        weakThreshold,
                        threshold,
                        maxLevel,
                        useTransparentMask,
                        limit,
                    ),
                )
            } catch (e: Throwable) {
                return McpToolResult.error(
                    "Template matching failed: ${e::class.java.simpleName}: ${e.message ?: "no message"}"
                )
            }

            return McpToolResult.json(McpJson.obj().apply {
                addProperty("hit", matches.isNotEmpty())
                addProperty("count", matches.size)
                addProperty("screenWidth", screenWidth)
                addProperty("screenHeight", screenHeight)
                addProperty("templatePath", templateFile.path)
                add("templateSize", JsonArray().apply {
                    add(originalTemplateWidth); add(originalTemplateHeight)
                })
                addProperty("threshold", threshold.toDouble())
                addProperty("weakThreshold", weakThreshold.toDouble())
                addProperty("useTransparentMask", useTransparentMask)
                if (scale < 1.0) {
                    addProperty("downscaleFactor", scale)
                }
                if (region != null) {
                    add("region", JsonArray().apply { region.forEach { add(it) } })
                }
                if (notes.isNotEmpty()) {
                    add("notes", JsonArray().apply { notes.forEach { add(it) } })
                }
                if (matches.isEmpty()) {
                    addProperty(
                        "hint",
                        "Nothing matched at $threshold. Check the template against a fresh `screenshot`, " +
                                "narrow `region`, or lower `threshold` in steps of 0.05.",
                    )
                }
                add("matches", JsonArray().apply {
                    matches.forEach { match ->
                        // The matcher works in the scaled, region relative space,
                        // so both transforms are undone here. The caller receives
                        // plain screen pixels and never learns the scaling existed.
                        // zh-CN: 匹配器工作在缩放且相对于区域的坐标空间里, 因此这里把
                        // 两种变换都还原. 调用方拿到的就是纯粹的屏幕像素, 完全不需要知道
                        // 期间发生过缩放.
                        val left = (match.point.x / scale).roundToInt() + originX
                        val top = (match.point.y / scale).roundToInt() + originY
                        add(McpJson.obj().apply {
                            add("point", JsonArray().apply { add(left); add(top) })
                            add("center", JsonArray().apply {
                                add(left + originalTemplateWidth / 2)
                                add(top + originalTemplateHeight / 2)
                            })
                            // Rounded so the value reads cleanly in a transcript
                            // instead of trailing a dozen decimal digits.
                            // zh-CN: 做取整处理, 使该值在对话记录中清爽可读,
                            // 而不是拖着一长串小数位.
                            addProperty("similarity", (match.similarity * 1000).roundToInt() / 1000.0)
                        })
                    }
                })
            })
        } finally {
            owned.forEach { OpenCVHelper.release(it) }
            screenBitmap.recycle()
        }
    }

    /**
     * Registers [candidate] only when it is a distinct object, because the
     * conversion helper returns its input untouched when no work is needed and
     * releasing the same Mat twice would corrupt the native reference count.
     * zh-CN: 仅当 [candidate] 是不同对象时才登记, 因为转换辅助函数在无需处理时会原样
     * 返回入参, 而对同一个 Mat 释放两次会破坏本地引用计数.
     */
    private fun MutableList<Mat>.addIfNew(candidate: Mat, source: Mat) {
        if (candidate !== source) add(candidate)
    }

    /**
     * Converts [source] to [targetChannels] 8-bit channels.
     *
     * @Note
     *  ! Returns [source] itself when nothing needs doing, so callers must not
     *  ! assume they own a new buffer.
     *  ! zh-CN: 无需处理时返回 [source] 本身, 因此调用方不可假定自己拿到了新缓冲.
     */
    private fun toChannels(source: Mat, targetChannels: Int): Mat {
        if (source.channels() == targetChannels) return source
        val code = when (source.channels() to targetChannels) {
            1 to 3 -> Imgproc.COLOR_GRAY2BGR
            1 to 4 -> Imgproc.COLOR_GRAY2BGRA
            3 to 4 -> Imgproc.COLOR_BGR2BGRA
            4 to 3 -> Imgproc.COLOR_BGRA2BGR
            3 to 1 -> Imgproc.COLOR_BGR2GRAY
            4 to 1 -> Imgproc.COLOR_BGRA2GRAY
            else -> -1
        }
        if (code < 0) return source
        return Mat().also { Imgproc.cvtColor(source, it, code) }
    }

    private fun resize(source: Mat, scale: Double): Mat {
        val width = max(1, (source.cols() * scale).roundToInt())
        val height = max(1, (source.rows() * scale).roundToInt())
        return Mat().also {
            Imgproc.resize(source, it, Size(width.toDouble(), height.toDouble()))
        }
    }

    /** @return a value in (0, 1], where 1 means no shrinking is needed. */
    private fun downscaleRatio(source: Mat, downscaleTo: Int): Double {
        if (downscaleTo <= 0) return 1.0
        val longest = max(source.cols(), source.rows())
        if (longest <= downscaleTo) return 1.0
        return downscaleTo.toDouble() / longest
    }

    /**
     * Validates and clamps `[left, top, right, bottom]` to the screen.
     * zh-CN: 校验并裁剪 `[left, top, right, bottom]` 至屏幕范围内.
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

    /**
     * @throws McpArgumentException when the path escapes the working directory
     * without explicit permission, mirroring the `file_*` tools.
     * zh-CN: 当路径在未显式许可的情况下越出工作目录时抛出, 与 `file_*` 工具保持一致.
     */
    private fun resolve(requested: String, outsideWorkingDirectory: Boolean): File {
        val root = File(WorkingDirectoryUtils.path).canonicalFile
        val candidate = File(requested)
            .let { if (it.isAbsolute) it else File(root, requested) }
            .canonicalFile

        val insideRoot = candidate == root || candidate.path.startsWith(root.path + File.separator)
        if (!insideRoot && !outsideWorkingDirectory) {
            throw McpArgumentException(
                "`$requested` resolves to `$candidate`, which is outside the AutoJs6 working directory " +
                        "`$root`. Pass `outsideWorkingDirectory: true` if that is really intended."
            )
        }
        return candidate
    }

}
