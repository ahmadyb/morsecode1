package net.morsecode.net

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import net.morsecode.util.Ids

/** A transfer target: where to connect and how to authenticate. */
data class Recipient(
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    /** Sent in HELLO when pairing by QR / manual entry. */
    val pairingToken: String? = null,
    /** True when already in TrustedDeviceRepo, so no token is needed. */
    val trusted: Boolean = false,
)

/**
 * One file in a broadcast.
 *
 * [newSource] is a *factory*: each recipient gets its own [ChunkSource], because
 * a source is a single-read cursor over the bytes and two recipients cannot
 * share one. This is also what makes the Apps/Photos/Videos/Music/Files tabs
 * converge on this path — any tab can build an [OutgoingFile] and hand it here.
 */
data class OutgoingFile(
    val manifest: FileManifestEntry,
    val newSource: () -> ChunkSource,
) {
    val totalBytes: Long get() = manifest.sizeBytes
}

/** Coarse aggregate state for the batch progress UI (Sections 7 and A). */
data class BatchProgress(
    val batchId: String,
    val totalRecipients: Int,
    val queued: Int,
    val active: Int,
    val completed: Int,
    val failed: Int,
    val bytesTransferred: Long,
    val totalBytes: Long,
) {
    val overallFraction: Float
        get() = if (totalBytes <= 0) 0f else (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** Whole-batch result; `recipients` is the expandable per-device breakdown. */
data class BatchResult(
    val batchId: String,
    val recipients: List<RecipientResult>,
) {
    val succeededRecipients: Int get() = recipients.count { it.succeeded }
    val allSucceeded: Boolean get() = recipients.isNotEmpty() && recipients.all { it.succeeded }
}

/**
 * Multi-recipient parallel broadcast (PROTOCOL SPECIFICATION, Section 7).
 *
 * ── ONE BATCH ────────────────────────────────────────────────────────────────
 * A single user action produces one [batchId]. Every file row written to
 * `transfer_state` carries that `batch_id`, which is what lets the History tab
 * render ONE batch entry expandable into per-device outcomes (Section 13's
 * `selectByBatch`). There is deliberately no separate "batch" table — the
 * batch is an attribute of the per-file rows.
 *
 * ── FULL ISOLATION PER RECIPIENT ────────────────────────────────────────────
 * Each recipient gets its own coroutine, its own TCP connection, its own
 * handshake, its own session key and its own windowed [TransferSender]. A slow
 * or lossy peer therefore throttles only itself; it can never stall the other
 * five. That is the spec's "fully parallel" requirement, and it is why the
 * per-recipient work happens inside its own `launch`.
 *
 * ── THE CAP ─────────────────────────────────────────────────────────────────
 * `MAX_CONCURRENT_RECIPIENTS = 6`. Overflow recipients sit on the [Semaphore]
 * (state = QUEUED) and are started as active slots free — exactly the
 * "queue overflow, process as slots free up" row of the Section 14 table. The
 * Semaphore, not a hand-rolled counter, is what makes this correct under
 * cancellation: `withPermit` releases even if the body throws.
 */
class BroadcastCoordinator(
    private val crypto: CryptoProvider,
    private val identity: DeviceIdentity,
    private val throttle: BandwidthThrottle = BandwidthThrottle(),
    private val store: TransferStateStore? = null,
    private val maxConcurrent: Int = MAX_CONCURRENT_RECIPIENTS,
    private val chunkSize: Int = ChunkDataLayout.DEFAULT_CHUNK_SIZE,
    private val nowMillis: () -> Long,
    /** Opens the connection and runs the Section 4 handshake. */
    private val connect: suspend (Recipient) -> HandshakeOutcome,
    /**
     * Injectable for tests: given an established connection, send all files.
     * `null` uses the real per-file [TransferSender] pipeline (Phase 4).
     */
    private val sendTo: (suspend (SecureConnection, Recipient, List<OutgoingFile>, String) -> List<SendFileResult>)? = null,
) {
    private val _progress = MutableStateFlow(
        BatchProgress("", 0, 0, 0, 0, 0, 0, 0),
    )
    val progress: StateFlow<BatchProgress> = _progress.asStateFlow()

    /**
     * Sends [files] to every [recipient], at most [maxConcurrent] at a time.
     *
     * Returns once every recipient has finished (completed, failed or
     * rejected). Cancellation of the enclosing scope aborts all recipients.
     */
    suspend fun sendBatch(
        files: List<OutgoingFile>,
        recipients: List<Recipient>,
    ): BatchResult {
        val batchId = Ids.uuid(crypto.randomBytes(16))
        val semaphore = Semaphore(maxConcurrent)
        val mutex = Mutex()
        val results = HashMap<String, RecipientResult>()
        val counts = Counts(total = recipients.size)
        val bytesDone = LongArray(1) // single accumulator, guarded by [mutex]
        val totalBytes = files.sumOf { it.totalBytes } * recipients.size

        // All state mutation and every publish happen inside [mutex], so the
        // emitted BatchProgress is always a consistent snapshot.
        fun snapshot() {
            _progress.value = BatchProgress(
                batchId = batchId,
                totalRecipients = counts.total,
                queued = counts.queued,
                active = counts.active,
                completed = counts.completed,
                failed = counts.failed,
                bytesTransferred = bytesDone[0],
                totalBytes = totalBytes,
            )
        }

        coroutineScope {
            recipients.map { recipient ->
                launch {
                    mutex.withLock { counts.queued++; snapshot() }

                    semaphore.withPermit {
                        mutex.withLock { counts.queued--; counts.active++; snapshot() }

                        val result = sendToOne(recipient, files, batchId)

                        mutex.withLock {
                            counts.active--
                            if (result.succeeded) counts.completed++ else counts.failed++
                            results[recipient.deviceId] = result
                            // Verified chunks per file, times the chunk size, is
                            // only an approximation of bytes; use the manifest's
                            // per-file size for completed files instead.
                            bytesDone[0] += completedBytes(result, files)
                            snapshot()
                        }
                    }
                }
            }.forEach { it.join() }
        }

        return BatchResult(batchId, recipients.mapNotNull { results[it.deviceId] })
    }

    /**
     * Bytes credited to a finished recipient: the full size of each file whose
     * transfer completed, nothing for partial or failed ones. Coarse, but never
     * overstates progress — a partially sent file counts 0 until it verifies.
     */
    private fun completedBytes(result: RecipientResult, files: List<OutgoingFile>): Long {
        val sizeByFileId = files.associate { it.manifest.fileId to it.manifest.sizeBytes }
        return result.files.sumOf { f ->
            if (f.succeeded) sizeByFileId[f.fileId] ?: 0L else 0L
        }
    }

    private suspend fun sendToOne(
        recipient: Recipient,
        files: List<OutgoingFile>,
        batchId: String,
    ): RecipientResult {
        val outcome = try {
            connect(recipient)
        } catch (e: Exception) {
            HandshakeOutcome.Failure(ErrorCode.INTERNAL, "connect failed: ${e.message}")
        }

        return when (outcome) {
            is HandshakeOutcome.Success -> {
                val fileResults = sendTo?.invoke(outcome.connection, recipient, files, batchId)
                    ?: defaultSend(outcome.connection, recipient, files, batchId)
                runCatching { outcome.connection.close() }
                RecipientResult(
                    deviceId = recipient.deviceId,
                    deviceName = recipient.deviceName,
                    files = fileResults,
                    errorCode = null,
                )
            }

            is HandshakeOutcome.Rejected -> RecipientResult(
                deviceId = recipient.deviceId,
                deviceName = recipient.deviceName,
                files = emptyList(),
                errorCode = "rejected:${outcome.reason}",
            )

            is HandshakeOutcome.Failure -> RecipientResult(
                deviceId = recipient.deviceId,
                deviceName = recipient.deviceName,
                files = emptyList(),
                errorCode = outcome.code,
            )
        }
    }

    /** Real pipeline: one [TransferSender] per connection, files sent in order. */
    private suspend fun defaultSend(
        connection: SecureConnection,
        recipient: Recipient,
        files: List<OutgoingFile>,
        batchId: String,
    ): List<SendFileResult> {
        val sender = TransferSender(
            connection = connection,
            crypto = crypto,
            throttle = throttle,
            store = store,
            chunkSize = chunkSize,
            nowMillis = nowMillis,
        )
        sender.manifestBatchId = batchId

        return files.map { file ->
            val transferId = Ids.uuid(crypto.randomBytes(16))
            sender.sendFile(transferId, file.manifest, file.newSource())
        }
    }

    private class Counts(
        val total: Int,
        var queued: Int = 0,
        var active: Int = 0,
        var completed: Int = 0,
        var failed: Int = 0,
    )

    companion object {
        /** MANDATORY CAP (Section 7). */
        const val MAX_CONCURRENT_RECIPIENTS: Int = 6
    }
}
