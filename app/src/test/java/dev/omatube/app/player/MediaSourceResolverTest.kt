package dev.omatube.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Builds real [org.schabi.newpipe.extractor.stream.StreamInfo] trees and runs
 * the resolver end to end. No network is touched: the chosen sources are URL
 * HLS/DASH or an inline DASH manifest, so Media3 source construction is offline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
@OptIn(UnstableApi::class)
class MediaSourceResolverTest {
    private lateinit var resolver: MediaSourceResolver

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        resolver = MediaSourceResolver(PlaybackDataSources(context))
    }

    @Test
    fun mergesSeparateVideoAndAudio() {
        val info = streamInfo(
            videoOnlyStreams = listOf(videoOnly("136", "720p", 720)),
            audioStreams = listOf(audio("140")),
        )

        val success = success(resolver.resolve(info, maximumHeight = 0))

        assertThat(success.mediaSource).isInstanceOf(MergingMediaSource::class.java)
        assertThat(success.selectedHeight).isEqualTo(720)
        assertThat(success.isLive).isFalse()
    }

    @Test
    fun muxedOnlyProducesSingleSource() {
        val info = streamInfo(
            videoStreams = listOf(muxed("22", "720p", 720)),
        )

        val success = success(resolver.resolve(info, maximumHeight = 0))

        assertThat(success.mediaSource).isNotInstanceOf(MergingMediaSource::class.java)
        assertThat(success.selectedHeight).isEqualTo(720)
    }

    @Test
    fun muxedFallbackWhenVideoOnlyHasNoAudio() {
        val info = streamInfo(
            videoStreams = listOf(muxed("22", "720p", 720)),
            videoOnlyStreams = listOf(videoOnly("136", "720p", 720)),
        )

        val success = success(resolver.resolve(info, maximumHeight = 0))

        // The video-only rendition is preferred, but with no audio it must fall
        // back to the muxed rendition instead of playing silently.
        assertThat(success.mediaSource).isNotInstanceOf(MergingMediaSource::class.java)
        assertThat(success.selectedHeight).isEqualTo(720)
    }

    @Test
    fun videoOnlyWithoutAudioAnywhereFails() {
        val info = streamInfo(
            videoOnlyStreams = listOf(videoOnly("136", "720p", 720)),
        )

        val result = resolver.resolve(info, maximumHeight = 0)
        assertThat(result).isInstanceOf(MediaSourceResolver.Result.Failure::class.java)
        assertThat((result as MediaSourceResolver.Result.Failure).message)
            .contains("No audio stream")
    }

    @Test
    fun sameResolutionKeepsTheExactVideoOnlyCandidate() {
        // Both candidates report "720p". Selecting by resolution alone would
        // pick the muxed stream and skip the separate audio; selecting by
        // identity must pick the video-only stream and merge audio.
        val info = streamInfo(
            videoStreams = listOf(muxed("22", "720p", 720)),
            videoOnlyStreams = listOf(videoOnly("136", "720p", 720)),
            audioStreams = listOf(audio("140")),
        )

        val success = success(resolver.resolve(info, maximumHeight = 0))

        assertThat(success.mediaSource).isInstanceOf(MergingMediaSource::class.java)
    }

    @Test
    fun inlineDashManifestParsesWithoutUsingContentAsUri() {
        val info = streamInfo(
            service = ServiceList.MediaCCC,
            videoStreams = listOf(inlineDash()),
        )

        val success = success(resolver.resolve(info, maximumHeight = 0))

        assertThat(success.mediaSource).isInstanceOf(DashMediaSource::class.java)
    }

    @Test
    fun liveHlsBuildsLiveSource() {
        val info = streamInfo(
            service = ServiceList.MediaCCC,
            type = StreamType.LIVE_STREAM,
            hlsUrl = "https://example.com/live.m3u8",
        )

        val success = success(resolver.resolve(info, maximumHeight = 0))

        assertThat(success.isLive).isTrue()
        assertThat(success.mediaSource).isInstanceOf(HlsMediaSource::class.java)
    }

    // region Fixtures

    private fun success(result: MediaSourceResolver.Result): MediaSourceResolver.Result.Success {
        assertThat(result).isInstanceOf(MediaSourceResolver.Result.Success::class.java)
        return result as MediaSourceResolver.Result.Success
    }

    private fun muxed(id: String, resolution: String, height: Int): VideoStream =
        videoStream(id, resolution, height, videoOnly = false)

    private fun videoOnly(id: String, resolution: String, height: Int): VideoStream =
        videoStream(id, resolution, height, videoOnly = true)

    private fun videoStream(
        id: String,
        resolution: String,
        height: Int,
        videoOnly: Boolean,
        delivery: DeliveryMethod = DeliveryMethod.HLS,
        content: String = "https://example.com/video-$id.mp4",
        isUrl: Boolean = true,
        format: MediaFormat = MediaFormat.MPEG_4,
        manifestUrl: String? = null,
    ): VideoStream {
        val itag = ItagItem(id.toInt(), ItagItem.ItagType.VIDEO, format, resolution)
            .apply { setHeight(height) }
        return VideoStream.Builder()
            .setId(id)
            .setContent(content, isUrl)
            .setMediaFormat(format)
            .setDeliveryMethod(delivery)
            .setIsVideoOnly(videoOnly)
            .setResolution(resolution)
            .setManifestUrl(manifestUrl)
            .setItagItem(itag)
            .build()
    }

    private fun audio(id: String): AudioStream {
        val itag = ItagItem(id.toInt(), ItagItem.ItagType.AUDIO, MediaFormat.M4A, 128_000)
        return AudioStream.Builder()
            .setId(id)
            .setContent("https://example.com/audio-$id.m4a", true)
            .setMediaFormat(MediaFormat.M4A)
            .setDeliveryMethod(DeliveryMethod.HLS)
            .setAverageBitrate(128_000)
            .setItagItem(itag)
            .build()
    }

    private fun inlineDash(): VideoStream = videoStream(
        id = "1001",
        resolution = "1080p",
        height = 1080,
        // A muxed stream: the no-audio safety guard in MediaSourceResolver must
        // keep rejecting video-only streams with no companion audio, so this
        // inline-manifest path can only be reached without separate audio when
        // the stream is muxed.
        videoOnly = false,
        delivery = DeliveryMethod.DASH,
        content = MINIMAL_MPD,
        isUrl = false,
        format = MediaFormat.MPEG_4,
        manifestUrl = "https://example.com/manifest.mpd",
    )

    private fun streamInfo(
        service: StreamingService = ServiceList.YouTube,
        type: StreamType = StreamType.VIDEO_STREAM,
        videoStreams: List<VideoStream> = emptyList(),
        videoOnlyStreams: List<VideoStream> = emptyList(),
        audioStreams: List<AudioStream> = emptyList(),
        hlsUrl: String? = null,
        dashMpdUrl: String? = null,
        duration: Long = 100L,
    ): StreamInfo {
        val info = StreamInfo(
            service.serviceId,
            WATCH_URL,
            WATCH_URL,
            type,
            "vid00000000",
            "Test video",
            0,
        )
        info.videoStreams = videoStreams
        info.videoOnlyStreams = videoOnlyStreams
        info.audioStreams = audioStreams
        if (hlsUrl != null) info.hlsUrl = hlsUrl
        if (dashMpdUrl != null) info.dashMpdUrl = dashMpdUrl
        info.duration = duration
        return info
    }

    // endregion

    private companion object {
        const val WATCH_URL = "https://example.com/watch?v=vid00000000"

        val MINIMAL_MPD = """
            <?xml version="1.0" encoding="utf-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011"
                 profiles="urn:mpeg:dash:profile:isoff-on-demand:2011"
                 type="static"
                 mediaPresentationDuration="PT10S"
                 minBufferTime="PT1.5S">
              <Period>
                <AdaptationSet mimeType="video/mp4" segmentAlignment="true">
                  <Representation id="1" bandwidth="1000000" codecs="avc1.42E01E"
                                  width="1280" height="720" frameRate="30">
                    <BaseURL>video.mp4</BaseURL>
                    <SegmentBase indexRange="0-99" timescale="1000">
                      <Initialization range="0-50"/>
                    </SegmentBase>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
    }
}
