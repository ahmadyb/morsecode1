package net.morsecode.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.File
import java.net.InetAddress
import net.morsecode.net.DeviceType
import net.morsecode.net.FileChunkSink
import net.morsecode.net.FileChunkSource
import net.morsecode.net.createCrypto
import net.morsecode.ui.AppState
import net.morsecode.ui.MorseCodeApp
import net.morsecode.ui.createAppState

/**
 * Desktop entry (Section D): hosts the shared Compose UI in a window.
 *
 * Tray / autostart / firewall diagnostics / drag-drop are wired from their
 * managers; the transfer engine lives in :shared.
 */
fun main() = application {
    val app = remember { buildDesktopAppState() }

    // Section D: verify VLC + firewall on first launch, non-blocking.
    FirewallDiagnostics.checkAndBanner()

    Window(onCloseRequest = ::exitApplication, title = "Morse Code") {
        MorseCodeApp(app)
    }
}

/**
 * Builds the desktop [AppState] with file-backed chunk source/sink so sends and
 * receives read/write the user's disk directly.
 */
fun buildDesktopAppState(): AppState {
    val crypto = createCrypto()
    val receiveDir = File(System.getProperty("user.home"), "MorseCode/Received").apply { mkdirs() }

    return createAppState(
        deviceName = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Desktop"),
        deviceType = DeviceType.WINDOWS,
        isDesktop = true,
        sinkFactory = { _, manifest ->
            FileChunkSink(
                path = File(receiveDir, manifest.filename).absolutePath,
                totalChunks = manifest.totalChunks,
                crypto = crypto,
            )
        },
        sourceFactory = { manifest ->
            FileChunkSource(path = File(manifest.relativePath ?: manifest.filename).absolutePath, crypto = crypto)
        },
    )
}
