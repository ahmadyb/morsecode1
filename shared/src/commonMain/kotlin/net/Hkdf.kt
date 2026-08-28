package net.morsecode.net

/**
 * HKDF-SHA256 (RFC 5869), extract-then-expand.
 *
 * The spec requires HKDF to be implemented manually rather than pulled from a
 * library, and requires it to live in `shared/commonMain`. Both are honoured
 * here: this file is pure Kotlin, and the only thing it needs from the platform
 * is an HMAC-SHA256 function, which is injected as [hmacSha256]. That keeps the
 * KDF itself unit-testable in `commonTest` with a deterministic stand-in, while
 * the real `javax.crypto.Mac` implementation lives in `jvmMain/net/Crypto.kt`.
 *
 * For Morse Code's session key the parameters are fixed (PROTOCOL
 * SPECIFICATION, Section 4):
 *  - `salt` = lexicographically sorted concatenation of the two 65-byte
 *    uncompressed public keys (130 bytes total)
 *  - `info` = `"morsecode-session-v1"`
 *  - `outputLength` = 32
 *
 * Sorting the salt is what makes the derivation symmetric: both peers compute
 * the identical 130-byte salt without either having to know who initiated, so
 * both arrive at the same session key from the same ECDH secret.
 */
object Hkdf {

    const val SHA256_HASH_LENGTH: Int = 32

    /** The exact `info` label this protocol uses. Changing it breaks interop. */
    const val SESSION_INFO: String = "morsecode-session-v1"

    /**
     * Derives the 32-byte session key.
     *
     * @param sharedSecret raw ECDH output.
     * @param publicKeyA one peer's 65-byte uncompressed public key.
     * @param publicKeyB the other peer's 65-byte uncompressed public key.
     *   Order does not matter: the two are sorted before concatenation.
     */
    fun deriveSessionKey(
        sharedSecret: ByteArray,
        publicKeyA: ByteArray,
        publicKeyB: ByteArray,
        hmacSha256: (key: ByteArray, data: ByteArray) -> ByteArray,
    ): ByteArray {
        require(sharedSecret.isNotEmpty()) { "ECDH shared secret must not be empty" }
        require(publicKeyA.size == CryptoConstants.UNCOMPRESSED_POINT_BYTES) {
            "public key A must be ${CryptoConstants.UNCOMPRESSED_POINT_BYTES} bytes, got ${publicKeyA.size}"
        }
        require(publicKeyB.size == CryptoConstants.UNCOMPRESSED_POINT_BYTES) {
            "public key B must be ${CryptoConstants.UNCOMPRESSED_POINT_BYTES} bytes, got ${publicKeyB.size}"
        }
        return expand(
            prk = extract(salt = sortedSalt(publicKeyA, publicKeyB), ikm = sharedSecret, hmacSha256 = hmacSha256),
            info = SESSION_INFO.encodeToByteArray(),
            outputLength = SHA256_HASH_LENGTH,
            hmacSha256 = hmacSha256,
        )
    }

    /**
     * Lexicographically sorted concatenation of the two public keys.
     *
     * Uses unsigned byte comparison: Java/Kotlin `Byte` is signed, and a naive
     * `compareTo` would order `0x80` before `0x7F`, which is still deterministic
     * and therefore still interoperable — but unsigned ordering is the
     * conventional reading of "sorted" for binary data, so pin it explicitly.
     */
    fun sortedSalt(publicKeyA: ByteArray, publicKeyB: ByteArray): ByteArray {
        val aFirst = compareUnsigned(publicKeyA, publicKeyB) <= 0
        val first = if (aFirst) publicKeyA else publicKeyB
        val second = if (aFirst) publicKeyB else publicKeyA
        return first + second
    }

    /** HKDF-Extract: `PRK = HMAC-Hash(salt, IKM)`. */
    fun extract(
        salt: ByteArray,
        ikm: ByteArray,
        hmacSha256: (key: ByteArray, data: ByteArray) -> ByteArray,
    ): ByteArray {
        // RFC 5869 §2.2: a missing/empty salt is replaced by HashLen zero bytes.
        // (JCE's Mac also rejects a truly empty key, so this is both correct and
        // necessary when the caller passes ByteArray(0).)
        val effectiveSalt = if (salt.isEmpty()) ByteArray(SHA256_HASH_LENGTH) else salt
        return hmacSha256(effectiveSalt, ikm)
    }

    /**
     * HKDF-Expand.
     *
     * @throws IllegalArgumentException if [outputLength] exceeds
     *   `255 * HashLen`, the RFC's own ceiling, or if it is not positive.
     */
    fun expand(
        prk: ByteArray,
        info: ByteArray,
        outputLength: Int,
        hmacSha256: (key: ByteArray, data: ByteArray) -> ByteArray,
    ): ByteArray {
        require(outputLength > 0) { "outputLength must be positive, got $outputLength" }
        require(outputLength <= 255 * SHA256_HASH_LENGTH) {
            "outputLength $outputLength exceeds RFC 5869 ceiling of ${255 * SHA256_HASH_LENGTH}"
        }
        val iterations = (outputLength + SHA256_HASH_LENGTH - 1) / SHA256_HASH_LENGTH
        val out = ByteArray(outputLength)
        var written = 0
        var previous = ByteArray(0) // T(0) is the empty string

        for (i in 1..iterations) {
            // T(i) = HMAC-Hash(PRK, T(i-1) | info | i)
            val input = ByteArray(previous.size + info.size + 1)
            previous.copyInto(input, 0)
            info.copyInto(input, previous.size)
            input[input.size - 1] = i.toByte() // counter fits in one byte: i <= 255

            previous = hmacSha256(prk, input)
            val toCopy = minOf(SHA256_HASH_LENGTH, outputLength - written)
            previous.copyInto(out, written, 0, toCopy)
            written += toCopy
        }
        return out
    }

    /** Unsigned lexicographic comparison of two equal-length byte strings. */
    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val ai = a[i].toInt() and 0xFF
            val bi = b[i].toInt() and 0xFF
            if (ai != bi) return ai - bi
        }
        return a.size - b.size
    }
}

/** Wire-format constants for the crypto layer (Section 4). */
object CryptoConstants {
    /** `0x04 || X || Y`, 32 bytes per coordinate. */
    const val UNCOMPRESSED_POINT_BYTES: Int = 65
    const val POINT_PREFIX: Byte = 0x04
    const val COORDINATE_BYTES: Int = 32

    /** NIST P-256, named `secp256r1` by `ECGenParameterSpec`. */
    const val CURVE_NAME: String = "secp256r1"

    const val SESSION_KEY_BYTES: Int = 32
    const val ECDH_ALGORITHM: String = "ECDH"
    const val AES_GCM_TRANSFORMATION: String = "AES/GCM/NoPadding"
}

/**
 * Per-direction, strictly-incrementing GCM nonce (Section 4).
 *
 * Nonce layout: 4 zero bytes followed by an 8-byte big-endian counter — 12
 * bytes total, matching [Framing.GCM_NONCE_BYTES].
 *
 * Reusing a (key, nonce) pair under GCM is catastrophic: it leaks the XOR of
 * two plaintexts and, worse, the authentication key. This class is therefore
 * monotonic and refuses to wrap, and the counter is per-direction so that the
 * two halves of a duplex connection never share a nonce under the same key.
 */
class GcmNonceSequence(
    private var counter: Long = 0L,
) {
    val current: Long get() = counter

    /** Returns the next nonce and advances the counter. */
    fun next(): ByteArray {
        if (counter == Long.MAX_VALUE) {
            // 2^63 frames on one session is unreachable in practice, but a wrap
            // here would silently reuse a nonce. Fail closed instead.
            throw IllegalStateException("GCM nonce counter exhausted; session must be renegotiated")
        }
        val value = counter
        counter += 1
        return encode(value)
    }

    companion object {
        fun encode(value: Long): ByteArray {
            val nonce = ByteArray(Framing.GCM_NONCE_BYTES)
            // Bytes 0..3 stay zero. The counter occupies bytes 4..11, big-endian.
            for (i in 0 until 8) {
                nonce[4 + i] = ((value ushr (8 * (7 - i))) and 0xFF).toByte()
            }
            return nonce
        }

        fun decode(nonce: ByteArray): Long {
            require(nonce.size == Framing.GCM_NONCE_BYTES) {
                "nonce must be ${Framing.GCM_NONCE_BYTES} bytes, got ${nonce.size}"
            }
            for (i in 0 until 4) {
                if (nonce[i] != 0.toByte()) {
                    throw IllegalArgumentException("nonce high 4 bytes must be zero; byte $i = ${nonce[i]}")
                }
            }
            var value = 0L
            for (i in 0 until 8) {
                value = (value shl 8) or (nonce[4 + i].toLong() and 0xFF)
            }
            return value
        }
    }
}

/** Result of sealing a plaintext: the nonce used plus `[ciphertext][tag]`. */
data class SealedMessage(
    val nonce: ByteArray,
    val sealedBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is SealedMessage && nonce.contentEquals(other.nonce) && sealedBytes.contentEquals(other.sealedBytes))

    override fun hashCode(): Int = 31 * nonce.contentHashCode() + sealedBytes.contentHashCode()
}

/**
 * The sealed-channel abstraction the state machines in `commonMain` use.
 *
 * Everything in `shared/commonMain/net/TransferSender.kt` and
 * `TransferReceiver.kt` talks to this interface rather than to JCE directly,
 * which is what makes the windowed-transfer logic testable without a real
 * cipher. The production implementation is `JceSessionCipher` in
 * `jvmMain/net/Crypto.kt`.
 */
interface SessionCipher {
    /** 32-byte session key. Never logged. */
    val sessionKey: ByteArray

    /**
     * Encrypts [plaintext] under a fresh nonce and returns both.
     * The nonce is never reused across calls on the same instance.
     */
    fun seal(plaintext: ByteArray): SealedMessage

    /**
     * Decrypts and authenticates.
     *
     * @throws CryptoException with `code == "decryption_failed"` when the GCM
     *   tag does not verify. Per the spec's error table this is FATAL: the
     *   caller must close the connection, because a tag failure under a
     *   correctly-derived key means either corruption or an active attacker.
     */
    fun open(nonce: ByteArray, sealedBytes: ByteArray): ByteArray
}

/** A fatal cryptographic failure. [code] goes straight into `ERROR{code:...}`. */
class CryptoException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
