package net.morsecode.ui

/**
 * True on the Desktop target, false on Android.
 *
 * Used to pick NavigationRail vs NavigationBar (Section A) and to hide the Apps
 * tab on Desktop (Section D). Implemented per-leaf because the shared `jvmMain`
 * cannot distinguish the two targets.
 */
expect fun isDesktopPlatform(): Boolean

/**
 * The LAN-facing IPv4 address to advertise / bind Web Connect to, or null when
 * there is no usable network. Link-local and loopback are excluded by the
 * implementation (Section H.3).
 */
expect fun lanAddress(): String?
