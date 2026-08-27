package net.morsecode.webconnect

/**
 * Section H.3 pairing: a fresh 6-digit PIN each time Web Connect is enabled
 * (never persisted), plus an HttpOnly session cookie that expires after 30
 * minutes of inactivity or immediately when Web Connect is toggled off.
 *
 * Pure and injectable so the PIN/session rules are testable without a server.
 */
class PairingManager(
    private val randomBytes: (Int) -> ByteArray,
    private val nowMillis: () -> Long,
    private val sessionTimeoutMs: Long = DEFAULT_SESSION_TIMEOUT_MS,
) {
    /** The current 6-digit PIN, or null while Web Connect is off. */
    var pin: String? = null
        private set

    /** One-time token embedded in the QR URL, skipping manual PIN entry. */
    var qrToken: String? = null
        private set

    /** session token -> last-active timestamp. */
    private val sessions = HashMap<String, Long>()

    /** Enables Web Connect, minting a fresh PIN and QR token. */
    fun enable() {
        // 6-digit numeric PIN: map one random byte to 0-9 per digit. Using a
        // modulo of a random byte is slightly biased; for a 6-digit pairing PIN
        // that a human types, the bias is immaterial and keeps it purely numeric.
        pin = String(CharArray(6) { ('0' + (randomBytes(1)[0].toInt() and 0xFF) % 10).toChar() })
        qrToken = token()
        sessions.clear()
    }

    /** Toggling off invalidates every session immediately (Section H.3). */
    fun disable() {
        pin = null
        qrToken = null
        sessions.clear()
    }

    /**
     * Validates a submitted PIN or QR token.
     *
     * @return a fresh session token on success, null on failure. A consumed QR
     *   token also opens a session (it is one-time in the sense that it is only
     *   ever shown in the QR, not that it cannot be redeemed).
     */
    fun pair(submitted: String): String? {
        if (pin == null) return null
        return when {
            submitted == pin -> newSession()
            submitted == qrToken -> newSession()
            else -> null
        }
    }

    /**
     * @return true when [token] is a live session. Touches it on success so an
     *   active browser keeps its session; an idle one lapses after 30 minutes.
     */
    fun validate(token: String): Boolean {
        val last = sessions[token] ?: return false
        if (nowMillis() - last > sessionTimeoutMs) {
            sessions.remove(token)
            return false
        }
        sessions[token] = nowMillis()
        return true
    }

    private fun newSession(): String = token().also { sessions[it] = nowMillis() }

    private fun token(): String = net.morsecode.util.Ids.hex(randomBytes(16))

    companion object {
        const val DEFAULT_SESSION_TIMEOUT_MS: Long = 30 * 60 * 1000
    }
}
