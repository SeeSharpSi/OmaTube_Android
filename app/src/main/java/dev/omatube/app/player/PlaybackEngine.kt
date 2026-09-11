package dev.omatube.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Immutable snapshot of the playback engine, consumed by the controller. */
data class PlaybackSnapshot(
    val playing: Boolean = false,
    val loading: Boolean = false,
    val ended: Boolean = false,
    val live: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val error: String? = null,
    val videoHeight: Int? = null,
)

/**
 * Minimal abstraction over the playback backend so the chrome can be driven
 * deterministically in automation mode without Media3, network or media files.
 */
interface PlaybackEngine {
    val snapshot: StateFlow<PlaybackSnapshot>

    fun load(mediaSource: MediaSource?, startPositionMs: Long, live: Boolean)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun seekToLiveEdge()

    fun setVolume(volume: Float)

    fun setSpeed(speed: Float)

    fun setMaxVideoHeight(height: Int)

    fun release()
}

/** Real Media3 engine used outside automation mode. */
@OptIn(UnstableApi::class)
class ExoPlaybackEngine(context: Context) : PlaybackEngine {
    private val playerInstance = ExoPlayer.Builder(context).build()
    private val _snapshot = MutableStateFlow(PlaybackSnapshot(loading = true))
    override val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Exposed to the `PlayerView` surface. */
    val player: Player get() = playerInstance

    init {
        playerInstance.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _snapshot.update {
                    it.copy(
                        loading = playbackState == Player.STATE_IDLE ||
                            playbackState == Player.STATE_BUFFERING,
                        ended = playbackState == Player.STATE_ENDED,
                        error = if (playbackState != Player.STATE_IDLE) null else it.error,
                    )
                }
                refreshPosition()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _snapshot.update { it.copy(playing = isPlaying) }
            }

            override fun onPlayerError(error: PlaybackException) {
                _snapshot.update {
                    it.copy(
                        loading = false,
                        playing = false,
                        error = error.message ?: "Playback error",
                    )
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val height = videoSize.height.takeIf { it > 0 }
                _snapshot.update { it.copy(videoHeight = height) }
            }
        })
        scope.launch {
            while (isActive) {
                refreshPosition()
                delay(POSITION_POLL_MS)
            }
        }
    }

    override fun load(mediaSource: MediaSource?, startPositionMs: Long, live: Boolean) {
        if (mediaSource == null) return
        _snapshot.update { it.copy(loading = true, ended = false, error = null, live = live) }
        playerInstance.setMediaSource(mediaSource)
        playerInstance.prepare()
        if (startPositionMs > 0L) {
            playerInstance.seekTo(startPositionMs)
        }
    }

    override fun play() {
        playerInstance.play()
    }

    override fun pause() {
        playerInstance.pause()
    }

    override fun seekTo(positionMs: Long) {
        playerInstance.seekTo(positionMs.coerceAtLeast(0L))
        refreshPosition()
    }

    override fun seekToLiveEdge() {
        playerInstance.seekToDefaultPosition()
        refreshPosition()
    }

    override fun setVolume(volume: Float) {
        playerInstance.volume = volume.coerceIn(0f, 1f)
    }

    override fun setSpeed(speed: Float) {
        playerInstance.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
    }

    override fun setMaxVideoHeight(height: Int) {
        val parameters = playerInstance.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, if (height > 0) height else Int.MAX_VALUE)
            .build()
        playerInstance.trackSelectionParameters = parameters
    }

    override fun release() {
        scope.cancel()
        playerInstance.release()
    }

    private fun refreshPosition() {
        val duration = playerInstance.duration
        _snapshot.update {
            it.copy(
                positionMs = playerInstance.currentPosition.coerceAtLeast(0L),
                durationMs = if (duration == C.TIME_UNSET || duration < 0L) 0L else duration,
                bufferedMs = playerInstance.bufferedPosition.coerceAtLeast(0L),
                playing = playerInstance.isPlaying,
            )
        }
    }

    private companion object {
        const val POSITION_POLL_MS = 250L
    }
}

/**
 * Deterministic automation engine. Never touches media, the extractor or the
 * network; it only advances a virtual position while "playing".
 *
 * [scope] lets the owner share its coroutine scope (and cancellation) with the
 * engine. When omitted the engine owns a main-dispatcher scope and cancels it
 * on [release].
 */
class FakePlaybackEngine(
    scope: CoroutineScope? = null,
    private val durationMs: Long = 10 * 60 * 1000L,
    private val tickMs: Long = 250L,
) : PlaybackEngine {
    private val _snapshot = MutableStateFlow(
        PlaybackSnapshot(durationMs = durationMs, loading = false),
    )
    override val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    private val ownsScope = scope == null
    private val scope: CoroutineScope =
        scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickJob: Job? = null
    private var loadingJob: Job? = null
    private var speed = 1f

    override fun load(mediaSource: MediaSource?, startPositionMs: Long, live: Boolean) {
        tickJob?.cancel()
        loadingJob?.cancel()
        _snapshot.value = PlaybackSnapshot(
            playing = false,
            loading = true,
            live = live,
            positionMs = startPositionMs.coerceAtLeast(0L),
            durationMs = durationMs,
        )
        loadingJob = scope.launch {
            delay(LOADING_DELAY_MS)
            _snapshot.update { it.copy(loading = false) }
        }
    }

    override fun play() {
        if (_snapshot.value.ended) return
        _snapshot.update { it.copy(playing = true) }
        startTicking()
    }

    override fun pause() {
        _snapshot.update { it.copy(playing = false) }
        tickJob?.cancel()
    }

    override fun seekTo(positionMs: Long) {
        _snapshot.update {
            it.copy(
                positionMs = positionMs.coerceIn(0L, durationMs),
                ended = false,
            )
        }
    }

    override fun seekToLiveEdge() {
        seekTo(durationMs)
    }

    override fun setVolume(volume: Float) = Unit

    override fun setSpeed(speed: Float) {
        this.speed = speed.coerceIn(0.25f, 4f)
    }

    override fun setMaxVideoHeight(height: Int) = Unit

    override fun release() {
        tickJob?.cancel()
        loadingJob?.cancel()
        tickJob = null
        loadingJob = null
        if (ownsScope) {
            scope.cancel()
        }
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(tickMs)
                val current = _snapshot.value
                if (!current.playing) return@launch
                val next = current.positionMs + (tickMs * speed).toLong()
                if (next >= durationMs) {
                    _snapshot.update {
                        it.copy(positionMs = durationMs, playing = false, ended = true)
                    }
                    return@launch
                }
                _snapshot.update { it.copy(positionMs = next) }
            }
        }
    }

    private companion object {
        const val LOADING_DELAY_MS = 300L
    }
}
