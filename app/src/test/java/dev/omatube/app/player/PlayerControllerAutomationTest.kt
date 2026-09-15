package dev.omatube.app.player

import android.content.ContextWrapper
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Automation must use the real deterministic [FakePlaybackEngine] (no override)
 * and must never touch the backend. The backend fails loudly on every call.
 */
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
class PlayerControllerAutomationTest {
    private class ExplodingBackend : VideoBackend {
        var callCount = 0
            private set

        private fun fail(): Nothing {
            callCount++
            throw AssertionError("Automation mode must not call the backend")
        }

        override suspend fun resolveChannel(input: String): Channel = fail()

        override suspend fun recentVideos(channel: Channel): VideoPage = fail()

        override suspend fun olderVideos(channel: Channel, cursor: String?): VideoPage = fail()

        override suspend fun liveVideos(channel: Channel): List<Video> = fail()

        override suspend fun resolveStream(videoId: String): StreamInfo = fail()

        override suspend fun sponsorSegments(videoId: String): List<SponsorSegment> = fail()
    }

    @Test
    fun automationUsesRealFakeEngineAndNeverCallsBackend() = runTest {
        val backend = ExplodingBackend()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val video = Video(id = "AUTO0000001", channelId = "fixture", title = "Fixture video")

        val controller = PlayerController(
            context = ContextWrapper(null),
            video = video,
            initialSettings = Settings(),
            backend = backend,
            automation = true,
            onSettingsChange = {},
            onReportPlayback = { _, _, _, _ -> },
            scope = scope,
        )

        controller.start()
        advanceTimeBy(2_000)

        assertThat(backend.callCount).isEqualTo(0)
        assertThat(controller.player).isNull()
        assertThat(controller.uiState.value.positionMs).isGreaterThan(0L)
        assertThat(controller.uiState.value.title).isEqualTo("Fixture video")
        assertThat(controller.uiState.value.videoAspectRatio).isEqualTo(DEFAULT_VIDEO_ASPECT)
        assertThat(controller.uiState.value.transcriptLoading).isFalse()
        assertThat(controller.uiState.value.transcriptCues).isNotEmpty()
        assertThat(controller.uiState.value.transcriptCues.last().endMs).isEqualTo(600_000L)

        controller.release()
        scope.cancel()
    }

    @Test
    fun liveAutomationDoesNotCreateTranscript() = runTest {
        val backend = ExplodingBackend()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = PlayerController(
            context = ContextWrapper(null),
            video = Video(
                id = "AUTO0000003",
                channelId = "fixture",
                title = "Live fixture",
                isLive = true,
            ),
            initialSettings = Settings(),
            backend = backend,
            automation = true,
            onSettingsChange = {},
            onReportPlayback = { _, _, _, _ -> },
            scope = scope,
        )

        controller.start()
        advanceTimeBy(1_000)

        assertThat(backend.callCount).isEqualTo(0)
        assertThat(controller.uiState.value.transcriptLoading).isFalse()
        assertThat(controller.uiState.value.transcriptCues).isEmpty()

        controller.release()
        scope.cancel()
    }

    /**
     * H1 regression: with no user interaction the periodic collector keeps
     * saving watch time every five seconds. The injected clock is the test
     * scheduler's virtual time, so no wall-clock mismatch can occur.
     */
    @Test
    fun savesWatchTimePeriodicallyWithoutInteraction() = runTest {
        val reports = mutableListOf<WatchAccounting.Report>()
        val watch = WatchAccounting(clockMs = { testScheduler.currentTime })
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val video = Video(id = "AUTO0000002", channelId = "fixture", title = "Fixture video")

        val controller = PlayerController(
            context = ContextWrapper(null),
            video = video,
            initialSettings = Settings(),
            backend = ExplodingBackend(),
            automation = true,
            onSettingsChange = {},
            onReportPlayback = { _, position, delta, newSession ->
                reports.add(
                    WatchAccounting.Report(video.id, position, delta, newSession),
                )
            },
            scope = scope,
            watchAccounting = watch,
        )

        controller.start()
        advanceTimeBy(12_000)
        controller.release()
        scope.cancel()

        assertThat(reports).isNotEmpty()
        assertThat(reports.sumOf { it.elapsedWatchedDeltaSeconds }).isGreaterThan(0L)
        assertThat(reports.count { it.newSession }).isEqualTo(1)
    }
}
