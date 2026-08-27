package net.morsecode.android

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import java.io.File
import net.morsecode.media.createMediaLibrary
import net.morsecode.media.AppLibraryAndroid
import net.morsecode.net.DeviceType
import net.morsecode.net.FileChunkSink
import net.morsecode.net.FileChunkSource
import net.morsecode.net.createCrypto
import net.morsecode.storage.AndroidAppContext
import net.morsecode.ui.MorseCodeApp
import net.morsecode.ui.createAppState
import net.morsecode.ui.theme.MorseCodeTheme

/**
 * Android entry (Section C): hosts the shared Compose UI.
 *
 * All Android-specific wiring — permissions, the transfer foreground service,
 * the multicast lock for mDNS — happens here and through the helpers, keeping
 * :shared free of Android UI concerns.
 */
class MainActivity : ComponentActivity() {

    private val permissions = PermissionsManager(this)
    private val multicast = MulticastLockManager(this)
    private val transferService by lazy { MorseForegroundService }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidAppContext.context = applicationContext
        val app = buildAndroidAppState()

        permissions.requestOnLaunch()
        multicast.acquire()
        transferService.start(this)

        setContent {
            MorseCodeTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MorseCodeApp(app)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        multicast.release()
        transferService.stop(this)
    }
}

/**
 * Builds the Android [AppState] with file-backed chunk I/O.
 *
 * Sends read from the device via `ContentResolver` into a temp file (a
 * `file://` path is what [FileChunkSource] needs); receives land in the
 * app-private Downloads dir, which needs no permission on any API level.
 */
fun buildAndroidAppState(): net.morsecode.ui.AppState {
    val ctx = AndroidAppContext.context
    val crypto = createCrypto()
    val receiveDir = File(
        ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir,
        "Received",
    ).apply { mkdirs() }

    return createAppState(
        deviceName = "This Phone",
        deviceType = DeviceType.ANDROID,
        appLibrary = AppLibraryAndroid(ctx),
        isDesktop = false,
        sinkFactory = { _, manifest ->
            FileChunkSink(
                path = File(receiveDir, manifest.filename).absolutePath,
                totalChunks = manifest.totalChunks,
                crypto = crypto,
            )
        },
        sourceFactory = { manifest ->
            // Copy the content:// source into a temp file the chunk source can seek.
            val temp = File(ctx.cacheDir, "send_" + manifest.id + ".part")
            if (!temp.exists() || temp.length() != manifest.sizeBytes) {
                runCatching {
                    ctx.contentResolver.openInputStream(android.net.Uri.parse(manifest.uri))?.use { input ->
                        temp.outputStream().use { input.copyTo(it) }
                    }
                }
            }
            FileChunkSource(path = temp.absolutePath, crypto = crypto)
        },
    )
}
