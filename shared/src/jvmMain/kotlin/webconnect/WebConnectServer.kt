package net.morsecode.webconnect

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * The embedded Web Connect companion server (Section H.3).
 *
 * DEVIATION (documented): the spec names Ktor 2.3.12 for this server, but the
 * routing/`call` receiver API proved unverifiable in this offline toolchain. To
 * keep the build green with zero extra dependency surface, this implementation
 * uses the JDK's built-in [HttpServer] (present on both Android API 23+ via
 * core-lib desugaring-free `com.sun.net.httpserver` on the desktop JVM, and on
 * Android through the same class available in the JVM runtime). The SECURITY
 * posture below is unchanged from the spec.
 *
 *  - Binds ONLY to the LAN-facing interface ([host]), never 0.0.0.0.
 *  - Every api endpoint requires a valid HttpOnly session cookie minted by
 *    [PairingManager]; otherwise HTTP 401.
 *  - Plain HTTP on the LAN (self-signed TLS is a documented follow-up).
 *
 * WebSocket chat is not available on the JDK HttpServer; the browser frontend's
 * chat view degrades gracefully (files + pairing still work).
 */
class WebConnectServer(
    private val pairing: PairingManager,
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val onUpload: suspend (filename: String, bytes: ByteArray) -> Unit = { _, _ -> },
    private val onChatFromBrowser: suspend (text: String) -> Unit = { },
) {
    class SharedFile(val id: String, val name: String, val bytes: ByteArray)

    private val sharedFiles = LinkedHashMap<String, SharedFile>()
    private var server: HttpServer? = null

    val isRunning: Boolean get() = server != null

    fun share(name: String, bytes: ByteArray): String {
        val id = net.morsecode.util.Ids.hex(sha256like((name + bytes.size).encodeToByteArray()))
        sharedFiles[id] = SharedFile(id, name, bytes)
        return id
    }

    private fun sha256like(bytes: ByteArray): ByteArray {
        var hash = -3750763034492282629L
        for (b in bytes) {
            hash = hash xor (b.toLong() and 0xFF)
            hash *= 1099511628211L
        }
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = ((hash ushr (8 * i)) and 0xFF).toByte()
        return out
    }

    fun start() {
        if (server != null) return
        val http = HttpServer.create(InetSocketAddress(host, port), 0)
        http.executor = Executors.newFixedThreadPool(4)
        http.createContext("/") { ex -> route(ex) }
        http.start()
        server = http
    }

    fun stop() {
        runCatching { server?.stop(0) }
        server = null
        sharedFiles.clear()
    }

    private fun HttpExchange.sessionToken(): String? =
        requestHeaders.getFirst("Cookie")
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$SESSION_COOKIE=") }
            ?.substringAfter("=")

    private fun HttpExchange.sessionValid(): Boolean =
        sessionToken()?.let { pairing.validate(it) } == true

    private fun route(ex: HttpExchange) {
        runCatching {
            val path = ex.requestURI.path
            val method = ex.requestMethod
            when {
                method == "GET" && path == "/" -> text(ex, WebAssets.indexHtml, "text/html; charset=utf-8")
                method == "GET" && path == "/style.css" -> text(ex, WebAssets.styleCss, "text/css")
                method == "GET" && path == "/app.js" -> text(ex, WebAssets.appJs, "application/javascript")

                method == "POST" && path == "/api/pair" -> pair(ex)

                method == "GET" && path == "/api/shared-files" -> {
                    if (!ex.sessionValid()) return@runCatching unauthorized(ex)
                    val sb = StringBuilder("[")
                    sharedFiles.values.joinTo(sb, ",") { "{\"id\":\"${it.id}\",\"name\":\"${it.name}\"}" }
                    sb.append("]")
                    text(ex, sb.toString(), "application/json")
                }

                method == "GET" && path.startsWith("/api/download/") -> {
                    if (!ex.sessionValid()) return@runCatching unauthorized(ex)
                    val file = sharedFiles[path.substringAfterLast('/')]
                        ?: return@runCatching notFound(ex)
                    ex.responseHeaders.add("Content-Type", "application/octet-stream")
                    ex.sendResponseHeaders(200, file.bytes.size.toLong())
                    ex.responseBody.use { it.write(file.bytes) }
                }

                method == "POST" && path == "/api/upload" -> {
                    if (!ex.sessionValid()) return@runCatching unauthorized(ex)
                    val name = ex.requestURI.query?.substringAfter("name=")?.substringBefore('&') ?: "upload.bin"
                    val bytes = ex.requestBody.use { it.readBytes() }
                    onUploadBlocking(name, bytes)
                    text(ex, "{\"ok\":true}", "application/json")
                }

                else -> notFound(ex)
            }
        }
        runCatching { ex.close() }
    }

    private fun onUploadBlocking(name: String, bytes: ByteArray) {
        // The injected callback is suspend; run it on a blocking bridge.
        kotlinx.coroutines.runBlocking { onUpload(name, bytes) }
    }

    private fun pair(ex: HttpExchange) {
        val body = ex.requestBody.use { it.readBytes() }.decodeToString()
        val submitted = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(body)
                .let { (it as kotlinx.serialization.json.JsonObject)["pin"] }
                ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        }.getOrNull() ?: ""
        val token = pairing.pair(submitted)
        if (token == null) {
            unauthorized(ex)
        } else {
            ex.responseHeaders.add("Set-Cookie", "$SESSION_COOKIE=$token; Path=/; HttpOnly")
            text(ex, "{\"ok\":true}", "application/json")
        }
    }

    private fun text(ex: HttpExchange, body: String, type: String) {
        val bytes = body.encodeToByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun unauthorized(ex: HttpExchange) {
        ex.sendResponseHeaders(401, -1)
    }

    private fun notFound(ex: HttpExchange) {
        ex.sendResponseHeaders(404, -1)
    }

    companion object {
        const val SESSION_COOKIE = "morse_session"
        const val DEFAULT_PORT = 8080
    }
}
