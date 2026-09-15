package dev.omatube.app.player

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import dev.omatube.app.OmaTubeApplication
import dev.omatube.app.model.Settings
import dev.omatube.app.model.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Started foreground service that owns the real [PlayerController] outside
 * automation, so ExoPlayer, source resolution, SponsorBlock and watch
 * accounting keep running when the Activity stops, the screen locks or the
 * user goes home.
 *
 * The current controller is published through [controller] for the in-process
 * Compose tree and is cleared in [onDestroy]. The service owns release; the
 * Activity/Compose layer only starts it and asks it to stop on explicit close.
 */
class PlaybackService : Service() {

    private val serviceScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, throwable ->
                if (throwable is CancellationException) return@CoroutineExceptionHandler
                reportUncaught(throwable)
            },
    )
    private val playbackMutex = Mutex()

    private var mediaSession: MediaSession? = null
    private var controller: PlayerController? = null
    private var currentVideo: Video? = null
    private var stateJob: Job? = null
    private var settingsJob: Job? = null
    private var lastNotificationKey: String? = null

    /**
     * Settings snapshot that was last pushed into the controller. Used to
     * merge only player-editable fields back into the store so concurrent
     * edits from the settings screen are not clobbered.
     */
    @Volatile
    private var controllerSettingsBase: Settings = Settings()

    override fun onCreate() {
        super.onCreate()
        PlaybackNotifications.ensureChannel(this)
        ensureMediaSession()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopPlayback()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                val video = decodeVideo(intent)
                if (video == null) {
                    stopPlayback()
                } else {
                    // Foreground promptly with a transport notification, then
                    // build the controller. A repeated start for the same video
                    // reuses the live controller state instead of showing the
                    // loading frame again.
                    val sameVideo = controller != null && currentVideo?.id == video.id
                    val state = if (sameVideo) controller?.uiState?.value else null
                    ensureForeground(
                        title = state?.title ?: video.title,
                        subtitle = video.channelTitle,
                        playing = state?.playing ?: false,
                        loading = state?.loading ?: true,
                    )
                    startVideo(video, decodeSettings(intent))
                }
            }
            ACTION_PLAY -> controller?.play()
            ACTION_PAUSE -> controller?.pause()
            ACTION_STOP -> stopPlayback()
            else -> if (controller == null) stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        settingsJob?.cancel()
        val existing = controller
        controller = null
        currentVideo = null
        if (_controller.value === existing) {
            _controller.value = null
        }
        existing?.release()
        releaseMediaSession()
        // Idempotent: drop any foreground state/notification the service still
        // owns even if stopPlayback was not the path that destroyed it.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
        super.onDestroy()
    }

    // region Video lifecycle

    private fun startVideo(video: Video, suppliedSettings: Settings?) {
        if (controller != null && currentVideo?.id == video.id) {
            // Repeated start for the same video must not restart playback.
            refreshNotification()
            return
        }
        releaseController()
        currentVideo = video

        val graph = (application as OmaTubeApplication).appGraph
        // Prefer the settings serialized with the start command so the very
        // first resolution uses what the UI observed; fall back to the store
        // when the intent is missing or malformed. The settings observer below
        // stays authoritative afterwards.
        val initialSettings = suppliedSettings ?: graph.currentSettings()
        controllerSettingsBase = initialSettings
        val created = PlayerController(
            context = applicationContext,
            video = video,
            initialSettings = initialSettings,
            backend = graph.backend,
            automation = false,
            onSettingsChange = ::persistControllerSettings,
            onReportPlayback = ::persistPlaybackReport,
            scope = serviceScope,
        )
        controller = created
        _controller.value = created
        // Recreate the session if a previous release left it null; a valid
        // ACTION_START must never run without a session.
        ensureMediaSession().isActive = true
        observeController(created)
        observeSettings(created)
        created.start()
        refreshNotification()
    }

    private fun releaseController() {
        stateJob?.cancel()
        stateJob = null
        settingsJob?.cancel()
        settingsJob = null
        val existing = controller
        controller = null
        currentVideo = null
        if (_controller.value === existing) {
            _controller.value = null
        }
        existing?.release()
    }

    private fun stopPlayback() {
        releaseController()
        releaseMediaSession()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // endregion

    // region Observers

    private fun observeController(created: PlayerController) {
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            created.uiState.collect { state ->
                updateMediaSession(state)
                updateNotification(state)
            }
        }
    }

    private fun observeSettings(created: PlayerController) {
        settingsJob?.cancel()
        val graph = (application as OmaTubeApplication).appGraph
        settingsJob = serviceScope.launch {
            graph.settingsStore.settings.collect { settings ->
                controllerSettingsBase = settings
                created.updateSettings(settings)
            }
        }
    }

    // endregion

    // region Persistence

    private fun persistPlaybackReport(
        videoId: String,
        positionSeconds: Long,
        watchedDeltaSeconds: Long,
        newSession: Boolean,
    ) {
        val graph = (application as OmaTubeApplication).appGraph
        graph.scope.launch {
            playbackMutex.withLock {
                try {
                    graph.repository.recordPlayback(
                        videoId = videoId,
                        positionSeconds = positionSeconds,
                        watchedDeltaSeconds = watchedDeltaSeconds,
                        newSession = newSession,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    graph.reportError(throwable)
                }
            }
        }
    }

    private fun persistControllerSettings(updated: Settings) {
        val graph = (application as OmaTubeApplication).appGraph
        val base = controllerSettingsBase
        graph.scope.launch {
            try {
                graph.settingsStore.update { current ->
                    mergeControllerSettings(current, base, updated)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                graph.reportError(throwable)
            }
        }
    }

    private fun mergeControllerSettings(
        current: Settings,
        base: Settings,
        updated: Settings,
    ): Settings {
        var result = current
        if (updated.playbackVolume != base.playbackVolume) {
            result = result.copy(playbackVolume = updated.playbackVolume)
        }
        if (updated.lastUsedVideoHeight != base.lastUsedVideoHeight) {
            result = result.copy(lastUsedVideoHeight = updated.lastUsedVideoHeight)
        }
        val changes = changedEntries(base.videoQualityOverrides, updated.videoQualityOverrides)
        if (changes.isNotEmpty()) {
            val merged = result.videoQualityOverrides.toMutableMap()
            for ((key, value) in changes) {
                if (value == null) merged.remove(key) else merged[key] = value
            }
            result = result.copy(videoQualityOverrides = merged)
        }
        return result
    }

    private fun <V> changedEntries(base: Map<String, V>, updated: Map<String, V>): Map<String, V?> {
        val changes = LinkedHashMap<String, V?>()
        for ((key, value) in updated) {
            if (base[key] != value) changes[key] = value
        }
        for (key in base.keys) {
            if (!updated.containsKey(key)) changes[key] = null
        }
        return changes
    }

    // endregion

    // region Media session and notification

    /** Lazily creates the session so a released session can never block a start. */
    internal fun ensureMediaSession(): MediaSession {
        mediaSession?.let { return it }
        val created = createMediaSession()
        mediaSession = created
        return created
    }

    private fun createMediaSession(): MediaSession {
        val session = MediaSession(this, MEDIA_SESSION_TAG)
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                controller?.play()
            }

            override fun onPause() {
                controller?.pause()
            }

            override fun onStop() {
                // A lock-screen/MediaSession stop ends the session rather than
                // only pausing, so the foreground notification cannot linger.
                stopPlayback()
            }

            override fun onSeekTo(pos: Long) {
                controller?.seekTo(pos)
            }
        })
        session.setSessionActivity(PlaybackNotifications.contentIntent(this))
        return session
    }

    internal fun updateMediaSession(state: PlayerUiState) {
        val session = mediaSession ?: return
        val playbackState = when {
            state.error != null -> PlaybackState.STATE_ERROR
            state.ended -> PlaybackState.STATE_STOPPED
            state.loading -> PlaybackState.STATE_BUFFERING
            state.playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PLAYBACK_ACTIONS)
                .setState(playbackState, state.positionMs, state.speed.coerceAtLeast(0.1f))
                .build(),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentVideo?.channelTitle)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
                .build(),
        )
    }

    private fun updateNotification(state: PlayerUiState) {
        val key = notificationKey(state.playing, state.loading, state.ended, state.error, state.title)
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        val session = mediaSession ?: return
        val notification = PlaybackNotifications.build(
            context = this,
            sessionToken = session.sessionToken,
            title = state.title.ifBlank { currentVideo?.title.orEmpty() },
            subtitle = currentVideo?.channelTitle.orEmpty(),
            playing = state.playing,
            loading = state.loading,
        )
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun refreshNotification() {
        updateNotification(controller?.uiState?.value ?: return)
    }

    private fun ensureForeground(title: String, subtitle: String, playing: Boolean, loading: Boolean) {
        val session = ensureMediaSession()
        lastNotificationKey = notificationKey(playing, loading, false, null, title)
        val notification = PlaybackNotifications.build(
            context = this,
            sessionToken = session.sessionToken,
            title = title,
            subtitle = subtitle,
            playing = playing,
            loading = loading,
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    internal fun releaseMediaSession() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }

    private fun notificationKey(
        playing: Boolean,
        loading: Boolean,
        ended: Boolean,
        error: String?,
        title: String,
    ): String = "$playing|$loading|$ended|$error|$title"

    // endregion

    /**
     * Routes an uncaught scope failure to the current graph without throwing if
     * the graph has already been replaced or closed.
     */
    private fun reportUncaught(throwable: Throwable) {
        val graph = runCatching { (application as OmaTubeApplication).appGraph }.getOrNull() ?: return
        runCatching { graph.reportError(throwable) }
    }

    companion object {
        const val ACTION_START = "dev.omatube.app.action.PLAYBACK_START"
        const val ACTION_PLAY = "dev.omatube.app.action.PLAYBACK_PLAY"
        const val ACTION_PAUSE = "dev.omatube.app.action.PLAYBACK_PAUSE"
        const val ACTION_STOP = "dev.omatube.app.action.PLAYBACK_STOP"

        const val REQUEST_CONTENT = 100
        const val REQUEST_PLAY = 101
        const val REQUEST_PAUSE = 102

        private const val EXTRA_VIDEO = "dev.omatube.app.extra.PLAYBACK_VIDEO"
        private const val EXTRA_SETTINGS = "dev.omatube.app.extra.PLAYBACK_SETTINGS"
        private const val NOTIFICATION_ID = 1001
        private const val MEDIA_SESSION_TAG = "OmaTubePlayback"

        private val PLAYBACK_ACTIONS =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SEEK_TO

        private val gson = Gson()

        private val _controller = MutableStateFlow<PlayerController?>(null)

        /** Active service-owned controller, or null when the service is idle. */
        val controller: StateFlow<PlayerController?> = _controller.asStateFlow()

        /**
         * Builds the explicit start intent that serializes the selected video
         * and the UI-observed settings so the first resolution does not race
         * the settings graph.
         */
        fun startIntent(context: Context, video: Video, settings: Settings): Intent =
            Intent(context, PlaybackService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_VIDEO, gson.toJson(video))
                .putExtra(EXTRA_SETTINGS, gson.toJson(settings))

        internal fun decodeVideo(intent: Intent): Video? {
            val json = intent.getStringExtra(EXTRA_VIDEO) ?: return null
            return try {
                gson.fromJson(json, Video::class.java)
            } catch (malformed: JsonSyntaxException) {
                null
            }
        }

        internal fun decodeSettings(intent: Intent): Settings? {
            val json = intent.getStringExtra(EXTRA_SETTINGS) ?: return null
            return try {
                gson.fromJson(json, Settings::class.java)
            } catch (malformed: JsonSyntaxException) {
                null
            }
        }

        /** Stops the service; [onDestroy] flushes the final watch report. */
        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
