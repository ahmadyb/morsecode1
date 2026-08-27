package net.morsecode.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.morsecode.net.AutoAcceptScope
import net.morsecode.net.CryptoProvider
import net.morsecode.net.DeviceIdentity
import net.morsecode.net.Discovery
import net.morsecode.net.FileManifestEntry
import net.morsecode.net.TransferController
import net.morsecode.net.createCrypto
import net.morsecode.net.createDiscovery
import net.morsecode.media.AppLibrary
import net.morsecode.media.MediaLibrary
import net.morsecode.media.createMediaLibrary
import net.morsecode.storage.ChatRepo
import net.morsecode.storage.HistoryRepo
import net.morsecode.storage.TrustedDeviceRepo
import net.morsecode.storage.TransferStateRepo
import net.morsecode.storage.createDatabase
import net.morsecode.storage.createDriver
import net.morsecode.storage.db.MorseCodeDatabase
import net.morsecode.util.Ids

/**
 * The session-wide service container the Compose tree reads from.
 *
 * Constructed once by the platform entry point (MainActivity / Main.kt).
 * Everything here is a thin handle over the real repositories and the
 * [TransferController]; screens take an [AppState] and derive UI state from its
 * flows.
 */
class AppState(
    val identity: DeviceIdentity,
    val crypto: CryptoProvider,
    val db: MorseCodeDatabase,
    val transferRepo: TransferStateRepo,
    val historyRepo: HistoryRepo,
    val trustedRepo: TrustedDeviceRepo,
    val chatRepo: ChatRepo,
    val media: MediaLibrary,
    val appLibrary: AppLibrary?,
    val discovery: Discovery,
    val controller: TransferController,
    val isDesktop: Boolean,
) {
    // Theme (Section A): light/dark/system. `system` is handled by the caller
    // defaulting darkTheme from isSystemInDarkTheme.
    var darkTheme by mutableStateOf(false)

    // Section 10 auto-accept settings.
    var autoAcceptEnabled by mutableStateOf(false)
    var autoAcceptAllDevices by mutableStateOf(false)

    // Section 12 bandwidth throttle; null = unlimited.
    var throttleKbps by mutableStateOf<Long?>(null)

    val devices get() = discovery.devices
    val rooms get() = discovery.rooms
    val pendingIncoming get() = controller.pendingRequest
    val receiveProgress get() = controller.receiveProgress
    val batchProgress get() = controller.batchProgress

    fun autoAcceptScope(): AutoAcceptScope = when {
        !autoAcceptEnabled -> AutoAcceptScope.OFF
        autoAcceptAllDevices -> AutoAcceptScope.ALL
        else -> AutoAcceptScope.TRUSTED_ONLY
    }
}

/**
 * Builds an [AppState]. Device name/type come from the platform; the app id is
 * generated per launch (MVP: trusted-device entries therefore reset across
 * restarts — noted in the README rather than papered over with a fake stable id).
 */
fun createAppState(
    deviceName: String,
    deviceType: String,
    appLibrary: AppLibrary? = null,
    isDesktop: Boolean,
    sinkFactory: suspend (net.morsecode.net.TransferRequest, FileManifestEntry) -> net.morsecode.net.ChunkSink?,
    sourceFactory: (FileManifestEntry) -> net.morsecode.net.ChunkSource,
): AppState {
    val crypto = createCrypto()
    val identity = DeviceIdentity(
        deviceId = Ids.hex(crypto.randomBytes(8)),
        deviceName = deviceName,
        deviceType = deviceType,
        appVersion = "1.0.0",
    )
    val db = createDatabase(createDriver())
    val transferRepo = TransferStateRepo(db)
    val trustedRepo = TrustedDeviceRepo(db)
    val discovery = createDiscovery()

    val controller = TransferController(
        identity = identity,
        crypto = crypto,
        store = transferRepo,
        discovery = discovery,
        isTrusted = { trustedRepo.isTrustedBlocking(it) },
        autoAcceptScope = { AutoAcceptScope.OFF },
        sinkFactory = sinkFactory,
        sourceFactory = sourceFactory,
    )

    return AppState(
        identity = identity,
        crypto = crypto,
        db = db,
        transferRepo = transferRepo,
        historyRepo = HistoryRepo(db),
        trustedRepo = trustedRepo,
        chatRepo = ChatRepo(db),
        media = createMediaLibrary(),
        appLibrary = appLibrary,
        discovery = discovery,
        controller = controller,
        isDesktop = isDesktop,
    )
}
