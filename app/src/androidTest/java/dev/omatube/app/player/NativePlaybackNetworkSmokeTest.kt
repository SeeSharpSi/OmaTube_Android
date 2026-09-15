package dev.omatube.app.player

import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.omatube.app.backend.NewPipeBackend
import dev.omatube.app.model.Settings
import dev.omatube.app.model.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Opt-in, network-dependent, real Media3 playback check.
 *
 * It is NOT part of the default offline suite. It only runs when the
 * instrumentation argument `omatubePlaybackSmoke=true` is supplied, for
 * example:
 *
 * ```
 * adb -s emulator-5580 shell am instrument -w -r \
 *   -e omatubePlaybackSmoke true \
 *   -e class dev.omatube.app.player.NativePlaybackNetworkSmokeTest \
 *   dev.omatube.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * Scope and safety:
 *  - a standalone [ComponentActivity] declared by the test manifest is launched;
 *    MainActivity and the production `AppGraph` are never touched;
 *  - the only backend is `NewPipeBackend(context) { Settings(...) }`, so no user
 *    database, no repository and no YouTube Data API key is involved;
 *  - it resolves and plays one public video through the real
 *    `MediaSourceResolver` + `ExoPlaybackEngine` produced by `PlayerController`
 *    with `automation=false`;
 *  - it never prints stream URLs or credentials; diagnostics carry the title,
 *    per-kind source counts, renderer states, track selections and positions.
 *
 * The bounded waits below fail the test rather than loop forever, and every
 * media/player/backend resource is released in `finally`.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class NativePlaybackNetworkSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var backend: NewPipeBackend
    private lateinit var scope: CoroutineScope
    private var controller: PlayerController? = null
    private var playerView: PlayerView? = null

    private val renderedFirstFrame = AtomicBoolean(false)
    private val observedStates = Collections.synchronizedList(mutableListOf<Int>())
    private val playerError = AtomicReference<String?>(null)

    @Before
    fun requireOptIn() {
        assumeTrue(
            "Set -e omatubePlaybackSmoke true to run this real playback check",
            InstrumentationRegistry.getArguments().getString(SMOKE_ARGUMENT) == "true",
        )
    }

    @Test
    fun playsPublicVideoThroughRealMedia3() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Settings(
            wifiMaximumVideoHeight = INITIAL_MAX_HEIGHT,
            dataMaximumVideoHeight = INITIAL_MAX_HEIGHT,
            lastUsedVideoHeight = INITIAL_MAX_HEIGHT,
        )
        val video = Video(
            id = PUBLIC_VIDEO_ID,
            channelId = PUBLIC_CHANNEL_ID,
            title = "OmaTube native playback smoke $PUBLIC_VIDEO_ID",
            durationSeconds = PUBLIC_VIDEO_DURATION_SECONDS,
        )

        backend = NewPipeBackend(context) { settings }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        try {
            compose.setContent {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            useController = false
                            setShutterBackgroundColor(Color.BLACK)
                        }.also { playerView = it }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            compose.waitForIdle()
            val surface = checkNotNull(playerView) { "PlayerView surface was not created" }

            compose.runOnUiThread {
                val created = PlayerController(
                    context = context,
                    video = video,
                    initialSettings = settings,
                    backend = backend,
                    automation = false,
                    onSettingsChange = {},
                    onReportPlayback = { _, _, _, _ -> },
                    scope = scope,
                )
                val player = checkNotNull(created.player) {
                    "Real ExoPlayer was not created for automation=false"
                }
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        observedStates.add(playbackState)
                    }

                    override fun onRenderedFirstFrame() {
                        renderedFirstFrame.set(true)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        playerError.compareAndSet(null, "${error.errorCodeName}: ${error.message}")
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) = Unit
                })
                surface.player = player
                controller = created
                created.start()
            }

            // Real Media3 must buffer a network source before it can play.
            await("the player to report STATE_BUFFERING") {
                observedStates.contains(Player.STATE_BUFFERING)
            }
            await("real playback to reach STATE_READY and start", READY_TIMEOUT_MS) {
                val active = requirePlayer()
                active.playbackState == Player.STATE_READY && active.isPlaying
            }
            assertTrue(
                "expected STATE_BUFFERING before STATE_READY, observed ${stateNames()}",
                observedStates.contains(Player.STATE_BUFFERING),
            )

            // The video renderer must have drawn a real frame, and the merged
            // video+audio sources must both have a selected track.
            await("the video renderer to draw its first frame", FIRST_FRAME_TIMEOUT_MS) {
                renderedFirstFrame.get()
            }
            compose.runOnUiThread {
                val active = requirePlayer()
                val size = active.videoSize
                assertTrue(
                    "expected a non-zero decoded video size, got ${size.width}x${size.height}",
                    size.width > 0 && size.height > 0,
                )
                assertTrue(
                    "expected a selected video track (AV merge), tracks=${trackSummary(active)}",
                    active.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO),
                )
                assertTrue(
                    "expected a selected audio track (AV merge), tracks=${trackSummary(active)}",
                    active.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO),
                )
                assertTrue(
                    "decoded video height ${size.height} exceeds the requested max $INITIAL_MAX_HEIGHT",
                    size.height <= INITIAL_MAX_HEIGHT,
                )
                assertTrue(
                    "selected source height ${controller?.uiState?.value?.selectedHeight} " +
                        "exceeds the requested max $INITIAL_MAX_HEIGHT",
                    (controller?.uiState?.value?.selectedHeight ?: 0) in 1..INITIAL_MAX_HEIGHT,
                )
            }

            // Position must advance by at least the required real-time window.
            val advanceStart = playerPosition()
            await("the playback position to advance ${MIN_ADVANCE_MS}ms", ADVANCE_TIMEOUT_MS) {
                requirePlayer().currentPosition - advanceStart >= MIN_ADVANCE_MS
            }

            // Pause must hold the position.
            compose.runOnUiThread { controller?.togglePlay() }
            await("pause to take effect") { !requirePlayer().isPlaying }
            val pausedAt = playerPosition()
            Thread.sleep(PAUSE_HOLD_MS)
            val stillPausedAt = playerPosition()
            assertTrue(
                "position moved while paused: $pausedAt -> $stillPausedAt",
                abs(stillPausedAt - pausedAt) < PAUSE_TOLERANCE_MS,
            )

            // Seek must land on the requested position.
            val duration = compose.runOnUiThread { requirePlayer().duration }
            val seekTarget = (pausedAt + SEEK_FORWARD_MS).let { proposed ->
                if (duration > 0L) proposed.coerceAtMost(duration - SEEK_MARGIN_MS) else proposed
            }
            compose.runOnUiThread { controller?.seekTo(seekTarget) }
            await("the seek to $seekTarget") {
                abs(requirePlayer().currentPosition - seekTarget) < SEEK_TOLERANCE_MS
            }

            // Resume must play from the seeked position.
            val resumeFrom = playerPosition()
            compose.runOnUiThread { controller?.togglePlay() }
            await("resume to play") { requirePlayer().isPlaying }
            await("position to advance after resume") {
                requirePlayer().currentPosition - resumeFrom >= RESUME_ADVANCE_MS
            }

            // Quality change: lower to 360p, then back to the 720p maximum. The
            // reload must preserve the position and keep the merged audio+video
            // tracks selected.
            val beforeDown = playerPosition()
            compose.runOnUiThread { controller?.setQuality(360) }
            await("the 360p quality change to finish", QUALITY_TIMEOUT_MS) {
                val active = requirePlayer()
                controller?.uiState?.value?.effectiveHeight == 360 &&
                    (controller?.uiState?.value?.selectedHeight ?: 0) in 1..360 &&
                    active.isPlaying
            }
            assertTrue(
                "position was not preserved across the 360p change " +
                    "($beforeDown -> ${playerPosition()})",
                abs(playerPosition() - beforeDown) < QUALITY_POSITION_TOLERANCE_MS,
            )
            assertSelectedAvTracks("360p")

            val beforeUp = playerPosition()
            compose.runOnUiThread { controller?.setQuality(PlaybackQuality.DEFAULT) }
            await("the 720p quality change to finish", QUALITY_TIMEOUT_MS) {
                val active = requirePlayer()
                // Wait for the reloaded source, not just the synchronous
                // effectiveHeight update, so the 720 selection is actually
                // observed rather than the previous 360 track.
                controller?.uiState?.value?.effectiveHeight == INITIAL_MAX_HEIGHT &&
                    controller?.uiState?.value?.selectedHeight == INITIAL_MAX_HEIGHT &&
                    active.isPlaying
            }
            assertTrue(
                "position was not preserved across the 720p change " +
                    "($beforeUp -> ${playerPosition()})",
                abs(playerPosition() - beforeUp) < QUALITY_POSITION_TOLERANCE_MS,
            )
            assertSelectedAvTracks("720p")
            assertTrue(
                "decoded height after the 720p change exceeds the maximum",
                compose.runOnUiThread { requirePlayer().videoSize.height } in 1..INITIAL_MAX_HEIGHT,
            )

            Log.i(TAG, "PASS real Media3 playback: ${summary()}")
        } finally {
            compose.runOnIdle {
                runCatching { playerView?.player = null }
                runCatching { controller?.release() }
                runCatching { scope.cancel() }
            }
            runCatching { backend.close() }
        }
    }

    private fun requirePlayer(): Player =
        checkNotNull(controller?.player) { "Real ExoPlayer is not available" }

    private fun playerPosition(): Long = compose.runOnUiThread { requirePlayer().currentPosition }

    private fun assertSelectedAvTracks(stage: String) {
        compose.runOnUiThread {
            val active = requirePlayer()
            assertTrue(
                "missing selected video track after $stage, tracks=${trackSummary(active)}",
                active.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO),
            )
            assertTrue(
                "missing selected audio track after $stage, tracks=${trackSummary(active)}",
                active.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO),
            )
        }
    }

    private fun await(
        what: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            playerError.get()?.let { error ->
                failNow("player error while waiting for $what: $error")
            }
            if (compose.runOnUiThread { condition() }) return
            Thread.sleep(POLL_MS)
        }
        failNow("timed out after ${timeoutMs}ms waiting for $what; ${diagnostics()}")
    }

    private fun failNow(message: String): Nothing {
        val diagnostics = runCatching { diagnostics() }.getOrNull()
        throw AssertionError("$message ($diagnostics)")
    }

    private fun diagnostics(): String = compose.runOnUiThread {
        val active = controller?.player
        "states=${stateNames()} playing=${active?.isPlaying} " +
            "state=${stateName(active?.playbackState)} " +
            "position=${active?.currentPosition} duration=${active?.duration} " +
            "video=${active?.videoSize?.width}x${active?.videoSize?.height} " +
            "selectedHeight=${controller?.uiState?.value?.selectedHeight} " +
            "tracks=${active?.let { trackSummary(it) }}"
    }

    private fun summary(): String = compose.runOnUiThread {
        val active = requirePlayer()
        "videoId=$PUBLIC_VIDEO_ID title=${controller?.uiState?.value?.title} " +
            "states=${stateNames()} video=${active.videoSize.width}x${active.videoSize.height} " +
            "selectedHeight=${controller?.uiState?.value?.selectedHeight} " +
            "tracks=${trackSummary(active)} position=${active.currentPosition} " +
            "duration=${active.duration}"
    }

    private fun stateNames(): String = observedStates.joinToString(",") { stateName(it) }

    private fun stateName(state: Int?): String = when (state) {
        null -> "none"
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    private fun trackSummary(player: Player): String {
        val tracks = player.currentTracks
        return "video=${tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)}," +
            "audio=${tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)}"
    }

    private companion object {
        const val TAG = "OmaTubePlaybackSmoke"
        const val SMOKE_ARGUMENT = "omatubePlaybackSmoke"

        const val PUBLIC_VIDEO_ID = "dQw4w9WgXcQ"
        const val PUBLIC_CHANNEL_ID = "UC38IQsAvIsxxjztdMZQtwHA"
        const val PUBLIC_VIDEO_DURATION_SECONDS = 213L

        const val INITIAL_MAX_HEIGHT = 720

        const val POLL_MS = 250L
        const val DEFAULT_TIMEOUT_MS = 90_000L
        const val READY_TIMEOUT_MS = 90_000L
        const val FIRST_FRAME_TIMEOUT_MS = 30_000L
        const val ADVANCE_TIMEOUT_MS = 20_000L
        const val RESUME_ADVANCE_MS = 1_000L
        const val QUALITY_TIMEOUT_MS = 60_000L

        const val MIN_ADVANCE_MS = 3_000L
        const val PAUSE_HOLD_MS = 1_200L
        const val PAUSE_TOLERANCE_MS = 500L
        const val SEEK_FORWARD_MS = 15_000L
        const val SEEK_MARGIN_MS = 5_000L
        const val SEEK_TOLERANCE_MS = 2_000L

        const val QUALITY_POSITION_TOLERANCE_MS = 3_000L
    }
}
