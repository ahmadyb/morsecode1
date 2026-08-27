package net.morsecode.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.morsecode.util.Base64

/**
 * KEY_EXCHANGE (0x00) payload.
 *
 * The spec fixes the public key's *wire format* (65-byte uncompressed point)
 * but leaves its JSON envelope unspecified. It is carried base64-encoded inside
 * the pre-handshake JSON frame, which is what Section 2's
 * `[4 bytes][1 byte type=0x00][N bytes JSON]` layout calls for.
 */
@Serializable
data class KeyExchangePayload(
    @SerialName("pub") val publicKeyBase64: String,
)

/**
 * Whether an incoming HELLO may be accepted without prompting the user
 * (Sections 9 and 10).
 */
enum class AutoAcceptScope {
    /** Default. Never accept silently. */
    OFF,

    /** Recommended default when auto-accept is enabled at all. */
    TRUSTED_ONLY,

    /** Requires the extra confirmation dialog described in Section 10. */
    ALL,
}

/**
 * Key exchange + HELLO flow (PROTOCOL SPECIFICATION, Section 4).
 *
 * ```
 * INITIATOR                                   RESPONDER
 *    TCP connect ------------------------------->
 *    KEY_EXCHANGE (0x00, unencrypted) ----------->
 *    <----------- KEY_EXCHANGE (0x00, unencrypted)
 *    (both derive session key)
 *    HELLO (encrypted, 0x01) --------------------->
 *    <--------------------------- HELLO_ACK (0x02)
 * ```
 *
 * Everything after the two KEY_EXCHANGE frames is encrypted. The pre-handshake
 * exchange leaks only the ephemeral public keys, which is exactly what an
 * unauthenticated ECDH is supposed to leak; identity is asserted *inside* the
 * encrypted HELLO so it cannot be tampered with in transit.
 *
 * This class holds no sockets of its own — it takes an already-connected
 * [SocketTransport] — so it is unit-testable over an in-memory pipe.
 */
class HandshakeCoordinator(
    private val crypto: CryptoProvider,
    private val identity: DeviceIdentity,
    private val protoVersion: Int = PROTO_VERSION,
    /** The token this device is currently advertising, if any. */
    private val activePairingToken: () -> String? = { null },
    /** Backed by TrustedDeviceRepo (Section 9). */
    private val isTrustedDevice: (deviceId: String) -> Boolean = { false },
    /** Section 10. */
    private val autoAcceptScope: () -> AutoAcceptScope = { AutoAcceptScope.OFF },
    /**
     * Called when a HELLO needs a human decision. Returning `false` produces
     * `HELLO_ACK{accepted:false, reason:"user_declined"}`.
     *
     * Invoked on whatever dispatcher owns the accepting coroutine, so an
     * implementation that shows a dialog must be main-thread safe.
     */
    private val promptUser: suspend (Hello) -> Boolean = { true },
) {

    // ────────────────────────────────────────────────────────────────────────
    // Initiator
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Runs the client half of the handshake on an already-connected socket.
     *
     * @param pairingToken sent in HELLO when the user paired by QR or manual
     *   entry. Omitted (null) when the peer is already trusted.
     */
    suspend fun initiate(
        transport: SocketTransport,
        pairingToken: String? = null,
        isTrustedRequest: Boolean = false,
    ): HandshakeOutcome {
        val local = crypto.newKeyExchange()

        // 1. Send our KEY_EXCHANGE.
        runCatching {
            transport.write(
                Framing.encodePreHandshake(
                    MessageType.KEY_EXCHANGE,
                    MessageJson.encodeToBytes(KeyExchangePayload(Base64.encode(local.publicKey))),
                ),
            )
        }.onFailure {
            return HandshakeOutcome.Failure(ErrorCode.INTERNAL, "failed to send KEY_EXCHANGE: ${it.message}")
        }

        // 2. Receive theirs.
        val peerKeyExchange = readPreHandshakeKeyExchange(transport)
            ?: return HandshakeOutcome.Failure(
                ErrorCode.MALFORMED_FRAME,
                "peer did not send a valid KEY_EXCHANGE frame",
            )

        val remotePublicKey = try {
            Base64.decode(peerKeyExchange.publicKeyBase64)
        } catch (e: IllegalArgumentException) {
            return HandshakeOutcome.Failure(ErrorCode.MALFORMED_FRAME, "malformed public key: ${e.message}")
        }
        if (remotePublicKey.size != CryptoConstants.UNCOMPRESSED_POINT_BYTES) {
            return HandshakeOutcome.Failure(
                ErrorCode.MALFORMED_FRAME,
                "public key is ${remotePublicKey.size} bytes, expected " +
                    CryptoConstants.UNCOMPRESSED_POINT_BYTES,
            )
        }

        // 3. Derive. Order of the two public keys does not matter — Hkdf sorts.
        val sharedSecret = try {
            local.sharedSecret(remotePublicKey)
        } catch (e: Exception) {
            return HandshakeOutcome.Failure(ErrorCode.DECRYPTION_FAILED, "ECDH failed: ${e.message}")
        }
        val sessionKey = crypto.deriveSessionKey(sharedSecret, local.publicKey, remotePublicKey)
        val cipher = crypto.sessionCipher(sessionKey)

        val connection = SecureConnection(
            transport = transport,
            cipher = cipher,
            peer = PeerIdentity(
                deviceId = "unknown",
                deviceName = transport.remoteAddress,
                deviceType = "unknown",
                appVersion = "unknown",
                protoVersion = protoVersion,
                isTrusted = false,
            ),
            isInitiator = true,
        )

        // 4. HELLO, encrypted.
        val hello = Hello(
            deviceId = identity.deviceId,
            deviceName = identity.deviceName,
            deviceType = identity.deviceType,
            appVersion = identity.appVersion,
            protoVersion = protoVersion,
            pairingToken = pairingToken,
            isTrustedRequest = isTrustedRequest,
        )
        runCatching { connection.sendJson(MessageType.HELLO, hello) }
            .onFailure { return HandshakeOutcome.Failure(ErrorCode.INTERNAL, "failed to send HELLO: ${it.message}") }

        // 5. HELLO_ACK.
        return when (val response = connection.receive()) {
            is ReceivedMessage.Payload -> {
                if (response.type != MessageType.HELLO_ACK) {
                    connection.fatal(
                        ErrorCode.MALFORMED_FRAME,
                        "expected HELLO_ACK, got ${MessageType.nameOf(response.type)}",
                    )
                    return HandshakeOutcome.Failure(
                        ErrorCode.MALFORMED_FRAME,
                        "expected HELLO_ACK, got ${MessageType.nameOf(response.type)}",
                    )
                }
                val ack = runCatching { MessageJson.decodeFromBytes<HelloAck>(response.plaintext) }
                    .getOrElse {
                        return HandshakeOutcome.Failure(ErrorCode.MALFORMED_FRAME, "unparsable HELLO_ACK")
                    }
                if (!ack.accepted) {
                    // The channel worked; the peer said no. Close it cleanly —
                    // a rejected session must not linger half-open.
                    connection.close()
                    return HandshakeOutcome.Rejected(ack.reason ?: RejectReason.USER_DECLINED)
                }
                connection.peer = PeerIdentity(
                    deviceId = ack.deviceId,
                    deviceName = ack.deviceName,
                    deviceType = ack.deviceType,
                    appVersion = ack.appVersion,
                    protoVersion = ack.protoVersion,
                    isTrusted = isTrustedDevice(ack.deviceId),
                )
                HandshakeOutcome.Success(connection, connection.peer)
            }

            is ReceivedMessage.PeerError -> {
                connection.close()
                HandshakeOutcome.Failure(response.code, response.message ?: "peer sent ERROR during handshake")
            }

            is ReceivedMessage.PeerClosed -> HandshakeOutcome.Failure(
                ErrorCode.INTERNAL,
                "peer closed the connection during handshake",
            )

            is ReceivedMessage.ProtocolViolation ->
                HandshakeOutcome.Failure(response.code, response.reason)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Responder
    // ────────────────────────────────────────────────────────────────────────

    /** Runs the server half of the handshake on an accepted socket. */
    suspend fun respond(transport: SocketTransport): HandshakeOutcome {
        // 1. Receive their KEY_EXCHANGE first — the initiator always sends first.
        val peerKeyExchange = readPreHandshakeKeyExchange(transport)
            ?: return HandshakeOutcome.Failure(
                ErrorCode.MALFORMED_FRAME,
                "client did not open with a valid KEY_EXCHANGE frame",
            )

        val remotePublicKey = try {
            Base64.decode(peerKeyExchange.publicKeyBase64)
        } catch (e: IllegalArgumentException) {
            return HandshakeOutcome.Failure(ErrorCode.MALFORMED_FRAME, "malformed public key: ${e.message}")
        }
        if (remotePublicKey.size != CryptoConstants.UNCOMPRESSED_POINT_BYTES) {
            return HandshakeOutcome.Failure(
                ErrorCode.MALFORMED_FRAME,
                "public key is ${remotePublicKey.size} bytes, expected " +
                    CryptoConstants.UNCOMPRESSED_POINT_BYTES,
            )
        }

        // 2. Answer with ours.
        val local = crypto.newKeyExchange()
        runCatching {
            transport.write(
                Framing.encodePreHandshake(
                    MessageType.KEY_EXCHANGE,
                    MessageJson.encodeToBytes(KeyExchangePayload(Base64.encode(local.publicKey))),
                ),
            )
        }.onFailure {
            return HandshakeOutcome.Failure(ErrorCode.INTERNAL, "failed to send KEY_EXCHANGE: ${it.message}")
        }

        // 3. Derive the same key the initiator derived.
        val sharedSecret = try {
            local.sharedSecret(remotePublicKey)
        } catch (e: Exception) {
            return HandshakeOutcome.Failure(ErrorCode.DECRYPTION_FAILED, "ECDH failed: ${e.message}")
        }
        val sessionKey = crypto.deriveSessionKey(sharedSecret, local.publicKey, remotePublicKey)
        val connection = SecureConnection(
            transport = transport,
            cipher = crypto.sessionCipher(sessionKey),
            peer = PeerIdentity(
                deviceId = "unknown",
                deviceName = transport.remoteAddress,
                deviceType = "unknown",
                appVersion = "unknown",
                protoVersion = protoVersion,
                isTrusted = false,
            ),
            isInitiator = false,
        )

        // 4. Read HELLO.
        val hello = when (val first = connection.receive()) {
            is ReceivedMessage.Payload -> {
                if (first.type != MessageType.HELLO) {
                    connection.fatal(
                        ErrorCode.MALFORMED_FRAME,
                        "expected HELLO, got ${MessageType.nameOf(first.type)}",
                    )
                    return HandshakeOutcome.Failure(
                        ErrorCode.MALFORMED_FRAME,
                        "expected HELLO, got ${MessageType.nameOf(first.type)}",
                    )
                }
                runCatching { MessageJson.decodeFromBytes<Hello>(first.plaintext) }
                    .getOrElse {
                        connection.fatal(ErrorCode.MALFORMED_FRAME, "unparsable HELLO")
                        return HandshakeOutcome.Failure(ErrorCode.MALFORMED_FRAME, "unparsable HELLO")
                    }
            }

            is ReceivedMessage.PeerError -> {
                connection.close()
                return HandshakeOutcome.Failure(first.code, first.message ?: "peer sent ERROR during handshake")
            }

            is ReceivedMessage.PeerClosed -> {
                return HandshakeOutcome.Failure(ErrorCode.INTERNAL, "peer closed during handshake")
            }

            is ReceivedMessage.ProtocolViolation -> {
                return HandshakeOutcome.Failure(first.code, first.reason)
            }
        }

        // 5. Decide, per Section 4's rules, in the order the spec states them.
        val decision = decide(hello)
        val ack = HelloAck(
            deviceId = identity.deviceId,
            deviceName = identity.deviceName,
            deviceType = identity.deviceType,
            appVersion = identity.appVersion,
            protoVersion = protoVersion,
            accepted = decision.accepted,
            reason = decision.reason,
        )
        runCatching { connection.sendJson(MessageType.HELLO_ACK, ack) }

        if (!decision.accepted) {
            connection.close()
            return HandshakeOutcome.Rejected(decision.reason ?: RejectReason.USER_DECLINED)
        }

        connection.peer = PeerIdentity(
            deviceId = hello.deviceId,
            deviceName = hello.deviceName,
            deviceType = hello.deviceType,
            appVersion = hello.appVersion,
            protoVersion = hello.protoVersion,
            isTrusted = decision.trusted,
        )
        return HandshakeOutcome.Success(connection, connection.peer)
    }

    // ────────────────────────────────────────────────────────────────────────

    private data class Decision(
        val accepted: Boolean,
        val reason: String?,
        val trusted: Boolean,
    )

    /**
     * Section 4's acceptance rules, evaluated in the spec's order.
     *
     * The order is not arbitrary: the protocol-version check comes first
     * because a mismatched peer cannot be reasoned about at all, and the
     * trusted-device check comes *before* the token check because Section 9
     * says a trusted `device_id` skips the token requirement entirely.
     */
    private suspend fun decide(hello: Hello): Decision {
        if (hello.protoVersion != protoVersion) {
            return Decision(false, RejectReason.PROTOCOL_VERSION_MISMATCH, trusted = false)
        }

        val trusted = isTrustedDevice(hello.deviceId)
        if (trusted) {
            // Section 9: pre-verified regardless of pairing_token. Still fully
            // encrypted — trust only removes the prompt, never the crypto.
            return Decision(true, null, trusted = true)
        }

        val active = activePairingToken()
        if (active != null) {
            // Constant-time comparison: the token is a shared secret, and a
            // short-circuiting `==` leaks its length and prefix through timing.
            return if (constantTimeEquals(hello.pairingToken ?: "", active)) {
                Decision(true, null, trusted = false)
            } else {
                Decision(false, RejectReason.INVALID_PAIRING_TOKEN, trusted = false)
            }
        }

        // No token advertised and the device is not trusted: fall back to the
        // auto-accept setting (Section 10), else ask the user.
        return when (autoAcceptScope()) {
            AutoAcceptScope.ALL -> Decision(true, null, trusted = false)
            AutoAcceptScope.TRUSTED_ONLY -> Decision(false, RejectReason.USER_DECLINED, trusted = false)
            AutoAcceptScope.OFF ->
                if (promptUser(hello)) Decision(true, null, trusted = false)
                else Decision(false, RejectReason.USER_DECLINED, trusted = false)
        }
    }

    /**
     * Reads exactly one pre-handshake frame.
     *
     * Deliberately bounded: a peer that never completes a frame must not be
     * able to hold this coroutine (and its thread) forever. The caller's
     * coroutine scope is expected to apply `withTimeout`.
     */
    private suspend fun readPreHandshakeKeyExchange(transport: SocketTransport): KeyExchangePayload? {
        val assembler = FrameAssembler(FrameAssembler.Mode.PRE_HANDSHAKE)
        val buffer = ByteArray(4096)
        while (true) {
            val n = transport.read(buffer)
            if (n < 0) return null
            if (n == 0) continue
            for (event in assembler.offer(buffer, 0, n)) {
                when (event) {
                    is AssemblerEvent.Rejected -> return null
                    is AssemblerEvent.Frame -> {
                        if (event.frame.type != MessageType.KEY_EXCHANGE) return null
                        return runCatching {
                            MessageJson.decodeFromBytes<KeyExchangePayload>(event.frame.body)
                        }.getOrNull()
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Compares two pairing tokens without short-circuiting on the first
         * differing byte.
         *
         * A plain `==` returns as soon as it finds a mismatch, so the elapsed
         * time tells an attacker how many leading characters they guessed
         * correctly — enough to brute-force a 6-digit Web Connect PIN a
         * character at a time instead of all at once.
         *
         * Honest limitation: this does NOT hide the token's *length*, because a
         * length mismatch returns immediately. That is acceptable here since
         * both token formats are fixed-length by construction (Section 8's
         * random hex, Section H.3's 6-digit PIN), so the length is public
         * knowledge rather than a secret.
         */
        internal fun constantTimeEquals(a: String, b: String): Boolean {
            val ab = a.encodeToByteArray()
            val bb = b.encodeToByteArray()
            if (ab.size != bb.size) return false

            // OR-accumulate every difference; the branch is outside the loop, so
            // the work done is identical whether the tokens match or not.
            var diff = 0
            for (i in ab.indices) {
                diff = diff or (ab[i].toInt() xor bb[i].toInt())
            }
            return diff == 0
        }
    }
}
