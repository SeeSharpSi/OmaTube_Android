package dev.omatube.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.omatube.app.backend.VideoBackend
import dev.omatube.app.model.Settings
import dev.omatube.app.model.SponsorSegment
import dev.omatube.app.model.Video
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withContext

/** Everything the player chrome needs to render. */
data class PlayerUiState(
    val title: String = "",
    val isLive: Boolean = false,
    val playing: Boolean = false,
    val loading: Boolean = true,
    val ended: Boolean = false,
    val error: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val volume: Int = 100,
    val muted: Boolean = false,
    val speed: Float = 1f,
    val qualityValue: Int = PlaybackQuality.DEFAULT,
    val effectiveHeight: Int = PlaybackQuality.AUTO,
    val selectedHeight: Int? = null,
    val sponsorSegments: List<SponsorSegment> = emptyList(),
    val manualSegment: SponsorSegment? = null,
) {
    val overlay: Overlay get() = when {
        error != null -> Overlay.ERROR
        loading -> Overlay.LOADING
        ended -> Overlay.ENDED
        else -> Overlay.NONE
    }

    enum class Overlay { NONE, LOADING, ERROR, ENDED }
}

/**
 * Owns the playback backend, source resolution, watch accounting and
 * SponsorBlock integration for a single video.
 *
 * The ViewModel/root owns persistence and passes [onReportPlayback] and
 * [onSettingsChange] callbacks in; this class never touches the repository.
 * It is not recreated on rotation (the Activity handles orientation changes),
 * so positions and session accounting survive rotation and quality changes.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    private val video: Video,
    initialSettings: Settings,
    private val backend: VideoBackend,
    val automation: Boolean,
    private val onSettingsChange: (Settings) -> Unit,
    private val onReportPlayback: (String, Long, Long, Boolean) -> Unit,
    private val scope: CoroutineScope,
    engineOverride: PlaybackEngine? = null,
    private val watchAccounting: WatchAccounting = WatchAccounting(),
) {
    private val appContext: Context by lazy { context.applicationContext }

    /**
     * Child scope that owns every coroutine this controller starts, so
     * [release] cancels all of them and cannot leak on close or rotation.
     */
    private val controllerScope: CoroutineScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val engine: PlaybackEngine = engineOverride
        ?: if (automation) FakePlaybackEngine(controllerScope) else ExoPlaybackEngine(context)

    private val dataSources: PlaybackDataSources by lazy { PlaybackDataSources(appContext) }
    private val resolver: MediaSourceResolver by lazy { MediaSourceResolver(dataSources) }

    private val watch: WatchAccounting = watchAccounting
    private val sponsor = SponsorBlockController(backend, controllerScope)

    private var settings: Settings = initialSettings
    private var started = false
    private var released = false
    private var reextracted = false
    private var resolveJob: Job? = null
    private var watchJob: Job? = null

    private val _uiState = MutableStateFlow(
        PlayerUiState(
            title = video.title,
            isLive = video.isLive,
            volume = initialSettings.playbackVolume,
            muted = initialSettings.playbackVolume == 0,
            qualityValue = initialSettings.videoQualityOverrides[video.id] ?: PlaybackQuality.DEFAULT,
            effectiveHeight = PlaybackQuality.effectiveHeight(initialSettings, video.id),
        ),
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /** Media3 player for `PlayerView`, or null in automation mode. */
    val player: Player? get() = (engine as? ExoPlaybackEngine)?.player

    init {
        controllerScope.launch {
            engine.snapshot.collect(::handleSnapshot)
        }
        controllerScope.launch {
            sponsor.segments.collect { segments ->
                _uiState.update { it.copy(sponsorSegments = segments) }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        val startMs = startPositionMs(video)
        watch.start(video.id, startMs / 1000L)
        sponsor.load(video.id, settings.sponsorBlockEnabled && !automation)
        startWatchLoop()
        resolveAndLoad(startMs, resumePlaying = true)
    }

    fun togglePlay() {
        val state = _uiState.value
        if (state.ended) {
            replay()
            return
        }
        if (state.playing) {
            flushReport()
            engine.pause()
        } else {
            engine.play()
        }
    }

    fun replay() {
        flushReport()
        watch.onSeek(0L)
        engine.seekTo(0L)
        engine.play()
    }

    fun retry() {
        flushReport()
        resolveAndLoad(_uiState.value.positionMs, resumePlaying = true)
    }

    fun seekTo(positionMs: Long) {
        flushReport()
        val clamped = positionMs.coerceAtLeast(0L)
        watch.onSeek(clamped / 1000L)
        engine.seekTo(clamped)
        _uiState.update { it.copy(positionMs = clamped, ended = false) }
    }

    fun setSpeed(speed: Float) {
        engine.setSpeed(speed)
        _uiState.update { it.copy(speed = speed) }
    }

    /** Seeks to the live edge for DASH/HLS live streams. */
    fun seekToLiveEdge() {
        flushReport()
        watch.onSeek(_uiState.value.positionMs / 1000L)
        engine.seekToLiveEdge()
    }

    fun setVolumePercent(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        settings = settings.copy(playbackVolume = clamped)
        onSettingsChange(settings)
        engine.setVolume(clamped / 100f)
        _uiState.update { it.copy(volume = clamped, muted = clamped == 0) }
    }

    fun toggleMute() {
        val muted = _uiState.value.muted
        if (muted) {
            engine.setVolume(settings.playbackVolume / 100f)
            _uiState.update { it.copy(muted = false) }
        } else {
            engine.setVolume(0f)
            _uiState.update { it.copy(muted = true) }
        }
    }

    fun setQuality(value: Int) {
        val previousEffective = PlaybackQuality.effectiveHeight(settings, video.id)
        if (automation) {
            val effective = if (value == PlaybackQuality.DEFAULT) {
                PlaybackQuality.normalizeGlobalHeight(settings.maximumVideoHeight)
            } else {
                PlaybackQuality.normalizeGlobalHeight(value)
            }
            _uiState.update { it.copy(qualityValue = value, effectiveHeight = effective) }
            return
        }
        settings = PlaybackQuality.applyChoice(settings, video.id, value)
        onSettingsChange(settings)
        val effective = PlaybackQuality.effectiveHeight(settings, video.id)
        _uiState.update {
            it.copy(
                qualityValue = settings.videoQualityOverrides[video.id] ?: PlaybackQuality.DEFAULT,
                effectiveHeight = effective,
            )
        }
        if (effective != previousEffective) {
            val position = _uiState.value.positionMs
            val playing = _uiState.value.playing
            flushReport()
            resolveAndLoad(position, resumePlaying = playing)
        }
    }

    /** Applies externally persisted settings without echoing them back. */
    fun updateSettings(newSettings: Settings) {
        val previousEffective = PlaybackQuality.effectiveHeight(settings, video.id)
        val sponsorEnabledChanged = newSettings.sponsorBlockEnabled != settings.sponsorBlockEnabled
        settings = newSettings
        engine.setVolume(newSettings.playbackVolume / 100f)
        _uiState.update {
            it.copy(
                volume = newSettings.playbackVolume,
                muted = newSettings.playbackVolume == 0,
                qualityValue = newSettings.videoQualityOverrides[video.id] ?: PlaybackQuality.DEFAULT,
                effectiveHeight = PlaybackQuality.effectiveHeight(newSettings, video.id),
            )
        }
        if (sponsorEnabledChanged) {
            sponsor.load(video.id, newSettings.sponsorBlockEnabled && !automation)
        }
        val effective = PlaybackQuality.effectiveHeight(newSettings, video.id)
        if (!automation && effective != previousEffective) {
            val position = _uiState.value.positionMs
            val playing = _uiState.value.playing
            flushReport()
            resolveAndLoad(position, resumePlaying = playing)
        }
    }

    /** Called on lifecycle stop when the activity is not in picture-in-picture. */
    fun onBackground() {
        flushReport()
        if (_uiState.value.playing) {
            engine.pause()
        }
    }

    /** Pauses when audio focus is lost or headphones are unplugged. */
    fun pauseIfPlaying() {
        flushReport()
        if (_uiState.value.playing) {
            engine.pause()
        }
    }

    /** Attenuates the volume for transient audio focus loss. */
    fun setDucked(ducked: Boolean) {
        val base = settings.playbackVolume / 100f
        engine.setVolume(if (ducked) base * DUCK_FACTOR else base)
    }

    fun release() {
        if (released) return
        released = true
        flushReport()
        watch.stop()?.let(::emitReport)
        sponsor.cancel()
        controllerScope.cancel()
        engine.release()
    }

    // region Internals

    private fun handleSnapshot(snapshot: PlaybackSnapshot) {
        val error = snapshot.error
        if (!automation && error != null && !reextracted && isExpiredSourceError(error)) {
            reextracted = true
            val resumeMs = snapshot.positionMs
            flushReport()
            resolveAndLoad(resumeMs, resumePlaying = true)
            return
        }
        val positionSeconds = snapshot.positionMs / 1000L
        val playing = snapshot.playing && snapshot.error == null
        // Watch accounting is driven by the periodic watch loop, not by UI
        // snapshot churn, so reports keep saving even when no interaction
        // occurs.

        if (!automation && playing) {
            val target = sponsor.autoSkipTarget(
                positionSeconds.toDouble(),
                settings.sponsorActions,
                settings.sponsorBlockEnabled,
            )
            if (target != null) {
                val targetMs = (target * 1000.0).toLong()
                if (targetMs != snapshot.positionMs) {
                    seekTo(targetMs)
                }
            }
        }

        val manual = if (automation) {
            null
        } else {
            sponsor.manualSegment(
                positionSeconds.toDouble(),
                settings.sponsorActions,
                settings.sponsorBlockEnabled,
            )
        }

        _uiState.update {
            it.copy(
                playing = playing,
                loading = snapshot.loading && snapshot.error == null,
                ended = snapshot.ended,
                isLive = snapshot.live,
                positionMs = snapshot.positionMs,
                durationMs = snapshot.durationMs,
                bufferedMs = snapshot.bufferedMs,
                error = snapshot.error,
                selectedHeight = snapshot.videoHeight ?: it.selectedHeight,
                manualSegment = manual,
            )
        }
    }

    private fun resolveAndLoad(startPositionMs: Long, resumePlaying: Boolean) {
        resolveJob?.cancel()
        val effectiveHeight = PlaybackQuality.effectiveHeight(settings, video.id)
        resolveJob = controllerScope.launch {
            _uiState.update { it.copy(loading = true, error = null, ended = false) }
            engine.setMaxVideoHeight(effectiveHeight)
            if (automation) {
                engine.load(mediaSource = null, startPositionMs = startPositionMs, live = video.isLive)
                if (resumePlaying) engine.play()
                return@launch
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    val info = backend.resolveStream(video.id)
                    resolver.resolve(info, effectiveHeight)
                }
                when (result) {
                    is MediaSourceResolver.Result.Success -> {
                        _uiState.update { it.copy(selectedHeight = result.selectedHeight) }
                        engine.load(result.mediaSource, startPositionMs, result.isLive)
                        if (resumePlaying) engine.play()
                    }
                    is MediaSourceResolver.Result.Failure -> {
                        _uiState.update { it.copy(loading = false, error = result.message) }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        playing = false,
                        error = throwable.message ?: "Could not start playback",
                    )
                }
            }
        }
    }

    private fun startWatchLoop() {
        if (watchJob?.isActive == true) return
        watchJob = controllerScope.launch {
            while (isActive) {
                delay(WATCH_SAVE_TICK_MS)
                val snapshot = engine.snapshot.value
                watch.onPosition(
                    positionSeconds = snapshot.positionMs / 1000L,
                    playing = snapshot.playing && snapshot.error == null,
                )
                watch.collect()?.let(::emitReport)
            }
        }
    }

    private fun flushReport() {
        val snapshot = engine.snapshot.value
        watch.onPosition(
            positionSeconds = snapshot.positionMs / 1000L,
            playing = _uiState.value.playing && snapshot.error == null,
        )
        watch.collect(force = true)?.let(::emitReport)
    }

    private fun emitReport(report: WatchAccounting.Report) {
        onReportPlayback(
            report.videoId,
            report.positionSeconds,
            report.elapsedWatchedDeltaSeconds,
            report.newSession,
        )
    }

    private fun startPositionMs(video: Video): Long {
        if (video.isLive) return 0L
        val resume = video.lastPositionSeconds
        if (resume < MINIMUM_RESUME_SECONDS) return 0L
        val duration = video.durationSeconds
        if (duration > 0 && resume >= duration - RESUME_END_THRESHOLD_SECONDS) return 0L
        return resume * 1000L
    }

    /**
     * YouTube stream URLs expire. A 403/410/412 playback failure is retried
     * exactly once with a fresh extraction while keeping the current position.
     */
    private fun isExpiredSourceError(message: String): Boolean {
        return message.contains("403") ||
            message.contains("410") ||
            message.contains("412") ||
            message.contains("Forbidden", ignoreCase = true) ||
            message.contains("expired", ignoreCase = true)
    }

    // endregion

    private companion object {
        const val MINIMUM_RESUME_SECONDS = 30L
        const val RESUME_END_THRESHOLD_SECONDS = 90L
        const val DUCK_FACTOR = 0.3f

        /** How often the periodic watch collector checks for a due report. */
        const val WATCH_SAVE_TICK_MS = 1_000L
    }
}
