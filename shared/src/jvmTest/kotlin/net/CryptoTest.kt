package net.morsecode.net

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import net.morsecode.util.Base64

/**
 * Phase 1: the crypto scheme from Section 4.
 *
 * Two kinds of assertion live here:
 *  - the published RFC 5869 test vector, which proves the hand-rolled HKDF is
 *    actually RFC-conformant rather than merely self-consistent;
 *  - adversarial cases (tampered ciphertext, replayed nonce, wrong key), which
 *    prove the failure modes close the connection instead of returning garbage.
 */
class CryptoTest {

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) {
            ((clean[it * 2].digitToInt(16) shl 4) or clean[it * 2 + 1].digitToInt(16)).toByte()
        }
    }

    // ── HKDF against RFC 5869 Appendix A.1 ───────────────────────────────────

    @Test
    fun `HKDF matches RFC 5869 test case 1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")

        val prk = Hkdf.extract(salt, ikm) { k, d -> hmac(k, d) }
        assertContentEquals(
            hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"),
            prk,
            "HKDF-Extract does not match RFC 5869 A.1",
        )

        val okm = Hkdf.expand(prk, info, 42) { k, d -> hmac(k, d) }
        assertContentEquals(
            hex(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                    "34007208d5b887185865",
            ),
            okm,
            "HKDF-Expand does not match RFC 5869 A.1",
        )
    }

    @Test
    fun `HKDF expand handles multi-block output`() {
        // RFC 5869 A.3: empty salt and info, 42-byte output from a 22-byte IKM.
        val ikm = ByteArray(22) { 0x0b }
        val prk = Hkdf.extract(ByteArray(0), ikm) { k, d -> hmac(k, d) }
        val okm = Hkdf.expand(prk, ByteArray(0), 42) { k, d -> hmac(k, d) }
        assertContentEquals(
            hex(
                "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d" +
                    "9d201395faa4b61a96c8",
            ),
            okm,
        )
    }

    @Test
    fun `HKDF rejects an output length above the RFC ceiling`() {
        assertFailsWith<IllegalArgumentException> {
            Hkdf.expand(ByteArray(32), ByteArray(0), 256 * 32) { k, d -> hmac(k, d) }
        }
    }

    @Test
    fun `salt is the sorted concatenation so both peers derive the same key`() {
        val a = ByteArray(65).also { it[0] = 0x04; it[1] = 0x11 }
        val b = ByteArray(65).also { it[0] = 0x04; it[1] = 0x22 }

        // Order of arguments must not change the result.
        assertContentEquals(Hkdf.sortedSalt(a, b), Hkdf.sortedSalt(b, a))
        assertEquals(130, Hkdf.sortedSalt(a, b).size)
        // And the smaller key comes first.
        assertEquals(0x11, Hkdf.sortedSalt(b, a)[1].toInt())
    }

    @Test
    fun `salt comparison is unsigned, not signed`() {
        // 0x80 is negative as a Kotlin Byte. Signed comparison would order it
        // before 0x7F and both peers would still agree, but the spec says
        // "sorted", which for binary data means unsigned.
        val low = ByteArray(65).also { it[0] = 0x04; it[1] = 0x7F }
        val high = ByteArray(65).also { it[0] = 0x04; it[1] = 0x80.toByte() }
        assertEquals(0x7F, Hkdf.sortedSalt(high, low)[1].toInt())
    }

    // ── ECDH ─────────────────────────────────────────────────────────────────

    @Test
    fun `both sides of an ECDH exchange derive the same session key`() {
        val alice = TestCrypto.newKeyExchange()
        val bob = TestCrypto.newKeyExchange()

        assertEquals(65, alice.publicKey.size)
        assertEquals(0x04, alice.publicKey[0].toInt(), "public key must be an uncompressed point")

        val aliceKey = TestCrypto.deriveSessionKey(
            alice.sharedSecret(bob.publicKey),
            alice.publicKey,
            bob.publicKey,
        )
        val bobKey = TestCrypto.deriveSessionKey(
            bob.sharedSecret(alice.publicKey),
            bob.publicKey,
            alice.publicKey,
        )

        assertEquals(32, aliceKey.size)
        assertContentEquals(aliceKey, bobKey, "ECDH peers must converge on one session key")
    }

    @Test
    fun `each connection gets a fresh ephemeral keypair`() {
        // Forward secrecy depends on this: reusing a keypair across connections
        // would let one compromise decrypt every past session.
        val first = TestCrypto.newKeyExchange()
        val second = TestCrypto.newKeyExchange()
        assertFalse(first.publicKey.contentEquals(second.publicKey))
    }

    @Test
    fun `public key survives a wire-format round trip`() {
        val kx = TestCrypto.newKeyExchange()
        val bytes = kx.publicKey
        val restored = bytes.toEcPoint().toUncompressedPoint()
        assertContentEquals(bytes, restored, "0x04||X||Y encoding must be lossless")
    }

    @Test
    fun `a compressed or truncated public key is refused`() {
        val kx = TestCrypto.newKeyExchange()
        val compressed = kx.publicKey.copyOf().also { it[0] = 0x02 }
        assertFailsWith<IllegalArgumentException> { compressed.toEcPoint() }
        assertFailsWith<IllegalArgumentException> { kx.publicKey.copyOfRange(0, 33).toEcPoint() }
    }

    // ── AES-GCM session channel ──────────────────────────────────────────────

    @Test
    fun `seal then open returns the original plaintext`() {
        val key = deterministicBytes(21, 32)
        val sender = TestCrypto.sessionCipher(key)
        val receiver = TestCrypto.sessionCipher(key)

        val plaintext = "the quick brown fox".encodeToByteArray()
        val sealed = sender.seal(plaintext)

        assertEquals(12, sealed.nonce.size)
        assertEquals(plaintext.size + Framing.GCM_TAG_BYTES, sealed.sealedBytes.size)
        assertContentEquals(plaintext, receiver.open(sealed.nonce, sealed.sealedBytes))
    }

    @Test
    fun `consecutive seals never reuse a nonce`() {
        val cipher = TestCrypto.sessionCipher(deterministicBytes(22, 32))
        val nonces = (0 until 1000).map { cipher.seal(ByteArray(1)).nonce }
        assertEquals(1000, nonces.map { it.toList() }.distinct().size, "nonce reuse would break GCM")
    }

    @Test
    fun `a tampered ciphertext fails the tag and reports decryption_failed`() {
        val key = deterministicBytes(23, 32)
        val sender = TestCrypto.sessionCipher(key)
        val receiver = TestCrypto.sessionCipher(key)

        val sealed = sender.seal("secret".encodeToByteArray())
        val tampered = sealed.sealedBytes.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }

        val ex = assertFailsWith<CryptoException> { receiver.open(sealed.nonce, tampered) }
        assertEquals(ErrorCode.DECRYPTION_FAILED, ex.code)
    }

    @Test
    fun `the wrong session key fails the tag`() {
        val sender = TestCrypto.sessionCipher(deterministicBytes(24, 32))
        val receiver = TestCrypto.sessionCipher(deterministicBytes(25, 32))
        val sealed = sender.seal("secret".encodeToByteArray())

        assertEquals(
            ErrorCode.DECRYPTION_FAILED,
            assertFailsWith<CryptoException> { receiver.open(sealed.nonce, sealed.sealedBytes) }.code,
        )
    }

    @Test
    fun `a replayed frame is refused even though its tag is valid`() {
        // A GCM tag verifies fine on replay; only the nonce counter catches it.
        // Without this check an attacker could re-inject an old CHUNK_ACK.
        val key = deterministicBytes(26, 32)
        val sender = TestCrypto.sessionCipher(key)
        val receiver = TestCrypto.sessionCipher(key)

        val sealed = sender.seal("one".encodeToByteArray())
        receiver.open(sealed.nonce, sealed.sealedBytes)

        val ex = assertFailsWith<CryptoException> { receiver.open(sealed.nonce, sealed.sealedBytes) }
        assertEquals(ErrorCode.NONCE_REUSE, ex.code)
    }

    @Test
    fun `an out-of-order older nonce is refused`() {
        val key = deterministicBytes(27, 32)
        val sender = TestCrypto.sessionCipher(key)
        val receiver = TestCrypto.sessionCipher(key)

        val first = sender.seal("a".encodeToByteArray())
        val second = sender.seal("b".encodeToByteArray())

        receiver.open(second.nonce, second.sealedBytes)
        assertEquals(
            ErrorCode.NONCE_REUSE,
            assertFailsWith<CryptoException> { receiver.open(first.nonce, first.sealedBytes) }.code,
        )
    }

    @Test
    fun `session key of the wrong length is rejected`() {
        assertFailsWith<IllegalArgumentException> { TestCrypto.sessionCipher(ByteArray(16)) }
    }

    // ── nonce encoding ───────────────────────────────────────────────────────

    @Test
    fun `nonce is 4 zero bytes followed by an 8-byte big-endian counter`() {
        val nonce = GcmNonceSequence.encode(0x0102030405060708L)
        assertEquals(12, nonce.size)
        assertContentEquals(byteArrayOf(0, 0, 0, 0), nonce.copyOfRange(0, 4))
        assertContentEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
            nonce.copyOfRange(4, 12),
        )
    }

    @Test
    fun `nonce counter starts at zero and increments by one`() {
        val seq = GcmNonceSequence()
        assertEquals(0L, GcmNonceSequence.decode(seq.next()))
        assertEquals(1L, GcmNonceSequence.decode(seq.next()))
        assertEquals(2L, GcmNonceSequence.decode(seq.next()))
    }

    @Test
    fun `a nonce with non-zero high bytes is rejected`() {
        val bad = GcmNonceSequence.encode(1L).also { it[0] = 1 }
        assertFailsWith<IllegalArgumentException> { GcmNonceSequence.decode(bad) }
    }

    // ── Base64 ───────────────────────────────────────────────────────────────

    @Test
    fun `base64 matches the RFC 4648 vectors`() {
        assertEquals("", Base64.encode("".encodeToByteArray()))
        assertEquals("Zg==", Base64.encode("f".encodeToByteArray()))
        assertEquals("Zm8=", Base64.encode("fo".encodeToByteArray()))
        assertEquals("Zm9v", Base64.encode("foo".encodeToByteArray()))
        assertEquals("Zm9vYmE=", Base64.encode("fooba".encodeToByteArray()))
        assertEquals("Zm9vYmFy", Base64.encode("foobar".encodeToByteArray()))
    }

    @Test
    fun `base64 round-trips every byte value`() {
        val all = ByteArray(256) { it.toByte() }
        assertContentEquals(all, Base64.decode(Base64.encode(all)))
    }

    @Test
    fun `base64 round-trips a 65-byte public key`() {
        val key = TestCrypto.newKeyExchange().publicKey
        assertContentEquals(key, Base64.decode(Base64.encode(key)))
    }

    @Test
    fun `base64 rejects characters outside the alphabet`() {
        assertFailsWith<IllegalArgumentException> { Base64.decode("Zm9v*") }
        assertFailsWith<IllegalArgumentException> { Base64.decode("=====") }
    }

    @Test
    fun `sha256 matches a known digest`() {
        // SHA-256("abc")
        assertEquals(
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            Hex.bytes(TestCrypto.sha256("abc".encodeToByteArray())),
        )
    }

    @Test
    fun `hex decode rejects odd length and non-hex characters`() {
        assertFailsWith<IllegalArgumentException> { Hex.decode("abc") }
        assertFailsWith<IllegalArgumentException> { Hex.decode("zz") }
        assertNotEquals(0, Hex.decode("00ff").size)
        assertTrue(Hex.bytes(byteArrayOf(0x00, 0xFF.toByte())) == "00FF")
    }
}
