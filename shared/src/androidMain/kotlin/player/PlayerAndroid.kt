package net.morsecode.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Media3-backed video player (Section F.2, Android actual).
 *
 * The ExoPlayer instance is scoped to the composition and released on dispose,
 * per the spec's lifecycle requirement.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
actual fun VideoPlayer(
    uri: String,
    modifier: Modifier,
    onPlaybackStateChanged: (PlaybackState) -> Unit,
) {
    val context = LocalContext.current
    val exo = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(exo) { onDispose { exo.release() } }

    AndroidView(
        modifier = modifier,
        factory = { ctx -> PlayerView(ctx).apply { player = exo } },
        update = { view ->
            val current = view.player?.currentMediaItem?.localConfiguration?.uri?.toString()
            if (current != uri) {
                view.player?.setMediaItem(MediaItem.fromUri(uri))
                view.player?.prepare()
            }
        },
    )
}

/**
 * ExoPlayer-driven audio controller (Section F.3, Android actual).
 *
 * Playback continues in [AudioPlaybackService] when the app is backgrounded;
 * this controller is the UI-facing handle.
 */
class AudioPlaybackControllerAndroid : AudioPlaybackController {
    private var exo: ExoPlayer? = null

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _position = MutableStateFlow(0L)
    override val currentPositionMs: StateFlow<Long> = _position.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _duration.asStateFlow()

    private fun player(ctx: android.content.Context): ExoPlayer =
        exo ?: ExoPlayer.Builder(ctx).build().also { exo = it }

    override fun play(uri: String) {
        val ctx = net.morsecode.storage.AndroidAppContext.context
        val p = player(ctx)
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.play()
        _isPlaying.value = true
    }

    override fun pause() {
        exo?.pause()
        _isPlaying.value = false
    }

    override fun seekTo(positionMs: Long) {
        exo?.seekTo(positionMs)
        _position.value = positionMs
    }

    override fun next() { /* playlist support is a later step */ }
    override fun previous() { }

    override fun release() {
        exo?.release()
        exo = null
    }
}

actual fun createAudioController(): AudioPlaybackController = AudioPlaybackControllerAndroid()
