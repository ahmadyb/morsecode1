package net.morsecode.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

/**
 * VLCJ-backed video player (Section F.2, Desktop actual).
 *
 * VLCJ requires VLC installed on the host; the desktop wrapper's startup check
 * (Section D) shows a banner with a download link if it is missing rather than
 * failing silently.
 */
@Composable
actual fun VideoPlayer(
    uri: String,
    modifier: Modifier,
    onPlaybackStateChanged: (PlaybackState) -> Unit,
) {
    val component = remember { EmbeddedMediaPlayerComponent() }
    val lastUri = remember { arrayOf<String?>(null) }

    DisposableEffect(component) {
        onDispose { runCatching { component.mediaPlayer().release() } }
    }

    SwingPanel(
        modifier = modifier,
        factory = { component },
        update = { c ->
            if (lastUri[0] != uri) {
                lastUri[0] = uri
                runCatching { c.mediaPlayer().media().play(uri) }
                onPlaybackStateChanged(PlaybackState.PLAYING)
            }
        },
    )
}

/** Headless VLCJ audio controller (Section F.3, Desktop actual). */
class AudioPlaybackControllerDesktop : AudioPlaybackController {
    private val factory = MediaPlayerFactory()
    // A plain media player with no video surface plays audio only.
    private val player = factory.mediaPlayers().newMediaPlayer()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _position = MutableStateFlow(0L)
    override val currentPositionMs: StateFlow<Long> = _position.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _duration.asStateFlow()

    override fun play(uri: String) {
        runCatching { player.media().play(uri) }
        _isPlaying.value = true
    }

    override fun pause() {
        runCatching { player.controls().pause() }
        _isPlaying.value = false
    }

    override fun seekTo(positionMs: Long) {
        runCatching { player.controls().setPosition(positionMs.toFloat() / (_duration.value.coerceAtLeast(1))) }
        _position.value = positionMs
    }

    override fun next() { }
    override fun previous() { }

    override fun release() {
        runCatching { player.release() }
        runCatching { factory.release() }
    }
}

actual fun createAudioController(): AudioPlaybackController = AudioPlaybackControllerDesktop()
