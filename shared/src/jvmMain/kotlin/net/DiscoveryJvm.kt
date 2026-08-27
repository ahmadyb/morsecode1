package net.morsecode.net

import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

actual fun createDiscovery(): Discovery = JmDnsDiscovery()

/**
 * JmDNS-backed [Discovery], shared by Android and Desktop.
 *
 * The listener callbacks arrive on JmDNS's own threads, so the map is guarded by
 * `synchronized`; our own housekeeping (the 15-second expiry sweep) runs in a
 * coroutine, per the no-raw-threads-by-us rule.
 *
 * Presence rules from Section 3: advertise `_morsecode._tcp.local.` with the
 * device metadata in TXT, and treat a peer as lost 15 s after it is last seen.
 */
class JmDnsDiscovery : Discovery {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val map = HashMap<String, DiscoveredDevice>()

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _rooms = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val rooms: StateFlow<List<DiscoveredDevice>> = _rooms.asStateFlow()

    private var jmdns: JmDNS? = null
    private var sweep: Job? = null
    private var selfId: String? = null
    private var listener: ServiceListener? = null

    override fun start(self: DeviceIdentity, port: Int, roomId: String?) {
        stop()
        selfId = self.deviceId

        val address = NetworkInterfaces.primaryLanAddress()
            ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
            ?: InetAddress.getLoopbackAddress()

        val dns = JmDNS.create(address, self.deviceName.take(20))
        jmdns = dns

        val txt = MdnsTxt.toMap(
            deviceId = self.deviceId,
            deviceName = self.deviceName,
            deviceType = self.deviceType,
            appVersion = self.appVersion,
            protoVersion = PROTO_VERSION,
            roomId = roomId,
        )
        runCatching {
            dns.registerService(
                ServiceInfo.create(MdnsTxt.SERVICE_TYPE, self.deviceName.take(20), port, 0, 0, HashMap(txt)),
            )
        }

        val l = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                // Resolution is delivered via serviceResolved; nothing to do here.
            }

            override fun serviceRemoved(event: ServiceEvent) {
                val id = runCatching { event.info.getPropertyString(MdnsTxt.KEY_DEVICE_ID) }.getOrNull()
                if (id != null) synchronized(map) {
                    map.remove(id)
                    publishLocked()
                }
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info ?: return
                val id = info.getPropertyString(MdnsTxt.KEY_DEVICE_ID) ?: return
                if (id == selfId) return

                val device = DiscoveredDevice(
                    deviceId = id,
                    deviceName = info.getPropertyString(MdnsTxt.KEY_DEVICE_NAME) ?: info.name,
                    deviceType = info.getPropertyString(MdnsTxt.KEY_DEVICE_TYPE) ?: "unknown",
                    host = info.hostAddress ?: info.inetAddress?.hostAddress ?: return,
                    port = info.port,
                    roomId = info.getPropertyString(MdnsTxt.KEY_ROOM_ID),
                    appVersion = info.getPropertyString(MdnsTxt.KEY_APP_VERSION) ?: "",
                    protoVersion = info.getPropertyString(MdnsTxt.KEY_PROTO_VERSION)?.toIntOrNull() ?: PROTO_VERSION,
                    lastSeenMs = now(),
                )
                synchronized(map) {
                    map[id] = device
                    publishLocked()
                }
            }
        }
        listener = l
        dns.addServiceListener(MdnsTxt.SERVICE_TYPE, l)

        sweep = scope.launch {
            while (isActive) {
                delay(EXPIRY_SWEEP_MS)
                expire()
            }
        }
    }

    private fun expire() {
        val cutoff = now() - DEVICE_LOST_MS
        synchronized(map) {
            val before = map.size
            map.entries.removeIf { it.value.lastSeenMs < cutoff }
            if (map.size != before) publishLocked()
        }
    }

    private fun publishLocked() {
        val all = map.values.sortedBy { it.deviceName }
        _devices.value = all
        _rooms.value = all.filter { it.advertisesRoom }
    }

    override fun stop() {
        sweep?.cancel()
        sweep = null
        val dns = jmdns
        listener?.let { runCatching { dns?.removeServiceListener(MdnsTxt.SERVICE_TYPE, it) } }
        listener = null
        runCatching { dns?.unregisterAllServices() }
        runCatching { dns?.close() }
        jmdns = null
        synchronized(map) {
            map.clear()
            publishLocked()
        }
    }

    private fun now(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

    companion object {
        const val DEVICE_LOST_MS: Long = 15_000
        const val EXPIRY_SWEEP_MS: Long = 5_000
    }
}
