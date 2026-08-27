package net.morsecode.desktop

import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.imageio.ImageIO

/**
 * System-tray icon with Open / Pause Discovery / Quit (Section D).
 *
 * Guarded by [SystemTray.isSupported] — headless CI runners and some WMs have
 * no tray, and absence must be silent rather than fatal.
 */
object TrayManager {

    private var icon: TrayIcon? = null

    fun install(onOpen: () -> Unit, onQuit: () -> Unit) {
        if (!SystemTray.isSupported()) return
        runCatching {
            val image = TrayManager::class.java.getResourceAsStream("/tray-icon-256.png")
                ?.use { ImageIO.read(it) } ?: return
            val menu = PopupMenu()
            menu.add(MenuItem("Open").apply { addActionListener { onOpen() } })
            menu.add(MenuItem("Pause Discovery"))
            menu.add(MenuItem("Quit").apply { addActionListener { onQuit() } })
            icon = TrayIcon(image, "Morse Code", menu).apply { isImageAutoSize = true }
            SystemTray.getSystemTray().add(icon)
        }
    }

    fun uninstall() {
        runCatching { icon?.let { SystemTray.getSystemTray().remove(it) } }
        icon = null
    }
}
