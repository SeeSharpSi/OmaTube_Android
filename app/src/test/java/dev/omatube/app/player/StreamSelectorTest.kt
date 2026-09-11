package dev.omatube.app.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamSelectorTest {
    private val videos = listOf(
        StreamSelector.Video("360p", 360, videoOnly = false),
        StreamSelector.Video("720p", 720, videoOnly = true),
        StreamSelector.Video("1080p", 1080, videoOnly = true),
        StreamSelector.Video("2160p", 2160, videoOnly = true),
    )

    @Test
    fun autoPicksBestAvailable() {
        assertThat(videos[StreamSelector.selectVideoIndex(videos, maximumHeight = 0)].height)
            .isEqualTo(2160)
    }

    @Test
    fun picksHighestAtOrBelowMaximum() {
        assertThat(videos[StreamSelector.selectVideoIndex(videos, maximumHeight = 1080)].height)
            .isEqualTo(1080)
        assertThat(videos[StreamSelector.selectVideoIndex(videos, maximumHeight = 1440)].height)
            .isEqualTo(1080)
        assertThat(videos[StreamSelector.selectVideoIndex(videos, maximumHeight = 720)].height)
            .isEqualTo(720)
    }

    @Test
    fun fallsBackToLowestWhenNothingIsAtOrBelowMaximum() {
        assertThat(videos[StreamSelector.selectVideoIndex(videos, maximumHeight = 240)].height)
            .isEqualTo(360)
    }

    @Test
    fun prefersVideoOnlyOnHeightTiesWithoutLosingIdentity() {
        val tied = listOf(
            StreamSelector.Video("1080p", 1080, videoOnly = false),
            StreamSelector.Video("1080p", 1080, videoOnly = true),
        )
        // Index 1 is the exact video-only candidate; the identical resolution
        // string must not make the selector fall back to index 0.
        assertThat(StreamSelector.selectVideoIndex(tied, maximumHeight = 0)).isEqualTo(1)
        assertThat(StreamSelector.selectVideoIndex(tied, maximumHeight = 1080)).isEqualTo(1)
    }

    @Test
    fun audioPrefersExplicitTrackThenCodecThenBitrate() {
        val audio = listOf(
            StreamSelector.Audio("en", formatRank = 2, bitrate = 128, original = true),
            StreamSelector.Audio("en", formatRank = 3, bitrate = 128, original = true),
            StreamSelector.Audio("fr", formatRank = 3, bitrate = 256, original = false),
        )
        assertThat(StreamSelector.selectAudioIndex(audio, preferredTrackId = "fr")).isEqualTo(2)
        assertThat(StreamSelector.selectAudioIndex(audio, preferredTrackId = null)).isEqualTo(2)
    }

    @Test
    fun audioFormatRankMatchesMimeTypes() {
        assertThat(StreamSelector.audioFormatRank("audio/mp4")).isEqualTo(3)
        assertThat(StreamSelector.audioFormatRank("audio/webm")).isEqualTo(2)
        assertThat(StreamSelector.audioFormatRank("audio/mpeg")).isEqualTo(1)
        assertThat(StreamSelector.audioFormatRank("audio/opus")).isEqualTo(0)
    }
}
