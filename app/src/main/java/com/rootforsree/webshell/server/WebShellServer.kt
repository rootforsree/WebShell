package com.rootforsree.webshell.server

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import java.io.IOException
import java.net.ServerSocket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a started server hands back to the UI. */
data class ServerInfo(
    val port: Int,
    val token: String,
    val addresses: List<LanAddress>,
)

@Serializable
private data class ControlMessage(
    val type: String,
    val cols: Int = 0,
    val rows: Int = 0,
)

/**
 * Ktor (CIO) server: one xterm.js page plus one WebSocket endpoint per
 * browser connection, each bridged to its own [ShellSession].
 *
 * Auth: a fresh token per start. The page itself requires `?token=` and
 * the token is embedded in the served page so the WebSocket connects
 * with it. The xterm.js library files are inert and served without a
 * token; they open no shell on their own. A wrong or missing token on
 * `/` or `/ws` gets 403 / close 1008, never a shell.
 *
 * One shell per WebSocket connection: two browser tabs get two shells,
 * and their bytes never interleave.
 */
class WebShellServer(
    private val context: Context,
    private val onLog: (String) -> Unit,
) {
    private var embedded: EmbeddedServer<*, *>? = null
    private var assetCache: Map<String, ByteArray>? = null

    /** Null until [start] succeeds. */
    var info: ServerInfo? = null
        private set

    val isRunning: Boolean
        get() = embedded != null

    fun start(): ServerInfo {
        val token = TokenGenerator.generate()
        val port = pickPort(DEFAULT_PORT)
        val addresses = NetworkAddresses.list()
        val assets = readAssets()
        val server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets) {
                pingPeriodMillis = 20_000
                timeoutMillis = 20_000
            }
            routing {
                get("/") {
                    if (call.request.queryParameters["token"] != token) {
                        call.respondText("token required", status = HttpStatusCode.Forbidden)
                        return@get
                    }
                    val html = String(assets.getValue("web/index.html"), Charsets.UTF_8)
                        .replace("__TOKEN__", token)
                    call.respondText(html, ContentType.Text.Html)
                }
                get("/xterm.js") {
                    call.respondBytes(assets.getValue("web/xterm.js"), ContentType.Application.JavaScript)
                }
                get("/xterm.css") {
                    call.respondBytes(assets.getValue("web/xterm.css"), ContentType.Text.CSS)
                }
                get("/addon-fit.js") {
                    call.respondBytes(assets.getValue("web/addon-fit.js"), ContentType.Application.JavaScript)
                }
                webSocket("/ws") {
                    if (call.request.queryParameters["token"] != token) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "bad token"))
                        return@webSocket
                    }
                    handleShell()
                }
            }
        }
        server.start(wait = false)
        embedded = server
        assetCache = assets
        val started = ServerInfo(port = port, token = token, addresses = addresses)
        info = started
        onLog("Server up on port $port, token ${token.take(6)}…")
        return started
    }

    fun stop() {
        embedded?.stop(0, 0)
        embedded = null
        info = null
        assetCache = null
        onLog("Server stopped.")
    }

    private fun readAssets(): Map<String, ByteArray> {
        assetCache?.let { return it }
        val names = listOf("web/index.html", "web/xterm.js", "web/xterm.css", "web/addon-fit.js")
        return names.associateWith { name ->
            context.assets.open(name).use { it.readBytes() }
        }
    }

    private fun pickPort(preferred: Int): Int {
        try {
            ServerSocket(preferred).use { return preferred }
        } catch (_: IOException) {
            // Preferred port is busy; fall through to a free one.
        }
        ServerSocket(0).use { return it.localPort }
    }

    private suspend fun DefaultWebSocketServerSession.handleShell() {
        val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
        val shell = ShellSession.start(
            initialCols = 80,
            initialRows = 24,
            onOutput = { bytes -> outgoing.trySend(bytes) },
            onExit = { outgoing.close() },
        )
        onLog("Browser connected (${shell.mode} shell).")
        shell.start()
        // First-input diagnostic, one line per connection: if the user
        // types and this line never appears, the keystrokes are dying
        // in the browser (xterm/IME), not on the server. If it appears
        // and nothing echoes, the break is between the server and the
        // shell. Read the frame bytes once and reuse them: a frame's
        // packet is consumed by the read.
        var firstInputLogged = false
        val sender = launch {
            for (bytes in outgoing) {
                try {
                    send(Frame.Binary(true, bytes))
                } catch (_: Exception) {
                    break
                }
            }
        }
        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> {
                        val bytes = frame.readBytes()
                        if (!firstInputLogged) {
                            firstInputLogged = true
                            onLog("First input received (${bytes.size} bytes).")
                        }
                        shell.write(bytes)
                    }
                    is Frame.Text -> handleControl(frame.readText(), shell)
                    is Frame.Close -> break
                    else -> {
                        // Ping/Pong are answered by the WebSockets plugin.
                    }
                }
            }
        } catch (_: Exception) {
            // Client went away mid-frame; the shell closes in finally.
        } finally {
            sender.cancel()
            outgoing.close()
            shell.close()
            onLog("Browser disconnected.")
        }
    }

    private fun handleControl(text: String, shell: ShellSession) {
        val control = runCatching { Json.decodeFromString<ControlMessage>(text) }.getOrNull() ?: return
        if (control.type == "resize") {
            shell.resize(control.cols, control.rows)
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
    }
}
