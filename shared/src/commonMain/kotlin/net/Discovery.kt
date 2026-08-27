package net.morsecode.net

import kotlinx.coroutines.flow.StateFlow

/**
 * A peer seen on the LAN via mDNS (Section 3).
 *
 * [lastSeenMs] drives the "lost after 15 seconds unseen" rule; the
 * implementation is responsible for expiring entries, consumers just read the
 * current list.
 */
data class DiscoveredDevice(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val host: String,
    val port: Int,
    val roomId: String?,
    val appVersion: String,
    val protoVersion: Int,
    val lastSeenMs: Long,
) {
    val advertisesRoom: Boolean get() = roomId != null

    fun toRecipient(pairingToken: String? = null, trusted: Boolean = false): Recipient =
        Recipient(deviceId, deviceName, host, port, pairingToken, trusted)
}

/**
 * mDNS service advertisement + discovery over `_morsecode._tcp.local.`.
 *
 * Declared as `expect` so the UI codes against it; the JmDNS-backed `actual`
 * lives in `jvmMain` and is shared by Android and Desktop, since JmDNS is pure
 * Java and behaves the same on both.
 */
interface Discovery {
    val devices: StateFlow<List<DiscoveredDevice>>

    /** Devices currently advertising a `room_id`, for the "Join Room" picker. */
    val rooms: StateFlow<List<DiscoveredDevice>>

    /**
     * Starts advertising this device and listening for others.
     *
     * @param port the TCP port actually bound (may be ephemeral — Section 1
     *   requires publishing the real port, not the requested one).
     * @param roomId set when this device is hosting a room (Section 8).
     */
    fun start(self: DeviceIdentity, port: Int, roomId: String? = null)

    fun stop()
}

expect fun createDiscovery(): Discovery
