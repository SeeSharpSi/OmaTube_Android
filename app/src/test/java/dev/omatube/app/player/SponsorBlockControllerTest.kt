package dev.omatube.app.player

import com.google.common.truth.Truth.assertThat
import dev.omatube.app.backend.VideoBackend
import dev.omatube.app.model.Channel
import dev.omatube.app.model.SponsorAction
import dev.omatube.app.model.SponsorSegment
import dev.omatube.app.model.Video
import dev.omatube.app.model.VideoPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamInfo

@kotlin.OptIn(ExperimentalCoroutinesApi::class)
class SponsorBlockControllerTest {
    private class FakeBackend(
        private val segmentsByVideo: Map<String, List<SponsorSegment>> = emptyMap(),
        private val delayMs: Long = 0L,
    ) : VideoBackend {
        var sponsorCalls = 0
            private set
        var resolveStreamCalls = 0
            private set

        override suspend fun resolveChannel(input: String): Channel = throw AssertionError("not used")

        override suspend fun recentVideos(channel: Channel): VideoPage = throw AssertionError("not used")

        override suspend fun olderVideos(channel: Channel, cursor: String?): VideoPage =
            throw AssertionError("not used")

        override suspend fun liveVideos(channel: Channel): List<Video> = throw AssertionError("not used")

        override suspend fun resolveStream(videoId: String): StreamInfo {
            resolveStreamCalls++
            throw AssertionError("not used")
        }

        override suspend fun sponsorSegments(videoId: String): List<SponsorSegment> {
            sponsorCalls++
            if (delayMs > 0L) delay(delayMs)
            return segmentsByVideo[videoId].orEmpty()
        }
    }

    @Test
    fun loadsSegmentsWhenEnabled() = runTest {
        val backend = FakeBackend(mapOf("v" to listOf(SponsorSegment(1.0, 2.0, "sponsor"))))
        val controller = SponsorBlockController(backend, this)

        controller.load("v", enabled = true)
        advanceUntilIdle()

        assertThat(controller.segments.value).hasSize(1)
        assertThat(backend.sponsorCalls).isEqualTo(1)
    }

    @Test
    fun doesNotCallBackendWhenDisabled() = runTest {
        val backend = FakeBackend(mapOf("v" to listOf(SponsorSegment(1.0, 2.0, "sponsor"))))
        val controller = SponsorBlockController(backend, this)

        controller.load("v", enabled = false)
        advanceUntilIdle()

        assertThat(controller.segments.value).isEmpty()
        assertThat(backend.sponsorCalls).isEqualTo(0)
    }

    @Test
    fun staleResponsesAreDroppedWhenVideoChanges() = runTest {
        val backend = FakeBackend(
            mapOf(
                "old" to listOf(SponsorSegment(1.0, 2.0, "sponsor")),
                "new" to listOf(SponsorSegment(3.0, 4.0, "intro")),
            ),
            delayMs = 100L,
        )
        val controller = SponsorBlockController(backend, this)

        controller.load("old", enabled = true)
        controller.load("new", enabled = true)
        advanceUntilIdle()

        assertThat(controller.segments.value.single().category).isEqualTo("intro")
    }

    @Test
    fun autoSkipGuardPreventsLoopingOnTheSameSegment() = runTest {
        val segment = SponsorSegment(10.0, 20.0, "sponsor")
        val backend = FakeBackend(mapOf("v" to listOf(segment)))
        val controller = SponsorBlockController(backend, this)
        controller.load("v", enabled = true)
        advanceUntilIdle()

        val actions = mapOf("sponsor" to SponsorAction.AUTO)
        assertThat(controller.autoSkipTarget(12.0, actions, enabled = true)).isEqualTo(20.1)
        assertThat(controller.autoSkipTarget(12.0, actions, enabled = true)).isNull()

        // Leaving and re-entering the segment allows another skip.
        controller.autoSkipTarget(50.0, actions, enabled = true)
        assertThat(controller.autoSkipTarget(12.0, actions, enabled = true)).isEqualTo(20.1)
    }

    @Test
    fun cancelStopsPendingFetch() = runTest {
        val backend = FakeBackend(mapOf("v" to listOf(SponsorSegment(1.0, 2.0, "sponsor"))), delayMs = 100L)
        val controller = SponsorBlockController(backend, this)

        controller.load("v", enabled = true)
        controller.cancel()
        advanceUntilIdle()

        assertThat(controller.segments.value).isEmpty()
    }
}
