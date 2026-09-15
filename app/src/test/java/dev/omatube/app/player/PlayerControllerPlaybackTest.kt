package dev.omatube.app.player

import android.content.ContextWrapper
import androidx.media3.exoplayer.source.MediaSource
import com.google.common.truth.Truth.assertThat
import dev.omatube.app.backend.VideoBackend
import dev.omatube.app.model.Channel
import dev.omatube.app.model.Settings
import dev.omatube.app.model.SponsorSegment
import dev.omatube.app.model.Video
import dev.omatube.app.model.VideoPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Offline coverage for the idempotent transport added for the background
 * service. Automation mode plus an injected recording engine keeps Media3, the
 * network and the extractor completely out of the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerControllerPlaybackTest {

    private class RecordingEngine : PlaybackEngine {
        private val _snapshot = MutableStateFlow(
            PlaybackSnapshot(loading = false, durationMs = DURATION_MS),
        )
        override val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

        var playCount = 0
            private set
        var pauseCount = 0
            private set

        override fun load(mediaSource: MediaSource?, startPositionMs: Long, live: Boolean) {
            _snapshot.value = PlaybackSnapshot(
                loading = false,
                positionMs = startPositionMs.coerceAtLeast(0L),
                durationMs = DURATION_MS,
            )
        }

        override fun play() {
            playCount++
            _snapshot.update { it.copy(playing = true) }
        }

        override fun pause() {
            pauseCount++
            _snapshot.update { it.copy(playing = false) }
        }

        override fun seekTo(positionMs: Long) {
            _snapshot.update { it.copy(positionMs = positionMs) }
        }

        override fun seekToLiveEdge() = Unit

        override fun setVolume(volume: Float) = Unit

        override fun setSpeed(speed: Float) = Unit

        override fun setMaxVideoHeight(height: Int) = Unit

        override fun release() = Unit

        private companion object {
            const val DURATION_MS = 600_000L
        }
    }

    private class ExplodingBackend : VideoBackend {
        private fun fail(): Nothing = throw AssertionError("Automation must not call the backend")

        override suspend fun resolveChannel(input: String): Channel = fail()

        override suspend fun recentVideos(channel: Channel): VideoPage = fail()

        override suspend fun olderVideos(channel: Channel, cursor: String?): VideoPage = fail()

        override suspend fun liveVideos(channel: Channel): List<Video> = fail()

        override suspend fun resolveStream(videoId: String): StreamInfo = fail()

        override suspend fun sponsorSegments(videoId: String): List<SponsorSegment> = fail()
    }

    @Test
    fun playAndPauseAreIdempotent() = runTest {
        val engine = RecordingEngine()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = newController(scope, engine)

        controller.start()
        advanceTimeBy(1_000)
        assertThat(engine.playCount).isEqualTo(1)
        assertThat(controller.uiState.value.playing).isTrue()
        assertThat(controller.videoId).isEqualTo("AUTO0000009")

        controller.pause()
        controller.pause()
        assertThat(engine.pauseCount).isEqualTo(1)
        assertThat(controller.uiState.value.playing).isFalse()

        controller.play()
        controller.play()
        assertThat(engine.playCount).isEqualTo(2)
        assertThat(controller.uiState.value.playing).isTrue()

        controller.release()
        scope.cancel()
    }

    @Test
    fun pauseFlushesWatchAccountingAndIsNotDoubleReported() = runTest {
        val engine = RecordingEngine()
        val reports = mutableListOf<WatchAccounting.Report>()
        val watch = WatchAccounting(clockMs = { testScheduler.currentTime })
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = newController(
            scope = scope,
            engine = engine,
            watch = watch,
            reports = reports,
        )

        controller.start()
        advanceTimeBy(6_000)
        controller.pause()

        val afterFirstPause = reports.size
        assertThat(reports.sumOf { it.elapsedWatchedDeltaSeconds }).isGreaterThan(0L)
        assertThat(reports.any { it.newSession }).isTrue()

        controller.pause()
        assertThat(reports.size).isEqualTo(afterFirstPause)

        controller.release()
        scope.cancel()
    }

    private fun newController(
        scope: CoroutineScope,
        engine: PlaybackEngine,
        watch: WatchAccounting = WatchAccounting(),
        reports: MutableList<WatchAccounting.Report> = mutableListOf(),
    ): PlayerController = PlayerController(
        context = ContextWrapper(null),
        video = Video(id = "AUTO0000009", channelId = "fixture", title = "Fixture video"),
        initialSettings = Settings(),
        backend = ExplodingBackend(),
        automation = true,
        onSettingsChange = {},
        onReportPlayback = { id, position, delta, newSession ->
            reports.add(WatchAccounting.Report(id, position, delta, newSession))
        },
        scope = scope,
        engineOverride = engine,
        watchAccounting = watch,
    )
}
