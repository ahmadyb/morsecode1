package net.morsecode.net

import kotlinx.coroutines.flow.StateFlow

/** Values stored in `transfer_state.status`. */
enum class TransferStatus(val wire: String) {
    TRANSFERRING("transferring"),
    PAUSED("paused"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    companion object {
        fun fromWire(value: String): TransferStatus =
            entries.firstOrNull { it.wire == value } ?: TRANSFERRING
    }
}

/** Values stored in `transfer_state.direction` and `chat_message.direction`. */
enum class TransferDirection(val wire: String) {
    SENT("sent"),
    RECEIVED("received");

    companion object {
        fun fromWire(value: String): TransferDirection =
            entries.firstOrNull { it.wire == value } ?: SENT
    }
}

/** A persisted `transfer_state` row. */
data class TransferRecord(
    val transferId: String,
    val fileId: String,
    val batchId: String?,
    val peerDeviceId: String,
    val filename: String,
    val totalChunks: Int,
    val verifiedBitmap: String,
    val sha256Full: String,
    val status: TransferStatus,
    val direction: TransferDirection,
    val updatedAtEpochMs: Long,
)

/**
 * Persistence seam between the transfer state machines and SQLDelight.
 *
 * Declared as an interface rather than used directly so the Phase 4 windowed
 * transfer can be tested with an in-memory store — which is exactly how the
 * spec wants Phase 4 verified, with two JVM instances on localhost, long before
 * an Android build exists.
 *
 * Implementations: `TransferStateRepo` (SQLDelight, both platforms) and
 * `InMemoryTransferStateStore` (tests).
 */
interface TransferStateStore {
    suspend fun begin(record: TransferRecord)

    /**
     * Records one verified chunk.
     *
     * MUST be durable before the caller treats the chunk as done: Section 6
     * step 2 requires the SQLDelight write to land *before* the transfer is
     * considered complete. A crash between "counted as verified in memory" and
     * "persisted" would otherwise make a resumed transfer skip a chunk it never
     * actually received.
     */
    suspend fun markChunkVerified(transferId: String, fileId: String, bitmap: String)

    suspend fun setStatus(transferId: String, fileId: String, status: TransferStatus)

    suspend fun load(transferId: String, fileId: String): TransferRecord?

    /** Resume support: everything paused or failed with this peer. */
    suspend fun loadResumable(peerDeviceId: String, direction: TransferDirection): List<TransferRecord>
}

/**
 * Reads chunk data on the sending side.
 *
 * Abstraction over "where the bytes come from" so the sender is identical for a
 * file picked from the Files tab, an APK extracted from PackageManager
 * (Section E.4), or a photo from MediaStore — the spec's requirement that all
 * five Library tabs converge on one transfer path.
 */
interface ChunkSource {
    val totalChunks: Int
    val totalBytes: Long

    /**
     * @return the bytes of chunk [index]. Must be exactly `chunkSize` long for
     *   every index except the last, which may be shorter.
     */
    suspend fun readChunk(index: Int): ByteArray

    /** SHA-256 of the entire payload, verified once after all chunks are acked. */
    suspend fun sha256Full(): ByteArray

    suspend fun close()
}

/**
 * Writes chunk data on the receiving side.
 *
 * Random-access by design: Section 6 step 1 requires writing each chunk to its
 * correct byte offset rather than appending, because chunks legitimately arrive
 * out of order under the windowed protocol.
 */
interface ChunkSink {
    val totalChunks: Int

    /** Writes [bytes] at `index * chunkSize`. */
    suspend fun writeChunk(index: Int, bytes: ByteArray)

    /** SHA-256 of everything written so far, for the full-file check. */
    suspend fun sha256Full(): ByteArray

    suspend fun close()

    /**
     * Signals that the sink cannot accept more data (disk full).
     *
     * Reported as `ERROR{code:"insufficient_storage"}` per the Section 14 table.
     * Checked *before* the write rather than derived from the exception, so a
     * half-written chunk never has to be rolled back.
     */
    suspend fun hasSpaceFor(bytes: Long): Boolean
}

/** Aggregate UI state for one file transfer. */
data class TransferProgress(
    val transferId: String,
    val fileId: String,
    val filename: String,
    val totalChunks: Int,
    val verifiedChunks: Int,
    val bytesTransferred: Long,
    val totalBytes: Long,
    /** Exposed for the optional debug indicator described in Section A. */
    val windowSize: Int,
    val speedBytesPerSecond: Long,
    val etaMillis: Long?,
    val status: TransferStatus,
    val error: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** Per-recipient breakdown for a broadcast batch (Sections 7 and A). */
data class RecipientProgress(
    val deviceId: String,
    val deviceName: String,
    val progress: TransferProgress?,
    val outcome: BroadcastOutcome,
)

enum class BroadcastOutcome { QUEUED, ACTIVE, COMPLETED, FAILED, REJECTED }

/** Final result of one file transfer attempt. */
data class SendFileResult(
    val fileId: String,
    val status: TransferStatus,
    val verifiedChunks: Int,
    val totalChunks: Int,
    val elapsedMillis: Long,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    val succeeded: Boolean get() = status == TransferStatus.COMPLETED
}

/** Outcome of a whole batch to one recipient. */
data class RecipientResult(
    val deviceId: String,
    val deviceName: String,
    val files: List<SendFileResult>,
    val errorCode: String? = null,
) {
    val succeeded: Boolean get() = files.isNotEmpty() && files.all { it.succeeded }
}

/**
 * Rolling speed / ETA estimator.
 *
 * A sliding window over recent samples rather than a whole-transfer average:
 * the average never recovers from a slow start, so a transfer that stalls and
 * resumes shows an ETA that is wrong for the rest of its life.
 */
class SpeedEstimator(
    private val windowSize: Int = 8,
    private val nowMillis: () -> Long,
) {
    private data class Sample(val atMillis: Long, val cumulativeBytes: Long)

    private val samples = ArrayDeque<Sample>()
    private var lastBytes: Long = 0

    fun record(cumulativeBytes: Long) {
        val now = nowMillis()
        samples.addLast(Sample(now, cumulativeBytes))
        while (samples.size > windowSize) samples.removeFirst()
        lastBytes = cumulativeBytes
    }

    /** Bytes/second over the window, or 0 when there is not enough data yet. */
    fun speedBytesPerSecond(): Long {
        if (samples.size < 2) return 0
        val oldest = samples.first()
        val newest = samples.last()
        val elapsed = newest.atMillis - oldest.atMillis
        if (elapsed <= 0) return 0
        val delta = newest.cumulativeBytes - oldest.cumulativeBytes
        return (delta * 1000L) / elapsed
    }

    fun etaMillis(remainingBytes: Long): Long? {
        val speed = speedBytesPerSecond()
        if (speed <= 0) return null
        return (remainingBytes * 1000L) / speed
    }

    fun reset() {
        samples.clear()
        lastBytes = 0
    }
}

/** Convenience: exposes progress as observable state for the UI layer. */
interface TransferProgressSink {
    val progress: StateFlow<TransferProgress?>
}
