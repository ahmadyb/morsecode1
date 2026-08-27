package net.morsecode.net

/**
 * The crypto abstraction that `commonMain` codes against.
 *
 * Deliberately dependency injection rather than `expect`/`actual`: the spec
 * only mandates expect/actual for Discovery, Thumbnail, MediaLibrary and
 * VideoPlayer. Keeping crypto behind a plain interface means the windowed
 * transfer state machines can be unit-tested with a deterministic fake cipher,
 * and it avoids one more place where a KMP source-set mistake could silently
 * pick the wrong implementation.
 *
 * The production implementation is [JceCryptoProvider] in
 * `jvmMain/net/Crypto.kt` — pure `javax.crypto` / `java.security`, no
 * third-party crypto dependency, exactly as the TECH STACK section requires.
 */
interface CryptoProvider {
    fun sha256(data: ByteArray): ByteArray

    /** HMAC-SHA256, injected into [Hkdf]. */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** Cryptographically strong random bytes (transfer ids, pairing tokens). */
    fun randomBytes(count: Int): ByteArray

    /** Generates a fresh ephemeral ECDH keypair. One per connection. */
    fun newKeyExchange(): KeyExchange

    /** Builds the AES-256-GCM channel for a derived session key. */
    fun sessionCipher(sessionKey: ByteArray): SessionCipher
}

/**
 * One side of an ephemeral ECDH exchange.
 *
 * A fresh instance is created for every TCP connection (Section 4), which is
 * what gives the protocol forward secrecy: the session key cannot be recovered
 * later even if a long-term device key is compromised, because no long-term
 * key participates in the derivation at all.
 */
interface KeyExchange {
    /** 65-byte uncompressed point: `0x04 || X || Y`. */
    val publicKey: ByteArray

    /** Raw ECDH output against the peer's 65-byte uncompressed public key. */
    fun sharedSecret(remotePublicKey: ByteArray): ByteArray
}

/**
 * Convenience: derive the session key using this provider's HMAC.
 *
 * Wraps [Hkdf.deriveSessionKey] so callers do not have to thread the HMAC
 * function reference through by hand — and so there is exactly one call site
 * where the salt/info/length parameters are assembled.
 */
fun CryptoProvider.deriveSessionKey(
    sharedSecret: ByteArray,
    publicKeyA: ByteArray,
    publicKeyB: ByteArray,
): ByteArray = Hkdf.deriveSessionKey(sharedSecret, publicKeyA, publicKeyB) { key, data ->
    hmacSha256(key, data)
}

/**
 * Builds the production [CryptoProvider]. The JCE implementation lives in
 * `jvmMain`, so common code obtains it through this expect.
 */
expect fun createCrypto(): CryptoProvider
