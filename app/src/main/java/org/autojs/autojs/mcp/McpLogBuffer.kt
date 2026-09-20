package org.autojs.autojs.mcp

import android.util.Log

/**
 * Bounded ring buffer of recent console output.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Design
 *  ! Scripts log through the console, and that stream is exactly what an AI
 *  ! client needs in order to see whether the script it launched actually
 *  ! worked. Instead of having the model poll the console view, output flowing
 *  ! through the console is mirrored here and served in a pull model, which is
 *  ! far cheaper than repeatedly re-reading a growing log.
 *  ! The buffer is bounded and lives in memory only: it is diagnostics, not a
 *  ! log store, so dropping the oldest entries is the right trade-off.
 *  ! zh-CN: 脚本通过控制台输出日志, 而这股数据流正是 AI 客户端判断"它启动的脚本是否
 *  ! 真的跑通"所需要的东西. 与其让模型反复轮询控制台视图, 不如把流经控制台的内容镜像
 *  ! 到这里, 再以拉取方式提供 —— 这比反复重读不断增长的日志便宜得多.
 *  ! 缓冲区有上限且仅存在于内存中: 它是诊断信息而非日志存储,
 *  ! 因此丢弃最旧条目是正确的取舍.
 */
internal object McpLogBuffer {

    private const val CAPACITY = 500

    class Entry(
        val sequence: Long,
        val timestamp: Long,
        val level: String,
        val message: String,
    )

    private val lock = Any()

    private val entries = ArrayDeque<Entry>(CAPACITY)

    private var sequence = 0L

    /**
     * Appends one console line. Called from the console print path, which may be
     * any thread, hence the lock.
     * zh-CN: 追加一行控制台输出. 由控制台打印路径调用, 可能来自任意线程, 因此需要加锁.
     */
    fun append(level: Int, message: CharSequence) {
        val text = message.toString().trimEnd()
        if (text.isEmpty()) return
        synchronized(lock) {
            if (entries.size >= CAPACITY) {
                entries.removeFirst()
            }
            entries.addLast(
                Entry(
                    sequence = ++sequence,
                    timestamp = System.currentTimeMillis(),
                    level = levelName(level),
                    message = text,
                )
            )
        }
    }

    /**
     * @param afterSequence only return entries newer than this, so a client can
     * poll incrementally by passing the `latestSequence` it saw last time.
     * @param limit keep at most this many entries, counted from the newest end.
     */
    fun snapshot(afterSequence: Long?, limit: Int): List<Entry> = synchronized(lock) {
        val selected = when (afterSequence) {
            null -> entries.toList()
            else -> entries.filter { it.sequence > afterSequence }
        }
        if (limit in 1 until selected.size) selected.takeLast(limit) else selected
    }

    fun latestSequence(): Long = synchronized(lock) { sequence }

    fun clear() = synchronized(lock) { entries.clear() }

    val capacity: Int get() = CAPACITY

    /**
     * The console passes android.util.Log level constants, not its own enum.
     * zh-CN: 控制台传入的是 android.util.Log 的级别常量, 而非自定义枚举.
     */
    private fun levelName(level: Int): String = when (level) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        Log.ASSERT -> "A"
        else -> "?"
    }

}
