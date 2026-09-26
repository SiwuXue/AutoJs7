package org.autojs.autojs.mcp.tools

import android.os.Process as AndroidProcess
import org.autojs.autojs.util.ProcessUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/** A bounded-output shell runner whose process remains reachable on timeout. */
internal object ManagedShellProcess {
    private const val MAX_OUTPUT_BYTES = 1024 * 1024

    data class Outcome(
        val code: Int?,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val outputTruncated: Boolean,
    )

    @Throws(InterruptedException::class)
    fun run(command: String, withRoot: Boolean, timeoutMs: Int, shellExecutable: String? = null): Outcome {
        val process = ProcessBuilder(shellExecutable ?: if (withRoot) "su" else "sh", "-c", command).start()
        val stdout = LimitedOutput()
        val stderr = LimitedOutput()
        val outThread = drain(process.inputStream, stdout, "mcp-shell-stdout")
        val errThread = drain(process.errorStream, stderr, "mcp-shell-stderr")
        val exitCode = AtomicInteger(Int.MIN_VALUE)
        val waiter = Thread({
            runCatching { process.waitFor() }.onSuccess(exitCode::set)
        }, "mcp-shell-wait").apply { isDaemon = true; start() }

        var timedOut = false
        try {
            waiter.join(timeoutMs.toLong())
            timedOut = waiter.isAlive
            if (timedOut) terminate(process)
            waiter.join(1_000)
        } catch (e: InterruptedException) {
            terminate(process)
            throw e
        } finally {
            if (process.isAlive) terminate(process)
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            outThread.join(1_000)
            errThread.join(1_000)
        }
        return Outcome(
            code = exitCode.get().takeUnless { it == Int.MIN_VALUE || timedOut },
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = timedOut,
            outputTruncated = stdout.truncated || stderr.truncated,
        )
    }

    private fun drain(input: InputStream, target: LimitedOutput, name: String) = Thread({
        runCatching {
            val bytes = ByteArray(8192)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                target.append(bytes, count)
            }
        }
    }, name).apply { isDaemon = true; start() }

    private fun terminate(process: Process) {
        // /proc exposes descendants on Android when they share our UID. Detached
        // processes and root-owned children may not be accessible; both are best effort.
        val pid = ProcessUtils.getProcessPid(process)
        if (pid > 0) descendantsOf(pid, mutableSetOf()).asReversed().forEach { child ->
            runCatching { AndroidProcess.killProcess(child) }
        }
        process.destroy()
        if (process.isAlive) runCatching { process.destroyForcibly() }
    }

    private fun descendantsOf(pid: Int, seen: MutableSet<Int>): List<Int> {
        if (!seen.add(pid) || seen.size > 256) return emptyList()
        val children = runCatching { File("/proc/$pid/task/$pid/children").readText() }
            .getOrDefault("")
            .splitToSequence(' ', '\n', '\t')
            .mapNotNull(String::toIntOrNull)
            .toList()
        return children.flatMap { child -> descendantsOf(child, seen) + child }
    }

    private class LimitedOutput {
        private val data = ByteArrayOutputStream()
        @Volatile var truncated = false
            private set

        @Synchronized
        fun append(bytes: ByteArray, count: Int) {
            val remaining = MAX_OUTPUT_BYTES - data.size()
            if (remaining > 0) data.write(bytes, 0, count.coerceAtMost(remaining))
            if (count > remaining) truncated = true
        }

        @Synchronized
        fun text(): String = data.toString(Charsets.UTF_8.name())
    }
}
