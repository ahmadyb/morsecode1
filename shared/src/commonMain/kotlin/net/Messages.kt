package net.morsecode.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Every JSON payload exchanged over the wire.
 *
 * `@SerialName` pins each field to the exact snake_case spelling the protocol
 * specification uses. Renaming one of these is a wire-protocol break, not a
 * refactor: two builds with different spellings will interoperate right up
 * until the first transfer, then fail in a way that looks like corruption.
 *
 * The shared [Json] instance is configured with `ignoreUnknownKeys = true` so a
 * newer peer can add optional fields without breaking an older one, and
 * `encodeDefaults = false` so the common case (`thumbnail_base64 = null` etc.)
 * stays small.
 */
object MessageJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        coerceInputValues = true
    }

    inline fun <reified T> encode(value: T): String = instance.encodeToString(value)

    inline fun <reified T> decode(text: String): T = instance.decodeFromString(text)

    inline fun <reified T> encodeToBytes(value: T): ByteArray = encode(value).encodeToByteArray()

    inline fun <reified T> decodeFromBytes(bytes: ByteArray): T = decode(bytes.decodeToString())
}

// ────────────────────────────────────────────────────────────────────────────
// Section 4 — Handshake
// ────────────────────────────────────────────────────────────────────────────

/**
 * `device_type` values advertised in the mDNS TXT record and in HELLO.
 * The spec allows exactly these two spellings.
 */
object DeviceType {
    const val ANDROID = "android"
    const val WINDOWS = "windows"
}

@Serializable
data class Hello(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_type") val deviceType: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("proto_version") val protoVersion: Int,
    @SerialName("pairing_token") val pairingToken: String? = null,
    @SerialName("is_trusted_request") val isTrustedRequest: Boolean = false,
)

/**
 * Reason strings for `accepted = false`. Pinned here so both sides compare the
 * same literals; the spec names the first two explicitly.
 */
object RejectReason {
    const val PROTOCOL_VERSION_MISMATCH = "protocol_version_mismatch"
    const val INVALID_PAIRING_TOKEN = "invalid_pairing_token"
    const val USER_DECLINED = "user_declined"
    const val BUSY = "busy"
}

@Serializable
data class HelloAck(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_type") val deviceType: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("proto_version") val protoVersion: Int,
    val accepted: Boolean,
    val reason: String? = null,
)

// ────────────────────────────────────────────────────────────────────────────
// Section 5 — Transfer request / response
// ────────────────────────────────────────────────────────────────────────────

@Serializable
data class FileManifestEntry(
    @SerialName("file_id") val fileId: String,
    val filename: String,
    @SerialName("relative_path") val relativePath: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("sha256_full") val sha256Full: String,
    @SerialName("chunk_size") val chunkSize: Int = ChunkDataLayout.DEFAULT_CHUNK_SIZE,
    @SerialName("total_chunks") val totalChunks: Int,
    @SerialName("thumbnail_base64") val thumbnailBase64: String? = null,
) {
    /**
     * Number of chunks implied by [sizeBytes] and [chunkSize].
     *
     * Kept as a method rather than trusting the transmitted [totalChunks]
     * blindly: [TransferReceiver] recomputes it and rejects a manifest whose
     * declared count disagrees, which catches both corruption and a malicious
     * sender trying to smuggle a short count past the bitmap.
     */
    val expectedTotalChunks: Int
        get() = if (chunkSize <= 0) 0 else ((sizeBytes + chunkSize - 1) / chunkSize).toInt()
}

@Serializable
data class TransferRequest(
    @SerialName("transfer_id") val transferId: String,
    @SerialName("batch_id") val batchId: String? = null,
    val files: List<FileManifestEntry>,
)

/** `decision` values for [TransferResponse]. */
object TransferDecision {
    const val ACCEPT_ALL = "accept_all"
    const val REJECT_ALL = "reject_all"
    const val PARTIAL = "partial"
}

@Serializable
data class TransferResponse(
    @SerialName("transfer_id") val transferId: String,
    val decision: String,
    @SerialName("accepted_file_ids") val acceptedFileIds: List<String> = emptyList(),
    @SerialName("rejected_file_ids") val rejectedFileIds: List<String> = emptyList(),
    /**
     * Resume support (Section 13): maps `file_id` to the last verified chunk
     * index the receiver already has, so the sender skips straight past it
     * instead of re-sending from zero.
     */
    @SerialName("resume_offsets") val resumeOffsets: Map<String, Int> = emptyMap(),
)

// ────────────────────────────────────────────────────────────────────────────
// Section 6 — Chunk acknowledgements
// ────────────────────────────────────────────────────────────────────────────

@Serializable
data class ChunkAck(
    @SerialName("file_id") val fileId: String,
    @SerialName("chunk_index") val chunkIndex: Int,
)

/** `reason` values for [ChunkNack]. */
object NackReason {
    const val CHECKSUM_MISMATCH = "checksum_mismatch"
    const val WRITE_FAILED = "write_failed"
    const val UNEXPECTED_INDEX = "unexpected_index"
    const val INSUFFICIENT_STORAGE = "insufficient_storage"
}

@Serializable
data class ChunkNack(
    @SerialName("file_id") val fileId: String,
    @SerialName("chunk_index") val chunkIndex: Int,
    val reason: String,
)

@Serializable
data class FileComplete(
    @SerialName("file_id") val fileId: String,
    @SerialName("sha256_full") val sha256Full: String,
)

@Serializable
data class TransferComplete(
    @SerialName("transfer_id") val transferId: String,
    @SerialName("batch_id") val batchId: String? = null,
)

@Serializable
data class TransferCancel(
    @SerialName("transfer_id") val transferId: String,
    val reason: String? = null,
)

// ────────────────────────────────────────────────────────────────────────────
// Section 14 — Errors, and Section 1 — liveness
// ────────────────────────────────────────────────────────────────────────────

/**
 * Error codes, taken verbatim from the Section 14 table.
 *
 * These strings are load-bearing: the receiver's recovery behaviour keys off
 * them, so a typo here turns a handled failure into a crash.
 */
object ErrorCode {
    const val FRAME_TOO_LARGE = "frame_too_large"
    const val DECRYPTION_FAILED = "decryption_failed"
    const val MAX_RETRIES_EXCEEDED = "max_retries_exceeded"
    const val INSUFFICIENT_STORAGE = "insufficient_storage"
    const val CHECKSUM_MISMATCH = "checksum_mismatch"
    const val PROTOCOL_VERSION_MISMATCH = "protocol_version_mismatch"
    const val NONCE_REUSE = "nonce_reuse"
    const val MALFORMED_FRAME = "malformed_frame"
    const val MALFORMED_CHUNK_DATA = "malformed_chunk_data"
    const val UNKNOWN_TRANSFER = "unknown_transfer"
    const val CANCELLED = "cancelled"
    const val INTERNAL = "internal_error"
}

@Serializable
data class ErrorPayload(
    val code: String,
    val message: String? = null,
)

@Serializable
data class Ping(val sentAtEpochMs: Long)

@Serializable
data class Pong(
    @SerialName("echo_sent_at_epoch_ms") val echoSentAtEpochMs: Long,
)

/** Informational only; a peer may ignore it (Section 2). */
@Serializable
data class WindowResize(
    @SerialName("transfer_id") val transferId: String,
    @SerialName("window_size") val windowSize: Int,
)

// ────────────────────────────────────────────────────────────────────────────
// Section 11 — Text / link sharing (distinct from chat)
// ────────────────────────────────────────────────────────────────────────────

@Serializable
data class TextShare(
    val text: String,
    @SerialName("sent_at") val sentAtEpochMs: Long,
)

// ────────────────────────────────────────────────────────────────────────────
// Section G — Chat
// ────────────────────────────────────────────────────────────────────────────

@Serializable
data class ChatMessage(
    @SerialName("message_id") val messageId: String,
    val text: String,
    @SerialName("sent_at") val sentAtEpochMs: Long,
    @SerialName("sender_device_id") val senderDeviceId: String,
)

// ────────────────────────────────────────────────────────────────────────────
// Section 8 — Rooms
// ────────────────────────────────────────────────────────────────────────────

@Serializable
data class RoomMember(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_type") val deviceType: String,
    val ip: String,
    val port: Int,
)

@Serializable
data class RoomAnnounce(
    @SerialName("room_id") val roomId: String,
    @SerialName("room_token") val roomToken: String,
    val member: RoomMember,
)

@Serializable
data class RoomMemberList(
    @SerialName("room_id") val roomId: String,
    val members: List<RoomMember>,
)

// ────────────────────────────────────────────────────────────────────────────
// Section 3 — QR fallback payloads
// ────────────────────────────────────────────────────────────────────────────

/** Direct-device QR: `{v:1, device_id, device_name, ip, port, pairing_token}`. */
@Serializable
data class DeviceQrPayload(
    val v: Int = 1,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    val ip: String,
    val port: Int,
    @SerialName("pairing_token") val pairingToken: String,
)

/** Room QR: `{v:1, room_id, room_token, creator_ip, creator_port}`. */
@Serializable
data class RoomQrPayload(
    val v: Int = 1,
    @SerialName("room_id") val roomId: String,
    @SerialName("room_token") val roomToken: String,
    @SerialName("creator_ip") val creatorIp: String,
    @SerialName("creator_port") val creatorPort: Int,
)

// ────────────────────────────────────────────────────────────────────────────
// Section 3 — mDNS TXT record
// ────────────────────────────────────────────────────────────────────────────

/**
 * The `_morsecode._tcp.local.` service TXT record.
 *
 * `deviceName` is capped at 64 UTF-8 bytes on write: mDNS TXT values are
 * length-prefixed with a single byte, and JmDNS silently drops an over-long
 * entry, which would present as a device that is discoverable but unnamed.
 */
object MdnsTxt {
    const val SERVICE_TYPE = "_morsecode._tcp.local."
    const val MAX_DEVICE_NAME_BYTES = 64

    const val KEY_DEVICE_ID = "device_id"
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_DEVICE_TYPE = "device_type"
    const val KEY_APP_VERSION = "app_version"
    const val KEY_PROTO_VERSION = "proto_version"
    const val KEY_ROOM_ID = "room_id"

    /** Truncates on a UTF-8 code-point boundary, never mid-sequence. */
    fun truncateDeviceName(name: String): String {
        val bytes = name.encodeToByteArray()
        if (bytes.size <= MAX_DEVICE_NAME_BYTES) return name

        var limit = MAX_DEVICE_NAME_BYTES
        // Back off while the cut would land on a continuation byte (10xxxxxx).
        while (limit > 0 && (bytes[limit].toInt() and 0xC0) == 0x80) limit--
        return bytes.decodeToString(0, limit)
    }

    fun toMap(
        deviceId: String,
        deviceName: String,
        deviceType: String,
        appVersion: String,
        protoVersion: Int,
        roomId: String? = null,
    ): Map<String, String> = buildMap {
        put(KEY_DEVICE_ID, deviceId)
        put(KEY_DEVICE_NAME, truncateDeviceName(deviceName))
        put(KEY_DEVICE_TYPE, deviceType)
        put(KEY_APP_VERSION, appVersion)
        put(KEY_PROTO_VERSION, protoVersion.toString())
        if (roomId != null) put(KEY_ROOM_ID, roomId)
    }
}

/** Current wire protocol version. Bump on any incompatible change. */
const val PROTO_VERSION: Int = 1

/** Default TCP port (Section 1). Falls back to an ephemeral port if occupied. */
const val DEFAULT_PORT: Int = 53317
