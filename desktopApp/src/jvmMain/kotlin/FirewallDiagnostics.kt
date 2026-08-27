package net.morsecode.desktop

import java.io.File

/**
 * Section D startup checks: verify VLC presence and detect the Windows
 * firewall posture, surfacing a non-blocking banner instead of failing later.
 *
 * Runs synchronously but cheaply at startup; it only stat()s a few paths and,
 * on Windows, shells out to `netsh` once. Output goes to stdout for now; the UI
 * banner hook is [bannerMessage].
 */
object FirewallDiagnostics {

    /** Non-null when the user should be shown a non-blocking banner. */
    var bannerMessage: String? = null
        private set

    fun checkAndBanner() {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (!isWindows) return

        if (!vlcInstalled()) {
            bannerMessage = "VLC not found. Video/audio playback needs VLC installed " +
                "(https://www.videolan.org). Install it, then restart Morse Code."
            println("[MorseCode] ${bannerMessage}")
        }

        if (!firewallAllows()) {
            bannerMessage = (bannerMessage ?: "") +
                "\nWindows Firewall may block incoming transfers. Allow Morse Code through " +
                "private networks if discovery fails."
            println("[MorseCode] Firewall check suggests possible block.")
        }
    }

    /** Looks for VLC in the usual places. */
    private fun vlcInstalled(): Boolean {
        val candidates = listOf(
            "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe",
            "C:\\Program Files (x86)\\VideoLAN\\VLC\\vlc.exe",
        )
        if (candidates.any { File(it).exists() }) return true
        return runCatching {
            ProcessBuilder("where", "vlc").start().waitFor() == 0
        }.getOrDefault(false)
    }

    /** Best-effort `netsh` detection; a failure to run it is treated as "unknown -> ok". */
    private fun firewallAllows(): Boolean = runCatching {
        val p = ProcessBuilder("netsh", "advfirewall", "show", "currentprofile", "state").start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        // If the firewall is ON we simply remind the user; we cannot know our rule
        // for certain without elevating, so "ON" -> false (show the hint).
        !out.contains("ON", ignoreCase = true)
    }.getOrDefault(true)
}
