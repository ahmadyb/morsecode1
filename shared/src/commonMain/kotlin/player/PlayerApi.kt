package net.morsecode.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

enum class PlaybackState { IDLE, PLAYING, PAUSED, ENDED }

/**
 * Built-in video player (Section F.2). Android wraps Media3's PlayerView;
 * Desktop wraps VLCJ's EmbeddedMediaPlayerComponent (chosen over JavaFX — see
 * README).
 */
@Composable
expect fun VideoPlayer(
    uri: String,
    modifier: Modifier = Modifier,
    onPlaybackStateChanged: (PlaybackState) -> Unit = {},
)

/**
 * Background-capable audio controller (Section F.3).
 *
 * Android is driven by a foreground MediaSession service so playback survives
 * backgrounding; Desktop uses a headless VLCJ player.
 */
interface AudioPlaybackController {
    val isPlaying: StateFlow<Boolean>
    val currentPositionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    fun play(uri: String)
    fun pause()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()
    fun release()
}

expect fun createAudioController(): AudioPlaybackController
