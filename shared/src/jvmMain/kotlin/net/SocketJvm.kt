package net.morsecode.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Blocking-socket [SocketTransport] over `Dispatchers.IO`.
 *
 * `java.net.Socket` only offers blocking reads, so every call is wrapped in
 * `withContext(Dispatchers.IO)`. That satisfies the TECH STACK rule — coroutine-
 * based sockets with no raw threads of our own — without pulling in NIO
 * selectors, which would be a large amount of machinery for a LAN file
 * transfer that is bottlenecked by Wi-Fi and disk, not by socket readiness.
 *
 * Cancellation note: `Dispatchers.IO` work is cancellable at suspension points
 * but a blocked `read()` will not return until data arrives or the socket is
 * closed. Callers that need to abandon a read must call [close], which shuts
 * the socket down and unblocks the pending read with an `IOException`. That is
 * why [close] is idempotent and never throws.
 */
class JvmSocketTransport private constructor(
    private val socket: Socket,
    private val input: java.io.InputStream,
    private val output: java.io.OutputStream,
) : SocketTransport {

    override val remoteAddress: String = socket.inetAddress?.hostAddress ?: "unknown"
    override val remotePort: Int get() = socket.port
    override val isOpen: Boolean get() = !socket.isClosed && socket.isConnected

    /**
     * Nagle off.
     *
     * Small control frames (CHUNK_ACK) must not sit in a 40 ms coalescing
     * buffer while the sender's window stalls waiting for them. The bulk data
     * path is 4 MiB chunks, which are already large enough to be unaffected by
     * turning this off.
     */
    init {
        runCatching { socket.tcpNoDelay = true }
    }

    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            // `OutputStream.write(byte[], off, len)` is contractually a full
            // write: it blocks until all `len` bytes are handed to the socket.
            // Unlike a raw NIO channel there is no short-write case to loop on,
            // so callers never see a partial frame.
            output.write(bytes)
            output.flush()
        }
    }

    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
        if (buffer.isEmpty()) return@withContext 0
        // -1 is the JDK's EOF signal; pass it straight through so
        // SecureConnection can distinguish orderly close from "no data yet".
        input.read(buffer, 0, buffer.size)
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            runCatching { input.close() }
            runCatching { output.close() }
            runCatching { socket.close() }
        }
    }

    companion object {
        /**
         * Connects to a peer.
         *
         * @param timeoutMs connection timeout. Bounded on purpose: mDNS can
         *   advertise a device that has since left the network, and an
         *   unbounded connect would hang the sender's coroutine for the full OS
         *   TCP timeout (~75 s) per stale entry.
         */
        suspend fun connect(host: String, port: Int, timeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS): SocketTransport =
            withContext(Dispatchers.IO) {
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    JvmSocketTransport(socket, socket.getInputStream(), socket.getOutputStream())
                } catch (e: Exception) {
                    runCatching { socket.close() }
                    throw e
                }
            }

        /** Wraps an already-accepted socket (server side). */
        fun fromAccepted(socket: Socket): SocketTransport =
            JvmSocketTransport(socket, socket.getInputStream(), socket.getOutputStream())

        const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 5_000
    }
}

/**
 * Listening socket for the transfer protocol (Section 1).
 *
 * Binds the default port 53317 and falls back to an ephemeral port when that
 * one is taken — two Morse Code instances on the same PC is a normal thing to
 * want, and the spec explicitly calls for the fallback. Whichever port is
 * actually bound is what gets published in the mDNS TXT record.
 */
class JvmTransferServer private constructor(
    private val serverSocket: ServerSocket,
) {
    /** The port actually bound, which may differ from the requested one. */
    val port: Int get() = serverSocket.localPort

    val boundAddress: InetAddress? get() = serverSocket.inetAddress

    /**
     * Accepts one connection.
     *
     * @return `null` once the server is closed. Callers loop on this inside a
     *   coroutine and break on null.
     */
    suspend fun accept(): SocketTransport? = withContext(Dispatchers.IO) {
        if (serverSocket.isClosed) return@withContext null
        try {
            JvmSocketTransport.fromAccepted(serverSocket.accept())
        } catch (e: java.net.SocketException) {
            // Expected when close() races an in-flight accept().
            null
        }
    }

    suspend fun close() {
        withContext(Dispatchers.IO) { runCatching { serverSocket.close() } }
    }

    companion object {
        /**
         * @param bindAddress `null` binds all interfaces, which is required for
         *   the app to be reachable over Wi-Fi. Section H's Web Connect server
         *   is the one component that must NOT do this, and it binds
         *   explicitly to the LAN address instead.
         */
        suspend fun bind(
            preferredPort: Int = DEFAULT_PORT,
            bindAddress: InetAddress? = null,
            backlog: Int = 16,
        ): JvmTransferServer = withContext(Dispatchers.IO) {
            val socket = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindAddress, preferredPort), backlog)
                }
            } catch (e: java.net.BindException) {
                // Port 53317 is taken; fall back to whatever the OS offers and
                // publish the real port over mDNS.
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindAddress, 0), backlog)
                }
            }
            JvmTransferServer(socket)
        }
    }
}

/**
 * Best-effort enumeration of this host's LAN-facing IPv4 addresses.
 *
 * Needed for the QR fallback (Section 3) and for binding the Web Connect
 * server to the LAN interface only (Section H.3). Link-local and loopback
 * addresses are filtered out: a `169.254.x.x` address is not reachable by a
 * peer on the Wi-Fi network, and advertising one produces a QR code that
 * scans fine and then never connects.
 */
object NetworkInterfaces {
    fun lanAddresses(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { iface ->
            if (!iface.isUp || iface.isLoopback) return@flatMap emptyList()
            iface.inetAddresses.toList().filter { addr ->
                addr is java.net.Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress
            }.map { it.hostAddress }
        }.filterNotNull().distinct()
    }.getOrDefault(emptyList())

    /** First LAN address, or null when there is no usable network at all. */
    fun primaryLanAddress(): String? = lanAddresses().firstOrNull()
}
