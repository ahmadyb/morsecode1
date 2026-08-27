package net.morsecode.android

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Acquires a Wi-Fi multicast lock so mDNS multicast (5353) is not filtered out
 * by the radio's default unicast-only mode. Held only while the app is in the
 * foreground and released on stop, per Section H's "LAN-only, minimal footprint".
 */
class MulticastLockManager(context: Context) {

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var lock: WifiManager.MulticastLock? = null

    fun acquire() {
        if (lock?.isHeld == true) return
        lock = wifiManager.createMulticastLock("morsecode_mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    fun release() {
        runCatching { if (lock?.isHeld == true) lock?.release() }
        lock = null
    }
}
