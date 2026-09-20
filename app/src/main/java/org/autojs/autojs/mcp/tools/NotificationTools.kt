package org.autojs.autojs.mcp.tools

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.autojs.autojs.AutoJs
import org.autojs.autojs.core.notification.NotificationListenerService
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpArgumentException
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.mcp.McpUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read and dismiss posted notifications.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Why
 *  ! Notification content is a channel the view hierarchy cannot reach: the
 *  ! shade is a system window, so `dump_ui_tree` only sees it when the shade is
 *  ! already open. These tools read the posted notifications directly, which
 *  ! makes flows like "wait for the verification code, then type it" possible
 *  ! without ever opening the shade.
 *  ! zh-CN: 通知内容是无障碍视图树够不到的通道: 通知栏属于系统窗口,
 *  ! `dump_ui_tree` 只有在通知栏已展开时才能看到它. 这两个工具直接读取已发布的通知,
 *  ! 使"等待验证码再输入"此类流程无需展开通知栏即可完成.
 */
internal object McpNotificationTools {

    val tools: List<McpTool> = listOf(
        readNotificationsTool(), dismissNotificationTool(), waitForNotificationTool(),
    )

    private const val DEFAULT_LIMIT = 30

    private const val MAX_LIMIT = 200

    private const val MAX_TEXT_CHARS = 400

    private const val REBIND_WAIT_MS = 1_500L

    private const val POLL_MS = 100L

    private const val DISMISS_VERIFY_MS = 600L

    private fun readNotificationsTool(): McpTool = McpTool(
        name = "read_notifications",
        title = "Read posted notifications",
        description = buildString {
            append("Returns the notifications currently posted in the shade, newest first, with the app that posted ")
            append("each one plus its title and body text. ")
            append("This reads the notification objects themselves rather than the shade UI, so it works without ")
            append("opening the status bar and it sees notifications the user has never scrolled to. ")
            append("Requires notification access to be granted to AutoJs6; the error says so when it is missing. ")
            append("The content is whatever other apps chose to publish, so expect private data such as messages ")
            append("and one-time codes to appear in the result.")
        },
        risk = McpToolRisk.SAFE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "packageName" to McpSchema.string("Only notifications posted by this package, e.g. `com.tencent.mm`."),
                "query" to McpSchema.string(
                    "Case insensitive substring matched against the title, the body text and the package name.",
                ),
                "includeOngoing" to McpSchema.boolean(
                    "Include ongoing notifications such as music players and downloads.", true,
                ),
                "onlyClearable" to McpSchema.boolean(
                    "Keep only notifications the user is able to swipe away.", false,
                ),
                "limit" to McpSchema.integer("Maximum notifications to return.", DEFAULT_LIMIT, 1, MAX_LIMIT),
            ),
        ),
    ) { args -> invokeReadNotifications(args) }

    private fun invokeReadNotifications(args: McpArgs): McpToolResult {
        val packageFilter = args.optString("packageName")?.takeIf { it.isNotBlank() }
        val query = args.optString("query")?.takeIf { it.isNotBlank() }?.lowercase()
        val includeOngoing = args.optBoolean("includeOngoing", true)
        val onlyClearable = args.optBoolean("onlyClearable", false)
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val posted = activeNotifications()

        // Sorted here rather than trusting the system's ordering, which is
        // posting order on some versions and not on others.
        // zh-CN: 在此处排序而非依赖系统顺序, 后者在某些版本上是发布顺序, 在另一些版本上则不是.
        val matched = posted
            .sortedByDescending { it.postTime }
            .filter { includeOngoing || !it.isOngoing }
            .filter { !onlyClearable || it.isClearable }
            .filter { packageFilter == null || it.packageName == packageFilter }
            .map { it to Snapshot.of(it) }
            .filter { (sbn, snapshot) ->
                query == null ||
                        snapshot.title.lowercase().contains(query) ||
                        snapshot.text.lowercase().contains(query) ||
                        sbn.packageName.lowercase().contains(query)
            }
            .toList()

        val limited = matched.take(limit)
        val timeFormat = newTimeFormat()

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("count", limited.size)
            addProperty("totalPosted", posted.size)
            if (matched.size > limited.size) {
                addProperty("truncated", true)
            }
            if (posted.isEmpty()) {
                addProperty("hint", "No notifications are currently posted.")
            }
            add("notifications", JsonArray().apply {
                limited.forEach { (sbn, snapshot) -> add(describe(sbn, snapshot, timeFormat)) }
            })
        })
    }

    private fun dismissNotificationTool(): McpTool = McpTool(
        name = "dismiss_notification",
        title = "Dismiss a notification",
        description = buildString {
            append("Removes one notification from the shade, identified by the `key` field returned from ")
            append("`read_notifications`. ")
            append("Ongoing notifications cannot be dismissed, and the system silently ignores the attempt instead ")
            append("of reporting an error -- so this tool re-reads the shade afterwards and reports whether the ")
            append("notification actually went away. ")
            append("Always call `read_notifications` first: the key encodes the package and is not guessable.")
        },
        risk = McpToolRisk.SENSITIVE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "key" to McpSchema.string("The `key` field of a notification returned by `read_notifications`."),
            ),
            required = listOf("key"),
        ),
    ) { args -> invokeDismissNotification(args) }

    private fun invokeDismissNotification(args: McpArgs): McpToolResult {
        val key = args.requireString("key")
        val service = requireListener()

        val target = activeNotifications().firstOrNull { it.key == key }
            ?: return McpToolResult.error(
                "No posted notification has the key `$key`. Call `read_notifications` again: " +
                        "the key changes whenever a notification is reposted."
            )

        if (!target.isClearable) {
            return McpToolResult.error(
                "The notification from `${target.packageName}` is ongoing and cannot be dismissed."
            )
        }

        val attempted = runCatching { service.cancelNotification(key) }
        if (attempted.isFailure) {
            val error = attempted.exceptionOrNull()!!
            return McpToolResult.error(
                "Dismissing failed: ${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        }

        // cancelNotification() returns void and reports nothing, not even when it
        // does nothing at all. Re-reading the shade is the only way to give the
        // caller an answer that is actually true.
        // zh-CN: cancelNotification() 返回 void 且不报告任何信息, 连它什么都没做时也不报告.
        // 重新读取通知栏是唯一能向调用方给出真实结论的办法.
        val deadline = System.currentTimeMillis() + DISMISS_VERIFY_MS
        var stillPosted = true
        while (System.currentTimeMillis() < deadline) {
            if (activeNotifications().none { it.key == key }) {
                stillPosted = false
                break
            }
            Thread.sleep(POLL_MS)
        }

        return McpToolResult.json(McpJson.obj().apply {
            addProperty("ok", !stillPosted)
            addProperty("key", key)
            addProperty("packageName", target.packageName)
            if (stillPosted) {
                addProperty(
                    "hint",
                    "The notification is still posted. The app that posted it may have reposted it immediately, " +
                            "or the system refused the dismissal.",
                )
            }
        })
    }

    /**
     * @throws IllegalStateException when notification access is missing or the
     * listener service could not be reached, carrying a message the AI can act
     * on rather than a bare failure.
     * zh-CN: 当缺少通知使用权, 或无法连接监听服务时抛出, 携带 AI 可据此行动的消息,
     * 而不是一个空洞的失败.
     */
    private fun requireListener(): NotificationListenerService {
        val context = McpUi.context

        if (!NotificationListenerService.isNotificationListenerEnabled(context)) {
            throw IllegalStateException(
                "Notification access has not been granted to AutoJs6. Grant it in system settings under " +
                        "Notifications > Device & app notifications, then try again."
            )
        }

        var instance = NotificationListenerService.instance
        if (instance == null) {
            // The grant can survive while the service itself has been killed by
            // the system, so a rebind is attempted before giving up.
            // zh-CN: 授权可能仍然有效, 而服务本身已被系统杀死, 因此在放弃前先尝试重新绑定.
            NotificationListenerService.requestRebindIfPossible(context)
            val deadline = System.currentTimeMillis() + REBIND_WAIT_MS
            while (instance == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_MS)
                instance = NotificationListenerService.instance
            }
        }

        return instance ?: throw IllegalStateException(
            "Notification access is granted but the listener service is not connected. " +
                    "Toggling notification access off and on again usually revives it."
        )
    }

    /**
     * Reads the posted notifications.
     *
     * @Note
     *  ! The call throws `SecurityException` when the listener has been
     *  ! disconnected behind the caller's back. Returning an empty array would
     *  ! report "no notifications" for what is really a permission problem -- a
     *  ! lie the caller cannot detect -- so the failure is surfaced instead.
     *  ! zh-CN: 当监听器在调用方不知情的情况下断连时, 该调用会抛出 `SecurityException`.
     *  ! 若返回空数组, 权限问题就会被报告为"没有通知", 这是调用方无法察觉的谎言,
     *  ! 因此这里选择把失败暴露出来.
     */
    private fun activeNotifications(): List<StatusBarNotification> {
        val service = requireListener()
        // The whole read sits inside `runCatching`, `toList` included. Calling
        // `toList` outside would step past the guard for the case where the
        // platform returns null after all, which turns a permission problem back
        // into a null pointer crash.
        // zh-CN: 整个读取过程都放在 `runCatching` 内, 包括 `toList`.
        // 若把 `toList` 放在外面, 一旦平台真的返回空值就会绕过防护,
        // 把权限问题重新变成空指针崩溃.
        return runCatching { service.activeNotifications.toList() }
            .getOrElse {
                throw IllegalStateException(
                    "Reading the posted notifications failed: " +
                            "${it::class.java.simpleName}: ${it.message ?: "no message"}. " +
                            "The listener connection may have dropped; toggling notification access usually fixes it."
                )
            }
    }

    /** The text fields of one notification, extracted once and reused. */
    private class Snapshot(val title: String, val text: String) {
        companion object {

            fun of(sbn: StatusBarNotification): Snapshot {
                val extras = sbn.notification.extras
                // Only constants declared on `android.app.Notification` are
                // usable here. `EXTRA_TICKER_TEXT` looks like it belongs to this
                // family but actually lives on `NotificationCompat`, and
                // `EXTRA_SUMMARY_TEXT` stands in for it as the last fallback.
                // zh-CN: 这里只能使用声明在 `android.app.Notification` 上的常量.
                // `EXTRA_TICKER_TEXT` 看上去属于这一族, 实际却在 `NotificationCompat` 上;
                // 这里用 `EXTRA_SUMMARY_TEXT` 作为最后的兜底.
                val candidates = listOf<CharSequence?>(
                    extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
                    extras.getCharSequence(Notification.EXTRA_TEXT),
                    extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
                    extras.getCharSequence(Notification.EXTRA_INFO_TEXT),
                    extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT),
                )
                return Snapshot(
                    title = truncate(extras.getCharSequence(Notification.EXTRA_TITLE)),
                    // EXTRA_BIG_TEXT holds the expanded form of a message, so
                    // preferring it returns the whole message rather than its
                    // first line. That is why the order above is not the order
                    // the constants are declared in.
                    // zh-CN: EXTRA_BIG_TEXT 保存消息的展开形态, 因此优先使用它可以拿到
                    // 完整消息而非首行. 这也是上面顺序与常量声明顺序不一致的原因.
                    text = truncate(candidates.firstOrNull { !it.isNullOrBlank() }),
                )
            }

            private fun truncate(value: CharSequence?): String {
                val text = value?.toString()?.trim().orEmpty()
                return if (text.length <= MAX_TEXT_CHARS) text else text.take(MAX_TEXT_CHARS) + "..."
            }

        }

    }

    private fun describe(
        sbn: StatusBarNotification,
        snapshot: Snapshot,
        timeFormat: SimpleDateFormat,
    ): JsonObject = McpJson.obj().apply {
        addProperty("key", sbn.key)
        addProperty("packageName", sbn.packageName)
        addProperty("appName", runCatching { AutoJs.instance.appUtils.getAppName(sbn.packageName) }.getOrNull())
        if (snapshot.title.isNotEmpty()) addProperty("title", snapshot.title)
        if (snapshot.text.isNotEmpty()) addProperty("text", snapshot.text)
        addProperty("postedAt", timeFormat.format(Date(sbn.postTime)))
        addProperty("isOngoing", sbn.isOngoing)
        addProperty("isClearable", sbn.isClearable)

        val notification = sbn.notification
        notification.category?.let { addProperty("category", it) }
        notification.channelId?.let { addProperty("channelId", it) }

        val actions = notification.actions
        if (actions != null && actions.isNotEmpty()) {
            add("actions", JsonArray().apply {
                actions.forEach { action ->
                    action?.title?.toString()?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            })
        }
    }

    /**
     * A fresh formatter per call. `SimpleDateFormat` is not thread safe and this
     * object is shared by concurrent requests.
     * zh-CN: 每次调用都新建格式化器. `SimpleDateFormat` 并非线程安全, 而本对象会被
     * 并发请求共享.
     */
    // ------------------------------------------------------ wait_for_notification

    private const val DEFAULT_WAIT_MS = 10_000

    private const val MAX_WAIT_MS = 60_000

    private const val WAIT_POLL_MS = 300L

    private fun waitForNotificationTool(): McpTool = McpTool(
        name = "wait_for_notification",
        title = "Wait for a notification",
        description = buildString {
            append("Blocks until a notification matching the filters is posted, or the timeout elapses. ")
            append("This is the building block for verification-code and approval flows: wait for the message, ")
            append("read the code out of it, act on it -- all without the user leaving their current app. ")
            append("A notification that is already posted counts as a match immediately, so poll `read_notifications` ")
            append("first and note the existing keys if only a *newly arriving* one will do. ")
            append("Matching follows the same rules as `read_notifications`: `packageName` is exact, `query` is a ")
            append("case insensitive substring over title, body and package.")
        },
        risk = McpToolRisk.SAFE,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "packageName" to McpSchema.string("Only notifications posted by this package, e.g. `com.tencent.mm`."),
                "query" to McpSchema.string(
                    "Case insensitive substring matched against the title, the body text and the package name.",
                ),
                "timeoutMs" to McpSchema.integer(
                    "How long to wait, in milliseconds.",
                    DEFAULT_WAIT_MS, 0, MAX_WAIT_MS,
                ),
                "includeOngoing" to McpSchema.boolean(
                    "Also match ongoing notifications such as download progress.", false,
                ),
            ),
        ),
    ) { args -> invokeWaitForNotification(args) }

    private fun invokeWaitForNotification(args: McpArgs): McpToolResult {
        val packageFilter = args.optString("packageName")?.takeIf { it.isNotBlank() }
        val query = args.optString("query")?.takeIf { it.isNotBlank() }?.lowercase()
        val timeoutMs = args.optInt("timeoutMs", DEFAULT_WAIT_MS).coerceIn(0, MAX_WAIT_MS)
        val includeOngoing = args.optBoolean("includeOngoing", false)

        if (packageFilter == null && query == null) {
            throw McpArgumentException(
                "At least one filter is required (`packageName` or `query`); waiting for *any* notification " +
                        "would match the stream of system chatter almost immediately."
            )
        }

        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + timeoutMs
        var matched: Pair<StatusBarNotification, Snapshot>? = null

        while (System.currentTimeMillis() <= deadline) {
            matched = activeNotifications()
                .sortedByDescending { it.postTime }
                .filter { includeOngoing || !it.isOngoing }
                .filter { packageFilter == null || it.packageName == packageFilter }
                .map { it to Snapshot.of(it) }
                .firstOrNull { (notification, snapshot) ->
                    query == null ||
                            snapshot.title.lowercase().contains(query) ||
                            snapshot.text.lowercase().contains(query) ||
                            notification.packageName.lowercase().contains(query)
                }
            if (matched != null) break
            if (System.currentTimeMillis() >= deadline) break
            Thread.sleep(WAIT_POLL_MS)
        }

        val elapsedMs = (System.currentTimeMillis() - startedAt).toInt()
        return McpToolResult.json(McpJson.obj().apply {
            addProperty("hit", matched != null)
            addProperty("elapsedMs", elapsedMs)
            addProperty("timeoutMs", timeoutMs)
            if (matched != null) {
                add("notification", describe(matched.first, matched.second, newTimeFormat()))
            } else {
                addProperty(
                    "hint",
                    "Nothing matching arrived within ${timeoutMs}ms. The filters may be too strict, or the " +
                            "notification may have been posted before the wait started -- " +
                            "check with `read_notifications`.",
                )
            }
        })
    }

    private fun newTimeFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

}
