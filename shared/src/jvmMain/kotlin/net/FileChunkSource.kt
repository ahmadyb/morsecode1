package net.morsecode.net

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Random-access file-backed [ChunkSource] (jvmMain, shared by both platforms).
 *
 * Seeking per chunk is what lets the sender retransmit an arbitrary chunk
 * without re-reading the whole file, and it matches the protocol's
 * out-of-order nature.
 */
class FileChunkSource(
    private val path: String,
    private val crypto: CryptoProvider,
    private val chunkSizeBytes: Int = ChunkDataLayout.DEFAULT_CHUNK_SIZE,
) : ChunkSource {

    private val file = RandomAccessFile(path, "r")

    override val totalBytes: Long = file.length()
    override val totalChunks: Int =
        if (totalBytes == 0L) 0 else ((totalBytes + chunkSizeBytes - 1) / chunkSizeBytes).toInt()

    override suspend fun readChunk(index: Int): ByteArray = withContext(Dispatchers.IO) {
        val start = index.toLong() * chunkSizeBytes
        val len = minOf(chunkSizeBytes.toLong(), totalBytes - start).coerceAtLeast(0).toInt()
        val out = ByteArray(len)
        file.seek(start)
        file.readFully(out)
        out
    }

    override suspend fun sha256Full(): ByteArray = withContext(Dispatchers.IO) {
        crypto.sha256(File(path).readBytes())
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) { runCatching { file.close() } }
    }
}

/**
 * Random-access file-backed [ChunkSink]: chunks land at `index * chunkSize`,
 * never appended, so out-of-order arrival reassembles correctly (Section 6).
 */
class FileChunkSink(
    private val path: String,
    override val totalChunks: Int,
    private val crypto: CryptoProvider,
    private val chunkSizeBytes: Int = ChunkDataLayout.DEFAULT_CHUNK_SIZE,
) : ChunkSink {

    private val file = RandomAccessFile(path, "rw")

    override suspend fun writeChunk(index: Int, bytes: ByteArray) = withContext(Dispatchers.IO) {
        file.seek(index.toLong() * chunkSizeBytes)
        file.write(bytes)
    }

    override suspend fun sha256Full(): ByteArray = withContext(Dispatchers.IO) {
        crypto.sha256(File(path).readBytes())
    }

    override suspend fun hasSpaceFor(bytes: Long): Boolean = withContext(Dispatchers.IO) {
        // `File.usableSpace` rather than `java.nio.file.Files.getFileStore`:
        // the NIO file API only exists from Android API 26, and this class is
        // compiled into the APK for a minSdk 23 target. `usableSpace` is
        // available on every API level and on the desktop JVM alike.
        runCatching {
            val dir = File(path).parentFile ?: File(".")
            dir.usableSpace > bytes
        }.getOrDefault(true)
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) { runCatching { file.close() } }
    }
}
