package net.morsecode.net

/**
 * The byte-stream abstraction every higher layer codes against.
 *
 * Declared in `commonMain` so the state machines never touch a platform socket
 * type. The production implementation is [JvmSocketTransport] in
 * `jvmMain/net/SocketJvm.kt`; tests substitute an in-memory pair, which is what
 * lets Phase 4's windowed transfer be verified with two JVM instances before
 * any Android build exists.
 *
 * All methods are `suspend`: the spec's TECH STACK requires coroutine-based
 * sockets with no raw threads, so a transport that blocks a thread is not a
 * valid implementation of this interface.
 */
interface SocketTransport {
    /**
     * Writes [bytes] completely, or throws.
     *
     * Partial writes are the transport's problem, not the caller's: a socket
     * `write()` may accept fewer bytes than offered, and leaking that to every
     * call site would be a permanent source of truncation bugs.
     */
    suspend fun write(bytes: ByteArray)

    /**
     * Reads up to `buffer.size` bytes.
     *
     * @return the number of bytes read, or `-1` on orderly peer close (EOF).
     *   Returns `0` only if [buffer] is empty.
     */
    suspend fun read(buffer: ByteArray): Int

    /** Idempotent. Safe to call from a failure path more than once. */
    suspend fun close()

    val remoteAddress: String
    val remotePort: Int
    val isOpen: Boolean
}

/** Identity we present to peers. */
data class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val appVersion: String,
)

/** Identity a peer presented to us during the handshake. */
data class PeerIdentity(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val appVersion: String,
    val protoVersion: Int,
    /** True when the peer was already in TrustedDeviceRepo, so no token was needed. */
    val isTrusted: Boolean,
)

/**
 * Outcome of the handshake, from the initiator's point of view.
 *
 * [Rejected] is a *protocol-level* answer from a reachable, correctly-speaking
 * peer (wrong proto version, bad token). [Failure] is a transport or crypto
 * problem. The distinction matters to the UI: a rejection is worth showing the
 * user verbatim, a failure is worth retrying.
 */
sealed interface HandshakeOutcome {
    data class Success(
        val connection: SecureConnection,
        val peer: PeerIdentity,
    ) : HandshakeOutcome

    data class Rejected(
        val reason: String,
        val peer: PeerIdentity? = null,
    ) : HandshakeOutcome

    data class Failure(
        val code: String,
        val message: String,
    ) : HandshakeOutcome
}
