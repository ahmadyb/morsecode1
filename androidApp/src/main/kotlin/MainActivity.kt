package net.morsecode.android

import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
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

    // Not eager field initialisers: an exception thrown while the Activity is
    // being constructed happens before onCreate runs, so nothing could catch it
    // and the app would simply vanish with no explanation on screen.
    private val permissions by lazy { PermissionsManager(this) }
    private var multicast: MulticastLockManager? = null
    private val transferService by lazy { MorseForegroundService }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startApp()
        } catch (t: Throwable) {
            showCrash(t)
        }
    }

    private fun startApp() {
        AndroidAppContext.context = applicationContext
        val app = buildAndroidAppState()

        permissions.requestOnLaunch()
        multicast = MulticastLockManager(this).also { it.acquire() }
        transferService.start(this)

        setContent {
            MorseCodeTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MorseCodeApp(app)
                }
            }
        }
    }

    /**
     * Startup failure screen.
     *
     * This app ships no analytics and no crash reporter by design (zero
     * tracking), so on a device with no adb attached a startup crash is
     * otherwise invisible — the launcher icon bounces and nothing happens.
     * Printing the trace on screen, selectable so it can be copied into a bug
     * report, keeps that diagnosis possible without sending anything anywhere.
     * The same text is written to app-private storage, which survives the
     * process death at `/sdcard/Android/data/<pkg>/files/last-crash.txt`.
     */
    private fun showCrash(t: Throwable) {
        val trace = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()
        runCatching {
            File(getExternalFilesDir(null) ?: filesDir, "last-crash.txt").writeText(trace)
        }
        val traceView = TextView(this).apply {
            setTextIsSelectable(true)
            setPadding(32, 48, 32, 32)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            text = "Morse Code could not start.\n\n" +
                "Long-press to select and copy this trace:\n\n$trace"
        }
        setContentView(ScrollView(this).apply { addView(traceView) })
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { multicast?.release() }
        runCatching { transferService.stop(this) }
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
        appLibrary = AppLibraryAndroid(),
        isDesktop = false,
        sinkFactory = { _, manifest ->
            FileChunkSink(
                path = File(receiveDir, manifest.filename).absolutePath,
                totalChunks = manifest.totalChunks,
                crypto = crypto,
            )
        },
        sourceFactory = { manifest ->
            // The manifest carries a relative/absolute path the chunk source can seek.
            FileChunkSource(
                path = File(manifest.relativePath ?: manifest.filename).absolutePath,
                crypto = crypto,
            )
        },
    )
}
