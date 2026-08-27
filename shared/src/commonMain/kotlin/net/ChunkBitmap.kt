package net.morsecode.net

/**
 * The verified-chunk bitmap that resume depends on (Section 13).
 *
 * Stored in SQLite as a string of `'0'`/`'1'` characters, one per chunk. A
 * character string rather than a packed bitmask or BLOB for two reasons:
 *  - `total_chunks` is unbounded. A 1 TB file at the 4 MiB chunk size is
 *    262144 chunks; a 32 KB bitmask string is trivial, and a packed
 *    representation would save 30 KB while making every resume bug much harder
 *    to diagnose.
 *  - It round-trips through SQLite TEXT without a BLOB adapter, keeping the
 *    one shared schema identical on Android and Desktop.
 *
 * Indices are validated on every access. An out-of-range index here would
 * silently corrupt the resume state, and the failure would surface much later
 * as a file that fails its full-file checksum.
 */
class VerifiedChunkBitmap private constructor(
    val totalChunks: Int,
    private val bits: BooleanArray,
) {
    /** Number of chunks currently marked verified. */
    val verifiedCount: Int get() = bits.count { it }

    val isComplete: Boolean get() = verifiedCount == totalChunks

    /**
     * Highest index `n` such that every chunk in `0 until n` is verified.
     *
     * This — not `verifiedCount - 1` — is what goes into
     * `TRANSFER_RESPONSE.resume_offsets`. Chunks may be acknowledged out of
     * order (the receiver explicitly accepts them in any order, Section 6), so
     * the verified set can be sparse: `{0,1,2,5,6}` has a count of 5 but the
     * sender may only safely skip the first 3.
     */
    val contiguousPrefixLength: Int
        get() {
            var n = 0
            while (n < totalChunks && bits[n]) n++
            return n
        }

    fun isVerified(index: Int): Boolean {
        checkIndex(index)
        return bits[index]
    }

    /**
     * @return `true` if this call changed anything, `false` if the chunk was
     *   already verified. Callers use the return value to avoid writing to
     *   SQLite twice for a retransmitted chunk.
     */
    fun markVerified(index: Int): Boolean {
        checkIndex(index)
        if (bits[index]) return false
        bits[index] = true
        return true
    }

    fun copy(): VerifiedChunkBitmap = VerifiedChunkBitmap(totalChunks, bits.copyOf())

    /** Serialises to the SQLite TEXT representation. */
    fun serialize(): String = buildString(totalChunks) {
        for (b in bits) append(if (b) '1' else '0')
    }

    private fun checkIndex(index: Int) {
        require(index in 0 until totalChunks) {
            "chunk index $index outside 0 until $totalChunks"
        }
    }

    override fun toString(): String = "VerifiedChunkBitmap($verifiedCount/$totalChunks)"

    companion object {
        fun empty(totalChunks: Int): VerifiedChunkBitmap {
            require(totalChunks >= 0) { "totalChunks must be >= 0, got $totalChunks" }
            return VerifiedChunkBitmap(totalChunks, BooleanArray(totalChunks))
        }

        /**
         * Parses the SQLite representation.
         *
         * A stored string shorter than [totalChunks] is treated as a prefix
         * (older rows written before the file grew), and a longer one is
         * truncated. Neither is an error worth aborting a resume over — losing
         * a few verified marks costs a retransmit, while refusing to parse
         * would cost the whole file.
         */
        fun parse(serialized: String, totalChunks: Int): VerifiedChunkBitmap {
            require(totalChunks >= 0) { "totalChunks must be >= 0, got $totalChunks" }
            val bits = BooleanArray(totalChunks)
            for (i in 0 until minOf(serialized.length, totalChunks)) {
                when (serialized[i]) {
                    '1' -> bits[i] = true
                    '0' -> bits[i] = false
                    else -> throw IllegalArgumentException(
                        "bitmap contains '${serialized[i]}' at index $i; expected '0' or '1'",
                    )
                }
            }
            return VerifiedChunkBitmap(totalChunks, bits)
        }
    }
}
