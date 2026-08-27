package net.morsecode.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sender state machine with windowed / pipelined chunk transfer
 * (PROTOCOL SPECIFICATION, Section 6).
 *
 * One instance drives one file to one peer over one [SecureConnection]. A
 * multi-recipient broadcast creates one of these per recipient (Section 7),
 * each with its own connection, handshake, session key and window.
 *
 * ── THE LOOP ────────────────────────────────────────────────────────────────
 * The whole algorithm runs as a single-threaded event loop: fill the window,
 * then block on one event, apply it, repeat. That shape is deliberate.
 * Sections 6.2–6.4 all mutate `windowSize`, `inFlight` and the bitmap, and
 * doing that from three concurrent coroutines would need locks around all
 * three. Funelling everything through one [Channel] removes the concurrency
 * entirely, so none of this state needs synchronisation — and, more usefully,
 * it makes the state machine testable with a fake clock and no timing flakiness.
 *
 * ── WINDOW CONTROL ──────────────────────────────────────────────────────────
 * Additive increase, multiplicative decrease — the same shape as TCP congestion
 * control, for the same reason: it converges quickly on a good link and backs
 * off hard on a lossy one.
 *   ACK          -> windowSize += 1, capped at [maxWindow]          (32)
 *   NACK/timeout -> windowSize /= 2, floored at [minWindow]          (1)
 * starts at 4 per the spec.
 */
class TransferSender(
    private val connection: SecureConnection,
    private val crypto: CryptoProvider,
    private val throttle: BandwidthThrottle = BandwidthThrottle(),
    private val store: TransferStateStore? = null,
    private val initialWindow: Int = DEFAULT_INITIAL_WINDOW,
    private val maxWindow: Int = DEFAULT_MAX_WINDOW,
    private val minWindow: Int = DEFAULT_MIN_WINDOW,
    private val chunkTimeoutMs: Long = DEFAULT_CHUNK_TIMEOUT_MS,
    private val sweepIntervalMs: Long = DEFAULT_SWEEP_INTERVAL_MS,
    private val maxConsecutiveNacksPerChunk: Int = DEFAULT_MAX_CONSECUTIVE_NACKS,
    private val chunkSize: Int = ChunkDataLayout.DEFAULT_CHUNK_SIZE,
    private val nowMillis: () -> Long,
) {
    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    /**
     * Sends one file.
     *
     * @param resumeFromChunk chunks below this index are already verified on
     *   the receiver (from `TRANSFER_RESPONSE.resume_offsets`) and are skipped.
     * @param alreadyVerified the persisted bitmap, when resuming a transfer
     *   that was interrupted mid-flight.
     */
    suspend fun sendFile(
        transferId: String,
        manifest: FileManifestEntry,
        source: ChunkSource,
        resumeFromChunk: Int = 0,
        alreadyVerified: VerifiedChunkBitmap? = null,
    ): SendFileResult {
        val startedAt = nowMillis()
        val fileIdBytes = FileIds.toBytes(manifest.fileId)
        val bitmap = alreadyVerified?.copy() ?: VerifiedChunkBitmap.empty(manifest.totalChunks)

        // Chunks below the resume offset are already on the receiver. Mark them
        // so `isComplete` can be reached without re-sending them.
        for (i in 0 until resumeFromChunk.coerceAtMost(manifest.totalChunks)) bitmap.markVerified(i)

        val state = SendState(
            transferId = transferId,
            manifest = manifest,
            source = source,
            fileIdBytes = fileIdBytes,
            bitmap = bitmap,
            nextChunkToSend = resumeFromChunk.coerceAtMost(manifest.totalChunks),
            startedAt = startedAt,
        )

        store?.begin(
            TransferRecord(
                transferId = state.transferId,
                fileId = manifest.fileId,
                batchId = manifestBatchId,
                peerDeviceId = connection.peer.deviceId,
                filename = manifest.filename,
                totalChunks = manifest.totalChunks,
                verifiedBitmap = bitmap.serialize(),
                sha256Full = manifest.sha256Full,
                status = TransferStatus.TRANSFERRING,
                direction = TransferDirection.SENT,
                updatedAtEpochMs = startedAt,
            ),
        )

        publishProgress(state)

        return try {
            coroutineScope {
                val events = Channel<SendEvent>(Channel.UNLIMITED)
                val reader = launchReader(events)
                val ticker = launchTicker(events)
                try {
                    runLoop(state, events)
                } finally {
                    reader.cancelAndJoin()
                    ticker.cancelAndJoin()
                    events.close()
                }
            }
        } finally {
            runCatching { source.close() }
        }
    }

    /** Set by [BroadcastCoordinator] before each file so history rows group. */
    var manifestBatchId: String? = null

    // ────────────────────────────────────────────────────────────────────────
    // Mutable per-file state. Confined to the event loop's coroutine.
    // ────────────────────────────────────────────────────────────────────────
    private class SendState(
        val transferId: String,
        val manifest: FileManifestEntry,
        val source: ChunkSource,
        val fileIdBytes: ByteArray,
        val bitmap: VerifiedChunkBitmap,
        var nextChunkToSend: Int,
        val startedAt: Long,
    ) {
        var windowSize: Int = DEFAULT_INITIAL_WINDOW
        val inFlight = HashMap<Int, Long>()
        val nackCounts = HashMap<Int, Int>()
        var bytesTransferred: Long = 0L
        var result: SendFileResult? = null
    }

    private sealed interface SendEvent {
        data class Ack(val chunkIndex: Int) : SendEvent
        data class Nack(val chunkIndex: Int, val reason: String) : SendEvent
        data class PeerError(val code: String, val message: String?) : SendEvent
        data object PeerClosed : SendEvent
        data object Sweep : SendEvent
    }

    // ────────────────────────────────────────────────────────────────────────

    private fun CoroutineScope.launchReader(events: Channel<SendEvent>): Job = launch {
        while (isActive) {
            when (val message = connection.receive()) {
                is ReceivedMessage.Payload -> when (message.type) {
                    MessageType.CHUNK_ACK -> {
                        val ack = runCatching { MessageJson.decodeFromBytes<ChunkAck>(message.plaintext) }.getOrNull()
                        if (ack != null) events.send(SendEvent.Ack(ack.chunkIndex))
                    }

                    MessageType.CHUNK_NACK -> {
                        val nack = runCatching { MessageJson.decodeFromBytes<ChunkNack>(message.plaintext) }.getOrNull()
                        if (nack != null) events.send(SendEvent.Nack(nack.chunkIndex, nack.reason))
                    }

                    MessageType.ERROR -> {
                        val err = runCatching { MessageJson.decodeFromBytes<ErrorPayload>(message.plaintext) }.getOrNull()
                        events.send(SendEvent.PeerError(err?.code ?: ErrorCode.INTERNAL, err?.message))
                        return@launch
                    }

                    // PING/PONG, WINDOW_RESIZE and unrelated traffic arriving on
                    // a connection we own for one file are simply not our
                    // business; dropping them is correct, not lossy.
                    else -> Unit
                }

                is ReceivedMessage.PeerError -> {
                    events.send(SendEvent.PeerError(message.code, message.message))
                    return@launch
                }

                is ReceivedMessage.PeerClosed -> {
                    events.send(SendEvent.PeerClosed)
                    return@launch
                }

                is ReceivedMessage.ProtocolViolation -> {
                    events.send(SendEvent.PeerError(message.code, message.reason))
                    return@launch
                }
            }
        }
    }

    /** Section 6 step 4: a sweep every 500 ms looking for timed-out chunks. */
    private fun CoroutineScope.launchTicker(events: Channel<SendEvent>): Job = launch {
        while (isActive) {
            delay(sweepIntervalMs)
            events.send(SendEvent.Sweep)
        }
    }

    // ────────────────────────────────────────────────────────────────────────

    private suspend fun runLoop(state: SendState, events: Channel<SendEvent>): SendFileResult {
        val speed = SpeedEstimator(nowMillis = nowMillis)

        while (state.result == null) {
            // Section 6 step 1: fill the window.
            fillWindow(state)

            if (state.bitmap.isComplete) {
                completeTransfer(state, speed)
                break
            }
            if (state.nextChunkToSend >= state.manifest.totalChunks && state.inFlight.isEmpty()) {
                // Everything sent, nothing outstanding, but the bitmap is not
                // complete: some chunk was dropped from in-flight without being
                // verified. Treat as a hard failure rather than hanging forever.
                fail(state, ErrorCode.MAX_RETRIES_EXCEEDED, "all chunks sent but ${state.bitmap.verifiedCount}/${state.manifest.totalChunks} verified")
                break
            }

            when (val event = events.receive()) {
                is SendEvent.Ack -> onAck(state, event.chunkIndex, speed)
                is SendEvent.Nack -> onNack(state, event.chunkIndex, event.reason)
                is SendEvent.Sweep -> onSweep(state)
                is SendEvent.PeerError -> fail(state, event.code, event.message ?: "peer error")
                is SendEvent.PeerClosed -> pauseForResume(state)
            }
            publishProgress(state, speed)
        }
        return state.result ?: fail(state, ErrorCode.INTERNAL, "transfer loop exited without a result")
    }

    /** Section 6 step 1. */
    private suspend fun fillWindow(state: SendState) {
        while (state.nextChunkToSend < state.manifest.totalChunks &&
            state.inFlight.size < state.windowSize
        ) {
            val index = state.nextChunkToSend
            if (state.bitmap.isVerified(index)) {
                state.nextChunkToSend++
                continue
            }
            sendChunk(state, index)
            state.nextChunkToSend++
        }
    }

    private suspend fun sendChunk(state: SendState, index: Int) {
        val bytes = state.source.readChunk(index)

        // Section 12: throttle *before* the write, with a non-blocking delay.
        throttle.acquire(bytes.size)

        val digest = crypto.sha256(bytes)
        val payload = ChunkDataLayout.encode(
            fileId = state.fileIdBytes,
            chunkIndex = index,
            chunkSha256 = digest,
            chunkBytes = bytes,
        )
        connection.send(MessageType.CHUNK_DATA, payload)
        state.inFlight[index] = nowMillis()
    }

    /** Section 6 step 2. */
    private suspend fun onAck(state: SendState, chunkIndex: Int, speed: SpeedEstimator) {
        if (chunkIndex !in 0 until state.manifest.totalChunks) return
        if (!state.inFlight.containsKey(chunkIndex) && state.bitmap.isVerified(chunkIndex)) {
            // Duplicate ACK for something already verified. Still grow the
            // window — a duplicate ACK means the link delivered twice, which is
            // good news, not bad.
            state.windowSize = (state.windowSize + 1).coerceAtMost(maxWindow)
            return
        }
        state.inFlight.remove(chunkIndex)
        state.nackCounts.remove(chunkIndex)

        val changed = state.bitmap.markVerified(chunkIndex)
        if (changed) {
            state.bytesTransferred += minOf(chunkSize.toLong(), remainingBytesFor(state, chunkIndex))
            speed.record(state.bytesTransferred)

            // Section 6 step 2, explicitly: "persist verified state (SQLDelight
            // write BEFORE considering complete)". The write lands here, before
            // the loop gets a chance to observe isComplete.
            store?.markChunkVerified(
                transferId = state.transferId,
                fileId = state.manifest.fileId,
                bitmap = state.bitmap.serialize(),
            )
        }
        state.windowSize = (state.windowSize + 1).coerceAtMost(maxWindow)
    }

    /**
     * Byte length of chunk [index], which is short only for the final chunk.
     */
    private fun remainingBytesFor(state: SendState, index: Int): Long {
        val total = state.manifest.sizeBytes
        val start = index.toLong() * chunkSize
        if (start >= total) return 0
        return minOf(chunkSize.toLong(), total - start)
    }

    /** Section 6 step 3. */
    private suspend fun onNack(state: SendState, chunkIndex: Int, reason: String) {
        if (chunkIndex !in 0 until state.manifest.totalChunks) return

        val attempts = (state.nackCounts[chunkIndex] ?: 0) + 1
        state.nackCounts[chunkIndex] = attempts

        // Section 14: 3+ consecutive NACKs for the same chunk aborts the file.
        if (attempts >= maxConsecutiveNacksPerChunk) {
            fail(
                state,
                ErrorCode.MAX_RETRIES_EXCEEDED,
                "chunk $chunkIndex NACKed $attempts times (last reason: $reason)",
            )
            return
        }

        // Halve the window (floor at min) and resend with a NEW nonce — the
        // nonce comes from the cipher's own counter, so any fresh seal() call
        // satisfies this without extra bookkeeping.
        state.windowSize = (state.windowSize / 2).coerceAtLeast(minWindow)
        state.inFlight.remove(chunkIndex)
        resendChunk(state, chunkIndex)
    }

    /** Section 6 step 4. */
    private suspend fun onSweep(state: SendState) {
        val now = nowMillis()
        val timedOut = state.inFlight.entries
            .filter { now - it.value > chunkTimeoutMs }
            .map { it.key }

        if (timedOut.isEmpty()) return

        // Halve once for the whole sweep, not once per chunk: a burst of
        // timeouts after a stall would otherwise collapse the window to 1 in a
        // single pass and take a long time to recover.
        state.windowSize = (state.windowSize / 2).coerceAtLeast(minWindow)
        for (index in timedOut) {
            state.inFlight.remove(index)
            resendChunk(state, index)
        }
    }

    private suspend fun resendChunk(state: SendState, index: Int) {
        if (state.bitmap.isVerified(index)) return
        // Resending puts the chunk back in flight without moving the send
        // pointer, which may legitimately be past it already.
        sendChunk(state, index)
    }

    /** Section 6 step 5: all chunks verified -> full-file SHA-256 -> FILE_COMPLETE. */
    private suspend fun completeTransfer(state: SendState, speed: SpeedEstimator) {
        val actual = state.source.sha256Full()
        val expected = runCatching { Hex.decode(state.manifest.sha256Full) }.getOrNull()

        if (expected != null && !actual.contentEquals(expected)) {
            // Section 14: full-file checksum mismatch is a hard failure. The
            // retry must use a new transfer_id, which is the caller's job —
            // this instance has no business inventing one.
            fail(state, ErrorCode.CHECKSUM_MISMATCH, "full-file SHA-256 does not match the manifest")
            return
        }

        connection.sendJson(
            MessageType.FILE_COMPLETE,
            FileComplete(fileId = state.manifest.fileId, sha256Full = state.manifest.sha256Full),
        )
        store?.setStatus(state.transferId, state.manifest.fileId, TransferStatus.COMPLETED)
        state.result = SendFileResult(
            fileId = state.manifest.fileId,
            status = TransferStatus.COMPLETED,
            verifiedChunks = state.bitmap.verifiedCount,
            totalChunks = state.manifest.totalChunks,
            elapsedMillis = nowMillis() - state.startedAt,
        )
        speed.record(state.bytesTransferred)
        publishProgress(state, speed)
    }

    private suspend fun fail(state: SendState, code: String, message: String) {
        runCatching {
            connection.send(MessageType.ERROR, MessageJson.encodeToBytes(ErrorPayload(code, message)))
        }
        store?.setStatus(state.transferId, state.manifest.fileId, TransferStatus.FAILED)
        state.result = SendFileResult(
            fileId = state.manifest.fileId,
            status = TransferStatus.FAILED,
            verifiedChunks = state.bitmap.verifiedCount,
            totalChunks = state.manifest.totalChunks,
            elapsedMillis = nowMillis() - state.startedAt,
            errorCode = code,
            errorMessage = message,
        )
    }

    /**
     * Section 14, row 1: a TCP drop mid-transfer is `paused`, not `failed`.
     *
     * The in-flight window is discarded (its ACKs are unreachable now) and the
     * verified bitmap — already persisted on every ACK — is what a later
     * resume replays from.
     */
    private suspend fun pauseForResume(state: SendState) {
        state.inFlight.clear()
        store?.setStatus(state.transferId, state.manifest.fileId, TransferStatus.PAUSED)
        state.result = SendFileResult(
            fileId = state.manifest.fileId,
            status = TransferStatus.PAUSED,
            verifiedChunks = state.bitmap.verifiedCount,
            totalChunks = state.manifest.totalChunks,
            elapsedMillis = nowMillis() - state.startedAt,
            errorCode = null,
            errorMessage = "peer closed the connection mid-transfer; resumable",
        )
    }

    private fun publishProgress(state: SendState, speed: SpeedEstimator? = null) {
        val verified = state.bitmap.verifiedCount
        val totalBytes = state.manifest.sizeBytes
        val remaining = (totalBytes - state.bytesTransferred).coerceAtLeast(0)
        _progress.value = TransferProgress(
            transferId = state.transferId,
            fileId = state.manifest.fileId,
            filename = state.manifest.filename,
            totalChunks = state.manifest.totalChunks,
            verifiedChunks = verified,
            bytesTransferred = state.bytesTransferred,
            totalBytes = totalBytes,
            windowSize = state.windowSize,
            speedBytesPerSecond = speed?.speedBytesPerSecond() ?: 0,
            etaMillis = speed?.etaMillis(remaining),
            status = state.result?.status ?: TransferStatus.TRANSFERRING,
            error = state.result?.errorMessage,
        )
    }

    companion object {
        const val DEFAULT_INITIAL_WINDOW: Int = 4
        const val DEFAULT_MAX_WINDOW: Int = 32
        const val DEFAULT_MIN_WINDOW: Int = 1
        const val DEFAULT_CHUNK_TIMEOUT_MS: Long = 3000
        const val DEFAULT_SWEEP_INTERVAL_MS: Long = 500
        const val DEFAULT_MAX_CONSECUTIVE_NACKS: Int = 3
    }
}
