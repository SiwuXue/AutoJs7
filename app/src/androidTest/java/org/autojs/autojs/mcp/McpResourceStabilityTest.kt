package org.autojs.autojs.mcp

import org.autojs.autojs.mcp.tools.ManagedShellProcess
import org.autojs.autojs.mcp.tools.McpShellTools
import org.autojs.autojs.runtime.api.AbstractShell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class McpResourceStabilityTest {
    private val server = McpHttpServer(0, InetAddress.getByName("127.0.0.1"))

    @After fun tearDown() = server.stop()

    @Test fun connectionLimitAndStopCloseSockets() {
        server.start()
        val sockets = (1..16).map { Socket("127.0.0.1", server.localPort).apply { soTimeout = 3_000 } }
        try {
            await { server.connectedClientCount() == 16 }
            Socket("127.0.0.1", server.localPort).use { extra ->
                extra.soTimeout = 3_000
                assertEquals(-1, extra.getInputStream().read())
            }
            assertEquals(16, server.connectedClientCount())
            server.stop()
            assertEquals(0, server.connectedClientCount())
            sockets.forEach { assertEquals(-1, it.getInputStream().read()) }
        } finally {
            sockets.forEach { it.close() }
        }
    }

    @Test fun oversizedHeadersAndTooManyFieldsAreClosed() {
        server.start()
        val oversized = "GET /mcp HTTP/1.1\r\n" +
                (1..5).joinToString("") { "X-$it: ${"a".repeat(15_000)}\r\n" } + "\r\n"
        assertRejected(oversized)
        val tooMany = "GET /mcp HTTP/1.1\r\n" +
                (1..101).joinToString("") { "X-$it: a\r\n" } + "\r\n"
        assertRejected(tooMany)
        await { server.connectedClientCount() == 0 }
    }

    @Test fun repeatedStartStopNeverProducesNegativeCount() {
        repeat(5) {
            server.start()
            Socket("127.0.0.1", server.localPort).use { socket ->
                await { server.connectedClientCount() == 1 }
                server.stop()
                assertEquals(0, server.connectedClientCount())
                socket.soTimeout = 3_000
                assertEquals(-1, socket.getInputStream().read())
            }
        }
    }

    @Test fun shellCompletesTimesOutAndBoundsLargeOutput() {
        val normal = ManagedShellProcess.run("printf hello", false, 3_000)
        assertFalse(normal.timedOut)
        assertEquals(0, normal.code)
        assertEquals("hello", normal.stdout)

        val started = android.os.SystemClock.elapsedRealtime()
        val timeout = ManagedShellProcess.run("sleep 10", false, 100)
        assertTrue(timeout.timedOut)
        assertTrue("timeout cleanup took too long", android.os.SystemClock.elapsedRealtime() - started < 3_000)

        val large = ManagedShellProcess.run("head -c 2000000 /dev/zero", false, 5_000)
        assertEquals(0, large.code)
        assertTrue(large.outputTruncated)
        assertTrue(large.stdout.length <= 1024 * 1024)
    }

    @Test fun shellLaunchFailureIsReported() {
        val failure = runCatching {
            ManagedShellProcess.run("true", false, 1_000, "/nonexistent/mcp-shell")
        }
        assertTrue(failure.isFailure)
    }

    @Test fun shizukuTimeoutKeepsGateUntilUnderlyingCallFinishes() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val args = McpArgs(com.google.gson.JsonObject().apply {
            addProperty("command", "test")
            addProperty("timeoutMs", 100)
        })
        val timedOut = McpShellTools.invokeShizuku(args) {
            entered.countDown()
            release.await(3, TimeUnit.SECONDS)
            AbstractShell.Result(0, "done")
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertTrue(timedOut.toJson().toString().contains("timedOut"))
        val busy = McpShellTools.invokeShizuku(args) { AbstractShell.Result(0, "unexpected") }
        assertTrue(busy.isError)
        assertTrue(busy.toJson().toString().contains("SHIZUKU_BUSY"))
        release.countDown()
        await {
            !McpShellTools.invokeShizuku(args) { AbstractShell.Result(0, "ready") }.isError
        }
    }

    private fun assertRejected(request: String) {
        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            assertEquals(-1, socket.getInputStream().read())
        }
    }

    private fun await(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(30)
        }
        assertTrue("condition did not become true", condition())
    }
}
