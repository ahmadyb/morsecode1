package net.morsecode.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Receiver state machine (PROTOCOL SPECIFICATION, Section 6).
 *
 * Three rules drive the whole implementation:
 *  1. Chunks arrive **in any order** and are written to their correct byte
 *     offset via random-access I/O, never appended. The windowed sender can
 *     retransmit an early chunk after later ones landed, so an append-only sink
 *     would silently produce a corrupt file.
 *  2. Every chunk is SHA-256 verified **before** it is written. An invalid
 *     chunk is NACKed and discarded, so bad bytes never touch the output file.
 *  3. The verified bitmap is the only record of completeness. Once it is full,
 *     a full-file SHA-256 is computed as the final gate.
 *
 * Unlike the sender there is no window to manage here — flow control is the
 * sender's job. The receiver simply acknowledges what it accepted, which is
 * what tells the sender to grow its window.
 */
class TransferReceiver(
    private val connection: SecureConnection,
    private val crypto: CryptoProvider,
    private val store: TransferStateStore? = null,
    /** Builds the output sink for an accepted file. Called once per file. */
    private val sinkFactory: suspend (TransferRequest, FileManifestEntry) -> ChunkSink?,
    /**
     * Decides whether to accept an incoming request (Sections 5 and 10).
     *
     * Implementations apply the auto-accept setting and, when that does not
     * settle it, prompt the user. Returning a response with
     * `decision = reject_all` is the "user declined" path.
     */
    private val decisionPolicy: suspend (TransferRequest) -> TransferResponse,
    private val nowMillis: () -> Long,
) {
    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    /** Files currently open, keyed by `file_id`. */
    private val openFiles = HashMap<String, OpenFile>()

    private class OpenFile(
        val transferId: String,
        val manifest: FileManifestEntry,
        val sink: ChunkSink,
        val bitmap: VerifiedChunkBitmap,
        val fileIdBytes: ByteArray,
        val startedAt: Long,
    ) {
        var bytesWritten: Long = 0
    }

    /**
     * Runs the receive loop until the peer closes, errors, or cancels.
     *
     * @return the ids of files that completed their full-file checksum.
     */
    suspend fun receiveLoop(): ReceiveSummary {
        val completed = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()

        while (true) {
            when (val message = connection.receive()) {
                is ReceivedMessage.Payload -> when (message.type) {
                    MessageType.TRANSFER_REQUEST -> onRequest(message.plaintext, completed, failed)

                    MessageType.CHUNK_DATA -> onChunkData(message.plaintext, completed, failed)

                    MessageType.TRANSFER_CANCEL -> {
                        val cancel = runCatching {
                            MessageJson.decodeFromBytes<TransferCancel>(message.plaintext)
                        }.getOrNull()
                        onCancel(cancel)
                    }

                    MessageType.PING -> connection.sendJson(
                        MessageType.PONG,
                        Pong(runCatching { MessageJson.decodeFromBytes<Ping>(message.plaintext).sentAtEpochMs }.getOrDefault(0L)),
                    )

                    // FILE_COMPLETE / TRANSFER_COMPLETE travel the other way;
                    // TEXT_SHARE and CHAT_MESSAGE are handled by their own
                    // dispatchers on a shared connection.
                    else -> Unit
                }

                is ReceivedMessage.PeerClosed -> break
                is ReceivedMessage.PeerError -> {
                    failed += "<session>" to message.code
                    break
                }

                is ReceivedMessage.ProtocolViolation -> {
                    failed += "<session>" to message.code
                    break
                }
            }
        }

        closeAll(TransferStatus.PAUSED)
        return ReceiveSummary(completed, failed)
    }

    // ────────────────────────────────────────────────────────────────────────

    private suspend fun onRequest(
        plaintext: ByteArray,
        completed: MutableList<String>,
        failed: MutableList<Pair<String, String>>,
    ) {
        val request = runCatching { MessageJson.decodeFromBytes<TransferRequest>(plaintext) }
            .getOrElse {
                connection.fatal(ErrorCode.MALFORMED_FRAME, "unparsable TRANSFER_REQUEST: ${it.message}")
                return
            }

        // Reject a manifest whose declared chunk count disagrees with its own
        // size and chunk_size. Section 5 gives the receiver no reason to trust
        // `total_chunks`, and a short count would let a sender finish a file
        // before all its bytes arrived.
        for (file in request.files) {
            if (file.expectedTotalChunks != file.totalChunks) {
                connection.sendJson(
                    MessageType.TRANSFER_RESPONSE,
                    TransferResponse(
                        transferId = request.transferId,
                        decision = TransferDecision.REJECT_ALL,
                        rejectedFileIds = request.files.map { it.fileId },
                    ),
                )
                failed += file.fileId to "manifest total_chunks ${file.totalChunks} != " +
                    "sizeBytes/chunk_size ${file.expectedTotalChunks}"
                return
            }
            if (file.chunkSize <= 0 || file.chunkSize > Framing.MAX_DECRYPTED_PAYLOAD) {
                connection.sendJson(
                    MessageType.TRANSFER_RESPONSE,
                    TransferResponse(
                        transferId = request.transferId,
                        decision = TransferDecision.REJECT_ALL,
                        rejectedFileIds = request.files.map { it.fileId },
                    ),
                )
                failed += file.fileId to "chunk_size ${file.chunkSize} is out of range"
                return
            }
        }

        val response = decisionPolicy(request)
        connection.sendJson(MessageType.TRANSFER_RESPONSE, response)

        if (response.decision == TransferDecision.REJECT_ALL) return

        for (file in request.files) {
            if (file.fileId in response.rejectedFileIds) continue

            val sink = sinkFactory(request, file)
            if (sink == null) {
                // sinkFactory returning null is how a platform reports "cannot
                // create the output file" — typically no space left.
                connection.send(
                    MessageType.ERROR,
                    MessageJson.encodeToBytes(
                        ErrorPayload(ErrorCode.INSUFFICIENT_STORAGE, "cannot open output for ${file.filename}"),
                    ),
                )
                failed += file.fileId to ErrorCode.INSUFFICIENT_STORAGE
                return
            }

            val resumeOffset = response.resumeOffsets[file.fileId] ?: 0
            val bitmap = VerifiedChunkBitmap.empty(file.totalChunks)
            for (i in 0 until resumeOffset.coerceAtMost(file.totalChunks)) bitmap.markVerified(i)

            openFiles[file.fileId] = OpenFile(
                transferId = request.transferId,
                manifest = file,
                sink = sink,
                bitmap = bitmap,
                fileIdBytes = runCatching { FileIds.toBytes(file.fileId) }.getOrElse {
                    connection.fatal(ErrorCode.MALFORMED_CHUNK_DATA, "invalid file_id '${file.fileId}'")
                    return
                },
                startedAt = nowMillis(),
            )

            store?.begin(
                TransferRecord(
                    transferId = request.transferId,
                    fileId = file.fileId,
                    batchId = request.batchId,
                    peerDeviceId = connection.peer.deviceId,
                    filename = file.filename,
                    totalChunks = file.totalChunks,
                    verifiedBitmap = bitmap.serialize(),
                    sha256Full = file.sha256Full,
                    status = TransferStatus.TRANSFERRING,
                    direction = TransferDirection.RECEIVED,
                    updatedAtEpochMs = nowMillis(),
                ),
            )

            // A zero-length file has no chunks, so no CHUNK_DATA will ever
            // arrive to drive completion: the bitmap is already full the moment
            // the file is accepted, so run the completion gate right here.
            val opened = openFiles[file.fileId] ?: continue
            if (opened.bitmap.isComplete) finishFile(opened, completed, failed)
        }
    }

    private suspend fun onChunkData(
        plaintext: ByteArray,
        completed: MutableList<String>,
        failed: MutableList<Pair<String, String>>,
    ) {
        val chunk = runCatching { ChunkDataLayout.decode(plaintext) }
            .getOrElse {
                connection.fatal(it.message ?: ErrorCode.MALFORMED_CHUNK_DATA, it.message ?: "malformed CHUNK_DATA")
                return
            }

        val fileIdHex = Hex.bytes(chunk.fileId).lowercase()
        val open = openFiles[fileIdHex]
        if (open == null) {
            // A chunk for a file we never accepted (or already finished). NACK
            // rather than drop silently, so the sender stops retransmitting it.
            connection.sendJson(
                MessageType.CHUNK_NACK,
                ChunkNack(
                    fileId = fileIdHex,
                    chunkIndex = chunk.chunkIndex,
                    reason = NackReason.UNEXPECTED_INDEX,
                ),
            )
            return
        }

        val manifest = open.manifest
        if (chunk.chunkIndex !in 0 until manifest.totalChunks) {
            connection.sendJson(
                MessageType.CHUNK_NACK,
                ChunkNack(
                    fileId = fileIdHex,
                    chunkIndex = chunk.chunkIndex,
                    reason = NackReason.UNEXPECTED_INDEX,
                ),
            )
            return
        }

        // Already verified: ACK again without rewriting. The sender may not have
        // seen the first ACK, and re-writing would be wasted I/O.
        if (open.bitmap.isVerified(chunk.chunkIndex)) {
            connection.sendJson(
                MessageType.CHUNK_ACK,
                ChunkAck(fileId = fileIdHex, chunkIndex = chunk.chunkIndex),
            )
            return
        }

        // Section 6 receiver step 2: verify BEFORE writing.
        val digest = crypto.sha256(chunk.chunkBytes)
        if (!digest.contentEquals(chunk.chunkSha256)) {
            connection.sendJson(
                MessageType.CHUNK_NACK,
                ChunkNack(
                    fileId = fileIdHex,
                    chunkIndex = chunk.chunkIndex,
                    reason = NackReason.CHECKSUM_MISMATCH,
                ),
            )
            return
        }

        if (!open.sink.hasSpaceFor(chunk.chunkBytes.size.toLong())) {
            connection.send(
                MessageType.ERROR,
                MessageJson.encodeToBytes(
                    ErrorPayload(ErrorCode.INSUFFICIENT_STORAGE, "disk full while writing ${manifest.filename}"),
                ),
            )
            store?.setStatus(open.transferId, manifest.fileId, TransferStatus.FAILED)
            failed += manifest.fileId to ErrorCode.INSUFFICIENT_STORAGE
            return
        }

        try {
            open.sink.writeChunk(chunk.chunkIndex, chunk.chunkBytes)
        } catch (e: Exception) {
            connection.sendJson(
                MessageType.CHUNK_NACK,
                ChunkNack(
                    fileId = fileIdHex,
                    chunkIndex = chunk.chunkIndex,
                    reason = NackReason.WRITE_FAILED,
                ),
            )
            return
        }

        open.bitmap.markVerified(chunk.chunkIndex)
        open.bytesWritten += chunk.chunkBytes.size
        store?.markChunkVerified(open.transferId, manifest.fileId, open.bitmap.serialize())

        connection.sendJson(
            MessageType.CHUNK_ACK,
            ChunkAck(fileId = fileIdHex, chunkIndex = chunk.chunkIndex),
        )
        publishProgress(open)

        // Section 6 receiver step 3: bitmap full -> full-file checksum.
        if (open.bitmap.isComplete) {
            finishFile(open, completed, failed)
        }
    }

    private suspend fun finishFile(
        open: OpenFile,
        completed: MutableList<String>,
        failed: MutableList<Pair<String, String>>,
    ) {
        val actual = open.sink.sha256Full()
        val expected = runCatching { Hex.decode(open.manifest.sha256Full) }.getOrNull()

        if (expected == null || !actual.contentEquals(expected)) {
            // Section 14: hard failure. The sender must retry with a new
            // transfer_id, so drop all resume state for this file — a partial
            // file whose checksum is wrong is worse than no file at all.
            connection.send(
                MessageType.ERROR,
                MessageJson.encodeToBytes(
                    ErrorPayload(ErrorCode.CHECKSUM_MISMATCH, "full-file SHA-256 mismatch for ${open.manifest.filename}"),
                ),
            )
            store?.setStatus(open.transferId, open.manifest.fileId, TransferStatus.FAILED)
            failed += open.manifest.fileId to ErrorCode.CHECKSUM_MISMATCH
            openFiles.remove(open.manifest.fileId)
            runCatching { open.sink.close() }
            return
        }

        store?.setStatus(open.transferId, open.manifest.fileId, TransferStatus.COMPLETED)
        connection.sendJson(
            MessageType.TRANSFER_COMPLETE,
            TransferComplete(transferId = open.transferId),
        )
        completed += open.manifest.fileId
        openFiles.remove(open.manifest.fileId)
        runCatching { open.sink.close() }
    }

    private suspend fun onCancel(cancel: TransferCancel?) {
        val matching = if (cancel == null) {
            openFiles.values.toList()
        } else {
            openFiles.values.filter { it.transferId == cancel.transferId }
        }
        for (open in matching) {
            store?.setStatus(open.transferId, open.manifest.fileId, TransferStatus.CANCELLED)
            openFiles.remove(open.manifest.fileId)
            runCatching { open.sink.close() }
        }
    }

    private suspend fun closeAll(status: TransferStatus) {
        for (open in openFiles.values) {
            store?.setStatus(open.transferId, open.manifest.fileId, status)
            runCatching { open.sink.close() }
        }
        openFiles.clear()
    }

    private fun publishProgress(open: OpenFile) {
        _progress.value = TransferProgress(
            transferId = open.transferId,
            fileId = open.manifest.fileId,
            filename = open.manifest.filename,
            totalChunks = open.manifest.totalChunks,
            verifiedChunks = open.bitmap.verifiedCount,
            bytesTransferred = open.bytesWritten,
            totalBytes = open.manifest.sizeBytes,
            windowSize = 0,
            speedBytesPerSecond = 0,
            etaMillis = null,
            status = TransferStatus.TRANSFERRING,
        )
    }
}

/** What a receive session produced. */
data class ReceiveSummary(
    /** `file_id`s that passed the full-file checksum. */
    val completedFileIds: List<String>,
    /** `file_id` (or `<session>`) to error code. */
    val failures: List<Pair<String, String>>,
) {
    val anyCompleted: Boolean get() = completedFileIds.isNotEmpty()
}
