package org.autojs.autojs.mcp

import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Storage permission diagnostics, shared by every tool that touches a path.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Why
 *  ! On Android 11 and newer, shared storage is not simply allowed or denied: the
 *  ! system filters it. An unreadable file is reported as `EPERM`/`EACCES`, a
 *  ! filtered directory reports nothing at all, and `File.exists()` returns false
 *  ! for a path that is plainly there. Left alone, all three read as facts about
 *  ! the user's filesystem rather than as symptoms of a missing setting, and the
 *  ! silent ones are the worst -- a caller confidently reports an empty directory
 *  ! or a missing file that actually exists.
 *  ! zh-CN: 在 Android 11 及以上, 共享存储并非简单的"允许或拒绝", 系统会将其过滤.
 *  ! 不可读的文件表现为 `EPERM`/`EACCES`, 被过滤的目录什么都不报告,
 *  ! 而 `File.exists()` 对明明存在的路径返回 false.
 *  ! 若不加以说明, 这三种情况都会被当成关于用户文件系统的事实,
 *  ! 而不是"缺少某项设置"的症状; 其中静默的那些最糟 ——
 *  ! 调用方会言之凿凿地报告一个空目录, 或一个其实存在的文件不存在.
 */
internal object McpStorage {

    /**
     * The actionable half of [permissionHint], split out for callers that have
     * already explained the cause and only need the fix.
     *
     * @Note
     *  ! Keeping the settings path in one place matters because it is the part a
     *  ! reader is expected to follow verbatim; a copy that drifts would send the
     *  ! user to a screen that does not exist.
     *  ! zh-CN: 把设置路径留在唯一一处很重要, 因为它是读者要照做的部分;
     *  ! 一份走样的副本会把用户引到一个并不存在的界面.
     */
    val remedy: String =
        "Grant it in Settings > Apps > AutoJs6 > Special app access > All files access, then retry."

    /**
     * Returns an explanation when a missing "All files access" grant could be the
     * cause, and null when it could not.
     *
     * @Note
     *  ! Returning null rather than a generic caveat is what makes this safe to
     *  ! append to any message: on a device that already has the grant, no
     *  ! misleading advice is produced.
     *  ! zh-CN: 返回 null 而不是一段泛泛的免责说明, 正是它可以被无条件拼接的原因:
     *  ! 在已获得授权的设备上不会产生误导性建议.
     */
    fun permissionHint(path: File): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (Environment.isExternalStorageManager()) return null
        if (!isOnSharedStorage(path)) return null
        return "Android is hiding this path because AutoJs6 has not been granted " +
                "\"All files access\". $remedy"
    }

    /** [message] with the storage hint appended when it is relevant. */
    fun explain(message: String, path: File): String =
        permissionHint(path)?.let { "$message $it" } ?: message

    /**
     * True when [path] sits under the shared external storage root.
     *
     * @Note
     *  ! The root is read from `Environment` rather than hardcoded as `/sdcard`,
     *  ! because the real path depends on the user and the storage volume.
     *  ! zh-CN: 存储根从 `Environment` 读取而非硬编码为 `/sdcard`,
     *  ! 因为真实路径取决于用户与存储卷.
     */
    private fun isOnSharedStorage(path: File): Boolean {
        val shared = runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull()
            ?: return false
        return path.path == shared.path || path.path.startsWith(shared.path + File.separator)
    }

}
