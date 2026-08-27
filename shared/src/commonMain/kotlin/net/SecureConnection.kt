package net.morsecode.net

/** What [SecureConnection.receive] hands back. */
sealed interface ReceivedMessage {
    /** A successfully authenticated payload of the given [type]. */
    data class Payload(
        val type: Byte,
        val plaintext: ByteArray,
    ) : ReceivedMessage {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Payload && type == other.type && plaintext.contentEquals(other.plaintext))

        override fun hashCode(): Int = 31 * type.hashCode() + plaintext.contentHashCode()
    }

    /** The peer sent `ERROR`. Usually terminal for this transfer. */
    data class PeerError(
        val code: String,
        val message: String?,
    ) : ReceivedMessage

    /** Orderly EOF: the peer closed the socket. */
    data object PeerClosed : ReceivedMessage

    /**
     * A framing or decryption violation detected locally. [code] is the string
     * that was sent in our own `ERROR` frame before closing.
     */
    data class ProtocolViolation(
        val code: String,
        val reason: String,
    ) : ReceivedMessage
}

/**
 * An authenticated, framed channel over a [SocketTransport].
 *
 * This is the single place that turns typed payloads into encrypted frames and
 * back. Everything above it — [TransferSender], [TransferReceiver],
 * [RoomManager], chat — deals only in `type` + plaintext bytes and never touches
 * a nonce or a cipher.
 *
 * CONCURRENCY CONTRACT: one connection is driven by one coroutine at a time for
 * reads, and writes are serialised internally. That contract is what makes it
 * safe to skip locking around [assembler] and [pending]. It holds because the
 * spec's "no raw threads" rule gives every socket exactly one owning scope.
 */
class SecureConnection(
    val transport: SocketTransport,
    val cipher: SessionCipher,
    /**
     * Mutable because the initiator only learns who it is talking to from the
     * HELLO_ACK that arrives *after* the channel is already encrypted. It is
     * written exactly once, immediately after the handshake, before any
     * transfer traffic flows.
     */
    var peer: PeerIdentity,
    val isInitiator: Boolean,
) {
    private val assembler = FrameAssembler(FrameAssembler.Mode.POST_HANDSHAKE)
    private val readBuffer = ByteArray(READ_BUFFER_BYTES)
    private val pending = ArrayDeque<ReceivedMessage>()

    /** Set once a fatal violation is detected; further reads return it once. */
    private var terminal: ReceivedMessage? = null
    private var closed = false

    val peerDeviceId: String get() = peer.deviceId

    /**
     * Seals [payload] under a fresh nonce and writes the frame.
     *
     * The nonce comes from [SessionCipher.seal], which increments its own
     * counter, so two consecutive sends can never share a nonce even if this
     * method were somehow called concurrently.
     */
    suspend fun send(type: Byte, payload: ByteArray) {
        if (closed) throw IllegalStateException("connection to ${peer.deviceId} is closed")
        val sealed = cipher.seal(payload)
        transport.write(Framing.encodePostHandshake(type, sealed.nonce, sealed.sealedBytes))
    }

    /** Convenience for a payload-carrying message. */
    suspend inline fun <reified T> sendJson(type: Byte, message: T) {
        send(type, MessageJson.encodeToBytes(message))
    }

    /**
     * Reads the next message, blocking (suspending) until one is available.
     *
     * A single socket read may complete several frames, so extras are buffered
     * in [pending] and returned by subsequent calls without touching the
     * transport.
     */
    suspend fun receive(): ReceivedMessage {
        pending.removeFirstOrNull()?.let { return it }
        terminal?.let { return it }

        while (true) {
            val n = transport.read(readBuffer)
            if (n < 0) {
                return mark(ReceivedMessage.PeerClosed)
            }
            if (n == 0) continue

            for (event in assembler.offer(readBuffer, 0, n)) {
                when (event) {
                    is AssemblerEvent.Rejected -> return fatal(event.code, event.reason)

                    is AssemblerEvent.Frame -> {
                        val frame = event.frame
                        val plaintext = try {
                            // `frame.nonce` is non-null in POST_HANDSHAKE mode by
                            // construction; the null branch is unreachable but
                            // must be handled for the type checker.
                            val nonce = frame.nonce ?: return fatal(
                                ErrorCode.MALFORMED_FRAME,
                                "post-handshake frame arrived without a nonce",
                            )
                            cipher.open(nonce, frame.body)
                        } catch (e: CryptoException) {
                            return fatal(e.code, e.message ?: "decryption_failed")
                        }

                        if (plaintext.size > Framing.MAX_DECRYPTED_PAYLOAD) {
                            // Second half of the two-place 16 MiB guard: the
                            // declared length was within limits, but the actual
                            // plaintext is not to be trusted either.
                            return fatal(
                                ErrorCode.FRAME_TOO_LARGE,
                                "decrypted payload ${plaintext.size} exceeds ${Framing.MAX_DECRYPTED_PAYLOAD}",
                            )
                        }

                        if (frame.type == MessageType.ERROR) {
                            val err = runCatching {
                                MessageJson.decodeFromBytes<ErrorPayload>(plaintext)
                            }.getOrNull()
                            val msg = ReceivedMessage.PeerError(
                                code = err?.code ?: ErrorCode.INTERNAL,
                                message = err?.message,
                            )
                            // Every code in the Section 14 table closes the
                            // connection, so a peer ERROR is session-terminal.
                            // Anything already parsed in this same read is
                            // queued in front of it and delivered first, so the
                            // caller never loses an ACK that arrived alongside.
                            pending.addLast(msg)
                            terminal = msg
                            return pending.removeFirst()
                        }

                        pending.addLast(ReceivedMessage.Payload(frame.type, plaintext))
                    }
                }
            }
            pending.removeFirstOrNull()?.let { return it }
        }
    }

    /**
     * Sends `ERROR{code}` and closes.
     *
     * Best-effort: if the write fails (the peer is already gone) we still
     * close cleanly rather than propagating, because the caller is already on a
     * failure path and a second exception would mask the first.
     */
    suspend fun fatal(code: String, reason: String): ReceivedMessage.ProtocolViolation {
        runCatching {
            send(
                MessageType.ERROR,
                MessageJson.encodeToBytes(ErrorPayload(code = code, message = reason)),
            )
        }
        close()
        val violation = ReceivedMessage.ProtocolViolation(code, reason)
        return mark(violation) as ReceivedMessage.ProtocolViolation
    }

    suspend fun close() {
        if (closed) return
        closed = true
        runCatching { transport.close() }
    }

    private fun mark(message: ReceivedMessage): ReceivedMessage {
        terminal = message
        return message
    }

    companion object {
        private const val READ_BUFFER_BYTES = 64 * 1024
    }
}
