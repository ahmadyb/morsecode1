package net.morsecode.util

/**
 * Base64 (RFC 4648) encode/decode.
 *
 * Hand-rolled because there is no Base64 in the Kotlin *common* standard
 * library — `java.util.Base64` is JVM-only and would break `commonMain`. This
 * is needed in two places in the protocol: the KEY_EXCHANGE public key and the
 * optional `thumbnail_base64` field in a transfer manifest.
 *
 * The alphabet is the standard one (`+`/`/`), not the URL-safe variant, because
 * the payloads go inside JSON string values where neither character is special.
 */
object Base64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val DECODE_TABLE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, ch -> table[ch.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val fullTriples = bytes.size / 3
        val remainder = bytes.size % 3
        val outChars = CharArray(fullTriples * 4 + if (remainder == 0) 0 else 4)

        var src = 0
        var dst = 0
        repeat(fullTriples) {
            val b0 = bytes[src].toInt() and 0xFF
            val b1 = bytes[src + 1].toInt() and 0xFF
            val b2 = bytes[src + 2].toInt() and 0xFF
            src += 3

            outChars[dst++] = ALPHABET[b0 ushr 2]
            outChars[dst++] = ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)]
            outChars[dst++] = ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)]
            outChars[dst++] = ALPHABET[b2 and 0x3F]
        }

        when (remainder) {
            1 -> {
                val b0 = bytes[src].toInt() and 0xFF
                outChars[dst++] = ALPHABET[b0 ushr 2]
                outChars[dst++] = ALPHABET[(b0 and 0x03) shl 4]
                outChars[dst++] = '='
                outChars[dst] = '='
            }

            2 -> {
                val b0 = bytes[src].toInt() and 0xFF
                val b1 = bytes[src + 1].toInt() and 0xFF
                outChars[dst++] = ALPHABET[b0 ushr 2]
                outChars[dst++] = ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)]
                outChars[dst++] = ALPHABET[(b1 and 0x0F) shl 2]
                outChars[dst] = '='
            }
        }
        return outChars.concatToString()
    }

    /**
     * @throws IllegalArgumentException on any character outside the alphabet
     *   (other than `=` padding and whitespace, which are skipped). A peer
     *   sending garbage should get a clear error, not a silently short key.
     */
    fun decode(text: String): ByteArray {
        val cleaned = text.filter { !it.isWhitespace() }
        if (cleaned.isEmpty()) return ByteArray(0)

        val padding = cleaned.count { it == '=' }
        require(padding <= 2) { "invalid base64: $padding padding characters" }
        val dataLength = cleaned.length - padding
        require(dataLength % 4 != 1) { "invalid base64: length ${cleaned.length}" }

        val outSize = dataLength * 3 / 4
        val out = ByteArray(outSize)

        var buffer = 0
        var bits = 0
        var dst = 0
        for (i in 0 until dataLength) {
            val ch = cleaned[i]
            require(ch.code < 128) { "invalid base64 character: $ch" }
            val value = DECODE_TABLE[ch.code]
            require(value >= 0) { "invalid base64 character: $ch" }

            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[dst++] = ((buffer ushr bits) and 0xFF).toByte()
            }
        }
        require(dst == outSize) { "invalid base64: decoded $dst bytes, expected $outSize" }
        return out
    }
}
