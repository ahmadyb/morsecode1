package net.morsecode.net

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JCE-backed implementation of [CryptoProvider] (PROTOCOL SPECIFICATION,
 * Section 4).
 *
 * Lives in `jvmMain`, which both `androidMain` and `desktopMain` inherit, so
 * this scheme is written exactly once and both platforms ship the identical
 * bytes. See the header comment on `shared/build.gradle.kts` for why this is
 * not in `commonMain` — JCE is not on the KMP common classpath.
 *
 * Every primitive here comes from `javax.crypto` / `java.security`. There is no
 * third-party crypto dependency, per the TECH STACK section.
 *
 * Thread safety: [JceSessionCipher] is NOT thread-safe and is not meant to be.
 * One cipher instance per direction per connection, driven from one coroutine.
 * A shared `Cipher` object would race on its internal buffer and, far worse,
 * could let two chunks be sealed under the same nonce.
 */
class JceCryptoProvider(
    private val random: SecureRandom = SecureRandom(),
) : CryptoProvider {

    override fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    override fun newKeyExchange(): KeyExchange = JceKeyExchange(random)

    override fun sessionCipher(sessionKey: ByteArray): SessionCipher = JceSessionCipher(sessionKey)

    companion object {
        /**
         * P-256 curve parameters, needed to rebuild a public key from its
         * wire-format coordinates on the receiving side.
         *
         * Fetched once via `AlgorithmParameters` rather than hard-coded: the
         * JCE supplies the standardised NIST values, and hard-coding them would
         * be both a transcription hazard and a deviation from
         * `ECGenParameterSpec("secp256r1")`, which the spec names explicitly.
         */
        internal fun p256Parameters(): ECParameterSpec {
            val params = AlgorithmParameters.getInstance("EC")
            params.init(ECGenParameterSpec(CryptoConstants.CURVE_NAME))
            return params.getParameterSpec(ECParameterSpec::class.java)
        }

        internal val CURVE_PARAMS: ECParameterSpec by lazy { p256Parameters() }
    }
}

/**
 * Ephemeral ECDH over NIST P-256.
 *
 * Wire format for the public key is the uncompressed point
 * `0x04 || X || Y` with 32-byte big-endian coordinates (Section 4).
 */
internal class JceKeyExchange(random: SecureRandom) : KeyExchange {

    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec(CryptoConstants.CURVE_NAME), random)
        generateKeyPair()
    }

    override val publicKey: ByteArray = (keyPair.public as ECPublicKey).w.toUncompressedPoint()

    override fun sharedSecret(remotePublicKey: ByteArray): ByteArray {
        val remotePoint = remotePublicKey.toEcPoint()
        val remoteKey = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(remotePoint, JceCryptoProvider.CURVE_PARAMS),
        )
        return KeyAgreement.getInstance(CryptoConstants.ECDH_ALGORITHM).run {
            init(keyPair.private)
            doPhase(remoteKey, true)
            generateSecret()
        }
    }
}

/**
 * AES-256-GCM session channel with a per-direction nonce counter.
 *
 * `AES/GCM/NoPadding` appends the 16-byte tag to its own output, so
 * [seal] returns `[ciphertext][tag]` in one contiguous array — exactly the
 * layout [Framing.encodePostHandshake] puts on the wire after the nonce.
 */
class JceSessionCipher(
    override val sessionKey: ByteArray,
) : SessionCipher {

    init {
        require(sessionKey.size == CryptoConstants.SESSION_KEY_BYTES) {
            "session key must be ${CryptoConstants.SESSION_KEY_BYTES} bytes, got ${sessionKey.size}"
        }
    }

    private val keySpec = SecretKeySpec(sessionKey, "AES")
    private val sendNonces = GcmNonceSequence()

    /**
     * Tracks nonces we have already accepted from the peer.
     *
     * The spec requires the counter to be "strictly-incrementing" and "never
     * reused". We enforce that on the receive side too rather than trusting the
     * peer, because a replayed frame is indistinguishable from a legitimate one
     * once the tag verifies. This costs one Long comparison per frame.
     */
    private var highestReceivedNonce: Long = -1L

    override fun seal(plaintext: ByteArray): SealedMessage {
        val nonce = sendNonces.next()
        val cipher = newCipher(Cipher.ENCRYPT_MODE, nonce)
        return SealedMessage(nonce = nonce, sealedBytes = cipher.doFinal(plaintext))
    }

    override fun open(nonce: ByteArray, sealedBytes: ByteArray): ByteArray {
        val sequence = GcmNonceSequence.decode(nonce)
        if (sequence <= highestReceivedNonce) {
            throw CryptoException(
                code = "nonce_reuse",
                "received nonce counter $sequence is not greater than the last accepted " +
                    "$highestReceivedNonce; refusing to decrypt a replayed frame",
            )
        }
        val cipher = newCipher(Cipher.DECRYPT_MODE, nonce)
        val plaintext = try {
            cipher.doFinal(sealedBytes)
        } catch (e: javax.crypto.AEADBadTagException) {
            // FATAL per the error table: close the connection, do not retry.
            throw CryptoException("decryption_failed", "GCM tag verification failed", e)
        } catch (e: javax.crypto.BadPaddingException) {
            throw CryptoException("decryption_failed", "GCM tag verification failed", e)
        }
        // Commit only after the tag verified.
        highestReceivedNonce = sequence
        return plaintext
    }

    private fun newCipher(mode: Int, nonce: ByteArray): Cipher =
        Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION).apply {
            init(
                mode,
                keySpec,
                GCMParameterSpec(Framing.GCM_TAG_BYTES * 8, nonce),
            )
        }
}

// ────────────────────────────────────────────────────────────────────────────
// Uncompressed-point codec.
//
// `BigInteger.toByteArray()` is two's-complement and variable length: it emits
// a leading 0x00 sign byte for values with the high bit set, and omits leading
// zero bytes for small values. The wire format needs exactly 32 bytes,
// big-endian, unsigned — so both directions need explicit normalisation.
// Getting this wrong produces a subtly-wrong key that still parses, which is
// the worst possible failure mode for a key exchange.
// ────────────────────────────────────────────────────────────────────────────

internal fun ECPoint.toUncompressedPoint(): ByteArray {
    val out = ByteArray(CryptoConstants.UNCOMPRESSED_POINT_BYTES)
    out[0] = CryptoConstants.POINT_PREFIX
    affineX.toUnsignedFixedWidth().copyInto(out, 1)
    affineY.toUnsignedFixedWidth().copyInto(out, 1 + CryptoConstants.COORDINATE_BYTES)
    return out
}

/** Left-pads or sign-strips to exactly [CryptoConstants.COORDINATE_BYTES]. */
private fun BigInteger.toUnsignedFixedWidth(): ByteArray {
    val raw = toByteArray() // two's-complement, may carry a leading sign byte
    val target = CryptoConstants.COORDINATE_BYTES
    val out = ByteArray(target)

    if (raw.size == target) {
        raw.copyInto(out)
    } else if (raw.size == target + 1 && raw[0] == 0.toByte()) {
        // Leading sign byte from a value with its high bit set.
        raw.copyInto(out, 0, 1, raw.size)
    } else if (raw.size > target) {
        // Strip any leading zero bytes.
        val start = raw.size - target
        for (i in 0 until start) {
            check(raw[i] == 0.toByte()) { "coordinate does not fit in $target bytes" }
        }
        raw.copyInto(out, 0, start, raw.size)
    } else {
        // Left-pad with zeros.
        raw.copyInto(out, target - raw.size)
    }
    return out
}

internal fun ByteArray.toEcPoint(): ECPoint {
    require(size == CryptoConstants.UNCOMPRESSED_POINT_BYTES) {
        "public key must be ${CryptoConstants.UNCOMPRESSED_POINT_BYTES} bytes, got $size"
    }
    require(this[0] == CryptoConstants.POINT_PREFIX) {
        "unsupported point format 0x${this[0].toInt() and 0xFF}; only uncompressed (0x04) is supported"
    }
    val c = CryptoConstants.COORDINATE_BYTES
    // `BigInteger(1, ...)` forces a positive interpretation: the coordinates
    // are unsigned magnitudes, not two's-complement values.
    val x = BigInteger(1, copyOfRange(1, 1 + c))
    val y = BigInteger(1, copyOfRange(1 + c, 1 + 2 * c))
    return ECPoint(x, y)
}
