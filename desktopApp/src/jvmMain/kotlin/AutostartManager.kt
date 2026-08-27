package net.morsecode.desktop

/**
 * Windows Registry `Run` key management for launch-at-startup (Section D).
 *
 * Writes `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` via `reg.exe`,
 * which needs no elevation. Non-Windows platforms are a silent no-op.
 */
object AutostartManager {

    private const val KEY_NAME = "MorseCode"

    fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    fun enable(executablePath: String) {
        if (!isWindows()) return
        runCatching {
            ProcessBuilder(
                "reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", KEY_NAME, "/t", "REG_SZ", "/d", "\"$executablePath\"", "/f",
            ).start().waitFor()
        }
    }

    fun disable() {
        if (!isWindows()) return
        runCatching {
            ProcessBuilder(
                "reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", KEY_NAME, "/f",
            ).start().waitFor()
        }
    }
}
