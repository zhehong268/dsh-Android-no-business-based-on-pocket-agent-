package dev.pocketagent.auth

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

/**
 * DeepSeek account sign-in, the same flow the DeepSeek Harness desktop app uses: a PKCE challenge is registered
 * with the platform, the browser lands on the platform page, and the code comes back to a loopback redirect this
 * object is listening on. The exchange returns an account token, which the model endpoint accepts in
 * [AUTH_HEADER] instead of an API key. Nothing here is dsh specific and no secret is logged.
 */
object DeepSeekAccountLogin {
    const val PLATFORM_ORIGIN = "https://platform.deepseek.com"
    const val BASE_URL = "https://api.deepseek.com/anthropic"
    const val AUTH_HEADER = "x-dsh-auth-token"
    const val DEFAULT_MODEL = "deepseek-flash"
    const val CONTEXT_TOKENS = 1_048_576

    private const val CALLBACK_PATH = "/oauth/callback"
    private const val API = "/auth-api/v0/dsh"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000

    /** What the platform wants to know about the device that asks for the token. */
    data class Device(val id: String, val model: String, val osVersion: String)

    /**
     * A sign-in that has been registered with the platform and is waiting for the browser. Keep it alive until
     * [awaitToken] returns or the user backs out; [close] releases the loopback port.
     */
    class Pending internal constructor(
        val authorizeUrl: String,
        private val socket: ServerSocket,
        private val verifier: String,
        private val state: String,
        private val redirectUri: String,
        private val device: Device,
        private val version: String,
        private val locale: String,
    ) {
        fun close() = runCatching { socket.close() }

        /**
         * Blocks until the browser comes back to the loopback redirect, then exchanges the code for a token.
         * Throws [IOException] when the browser never comes back, the user denied the request, or the exchange fails.
         */
        fun awaitToken(timeoutMs: Int = 300_000): String {
            socket.soTimeout = timeoutMs
            try {
                socket.accept().use { client ->
                    val request = readRequestHead(client.getInputStream())
                    val target = request.substringAfter(' ').substringBefore(' ')
                    val query = target.substringAfter('?', "")
                    reply(client, GRANTED_PAGE)
                    val params = parseQuery(query)
                    val error = params["error"]
                    if (!error.isNullOrEmpty()) throw IOException("登录没有被批准。")
                    val code = params["code"].orEmpty()
                    val returned = params["state"].orEmpty()
                    if (code.isEmpty() || !constantTimeEquals(returned, state)) {
                        throw IOException("登录回传的信息不完整。")
                    }
                    return exchange(code, verifier, redirectUri, device, version, locale)
                }
            } catch (e: SocketTimeoutException) {
                throw IOException("登录超时了。")
            } finally {
                close()
            }
        }
    }

    /**
     * Registers a PKCE challenge and binds the loopback port the browser will come back to. Blocking; call off the
     * main thread.
     */
    fun prepare(version: String, locale: String, device: Device): Pending {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val verifier = randomToken(32)
        val state = randomToken(32)
        val redirectUri = "http://127.0.0.1:${socket.localPort}$CALLBACK_PATH"
        val body = JSONObject()
            .put("code_challenge", challenge(verifier))
            .put("code_challenge_method", "S256")
            .put("state", state)
            .put("redirect_uri", redirectUri)
            .put("locale", locale)
            .put("login_source", "desktop")
        val reply = try {
            post("$PLATFORM_ORIGIN$API/auth_init", body, version, locale)
        } catch (e: Exception) {
            socket.close()
            throw e
        }
        val authorizeUrl = reply.optJSONObject("biz_data")?.optString("authorize_url").orEmpty()
        if (authorizeUrl.isEmpty()) {
            socket.close()
            throw IOException("登录没能启动。")
        }
        return Pending(authorizeUrl, socket, verifier, state, redirectUri, device, version, locale)
    }

    private fun exchange(code: String, verifier: String, redirectUri: String, device: Device, version: String, locale: String): String {
        val body = JSONObject()
            .put("code", code)
            .put("code_verifier", verifier)
            .put("redirect_uri", redirectUri)
            .put("device_id", device.id)
            .put("device_model", device.model)
            .put("os_version", device.osVersion)
        val reply = post("$PLATFORM_ORIGIN$API/auth_exchange", body, version, locale)
        val token = reply.optJSONObject("biz_data")?.optString("token").orEmpty()
        if (token.isEmpty()) throw IOException("登录没有返回令牌。")
        return token
    }

    /** Posts a JSON body and returns the `data` object of the platform envelope, or throws with a fixed message. */
    private fun post(url: String, body: JSONObject, version: String, locale: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("content-type", "application/json")
            connection.setRequestProperty("accept", "application/json")
            connection.setRequestProperty("x-client-bundle-id", "")
            connection.setRequestProperty("x-client-platform", "web")
            connection.setRequestProperty("x-client-version", version)
            connection.setRequestProperty("x-client-locale", locale)
            connection.setRequestProperty(
                "x-client-timezone-offset",
                (java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000).toString(),
            )
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val text = if (status in 200..299) {
                readAll(connection.inputStream)
            } else {
                val error = connection.errorStream ?: throw IOException("登录服务器返回了 $status。")
                readAll(error)
            }
            val envelope = runCatching { JSONObject(text) }.getOrNull()
                ?: throw IOException("登录服务器返回了意外内容。")
            if (status !in 200..299 || envelope.optInt("code", -1) != 0) {
                throw IOException("登录服务器拒绝了这次请求。")
            }
            val data = envelope.optJSONObject("data") ?: throw IOException("登录服务器返回了意外内容。")
            if (data.optInt("biz_code", 0) != 0) throw IOException("登录服务器拒绝了这次请求。")
            return data
        } finally {
            connection.disconnect()
        }
    }

    private fun readRequestHead(input: InputStream): String {
        val text = StringBuilder()
        var previous = -1
        while (text.length < 8_000) {
            val b = input.read()
            if (b < 0) break
            text.append(b.toChar())
            if (b == '\n'.code && previous == '\n'.code) break
            previous = b
        }
        return text.toString().lineSequence().firstOrNull().orEmpty()
    }

    private fun reply(client: Socket, page: String) {
        val body = page.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\ncontent-type: text/html; charset=utf-8\r\n" +
            "content-length: ${body.size}\r\nconnection: close\r\n\r\n"
        runCatching {
            client.getOutputStream().use { out ->
                out.write(head.toByteArray(Charsets.US_ASCII))
                out.write(body)
                out.flush()
            }
        }
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val name = decode(pair.substringBefore('='))
            val value = decode(pair.substringAfter('=', ""))
            name to value
        }.toMap()

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun readAll(input: InputStream): String {
        val out = ByteArrayOutputStream()
        BufferedInputStream(input).use { stream ->
            val buffer = ByteArray(8_192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > 1_000_000) throw IOException("登录返回的内容太大了。")
            }
        }
        return out.toString("UTF-8")
    }

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes)
        SecureRandom().nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private val GRANTED_PAGE = """
        <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
        <body style="font-family:system-ui;margin:0;display:flex;height:100vh;align-items:center;justify-content:center;background:#f6f5f2">
        <div style="text-align:center;padding:24px">
        <h2 style="font-weight:600">Signed in</h2>
        <p style="color:#666">Close this tab and return to dsh.</p>
        </div></body>
    """.trimIndent()
}
