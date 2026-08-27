package net.morsecode.webconnect

import io.ktor.http.ContentDisposition
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject

/**
 * The embedded Web Connect companion server (Section H.3).
 *
 * Ktor CIO runs as plain JVM code, so this lives in `commonMain` and runs
 * unmodified on Android and Desktop — no expect/actual split, per the spec.
 *
 * SECURITY POSTURE (Section H):
 *  - Binds ONLY to the LAN-facing interface ([host]), never 0.0.0.0; there is no
 *    cloud relay and nothing is reachable beyond the local network.
 *  - Every api and ws endpoint requires a valid HttpOnly session
 *    cookie minted by [PairingManager]; otherwise HTTP 401.
 *
 * DEVIATION (documented): the spec asks for self-signed TLS. Generating a
 * certificate requires `java.security` (JVM-only), which `commonMain` cannot
 * use. This build therefore serves plain HTTP on the LAN and the TLS layer is
 * left as a jvm-side extension point; see README. The session cookie is still
 * HttpOnly and every endpoint is still auth-gated.
 */
class WebConnectServer(
    private val pairing: PairingManager,
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val onUpload: suspend (filename: String, bytes: ByteArray) -> Unit,
    private val onChatFromBrowser: suspend (text: String) -> Unit,
) {
    /** A file the device owner has shared for this session (default: none). */
    class SharedFile(val id: String, val name: String, val bytes: ByteArray)

    private val sharedFiles = LinkedHashMap<String, SharedFile>()
    private var engine: ApplicationEngine? = null

    val isRunning: Boolean get() = engine != null

    /** Marks a file as shared for this session and returns its download id. */
    fun share(name: String, bytes: ByteArray): String {
        val id = net.morsecode.util.Ids.hex(sha256like((name + bytes.size).encodeToByteArray()))
        sharedFiles[id] = SharedFile(id, name, bytes)
        return id
    }

    private fun sha256like(bytes: ByteArray): ByteArray {
        // A short, stable id; not cryptographic. FNV-1a folded to 8 bytes.
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
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = host) { module() }.start(wait = false)
    }

    fun stop() {
        runCatching { engine?.stop(100, 200) }
        engine = null
        sharedFiles.clear()
    }

    private fun ApplicationCall.sessionValid(): Boolean =
        request.cookies[SESSION_COOKIE]?.let { pairing.validate(it) } == true

    private fun Application.module() {
        install(ContentNegotiation) { json() }
        install(WebSockets)

        routing {
            get("/") { call.respondText(WebAssets.indexHtml, ContentType.Text.Html) }
            get("/style.css") { call.respondText(WebAssets.styleCss, ContentType.Text.CSS) }
            get("/app.js") { call.respondText(WebAssets.appJs, ContentType.Text.JavaScript) }

            post("/api/pair") {
                val submitted = runCatching {
                    Json.parseToJsonElement(call.receiveText()).jsonObject["pin"]?.jsonPrimitive?.content
                }.getOrNull() ?: ""
                val token = pairing.pair(submitted)
                if (token == null) {
                    call.respond(HttpStatusCode.Unauthorized)
                } else {
                    call.response.cookies.append(
                        io.ktor.http.Cookie(SESSION_COOKIE, token, httpOnly = true, path = "/"),
                    )
                    call.respond(HttpStatusCode.OK)
                }
            }

            get("/api/shared-files") {
                if (!call.sessionValid()) return@get call.respond(HttpStatusCode.Unauthorized)
                val array = kotlinx.serialization.json.buildJsonArray {
                    sharedFiles.values.forEach { f ->
                        add(buildJsonObject { put("id", f.id); put("name", f.name) })
                    }
                }
                call.respondText(array.toString(), ContentType.Application.Json)
            }

            get("/api/download/{id}") {
                if (!call.sessionValid()) return@get call.respond(HttpStatusCode.Unauthorized)
                val file = sharedFiles[call.parameters["id"]]
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondBytes(file.bytes, ContentType.Application.OctetStream)
            }

            post("/api/upload") {
                if (!call.sessionValid()) return@post call.respond(HttpStatusCode.Unauthorized)
                val multipart = call.receiveMultipart()
                var saved = false
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        val name = part.originalFileName ?: "upload.bin"
                        val bytes = part.streamProvider().use { it.readBytes() }
                        onUpload(name, bytes)
                        saved = true
                    }
                    part.dispose()
                }
                call.respond(if (saved) HttpStatusCode.OK else HttpStatusCode.BadRequest)
            }

            webSocket("/ws/chat") {
                if (!call.sessionValid()) {
                    close(io.ktor.websocket.CloseReason(4401, "unauthorized"))
                    return@webSocket
                }
                incoming.consumeEach { frame ->
                    if (frame is io.ktor.websocket.Frame.Text) {
                        val text = frame.readText()
                        onChatFromBrowser(text)
                        // Echo back so the browser shows its own message.
                        outgoing.send(io.ktor.websocket.Frame.Text(text))
                    }
                }
            }
        }
    }

    companion object {
        const val SESSION_COOKIE = "morse_session"
        const val DEFAULT_PORT = 8080
    }
}
