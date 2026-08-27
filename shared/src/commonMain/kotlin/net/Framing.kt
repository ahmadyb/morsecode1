package net.morsecode.net

/**
 * Message framing (PROTOCOL SPECIFICATION, Section 2).
 *
 * Two distinct wire layouts exist, and the difference matters for the byte
 * budget of every frame:
 *
 * PRE-HANDSHAKE — `message_type 0x00` only, sent in the clear:
 * ```
 * [4 bytes payload_length (u32, BE)] [1 byte type = 0x00] [N bytes JSON]
 * ```
 * `payload_length` counts the type byte plus the JSON body.
 *
 * POST-HANDSHAKE — `message_type 0x01+`, encrypted:
 * ```
 * [4 bytes frame_len (u32, BE)] [1 byte msg_type]
 * [12 bytes gcm_nonce] [N bytes ciphertext] [16 bytes gcm_tag]
 * ```
 * `frame_len` counts everything after the length prefix itself.
 *
 * Note on the trailing tag: `Cipher.getInstance("AES/GCM/NoPadding")` appends
 * the 16-byte authentication tag to its own output, so `doFinal()` already
 * produces `[ciphertext][tag]` contiguously. The encoder therefore copies the
 * cipher output verbatim rather than appending a separate tag block.
 *
 * This file is pure Kotlin with no JVM imports, so it runs in `commonTest`
 * on every target (Phase 1 is verified with `./gradlew :shared:jvmTest`).
 */
object Framing {

    /** Multi-byte integers are big-endian throughout (Section 1). */
    const val LENGTH_PREFIX_BYTES: Int = 4

    const val GCM_NONCE_BYTES: Int = 12
    const val GCM_TAG_BYTES: Int = 16

    /**
     * MANDATORY SAFETY LIMIT — max *decrypted* payload, 16 MiB.
     * Oversized frames are rejected immediately, the connection is closed, and
     * `ERROR{code:"frame_too_large"}` is sent.
     */
    const val MAX_DECRYPTED_PAYLOAD: Int = 16 * 1024 * 1024 // 16_777_216

    /** Header bytes that follow the length prefix on an encrypted frame. */
    private const val POST_HEADER_BYTES: Int = 1 + GCM_NONCE_BYTES

    /**
     * Largest `frame_len` we are willing to *allocate for* on an encrypted
     * frame. Derived, not chosen: with GCM/NoPadding the ciphertext is exactly
     * as long as the plaintext, so capping `frame_len` at
     * `MAX + 1 + 12 + 16` is the same rule as the 16 MiB plaintext cap, applied
     * before a hostile peer can make us allocate.
     */
    const val MAX_ENCRYPTED_FRAME_LENGTH: Int =
        MAX_DECRYPTED_PAYLOAD + 1 + GCM_NONCE_BYTES + GCM_TAG_BYTES

    /** Same rule for the unencrypted handshake frame (type byte + JSON). */
    const val MAX_PLAIN_FRAME_LENGTH: Int = MAX_DECRYPTED_PAYLOAD + 1

    /**
     * Builds a pre-handshake frame.
     *
     * @throws FrameFormatException if [type] is anything other than
     *   [MessageType.KEY_EXCHANGE]; the spec allows exactly one unencrypted
     *   message type, and silently framing something else would be a downgrade
     *   vector rather than a convenience.
     * @throws FrameFormatException if [payload] exceeds [MAX_DECRYPTED_PAYLOAD].
     */
    fun encodePreHandshake(type: Byte, payload: ByteArray): ByteArray {
        require(type == MessageType.KEY_EXCHANGE) {
            "Pre-handshake framing is reserved for KEY_EXCHANGE (0x00); got ${Hex.byte(type)}"
        }
        require(payload.size <= MAX_DECRYPTED_PAYLOAD) {
            "payload ${payload.size} exceeds MAX_DECRYPTED_PAYLOAD $MAX_DECRYPTED_PAYLOAD"
        }
        val out = ByteArray(LENGTH_PREFIX_BYTES + 1 + payload.size)
        putUInt32BE(out, 0, 1 + payload.size)
        out[LENGTH_PREFIX_BYTES] = type
        payload.copyInto(out, LENGTH_PREFIX_BYTES + 1)
        return out
    }

    /**
     * Builds a post-handshake frame from an already-sealed cipher output.
     *
     * @param sealedBytes the full `doFinal()` output: `[ciphertext][tag]`.
     * @param nonce the 12-byte nonce actually used to seal [sealedBytes]. This
     *   must be the sender's own next sequence value; see [Crypto].
     */
    fun encodePostHandshake(
        type: Byte,
        nonce: ByteArray,
        sealedBytes: ByteArray,
    ): ByteArray {
        require(nonce.size == GCM_NONCE_BYTES) {
            "nonce must be $GCM_NONCE_BYTES bytes, got ${nonce.size}"
        }
        require(sealedBytes.size >= GCM_TAG_BYTES) {
            "sealed output must include a $GCM_TAG_BYTES-byte tag"
        }
        // A sealed body of `MAX + tag` is exactly the limit, so compare against
        // the plaintext budget by subtracting the tag first.
        if (sealedBytes.size - GCM_TAG_BYTES > MAX_DECRYPTED_PAYLOAD) {
            throw FrameFormatException(
                "frame_too_large",
                "encrypted payload ${sealedBytes.size - GCM_TAG_BYTES} exceeds " +
                    "MAX_DECRYPTED_PAYLOAD $MAX_DECRYPTED_PAYLOAD",
            )
        }
        val frameLen = 1 + nonce.size + sealedBytes.size
        val out = ByteArray(LENGTH_PREFIX_BYTES + frameLen)
        putUInt32BE(out, 0, frameLen)
        out[LENGTH_PREFIX_BYTES] = type
        nonce.copyInto(out, LENGTH_PREFIX_BYTES + 1)
        sealedBytes.copyInto(out, LENGTH_PREFIX_BYTES + 1 + GCM_NONCE_BYTES)
        return out
    }

    /** Writes a big-endian u32. Values above 2^32-1 are a programming error. */
    fun putUInt32BE(target: ByteArray, offset: Int, value: Int) {
        require(value >= 0) { "negative length is not representable as u32 BE" }
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    fun readUInt32BE(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)
}

/** A message type value from the table in Section 2. */
object MessageType {
    const val KEY_EXCHANGE: Byte = 0x00
    const val HELLO: Byte = 0x01
    const val HELLO_ACK: Byte = 0x02
    const val TRANSFER_REQUEST: Byte = 0x03
    const val TRANSFER_RESPONSE: Byte = 0x04
    const val CHUNK_DATA: Byte = 0x05
    const val CHUNK_ACK: Byte = 0x06
    const val CHUNK_NACK: Byte = 0x07
    const val FILE_COMPLETE: Byte = 0x08
    const val TRANSFER_COMPLETE: Byte = 0x09
    const val TRANSFER_CANCEL: Byte = 0x0A
    const val ERROR: Byte = 0x0B
    const val PING: Byte = 0x0C
    const val PONG: Byte = 0x0D
    const val ROOM_ANNOUNCE: Byte = 0x0E
    const val ROOM_MEMBER_LIST: Byte = 0x0F
    const val TEXT_SHARE: Byte = 0x10
    /** Informational, optional diagnostic message. */
    const val WINDOW_RESIZE: Byte = 0x11

    /** Added by Section G — chat over the same encrypted session. */
    const val CHAT_MESSAGE: Byte = 0x12

    /** Human-readable name, used in logs and error payloads. */
    fun nameOf(type: Byte): String = when (type) {
        KEY_EXCHANGE -> "KEY_EXCHANGE"
        HELLO -> "HELLO"
        HELLO_ACK -> "HELLO_ACK"
        TRANSFER_REQUEST -> "TRANSFER_REQUEST"
        TRANSFER_RESPONSE -> "TRANSFER_RESPONSE"
        CHUNK_DATA -> "CHUNK_DATA"
        CHUNK_ACK -> "CHUNK_ACK"
        CHUNK_NACK -> "CHUNK_NACK"
        FILE_COMPLETE -> "FILE_COMPLETE"
        TRANSFER_COMPLETE -> "TRANSFER_COMPLETE"
        TRANSFER_CANCEL -> "TRANSFER_CANCEL"
        ERROR -> "ERROR"
        PING -> "PING"
        PONG -> "PONG"
        ROOM_ANNOUNCE -> "ROOM_ANNOUNCE"
        ROOM_MEMBER_LIST -> "ROOM_MEMBER_LIST"
        TEXT_SHARE -> "TEXT_SHARE"
        WINDOW_RESIZE -> "WINDOW_RESIZE"
        CHAT_MESSAGE -> "CHAT_MESSAGE"
        else -> "UNKNOWN(${Hex.byte(type)})"
    }
}

/**
 * Minimal hex formatting.
 *
 * `String.format` is a JVM-only Kotlin stdlib extension and is NOT available in
 * `commonMain`, so anything in this module that needs hex must use this helper.
 * This has already bitten once in this file — keep it that way.
 */
internal object Hex {
    private const val DIGITS = "0123456789ABCDEF"

    fun byte(value: Byte): String {
        val i = value.toInt() and 0xFF
        return "0x" + DIGITS[i ushr 4] + DIGITS[i and 0x0F]
    }

    fun bytes(value: ByteArray, limit: Int = value.size): String {
        val n = minOf(limit, value.size)
        val sb = StringBuilder(n * 2 + 2)
        for (i in 0 until n) {
            val v = value[i].toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * @throws IllegalArgumentException on odd length or a non-hex character.
     *   Silently skipping a bad character would produce a digest of the wrong
     *   length, which then fails comparison for a reason that has nothing to do
     *   with the data.
     */
    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have even length, got ${hex.length}" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hex[i * 2]
            val lo = hex[i * 2 + 1]
            require(hi.isHexDigit() && lo.isHexDigit()) { "'$hi$lo' is not a hex byte" }
            out[i] = ((hi.digitToInt(16) shl 4) or lo.digitToInt(16)).toByte()
        }
        return out
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}

/**
 * The plaintext layout of a `CHUNK_DATA` payload, before encryption:
 * ```
 * [16 bytes file_id] [4 bytes chunk_index (u32, BE)]
 * [32 bytes chunk_sha256] [4 bytes chunk_length (u32, BE)]
 * [N bytes chunk_bytes]
 * ```
 */
object ChunkDataLayout {
    const val FILE_ID_BYTES: Int = 16
    const val CHUNK_INDEX_BYTES: Int = 4
    const val CHUNK_SHA256_BYTES: Int = 32
    const val CHUNK_LENGTH_BYTES: Int = 4
    const val HEADER_BYTES: Int = FILE_ID_BYTES + CHUNK_INDEX_BYTES + CHUNK_SHA256_BYTES + CHUNK_LENGTH_BYTES

    /** Section 5: `chunk_size: 4194304`. */
    const val DEFAULT_CHUNK_SIZE: Int = 4 * 1024 * 1024

    fun encode(
        fileId: ByteArray,
        chunkIndex: Int,
        chunkSha256: ByteArray,
        chunkBytes: ByteArray,
    ): ByteArray {
        require(fileId.size == FILE_ID_BYTES) { "file_id must be $FILE_ID_BYTES bytes, got ${fileId.size}" }
        require(chunkSha256.size == CHUNK_SHA256_BYTES) {
            "chunk_sha256 must be $CHUNK_SHA256_BYTES bytes, got ${chunkSha256.size}"
        }
        val out = ByteArray(HEADER_BYTES + chunkBytes.size)
        fileId.copyInto(out, 0)
        Framing.putUInt32BE(out, FILE_ID_BYTES, chunkIndex)
        chunkSha256.copyInto(out, FILE_ID_BYTES + CHUNK_INDEX_BYTES)
        Framing.putUInt32BE(out, FILE_ID_BYTES + CHUNK_INDEX_BYTES + CHUNK_SHA256_BYTES, chunkBytes.size)
        chunkBytes.copyInto(out, HEADER_BYTES)
        return out
    }

    data class Decoded(
        val fileId: ByteArray,
        val chunkIndex: Int,
        val chunkSha256: ByteArray,
        val chunkBytes: ByteArray,
    )

    fun decode(payload: ByteArray): Decoded {
        if (payload.size < HEADER_BYTES) {
            throw FrameFormatException(
                "malformed_chunk_data",
                "CHUNK_DATA payload ${payload.size} < header $HEADER_BYTES",
            )
        }
        val declaredLength = Framing.readUInt32BE(payload, HEADER_BYTES - CHUNK_LENGTH_BYTES)
        if (declaredLength != payload.size - HEADER_BYTES) {
            throw FrameFormatException(
                "malformed_chunk_data",
                "declared chunk_length $declaredLength != actual ${payload.size - HEADER_BYTES}",
            )
        }
        return Decoded(
            fileId = payload.copyOfRange(0, FILE_ID_BYTES),
            chunkIndex = Framing.readUInt32BE(payload, FILE_ID_BYTES),
            chunkSha256 = payload.copyOfRange(FILE_ID_BYTES + CHUNK_INDEX_BYTES, HEADER_BYTES - CHUNK_LENGTH_BYTES),
            chunkBytes = payload.copyOfRange(HEADER_BYTES, payload.size),
        )
    }
}

/**
 * `file_id` codec.
 *
 * The wire format pins `file_id` at exactly 16 bytes inside CHUNK_DATA, while
 * the JSON manifests carry it as a string. Bridging those two means the string
 * form must be a fixed-width hex encoding of 16 random bytes — 32 hex
 * characters — and never an arbitrary user-supplied name.
 *
 * Deriving the bytes from, say, a filename hash would be a collision risk
 * across a batch, and letting a sender choose its own id would let it overwrite
 * another in-flight file's chunks on the receiver.
 */
object FileIds {
    const val HEX_LENGTH: Int = 32

    fun newId(randomBytes: ByteArray): String {
        require(randomBytes.size == ChunkDataLayout.FILE_ID_BYTES) {
            "file id needs ${ChunkDataLayout.FILE_ID_BYTES} random bytes, got ${randomBytes.size}"
        }
        return Hex.bytes(randomBytes).lowercase()
    }

    /**
     * @throws FrameFormatException if [id] is not exactly 32 lowercase-or-
     *   uppercase hex characters. A peer sending anything else is malformed,
     *   and silently truncating would misroute chunks.
     */
    fun toBytes(id: String): ByteArray {
        if (id.length != HEX_LENGTH) {
            throw FrameFormatException(
                ErrorCode.MALFORMED_CHUNK_DATA,
                "file_id '$id' is ${id.length} characters, expected $HEX_LENGTH",
            )
        }
        return try {
            Hex.decode(id)
        } catch (e: IllegalArgumentException) {
            throw FrameFormatException(ErrorCode.MALFORMED_CHUNK_DATA, "file_id '$id' is not valid hex: ${e.message}")
        }
    }
}

/**
 * Thrown for any structural framing violation. [code] is the exact string the
 * spec's error table expects in `ERROR{code:...}`.
 */
class FrameFormatException(
    val code: String,
    message: String,
) : Exception(message)

/** A complete frame extracted from the byte stream. */
data class WireFrame(
    val type: Byte,
    /** Present only in [FrameAssembler.Mode.POST_HANDSHAKE]. */
    val nonce: ByteArray?,
    /** Pre-handshake: the JSON body. Post-handshake: `[ciphertext][tag]`. */
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WireFrame) return false
        return type == other.type &&
            nonce.contentEqualsNullable(other.nonce) &&
            body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + (nonce?.contentHashCode() ?: 0)
        result = 31 * result + body.contentHashCode()
        return result
    }

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
        when {
            this == null && other == null -> true
            this == null || other == null -> false
            else -> this.contentEquals(other)
        }
}

/** Output of [FrameAssembler.offer]. */
sealed interface AssemblerEvent {
    data class Frame(val frame: WireFrame) : AssemblerEvent

    /**
     * A protocol violation. [code] is the string to put in `ERROR{code:...}`
     * before closing the connection.
     */
    data class Rejected(
        val code: String,
        val reason: String,
    ) : AssemblerEvent
}

/**
 * Reassembles `WireFrame`s from an arbitrary TCP byte stream.
 *
 * TCP gives no message boundaries, so a single `read()` may contain a partial
 * frame, exactly one frame, or several. This class buffers incrementally and
 * emits whole frames as they complete.
 *
 * The 16 MiB guard is enforced in two places on purpose:
 *  1. on the declared length prefix, *before* allocating — otherwise a peer
 *     claiming a 4 GB frame would OOM us before we could reject it;
 *  2. on the decrypted plaintext in [Crypto], because the declared length is
 *     attacker-controlled and cannot be trusted on its own.
 *
 * Not thread-safe by design: one instance per connection, driven from a single
 * coroutine. The spec's "no raw threads" rule means the caller already owns a
 * structured-concurrency scope per socket.
 */
class FrameAssembler(
    private var mode: Mode = Mode.PRE_HANDSHAKE,
) {
    enum class Mode { PRE_HANDSHAKE, POST_HANDSHAKE }

    private var buffer: ByteArray = ByteArray(INITIAL_BUFFER_BYTES)
    private var filled: Int = 0
    private var rejected: Boolean = false

    /** Once rejected, the connection must be closed; further input is ignored. */
    val isRejected: Boolean get() = rejected

    fun switchToPostHandshake() {
        mode = Mode.POST_HANDSHAKE
    }

    fun offer(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset): List<AssemblerEvent> {
        if (rejected) return emptyList()
        require(offset >= 0 && length >= 0 && offset + length <= chunk.size) { "bad offer() bounds" }
        if (length == 0) return emptyList()

        ensureCapacity(filled + length)
        chunk.copyInto(buffer, filled, offset, offset + length)
        filled += length

        val out = ArrayList<AssemblerEvent>(2)
        while (true) {
            if (filled < Framing.LENGTH_PREFIX_BYTES) break
            val declared = Framing.readUInt32BE(buffer, 0)

            // Length prefixes are unsigned on the wire; a top-bit-set value
            // reads back negative and is always malformed.
            if (declared < 0) {
                reject(out, "frame_too_large", "length prefix is negative when read as i32")
                break
            }
            val limit = when (mode) {
                Mode.PRE_HANDSHAKE -> Framing.MAX_PLAIN_FRAME_LENGTH
                Mode.POST_HANDSHAKE -> Framing.MAX_ENCRYPTED_FRAME_LENGTH
            }
            if (declared > limit) {
                reject(out, "frame_too_large", "declared frame_len $declared exceeds limit $limit")
                break
            }
            if (declared < minFrameBody(mode)) {
                reject(out, "malformed_frame", "declared frame_len $declared is below the minimum for $mode")
                break
            }
            if (filled < Framing.LENGTH_PREFIX_BYTES + declared) break

            val frameStart = Framing.LENGTH_PREFIX_BYTES
            val frame = when (mode) {
                Mode.PRE_HANDSHAKE -> WireFrame(
                    type = buffer[frameStart],
                    nonce = null,
                    body = buffer.copyOfRange(frameStart + 1, frameStart + declared),
                )

                Mode.POST_HANDSHAKE -> WireFrame(
                    type = buffer[frameStart],
                    nonce = buffer.copyOfRange(frameStart + 1, frameStart + 1 + Framing.GCM_NONCE_BYTES),
                    body = buffer.copyOfRange(
                        frameStart + 1 + Framing.GCM_NONCE_BYTES,
                        frameStart + declared,
                    ),
                )
            }
            out += AssemblerEvent.Frame(frame)

            // Slide the remainder to the front. A copy is fine here: frames are
            // at most 16 MiB and the common case leaves very little behind.
            val consumed = frameStart + declared
            buffer.copyInto(buffer, 0, consumed, filled)
            filled -= consumed
        }
        return out
    }

    private fun minFrameBody(mode: Mode): Int = when (mode) {
        Mode.PRE_HANDSHAKE -> 1
        Mode.POST_HANDSHAKE -> 1 + Framing.GCM_NONCE_BYTES + Framing.GCM_TAG_BYTES
    }

    private fun reject(out: MutableList<AssemblerEvent>, code: String, reason: String) {
        rejected = true
        out += AssemblerEvent.Rejected(code, reason)
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        var size = buffer.size
        while (size < needed) size = (size * 2).coerceAtMost(Framing.MAX_ENCRYPTED_FRAME_LENGTH + 64)
        if (size < needed) {
            // Cannot happen: `needed` is bounded by the declared-length guard.
            throw FrameFormatException("frame_too_large", "cannot grow buffer to $needed")
        }
        buffer = buffer.copyOf(size)
    }

    companion object {
        private const val INITIAL_BUFFER_BYTES = 8 * 1024
    }
}
