package net.morsecode.net

import java.security.SecureRandom
import kotlinx.coroutines.channels.Channel

/**
 * An in-memory bidirectional pipe implementing [SocketTransport].
 *
 * This is what lets Phase 4 be verified the way the spec demands — "two JVM
 * desktop instances on localhost before touching Android" — without even
 * needing localhost. Two endpoints share one [PipePair]; bytes written by one
 * appear in the other's read buffer.
 *
 * [lossyIndices] makes it possible to test the retransmission paths: chunk
 * writes whose sequence number is in that set are silently dropped, exactly as
 * a lossy Wi-Fi link would. Without this, the NACK and timeout branches of
 * Section 6 could only be tested by hand.
 */
class InMemoryTransport(
    private val inbox: Channel<Byte>,
    private val outbox: Channel<Byte>,
    override val remoteAddress: String = "in-memory",
    override val remotePort: Int = 0,
    /** Sequence numbers of outbound *frames* to drop. See [frameCounter]. */
    var dropFrames: Set<Int> = emptySet(),
) : SocketTransport {

    private var closed = false
    override val isOpen: Boolean get() = !closed

    /** Counts outbound writes so [dropFrames] can target specific frames. */
    var frameCounter: Int = 0
        private set

    override suspend fun write(bytes: ByteArray) {
        if (closed) throw java.io.IOException("transport closed")
        val n = frameCounter++
        if (n in dropFrames) return
        for (b in bytes) outbox.send(b)
    }

    override suspend fun read(buffer: ByteArray): Int {
        if (buffer.isEmpty()) return 0
        // Block for the first byte, then drain whatever else is already queued.
        // That mimics a socket read returning a partial frame, which is the case
        // FrameAssembler has to survive.
        val first = inbox.receiveCatching().getOrNull() ?: return -1
        buffer[0] = first
        var count = 1
        while (count < buffer.size) {
            val next = inbox.tryReceive().getOrNull() ?: break
            buffer[count++] = next
        }
        return count
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        outbox.close()
    }

    companion object {
        fun pair(
            dropFromAToB: Set<Int> = emptySet(),
            dropFromBToA: Set<Int> = emptySet(),
        ): Pair<InMemoryTransport, InMemoryTransport> {
            val aToB = Channel<Byte>(Channel.UNLIMITED)
            val bToA = Channel<Byte>(Channel.UNLIMITED)
            val a = InMemoryTransport(inbox = bToA, outbox = aToB, remoteAddress = "A", dropFrames = dropFromAToB)
            val b = InMemoryTransport(inbox = aToB, outbox = bToA, remoteAddress = "B", dropFrames = dropFromBToA)
            return a to b
        }
    }
}

/**
 * A [CryptoProvider] backed by the real JCE implementation.
 *
 * Tests use the production crypto rather than a fake: the framing, handshake
 * and transfer layers all depend on nonce and tag semantics that a stub would
 * paper over. Using [JceCryptoProvider] means a regression in the crypto shows
 * up as a failing protocol test instead of passing silently.
 */
val TestCrypto: CryptoProvider = JceCryptoProvider(SecureRandom())

/** Deterministic bytes, so a failure is reproducible from the test output. */
fun deterministicBytes(seed: Int, length: Int): ByteArray {
    val random = java.util.Random(seed.toLong())
    return ByteArray(length).also { random.nextBytes(it) }
}

/** [ChunkSource] over an in-memory byte array. */
class ByteArrayChunkSource(
    private val data: ByteArray,
    private val chunkSize: Int = ChunkDataLayout.DEFAULT_CHUNK_SIZE,
    private val crypto: CryptoProvider = TestCrypto,
) : ChunkSource {
    override val totalBytes: Long = data.size.toLong()
    override val totalChunks: Int = if (data.isEmpty()) 0 else (data.size + chunkSize - 1) / chunkSize

    override suspend fun readChunk(index: Int): ByteArray {
        val start = index * chunkSize
        val end = minOf(start + chunkSize, data.size)
        return data.copyOfRange(start, end)
    }

    override suspend fun sha256Full(): ByteArray = crypto.sha256(data)

    override suspend fun close() = Unit
}

/**
 * [ChunkSink] over an in-memory buffer, with genuine random-access writes.
 *
 * Writing at `index * chunkSize` into a sparse list is what proves the receiver
 * honours out-of-order delivery; an append-only fake would pass every test
 * while hiding the bug the protocol exists to prevent.
 */
class InMemoryChunkSink(
    override val totalChunks: Int,
    private val chunkSize: Int = ChunkDataLayout.DEFAULT_CHUNK_SIZE,
    private val crypto: CryptoProvider = TestCrypto,
    var spaceAvailable: Boolean = true,
) : ChunkSink {
    private val chunks = arrayOfNulls<ByteArray>(totalChunks)

    override suspend fun writeChunk(index: Int, bytes: ByteArray) {
        chunks[index] = bytes.copyOf()
    }

    override suspend fun sha256Full(): ByteArray = crypto.sha256(assembled())

    override suspend fun close() = Unit

    override suspend fun hasSpaceFor(bytes: Long): Boolean = spaceAvailable

    fun assembled(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (c in chunks) out.write(c ?: ByteArray(0))
        return out.toByteArray()
    }

    val writtenIndices: List<Int> get() = chunks.indices.filter { chunks[it] != null }
}

/** [TransferStateStore] backed by a map, for tests. */
class InMemoryTransferStateStore : TransferStateStore {
    val records = LinkedHashMap<Pair<String, String>, TransferRecord>()
    var persistCount: Int = 0
        private set

    override suspend fun begin(record: TransferRecord) {
        records[record.transferId to record.fileId] = record
    }

    override suspend fun markChunkVerified(transferId: String, fileId: String, bitmap: String) {
        persistCount++
        val key = transferId to fileId
        records[key]?.let { records[key] = it.copy(verifiedBitmap = bitmap) }
    }

    override suspend fun setStatus(transferId: String, fileId: String, status: TransferStatus) {
        val key = transferId to fileId
        records[key]?.let { records[key] = it.copy(status = status) }
    }

    override suspend fun load(transferId: String, fileId: String): TransferRecord? =
        records[transferId to fileId]

    override suspend fun loadResumable(peerDeviceId: String, direction: TransferDirection): List<TransferRecord> =
        records.values.filter {
            it.peerDeviceId == peerDeviceId &&
                it.direction == direction &&
                it.status in listOf(TransferStatus.PAUSED, TransferStatus.FAILED)
        }
}

/** A clock the test controls, so timeouts are tested without real sleeping. */
class ManualClock(startMillis: Long = 1_000_000L) {
    var now: Long = startMillis
    fun advance(millis: Long) {
        now += millis
    }
}
