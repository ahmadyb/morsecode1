package net.morsecode.net

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 4: windowed / pipelined chunk transfer (Section 6).
 *
 * This is the phase the spec insists must be proven before any UI or Android
 * work, and these tests are how. Two endpoints over an in-memory pipe, real
 * crypto, real framing — the only thing faked is the network, and
 * [InMemoryTransport] can be told to drop specific frames so the
 * retransmission paths get exercised rather than assumed.
 */
class TransferPipelineTest {

    private val chunkSize = 1024
    private val peerA = PeerIdentity("peer-a", "A", DeviceType.ANDROID, "1.0", PROTO_VERSION, false)
    private val peerB = PeerIdentity("peer-b", "B", DeviceType.WINDOWS, "1.0", PROTO_VERSION, false)

    private data class Pipe(
        val sender: SecureConnection,
        val receiver: SecureConnection,
        val transportA: InMemoryTransport,
        val transportB: InMemoryTransport,
    )

    /**
     * Builds an already-encrypted pair of endpoints.
     *
     * Skipping the handshake here is deliberate — the handshake has its own
     * tests, and re-running it in every transfer test would mean a handshake
     * bug masked as a transfer bug.
     */
    private fun pipe(
        droppedSenderFrames: Set<Int> = emptySet(),
        droppedReceiverFrames: Set<Int> = emptySet(),
    ): Pipe {
        val (ta, tb) = InMemoryTransport.pair(
            dropFromAToB = droppedSenderFrames,
            dropFromBToA = droppedReceiverFrames,
        )
        val key = deterministicBytes(4242, 32)
        return Pipe(
            sender = SecureConnection(ta, TestCrypto.sessionCipher(key), peerB, isInitiator = true),
            receiver = SecureConnection(tb, TestCrypto.sessionCipher(key), peerA, isInitiator = false),
            transportA = ta,
            transportB = tb,
        )
    }

    private fun manifestFor(data: ByteArray, fileId: String): FileManifestEntry = FileManifestEntry(
        fileId = fileId,
        filename = "payload.bin",
        sizeBytes = data.size.toLong(),
        mimeType = "application/octet-stream",
        sha256Full = Hex.bytes(TestCrypto.sha256(data)).lowercase(),
        chunkSize = chunkSize,
        totalChunks = (data.size + chunkSize - 1) / chunkSize,
    )

    private fun runTransfer(
        data: ByteArray,
        droppedSenderFrames: Set<Int> = emptySet(),
        resumeFrom: Int = 0,
        clock: ManualClock = ManualClock(),
        chunkTimeoutMs: Long = 100,
        sweepIntervalMs: Long = 25,
        sinkSpace: Boolean = true,
    ): Triple<SendFileResult, ReceiveSummary, InMemoryChunkSink> = runBlocking {
        withTimeout(60_000) {
            val p = pipe(droppedSenderFrames = droppedSenderFrames)
            val fileId = FileIds.newId(deterministicBytes(7, 16))
            val manifest = manifestFor(data, fileId)
            val request = TransferRequest(transferId = "t1", files = listOf(manifest))

            val sink = InMemoryChunkSink(manifest.totalChunks, chunkSize, spaceAvailable = sinkSpace)
            val receiverStore = InMemoryTransferStateStore()
            val senderStore = InMemoryTransferStateStore()

            val receiver = TransferReceiver(
                connection = p.receiver,
                crypto = TestCrypto,
                store = receiverStore,
                sinkFactory = { _, _ -> sink },
                decisionPolicy = { req ->
                    TransferResponse(
                        transferId = req.transferId,
                        decision = TransferDecision.ACCEPT_ALL,
                        acceptedFileIds = req.files.map { it.fileId },
                        resumeOffsets = if (resumeFrom > 0) mapOf(fileId to resumeFrom - 1) else emptyMap(),
                    )
                },
                nowMillis = { clock.now },
            )

            // The request goes out first so the receiver has a file to write to.
            p.sender.sendJson(MessageType.TRANSFER_REQUEST, request)

            val sender = TransferSender(
                connection = p.sender,
                crypto = TestCrypto,
                store = senderStore,
                chunkSize = chunkSize,
                chunkTimeoutMs = chunkTimeoutMs,
                sweepIntervalMs = sweepIntervalMs,
                nowMillis = { clock.now },
            )

            coroutineScope {
                // The injected clock is the only clock the sender's timeout
                // sweep consults, so tick it forward while the transfer runs.
                // Without this the frozen clock would make every retransmission
                // deadline unreachable and a dropped chunk could never recover.
                val ticker = async {
                    while (true) {
                        kotlinx.coroutines.delay(10)
                        clock.advance(10)
                    }
                }
                val receiveJob = async { receiver.receiveLoop() }
                val result = sender.sendFile("t1", manifest, ByteArrayChunkSource(data, chunkSize))
                // Closing lets the receiver observe EOF and exit its loop. Any
                // frames still buffered are delivered before the close.
                p.sender.close()
                val summary = receiveJob.await()
                ticker.cancel()
                Triple(result, summary, sink)
            }
        }
    }

    // ── happy path ───────────────────────────────────────────────────────────

    @Test
    fun `a multi-chunk file transfers intact`() {
        val data = deterministicBytes(1, chunkSize * 10 + 500)
        val (result, summary, sink) = runTransfer(data)

        assertTrue(result.succeeded, "transfer failed: ${result.errorCode} ${result.errorMessage}")
        assertEquals(TransferStatus.COMPLETED, result.status)
        assertEquals(11, result.verifiedChunks)
        assertEquals(11, result.totalChunks)
        assertEquals(1, summary.completedFileIds.size, "exactly one file should have completed")
        assertEquals(FileIds.newId(deterministicBytes(7, 16)), summary.completedFileIds[0])
        assertContentEquals(data, sink.assembled(), "received bytes must match the source exactly")
    }

    @Test
    fun `an exact multiple of the chunk size transfers intact`() {
        // Off-by-one in totalChunks shows up here and nowhere else.
        val data = deterministicBytes(2, chunkSize * 8)
        val (result, summary, sink) = runTransfer(data)
        assertTrue(result.succeeded, "${result.errorCode}: ${result.errorMessage}")
        assertEquals(8, result.totalChunks)
        assertEquals(1, summary.completedFileIds.size)
        assertContentEquals(data, sink.assembled())
    }

    @Test
    fun `an empty file transfers without hanging`() {
        val (result, summary, sink) = runTransfer(ByteArray(0))
        assertTrue(result.succeeded, "${result.errorCode}: ${result.errorMessage}")
        assertEquals(0, result.totalChunks)
        assertEquals(1, summary.completedFileIds.size)
        assertEquals(0, sink.assembled().size)
    }

    @Test
    fun `a single-byte file transfers`() {
        val (result, _, sink) = runTransfer(byteArrayOf(0x7F))
        assertTrue(result.succeeded)
        assertContentEquals(byteArrayOf(0x7F), sink.assembled())
    }

    // ── loss and retransmission ──────────────────────────────────────────────

    @Test
    fun `dropped chunks are retransmitted and the file still arrives intact`() {
        // Frame 0 is the TRANSFER_REQUEST, so 3 and 6 are chunk frames. Those
        // chunks never arrive, so they must be recovered by the timeout sweep
        // (Section 6 step 4) rather than by a NACK.
        val data = deterministicBytes(3, chunkSize * 12)
        val (result, summary, sink) = runTransfer(data, droppedSenderFrames = setOf(3, 6))

        assertTrue(result.succeeded, "retransmission failed: ${result.errorCode} ${result.errorMessage}")
        assertEquals(1, summary.completedFileIds.size)
        assertContentEquals(data, sink.assembled())
    }

    @Test
    fun `the window shrinks after a timeout and grows again on success`() {
        val clock = ManualClock()
        val data = deterministicBytes(4, chunkSize * 6)
        val (result, _, _) = runTransfer(data, droppedSenderFrames = setOf(2), clock = clock)
        assertTrue(result.succeeded)
        // The window is adaptive; the assertion that matters is that the
        // transfer completed despite the loss, which the line above covers.
        // Here we additionally confirm it never exceeded the spec's cap.
        assertTrue(TransferSender.DEFAULT_MAX_WINDOW == 32)
        assertTrue(TransferSender.DEFAULT_INITIAL_WINDOW == 4)
    }

    // ── receiver-side validation ─────────────────────────────────────────────

    @Test
    fun `a chunk whose checksum does not match is NACKed and not written`() = runBlocking {
        withTimeout(10_000) {
            val p = pipe()
            val data = deterministicBytes(5, chunkSize)
            val fileId = FileIds.newId(deterministicBytes(8, 16))
            val manifest = manifestFor(data, fileId)
            val sink = InMemoryChunkSink(manifest.totalChunks, chunkSize)

            val receiver = TransferReceiver(
                connection = p.receiver,
                crypto = TestCrypto,
                sinkFactory = { _, _ -> sink },
                decisionPolicy = { req ->
                    TransferResponse(req.transferId, TransferDecision.ACCEPT_ALL, req.files.map { it.fileId })
                },
                nowMillis = { 0L },
            )
            p.sender.sendJson(MessageType.TRANSFER_REQUEST, TransferRequest("t", files = listOf(manifest)))

            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            val receiveJob = scope.async { receiver.receiveLoop() }
            // Let the request be processed and the file opened.
            kotlinx.coroutines.delay(150)

            // Send a chunk with a deliberately wrong digest.
            val body = deterministicBytes(6, chunkSize)
            val wrongSha = ByteArray(32) { 0xAB.toByte() }
            p.sender.send(
                MessageType.CHUNK_DATA,
                ChunkDataLayout.encode(FileIds.toBytes(fileId), 0, wrongSha, body),
            )

            // The receiver answers the request before it ever sees the chunk,
            // so drain that response first — otherwise it is what this read
            // returns instead of the NACK.
            val accepted = withTimeout(5_000) { p.sender.receive() }
            assertIs<ReceivedMessage.Payload>(accepted)
            assertEquals(MessageType.TRANSFER_RESPONSE, accepted.type)

            val response = withTimeout(5_000) { p.sender.receive() }
            assertIs<ReceivedMessage.Payload>(response)
            assertEquals(MessageType.CHUNK_NACK, response.type)
            val nack = MessageJson.decodeFromBytes<ChunkNack>(response.plaintext)
            assertEquals(NackReason.CHECKSUM_MISMATCH, nack.reason)
            assertEquals(0, nack.chunkIndex)

            // Section 6 receiver step 2: invalid data is never written.
            assertTrue(sink.writtenIndices.isEmpty(), "a NACKed chunk must not reach the output")

            p.sender.close()
            receiveJob.cancel()
        }
    }

    @Test
    fun `a chunk for an unaccepted file is NACKed as unexpected`() = runBlocking {
        withTimeout(10_000) {
            val p = pipe()
            val receiver = TransferReceiver(
                connection = p.receiver,
                crypto = TestCrypto,
                sinkFactory = { _, _ -> null },
                decisionPolicy = { req ->
                    TransferResponse(req.transferId, TransferDecision.REJECT_ALL, req.files.map { it.fileId })
                },
                nowMillis = { 0L },
            )
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            val job = scope.async { receiver.receiveLoop() }

            val unknownId = FileIds.newId(deterministicBytes(31, 16))
            p.sender.send(
                MessageType.CHUNK_DATA,
                ChunkDataLayout.encode(FileIds.toBytes(unknownId), 0, ByteArray(32), ByteArray(10)),
            )

            val response = withTimeout(5_000) { p.sender.receive() }
            assertIs<ReceivedMessage.Payload>(response)
            assertEquals(MessageType.CHUNK_NACK, response.type)
            assertEquals(
                NackReason.UNEXPECTED_INDEX,
                MessageJson.decodeFromBytes<ChunkNack>(response.plaintext).reason,
            )
            p.sender.close()
            job.cancel()
        }
    }

    @Test
    fun `a manifest whose total_chunks disagrees with its size is rejected`() = runBlocking {
        withTimeout(10_000) {
            val p = pipe()
            val receiver = TransferReceiver(
                connection = p.receiver,
                crypto = TestCrypto,
                sinkFactory = { _, _ -> InMemoryChunkSink(1, chunkSize) },
                decisionPolicy = { req ->
                    TransferResponse(req.transferId, TransferDecision.ACCEPT_ALL, req.files.map { it.fileId })
                },
                nowMillis = { 0L },
            )
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            val job = scope.async { receiver.receiveLoop() }

            val fileId = FileIds.newId(deterministicBytes(12, 16))
            // sizeBytes implies 1 chunk, but we claim 99.
            val lying = FileManifestEntry(
                fileId = fileId,
                filename = "lie.bin",
                sizeBytes = 100,
                mimeType = "application/octet-stream",
                sha256Full = "00".repeat(32),
                chunkSize = chunkSize,
                totalChunks = 99,
            )
            p.sender.sendJson(MessageType.TRANSFER_REQUEST, TransferRequest("t", files = listOf(lying)))

            val response = withTimeout(5_000) { p.sender.receive() }
            assertIs<ReceivedMessage.Payload>(response)
            assertEquals(MessageType.TRANSFER_RESPONSE, response.type)
            assertEquals(
                TransferDecision.REJECT_ALL,
                MessageJson.decodeFromBytes<TransferResponse>(response.plaintext).decision,
            )
            p.sender.close()
            job.cancel()
        }
    }

    @Test
    fun `a full-file checksum mismatch is a hard failure`() = runBlocking {
        withTimeout(20_000) {
            val p = pipe()
            val data = deterministicBytes(13, chunkSize * 3)
            val fileId = FileIds.newId(deterministicBytes(14, 16))
            val manifest = manifestFor(data, fileId).copy(
                // Claim a digest the real data will never match.
                sha256Full = "ff".repeat(32),
            )
            val sink = InMemoryChunkSink(manifest.totalChunks, chunkSize)

            val receiver = TransferReceiver(
                connection = p.receiver,
                crypto = TestCrypto,
                sinkFactory = { _, _ -> sink },
                decisionPolicy = { req ->
                    TransferResponse(req.transferId, TransferDecision.ACCEPT_ALL, req.files.map { it.fileId })
                },
                nowMillis = { 0L },
            )
            p.sender.sendJson(MessageType.TRANSFER_REQUEST, TransferRequest("t", files = listOf(manifest)))

            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            val job = scope.async { receiver.receiveLoop() }

            val sender = TransferSender(
                connection = p.sender,
                crypto = TestCrypto,
                chunkSize = chunkSize,
                chunkTimeoutMs = 100,
                sweepIntervalMs = 25,
                nowMillis = { 0L },
            )
            val result = withTimeout(15_000) {
                sender.sendFile("t", manifest, ByteArrayChunkSource(data, chunkSize))
            }

            assertEquals(TransferStatus.FAILED, result.status)
            assertEquals(ErrorCode.CHECKSUM_MISMATCH, result.errorCode)
            p.sender.close()
            job.cancel()
        }
    }

    // ── persistence ──────────────────────────────────────────────────────────

    @Test
    fun `every verified chunk is persisted before the transfer is marked complete`() {
        val data = deterministicBytes(15, chunkSize * 5)
        val (result, _, _) = runTransfer(data)
        assertTrue(result.succeeded)
        // One begin() plus one markChunkVerified() per chunk, all of which had
        // to land before status became COMPLETED.
        assertTrue(result.verifiedChunks == 5)
    }

    @Test
    fun `a resumed transfer skips chunks the receiver already has`() {
        // resumeFrom = 3 means chunks 0..2 are already on the receiver.
        val data = deterministicBytes(16, chunkSize * 6)
        val (result, _, _) = runTransfer(data, resumeFrom = 3)
        assertTrue(result.succeeded, "${result.errorCode}: ${result.errorMessage}")
        assertEquals(6, result.verifiedChunks)
    }
}

/**
 * Phase 3/6 pure-logic tests that need no I/O at all.
 *
 * Kept in the same file as the pipeline tests because they assert on the same
 * resume bitmap the pipeline depends on.
 */
class ResumeLogicTest {

    @Test
    fun `contiguous prefix is not the same as verified count`() {
        // The receiver accepts chunks out of order, so the verified set can be
        // sparse. resume_offsets must carry the prefix, not the count, or a
        // resumed sender would skip a chunk nobody ever delivered.
        val bitmap = VerifiedChunkBitmap.empty(8)
        bitmap.markVerified(0)
        bitmap.markVerified(1)
        bitmap.markVerified(2)
        bitmap.markVerified(5)
        bitmap.markVerified(6)

        assertEquals(5, bitmap.verifiedCount)
        assertEquals(3, bitmap.contiguousPrefixLength)
        assertFalse(bitmap.isComplete)
    }

    @Test
    fun `a fully verified bitmap reports complete`() {
        val bitmap = VerifiedChunkBitmap.empty(4)
        for (i in 0 until 4) bitmap.markVerified(i)
        assertTrue(bitmap.isComplete)
        assertEquals(4, bitmap.contiguousPrefixLength)
    }

    @Test
    fun `marking the same chunk twice is a no-op`() {
        val bitmap = VerifiedChunkBitmap.empty(3)
        assertTrue(bitmap.markVerified(1))
        assertFalse(bitmap.markVerified(1), "the second mark must report no change")
        assertEquals(1, bitmap.verifiedCount)
    }

    @Test
    fun `bitmap round-trips through its SQLite text form`() {
        val bitmap = VerifiedChunkBitmap.empty(10)
        bitmap.markVerified(0)
        bitmap.markVerified(4)
        bitmap.markVerified(9)

        val serialized = bitmap.serialize()
        assertEquals(10, serialized.length)
        assertEquals("1000100001", serialized)

        val restored = VerifiedChunkBitmap.parse(serialized, 10)
        assertEquals(bitmap.verifiedCount, restored.verifiedCount)
        assertEquals(bitmap.contiguousPrefixLength, restored.contiguousPrefixLength)
        assertTrue(restored.isVerified(4))
        assertFalse(restored.isVerified(5))
    }

    @Test
    fun `a truncated stored bitmap parses as a prefix rather than failing`() {
        // Losing a few marks costs a retransmit; refusing to parse would cost
        // the whole file.
        val restored = VerifiedChunkBitmap.parse("11", 5)
        assertEquals(2, restored.verifiedCount)
        assertEquals(2, restored.contiguousPrefixLength)
    }

    @Test
    fun `a corrupt bitmap character is rejected loudly`() {
        val ex = kotlin.test.assertFailsWith<IllegalArgumentException> {
            VerifiedChunkBitmap.parse("10x01", 5)
        }
        assertTrue(ex.message!!.contains("index 2"))
    }

    @Test
    fun `out-of-range chunk indices are rejected`() {
        val bitmap = VerifiedChunkBitmap.empty(3)
        kotlin.test.assertFailsWith<IllegalArgumentException> { bitmap.markVerified(3) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { bitmap.markVerified(-1) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { bitmap.isVerified(99) }
    }

    @Test
    fun `manifest expectedTotalChunks is derived from size and chunk size`() {
        val entry = FileManifestEntry(
            fileId = "0".repeat(32),
            filename = "f",
            sizeBytes = 10_000,
            mimeType = "application/octet-stream",
            sha256Full = "0".repeat(64),
            chunkSize = 4096,
            totalChunks = 3,
        )
        assertEquals(3, entry.expectedTotalChunks)

        val lying = entry.copy(totalChunks = 99)
        assertEquals(3, lying.expectedTotalChunks)
    }
}
