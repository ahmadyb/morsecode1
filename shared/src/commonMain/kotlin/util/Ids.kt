package net.morsecode.util

/**
 * Identifier generation for batches and rooms (Sections 7 and 8).
 *
 * Everything here derives from injected random bytes so it is testable in
 * `commonMain` without a platform RNG, and so tests can pin the inputs and
 * assert the exact formatted output.
 *
 * `uuid` produces an RFC 4122 version-4 UUID from 16 random bytes, overwriting
 * the version and variant nibbles. The overwrites matter: a raw 16-byte random
 * string formatted with dashes is *not* a v4 UUID, and tools that parse
 * `batch_id`/`room_id` would be within their rights to reject it.
 */
object Ids {

    /**
     * @param randomBytes exactly 16 bytes of strong randomness.
     */
    fun uuid(randomBytes: ByteArray): String {
        require(randomBytes.size == 16) { "uuid needs 16 random bytes, got ${randomBytes.size}" }
        val b = randomBytes.copyOf()
        // Version 4 (bits 0100) in byte 6, variant 10xx in byte 8.
        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80.toByte().toInt()).toByte()

        val hex = hex(b)
        return buildString(36) {
            append(hex, 0, 8).append('-')
            append(hex, 8, 12).append('-')
            append(hex, 12, 16).append('-')
            append(hex, 16, 20).append('-')
            append(hex, 20, 32)
        }
    }

    /** Lower-case hex, e.g. for `room_token` (Section 8: "random hex"). */
    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val i = byte.toInt() and 0xFF
            sb.append(digits[i ushr 4]).append(digits[i and 0x0F])
        }
        return sb.toString()
    }
}
