package dev.pocketagent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpHttpServerTest {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var now = 1_000_000L
    private var slow = false
    private val asked = ArrayList<String>()
    private val handler = McpHandler { task ->
        asked.add(task)
        if (slow) delay(5_000)
        TaskReply.Done(true, "done")
    }
    private lateinit var server: McpHttpServer
    private val token = "secret-token-1234"
    private val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
    private val call = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"run_task","arguments":{"task":"hello"}}}"""

    @Before fun start() {
        server = McpHttpServer(0, { token }, handler, scope, nowMs = { now }, runLimitMs = 500)
        assertTrue(server.start())
    }

    @After fun stop() {
        server.stop()
        scope.cancel()
    }

    /** Sends one raw request and returns the status code and body. */
    private fun send(
        method: String = "POST",
        path: String = "/mcp",
        auth: String? = "Bearer $token",
        body: String = ping,
        extra: String = "",
        length: Int = body.toByteArray().size,
    ): Pair<Int, String> = Socket("127.0.0.1", server.boundPort).use { s ->
        s.soTimeout = 10_000
        val head = "$method $path HTTP/1.1\r\nHost: phone\r\n" + (auth?.let { "Authorization: $it\r\n" } ?: "") + extra +
            "Content-Length: $length\r\n\r\n"
        s.getOutputStream().write(head.toByteArray() + body.toByteArray())
        s.getOutputStream().flush()
        val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
        text.substringAfter("HTTP/1.1 ").substringBefore(' ').toInt() to text.substringAfter("\r\n\r\n", "")
    }

    @Test fun anAuthorisedCallIsAnswered() {
        val (code, body) = send(body = call)
        assertEquals(200, code)
        assertTrue(body.contains("\"done\""), body)
        assertEquals(listOf("hello"), asked)
    }

    @Test fun aNotificationIsAcceptedWithNoBody() {
        val (code, body) = send(body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, code)
        assertEquals("", body)
    }

    @Test fun missingOrWrongTokensAreRefusedAndNothingRuns() {
        assertEquals(401, send(auth = null, body = call).first)
        assertEquals(401, send(auth = "Bearer nope", body = call).first)
        assertEquals(401, send(auth = "Basic $token", body = call).first)
        assertTrue(asked.isEmpty())
    }

    @Test fun fiveWrongTokensLockEveryoneOutForAMinute() {
        repeat(5) { assertEquals(401, send(auth = "Bearer nope").first) }
        assertEquals(429, send().first, "even the right token is refused while locked")
        now += McpHttpServer.LOCKOUT_MS + 1
        assertEquals(200, send().first)
    }

    @Test fun aRightTokenResetsTheFailureCount() {
        repeat(4) { send(auth = "Bearer nope") }
        assertEquals(200, send().first)
        repeat(4) { send(auth = "Bearer nope") }
        assertEquals(200, send().first)
    }

    @Test fun aBrowserRequestWithAnOriginIsRefused() {
        assertEquals(403, send(extra = "Origin: https://evil.example\r\n").first)
        assertTrue(asked.isEmpty())
    }

    @Test fun onlyPostToMcpIsServed() {
        assertEquals(405, send(method = "GET").first)
        assertEquals(404, send(path = "/other").first)
    }

    @Test fun anOversizedBodyIsRefusedBeforeItIsRead() {
        assertEquals(400, send(body = "", length = McpHttpServer.MAX_BODY + 1).first)
    }

    @Test fun aTaskThatRunsTooLongGetsATimeout() {
        slow = true
        assertEquals(504, send(body = call).first)
    }

    @Test fun stoppingClosesThePort() {
        repeat(20) { cycle ->
            if (cycle > 0) assertTrue(server.start())
            val port = server.boundPort
            server.stop()
            // Closing wakes accept() on its IO dispatcher; the OS may release the listener a little later.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var refused = portRefusesConnection(port)
            while (!refused && System.nanoTime() < deadline) {
                Thread.sleep(10)
                refused = portRefusesConnection(port)
            }
            assertTrue(refused, "停止后端口 $port 未在 2 秒内关闭，第 ${cycle + 1} 次启停")
        }
    }

    private fun portRefusesConnection(port: Int): Boolean = Socket().use { probe ->
        probe.bind(InetSocketAddress("127.0.0.1", 0))
        // Linux can connect an ephemeral socket to itself. Such a probe does not test the server.
        if (probe.localPort == port) return@use false
        try {
            probe.connect(InetSocketAddress("127.0.0.1", port), 1_000)
            false
        } catch (_: ConnectException) {
            true
        }
    }
}
