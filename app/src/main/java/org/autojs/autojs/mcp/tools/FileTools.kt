package org.autojs.autojs.mcp.tools

import com.google.gson.JsonArray
import org.autojs.autojs.mcp.McpArgumentException
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpStorage
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.util.WorkingDirectoryUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Workspace file access.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Security
 *  ! All paths are confined to the AutoJs6 working directory. That boundary is
 *  ! what keeps a compromised or confused client from rewriting the whole
 *  ! filesystem, and it is worth the small inconvenience of a relative path
 *  ! being the common case. Reaching outside requires passing
 *  ! `outsideWorkingDirectory: true` explicitly on each call, which keeps the
 *  ! escape deliberate and visible in the transcript rather than accidental.
 *  ! zh-CN: 所有路径都被限制在 AutoJs6 工作目录内. 这条边界正是防止被攻陷或"迷糊"的
 *  ! 客户端改写整个文件系统的东西, 代价只是常用场景要写相对路径, 完全值得.
 *  ! 越界访问必须在每次调用时显式传入 `outsideWorkingDirectory: true`,
 *  ! 这样"越界"就是有意且留痕的, 而不是无意发生的.
 */
internal object McpFileTools {

    val tools: List<McpTool> = listOf(
        fileReadTool(), fileWriteTool(), fileListTool(), fileDeleteTool(),
        fileCopyTool(), fileMoveTool(), fileRenameTool(), fileMkdirTool(),
    )

    private const val DEFAULT_READ_LIMIT_BYTES = 256 * 1024

    private const val MAX_READ_LIMIT_BYTES = 4 * 1024 * 1024

    private const val DEFAULT_LIST_LIMIT = 200

    /**
     * A fresh formatter per listing. `SimpleDateFormat` is not thread safe and
     * this object is shared by concurrent requests, so a single instance would
     * be a latent corruption bug in exchange for saving nothing meaningful.
     * zh-CN: 每次列举都新建格式化器. `SimpleDateFormat` 并非线程安全, 而本对象会被
     * 并发请求共享, 因此共用单个实例等于用一个潜在的输出错乱换取毫无意义的开销节省.
     */
    private fun newTimeFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Working directory, resolved once per call so a settings change takes effect. */
    private fun root(): File = File(WorkingDirectoryUtils.path).canonicalFile

    /** An error result with the shared storage hint attached when it is relevant. */
    private fun failure(prefix: String, error: Throwable, path: File): McpToolResult {
        val reason = "$prefix: ${error.message ?: error::class.java.simpleName}"
        return McpToolResult.error(McpStorage.explain(reason, path))
    }

    private const val PATH_DESC = "Path relative to the working directory, or absolute when " +
            "`outsideWorkingDirectory` is true."

    /**
     * @throws McpArgumentException when the path escapes the working directory.
     */
    private fun resolve(requested: String, outsideWorkingDirectory: Boolean): File {
        val root = root()
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

    // ---------------------------------------------------------------- file_read

    private fun fileReadTool(): McpTool = McpTool(
        name = "file_read",
        title = "Read a file",
        description = buildString {
            append("Returns the contents of a text file. Reads are capped so a large file cannot flood the context; ")
            append("check `truncated` in the result and raise `maxBytes` if you really need more. ")
            append("The response also reports the full `size`, which is usually enough to decide.")
        },
        risk = McpToolRisk.DANGEROUS,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "path" to McpSchema.string(PATH_DESC),
                "maxBytes" to McpSchema.integer(
                    "Do not return more than this many bytes.",
                    DEFAULT_READ_LIMIT_BYTES, 1024, MAX_READ_LIMIT_BYTES,
                ),
                "outsideWorkingDirectory" to McpSchema.boolean(
                    "Allow absolute paths outside the working directory.", false,
                ),
            ),
            required = listOf("path"),
        ),
    ) { args -> invokeFileRead(args) }

    private fun invokeFileRead(args: McpArgs): McpToolResult {
        val file = resolve(
            args.requireString("path"),
            args.optBoolean("outsideWorkingDirectory", false),
        )
        val maxBytes = args.optInt("maxBytes", DEFAULT_READ_LIMIT_BYTES)
            .coerceIn(1024, MAX_READ_LIMIT_BYTES)

        // `exists()` is not trustworthy on shared storage: when Android filters a
        // path it answers false for a file that is really there, so the hint is
        // appended rather than the bare absence being reported as a fact.
        // zh-CN: 在共享存储上 `exists()` 并不可信: 当 Android 过滤某条路径时,
        // 对真实存在的文件也会返回 false. 因此这里附带提示,
        // 而不是把一个裸的"不存在"当作事实报出去.
        if (!file.exists()) {
            return McpToolResult.error(McpStorage.explain("`${file.path}` does not exist.", file))
        }
        if (file.isDirectory) {
            return McpToolResult.error("`${file.path}` is a directory. Use `file_list` instead.")
        }

        val bytes = runCatching { file.readBytes() }.getOrElse {
            return failure("Reading failed", it, file)
        }

        val truncated = bytes.size > maxBytes
        val content = String(bytes, 0, minOf(bytes.size, maxBytes), Charsets.UTF_8)

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("path", file.path)
            addProperty("size", bytes.size)
            addProperty("truncated", truncated)
            addProperty("content", content)
            if (truncated) {
                addProperty("hint", "Only the first $maxBytes of ${bytes.size} bytes are shown.")
            }
        })
    }

    // --------------------------------------------------------------- file_write

    private fun fileWriteTool(): McpTool = McpTool(
        name = "file_write",
        title = "Write a file",
        description = buildString {
            append("Writes text to a file, creating missing parent directories. ")
            append("Overwrites by default: set `append: true` to add to the end instead. ")
            append("This is how an AI authored script gets onto the device before `run_script` or the script list sees it.")
        },
        risk = McpToolRisk.DANGEROUS,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "path" to McpSchema.string(PATH_DESC),
                "content" to McpSchema.string("Text to write. An empty string creates or empties the file."),
                "append" to McpSchema.boolean("Append instead of overwriting.", false),
                "outsideWorkingDirectory" to McpSchema.boolean(
                    "Allow absolute paths outside the working directory.", false,
                ),
            ),
            required = listOf("path", "content"),
        ),
    ) { args -> invokeFileWrite(args) }

    private fun invokeFileWrite(args: McpArgs): McpToolResult {
        val file = resolve(
            args.requireString("path"),
            args.optBoolean("outsideWorkingDirectory", false),
        )
        val content = args.optString("content")
            ?: throw McpArgumentException("`content` is required; pass an empty string to create an empty file.")
        val append = args.optBoolean("append", false)

        if (file.isDirectory) {
            return McpToolResult.error("`${file.path}` is a directory.")
        }

        return runCatching {
            file.parentFile?.mkdirs()
            if (append) file.appendText(content) else file.writeText(content)
        }.fold(
            onSuccess = {
                McpToolResult.json(McpJson.obj().apply {
                    addProperty("ok", true)
                    addProperty("path", file.path)
                    addProperty("bytes", content.toByteArray(Charsets.UTF_8).size)
                    addProperty("append", append)
                    addProperty("size", file.length())
                })
            },
            onFailure = { failure("Writing failed", it, file) },
        )
    }

    // ---------------------------------------------------------------- file_list

    private fun fileListTool(): McpTool = McpTool(
        name = "file_list",
        title = "List a directory",
        description = buildString {
            append("Lists directory entries with their size, type and modification time. ")
            append("Set `recursive: true` to walk subdirectories. The listing is breadth first and capped by `limit`, ")
            append("so `truncated` tells you whether anything was left out.")
        },
        risk = McpToolRisk.DANGEROUS,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "path" to McpSchema.string("$PATH_DESC Defaults to the working directory itself."),
                "recursive" to McpSchema.boolean("Walk subdirectories as well.", false),
                "limit" to McpSchema.integer("Maximum entries to return.", DEFAULT_LIST_LIMIT, 1, 1000),
                "outsideWorkingDirectory" to McpSchema.boolean(
                    "Allow absolute paths outside the working directory.", false,
                ),
            ),
        ),
    ) { args -> invokeFileList(args) }

    private fun invokeFileList(args: McpArgs): McpToolResult {
        val requested = args.optString("path")?.takeIf { it.isNotBlank() } ?: "."
        val recursive = args.optBoolean("recursive", false)
        val limit = args.optInt("limit", DEFAULT_LIST_LIMIT).coerceIn(1, 1000)
        val directory = resolve(requested, args.optBoolean("outsideWorkingDirectory", false))

        if (!directory.exists()) {
            return McpToolResult.error(McpStorage.explain("`${directory.path}` does not exist.", directory))
        }
        if (!directory.isDirectory) {
            return McpToolResult.error("`${directory.path}` is a file. Use `file_read` instead.")
        }

        val root = root()
        val entries = JsonArray()
        val timeFormat = newTimeFormat()
        // Breadth first with an explicit queue rather than walkTopDown(), so a
        // deep or huge tree stops at the limit instead of walking everything.
        // zh-CN: 用显式队列做广度优先, 而不是 walkTopDown(),
        // 这样遇到很深或很大的目录树时会先停在限额处, 而不是遍历完整棵树.
        val queue = ArrayDeque<File>().apply { addLast(directory) }
        var matched = 0
        var truncated = false
        // Under scoped storage `listFiles()` returns null for a directory the app
        // may not read, which is indistinguishable from an empty one further up.
        // Remembering that it happened is the only way to avoid reporting a
        // filtered tree as a complete one.
        // zh-CN: 在分区存储下, 应用无权读取的目录其 `listFiles()` 会返回 null,
        // 这与"空目录"在上层看到的结果完全一样. 只有记住这件事发生过,
        // 才能避免把被过滤过的目录树当成完整的目录树报告出去.
        var sawUnreadableDirectory = false

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = current.listFiles()
            if (children == null) {
                sawUnreadableDirectory = true
                continue
            }
            for (child in children.sortedBy { it.name.lowercase() }) {
                if (matched >= limit) {
                    truncated = true
                    break
                }
                matched++
                entries.add(McpJson.obj().apply {
                    addProperty(
                        "path",
                        runCatching { child.canonicalFile.relativeTo(root).path }
                            .getOrElse { child.path },
                    )
                    addProperty("name", child.name)
                    addProperty("directory", child.isDirectory)
                    addProperty("size", if (child.isDirectory) 0 else child.length())
                    addProperty("modifiedAt", timeFormat.format(Date(child.lastModified())))
                })
                if (recursive && child.isDirectory) {
                    queue.addLast(child)
                }
            }
            if (matched >= limit) {
                truncated = true
                break
            }
        }

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("directory", directory.path)
            addProperty("count", entries.size())
            addProperty("truncated", truncated)
            add("entries", entries)
            // A filtered listing is the one result a caller cannot sanity check for
            // itself, and it is worse than it looks: without the grant Android does
            // not refuse `listFiles()`, it hands back a *shorter* array with the
            // unreadable children quietly removed. A full looking list of four
            // entries where there are six is therefore indistinguishable from the
            // truth, and the caller will report it as the truth. So the caveat is
            // attached whenever the grant is missing, not merely when the list is
            // empty.
            // zh-CN: 被过滤过的列表是调用方唯一无法自行核对的结果, 而且比看上去更糟:
            // 缺少授权时 Android 并不会让 `listFiles()` 失败, 而是返回一个**更短**的数组,
            // 把读不了的子项悄悄摘掉. 因此"看着完整的 4 条"与真实的 6 条无法区分,
            // 而调用方会把它当真. 所以只要缺少授权就附上说明, 而不只是在列表为空时.
            val storageHint = McpStorage.permissionHint(directory)
            when {
                storageHint != null -> addProperty(
                    "note",
                    "This listing was produced without \"All files access\", and Android omits " +
                            "entries the app cannot read instead of failing, so the list may be " +
                            "incomplete while still looking complete. ${McpStorage.remedy}",
                )
                sawUnreadableDirectory -> addProperty(
                    "note",
                    "One or more subdirectories could not be read, so this listing is incomplete. " +
                            "Directories such as `Android/data` are protected by the system and " +
                            "cannot be listed by any app.",
                )
            }
        })
    }

    // -------------------------------------------------------------- file_delete

    private fun fileDeleteTool(): McpTool = McpTool(
        name = "file_delete",
        title = "Delete a file or directory",
        description = buildString {
            append("Deletes a file, or a directory when `recursive: true` is also set. ")
            append("Deletion is permanent: it does not go through any trash. ")
            append("The working directory itself is refused, which prevents one bad path from wiping the whole workspace. ")
            append("Call `file_list` first to confirm what is actually there.")
        },
        risk = McpToolRisk.DANGEROUS,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "path" to McpSchema.string(PATH_DESC),
                "recursive" to McpSchema.boolean(
                    "Required to delete a non-empty directory, including everything inside it.", false,
                ),
                "outsideWorkingDirectory" to McpSchema.boolean(
                    "Allow absolute paths outside the working directory.", false,
                ),
            ),
            required = listOf("path"),
        ),
    ) { args -> invokeFileDelete(args) }

    private fun invokeFileDelete(args: McpArgs): McpToolResult {
        val target = resolve(
            args.requireString("path"),
            args.optBoolean("outsideWorkingDirectory", false),
        )
        val recursive = args.optBoolean("recursive", false)

        // Guard the boundary itself: deleting the workspace root is almost
        // certainly a mistake, and it is the one path that makes everything else
        // unrecoverable.
        // zh-CN: 守护边界本身: 删除工作目录根几乎肯定是误操作,
        // 而它恰恰是会让其他一切都无法恢复的那一条路径.
        if (target == root()) {
            throw McpArgumentException("Refusing to delete the working directory itself.")
        }
        if (!target.exists()) {
            return McpToolResult.error(McpStorage.explain("`${target.path}` does not exist.", target))
        }

        val deleted = runCatching {
            when {
                target.isDirectory && !recursive -> throw McpArgumentException(
                    "`${target.path}` is a directory. Pass `recursive: true` to delete it and everything inside."
                )
                target.isDirectory -> target.deleteRecursively()
                else -> target.delete()
            }
        }.getOrElse {
            return failure("Deleting failed", it, target)
        }

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("ok", deleted)
            addProperty("path", target.path)
            addProperty("recursive", recursive)
            if (!deleted) {
                // delete() reports refusal as a plain false rather than throwing,
                // so the reason has to be inferred here.
                // zh-CN: delete() 以返回 false 而非抛异常来表示拒绝, 因此原因只能在此推断.
                val reason = "The filesystem refused the delete. The file may be in use or read only."
                val hint = McpStorage.permissionHint(target)
                addProperty("hint", if (hint == null) reason else "$reason $hint")
            }
        })
    }

    // ---------------------------------------------------------------- file_copy

    private fun fileCopyTool(): McpTool = McpTool(
        name = "file_copy",
        title = "Copy a file or directory",
        description = buildString {
            append("Copies a file, or a whole directory tree, to a new path. ")
            append("The destination is refused when it already exists unless `overwrite: true` is passed, ")
            append("so an existing script is never silently replaced. ")
            append("Both paths obey the same `outsideWorkingDirectory` switch.")
        },
        risk = McpToolRisk.DANGEROUS,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "path" to McpSchema.string("Existing source. $PATH_DESC"),
                "dest" to McpSchema.string("Destination path. $PATH_DESC"),
                "overwrite" to McpSchema.boolean(
                    "Replace the destination when it already exists. Without it an existing " +
                            "destination is an error.", false,
                ),
                "outsideWorkingDirectory" to McpSchema.boolean(
                    "Allow absolute paths outside the working directory, for both `path` and `dest`.", false,
                ),
            ),
            required = listOf("path", "dest"),
        ),
    ) { args -> invokeFileCopyOrMove(args, isMove = false) }

    // ---------------------------------------------------------------- file_move

    private fun fileMoveTool(): McpTool = McpTool(
        name = "file_move",
        title = "Move a file or directory",
        description = buildString {
            append("Moves a file or directory to a new path, which within one storage volume is a rename and ")
            append("across volumes falls back to copy then delete. ")
            append("The destination is refused when it already exists unless `overwrite: true` is passed. ")
            append("Both paths obey the same `outsideWorkingDirectory` switch.")
        },
        risk = McpToolRisk.DANGEROUS,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "path" to McpSchema.string("Existing source. $PATH_DESC"),
                "dest" to McpSchema.string("Destination path. $PATH_DESC"),
                "overwrite" to McpSchema.boolean(
                    "Replace the destination when it already exists. Without it an existing " +
                            "destination is an error.", false,
                ),
                "outsideWorkingDirectory" to McpSchema.boolean(
                    "Allow absolute paths outside the working directory, for both `path` and `dest`.", false,
                ),
            ),
            required = listOf("path", "dest"),
        ),
    ) { args -> invokeFileCopyOrMove(args, isMove = true) }

    private fun invokeFileCopyOrMove(args: McpArgs, isMove: Boolean): McpToolResult {
        val verb = if (isMove) "move" else "copy"
        val outside = args.optBoolean("outsideWorkingDirectory", false)
        val source = resolve(args.requireString("path"), outside)
        val dest = resolve(args.requireString("dest"), outside)
        val overwrite = args.optBoolean("overwrite", false)

        if (!source.exists()) {
            return McpToolResult.error(McpStorage.explain("`${source.path}` does not exist.", source))
        }
        if (source == dest) {
            throw McpArgumentException("`path` and `dest` are the same file: `${source.path}`.")
        }
        if (dest.exists() && !overwrite) {
            return McpToolResult.error(
                "`${dest.path}` already exists. Pass `overwrite: true` to replace it, " +
                        "or choose a different destination."
            )
        }

        val moved = runCatching {
            dest.parentFile?.mkdirs()
            if (isMove) move(source, dest) else copy(source, dest)
        }.getOrElse {
            return failure("${verb.capitalizeAscii()} failed", it, dest)
        }

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("ok", moved)
            addProperty("source", source.path)
            addProperty("dest", dest.path)
            addProperty("directory", source.isDirectory)
            if (!moved) {
                addProperty(
                    "hint",
                    "The filesystem refused the operation. The destination may be read only, " +
                            "or a parent directory may be missing.",
                )
            }
        })
    }

    private fun copy(source: File, dest: File): Boolean = when {
        source.isDirectory -> source.copyRecursively(dest, overwrite = true)
        else -> runCatching { source.copyTo(dest, overwrite = true).length() > 0 }
            .getOrDefault(false)
    }

    /**
     * A rename within one volume is atomic and instant, so it is tried first;
     * across volumes it fails, and copy-then-delete is the fallback that still
     * gets the caller where it asked to go.
     * zh-CN: 同一存储卷内的重命名是原子且即时的, 因此先尝试它;
     * 跨卷时它会失败, 此时以"复制后删除"作为仍能达成目的的退路.
     */
    private fun move(source: File, dest: File): Boolean {
        if (source.renameTo(dest)) return true
        if (!copy(source, dest)) return false
        return source.deleteRecursively()
    }

    // -------------------------------------------------------------- file_rename

    private fun fileRenameTool(): McpTool = McpTool(
        name = "file_rename",
        title = "Rename a file or directory",
        description = buildString {
            append("Renames a file or directory within its own directory. ")
            append("`newName` is a bare name, not a path: to place a file in a different directory use ")
            append("`file_move` instead.")
        },
        risk = McpToolRisk.DANGEROUS,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "path" to McpSchema.string("Existing file or directory. $PATH_DESC"),
                "newName" to McpSchema.string(
                    "The new name, without any directory part, e.g. `main_v2.js`.",
                ),
                "outsideWorkingDirectory" to McpSchema.boolean(
                    "Allow absolute paths outside the working directory.", false,
                ),
            ),
            required = listOf("path", "newName"),
        ),
    ) { args -> invokeFileRename(args) }

    private fun invokeFileRename(args: McpArgs): McpToolResult {
        val source = resolve(
            args.requireString("path"),
            args.optBoolean("outsideWorkingDirectory", false),
        )
        val newName = args.requireString("newName")

        if (newName.contains(File.separatorChar) || newName.contains('/') || newName.contains('\\')) {
            throw McpArgumentException(
                "`newName` must be a bare file name without a directory part. " +
                        "Use `file_move` to place a file in a different directory."
            )
        }
        if (newName == "." || newName == "..") {
            throw McpArgumentException("`newName` `$newName` is not a usable file name.")
        }
        if (!source.exists()) {
            return McpToolResult.error(McpStorage.explain("`${source.path}` does not exist.", source))
        }

        val dest = File(source.parentFile, newName)
        when {
            dest == source -> throw McpArgumentException("`newName` is the name the file already has.")
            dest.exists() -> return McpToolResult.error(
                "`${dest.path}` already exists. Rename to a free name instead: replacing by rename " +
                        "behaves differently across filesystems."
            )
        }

        val renamed = runCatching { source.renameTo(dest) }.getOrElse {
            return failure("Renaming failed", it, dest)
        }

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("ok", renamed)
            addProperty("source", source.path)
            addProperty("dest", dest.path)
            if (!renamed) {
                addProperty("hint", "The filesystem refused the rename. The file may be in use.")
            }
        })
    }

    // ---------------------------------------------------------------- file_mkdir

    private fun fileMkdirTool(): McpTool = McpTool(
        name = "mkdir",
        title = "Create a directory",
        description = buildString {
            append("Creates a directory, including missing parents by default. ")
            append("Creating a directory that already exists is reported as success with `alreadyExists: true`, ")
            append("because for the caller's purpose the directory being there is the goal.")
        },
        risk = McpToolRisk.DANGEROUS,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "path" to McpSchema.string("Directory to create. $PATH_DESC"),
                "parents" to McpSchema.boolean(
                    "Create missing parent directories too. Without it, only the final segment is created.",
                    true,
                ),
                "outsideWorkingDirectory" to McpSchema.boolean(
                    "Allow absolute paths outside the working directory.", false,
                ),
            ),
            required = listOf("path"),
        ),
    ) { args -> invokeFileMkdir(args) }

    private fun invokeFileMkdir(args: McpArgs): McpToolResult {
        val directory = resolve(
            args.requireString("path"),
            args.optBoolean("outsideWorkingDirectory", false),
        )
        val parents = args.optBoolean("parents", true)

        if (directory.exists()) {
            if (directory.isDirectory) {
                return McpToolResult.json(McpJson.obj().apply {
                    addProperty("ok", true)
                    addProperty("path", directory.path)
                    addProperty("alreadyExists", true)
                })
            }
            return McpToolResult.error(
                "`${directory.path}` exists and is a file, so a directory cannot take its place."
            )
        }

        val created = runCatching {
            if (parents) directory.mkdirs() else directory.mkdir()
        }.getOrElse {
            return failure("Creating the directory failed", it, directory)
        }

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("ok", created)
            addProperty("path", directory.path)
            addProperty("alreadyExists", false)
            if (!created) {
                val reason = "The filesystem refused to create the directory. A parent may be a file, " +
                        "or the storage may be read only."
                val hint = McpStorage.permissionHint(directory)
                addProperty("hint", if (hint == null) reason else "$reason $hint")
            }
        })
    }

    private fun String.capitalizeAscii(): String =
        if (isEmpty()) this else this[0].uppercaseChar() + substring(1)

}
