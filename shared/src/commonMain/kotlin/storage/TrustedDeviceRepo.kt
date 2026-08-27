package net.morsecode.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.morsecode.storage.db.MorseCodeDatabase

/** A persisted `trusted_device` row. */
data class StoredTrustedDevice(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val lastSeenIp: String?,
    val trustedAtEpochMs: Long,
    val lastSeenAtEpochMs: Long,
)

/**
 * Section 9. The hot path is [isTrusted], called on every incoming HELLO; it is
 * a COUNT over a primary key, so it stays fast even with many entries.
 */
class TrustedDeviceRepo(
    private val db: MorseCodeDatabase,
) {
    private val q get() = db.trustedDeviceQueries

    suspend fun isTrusted(deviceId: String): Boolean = withContext(Dispatchers.Default) {
        q.isTrusted(deviceId).executeAsOne() > 0L
    }

    /** Synchronous variant for use inside the handshake, which is not suspend. */
    fun isTrustedBlocking(deviceId: String): Boolean = q.isTrusted(deviceId).executeAsOne() > 0L

    suspend fun trust(device: StoredTrustedDevice) = withContext(Dispatchers.Default) {
        q.trust(
            device_id = device.deviceId,
            device_name = device.deviceName,
            device_type = device.deviceType,
            last_seen_ip = device.lastSeenIp,
            trusted_at = device.trustedAtEpochMs,
            last_seen_at = device.lastSeenAtEpochMs,
        )
    }

    suspend fun forget(deviceId: String) = withContext(Dispatchers.Default) {
        q.forget(deviceId)
    }

    suspend fun all(): List<StoredTrustedDevice> = withContext(Dispatchers.Default) {
        q.selectAll().executeAsList().map {
            StoredTrustedDevice(
                deviceId = it.device_id,
                deviceName = it.device_name,
                deviceType = it.device_type,
                lastSeenIp = it.last_seen_ip,
                trustedAtEpochMs = it.trusted_at,
                lastSeenAtEpochMs = it.last_seen_at,
            )
        }
    }

    suspend fun touch(deviceId: String, ip: String?, atEpochMs: Long) = withContext(Dispatchers.Default) {
        q.touchLastSeen(atEpochMs, ip, deviceId)
    }
}
